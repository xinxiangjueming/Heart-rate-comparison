package com.example.heartratecomparison.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.heartratecomparison.MainActivity
import com.example.heartratecomparison.R
import com.example.heartratecomparison.data.CsvRecorder
import com.example.heartratecomparison.model.DeviceState
import com.example.heartratecomparison.model.UiDeviceState
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class HeartRateService : Service() {

    // ── BLE 高优先级：单线程调度器，保证 GATT 操作顺序 ─────
    @OptIn(ExperimentalCoroutinesApi::class)
    private val bleDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val bleScope = CoroutineScope(bleDispatcher + SupervisorJob())

    // ── 通用后台：周期任务（轻量，保留在 Main） ────────────
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var connector: BluetoothConnector
    private lateinit var csvRecorder: CsvRecorder
    private val deviceStates = ConcurrentHashMap<String, DeviceState>()
    private val connectionOrder = CopyOnWriteArrayList<String>()
    private val deviceColors = ConcurrentHashMap<String, Int>()
    private var isRecording = false
    private var isScanning = false
    private var scanner: BluetoothScanner? = null
    private var scanTimeoutJob: Job? = null

    companion object {
        const val TAG = "HeartRateService"
        const val CHANNEL_ID = "heart_rate_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_SERVICE"

        private val _globalUiState = MutableStateFlow(UiState())
        val globalUiState = _globalUiState.asStateFlow()

        data class UiState(
            val devices: Map<String, UiDeviceState> = emptyMap(),
            val connectionOrder: List<String> = emptyList(),
            val deviceColors: Map<String, Int> = emptyMap(),
            val isRecording: Boolean = false,
            val isScanning: Boolean = false
        )

        /**
         * HyperOS 公平运行内存适配：系统预警时释放内存
         * 由 MemoryReceiver 调用
         */
        fun releaseMemory() {
            Log.w(TAG, "releaseMemory: 系统内存预警，释放缓存")
            // 清除所有设备的心率历史数据（最大的内存消耗）
            _globalUiState.update { state ->
                state.copy(
                    devices = state.devices.mapValues { (_, device) ->
                        device.copy(heartRateHistory = emptyList())
                    }
                )
            }
            // 建议 GC 回收
            Runtime.getRuntime().gc()
            Log.i(TAG, "releaseMemory: 已释放心率历史数据并触发 GC")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务 onCreate")
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_service_started)))
            Log.d(TAG, "前台服务已启动")
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少通知权限，前台服务启动失败，服务可能随时被终止", e)
        }

        connector = BluetoothConnector(
            context = this,
            scope = bleScope,
            onDeviceConnected = { addr ->
                // BLE 线程 → 切回主线程更新 UI 状态
                serviceScope.launch {
                    deviceStates[addr]?.isConnected = true
                    emitState()
                }
            },
            onDeviceDisconnected = { addr ->
                serviceScope.launch {
                    deviceStates[addr]?.isConnected = false
                    emitState()
                }
            },
            onHeartRateReceived = { addr, hr ->
                // CSV 录制：Channel 式发送，零阻塞
                if (isRecording) {
                    csvRecorder.onHeartRate(addr, hr)
                }
                // 切回主线程更新业务状态 & UI
                serviceScope.launch {
                    deviceStates[addr]?.heartRate = hr
                    if (isRecording) {
                        val history = deviceStates[addr]?.heartRateHistory ?: mutableListOf()
                        history.add(hr)
                        if (history.size > 300) history.removeAt(0)
                    }
                    emitState()
                }
            },
            onBatteryLevelReceived = { addr, level ->
                serviceScope.launch {
                    deviceStates[addr]?.batteryLevel = level
                    emitState()
                }
            }
        )

        csvRecorder = CsvRecorder(this)

        serviceScope.launch {
            while (isActive) {
                delay(30_000)
                val toRemove = deviceStates.filterValues { !it.isConnected }.keys
                toRemove.forEach {
                    deviceStates.remove(it)
                    connectionOrder.remove(it)
                    deviceColors.remove(it)
                }
                emitState()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "收到命令: ${intent?.action}")
        when (intent?.action) {
            "TOGGLE_SCAN" -> toggleScan()
            "START_RECORDING" -> startRecording()
            "STOP_RECORDING" -> stopRecording()
            "CONNECT_DEVICE" -> {
                val address = intent.getStringExtra("device_address") ?: return START_STICKY
                deviceStates[address]?.device?.let { connector.connect(it) }
            }
            "DISCONNECT_DEVICE" -> {
                val address = intent.getStringExtra("device_address") ?: return START_STICKY
                connector.disconnect(address)
                deviceStates[address]?.isConnected = false
                deviceStates[address]?.heartRateHistory?.clear()
                emitState()
            }
            "STOP_SERVICE" -> stopSelf()
        }
        return START_STICKY
    }

    private fun toggleScan() {
        if (isScanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (isScanning) return
        isScanning = true
        emitState()

        val newScanner = BluetoothScanner(this)
        scanner = newScanner
        newScanner.startScan(
            onDeviceDiscovered = { newDevice ->
                val addr = newDevice.device.address
                if (!deviceStates.containsKey(addr)) {
                    deviceStates[addr] = newDevice
                    if (!deviceColors.containsKey(addr)) {
                        deviceColors[addr] = connectionOrder.size
                        connectionOrder.add(addr)
                    }
                    Log.d(TAG, "新设备加入: ${newDevice.device.name ?: addr} ($addr)")
                    emitState()
                }
            },
            onScanFailed = { errorCode ->
                Log.e(TAG, "扫描失败: $errorCode")
            }
        )

        scanTimeoutJob?.cancel()
        scanTimeoutJob = serviceScope.launch {
            delay(15_000)
            stopScan()
        }
    }

    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        scanner?.stopScan()
        scanner = null
        isScanning = false
        emitState()
    }

    private fun startRecording() {
        if (isRecording) return
        deviceStates.values.forEach { it.heartRateHistory.clear() }
        val connectedDevices = deviceStates.filter { it.value.isConnected }
        val connectedAddresses = connectedDevices.keys.toList()
        val addressToName = connectedDevices.mapValues { (_, ds) ->
            ds.device.name ?: ds.device.address
        }
        serviceScope.launch {
            if (isRecording) return@launch
            try {
                csvRecorder.start(connectedAddresses, addressToName)
                // 文件已创建 & 表头已写入，安全标记录制开始
                isRecording = true
                updateNotification(getString(R.string.notif_recording))
            } catch (e: Exception) {
                Log.e(TAG, "CSV 录制启动失败", e)
                isRecording = false
                updateNotification(getString(R.string.notif_record_failed))
            }
            emitState()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        serviceScope.launch {
            // suspend — 等待 IO 线程完成文件 flush/close，不阻塞主线程
            csvRecorder.stop()
            // 文件已落盘，切回主线程清理状态
            deviceStates.values.forEach { it.heartRateHistory.clear() }
            isRecording = false
            updateNotification(getString(R.string.notif_record_stopped))
            emitState()
        }
    }

    private fun emitState() {
        val uiDevices = deviceStates.mapValues { (addr, ds) ->
            UiDeviceState(
                address = addr,
                name = ds.device.name ?: addr,
                isConnected = ds.isConnected,
                heartRate = ds.heartRate,
                batteryLevel = ds.batteryLevel,
                heartRateHistory = ds.heartRateHistory.toList()
            )
        }
        _globalUiState.update {
            it.copy(
                devices = uiDevices,
                connectionOrder = connectionOrder.toList(),
                deviceColors = deviceColors.toMap(),
                isRecording = isRecording,
                isScanning = isScanning
            )
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "无法更新通知", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, HeartRateService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Heart Rate Comparison")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "服务 onDestroy")
        scanTimeoutJob?.cancel()
        scanner?.let {
            it.stopScan()
            scanner = null
        }
        if (isRecording) {
            // onDestroy 在主线程，csvRecorder.stop() 是 suspend
            // 用 runBlocking 等待文件关闭（onDestroy 是生命周期终结，短暂阻塞可接受）
            runBlocking { csvRecorder.stop() }
            isRecording = false
        }
        bleScope.cancel()
        serviceScope.cancel()
        connector.disconnectAll()
        super.onDestroy()
    }
}
