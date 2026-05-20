package com.example.heartratecomparison.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

    // 震动器
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

    var showExitDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        // 电池优化改为按需申请，见 onStartRecord
    }

    BackHandler(enabled = isRecording) {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.dialog_exit_title)) },
            text = { Text(stringResource(R.string.dialog_exit_message)) },
            confirmButton = {
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
            },
            dismissButton = {
                Button(
                    onClick = { showExitDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.outline)
                ) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = statusBarHeight + 7.dp,
                bottom = navigationBarHeight + 7.dp,
                start = 7.dp,
                end = 7.dp
            )
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            LeftPanel(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                isScanning = isScanning,
                isRecording = isRecording,
                deviceStates = deviceStates.values.toList(),
                deviceColors = deviceColors,
                onScanClick = {
                    val needRequest = permissions.any {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (needRequest) {
                        permissionLauncher.launch(permissions)
                    } else {
                        sendServiceCommand("TOGGLE_SCAN")
                    }
                },
                onStartRecord = {
                    // 电量无限制：按需申请
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                    // 短震 50ms
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(50)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "缺少震动权限")
                    }
                    sendServiceCommand("START_RECORDING")
                },
                onStopRecord = {
                    // 长震 3s
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(3000)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "缺少震动权限")
                    }
                    sendServiceCommand("STOP_RECORDING")
                },
                onDeviceClick = { state ->
                    sendServiceCommand("CONNECT_DEVICE", "device_address" to state.address)
                },
                onDeviceLongClick = { state ->
                    if (state.isConnected) {
                        sendServiceCommand("DISCONNECT_DEVICE", "device_address" to state.address)
                    }
                }
            )

            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(15.dp)
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