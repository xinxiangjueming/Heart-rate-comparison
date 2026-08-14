# 线程架构重构方案

## 目标架构

```
┌─────────────────────────────────────────────────────┐
│  主线程 (Dispatchers.Main)                            │
│  · UI 渲染 (Compose)                                  │
│  · 用户交互逻辑                                        │
│  · 业务状态维护 (emitState / StateFlow)                │
│  · 无任何阻塞任务                                      │
├─────────────────────────────────────────────────────┤
│  BLE 高优先级异步 (Dispatchers.Default.limitedParallelism(1)) │
│  · GATT 回调处理                                      │
│  · 心率数据解析 & 状态更新                              │
│  · 设备连接/断连状态管理                                │
│  · 最终通过 withContext(Main) 刷新 UI                  │
├─────────────────────────────────────────────────────┤
│  IO 低优先级异步 (Dispatchers.IO)                      │
│  · CSV 文件写入 (CsvRecorder)                         │
│  · CSV 文件读取 (CsvChartDialog / HistoryScreen)      │
│  · 目录创建、文件开关                                   │
└─────────────────────────────────────────────────────┘
```

## 变更清单

### 1. HeartRateService.kt — 三调度器架构

**现状**: 单一 `serviceScope = CoroutineScope(Dispatchers.Main + Job())`

**改为**:
```kotlin
// BLE 专用：单线程，保证 GATT 操作顺序性
private val bleDispatcher = Dispatchers.Default.limitedParallelism(1)
private val bleScope = CoroutineScope(bleDispatcher + SupervisorJob())

// 通用后台：周期清理等轻量任务
private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
```

改动点:
- `BluetoothConnector` 注入 `bleScope` 替代 `serviceScope`
- `CsvRecorder` 不再注入外部 scope，自己管理 IO scope
- `stopRecording()` 中 `csvRecorder.stop()` 在 IO 上等待完成后，切回 Main 清理状态
- `onDestroy()` 同时取消 `bleScope` 和 `serviceScope`
- `onHeartRateReceived` 中 CSV 录制调用保持 Channel 式发送（非阻塞）

### 2. BluetoothConnector.kt — BLE 回调全部走 bleScope

**现状**: GATT 回调在主线程，用 `scope.launch(Dispatchers.Main)` 冗余回到主线程

**改为**:
- 注入 `bleScope`（BLE 单线程调度器）
- GATT 回调处理全部在 BLE 线程上执行
- 状态更新（`onDeviceConnected` 等）在 BLE 线程
- 只有 `emitState()` 最终在主线程（通过 HeartRateService 的回调链保证）

具体:
- `onConnectionStateChange`: `scope.launch { ... }` (BLE 线程)
- `onServicesDiscovered`: `scope.launch { ... }` (BLE 线程)
- `onCharacteristicChanged`: `scope.launch { ... }` (BLE 线程，解析也在同线程)
- `handleBatteryRead`: `scope.launch { ... }` (BLE 线程)

**注意**: BluetoothConnector 中的回调只是通知 HeartRateService，实际 `emitState()` 是在 HeartRateService 的 lambda 中调用的。由于 BLE 回调 -> HeartRateService lambda 都在 BLE 线程，而 `emitState()` 需要在主线程（更新 StateFlow），所以 HeartRateService 中的 `onDeviceConnected` / `onDeviceDisconnected` / `onHeartRateReceived` / `onBatteryLevelReceived` 回调需要用 `serviceScope.launch` 包装来切回主线程。

### 3. CsvRecorder.kt — Channel + 独立 IO 协程

**现状**: 全部在主线程执行文件 IO（write/flush/close）

**改为**:
```kotlin
class CsvRecorder(private val context: Context) {
    // 独立 IO scope，不依赖外部注入
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Channel 消除主线程与 IO 线程的竞态
    private val dataChannel = Channel<HrSample>(Channel.UNLIMITED)

    // 所有 mutable 状态只在 IO 协程中访问
    private var writer: BufferedWriter? = null
    private val latestHr = mutableMapOf<String, Int?>()
    // ...

    init {
        ioScope.launch { dataProcessingLoop() }
    }

    // 主线程调用：非阻塞，仅发送到 Channel
    fun onHeartRate(addr: String, hr: Int) {
        dataChannel.trySend(HrSample(addr, hr))
    }

    // IO 协程中顺序处理
    private tailrec suspend fun dataProcessingLoop() {
        when (val msg = dataChannel.receive()) {
            is HrSample -> { /* 更新 latestHr, 检查秒切换, writeRow */ }
            Stop -> { /* flush, close, return */ }
        }
        dataProcessingLoop()
    }

    // suspend：等待 IO 协程完成最后写入
    suspend fun stop() { dataChannel.send(Stop); flushJob?.join() }
}
```

**优势**:
- `onHeartRate()` 零阻塞（`trySend` 无锁无等待）
- 所有 mutable 状态单线程访问，无需 synchronized
- 主线程与 IO 线程无竞态条件
- `stop()` 保证数据落盘后再返回

### 4. CsvChartDialog.kt — 异步解析 CSV

**现状**: `remember(file) { parseCsv(file) }` 在主线程同步读文件

**改为**:
```kotlin
var parsed by remember { mutableStateOf<CsvParsed?>(null) }
var isLoading by remember { mutableStateOf(true) }
LaunchedEffect(file) {
    isLoading = true
    parsed = withContext(Dispatchers.IO) { parseCsv(file) }
    isLoading = false
}
// isLoading 时显示加载指示器
```

### 5. HistoryScreen.kt — 异步文件列表

**现状**: `comparisonDir.listFiles()` 在 `remember` 和 `refreshFileList()` 中同步执行

**改为**:
```kotlin
var fileList by remember { mutableStateOf(emptyList<File>()) }
var refreshTrigger by remember { mutableIntStateOf(0) }
LaunchedEffect(refreshTrigger) {
    fileList = withContext(Dispatchers.IO) {
        // listFiles + sort
    }
}
fun refreshFileList() { refreshTrigger++ }
```

## 线程安全分析

| 资源 | 改前 | 改后 |
|------|------|------|
| deviceStates (ConcurrentHashMap) | 主线程读写 | BLE 线程写, 主线程读 (emitState) → **仍需 ConcurrentHashMap** |
| DeviceState 可变字段 | 主线程读写 | BLE 线程写, 主线程读 → **主线程读可能看到旧值，但对 UI 来说可接受（下一帧刷新）** |
| CsvRecorder writer/latestHr | 主线程 + synchronized | IO 单线程，无需 synchronized |
| StateFlow (_globalUiState) | 主线程更新 | 主线程更新（回调切回 Main）→ 线程安全 |

## 改动文件汇总

| 文件 | 改动程度 | 说明 |
|------|---------|------|
| HeartRateService.kt | 中 | 新增 bleScope/bleDispatcher，回调切回 Main，onDestroy 双取消 |
| BluetoothConnector.kt | 中 | scope 含义改为 BLE scope，去掉冗余 Dispatchers.Main |
| CsvRecorder.kt | **大** | 整体重写：Channel + IO scope + 消费者协程 |
| CsvChartDialog.kt | 小 | remember → LaunchedEffect + withContext(IO) |
| HistoryScreen.kt | 小 | listFiles → LaunchedEffect + withContext(IO) |
