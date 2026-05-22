package com.example.heartratecomparison.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartratecomparison.R
import com.example.heartratecomparison.model.UiDeviceState
import com.example.heartratecomparison.ui.theme.LocalChartAxis
import com.example.heartratecomparison.ui.theme.LocalChartGrid

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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.chart_no_device),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.chart_guide_search),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chart_guide_connect),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chart_guide_record),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chart_guide_stop),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chart_guide_history),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chart_guide_share),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        return
    }

    val hasData = connected.any { it.second.heartRateHistory.isNotEmpty() }
    if (!hasData) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chart_waiting))
        }
        return
    }

    val yMax by derivedStateOf {
        val recentMax = deviceStates.values
            .filter { it.isConnected }
            .flatMap { it.heartRateHistory.takeLast(60) }
            .maxOrNull() ?: 0
        val padded = (recentMax + 20).coerceAtMost(220)
        ((padded + 9) / 10 * 10).toFloat()
    }
    val yMin by derivedStateOf {
        val recentMin = deviceStates.values
            .filter { it.isConnected }
            .flatMap { it.heartRateHistory.takeLast(60) }
            .minOrNull() ?: 0
        val padded = (recentMin - 20).coerceAtLeast(40)
        (padded / 10 * 10).toFloat()
    }
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val isDark = isSystemInDarkTheme()
    val labelColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val axisColor = LocalChartAxis.current
    val gridColor = LocalChartGrid.current

    // 限帧：最多每 300ms 更新一次图表数据
    data class ChartDeviceData(val addr: String, val color: Color, val values: List<Int>)
    var throttledData by remember { mutableStateOf(emptyList<ChartDeviceData>()) }
    var lastUpdateTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connected) {
        while (true) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= 300) {
                throttledData = connected.mapNotNull { (addr, state) ->
                    if (state.heartRateHistory.size < 2) return@mapNotNull null
                    val color = deviceColors[addr] ?: Color.Gray
                    ChartDeviceData(addr, color, downsample(state.heartRateHistory, 600))
                }
                lastUpdateTime = now
            }
            delay(100)
        }
    }

    // 时间格式化：不足1分钟显示 "XX s"，超过1分钟显示 "X min"
    fun formatTime(seconds: Int): String {
        return if (seconds < 60) "${seconds} s" else "${seconds / 60} min"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 图表区域（Y轴 + 曲线）
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 左侧 25dp 留白区域，Y 轴数值
            Canvas(
                modifier = Modifier
                    .width(25.dp)
                    .fillMaxHeight()
            ) {
                val textPaint = android.graphics.Paint().apply {
                    color = labelColor
                    // 使用 sp 单位，跟随系统字体缩放
                    textSize = with(density) { 10.sp.toPx() }
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
                drawLine(axisColor, Offset(0f, 0f), Offset(0f, height), strokeWidth = 3f)
                drawLine(axisColor, Offset(0f, height), Offset(width, height), strokeWidth = 3f)

                // 水平网格线（虚线，跳过最底部与X轴重合的线）
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                for (bpm in (yMin.toInt() + 10)..yMax.toInt() step 10) {
                    val y = height - (bpm - yMin) / (yMax - yMin) * height
                    drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f, pathEffect = dashEffect)
                }

                // 垂直网格线（虚线，跳过最左侧与Y轴重合的线）
                val verticalLines = 5
                for (i in 1 until verticalLines) {
                    val x = width * i / (verticalLines - 1)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1f, pathEffect = dashEffect)
                }

                // 绘制曲线及背景填充（使用限帧+降采样后的数据）
                for (device in throttledData) {
                    val history = device.values
                    val fillColor = device.color.copy(alpha = 0.15f)

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
                    drawPath(linePath, device.color, style = Stroke(width = 3f))
                }
            }
        }

        // 底部时间标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        ) {
            // 与 Y 轴等宽的占位
            Spacer(modifier = Modifier.width(25.dp))
            // 时间标签
            Row(modifier = Modifier.weight(1f)) {
                val maxHistorySize = connected.maxOfOrNull { it.second.heartRateHistory.size } ?: 0
                val totalSeconds = maxHistorySize.coerceAtLeast(1)

                Text(
                    text = formatTime(0),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (totalSeconds > 1) {
                    Text(
                        text = formatTime(totalSeconds / 2),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Text(
                    text = formatTime(totalSeconds),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * 降采样：将数据按目标数量分桶，每桶保留 min 和 max，保留曲线形状
 */
private fun downsample(data: List<Int>, targetCount: Int): List<Int> {
    if (data.size <= targetCount) return data
    val bucketSize = data.size.toFloat() / targetCount
    val result = mutableListOf<Int>()
    var i = 0
    while (i < data.size) {
        val end = minOf((i + bucketSize).toInt(), data.size)
        var bucketMin = data[i]
        var bucketMax = data[i]
        for (j in i until end) {
            if (data[j] < bucketMin) bucketMin = data[j]
            if (data[j] > bucketMax) bucketMax = data[j]
        }
        result.add(bucketMin)
        if (bucketMax != bucketMin) result.add(bucketMax)
        i = end
    }
    return result
}
