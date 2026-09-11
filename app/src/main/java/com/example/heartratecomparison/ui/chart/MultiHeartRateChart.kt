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
import com.example.heartratecomparison.ui.theme.LocalChartAxis
import com.example.heartratecomparison.ui.theme.LocalChartGrid

/** 图表数据更新间隔：数据实际变化且距上次更新 ≥ 该值才重建（限帧） */
private const val CHART_UPDATE_INTERVAL_MS = 300L

/**
 * 图表输入变更信号。服务端历史是"追加 + 淘汰"队列：size、首值、末值均不变时，
 * 内容必不变（淘汰改首值，追加改末值/size），因此以此为指纹判断是否需要重建数据。
 */
private data class ChartDataKey(
    val sizes: List<Int>,
    val firsts: List<Int>,
    val lasts: List<Int>,
    val colors: List<Color?>
)

@Composable
fun MultiHeartRateChart(
    connectedAddresses: List<String>,
    heartRateHistories: Map<String, List<Int>>,
    deviceColors: Map<String, Color>
) {
    // 协程常驻后仍需读到每次重组后的最新值（否则闭包捕获旧引用）
    val currentAddresses by rememberUpdatedState(connectedAddresses)
    val currentHistories by rememberUpdatedState(heartRateHistories)
    val currentDeviceColors by rememberUpdatedState(deviceColors)

    if (connectedAddresses.isEmpty()) {
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

    val hasData = connectedAddresses.any { heartRateHistories[it]?.isNotEmpty() == true }
    if (!hasData) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chart_waiting))
        }
        return
    }

    // Y 轴范围：取各连接设备最近 60 点的极值（原实现包在 derivedStateOf 中，
    // 但读取的是普通参数而非 State，缓存并不生效，直接计算等价）
    val recentMax = connectedAddresses
        .flatMap { addr -> heartRateHistories[addr].orEmpty().takeLast(60) }
        .maxOrNull() ?: 0
    val yMax = ((((recentMax + 20).coerceAtMost(220) + 9) / 10) * 10).toFloat()
    val recentMin = connectedAddresses
        .flatMap { addr -> heartRateHistories[addr].orEmpty().takeLast(60) }
        .minOrNull() ?: 0
    val yMin = (((recentMin - 20).coerceAtLeast(40) / 10) * 10).toFloat()
    val density = LocalDensity.current
    val isDark = isSystemInDarkTheme()
    val labelColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val axisColor = LocalChartAxis.current
    val gridColor = LocalChartGrid.current

    // 限帧更新（事件驱动，替代原 100ms 轮询）：仅在数据指纹变化时调度重建，
    // 距上次更新不足 300ms 则先等待；等待期间指纹再变会取消本协程并由新协程接手 → 天然合并
    data class ChartDeviceData(val addr: String, val color: Color, val values: List<Int>)
    var throttledData by remember { mutableStateOf(emptyList<ChartDeviceData>()) }
    var lastUpdateTime by remember { mutableLongStateOf(0L) }
    val dataKey = ChartDataKey(
        sizes = connectedAddresses.map { heartRateHistories[it]?.size ?: 0 },
        firsts = connectedAddresses.map { heartRateHistories[it]?.firstOrNull() ?: 0 },
        lasts = connectedAddresses.map { heartRateHistories[it]?.lastOrNull() ?: 0 },
        colors = connectedAddresses.map { deviceColors[it] }
    )
    LaunchedEffect(dataKey) {
        val elapsed = System.currentTimeMillis() - lastUpdateTime
        if (elapsed < CHART_UPDATE_INTERVAL_MS) delay(CHART_UPDATE_INTERVAL_MS - elapsed)
        throttledData = currentAddresses.mapNotNull { addr ->
            val history = currentHistories[addr] ?: return@mapNotNull null
            if (history.size < 2) return@mapNotNull null
            ChartDeviceData(addr, currentDeviceColors[addr] ?: Color.Gray, history)
        }
        lastUpdateTime = System.currentTimeMillis()
    }

    // 每帧可复用对象缓存（避免 draw pass 反复分配）
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) }
    val linePath = remember { Path() }
    val fillPath = remember { Path() }
    val textPaint = remember(labelColor, density) {
        android.graphics.Paint().apply {
            color = labelColor
            textSize = with(density) { 10.sp.toPx() }
            textAlign = android.graphics.Paint.Align.RIGHT
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

                // 绘制曲线及背景填充（使用限帧后的数据；Path 每帧 rewind 复用，不再逐设备新建）
                for (device in throttledData) {
                    val history = device.values
                    val fillColor = device.color.copy(alpha = 0.15f)

                    // 曲线路径
                    linePath.rewind()
                    history.forEachIndexed { index, hr ->
                        val x = (index.toFloat() / (history.size - 1)) * width
                        val y = height - (hr - yMin) / (yMax - yMin) * height
                        if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                    }

                    // 填充路径：曲线到底部封闭
                    fillPath.rewind()
                    fillPath.addPath(linePath)
                    fillPath.lineTo(width, height)
                    fillPath.lineTo(0f, height)
                    fillPath.close()

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
                val maxHistorySize = connectedAddresses.maxOfOrNull { heartRateHistories[it]?.size ?: 0 } ?: 0
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
