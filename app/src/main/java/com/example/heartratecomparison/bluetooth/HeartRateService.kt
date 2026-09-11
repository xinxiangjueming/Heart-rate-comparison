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

    // ── 增量快照缓存（仅主线程访问）：未变化设备复用上次实例，让下游按值相等跳过重组 ──
    /** 待发射合并窗口（事件驱动节流）：非空表示窗口已开启，期间的新变化合并进同一次发射 */
    private var hrEmitJob: Job? = null
    private val uiDeviceCache = HashMap<String, UiDeviceState>()
    private val uiHistoryCache = HashMap<String, List<Int>>()

    companion object {
        const val TAG = "HeartRateService"
        const val CHANNEL_ID = "heart_rate_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "STOP_SERVICE"

        /** 心率状态合并发射窗口：首个变化后延迟该时长统一发射，窗口内变化合并，空闲零轮询 */
        private const val THROTTLE_MS = 120L
        /** 单设备内存历史上限 */
        private const val HISTORY_MAX = 300

        /** 供静态 releaseMemory() 访问实例（onCreate 赋值 / onDestroy 置空） */
        @Volatile
        private var instance: HeartRateService? = null

        /** 卡片状态流：设备名/连接/心率/电量。UiDeviceState 不含历史字段（全稳定类型，下游可按值跳过重组） */
        private val _globalUiState = MutableStateFlow(UiState())
        val globalUiState = _globalUiState.asStateFlow()

        /** 图表历史流：设备地址 → 心率历史。与卡片状态分流，StateFlow 按内容去重：
         *  历史内容未变（如非录制期心率刷新）时不发射，图表侧完全无重组 */
        private val _globalHistoryState = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
        val globalHistoryState = _globalHistoryState.asStateFlow()

        data class UiState(
            val devices: Map<String, UiDeviceState> = emptyMap(),
            val connectionOrder: List<String> = emptyList(),
            val deviceColors: Map<String, Int> = emptyMap(),
            val isRecording: Boolean = false,
            val isScanning: Boolean = false
        )

        /**
         * HyperOS 公平运行内存适配：系统预警时释放内存
         * 由 MemoryReceiver 调用（HandlerThread 线程）
         * 必须切到主线程清理数据源，避免与主线程的 history 读写竞争
         */
        fun releaseMemory() {
            Log.w(TAG, "releaseMemory: 系统内存预警，释放缓存")
            instance?.let { svc ->
                svc.serviceScope.launch {
                    svc.deviceStates.values.forEach { ds ->
                        ds.heartRateHistory.clear()
                        ds.dirty = true
                    }
                    svc.emitState()   // 立即发射空历史
                }
            }
            Runtime.getRuntime().gc()
            Log.i(TAG, "releaseMemory: 已释放心率历史数据并触发 GC")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务 onCreate")
        instance = this
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
                    deviceStates[addr]?.let {
                        it.isConnected = true
                        it.dirty = true
                    }
                    emitState()   // 即时，不走节流
                }
            },
            onDeviceDisconnected = { addr ->
                serviceScope.launch {
                    deviceStates[addr]?.let { ds ->
                        ds.isConnected = false
                        ds.heartRateHistory.clear()   // 立即释放 ≤300 点内存（保留条目可重连）
                        ds.dirty = true
                    }
                    emitState()   // 即时，不走节流
                }
            },
            onHeartRateReceived = { addr, hr ->
                // CSV 录制：Channel 式发送，零阻塞
                if (isRecording) {
                    csvRecorder.onHeartRate(addr, hr)
                }
                // 切回主线程更新业务状态 & UI（节流合并发射）
                serviceScope.launch {
                    deviceStates[addr]?.let { ds ->
                        ds.heartRate = hr
                        ds.dirty = true
                        if (isRecording) {
                            ds.heartRateHistory.addLast(hr)
                            if (ds.heartRateHistory.size > HISTORY_MAX) {
                                ds.heartRateHistory.removeFirst()   // ArrayDeque O(1)
                            }
                        }
                    }
                    markHrDirty()
                }
            },
            onBatteryLevelReceived = { addr, level ->
                serviceScope.launch {
                    deviceStates[addr]?.let {
                        it.batteryLevel = level
                        it.dirty = true
                    }
                    // 录制中：电量读数同步给 CsvRecorder（落库去重交给 5s 强刷/stop）
                    if (isRecording) {
                        csvRecorder.recordBattery(addr, level)
                    }
                    emitState()   // 电池事件稀少，保持即时
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
                    uiDeviceCache.remove(it)
                    uiHistoryCache.remove(it)
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
                deviceStates[address]?.let { ds ->
                    ds.isConnected = false
                    ds.heartRateHistory.clear()
                    ds.dirty = true
                }
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
        deviceStates.values.forEach { it.heartRateHistory.clear(); it.dirty = true }
        val connectedDevices = deviceStates.filter { it.value.isConnected }
        val connectedAddresses = connectedDevices.keys.toList()
        val addressToName = connectedDevices.mapValues { (_, ds) ->
            ds.device.name ?: ds.device.address
        }
        // 起始电量：录制开始时各连接设备当前已知电量（可能为 null = 设备无电量服务）
        val initialBattery = connectedDevices.mapValues { (_, ds) -> ds.batteryLevel }
        serviceScope.launch {
            if (isRecording) return@launch
            try {
                csvRecorder.start(connectedAddresses, addressToName, initialBattery)
                // 会话已创建，安全标记录制开始
                isRecording = true
                startBatteryRefreshLoop()
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
            // suspend — 等待 IO 线程完成最后落库（含结束电量 + 最终汇总），不阻塞主线程
            csvRecorder.stop()
            stopBatteryRefreshLoop()
            // 数据已落库，切回主线程清理状态
            deviceStates.values.forEach { it.heartRateHistory.clear(); it.dirty = true }
            isRecording = false
            updateNotification(getString(R.string.notif_record_stopped))
            emitState()
        }
    }

    // ── 录制中周期电量刷新：每 30 秒重读一次连接设备电量 ──
    private var batteryRefreshJob: Job? = null

    private fun startBatteryRefreshLoop() {
        batteryRefreshJob?.cancel()
        batteryRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(30_000)
                if (isRecording) {
                    val addresses = deviceStates.filter { it.value.isConnected }.keys.toList()
                    addresses.forEach { addr ->
                        bleScope.launch { connector.refreshBattery(addr) }
                    }
                }
            }
        }
    }

    private fun stopBatteryRefreshLoop() {
        batteryRefreshJob?.cancel()
        batteryRefreshJob = null
    }

    /**
     * 心率状态节流发射（事件驱动）：首个变化开启一个 THROTTLE_MS 合并窗口，
     * 窗口结束时统一 emitState 一次；窗口已开启则直接返回（变化合并）。
     * 无心率变化时零协程、零唤醒（替代原 120ms 常驻轮询循环）。
     */
    private fun markHrDirty() {
        if (hrEmitJob?.isActive == true) return
        hrEmitJob = serviceScope.launch {
            delay(THROTTLE_MS)
            hrEmitJob = null
            emitState()
        }
    }

    /**
     * 双流增量快照（仅主线程调用）：
     *  - 卡片流：仅 dirty 设备重建 UiDeviceState（已不含历史字段），其余复用缓存实例；
     *  - 历史流：仅 dirty 设备做 history.toList()，其余复用上次列表实例；
     *    StateFlow 按内容去重，历史内容未变时图表侧不发射、不重组。
     */
    private fun emitState() {
        val uiDevices = HashMap<String, UiDeviceState>(deviceStates.size)
        val uiHistories = HashMap<String, List<Int>>(deviceStates.size)
        deviceStates.forEach { (addr, ds) ->
            val prevDevice = uiDeviceCache[addr]
            val prevHistory = uiHistoryCache[addr]
            if (ds.dirty || prevDevice == null || prevHistory == null) {
                uiDevices[addr] = UiDeviceState(
                    address = addr,
                    name = ds.device.name ?: addr,
                    isConnected = ds.isConnected,
                    heartRate = ds.heartRate,
                    batteryLevel = ds.batteryLevel
                )
                uiHistories[addr] = ds.heartRateHistory.toList()
                ds.dirty = false
            } else {
                uiDevices[addr] = prevDevice
                uiHistories[addr] = prevHistory
            }
        }
        uiDeviceCache.clear()
        uiDeviceCache.putAll(uiDevices)
        uiHistoryCache.clear()
        uiHistoryCache.putAll(uiHistories)
        _globalUiState.update {
            it.copy(
                devices = uiDevices,
                connectionOrder = connectionOrder.toList(),
                deviceColors = deviceColors.toMap(),
                isRecording = isRecording,
                isScanning = isScanning
            )
        }
        _globalHistoryState.value = uiHistories
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
        if (instance === this) instance = null
        scanTimeoutJob?.cancel()
        scanner?.let {
            it.stopScan()
            scanner = null
        }
        if (isRecording) {
            // onDestroy 在主线程，csvRecorder.stop() 是 suspend
            // 用 runBlocking 等待数据落库（onDestroy 是生命周期终结，短暂阻塞可接受）：
            // 落库量仅为最后 ≤5 秒缓冲 + 结束电量快照 + 汇总更新（2~3 条小事务），
            // 正常 <100ms，远低于服务销毁场景的系统容忍上限，不会 ANR
            runBlocking { csvRecorder.stop() }
            isRecording = false
        }
        stopBatteryRefreshLoop()
        bleScope.cancel()
        serviceScope.cancel()
        connector.disconnectAll()
        super.onDestroy()
    }
}
