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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HeartRateService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var connector: BluetoothConnector
    private lateinit var csvRecorder: CsvRecorder
    private val deviceStates = mutableMapOf<String, DeviceState>()
    private val connectionOrder = mutableListOf<String>()
    private val deviceColors = mutableMapOf<String, Int>()
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
            scope = serviceScope,
            onDeviceConnected = { addr ->
                deviceStates[addr]?.isConnected = true
                emitState()
            },
            onDeviceDisconnected = { addr ->
                deviceStates[addr]?.isConnected = false
                emitState()
            },
            onHeartRateReceived = { addr, hr ->
                deviceStates[addr]?.heartRate = hr
                if (isRecording) {
                    val history = deviceStates[addr]?.heartRateHistory ?: mutableListOf()
                    history.add(hr)
                    if (history.size > 300) history.removeAt(0)
                    csvRecorder.onHeartRate(addr, hr)
                }
                emitState()
            }
        )

        csvRecorder = CsvRecorder(serviceScope, this)

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
                    Log.d(TAG, "新设备加入: ${newDevice.device.name} ($addr)")
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
        val connectedAddresses = deviceStates.filter { it.value.isConnected }.keys.toList()
        csvRecorder.start(connectedAddresses) { error ->
            Log.e(TAG, "CSV error: $error")
            isRecording = false
            updateNotification(getString(R.string.notif_record_failed))
            emitState()
        }
        isRecording = true
        updateNotification(getString(R.string.notif_recording))
        emitState()
    }

    private fun stopRecording() {
        if (!isRecording) return
        runBlocking { csvRecorder.stop() }
        deviceStates.values.forEach { it.heartRateHistory.clear() }
        isRecording = false
        updateNotification(getString(R.string.notif_record_stopped))
        emitState()
    }

    private fun emitState() {
        val uiDevices = deviceStates.mapValues { (addr, ds) ->
            UiDeviceState(
                address = addr,
                name = ds.device.name ?: addr,
                isConnected = ds.isConnected,
                heartRate = ds.heartRate,
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
        scanner?.stopScan()
        if (isRecording) {
            runBlocking { csvRecorder.stop() }
            isRecording = false
        }
        serviceScope.cancel()
        connector.disconnectAll()
        super.onDestroy()
    }
}