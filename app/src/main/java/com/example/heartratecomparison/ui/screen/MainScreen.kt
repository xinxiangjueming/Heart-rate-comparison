package com.example.heartratecomparison.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.heartratecomparison.R
import com.example.heartratecomparison.bluetooth.HeartRateService
import com.example.heartratecomparison.ui.chart.MultiHeartRateChart
import com.example.heartratecomparison.ui.components.LeftPanel
import com.example.heartratecomparison.ui.common.GlassAlertDialog
import com.example.heartratecomparison.ui.theme.ChartColors
import com.example.heartratecomparison.ui.screen.SplashScreen

private const val TAG = "MainScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val activity = context as? Activity

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val colorPool = ChartColors

    val uiState by HeartRateService.globalUiState.collectAsState()
    // 图表历史单独订阅：历史内容未变时该流不发射（StateFlow 内容去重），图表不受卡片状态刷新牵动
    val heartRateHistories by HeartRateService.globalHistoryState.collectAsState()
    val deviceStates = uiState.devices
    val connectionOrder = uiState.connectionOrder
    // key 用设备地址集合（结构性相等）：设备集合不变则命中缓存，避免每次心率重建整个 color map
    val deviceColors = remember(deviceStates.keys) {
        val map = mutableMapOf<String, Color>()
        uiState.deviceColors.forEach { (addr, index) ->
            map[addr] = colorPool[index % colorPool.size]
        }
        map
    }
    val isRecording = uiState.isRecording
    val isScanning = uiState.isScanning
    val hasConnectedDevices = deviceStates.values.any { it.isConnected }

    var showExitDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // 用 rememberSaveable：从 CSV 查看页返回本屏时，MainScreen 会重新组合，
    // 普通 remember 会重置为 true 并重播启动动画；saveable 可保留已播放状态，避免每次返回都重播
    var showSplash by rememberSaveable { mutableStateOf(true) }

    fun sendServiceCommand(action: String, extra: Pair<String, String>? = null) {
        val intent = Intent(context, HeartRateService::class.java).apply {
            this.action = action
            extra?.let { putExtra(it.first, it.second) }
        }
        context.startService(intent)
    }

    // BLUETOOTH_SCAN 已声明 neverForLocation → Android 12+ 扫描不再需要定位权限；Android 8~11 仍需 FINE_LOCATION
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            sendServiceCommand("TOGGLE_SCAN")
        }
    }

    // 开屏动画
    if (showSplash) {
        SplashScreen(onFinished = { showSplash = false })
        return
    }

    // 退出确认弹窗
    BackHandler(enabled = isRecording) { showExitDialog = true }
    if (showExitDialog) {
        GlassAlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = stringResource(R.string.dialog_exit_title),
            text = stringResource(R.string.dialog_exit_message),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = { showExitDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.btn_cancel),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            showExitDialog = false
                            sendServiceCommand("STOP_RECORDING")
                            sendServiceCommand("STOP_SERVICE")
                            activity?.finish()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_finish),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // 历史页面（预测性返回动画）
    BackHandler(enabled = showHistory) { showHistory = false }
    AnimatedContent(
        targetState = showHistory,
        transitionSpec = {
            if (targetState) {
                // 进入历史：从右滑入
                slideInHorizontally(tween(500)) { it } + fadeIn(tween(500)) togetherWith
                    slideOutHorizontally(tween(500)) { -it / 3 } + fadeOut(tween(250))
            } else {
                // 返回主页：从左滑回（预测性返回手势方向）
                slideInHorizontally(tween(500)) { -it / 3 } + fadeIn(tween(500)) togetherWith
                    slideOutHorizontally(tween(500)) { it } + fadeOut(tween(250))
            }
        },
        label = "history_transition"
    ) { isHistory ->
        if (isHistory) {
            HistoryScreen(onBack = { showHistory = false })
        } else {
            // 共享的回调：remember 固定实例（捕获项均为生命周期恒定值），使 LeftPanel/DeviceItem
            // 能按参数相等跳过重组，让 UiDeviceState 稳定化的收益真正落地
            val onScanClick: () -> Unit = remember {
                {
                    val needRequest = permissions.any {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (needRequest) permissionLauncher.launch(permissions)
                    else sendServiceCommand("TOGGLE_SCAN")
                }
            }
            val onStartRecord: () -> Unit = remember {
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            })
                        }
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION") vibrator.vibrate(50)
                        }
                    } catch (_: SecurityException) {}
                    sendServiceCommand("START_RECORDING")
                }
            }
            val onStopRecord: () -> Unit = remember {
                {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION") vibrator.vibrate(50)
                        }
                    } catch (_: SecurityException) {}
                    sendServiceCommand("STOP_RECORDING")
                }
            }
            val onDeviceClick: (com.example.heartratecomparison.model.UiDeviceState) -> Unit = remember {
                { state -> sendServiceCommand("CONNECT_DEVICE", "device_address" to state.address) }
            }
            val onDeviceLongClick: (com.example.heartratecomparison.model.UiDeviceState) -> Unit = remember {
                { state ->
                    if (state.isConnected) {
                        sendServiceCommand("DISCONNECT_DEVICE", "device_address" to state.address)
                    }
                }
            }

            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val isTablet = (LocalConfiguration.current.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
            // 图表数据源：连接地址列表 + 历史流（卡片状态与图表历史已分流）
            val connectedAddresses = connectionOrder.filter { deviceStates[it]?.isConnected == true }

            // 主内容：背景铺满全屏（edge-to-edge），只避左右挖孔/侧边手势条；
            // 横屏不隐藏系统栏（状态栏/小白条保持可见），仅数据查看页（CsvChartScreen）进入时隐藏；
            // 横屏布局避开全部系统栏区域（不允许内容侵入状态栏/小白条），竖屏保持原 edge-to-edge 行为
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(
                        if (isLandscape) WindowInsets.safeDrawing
                        else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )
                    .padding(7.dp)
            ) {
                if (isLandscape) {
                    // 横屏：左（搜索+设备）右（图表）
                    // 平板 1:2，手机 1:3
                    val chartWeight = if (isTablet) 2f else 3f
                    Row(modifier = Modifier.fillMaxSize()) {
                        LeftPanel(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            isScanning = isScanning,
                            isRecording = isRecording,
                            hasConnectedDevices = hasConnectedDevices,
                            deviceStates = deviceStates.values.toList(),
                            deviceColors = deviceColors,
                            onScanClick = onScanClick,
                            onStartRecord = onStartRecord,
                            onStopRecord = onStopRecord,
                            onShowHistory = { showHistory = true },
                            onDeviceClick = onDeviceClick,
                            onDeviceLongClick = onDeviceLongClick
                        )
                        Box(
                            modifier = Modifier
                                .weight(chartWeight)
                                .fillMaxHeight()
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(start = 15.dp, top = 25.dp, end = 15.dp, bottom = 15.dp)
                        ) {
                            MultiHeartRateChart(
                                connectedAddresses = connectedAddresses,
                                heartRateHistories = heartRateHistories,
                                deviceColors = deviceColors
                            )
                        }
                    }
                } else {
                    // 竖屏：上（搜索+设备）下（图表）1:1
                    // 顶部/底部 inset 占位置于 weight 结构外，数学等效原全边 padding，不压缩权重区域
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
                        LeftPanel(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            isScanning = isScanning,
                            isRecording = isRecording,
                            hasConnectedDevices = hasConnectedDevices,
                            deviceStates = deviceStates.values.toList(),
                            deviceColors = deviceColors,
                            onScanClick = onScanClick,
                            onStartRecord = onStartRecord,
                            onStopRecord = onStopRecord,
                            onShowHistory = { showHistory = true },
                            onDeviceClick = onDeviceClick,
                            onDeviceLongClick = onDeviceLongClick
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(MaterialTheme.shapes.large)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(start = 15.dp, top = 25.dp, end = 15.dp, bottom = 15.dp)
                        ) {
                        MultiHeartRateChart(
                            connectedAddresses = connectedAddresses,
                            heartRateHistories = heartRateHistories,
                            deviceColors = deviceColors
                        )
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
                    }
                }
            }
        }
    }
}
