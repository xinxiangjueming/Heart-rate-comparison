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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.heartratecomparison.R
import com.example.heartratecomparison.bluetooth.HeartRateService
import com.example.heartratecomparison.ui.chart.MultiHeartRateChart
import com.example.heartratecomparison.ui.components.LeftPanel

private const val TAG = "MainScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val density = LocalDensity.current

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val colorPool = listOf(
        Color.Red, Color(0xFF00BFFF), Color.Green, Color(0xFF800080), Color(0xFFFF9800)
    )

    val uiState by HeartRateService.globalUiState.collectAsState()
    val deviceStates = uiState.devices
    val connectionOrder = uiState.connectionOrder
    val deviceColors = remember(uiState.deviceColors) {
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

    val statusBarHeight = with(density) {
        WindowInsets.systemBars.getTop(this).toDp()
    }
    val navigationBarHeight = with(density) {
        WindowInsets.systemBars.getBottom(this).toDp()
    }

    fun sendServiceCommand(action: String, extra: Pair<String, String>? = null) {
        val intent = Intent(context, HeartRateService::class.java).apply {
            this.action = action
            extra?.let { putExtra(it.first, it.second) }
        }
        context.startService(intent)
    }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
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

    // 退出确认弹窗
    BackHandler(enabled = isRecording) { showExitDialog = true }
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_exit_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_exit_message),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                    Button(
                        onClick = {
                            showExitDialog = false
                            sendServiceCommand("STOP_RECORDING")
                            sendServiceCommand("STOP_SERVICE")
                            activity?.finish()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.btn_finish))
                    }
                }
            },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
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
            // 共享的回调
            val onScanClick: () -> Unit = {
                val needRequest = permissions.any {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (needRequest) permissionLauncher.launch(permissions)
                else sendServiceCommand("TOGGLE_SCAN")
            }
            val onStartRecord: () -> Unit = {
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
            val onStopRecord: () -> Unit = {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION") vibrator.vibrate(50)
                    }
                } catch (_: SecurityException) {}
                sendServiceCommand("STOP_RECORDING")
            }
            val onDeviceClick: (com.example.heartratecomparison.model.UiDeviceState) -> Unit = { state ->
                sendServiceCommand("CONNECT_DEVICE", "device_address" to state.address)
            }
            val onDeviceLongClick: (com.example.heartratecomparison.model.UiDeviceState) -> Unit = { state ->
                if (state.isConnected) {
                    sendServiceCommand("DISCONNECT_DEVICE", "device_address" to state.address)
                }
            }

            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val isTablet = (LocalConfiguration.current.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE

            // 横屏隐藏状态栏和导航栏
            LaunchedEffect(isLandscape) {
                val window = activity?.window ?: return@LaunchedEffect
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (isLandscape) {
                    controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                }
            }

            // 主内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(
                        top = if (isLandscape) 7.dp else statusBarHeight + 7.dp,
                        bottom = if (isLandscape) 7.dp else navigationBarHeight + 7.dp,
                        start = 7.dp,
                        end = 7.dp
                    )
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
                                deviceStates = deviceStates,
                                connectionOrder = connectionOrder,
                                deviceColors = deviceColors
                            )
                        }
                    }
                } else {
                    // 竖屏：上（搜索+设备）下（图表）1:1
                    Column(modifier = Modifier.fillMaxSize()) {
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
                                deviceStates = deviceStates,
                                connectionOrder = connectionOrder,
                                deviceColors = deviceColors
                            )
                        }
                    }
                }
            }
        }
    }
}
