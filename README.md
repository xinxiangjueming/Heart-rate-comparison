# 心率对比工具

一款 Android 应用，用于同时连接多个蓝牙心率设备，实时对比心率数据并记录为 CSV 文件。

## 功能

- **多设备连接**：同时连接多个蓝牙心率传感器
- **实时图表**：实时显示各设备心率曲线，颜色区分
- **数据记录**：一键开始/停止记录，数据保存为 CSV 格式
- **历史查看**：浏览、查看、分享历史记录文件
- **CSV 图表回放**：支持缩放、滑动手势查看历史数据
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
│  MainScreen ── HistoryScreen ── CsvChartScreen      │
│  LeftPanel / DeviceItem / MultiHeartRateChart       │
│       │                                             │
│       │ collectAsState()                             │
│       ▼                                             │
│  HeartRateService.globalUiState (StateFlow)         │
├─────────────────────────────────────────────────────┤
│                 Service Layer                        │
│  HeartRateService (Foreground Service)               │
│       ├── BluetoothScanner (BLE 扫描)                │
│       ├── BluetoothConnector (GATT 连接)             │
│       ├── HeartRateParser (数据解析)                  │
│       └── CsvRecorder (CSV 文件写入)                  │
├─────────────────────────────────────────────────────┤
│                 Data Layer                           │
│  DeviceState (设备连接状态)                            │
│  UiDeviceState (UI 数据模型)                          │
│  CsvRecorder (文件 I/O)                              │
└─────────────────────────────────────────────────────┘
```

### 核心模块说明

| 模块 | 文件 | 职责 |
|------|------|------|
| **蓝牙扫描** | `BluetoothScanner.kt` | BLE 设备发现，扫描附近心率传感器 |
| **蓝牙连接** | `BluetoothConnector.kt` | GATT 连接管理，订阅心率特征值通知 |
| **数据解析** | `HeartRateParser.kt` | 解析 BLE Heart Rate Profile 原始字节数据 |
| **前台服务** | `HeartRateService.kt` | 管理多设备连接生命周期，维护全局状态 |
| **数据记录** | `CsvRecorder.kt` | 将心率数据按时间戳写入 CSV 文件 |
| **设备状态** | `DeviceState.kt` / `UiDeviceState.kt` | 设备连接状态与 UI 数据模型 |
| **内存适配** | `MemoryReceiver.kt` | HyperOS 公平运行内存适配 |

### UI 模块说明

| 模块 | 文件 | 职责 |
|------|------|------|
| **主页面** | `MainScreen.kt` | 横竖屏自适应布局，协调各子组件 |
| **左侧面板** | `LeftPanel.kt` | 搜索/记录按钮 + 设备列表 |
| **设备卡片** | `DeviceItem.kt` | 单个蓝牙设备的显示与交互 |
| **实时图表** | `MultiHeartRateChart.kt` | Canvas 自绘多设备心率曲线 |
| **历史页面** | `HistoryScreen.kt` | CSV 文件列表，支持拖拽分享 |
| **CSV 图表** | `CsvChartDialog.kt` | 历史数据回放，支持缩放/滑动手势 |
| **主题** | `Theme.kt` / `Color.kt` / `Type.kt` | Material 3 主题、颜色、字体，动态屏幕圆角适配 |

### 数据流

```
蓝牙设备 ──BLE──▶ BluetoothConnector
                      │
                      ▼
              HeartRateParser.parse()
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
│   ├── BluetoothScanner.kt     # BLE 扫描
│   ├── BluetoothConnector.kt   # GATT 连接
│   ├── HeartRateParser.kt      # 心率数据解析
│   └── HeartRateService.kt     # 前台服务（核心调度）
├── data/
│   └── CsvRecorder.kt          # CSV 文件写入
├── model/
│   ├── DeviceState.kt          # 设备连接状态
│   └── UiDeviceState.kt        # UI 数据模型
├── ui/
│   ├── chart/
│   │   └── MultiHeartRateChart.kt  # 实时心率图表
│   ├── components/
│   │   ├── LeftPanel.kt        # 左侧控制面板
│   │   └── DeviceItem.kt       # 设备列表项
│   ├── screen/
│   │   ├── MainScreen.kt       # 主页面
│   │   ├── HistoryScreen.kt    # 历史记录页面
│   │   └── CsvChartDialog.kt   # CSV 图表回放
│   └── theme/
│       ├── Theme.kt            # 主题配置（含屏幕圆角适配）
│       ├── Color.kt            # 颜色定义
│       └── Type.kt             # 字体定义
├── MainActivity.kt             # 入口 Activity
└── MemoryReceiver.kt           # HyperOS 内存适配
```

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

---

# Heart Rate Comparison Tool

An Android app for connecting multiple Bluetooth heart rate devices simultaneously, comparing real-time heart rate data, and recording it as CSV files.

## Features

- **Multi-device Connection**: Connect multiple Bluetooth heart rate sensors at the same time
- **Real-time Chart**: Live heart rate curve display with color-coded devices
- **Data Recording**: One-tap start/stop recording, data saved in CSV format
- **History Viewer**: Browse, view, and share recorded files
- **CSV Chart Playback**: Zoom and pan gestures for historical data analysis
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
│  MainScreen ── HistoryScreen ── CsvChartScreen      │
│  LeftPanel / DeviceItem / MultiHeartRateChart       │
│       │                                             │
│       │ collectAsState()                             │
│       ▼                                             │
│  HeartRateService.globalUiState (StateFlow)         │
├─────────────────────────────────────────────────────┤
│                 Service Layer                        │
│  HeartRateService (Foreground Service)               │
│       ├── BluetoothScanner (BLE Scan)                │
│       ├── BluetoothConnector (GATT Connection)       │
│       ├── HeartRateParser (Data Parsing)             │
│       └── CsvRecorder (CSV File Writing)             │
├─────────────────────────────────────────────────────┤
│                 Data Layer                           │
│  DeviceState (Connection State)                      │
│  UiDeviceState (UI Data Model)                       │
│  CsvRecorder (File I/O)                              │
└─────────────────────────────────────────────────────┘
```

### Core Modules

| Module | File | Responsibility |
|--------|------|----------------|
| **BLE Scanner** | `BluetoothScanner.kt` | Discover nearby heart rate sensors |
| **BLE Connector** | `BluetoothConnector.kt` | GATT connection, subscribe to HR notifications |
| **Data Parser** | `HeartRateParser.kt` | Parse BLE Heart Rate Profile raw bytes |
| **Foreground Service** | `HeartRateService.kt` | Multi-device lifecycle, global state management |
| **Data Recorder** | `CsvRecorder.kt` | Write heart rate data to CSV with timestamps |
| **Device State** | `DeviceState.kt` / `UiDeviceState.kt` | Connection state & UI data models |
| **Memory Adapter** | `MemoryReceiver.kt` | HyperOS fair memory management |

### UI Modules

| Module | File | Responsibility |
|--------|------|----------------|
| **Main Screen** | `MainScreen.kt` | Adaptive landscape/portrait layout |
| **Left Panel** | `LeftPanel.kt` | Scan/record buttons + device list |
| **Device Card** | `DeviceItem.kt` | Individual Bluetooth device display |
| **Live Chart** | `MultiHeartRateChart.kt` | Canvas-drawn multi-device HR curves |
| **History** | `HistoryScreen.kt` | CSV file list with drag-to-share |
| **CSV Chart** | `CsvChartDialog.kt` | Historical data playback with zoom/pan |
| **Theme** | `Theme.kt` / `Color.kt` / `Type.kt` | Material 3 theme, dynamic screen corner radius |

### Data Flow

```
BLE Device ──BLE──▶ BluetoothConnector
                        │
                        ▼
                HeartRateParser.parse()
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
│   ├── BluetoothScanner.kt     # BLE scanning
│   ├── BluetoothConnector.kt   # GATT connection
│   ├── HeartRateParser.kt      # HR data parsing
│   └── HeartRateService.kt     # Foreground service (core scheduler)
├── data/
│   └── CsvRecorder.kt          # CSV file writer
├── model/
│   ├── DeviceState.kt          # Device connection state
│   └── UiDeviceState.kt        # UI data model
├── ui/
│   ├── chart/
│   │   └── MultiHeartRateChart.kt  # Live HR chart
│   ├── components/
│   │   ├── LeftPanel.kt        # Left control panel
│   │   └── DeviceItem.kt       # Device list item
│   ├── screen/
│   │   ├── MainScreen.kt       # Main screen
│   │   ├── HistoryScreen.kt    # History screen
│   │   └── CsvChartDialog.kt   # CSV chart playback
│   └── theme/
│       ├── Theme.kt            # Theme (with screen corner radius)
│       ├── Color.kt            # Color definitions
│       └── Type.kt             # Typography
├── MainActivity.kt             # Entry Activity
└── MemoryReceiver.kt           # HyperOS memory adapter
```

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
