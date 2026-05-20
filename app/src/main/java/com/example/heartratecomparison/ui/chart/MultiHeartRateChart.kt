package com.example.heartratecomparison.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.heartratecomparison.model.UiDeviceState

@Composable
fun MultiHeartRateChart(
    deviceStates: Map<String, UiDeviceState>,
    connectionOrder: List<String>,
    deviceColors: Map<String, Color>
) {
    val connected = connectionOrder.mapNotNull { addr ->
        val state = deviceStates[addr]
        if (state?.isConnected == true) addr to state else null
    }

    if (connected.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无设备连接")
        }
        return
    }

    val hasData = connected.any { it.second.heartRateHistory.isNotEmpty() }
    if (!hasData) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("等待记录…")
        }
        return
    }

    val yMax by remember {
        derivedStateOf {
            val maxHr = connected.flatMap { it.second.heartRateHistory }.maxOrNull() ?: 0
            when {
                maxHr <= 100 -> 100f
                maxHr <= 150 -> 150f
                else -> 220f
            }
        }
    }
    val yMin = 50f
    val density = LocalDensity.current

    Row(modifier = Modifier.fillMaxSize()) {
        // 左侧 30dp 留白区域，Y 轴数值
        Canvas(
            modifier = Modifier
                .width(30.dp)
                .fillMaxHeight()
        ) {
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 26f
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            // 与图表边框保持 3dp 间距
            val offsetX = with(density) { 3.dp.toPx() }
            val height = size.height
            for (bpm in listOf(yMax, (yMin + yMax) / 2, yMin)) {
                val y = height - (bpm - yMin) / (yMax - yMin) * height
                drawContext.canvas.nativeCanvas.drawText(
                    "${bpm.toInt()}",
                    size.width - offsetX,   // 右对齐再左移 3dp
                    y + 8f,
                    textPaint
                )
            }
        }

        // 图表区域（曲线 + 网格 + 填充）
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            val width = size.width
            val height = size.height

            // 坐标轴边框
            val axisColor = Color.DarkGray
            drawLine(axisColor, Offset(0f, 0f), Offset(0f, height), strokeWidth = 3f)
            drawLine(axisColor, Offset(0f, height), Offset(width, height), strokeWidth = 3f)

            // 水平网格线
            val gridColor = Color.LightGray
            for (bpm in yMin.toInt()..yMax.toInt() step 10) {
                val y = height - (bpm - yMin) / (yMax - yMin) * height
                drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
            }

            // 垂直网格线
            val verticalLines = 5
            for (i in 0 until verticalLines) {
                val x = width * i / (verticalLines - 1)
                drawLine(gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
            }

            // 绘制曲线及背景填充
            for ((addr, state) in connected) {
                val history = state.heartRateHistory.takeLast(300)
                if (history.size < 2) continue
                val color = deviceColors[addr] ?: Color.Gray
                val fillColor = color.copy(alpha = 0.15f)

                // 曲线路径
                val linePath = Path()
                history.forEachIndexed { index, hr ->
                    val x = (index.toFloat() / (history.size - 1)) * width
                    val y = height - (hr - yMin) / (yMax - yMin) * height
                    if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                }

                // 填充路径：曲线到底部封闭
                val fillPath = Path().apply {
                    addPath(linePath)
                    lineTo(width, height)
                    lineTo(0f, height)
                    close()
                }

                // 先填充，再描边
                drawPath(fillPath, fillColor, style = Fill)
                drawPath(linePath, color, style = Stroke(width = 3f))
            }
        }
    }
}