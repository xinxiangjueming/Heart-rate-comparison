package com.example.heartratecomparison.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
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
    onClick: () -> Unit,
    onLongClick: () -> Unit
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
            .border(1.dp, borderColor, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                if (deviceState.isConnected &&
                    (deviceState.batteryLevel != null || deviceState.heartRate != null)
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
                            Text(
                                text = "$level%",
                                color = infoColor,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
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
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = stringResource(R.string.desc_bluetooth),
                tint = if (deviceState.isConnected) BluetoothConnected else BluetoothDisconnected,
                modifier = Modifier.weight(1f)
            )
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

/**
 * 自绘心率图标：心形 + 脉搏线
 * 心形固定红色，脉搏线叠加在心形上
 */
@Composable
private fun HeartRateIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // ── 心形 ──
        val heartPath = Path().apply {
            // 心形用两段贝塞尔曲线构成
            val topY = h * 0.20f
            val centerX = w * 0.50f
            val bottomY = h * 0.95f
            val cuspX = w * 0.06f
            val cuspY = h * 0.02f

            moveTo(centerX, bottomY)
            // 左半心
            cubicTo(
                centerX - w * 0.30f, h * 0.70f,
                -w * 0.12f, h * 0.35f,
                cuspX, topY
            )
            // 左上圆弧
            cubicTo(
                cuspX, cuspY,
                centerX, cuspY,
                centerX, topY + h * 0.12f
            )
            // 右上圆弧
            cubicTo(
                centerX, cuspY,
                w - cuspX, cuspY,
                w - cuspX, topY
            )
            // 右半心
            cubicTo(
                w + w * 0.12f, h * 0.35f,
                centerX + w * 0.30f, h * 0.70f,
                centerX, bottomY
            )
            close()
        }
        drawPath(heartPath, color = color.copy(alpha = 0.20f), style = Fill)
        drawPath(heartPath, color = color.copy(alpha = 0.70f), style = Stroke(width = h * 0.06f))

        // ── 脉搏线（叠加在心形中间） ──
        val pulseY = h * 0.48f
        val amp = h * 0.18f
        val pulsePath = Path().apply {
            moveTo(0f, pulseY)
            lineTo(w * 0.22f, pulseY)
            // R 波峰
            lineTo(w * 0.34f, pulseY - amp * 0.55f)
            lineTo(w * 0.44f, pulseY + amp * 0.80f)
            lineTo(w * 0.52f, pulseY - amp)
            lineTo(w * 0.62f, pulseY + amp * 0.45f)
            lineTo(w * 0.72f, pulseY)
            lineTo(w, pulseY)
        }
        drawPath(
            pulsePath,
            color = color,
            style = Stroke(
                width = h * 0.07f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
