package com.example.heartratecomparison.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartratecomparison.R
import com.example.heartratecomparison.model.UiDeviceState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "LeftPanel"

@Composable
fun LeftPanel(
    modifier: Modifier = Modifier,
    isScanning: Boolean,
    isRecording: Boolean,
    hasConnectedDevices: Boolean,
    deviceStates: List<UiDeviceState>,
    deviceColors: Map<String, Color>,
    onScanClick: () -> Unit,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onShowHistory: () -> Unit,
    onDeviceClick: (UiDeviceState) -> Unit,
    onDeviceLongClick: (UiDeviceState) -> Unit = {}
) {
    // 让手势内部始终能拿到最新的状态值
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentIsScanning by rememberUpdatedState(isScanning)
    val currentHasConnectedDevices by rememberUpdatedState(hasConnectedDevices)

    Column(
        modifier = modifier.padding(8.dp)
    ) {
        // 搜索 / 记录按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    coroutineScope {
                        awaitEachGesture {
                            awaitFirstDown()
                            var longPressTriggered = false
                            val startTime = System.currentTimeMillis()

                            val job = launch {
                                while (true) {
                                    delay(50)
                                    val elapsed = System.currentTimeMillis() - startTime
                                    if (!longPressTriggered) {
                                        if (currentIsRecording && elapsed >= 3000) {
                                            Log.d(TAG, "长按3秒 → 停止记录")
                                            onStopRecord()
                                            longPressTriggered = true
                                            break
                                        } else if (!currentIsRecording && currentHasConnectedDevices && elapsed >= 1500) {
                                            Log.d(TAG, "长按1.5秒 → 开始记录")
                                            onStartRecord()
                                            longPressTriggered = true
                                            break
                                        } else if (!currentIsRecording && !currentHasConnectedDevices && elapsed >= 1500) {
                                            Log.d(TAG, "长按1.5秒 → 查看历史")
                                            onShowHistory()
                                            longPressTriggered = true
                                            break
                                        }
                                    }
                                }
                            }

                            // 等待所有手指抬起
                            var up = false
                            while (!up) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { it.changedToUp() }) {
                                    up = true
                                }
                            }
                            job.cancel()

                            val duration = System.currentTimeMillis() - startTime
                            if (!longPressTriggered && duration < 300) {
                                Log.d(TAG, "短按 → 切换搜索")
                                onScanClick()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    currentIsScanning -> stringResource(R.string.btn_scanning)
                    currentIsRecording -> stringResource(R.string.btn_recording)
                    else -> stringResource(R.string.btn_search)
                },
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(deviceStates, key = { it.address }) { state ->
                DeviceItem(
                    deviceState = state,
                    isRecording = currentIsRecording,
                    nameColor = deviceColors[state.address],
                    onClick = { onDeviceClick(state) },
                    onLongClick = { onDeviceLongClick(state) }
                )
            }
        }
    }
}