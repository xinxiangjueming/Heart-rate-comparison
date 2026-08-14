# 心率对比工具

一款 Android 应用，用于同时连接多个蓝牙心率设备，实时对比心率数据，数据以 Room 数据库形式保存在应用私有目录，可导出带统计总结的 CSV 文件。

## 功能

- **开屏动画**：粒子聚合心形 + ECG 心电图描线动画，科技感启动体验
- **多设备连接**：同时连接多个蓝牙心率传感器
- **设备电量读取**：自动读取 BLE 设备电池电量，设备卡片中以自绘电池图标 + 百分比显示
- **自绘设备图标**：电池图标随电量动态填充（绿 >50% / 橙 20-50% / 红 <20%），心率图标为心形 + ECG 脉搏线
- **实时图表**：实时显示各设备心率曲线，带渐变填充色，限帧 + 降采样保证流畅
- **Room 数据存储**：心率数据按秒聚合写入 Room 数据库（应用私有目录，无需任何存储权限）
- **自动保存**：每 5 秒强制落库（含当前秒缓冲），后台被杀最多丢 ≤5 秒数据
- **电量快照**：记录起始电量 + 录制中每 30 秒自动重读 + 结束电量，电量变化自动记录
- **会话总结**：每次记录自动汇总每设备有效样本数、平均心率、时长（存 Room，导出时随 CSV 输出）
- **数据记录**：点击记录图标开始，**长按 3 秒停止**（带进度环反馈，防误触）
- **历史查看**：浏览历史记录（单击查看图表、双击分享、长按删除）
- **CSV 导出**：分享时动态生成 CSV 文件（UTF-8 BOM，中文设备名不乱码），末尾附 `Summary:` 汇总区（英文标签：Samples / Start Battery / End Battery / Avg HR / Duration / Start/End Time）
- **图表回放**：支持缩放、滑动手势查看历史数据，曲线带渐变填充
- **多语言支持**：简体中文、English、日本語、한국어、Deutsch、Français、繁體中文（7 种语言）
- **深色/浅色主题**：跟随系统主题自动切换，深色下图表区与背景分层清晰
- **大屏适配**：支持折叠屏、平板横竖屏自适应布局；历史页大屏分栏（左列表 + 右图表）
- **HyperOS 适配**：适配小米折叠屏连续性、Flip 外屏、沉浸式状态栏、系统内存预警释放

## 系统要求

- Android 8.0+（API 26）
- 蓝牙 4.0+（BLE）

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 持久化 | **Room 数据库**（SQLite） |
| 蓝牙通信 | Android BLE API |
| 异步处理 | Kotlin Coroutines + Flow |
| 最低 SDK | 26（Android 8.0） |
| 目标 SDK | 34（Android 14） |

## 技术架构

### 整体架构

采用 **单 Activity + Compose** 架构，`HeartRateService` 前台服务管理蓝牙连接和数据采集，UI 层通过 `StateFlow` 响应式更新（心率状态 **120ms 节流合并发射 + 增量快照**，避免高频全量拷贝）。

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                │
│  SplashScreen ── MainScreen ── HistoryScreen        │
│  LeftPanel / DeviceItem / MultiHeartRateChart       │
│       │                                             │
│       │ collectAsState()                            │
│       ▼                                             │
│  HeartRateService.globalUiState (StateFlow)         │
├─────────────────────────────────────────────────────┤
│                 Service Layer                        │
│  HeartRateService (Foreground Service)               │
│       ├── BluetoothScanner (BLE 扫描)                │
│       ├── BluetoothConnector (GATT + 电量读取)        │
│       ├── HeartRateParser (数据解析)                  │
│       └── CsvRecorder (Room 写入，5s 强刷 + 汇总)     │
├─────────────────────────────────────────────────────┤
│                 Data Layer (Room)                    │
│  HeartRateDatabase ── RecordDao                      │
│       ├── sessions（会话 + 汇总字段）                 │
│       ├── hr_samples（心率样本，唯一索引防重复）       │
│       └── battery_snapshots（电量快照）               │
└─────────────────────────────────────────────────────┘
```

### 核心模块说明

| 模块 | 文件 | 职责 |
|------|------|------|
| **蓝牙扫描** | `BluetoothScanner.kt` | BLE 设备发现，扫描附近心率传感器 |
| **蓝牙连接** | `BluetoothConnector.kt` | GATT 连接管理，订阅心率通知，读取/周期重读电量 |
| **数据解析** | `HeartRateParser.kt` | 解析 BLE Heart Rate Profile 原始字节数据 |
| **前台服务** | `HeartRateService.kt` | 多设备连接生命周期、全局状态（节流发射）、录制控制、30s 电量刷新循环 |
| **数据录制** | `CsvRecorder.kt` | 秒级聚合 + 5 秒强刷写 Room；电量去重快照；会话汇总统计 |
| **数据库** | `HeartRateDatabase.kt` / `RecordDao.kt` | Room 实例（私有目录）与 DAO（含 Migration v1→v2） |
| **数据模型** | `RecordSession.kt` / `HrSample.kt` / `BatterySnapshot.kt` | 会话、心率样本、电量快照实体 |
| **CSV 导出** | `CsvExporter.kt` | 会话 → 带 Summary 汇总区的 CSV（UTF-8 BOM） |
| **设备状态** | `DeviceState.kt` / `UiDeviceState.kt` | 设备连接状态、心率历史（ArrayDeque）与 UI 数据模型 |

### UI 模块说明

| 模块 | 文件 | 职责 |
|------|------|------|
| **开屏动画** | `SplashScreen.kt` | 粒子聚合心形 + ECG 心电图描线启动动画 |
| **主页面** | `MainScreen.kt` | 横竖屏自适应布局（横屏避开系统栏，不侵入状态栏） |
| **左侧面板** | `LeftPanel.kt` | 三图标操作区（历史/搜索/记录）+ 设备列表 |
| **设备卡片** | `DeviceItem.kt` | 自绘电池图标 + 心率图标；连接后信息行淡入展开动画 |
| **实时图表** | `MultiHeartRateChart.kt` | Canvas 自绘多设备心率曲线（限帧 + 降采样 + 渐变填充） |
| **历史页面** | `HistoryScreen.kt` | Room 会话列表，单击看图表 / 双击分享 CSV / 长按删除 |
| **CSV 图表** | `CsvChartDialog.kt` | 历史数据回放（Room 数据源，缩放/平移/渐变填充），全屏查看时隐藏系统栏 |
| **系统栏** | `NavigationBarHelper.kt` | edge-to-edge 沉浸统一处理（Android 10~14 小白条适配） |
| **主题** | `Theme.kt` / `Color.kt` / `Type.kt` | Material 3 主题、10 色图表调色盘、动态屏幕圆角 |

### 数据存储与安全

- **私有目录**：Room 数据库位于 `/data/data/<包名>/databases/`，全程无需任何存储权限
- **自动保存**：秒切换落库 + 独立协程**每 5 秒强制落库**（含当前秒缓冲 + 汇总更新 + 电量快照），进程被杀最多丢 ≤5 秒
- **同秒去重**：`hr_samples` 唯一索引 `(sessionId, second, deviceAddress)` + REPLACE 写入，5 秒强刷与秒切换写同一行不会产生重复
- **电量快照**：起始电量（录制开始时）+ 录制中每 30 秒周期重读 + 结束电量（停止时强制写入）；电量变化时自动记录（同值去重）
- **会话总结**：每设备有效样本数（JSON）、全局平均心率，每 5 秒随强刷更新、停止时最终修正
- **数据不丢**：录制 Channel 保持 UNLIMITED，积压监控仅告警不改行为

### 交互说明（主界面三图标）

| 图标 | 位置 | 行为 |
|------|------|------|
| 🕘 历史（蓝） | 最左 | 点击进入历史记录 |
| 🔍 搜索（红） | 中间 | 点击扫描；扫描中图标变进度环 |
| ⭕ 记录（红） | 最右 | 点击开始记录（无设备时置灰）；**记录中长按 3 秒停止**（按住显示进度环，短按不触发） |

### 数据流

```
蓝牙设备 ──BLE──▶ BluetoothConnector
                      │
                      ├─▶ HeartRateParser.parse() ──▶ 心率值
                      └─▶ Battery Level Read ──▶ 电量（连接时 + 每 30s 重读）
                              │
                              ▼
                      HeartRateService (节流合并 emitState)
                              │
                    ┌─────────┼─────────┐
                    ▼         ▼         ▼
                 MainScreen  CsvRecorder  Notification
                 (实时图表)  (Room 写入)   (前台通知)
                              │
                              ▼
              HeartRateDatabase (sessions / hr_samples / battery_snapshots)
```

### 项目结构

```
app/src/main/java/com/example/heartratecomparison/
├── bluetooth/
│   ├── BluetoothScanner.kt     # BLE 扫描（15 秒超时，过滤心率服务 UUID）
│   ├── BluetoothConnector.kt   # GATT 连接 + 心率通知 + 电量读取/周期重读
│   ├── HeartRateParser.kt      # 8-bit / 16-bit 心率数据解析
│   └── HeartRateService.kt     # 前台服务（状态节流、录制控制、电量刷新循环）
├── data/
│   ├── CsvRecorder.kt          # Room 录制器（秒聚合 + 5s 强刷 + 汇总统计）
│   ├── CsvExporter.kt          # 会话 → 带 Summary 的 CSV（UTF-8 BOM）
│   ├── HeartRateDatabase.kt    # Room 数据库（私有目录，Migration v1→v2）
│   ├── RecordDao.kt            # DAO（REPLACE 写入、汇总更新、电量快照）
│   ├── RecordSession.kt        # 会话实体（含 deviceSampleCounts/avgHeartRate）
│   ├── HrSample.kt             # 心率样本实体（唯一索引防重复）
│   └── BatterySnapshot.kt      # 电量快照实体
├── model/
│   ├── DeviceState.kt          # 设备连接状态（ArrayDeque 历史 + dirty 标记）
│   └── UiDeviceState.kt        # UI 数据模型
├── ui/
│   ├── chart/MultiHeartRateChart.kt  # 实时图表（限帧 + 降采样 + 渐变填充）
│   ├── components/LeftPanel.kt       # 三图标操作区 + 设备列表
│   ├── components/DeviceItem.kt      # 设备卡片（自绘电池/心率图标 + 过渡动画）
│   ├── screen/SplashScreen.kt        # 开屏动画
│   ├── screen/MainScreen.kt          # 主页面（横竖屏自适应）
│   ├── screen/HistoryScreen.kt       # 历史记录页（Room 数据源）
│   ├── screen/CsvChartDialog.kt      # CSV 图表回放（Room 数据源）
│   └── theme/Theme.kt / Color.kt / Type.kt
├── NavigationBarHelper.kt     # edge-to-edge 系统栏沉浸统一处理
├── MainActivity.kt            # 入口 Activity
└── MemoryReceiver.kt          # HyperOS 内存预警适配（释放图表历史缓存）
```

### 国际化

所有 UI 字符串通过 `res/values-*/strings.xml` 管理，支持 7 种语言：简体中文（默认）、English、日本語、한국어、Deutsch、Français、繁體中文。

### 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Jetpack Compose BOM | 2024.02.00 | UI 框架版本管理 |
| Material 3 | (BOM 管理) | Material Design 3 组件 |
| Material Icons Extended | (BOM 管理) | 扩展图标库 |
| **Room** | 2.6.1（kapt） | 心率数据持久化 |
| Coroutines | 1.7.3 | 异步编程 |
| AndroidX Core KTX | 1.12.0 | Kotlin 扩展 |
| Lifecycle Runtime KTX | 2.7.0 | 生命周期感知 |

无第三方 BLE 库依赖，全部使用 Android 标准蓝牙 API。

## 权限说明

| 权限 | 用途 |
|------|------|
| `BLUETOOTH_SCAN`（含 `neverForLocation` 标志） | BLE 扫描；标志声明"扫描不用于推算位置"，Android 12+ 可免除定位权限 |
| `BLUETOOTH_CONNECT` | 连接蓝牙设备 |
| `ACCESS_FINE_LOCATION` | 仅供 Android 8~11 的 BLE 扫描使用（Android 12+ 运行时不再请求） |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 蓝牙前台服务类型 |
| `POST_NOTIFICATIONS` | 前台服务通知（Android 13+） |
| `VIBRATE` | 操作振动反馈 |

> 数据存储在应用私有目录（Room），**不需要任何存储/文件访问权限**。

## 构建

```bash
# 需要 JDK 17（AGP 8.2 要求；JBR 21 无法处理 SDK core-for-system-modules.jar）
# gradle.properties 已配置 org.gradle.java.home 指向本机 JDK 17

./gradlew assembleRelease
```

- 需要 Android SDK 34 和 JDK 17
- 产物：`app/build/outputs/apk/release/app-release-unsigned.apk`

## 历史迭代要点（开发记录）

- **Room 化（v2）**：CSV 文件 → Room 数据库；分享时动态生成 CSV（含 Summary 汇总区，UTF-8 BOM 防中文乱码）
- **交互重构**：单按钮长按 → 搜索/记录/历史三图标；记录停止改为长按 3 秒（防误触）
- **内存优化**：emitState 限频节流 + 增量快照、history 改 ArrayDeque、releaseMemory 修复数据源、录制 Channel 积压监控
- **系统栏**：横屏不隐藏系统栏且内容不侵入状态栏；仅数据全屏查看时隐藏

---

# Heart Rate Comparison Tool

An Android app for connecting multiple Bluetooth heart rate devices simultaneously, comparing real-time heart rate data, and recording it to a Room database in the app's private storage. Sessions can be exported as CSV with a summary section.

## Features

- **Splash Animation**: Particle-aggregated heart + ECG waveform animation
- **Multi-device Connection**: Connect multiple BLE heart rate sensors at once
- **Battery Level**: Auto-read via Battery Service / Device Information Service, custom battery icon
- **Real-time Chart**: Live multi-device HR curves with gradient fill, frame throttling + downsampling
- **Room Storage**: HR data aggregated per second into Room DB (private directory, no storage permission needed)
- **Auto-save**: Forced DB flush every 5 seconds (≤5s data loss window on process kill)
- **Battery Snapshots**: Start battery + 30s periodic re-read + end battery (deduplicated)
- **Session Summary**: Per-device sample count, average HR, duration (stored in Room, included in CSV export)
- **Recording**: Tap record icon to start; **long-press 3s to stop** (progress ring, anti-mistouch)
- **History**: Browse sessions (tap = chart, double-tap = share CSV, long-press = delete)
- **CSV Export**: Dynamically generated with UTF-8 BOM + `Summary:` section (English labels)
- **Chart Playback**: Zoom/pan gestures with gradient fill
- **7 Languages**: zh-CN / en / ja / ko / de / fr / zh-TW
- **Dark/Light Theme**, large-screen adaptation, HyperOS support

## Requirements

- Android 8.0+ (API 26), Bluetooth 4.0+ (BLE)

## Tech Stack

Kotlin · Jetpack Compose + Material 3 · **Room 2.6.1** · Android BLE API · Coroutines/Flow · minSdk 26 / targetSdk 34

## Permissions

`BLUETOOTH_SCAN` (with `neverForLocation`) · `BLUETOOTH_CONNECT` · `ACCESS_FINE_LOCATION` (Android 8~11 only) · `FOREGROUND_SERVICE` · `FOREGROUND_SERVICE_CONNECTED_DEVICE` · `POST_NOTIFICATIONS` · `VIBRATE`

> No storage permission required — data lives in the app's private Room database.

## Build

```bash
./gradlew assembleRelease   # Requires JDK 17 (configured via org.gradle.java.home)
```

APK output: `app/build/outputs/apk/release/app-release-unsigned.apk`

---

## BLE 踩坑记录 / BLE Development Pitfalls

### 1. 很多心率设备没有 Battery Service

大量心率设备只实现 Heart Rate Service（`0x180D`），不暴露 Battery Service（`0x180F`），`gatt.getService(0x180F)` 会返回 `null`。**解决方案**：增加 fallback，依次尝试 Battery Service 与 Device Information Service（`0x180A`）。

### 2. onDescriptorWrite 没有 API 33+ 重载

`onCharacteristicRead` / `onCharacteristicChanged` 在 API 33 有新增 `value` 参数的重载，但 **`onDescriptorWrite` 只有旧签名**，不要为其添加 `value` 参数（会报 "overrides nothing"）。

### 3. CCCD 写入与特征读取的时序问题

Android BLE 的 GATT 操作是串行队列，`writeDescriptor`（启用心率通知）未完成时发起电池读取会**静默失败**。**解决方案**：在 `onDescriptorWrite` 回调中再发起电池读取，确保 CCCD 写入完成。

### 4. onCharacteristicRead 需要同时处理两个重载

API 33 前后 `onCharacteristicRead` 签名不同（旧版数据在 `characteristic.value`，新版作为参数传入）。**必须同时重写两个版本**，否则对应 API 版本上不会触发回调。

### 5. Battery Level 特征值的解析

Battery Level 是单字节无符号整数（0~100），Kotlin 的 `Byte` 是有符号的，`value[0].toInt()` 对 `0x80` 以上会产生负数。**正确写法**：`value?.getOrNull(0)?.toInt()?.and(0xFF)`。
