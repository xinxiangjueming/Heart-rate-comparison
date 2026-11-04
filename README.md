# 心率对比工具 / Heart Rate Comparison

一款基于 Kotlin + Jetpack Compose 的 Android 应用：同时连接多台 BLE 心率设备，实时对比心率曲线，把采集数据按秒聚合写入 Room 私有数据库，支持历史回放、CSV 导入导出与分享。全部数据存放在应用私有目录，**不需要任何存储权限**。

## 功能

### 连接与采集
- **多设备同时连接**：BLE 扫描（15 秒超时，按 Heart Rate Service `0x180D` 过滤），可同时保持多路 GATT 连接
- **心率解析**：支持 Heart Rate Measurement 的 8-bit / 16-bit 格式
- **设备电量**：连接时读取 Battery Service `0x180F`，缺失时回退 Device Information Service `0x180A`；录制中每 30 秒重读
- **设备信息**：读取 DIS 的厂商、型号、序列号、固件与软件版本，在设备信息弹窗中展示

### 实时展示
- **多设备曲线**：Canvas 自绘，300 秒窗口，300ms 限帧、按绝对秒对齐、缺失值前向填充、渐变填充与 10 色调色盘
- **设备卡片**：自绘电池图标（绿 / 橙 / 红三档）与等比心形 + ECG 脉搏线心率图标
- **开屏动画**：160 个粒子聚合成心形，叠加 ECG 描线，约 3.8 秒
- **主题与适配**：跟随系统深浅色、动态读取屏幕圆角；横屏 1:3、平板 1:2、竖屏 1:1 自适应；edge-to-edge 沉浸式系统栏
- **HyperOS 适配**：折叠屏连续性、Flip 外屏、平行窗口 meta-data、系统内存预警时释放图表历史缓存

### 录制与存储
- **Room 持久化**：`heartrate.db`（数据库版本 3），三张表 `sessions` / `hr_samples` / `battery_snapshots`
- **自动保存**：秒级聚合 + 独立协程每 5 秒强制落库（含当前秒缓冲、会话汇总、电量快照），进程被杀最多丢约 5 秒
- **同秒去重**：`hr_samples` 唯一索引 `(sessionId, second, deviceAddress)`，写入用 REPLACE
- **电量快照**：起始电量、录制中每 30 秒变化值、结束电量，同值去重
- **会话总结**：每设备有效样本数、全局平均心率、时长，随 5 秒强刷更新，停止时最终修正

### 历史与数据交换
- **历史列表**：横屏两列网格、平板分栏（左列表 + 右图表）、旋转淡入淡出过渡；单击查看图表，右侧分享图标导出，长按删除（二次确认）
- **图表回放**：Room 数据源，双指缩放 1–20 倍、底部滑块平移、单指按住显示当秒读数、图例显隐；全屏查看时锁定横屏并隐藏系统栏
- **CSV 导出**：分享时动态生成，UTF-8 BOM 保证中文设备名不乱码，末尾附 `Summary:` 汇总区（Samples / Start Battery / End Battery / Avg HR / Duration / Start / End Time），经 FileProvider 以 `ACTION_SEND` 分享
- **CSV 导入**：作为系统「用其他应用打开」的接收端（`ACTION_VIEW`，5 组 intent-filter 覆盖 MIME 与 content/file 的 `.csv`），流式解析本应用导出格式后写入 Room 并直接打开图表

### 交互
- 主界面三个等宽图标按钮：历史（蓝）、搜索、记录
- 记录：点击开始（无已连接设备时置灰），**记录中长按 3 秒停止**，按住时显示进度环，防误触
- **一镜到底转场**：点击历史图标，页面从图标处 40dp 圆扩散铺满全屏，返回沿同一几何收拢（见下节）

## 系统要求

- Android 8.0+（API 26），蓝牙 4.0+（BLE）
- 构建：JDK 17、Android SDK 34、Gradle 8.2（wrapper 已带校验和）

## 技术栈

| 类别 | 版本 / 技术 |
|---|---|
| 语言 | Kotlin 1.9.22 |
| 构建 | Android Gradle Plugin 8.2.0、Gradle 8.2、KSP 1.9.22-1.0.17 |
| UI | Jetpack Compose BOM 2024.02.00 + Material 3 |
| 持久化 | Room 2.6.1（runtime / ktx / compiler，注解处理走 KSP） |
| 异步 | Kotlin Coroutines 1.7.3 + StateFlow / Channel |
| 蓝牙 | Android 标准 BLE API，无第三方 BLE 库 |
| SDK | minSdk 26 / compileSdk 34 / targetSdk 34；versionCode 1，versionName 1.0 |
| 发布配置 | release 开启 R8 混淆（`isMinifyEnabled`）与资源压缩；**未配置 signingConfigs** |

> `gradle/libs.versions.toml` 中的版本号当前**未被任何构建脚本引用**，实际版本以根 `build.gradle.kts`、`app/build.gradle.kts` 的硬编码为准，不要按 toml 判断工具链版本。

## 架构

单 Activity + Compose；`HeartRateService` 前台服务持有蓝牙连接与全局状态，UI 通过三个静态 `StateFlow` 订阅，命令通过 `startService(Intent(action))` 下发（不 `bindService`）。

```
┌──────────────────────── UI Layer (Compose) ────────────────────────┐
│  SplashScreen → MainScreen → HistoryScreen → CsvChartScreen         │
│  LeftPanel / DeviceItem / MultiHeartRateChart / DeviceInfoDialog     │
│           collectAsState()                                          │
│  globalUiState(卡片) · globalHistoryState(图表) · globalDeviceInfo   │
├──────────────────────── Service Layer ─────────────────────────────┤
│  HeartRateService (前台服务, connectedDevice)                        │
│    ├─ BluetoothScanner      BLE 扫描                                │
│    ├─ BluetoothConnector    GATT / 心率通知 / 电量 / DIS              │
│    ├─ HeartRateParser       心率字节解析                              │
│    └─ CsvRecorder           录制 Channel → Room                      │
├──────────────────────── Data Layer (Room v3) ──────────────────────┤
│  HeartRateDatabase → RecordDao                                       │
│    sessions · hr_samples · battery_snapshots                         │
│  CsvExporter（导出/分享） · CsvImporter（ACTION_VIEW 导入）            │
└─────────────────────────────────────────────────────────────────────┘
```

**数据流**：BLE 回调 → `HeartRateParser` → `HeartRateService` 按秒聚合进 `ArrayDeque<HeartRatePoint>` → 事件驱动节流（120ms，空闲零轮询）→ 三路增量快照 StateFlow → Compose。录制侧同一份心率数据进 `CsvRecorder` 的 `Channel(UNLIMITED)`，由单消费者 IO 协程写库。

**线程模型**：主线程负责状态发射与 UI；BLE 回调走 `Dispatchers.Default.limitedParallelism(1)` 串行化；数据库与文件 IO 走 `Dispatchers.IO`。详见仓库中的 `THREADING_REFACTOR.md`——注意该文档写于 CSV 文件存储时代，其中 `parseCsv(file)` 等描述与当前 Room 实现不符，应作为**历史设计文档**阅读。

### 模块职责

| 路径（相对 `app/src/main/java/com/example/heartratecomparison/`） | 职责 |
|---|---|
| `MainActivity.kt` | 入口、运行时权限、CSV 导入路由（main / loading / chart / error） |
| `NavigationBarHelper.kt` | edge-to-edge 系统栏统一处理，API 分支、Flip 外屏、insets 监听兜底 |
| `MemoryReceiver.kt` | 动态注册 HyperOS `TRIM` 内存预警，触发 `releaseMemory()` 释放历史缓存 |
| `bluetooth/` | `HeartRateService`（状态、命令分发、30 秒电量刷新）、`BluetoothScanner`、`BluetoothConnector`、`HeartRateParser` |
| `data/` | `CsvRecorder`（Channel 单消费者录制）、`CsvExporter`、`CsvImporter`、`HeartRateDatabase`（v3，MIGRATION_1_2 / 2_3）、`RecordDao`、三个 `@Entity` |
| `model/` | `DeviceState`（连接态 + `ArrayDeque` 历史 + dirty）、`HeartRatePoint`（epoch 秒 + 心率）、`UiDeviceState`（卡片稳定模型，不含历史）、`DeviceInfo` |
| `ui/ClipReveal.kt` / `ui/ClipRevealLayout.kt` | 圆形扩散一镜到底转场的调度与裁剪容器 |
| `ui/chart/` | `MultiHeartRateChart` 实时曲线 |
| `ui/common/` | `GlassDialog`（确认弹窗、设备信息弹窗） |
| `ui/components/` | `LeftPanel`（三图标 + 设备列表）、`DeviceItem`（卡片与自绘图标） |
| `ui/icon/` | `MiuixShareIcon`（本地 `ImageVector`） |
| `ui/screen/` | `MainScreen`、`HistoryScreen`、`CsvChartDialog`、`SplashScreen` |
| `ui/theme/` | `Color`（10 色图表调色盘）、`Theme`（Material 3 + 动态圆角）、`Type` |

## 一镜到底转场

点击历史图标进入历史页时，不是 Activity 跳转，而是在本窗口 `android.R.id.content` 上挂一层全屏覆盖层（`TouchBlockingHost` > `ClipRevealLayout` > `ComposeView(HistoryScreen)`）：

1. 点击前用 `positionInWindow()` 采集按钮窗口矩形，以图标中心构造 40dp 圆的外接矩形；
2. 用 `decorView.draw(Canvas)` 局部截取该区域像素，铺在起始圆内 —— f=0 时与真实图标逐像素重合；
3. 圆从锚点向外扩散到覆盖全屏（`radius = startR + (endR - startR) * f`，`endR` 为圆心到最远屏角距离 +1px），580ms `FastOutSlowIn`；返回沿同一几何收拢，420ms `FastOutLinearIn`；
4. 锚点像素 0→0.22 淡出、历史页内容 0.48→0.88 淡入，两层都钉死屏幕坐标、只做透明度交叉，不做缩放或位移；
5. 返回键 / 左滑返回 / 页内返回由 `OnBackPressedCallback` 统一接管，动画期间覆盖层吞掉全部触摸。

实现口径对齐 `sportlink` 手机端「设置齿轮 → 运动参数设置」的容器变换；差异是本项目历史页为**同窗口覆盖层**，不引入其跨 Activity 的 `AppTransitions` Handoff，源端截图也用 View 层局部截取替代 `GraphicsLayer`（本项目 Compose BOM 2024.02.00 无 `rememberGraphicsLayer`，避免为动画升级整个编译器链）。

几个踩过坑的硬约束：首帧 `PreDraw` 必须返回 `false` 取消首绘，否则整页先闪一帧；`ClipRevealLayout` 用 `clipToOutline` + `canvas.clipPath` 双层圆形裁剪（minSdk 26 不能用 API 30 的 `Outline.setCircle`，改以正方形 `setRoundRect` + 半径=半边长等效），**稳态必须关闭 `clipToOutline` 且 outline 给全屏矩形**，空 outline 会把整页裁没；Activity 声明了 `configChanges`，覆盖层存续期间旋转需按占比重映射圆心。截图失败时自动降级为纯色圆扩散，动画不中断。

## 交互说明（主界面三图标）

| 图标 | 位置 | 行为 |
|---|---|---|
| 历史（蓝） | 最左 | 点击后从图标处圆形扩散进入历史记录 |
| 搜索 | 中间 | 点击扫描，扫描中切换为进度环 |
| 记录 | 最右 | 点击开始记录（无设备时置灰）；记录中长按 3 秒停止，按住显示进度环 |

## 国际化与主题

所有 UI 字符串由 `res/values*/strings.xml` 管理，共 **13 种语言**：默认简体中文，以及 de / en / es / fr / it / ja / ko / nl / pl / pt / ru / zh-rTW（`values-night` 是夜间主题资源，不计入语言）。`localization_tools/` 提供键集合与顺序校验（以 `values-en` 为基准）、按基准重排、批量补齐新增字符串三个 Python 脚本。深浅色主题跟随系统，图表在深色下与背景分层。

## 权限

| 权限 | 用途 |
|---|---|
| `BLUETOOTH_SCAN`（`neverForLocation`） | BLE 扫描；该标志使 Android 12+ 免除定位权限 |
| `BLUETOOTH_CONNECT` | 连接蓝牙设备 |
| `ACCESS_FINE_LOCATION` | 仅 Android 8~11 的 BLE 扫描需要，Android 12+ 运行时不再请求 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 前台服务及其蓝牙设备类型 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知，拒绝时引导至系统设置页 |
| `VIBRATE` | 操作振动反馈 |

录制开始时还会请求忽略电池优化。数据存储于 `/data/data/<包名>/databases/`，**不需要任何存储或文件访问权限**。

## 构建与验证

环境由 `gradle.properties` 固定：JDK 17（`org.gradle.java.home`；JBR 21 会因 `core-for-system-modules.jar` 的 jlink 解析失败）、Android SDK 34、腾讯镜像仓库（`settings.gradle.kts`）。

日常改动只需**快速编译验证**，不产出 APK、不装机：

```bash
./gradlew :app:compileReleaseKotlin --no-daemon --offline
```

- Windows 上编译前建议先停掉陈旧的 Gradle / Kotlin daemon，避免 `classpath-snapshot`、`fileHashes.lock` 一类文件锁导致的假失败；
- 只有出包、签名、装机才需要 release 组装流程，由维护者按需执行；工程当前**未配置签名**，release 变体启用 R8 与资源压缩；
- 仓库根目录的 `build/` 仅含 Gradle 问题报告，`app/release/` 与 `.workbuddy/`、`local.properties` 均被 `.gitignore` 忽略。

## 项目结构

```
app/src/main/java/com/example/heartratecomparison/
├── MainActivity.kt / NavigationBarHelper.kt / MemoryReceiver.kt
├── bluetooth/   BluetoothScanner · BluetoothConnector · HeartRateParser · HeartRateService
├── data/        CsvRecorder · CsvExporter · CsvImporter · HeartRateDatabase · RecordDao
│                RecordSession · HrSample · BatterySnapshot
├── model/       DeviceState · UiDeviceState · DeviceInfo
└── ui/
    ├── ClipReveal.kt / ClipRevealLayout.kt
    ├── chart/MultiHeartRateChart.kt
    ├── common/GlassDialog.kt
    ├── components/LeftPanel.kt · DeviceItem.kt
    ├── icon/MiuixShareIcon.kt
    ├── screen/MainScreen.kt · HistoryScreen.kt · CsvChartDialog.kt · SplashScreen.kt
    └── theme/Color.kt · Theme.kt · Type.kt
```

## 架构演进

- **存储**：CSV 文件 → Room（当前 v3，含 `MIGRATION_1_2` 汇总列/电量表/唯一索引、`MIGRATION_2_3` 清理单列索引）；分享时按需生成 CSV
- **状态**：全量快照 → 120ms 事件驱动节流 + 卡片 / 图表历史 / 设备信息三路 StateFlow 分流，`UiDeviceState` 不再携带心率历史
- **交互**：单按钮长按 → 搜索/记录/历史三图标；记录停止改成长按 3 秒防误触
- **转场**：`AnimatedContent` 左右滑动 → 锚点线上下撑开 → 当前从图标圆形扩散的一镜到底
- **适配**：横竖屏与平板分栏、13 语言、HyperOS 折叠屏/Flip/内存预警

---

## BLE 踩坑记录

### 1. 很多心率设备没有 Battery Service

大量心率设备只实现 Heart Rate Service（`0x180D`），不暴露 Battery Service（`0x180F`），`gatt.getService(0x180F)` 返回 `null`。解决：依次尝试 `0x180F` 与 Device Information Service（`0x180A`）。

### 2. `onDescriptorWrite` 没有 API 33+ 重载

`onCharacteristicRead` / `onCharacteristicChanged` 在 API 33 新增了带 `value` 参数的重载，但 `onDescriptorWrite` **只有旧签名**，为其添加 `value` 参数会报 "overrides nothing"。

### 3. CCCD 写入与特征读取的时序

BLE GATT 操作是串行队列，`writeDescriptor`（启用心率通知）未完成时发起电池读取会静默失败。解决：在 `onDescriptorWrite` 回调里再发起电池读取。

### 4. API 33 前后的读/通知回调要同时重写两个版本

旧版数据在 `characteristic.value`，新版通过参数传入；`onCharacteristicRead` 与 `onCharacteristicChanged` 都必须同时重写两个重载，否则对应系统版本上不触发回调。

### 5. Battery Level 是单字节无符号整数

Kotlin 的 `Byte` 有符号，`value[0].toInt()` 对 `0x80` 以上会得到负数。正确写法：`value?.getOrNull(0)?.toInt()?.and(0xFF)`。

---

# Heart Rate Comparison (English summary)

An Android app (Kotlin + Jetpack Compose) that connects multiple BLE heart-rate sensors simultaneously, plots live multi-device curves, records per-second samples into a private Room database, and supports history playback, CSV import/export and sharing. All data lives in app-private storage — **no storage permission required**.

## Features

- Multi-device BLE scanning/connection, 8-bit & 16-bit Heart Rate Measurement parsing, battery level with `0x180F` → `0x180A` fallback, DIS device info
- Live Canvas chart (300 s window, frame throttling, forward-fill, gradient fill), device cards with custom battery/heart icons, particle + ECG splash
- Room v3 storage (`sessions` / `hr_samples` / `battery_snapshots`), forced flush every 5 s, unique index de-duplication, battery snapshots, per-session summary
- History list (landscape two-column grid, tablet split view), chart playback with zoom/pan/scrub, CSV export with UTF-8 BOM and `Summary:` section via FileProvider
- CSV **import** through `ACTION_VIEW` ("open with"), written into Room and opened in the chart directly
- One-shot container transition: tapping the history icon expands a 40dp circle into the full page and collapses back on return (580 ms / 420 ms, cross-fade only, no content scaling)
- 13 languages, dark/light theme, large-screen and HyperOS (foldable / Flip / memory trim) adaptation

## Requirements & stack

Android 8.0+ (API 26), BLE 4.0+; build with JDK 17 + Android SDK 34 + Gradle 8.2.

Kotlin 1.9.22 · AGP 8.2.0 · Compose BOM 2024.02.00 + Material 3 · Room 2.6.1 via KSP · Coroutines 1.7.3 · minSdk 26 / targetSdk 34. No third-party BLE library.

## Build & verification

```bash
./gradlew :app:compileReleaseKotlin --no-daemon --offline
```

This is the routine verification step — it compiles without producing an APK. Release packaging/signing is a separate maintainer action; `signingConfigs` is not configured, while R8 minification and resource shrinking are enabled for release.

## Permissions

`BLUETOOTH_SCAN` (`neverForLocation`) · `BLUETOOTH_CONNECT` · `ACCESS_FINE_LOCATION` (Android 8–11 only) · `FOREGROUND_SERVICE` · `FOREGROUND_SERVICE_CONNECTED_DEVICE` · `POST_NOTIFICATIONS` · `VIBRATE`
