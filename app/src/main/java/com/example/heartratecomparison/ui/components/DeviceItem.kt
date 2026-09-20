package com.example.heartratecomparison.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.heartratecomparison.R
import com.example.heartratecomparison.model.UiDeviceState
import com.example.heartratecomparison.ui.theme.LocalDeviceCardBg
import com.example.heartratecomparison.ui.theme.LocalDeviceCardBorder
import com.example.heartratecomparison.ui.theme.BluetoothConnected
import com.example.heartratecomparison.ui.theme.BluetoothDisconnected

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceItem(
    deviceState: UiDeviceState,
    isRecording: Boolean,
    nameColor: Color?,
    onItemClick: () -> Unit,
    onIconClick: () -> Unit,
    onIconLongClick: () -> Unit
) {
    val textColor = if (isRecording && nameColor != null) nameColor else Color.Unspecified
    val shape = MaterialTheme.shapes.small
    val cardBg = LocalDeviceCardBg.current
    val borderColor = LocalDeviceCardBorder.current

    val infoColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,   // 不显示水波纹
                    onClick = onItemClick
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(4f)) {
                Text(
                    text = deviceState.name,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 连接后电池/心率信息行：淡入 + 纵向展开过渡
                AnimatedVisibility(
                    visible = deviceState.isConnected &&
                        (deviceState.batteryLevel != null || deviceState.heartRate != null),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        deviceState.batteryLevel?.let { level ->
                            val batteryDesc = stringResource(R.string.label_battery)
                            BatteryIcon(
                                level = level,
                                color = infoColor,
                                modifier = Modifier
                                    .size(width = 24.dp, height = 12.dp)
                                    .semantics { contentDescription = "$batteryDesc $level%" }
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Box {
                                // 占位文本："100%" 为最宽情形，撑出固定宽度，
                                // 使不同电量的设备心率图标仍保持左对齐
                                Text(
                                    text = "100%",
                                    color = Color.Transparent,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.clearAndSetSemantics { }
                                )
                                Text(
                                    text = "$level%",
                                    color = infoColor,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                        }
                        if (deviceState.batteryLevel != null && deviceState.heartRate != null) {
                            Spacer(modifier = Modifier.width(14.dp))
                        }
                        deviceState.heartRate?.let { hr ->
                            val hrDesc = stringResource(R.string.label_heart_rate)
                            HeartRateIcon(
                                color = Color(0xFFEF5350),
                                modifier = Modifier
                                    .size(width = 22.dp, height = 14.dp)
                                    .semantics { contentDescription = "$hrDesc $hr" }
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "$hr",
                                color = infoColor,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,   // 不显示水波纹
                        onClick = onIconClick,
                        onLongClick = onIconLongClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Bluetooth,
                    contentDescription = stringResource(R.string.desc_bluetooth),
                    tint = if (deviceState.isConnected) BluetoothConnected else BluetoothDisconnected,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 自绘电池图标：圆角矩形机身 + 小凸起 + 根据电量填充
 * 电量颜色：<20% 红色 / 20-50% 橙色 / >50% 绿色
 */
@Composable
private fun BatteryIcon(
    level: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 电池机身
        val bodyW = w * 0.80f
        val bodyH = h
        val capW = w * 0.06f
        val capH = h * 0.40f
        val cornerRadius = CornerRadius(h * 0.25f, h * 0.25f)
        val strokeW = h * 0.08f

        // 绘制机身边框
        drawRoundRect(
            color = color.copy(alpha = 0.6f),
            size = Size(bodyW, bodyH),
            cornerRadius = cornerRadius,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 绘制右侧凸起（电池帽）
        val capX = bodyW + strokeW * 0.5f
        val capY = (bodyH - capH) / 2f
        drawRoundRect(
            color = color.copy(alpha = 0.6f),
            topLeft = Offset(capX, capY),
            size = Size(capW, capH),
            cornerRadius = CornerRadius(capW * 0.5f, capW * 0.5f),
            style = Fill
        )

        // 电量填充
        val levelFraction = (level.coerceIn(0, 100) / 100f)
        val pad = strokeW + h * 0.08f
        val fillW = (bodyW - pad * 2) * levelFraction
        val fillH = bodyH - pad * 2

        if (fillW > 0f) {
            val fillColor = when {
                level <= 20 -> Color(0xFFEF5350)   // 红色
                level <= 50 -> Color(0xFFFFA726)   // 橙色
                else        -> Color(0xFF66BB6A)   // 绿色
            }
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(pad, pad),
                size = Size(fillW, fillH),
                cornerRadius = CornerRadius(h * 0.12f, h * 0.12f),
                style = Fill
            )
        }
    }
}

// Material `Favorite` 基准路径：24×24 视口，内容外接框 20 × 18.35（x 2→22，y 3→21.35）
private const val HEART_VP_LEFT = 2f
private const val HEART_VP_TOP = 3f
private const val HEART_BASE_WIDTH = 20f
private const val HEART_BASE_HEIGHT = 18.35f

/** Material Icons `Favorite` 心形路径（24×24 视口）；只读复用，绘制时由 DrawScope 缩放 */
private val heartPath24: Path = Path().apply {
    moveTo(12f, 21.35f)
    relativeLineTo(-1.45f, -1.32f)
    cubicTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
    cubicTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
    relativeCubicTo(1.74f, 0f, 3.41f, 0.81f, 4.5f, 2.09f)
    cubicTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
    cubicTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
    relativeCubicTo(0f, 3.78f, -3.4f, 6.86f, -8.55f, 11.54f)
    lineTo(12f, 21.35f)
    close()
}

/**
 * 自绘心率图标：等比心形轮廓 + ECG 脉搏线
 *
 * 几何口径（均为图标框 w × h 的比例，不做任何非等比拉伸）：
 * - 心形取 Material `Favorite` 基准路径（24×24 视口内内容占 20 × 18.35，宽高比 1.09），
 *   等比缩放到 0.94h 高、水平居中 —— 旧实现把心形横向拉到满宽，实际宽高比达 1.72，
 *   心瓣被压成扁平弧、缺口深达 27%，是"失真"的根因；
 * - ECG 折线横贯图标宽度，基线 0.58h，R 峰 -0.264h / S 谷 +0.212h，
 *   与心形轮廓交叉两次，保留原有 22 × 14 dp 构图。
 * - 颜色一律用实色不透明，不再用低透明度填充（旧实现 20% 填充 + 70% 描边导致整体发虚）。
 */
@Composable
private fun HeartRateIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // ── 等比心形 ──
        val heartScale = (h * 0.94f) / HEART_BASE_HEIGHT
        val heartW = HEART_BASE_WIDTH * heartScale
        val heartLeft = (w - heartW) / 2f
        val heartTop = h * 0.03f

        translate(left = heartLeft, top = heartTop) {
            scale(scaleX = heartScale, scaleY = heartScale, pivot = Offset.Zero) {
                translate(left = -HEART_VP_LEFT, top = -HEART_VP_TOP) {
                    drawPath(
                        path = heartPath24,
                        color = color,
                        style = Stroke(
                            // 缩放作用域内的线宽会被 heartScale 放大，先除回去
                            width = 1.25.dp.toPx() / heartScale,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }

        // ── ECG 脉搏线 ──
        val baseY = h * 0.58f
        val pulsePath = Path().apply {
            moveTo(0f, baseY)
            lineTo(w * 0.197f, baseY)
            lineTo(w * 0.273f, baseY - h * 0.057f)
            lineTo(w * 0.349f, baseY + h * 0.082f)
            lineTo(w * 0.424f, baseY - h * 0.264f)   // R 峰
            lineTo(w * 0.500f, baseY + h * 0.212f)   // S 谷
            lineTo(w * 0.583f, baseY - h * 0.054f)
            lineTo(w * 0.682f, baseY)
            lineTo(w, baseY)
        }
        drawPath(
            path = pulsePath,
            color = color,
            style = Stroke(
                width = 1.05.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
