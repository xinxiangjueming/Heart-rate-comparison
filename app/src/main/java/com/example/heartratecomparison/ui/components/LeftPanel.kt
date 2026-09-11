package com.example.heartratecomparison.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.heartratecomparison.R
import com.example.heartratecomparison.model.UiDeviceState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 记录中图标色：比主题主色(0xFFE53935)更深一档的红色，用于状态区分 */
private val RecordingRed = Color(0xFFC62828)

/** 历史图标色：蓝色，与红色主题区分 */
private val HistoryBlue = Color(0xFF1E88E5)

/** 记录中停止记录的长按时长 */
private const val STOP_RECORD_HOLD_MS = 3000L

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
    // 长按停止的进度（0→1，3 秒走满触发停止）
    val stopProgress = remember { Animatable(0f) }
    var stopPressing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.padding(8.dp)
    ) {
        // —— 三个等宽纯图标按钮（无背景色块）—— 布局：左=历史 中=搜索 右=记录
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. 历史（最左）：点击查看历史
            IconButton(
                onClick = onShowHistory,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = stringResource(R.string.cd_history),
                    tint = HistoryBlue,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 2. 搜索（中间）：点击扫描；扫描中隐藏图标、仅显示进度环
            IconButton(
                onClick = onScanClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.cd_search),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 3. 记录（最右）：未记录=点击开始；记录中=长按3秒停止（按住时显示进度环，短按不触发）
            val recordEnabled = isRecording || hasConnectedDevices
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .pointerInput(Unit) {
                            coroutineScope {
                            awaitEachGesture {
                                awaitFirstDown()
                                stopPressing = true
                                val job = launch {
                                    stopProgress.snapTo(0f)
                                    stopProgress.animateTo(1f, animationSpec = tween(STOP_RECORD_HOLD_MS.toInt()))
                                    onStopRecord()
                                }
                                // 等待手指抬起；3 秒到则触发停止（job 完成）
                                var up = false
                                while (!up) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.all { it.changedToUp() }) up = true
                                }
                                job.cancel()
                                stopPressing = false
                            }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.cd_record_stop),
                        tint = RecordingRed,
                        modifier = Modifier.size(20.dp)
                    )
                    // 长按进度环：按住时显示，3 秒走满停止记录
                    if (stopPressing) {
                        CircularProgressIndicator(
                            progress = { stopProgress.value },
                            modifier = Modifier.size(34.dp),
                            color = RecordingRed,
                            trackColor = RecordingRed.copy(alpha = 0.15f),
                            strokeWidth = 2.5.dp
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onStartRecord,
                    enabled = recordEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = stringResource(R.string.cd_record),
                        tint = if (recordEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(deviceStates, key = { it.address }) { state ->
                // 回调记忆化：state 未变时实例稳定，配合全稳定类型的 UiDeviceState 让 DeviceItem 跳过重组
                val itemClick = remember(state) { { onDeviceClick(state) } }
                val itemLongClick = remember(state) { { onDeviceLongClick(state) } }
                DeviceItem(
                    deviceState = state,
                    isRecording = isRecording,
                    nameColor = deviceColors[state.address],
                    onClick = itemClick,
                    onLongClick = itemLongClick
                )
            }
        }
    }
}
