# 心率对比工具

一款 Android 应用，用于同时连接多个蓝牙心率设备，实时对比心率数据并记录为 CSV 文件。

## 功能

- **开屏动画**：粒子聚合心形 + ECG 心电图描线动画，科技感启动体验
- **多设备连接**：同时连接多个蓝牙心率传感器
- **设备电量读取**：自动读取 BLE 设备电池电量（Battery Service / Device Information Service），设备卡片中以自绘电池图标 + 百分比显示
- **自绘设备图标**：电池图标随电量动态填充（绿 >50% / 橙 20-50% / 红 <20%），心率图标为心形 + ECG 脉搏线
- **实时图表**：实时显示各设备心率曲线，带渐变填充色，限帧 + 降采样保证流畅
- **数据记录**：一键开始/停止记录，数据保存为 CSV 格式
- **历史查看**：浏览、查看、分享历史记录文件
- **CSV 图表回放**：支持缩放、滑动手势查看历史数据，曲线带渐变填充
- **多语言支持**：简体中文、English、日本語、한국어、Deutsch、Français、繁體中文（7 种语言）
- **深色/浅色主题**：跟随系统主题自动切换
- **大屏适配**：支持折叠屏、平板横竖屏自适应布局
- **HyperOS 适配**：适配小米折叠屏连续性、Flip 外屏、沉浸式状态栏

## 系统要求

- Android 8.0+（API 26）
- 蓝牙 4.0+（BLE）

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 蓝牙通信 | Android BLE API |
| 异步处理 | Kotlin Coroutines + Flow |
| 数据序列化 | Serializable |
| 文件存储 | External Files (CSV) |
| 最低 SDK | 26（Android 8.0） |
| 目标 SDK | 34（Android 14） |

## 技术架构

### 整体架构

采用 **单 Activity + Compose** 架构，通过 `HeartRateService` 前台服务管理蓝牙连接和数据采集，UI 层通过 `StateFlow` 响应式更新。

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                │
│  SplashScreen ── MainScreen ── HistoryScreen        │
│  LeftPanel / DeviceItem / MultiHeartRateChart       │
│       │                                             │
│       │ collectAsState()                             │
│       ▼                                             │
│  HeartRateService.globalUiState (StateFlow)         │
├─────────────────────────────────────────────────────┤
│                 Service Layer                        │
│  HeartRateService (Foreground Service)               │
│       ├── BluetoothScanner (BLE 扫描)                │
│       ├── BluetoothConnector (GATT 连接 + 电量读取)  │
│       ├── HeartRateParser (数据解析)                  │
│       └── CsvRecorder (CSV 文件写入)                  │
├─────────────────────────────────────────────────────┤
│                 Data Layer                           │
│  DeviceState (设备连接状态 + 电池电量)                 │
│  UiDeviceState (UI 数据模型)                          │
│  CsvRecorder (文件 I/O)                              │
└─────────────────────────────────────────────────────┘
```

### 核心模块说明

| 模块 | 文件 | 职责 |
|------|------|------|
| **蓝牙扫描** | `BluetoothScanner.kt` | BLE 设备发现，扫描附近心率传感器 |
| **蓝牙连接** | `BluetoothConnector.kt` | GATT 连接管理，订阅心率通知，读取电池电量 |
| **数据解析** | `HeartRateParser.kt` | 解析 BLE Heart Rate Profile 原始字节数据 |
| **前台服务** | `HeartRateService.kt` | 管理多设备连接生命周期，维护全局状态 |
| **数据记录** | `CsvRecorder.kt` | 将心率数据按时间戳写入 CSV 文件 |
| **设备状态** | `DeviceState.kt` / `UiDeviceState.kt` | 设备连接状态、电池电量与 UI 数据模型 |
| **内存适配** | `MemoryReceiver.kt` | HyperOS 公平运行内存适配 |

### UI 模块说明

| 模块 | 文件 | 职责 |
|------|------|------|
| **开屏动画** | `SplashScreen.kt` | 粒子聚合心形 + ECG 心电图描线启动动画 |
| **主页面** | `MainScreen.kt` | 横竖屏自适应布局，协调各子组件 |
| **左侧面板** | `LeftPanel.kt` | 搜索/记录按钮 + 设备列表 |
| **设备卡片** | `DeviceItem.kt` | 自绘电池图标（动态填充 + 三色）+ 心率图标（心形 + ECG 脉搏线） |
| **实时图表** | `MultiHeartRateChart.kt` | Canvas 自绘多设备心率曲线，带渐变填充 |
| **历史页面** | `HistoryScreen.kt` | CSV 文件列表，支持拖拽分享 |
| **CSV 图表** | `CsvChartDialog.kt` | 历史数据回放，支持缩放/滑动手势，带渐变填充 |
| **主题** | `Theme.kt` / `Color.kt` / `Type.kt` | Material 3 主题、颜色、字体，动态屏幕圆角适配 |

### 开屏动画说明

`SplashScreen.kt` 实现了一个粒子聚合 + 心电图描线的启动动画，纯 Compose Canvas 绘制，无额外依赖。

**动画流程：**
1. 160 个光点粒子从屏幕四边飞入，聚合成心形（参数方程 `x=16sin³t, y=13cost-5cos2t-2cos3t-cos4t`）
2. 聚合过程中脉冲光环从心形中心向外扩散
3. 心形稳定后，蓝色 ECG 心电图线从左到右逐笔描出（PQRST 波形）
4. 背景先淡出露出主界面，内容再渐隐完成过渡

**技术要点：**
- `Animatable` 单一进度值驱动全部动画元素
- 每粒子随机延迟（0~25%）实现自然错峰
- `smoothStep` hermite 插值实现丝滑过渡
- 横屏自适应：振幅基于短边计算，避免压扁

### 数据流

```
蓝牙设备 ──BLE──▶ BluetoothConnector
                      │
                      ├─▶ HeartRateParser.parse() ──▶ 心率值
                      └─▶ Battery Level Read ──▶ 电池电量
                              │
                              ▼
                      HeartRateService (StateFlow)
                              │
                    ┌─────────┼─────────┐
                    ▼         ▼         ▼
                MainScreen  CsvRecorder  Notification
                (实时图表)   (CSV 文件)   (前台通知)
```

### 项目结构

```
app/src/main/java/com/example/heartratecomparison/
├── bluetooth/                  # 蓝牙通信层
│   ├── BluetoothScanner.kt     # BLE 扫描（15 秒超时，过滤心率服务 UUID）
│   ├── BluetoothConnector.kt   # GATT 连接 + 心率通知 + 电池电量读取
│   ├── HeartRateParser.kt      # 8-bit / 16-bit 心率数据解析
│   └── HeartRateService.kt     # 前台服务（核心调度，StateFlow 状态管理）
├── data/
│   └── CsvRecorder.kt          # CSV 文件写入
├── model/
│   ├── DeviceState.kt          # 设备连接状态（含电池电量）
│   └── UiDeviceState.kt        # UI 数据模型（可序列化）
├── ui/
│   ├── chart/
│   │   └── MultiHeartRateChart.kt  # 实时心率图表（限帧 + 降采样 + 渐变填充）
│   ├── components/
│   │   ├── LeftPanel.kt        # 左侧控制面板（手势操作）
│   │   └── DeviceItem.kt       # 设备卡片（自绘电池 + 心率图标）
│   ├── screen/
│   │   ├── SplashScreen.kt     # 开屏动画（粒子聚合 + ECG 描线）
│   │   ├── MainScreen.kt       # 主页面（横竖屏自适应）
│   │   ├── HistoryScreen.kt    # 历史记录页面
│   │   └── CsvChartDialog.kt   # CSV 图表回放（缩放/平移/渐变填充）
│   └── theme/
│       ├── Theme.kt            # 主题配置（含屏幕圆角适配）
│       ├── Color.kt            # 颜色定义（10 色图表调色盘）
│       └── Type.kt             # 字体定义
├── MainActivity.kt             # 入口 Activity
└── MemoryReceiver.kt           # HyperOS 内存适配
```

### 国际化

所有 UI 字符串均通过 `res/values-*/strings.xml` 管理，代码中通过 `stringResource()` 访问。

| 语言 | 资源目录 |
|------|----------|
| 简体中文（默认） | `values/` |
| English | `values-en/` |
| 日本語 | `values-ja/` |
| 한국어 | `values-ko/` |
| Deutsch | `values-de/` |
| Français | `values-fr/` |
| 繁體中文 | `values-zh-rTW/` |

### 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Jetpack Compose BOM | 2024.02.00 | UI 框架版本管理 |
| Material 3 | (BOM 管理) | Material Design 3 组件 |
| Material Icons Extended | (BOM 管理) | 扩展图标库 |
| Coroutines | 1.7.3 | 异步编程 |
| AndroidX Core KTX | 1.12.0 | Kotlin 扩展 |
| Lifecycle Runtime KTX | 2.7.0 | 生命周期感知 |

无第三方 BLE 库依赖，全部使用 Android 标准蓝牙 API。

## 权限说明

| 权限 | 用途 |
|------|------|
| `BLUETOOTH_SCAN` | 扫描 BLE 设备 |
| `BLUETOOTH_CONNECT` | 连接蓝牙设备 |
| `ACCESS_FINE_LOCATION` | BLE 扫描定位（Android 12 以下） |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 蓝牙前台服务类型 |
| `POST_NOTIFICATIONS` | 前台服务通知（Android 13+） |
| `VIBRATE` | 操作振动反馈 |

## 构建

```bash
./gradlew assembleDebug
```

需要 Android SDK 34 和 JDK 17。

---

## BLE 踩坑记录

开发过程中遇到的 BLE 相关问题，记录在此供参考。

### 1. 很多心率设备没有 Battery Service

BLE 规范中 Battery Service（UUID `0x180F`）和 Heart Rate Service（UUID `0x180D`）是两个独立的服务。**大量心率设备只实现了 Heart Rate Service，不暴露 Battery Service**，因此直接用 `gatt.getService(0x180F)` 会返回 `null`。

**解决方案**：增加 fallback 路径，依次尝试两个服务：

```kotlin
private fun onDeviceReady(gatt: BluetoothGatt) {
    // 方案1：Battery Service (0x180F)
    val batteryChar = gatt.getService(BATTERY_SERVICE_UUID)
        ?.getCharacteristic(BATTERY_LEVEL_UUID)
    if (batteryChar != null) {
        gatt.readCharacteristic(batteryChar)
        return
    }
    // 方案2：Device Information Service (0x180A)
    val disChar = gatt.getService(DEVICE_INFO_SERVICE_UUID)
        ?.getCharacteristic(BATTERY_LEVEL_UUID)
    if (disChar != null) {
        gatt.readCharacteristic(disChar)
    }
}
```

### 2. onDescriptorWrite 没有 API 33+ 重载

Android API 33 为 `BluetoothGattCallback` 引入了新的方法签名：

- `onCharacteristicRead(gatt, characteristic, value, status)` — 新增了 `value` 参数
- `onCharacteristicChanged(gatt, characteristic, value)` — 新增了 `value` 参数

但 **`onDescriptorWrite` 没有新的重载**，只有旧版签名可用：

```kotlin
// 正确：只有一个签名
@Suppress("DEPRECATION")
override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int
) { ... }

// 错误：这个方法不存在，编译报错 "overrides nothing"
override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
    value: ByteArray  // ← 不存在的参数
) { ... }
```

坑点在于：直觉上会以为三个 changed/read/write 回调都有 API 33+ 新版，但实际上只有前两者有。

### 3. CCCD 写入与特征读取的时序问题

在 `onServicesDiscovered` 中，如果同时发起 CCCD 描述符写入（启用心率通知）和电池电量读取：

```kotlin
// ❌ 错误写法：两个操作同时排队
override fun onServicesDiscovered(gatt, status) {
    // ... 启用心率通知
    gatt.writeDescriptor(descriptor, ENABLE_NOTIFICATION_VALUE)

    // 立即读电池 —— 此时 CCCD 写入还没完成，读取可能被静默吞掉
    val batteryChar = gatt.getService(BATTERY_SERVICE_UUID)
        ?.getCharacteristic(BATTERY_LEVEL_UUID)
    gatt.readCharacteristic(batteryChar)
}
```

Android BLE 的 GATT 操作是串行队列，`writeDescriptor` 和 `readCharacteristic` 会按顺序执行，但**在某些设备上，写入未完成时发起读取会导致读取静默失败**（不触发 `onCharacteristicRead` 回调，也没有任何错误日志）。

**解决方案**：在 `onDescriptorWrite` 回调中再发起电池读取，确保 CCCD 写入完全完成：

```kotlin
// ✅ 正确写法：CCCD 写完后再读电池
override fun onServicesDiscovered(gatt, status) {
    // ... 启用心率通知
    gatt.writeDescriptor(descriptor, ENABLE_NOTIFICATION_VALUE)
    // 不在这里读电池，等 onDescriptorWrite 回调
}

override fun onDescriptorWrite(gatt, descriptor, status) {
    if (descriptor.uuid == CCCD_UUID) {
        onDeviceReady(gatt)  // CCCD 写完了，安全读电池
    }
}
```

### 4. onCharacteristicRead 需要同时处理两个重载

和 `onCharacteristicChanged` 一样，`onCharacteristicRead` 在 API 33 前后有两个签名：

```kotlin
// API < 33：数据在 characteristic.value 中
@Deprecated("Deprecated in API 33")
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int
) {
    val data = characteristic.value  // ← 从这里取数据
}

// API >= 33：数据直接作为参数传入
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,               // ← 从这里取数据
    status: Int
) { }
```

**必须同时重写两个版本**，否则在对应 API 版本上不会触发回调。

### 5. Battery Level 特征值的解析

Battery Level 是一个单字节无符号整数（0~100），但 Kotlin 中 `Byte` 是有符号的（-128~127），直接 `toInt()` 会对 0x80 以上的值产生负数：

```kotlin
// ❌ 错误：0x80 以上变成负数
val level = value[0].toInt()   // 0x80 → -128

// ✅ 正确：位与 0xFF 转为无符号
val level = value?.getOrNull(0)?.toInt()?.and(0xFF)  // 0x80 → 128
```

---

# Heart Rate Comparison Tool

An Android app for connecting multiple Bluetooth heart rate devices simultaneously, comparing real-time heart rate data, and recording it as CSV files.

## Features

- **Splash Animation**: Particle-aggregated heart shape + ECG waveform line drawing animation
- **Multi-device Connection**: Connect multiple Bluetooth heart rate sensors at the same time
- **Battery Level Reading**: Auto-read BLE device battery via Battery Service / Device Information Service, displayed as custom-drawn battery icon with percentage
- **Custom Device Icons**: Battery icon with dynamic fill (green/orange/red by level), heart rate icon with heart shape + ECG pulse line
- **Real-time Chart**: Live heart rate curve display with gradient fill, frame throttling + downsampling
- **Data Recording**: One-tap start/stop recording, data saved in CSV format
- **History Viewer**: Browse, view, and share recorded files
- **CSV Chart Playback**: Zoom and pan gestures for historical data, curves with gradient fill
- **Multi-language Support**: Chinese (Simplified & Traditional), English, Japanese, Korean, German, French
- **Dark/Light Theme**: Follows system theme
- **Large Screen Adaptation**: Adaptive layout for foldable devices and tablets
- **HyperOS Support**: Foldable continuity, Flip cover screen, and immersive status bar support

## Requirements

- Android 8.0+ (API 26)
- Bluetooth 4.0+ (BLE)

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material 3 |
| Bluetooth | Android BLE API |
| Async | Kotlin Coroutines + Flow |
| Serialization | Serializable |
| Storage | External Files (CSV) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

## Architecture

### Overview

**Single Activity + Compose** architecture. `HeartRateService` foreground service manages Bluetooth connections and data collection. UI updates reactively via `StateFlow`.

```
┌─────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                │
│  SplashScreen ── MainScreen ── HistoryScreen        │
│  LeftPanel / DeviceItem / MultiHeartRateChart       │
│       │                                             │
│       │ collectAsState()                             │
│       ▼                                             │
│  HeartRateService.globalUiState (StateFlow)         │
├─────────────────────────────────────────────────────┤
│                 Service Layer                        │
│  HeartRateService (Foreground Service)               │
│       ├── BluetoothScanner (BLE Scan)                │
│       ├── BluetoothConnector (GATT + Battery Read)   │
│       ├── HeartRateParser (Data Parsing)             │
│       └── CsvRecorder (CSV File Writing)             │
├─────────────────────────────────────────────────────┤
│                 Data Layer                           │
│  DeviceState (Connection State + Battery Level)      │
│  UiDeviceState (UI Data Model)                       │
│  CsvRecorder (File I/O)                              │
└─────────────────────────────────────────────────────┘
```

### Core Modules

| Module | File | Responsibility |
|--------|------|----------------|
| **BLE Scanner** | `BluetoothScanner.kt` | Discover nearby heart rate sensors |
| **BLE Connector** | `BluetoothConnector.kt` | GATT connection, HR notifications, battery level reading |
| **Data Parser** | `HeartRateParser.kt` | Parse BLE Heart Rate Profile raw bytes |
| **Foreground Service** | `HeartRateService.kt` | Multi-device lifecycle, global state management |
| **Data Recorder** | `CsvRecorder.kt` | Write heart rate data to CSV with timestamps |
| **Device State** | `DeviceState.kt` / `UiDeviceState.kt` | Connection state, battery level & UI data models |
| **Memory Adapter** | `MemoryReceiver.kt` | HyperOS fair memory management |

### UI Modules

| Module | File | Responsibility |
|--------|------|----------------|
| **Splash Screen** | `SplashScreen.kt` | Particle-aggregated heart + ECG line animation |
| **Main Screen** | `MainScreen.kt` | Adaptive landscape/portrait layout |
| **Left Panel** | `LeftPanel.kt` | Scan/record buttons + device list |
| **Device Card** | `DeviceItem.kt` | Custom-drawn battery icon (dynamic fill) + heart rate icon (heart + ECG pulse) |
| **Live Chart** | `MultiHeartRateChart.kt` | Canvas-drawn multi-device HR curves with gradient fill |
| **History** | `HistoryScreen.kt` | CSV file list with drag-to-share |
| **CSV Chart** | `CsvChartDialog.kt` | Historical data playback with zoom/pan and gradient fill |
| **Theme** | `Theme.kt` / `Color.kt` / `Type.kt` | Material 3 theme, 10-color chart palette, dynamic screen corner radius |

### Data Flow

```
BLE Device ──BLE──▶ BluetoothConnector
                        │
                        ├─▶ HeartRateParser.parse() ──▶ Heart Rate
                        └─▶ Battery Level Read ──▶ Battery %
                                │
                                ▼
                        HeartRateService (StateFlow)
                                │
                      ┌─────────┼─────────┐
                      ▼         ▼         ▼
                  MainScreen  CsvRecorder  Notification
                  (Live Chart) (CSV File)  (Foreground)
```

### Project Structure

```
app/src/main/java/com/example/heartratecomparison/
├── bluetooth/                  # Bluetooth communication
│   ├── BluetoothScanner.kt     # BLE scanning (15s timeout, HR UUID filter)
│   ├── BluetoothConnector.kt   # GATT connection + HR notifications + battery reading
│   ├── HeartRateParser.kt      # 8-bit / 16-bit HR data parsing
│   └── HeartRateService.kt     # Foreground service (StateFlow state management)
├── data/
│   └── CsvRecorder.kt          # CSV file writer
├── model/
│   ├── DeviceState.kt          # Device connection state (with battery level)
│   └── UiDeviceState.kt        # UI data model (serializable)
├── ui/
│   ├── chart/
│   │   └── MultiHeartRateChart.kt  # Live HR chart (throttle + downsample + fill)
│   ├── components/
│   │   ├── LeftPanel.kt        # Left control panel (gesture controls)
│   │   └── DeviceItem.kt       # Device card (custom battery + HR icons)
│   ├── screen/
│   │   ├── SplashScreen.kt     # Splash animation (particles + ECG)
│   │   ├── MainScreen.kt       # Main screen (responsive layout)
│   │   ├── HistoryScreen.kt    # History screen
│   │   └── CsvChartDialog.kt   # CSV chart playback (zoom/pan/fill)
│   └── theme/
│       ├── Theme.kt            # Theme config (screen corner radius)
│       ├── Color.kt            # Color definitions (10-color chart palette)
│       └── Type.kt             # Typography
├── MainActivity.kt             # Entry Activity
└── MemoryReceiver.kt           # HyperOS memory adapter
```

### Internationalization

All UI strings managed via `res/values-*/strings.xml`, accessed through `stringResource()`.

| Language | Resource Directory |
|----------|--------------------|
| Chinese Simplified (default) | `values/` |
| English | `values-en/` |
| Japanese | `values-ja/` |
| Korean | `values-ko/` |
| German | `values-de/` |
| French | `values-fr/` |
| Chinese Traditional | `values-zh-rTW/` |

### Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Jetpack Compose BOM | 2024.02.00 | UI framework version management |
| Material 3 | (BOM managed) | Material Design 3 components |
| Material Icons Extended | (BOM managed) | Extended icon library |
| Coroutines | 1.7.3 | Asynchronous programming |
| AndroidX Core KTX | 1.12.0 | Kotlin extensions |
| Lifecycle Runtime KTX | 2.7.0 | Lifecycle-aware components |

No third-party BLE library dependencies — all Bluetooth communication uses Android standard APIs.

## Permissions

| Permission | Purpose |
|------------|---------|
| `BLUETOOTH_SCAN` | Scan for BLE devices |
| `BLUETOOTH_CONNECT` | Connect to Bluetooth devices |
| `ACCESS_FINE_LOCATION` | BLE scan location (Android < 12) |
| `FOREGROUND_SERVICE` | Keep service alive |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Bluetooth foreground service type |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `VIBRATE` | Haptic feedback |

## Build

```bash
./gradlew assembleDebug
```

Requires Android SDK 34 and JDK 17.

---

## BLE Development Pitfalls

Issues encountered during development, documented for reference.

### 1. Many Heart Rate Devices Don't Have Battery Service

In the BLE specification, Battery Service (UUID `0x180F`) and Heart Rate Service (UUID `0x180D`) are independent services. **Many heart rate devices only implement Heart Rate Service and don't expose Battery Service**, so `gatt.getService(0x180F)` returns `null`.

**Solution**: Add a fallback path, trying multiple services:

```kotlin
private fun onDeviceReady(gatt: BluetoothGatt) {
    // Option 1: Battery Service (0x180F)
    val batteryChar = gatt.getService(BATTERY_SERVICE_UUID)
        ?.getCharacteristic(BATTERY_LEVEL_UUID)
    if (batteryChar != null) {
        gatt.readCharacteristic(batteryChar)
        return
    }
    // Option 2: Device Information Service (0x180A)
    val disChar = gatt.getService(DEVICE_INFO_SERVICE_UUID)
        ?.getCharacteristic(BATTERY_LEVEL_UUID)
    if (disChar != null) {
        gatt.readCharacteristic(disChar)
    }
}
```

### 2. onDescriptorWrite Has No API 33+ Override

Android API 33 introduced new method signatures for `BluetoothGattCallback`:

- `onCharacteristicRead(gatt, characteristic, value, status)` — new `value` parameter
- `onCharacteristicChanged(gatt, characteristic, value)` — new `value` parameter

But **`onDescriptorWrite` has no new overload** — only the old signature exists:

```kotlin
// Correct: only one signature exists
@Suppress("DEPRECATION")
override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int
) { ... }

// Wrong: this method does not exist, compile error "overrides nothing"
override fun onDescriptorWrite(
    gatt: BluetoothGatt,
    descriptor: BluetoothGattDescriptor,
    status: Int,
    value: ByteArray  // ← does not exist
) { ... }
```

The pitfall: you'd naturally expect all three callbacks (changed/read/write) to have API 33+ versions, but only the first two do.

### 3. Timing Issue Between CCCD Write and Characteristic Read

In `onServicesDiscovered`, if you issue both a CCCD descriptor write (to enable HR notifications) and a battery level read simultaneously:

```kotlin
// ❌ Wrong: both operations queued at the same time
override fun onServicesDiscovered(gatt, status) {
    // ... enable HR notifications
    gatt.writeDescriptor(descriptor, ENABLE_NOTIFICATION_VALUE)

    // Read battery immediately — CCCD write hasn't completed,
    // read may be silently dropped
    val batteryChar = gatt.getService(BATTERY_SERVICE_UUID)
        ?.getCharacteristic(BATTERY_LEVEL_UUID)
    gatt.readCharacteristic(batteryChar)
}
```

Android BLE's GATT operations are serialized in a queue, so `writeDescriptor` and `readCharacteristic` execute in order. But **on some devices, issuing a read before the write completes causes the read to silently fail** — no `onCharacteristicRead` callback fires, and no error is logged.

**Solution**: Issue the battery read in the `onDescriptorWrite` callback, ensuring the CCCD write is fully complete:

```kotlin
// ✅ Correct: read battery after CCCD write completes
override fun onServicesDiscovered(gatt, status) {
    // ... enable HR notifications
    gatt.writeDescriptor(descriptor, ENABLE_NOTIFICATION_VALUE)
    // Don't read battery here — wait for onDescriptorWrite
}

override fun onDescriptorWrite(gatt, descriptor, status) {
    if (descriptor.uuid == CCCD_UUID) {
        onDeviceReady(gatt)  // CCCD write done, safe to read battery
    }
}
```

### 4. onCharacteristicRead Requires Both Overloads

Like `onCharacteristicChanged`, `onCharacteristicRead` has two signatures across API 33:

```kotlin
// API < 33: data in characteristic.value
@Deprecated("Deprecated in API 33")
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int
) {
    val data = characteristic.value  // ← read data from here
}

// API >= 33: data passed directly as parameter
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,               // ← read data from here
    status: Int
) { }
```

**Both versions must be overridden**, otherwise the callback won't fire on the corresponding API level.

### 5. Battery Level Characteristic Value Parsing

Battery Level is a single-byte unsigned integer (0~100), but Kotlin's `Byte` is signed (-128~127). Direct `toInt()` produces negative values for anything above 0x7F:

```kotlin
// ❌ Wrong: 0x80+ becomes negative
val level = value[0].toInt()   // 0x80 → -128

// ✅ Correct: mask with 0xFF for unsigned conversion
val level = value?.getOrNull(0)?.toInt()?.and(0xFF)  // 0x80 → 128
```
