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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartratecomparison.R
import com.example.heartratecomparison.model.HeartRatePoint
import com.example.heartratecomparison.ui.theme.LocalChartAxis
import com.example.heartratecomparison.ui.theme.LocalChartGrid

/** 图表数据更新间隔：数据实际变化且距上次更新 ≥ 该值才重建（限帧） */
private const val CHART_UPDATE_INTERVAL_MS = 300L

/**
 * 图表输入变更信号。服务端历史是"追加 + 淘汰"队列且设备内秒值严格递增：
 * 淘汰改首秒，追加改 size/末秒，同秒覆盖改末值 —— 四者均不变时内容必不变，
 * 以此为指纹判断是否需要重建数据。
 */
private data class ChartDataKey(
    val sizes: List<Int>,
    val firstSeconds: List<Long>,
    val lastSeconds: List<Long>,
    val lastValues: List<Int>,
    val colors: List<Color?>
)

/** 单设备对齐到并集秒网格后的曲线：values 与 grid 等长，缺失秒已前向填充，首个样本之前为 NaN */
private class ChartSeries(
    val addr: String,
    val color: Color,
    val values: FloatArray,
    /** 首个有效值的网格索引 */
    val firstIdx: Int
)

/** 一次限帧重建的绘图帧：grid 为各设备采样秒的并集（升序去重、无人上报的秒被压缩），series 与之对齐 */
private class ChartFrame(val grid: LongArray, val series: List<ChartSeries>)

@Composable
fun MultiHeartRateChart(
    connectedAddresses: List<String>,
    heartRateHistories: Map<String, List<HeartRatePoint>>,
    deviceColors: Map<String, Color>,
    recordingStartSecond: Long?
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

    // Y 轴范围：取各连接设备最近 60 点（= 60 秒，每秒最多一点）的极值
    // （原实现包在 derivedStateOf 中，但读取的是普通参数而非 State，缓存并不生效，直接计算等价）
    val recentMax = connectedAddresses
        .flatMap { addr -> heartRateHistories[addr].orEmpty().takeLast(60) }
        .maxOfOrNull { it.heartRate } ?: 0
    val yMax = ((((recentMax + 20).coerceAtMost(220) + 9) / 10) * 10).toFloat()
    val recentMin = connectedAddresses
        .flatMap { addr -> heartRateHistories[addr].orEmpty().takeLast(60) }
        .minOfOrNull { it.heartRate } ?: 0
    val yMin = (((recentMin - 20).coerceAtLeast(40) / 10) * 10).toFloat()
    val density = LocalDensity.current
    val isDark = isSystemInDarkTheme()
    val labelColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val axisColor = LocalChartAxis.current
    val gridColor = LocalChartGrid.current

    // 限帧更新（事件驱动，替代原 100ms 轮询）：仅在数据指纹变化时调度重建，
    // 距上次更新不足 300ms 则先等待；等待期间指纹再变会取消本协程并由新协程接手 → 天然合并。
    // 重建时把各设备历史对齐到"并集秒网格"（语义与历史回放 CsvChartDialog 一致）：
    // 无人上报的秒被压缩，设备缺失的秒沿用上一秒值（前向填充），首个样本之前不绘制。
    var lastUpdateTime by remember { mutableLongStateOf(0L) }
    var chartFrame by remember { mutableStateOf(ChartFrame(LongArray(0), emptyList())) }
    val dataKey = ChartDataKey(
        sizes = connectedAddresses.map { heartRateHistories[it]?.size ?: 0 },
        firstSeconds = connectedAddresses.map { heartRateHistories[it]?.firstOrNull()?.second ?: 0L },
        lastSeconds = connectedAddresses.map { heartRateHistories[it]?.lastOrNull()?.second ?: 0L },
        lastValues = connectedAddresses.map { heartRateHistories[it]?.lastOrNull()?.heartRate ?: 0 },
        colors = connectedAddresses.map { deviceColors[it] }
    )
    LaunchedEffect(dataKey) {
        val elapsed = System.currentTimeMillis() - lastUpdateTime
        if (elapsed < CHART_UPDATE_INTERVAL_MS) delay(CHART_UPDATE_INTERVAL_MS - elapsed)

        val drawable = currentAddresses.mapNotNull { addr ->
            val pts = currentHistories[addr] ?: return@mapNotNull null
            if (pts.size < 2) return@mapNotNull null
            addr to pts
        }
        chartFrame = if (drawable.isEmpty()) {
            ChartFrame(LongArray(0), emptyList())
        } else {
            val grid = sortedSetOf<Long>()
            drawable.forEach { (_, pts) -> pts.forEach { grid.add(it.second) } }
            val seconds = grid.toLongArray()
            val series = drawable.map { (addr, pts) ->
                val values = FloatArray(seconds.size) { Float.NaN }
                var p = 0
                pts.forEach { point ->
                    while (seconds[p] < point.second) p++
                    values[p] = point.heartRate.toFloat()
                }
                var carry = Float.NaN
                for (i in values.indices) {
                    val v = values[i]
                    if (!v.isNaN()) carry = v else if (!carry.isNaN()) values[i] = carry
                }
                ChartSeries(
                    addr = addr,
                    color = currentDeviceColors[addr] ?: Color.Gray,
                    values = values,
                    firstIdx = values.indexOfFirst { !it.isNaN() }
                )
            }
            ChartFrame(seconds, series)
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

    // 时间格式化：录制经过时间，mm:ss；超过 1 小时为 h:mm:ss
    fun formatElapsed(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "$m:${s.toString().padStart(2, '0')}"
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

                // 严格逐秒网格绘制：所有曲线对齐同一网格，X 按网格索引均分（语义同历史回放）
                val frame = chartFrame
                if (frame.grid.size >= 2) {
                    val gridMaxIdx = frame.grid.size - 1

                    // Y 轴范围由最近 60 秒极值决定，越界点裁剪到绘图区内
                    clipRect(0f, 0f, width, height) {
                        // 绘制曲线及背景填充（使用限帧后的数据；Path 每帧 rewind 复用，不再逐设备新建）
                        for (series in frame.series) {
                            val fillColor = series.color.copy(alpha = 0.15f)

                            // 曲线路径：从设备首个样本的网格位置起画（之前无数据不绘制）
                            linePath.rewind()
                            var firstX = 0f
                            for (i in series.firstIdx..gridMaxIdx) {
                                val x = i.toFloat() / gridMaxIdx * width
                                val y = height - (series.values[i] - yMin) / (yMax - yMin) * height
                                if (i == series.firstIdx) {
                                    firstX = x
                                    linePath.moveTo(x, y)
                                } else {
                                    linePath.lineTo(x, y)
                                }
                            }

                            // 填充路径：沿曲线两端垂直落到 X 轴封闭（前向填充至网格末尾，故止于右边界）
                            fillPath.rewind()
                            fillPath.addPath(linePath)
                            fillPath.lineTo(width, height)
                            fillPath.lineTo(firstX, height)
                            fillPath.close()

                            // 先填充，再描边
                            drawPath(fillPath, fillColor, style = Fill)
                            drawPath(linePath, series.color, style = Stroke(width = 3f))
                        }
                    }
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
            // 时间标签：录制经过时间 —— 窗口首/中/末格位距录制开始的秒数。
            // 窗口未满 5 分钟时左端为 0:00（窗口起点=录制起点），录满后窗口开始滑动，左端随之前进。
            // 无录制基准（recordingStartSecond 为空）时退回窗口相对时间（左端恒为 0:00）。
            Row(modifier = Modifier.weight(1f)) {
                val grid = chartFrame.grid
                val hasGrid = grid.size >= 2
                val baseSecond = recordingStartSecond ?: (grid.firstOrNull() ?: 0L)
                val leftSeconds = if (hasGrid) (grid[0] - baseSecond).coerceAtLeast(0L) else 0L
                val midSeconds = if (hasGrid) (grid[(grid.size - 1) / 2] - baseSecond).coerceAtLeast(0L) else 0L
                val totalSeconds = if (hasGrid) (grid[grid.size - 1] - baseSecond).coerceAtLeast(0L) else 1L

                Text(
                    text = formatElapsed(leftSeconds),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.weight(1f))
                if (totalSeconds > 1) {
                    Text(
                        text = formatElapsed(midSeconds),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Text(
                    text = formatElapsed(totalSeconds),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
