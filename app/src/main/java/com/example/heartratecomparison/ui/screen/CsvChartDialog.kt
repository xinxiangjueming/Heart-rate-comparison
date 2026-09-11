package com.example.heartratecomparison.ui.screen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.heartratecomparison.R
import com.example.heartratecomparison.data.HeartRateDatabase
import com.example.heartratecomparison.data.RecordDao
import com.example.heartratecomparison.ui.theme.ChartColors
import com.example.heartratecomparison.ui.theme.LocalChartAxis
import com.example.heartratecomparison.ui.theme.LocalChartGrid
import java.text.SimpleDateFormat
import java.util.*

private val chartColors = ChartColors

@Composable
fun CsvChartScreen(
    sessionId: Long,
    onBack: () -> Unit,
    /** 全屏数据查看时 true：锁定横屏 + 隐藏系统栏；大屏分栏（历史列表可见）时为 false，保持系统栏 */
    immersive: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isDark = isSystemInDarkTheme()
    val density = LocalDensity.current

    // 全屏数据查看时锁定横屏、隐藏状态栏，退出时恢复；分栏模式不做任何系统栏/方向改动
    DisposableEffect(Unit) {
        if (immersive) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val window = activity?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (immersive) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                val win = activity?.window
                if (win != null) {
                    val ctrl = WindowInsetsControllerCompat(win, win.decorView)
                    ctrl.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    BackHandler { onBack() }

    var parsed by remember { mutableStateOf<CsvParsed?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var titleText by remember { mutableStateOf("") }
    LaunchedEffect(sessionId) {
        isLoading = true
        val dao = HeartRateDatabase.getInstance(context).recordDao()
        val session = dao.getSession(sessionId)
        titleText = session?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it.startTime))
        } ?: ""
        parsed = withContext(Dispatchers.IO) { buildParsedFromRoom(dao, sessionId) }
        isLoading = false
    }
    var hiddenDevices by remember { mutableStateOf(setOf<Int>()) }

    val axisColor = LocalChartAxis.current
    val gridColor = LocalChartGrid.current
    val labelColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK

    // 每帧复用的绘制对象（原实现手势期间每帧新建 Paint/PathEffect/Path，60fps 重绘下 GC 压力大）
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) }
    val yLabelPaint = remember(labelColor, density) {
        android.graphics.Paint().apply {
            color = labelColor
            textSize = with(density) { 10.sp.toPx() }
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    val mainPath = remember { Path() }
    val fillPath = remember { Path() }
    val edgePath = remember { Path() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(7.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏：返回 + 标题 + 图例
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                parsed?.columns?.forEachIndexed { index, col ->
                    val isHidden = index in hiddenDevices
                    val visibleCount = ((parsed?.columns?.size ?: 0) - hiddenDevices.size)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 14.dp, start = 2.dp, end = 2.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                if (isHidden) {
                                    hiddenDevices = hiddenDevices - index
                                } else if (visibleCount > 1) {
                                    hiddenDevices = hiddenDevices + index
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.size(12.dp)) {
                            drawCircle(chartColors[index % chartColors.size].copy(alpha = if (isHidden) 0.2f else 1f))
                        }
                        Text(
                            text = col.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isHidden) 0.3f else 1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 图表
            val parsedData = parsed
            if (parsedData == null || parsedData.columns.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        text = stringResource(R.string.chart_parse_failed),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                val yMin = parsedData.globalMin * 0.95f
                val yMax = parsedData.globalMax * 1.05f

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(start = 15.dp, top = 25.dp, end = 15.dp, bottom = 15.dp)
                ) {
                    // 缩放状态（图表和时间标签共享）
                    val maxDataPoints = parsedData.columns.maxOfOrNull { it.values.size } ?: 0
                    var zoomLevel by remember { mutableFloatStateOf(1f) }
                    var panOffset by remember { mutableFloatStateOf(0f) }

                    // 图表区域（支持手势缩放和滑动）
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Y 轴标签（固定显示完整数据范围）
                        Canvas(
                            modifier = Modifier
                                .width(20.dp)
                                .fillMaxHeight()
                        ) {
                            val h = size.height
                            for (bpm in listOf(yMax, (yMin + yMax) / 2, yMin)) {
                                val y = h - (bpm - yMin) / (yMax - yMin) * h
                                drawContext.canvas.nativeCanvas.drawText(
                                    "${bpm.toInt()}",
                                    size.width - 4.dp.toPx(),
                                    y + 8f,
                                    yLabelPaint
                                )
                            }
                        }

                        // 曲线区域（支持缩放、滑动、长按重置）


                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .graphicsLayer(clip = true)
                                .pointerInput(maxDataPoints) {
                                    if (maxDataPoints < 2) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val downPos = down.position
                                        var moved = false

                                        // 3 秒超时直接重置缩放
                                        try {
                                            withTimeout(3000) {
                                                do {
                                                    val event = awaitPointerEvent()

                                                    for (change in event.changes) {
                                                        if ((change.position - downPos).getDistance() > 10f) {
                                                            moved = true
                                                        }
                                                    }

                                                    // 缩放 + 滑动处理
                                                    if (event.changes.size >= 2) {
                                                        val currentDist = (event.changes[0].position - event.changes[1].position).getDistance()
                                                        val prevDist = (event.changes[0].previousPosition - event.changes[1].previousPosition).getDistance()
                                                        if (prevDist > 0f) {
                                                            val zoom = currentDist / prevDist
                                                            val newZoom = (zoomLevel * zoom).coerceIn(1f, 20f)
                                                            val visibleCount = maxDataPoints / newZoom
                                                            val centerIndex = panOffset + visibleCount / 2f
                                                            zoomLevel = newZoom
                                                            val newVisibleCount = maxDataPoints / zoomLevel
                                                            panOffset = (centerIndex - newVisibleCount / 2f)
                                                                .coerceIn(0f, (maxDataPoints - newVisibleCount).coerceAtLeast(0f))
                                                            moved = true
                                                        }
                                                    } else if (moved) {
                                                        val pan = event.changes.firstOrNull()?.let {
                                                            it.position - it.previousPosition
                                                        } ?: Offset.Zero
                                                        if (zoomLevel > 1f) {
                                                            val newVisibleCount = maxDataPoints / zoomLevel
                                                            val pixelsPerPoint = size.width / newVisibleCount
                                                            val indexDelta = -pan.x / pixelsPerPoint
                                                            panOffset = (panOffset + indexDelta)
                                                                .coerceIn(0f, (maxDataPoints - newVisibleCount).coerceAtLeast(0f))
                                                        }
                                                    }
                                                } while (event.changes.any { it.pressed })
                                            }
                                        } catch (_: CancellationException) {
                                            // 3 秒超时 → 重置缩放
                                            if (!moved) {
                                                zoomLevel = 1f
                                                panOffset = 0f
                                            }
                                        }
                                    }
                                }
                        ) {
                            val w = size.width
                            val h = size.height
                            if (maxDataPoints < 2) return@Canvas

                            // 可见范围
                            val visibleCount = (maxDataPoints / zoomLevel).coerceAtLeast(2f)
                            val visStart = panOffset
                            val visEnd = panOffset + visibleCount

                            // 可见数据的 Y 范围（块级预聚合：完整块 O(1)，仅两端残余块扫描，
                            // 替代原 O(可见点×列数) 逐点扫描，长会话手势不再随数据量变慢）
                            var visMin = Float.MAX_VALUE
                            var visMax = -Float.MAX_VALUE
                            parsedData.columns.forEachIndexed { colIndex, col ->
                                if (colIndex in hiddenDevices) return@forEachIndexed
                                val startIdx = visStart.toInt().coerceIn(0, col.values.size - 1)
                                val endIdx = visEnd.toInt().coerceIn(0, col.values.size - 1)
                                val (mn, mx) = col.minMax(startIdx, endIdx)
                                if (mn < visMin) visMin = mn
                                if (mx > visMax) visMax = mx
                            }
                            if (visMin > visMax) { visMin = yMin; visMax = yMax }
                            val yPad = (visMax - visMin) * 0.05f
                            val adjYMin = (visMin - yPad).coerceAtLeast(0f)
                            val adjYMax = visMax + yPad

                            // 网格线
                            val step = 10
                            val gridStart = ((adjYMin / step).toInt() + 1) * step
                            for (bpm in gridStart..adjYMax.toInt() step step) {
                                val y = h - (bpm - adjYMin) / (adjYMax - adjYMin) * h
                                if (y in 0f..h) {
                                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dashEffect)
                                }
                            }

                            // 垂直网格线
                            val vertLines = 5
                            for (i in 1 until vertLines) {
                                val x = w * i / (vertLines - 1)
                                drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f, pathEffect = dashEffect)
                            }

                            // 坐标轴
                            drawLine(axisColor, Offset(0f, 0f), Offset(0f, h), strokeWidth = 3f)
                            drawLine(axisColor, Offset(0f, h), Offset(w, h), strokeWidth = 3f)

                            // 绘制曲线（仅可见范围，分段绘制避免首尾连接）
                            fun scaledX(dataIndex: Int): Float {
                                return ((dataIndex - visStart) / (visEnd - visStart)) * w
                            }
                            fun yForValue(v: Float): Float {
                                return h - (v - adjYMin) / (adjYMax - adjYMin) * h
                            }

                            parsedData.columns.forEachIndexed { colIndex, col ->
                                if (colIndex in hiddenDevices) return@forEachIndexed
                                if (col.values.size < 2) return@forEachIndexed
                                val color = chartColors[colIndex % chartColors.size]
                                val fillColor = color.copy(alpha = 0.15f)
                                // mainPath/fillPath/edgePath 为 remember 的复用对象，每列先 rewind
                                mainPath.rewind()
                                var started = false

                                val iStart = visStart.toInt().coerceIn(0, col.values.size - 1)
                                val iEnd = (visEnd.toInt() + 1).coerceIn(0, col.values.size - 1)

                                for (i in iStart..iEnd) {
                                    val x = scaledX(i)
                                    val y = yForValue(col.values[i])
                                    if (!started) { mainPath.moveTo(x, y); started = true }
                                    else mainPath.lineTo(x, y)
                                }

                                // 构建填充路径：从左边界开始，沿曲线，到右边界，封闭到底部
                                fillPath.rewind()

                                // 左边界插值（填充与描边共用）
                                val leftClip = iStart > 0 && visStart > iStart
                                val leftY = if (leftClip) {
                                    val prev = col.values[iStart - 1]
                                    val curr = col.values[iStart]
                                    prev + (curr - prev) * (visStart - iStart)
                                } else {
                                    col.values[iStart].toFloat()
                                }
                                fillPath.moveTo(0f, yForValue(leftY))

                                // 连接到主曲线
                                for (i in iStart..iEnd) {
                                    fillPath.lineTo(scaledX(i), yForValue(col.values[i]))
                                }

                                // 右边界插值（填充与描边共用）
                                val lastIdx = col.values.size - 1
                                val rightClip = iEnd < lastIdx && visEnd < lastIdx
                                val rightY = if (rightClip) {
                                    col.values[iEnd] + (col.values[iEnd + 1] - col.values[iEnd]) * (visEnd - iEnd)
                                } else {
                                    col.values[iEnd].toFloat()
                                }
                                fillPath.lineTo(w, yForValue(rightY))

                                // 封闭到底部
                                fillPath.lineTo(w, h)
                                fillPath.lineTo(0f, h)
                                fillPath.close()

                                // 先填充，再描边
                                drawPath(fillPath, fillColor, style = Fill)

                                // 左边界描边
                                if (leftClip) {
                                    edgePath.rewind()
                                    edgePath.moveTo(0f, yForValue(leftY))
                                    edgePath.lineTo(scaledX(iStart), yForValue(col.values[iStart]))
                                    drawPath(edgePath, color, style = Stroke(width = 3f))
                                }

                                // 右边界描边
                                if (rightClip) {
                                    edgePath.rewind()
                                    edgePath.moveTo(scaledX(iEnd), yForValue(col.values[iEnd]))
                                    edgePath.lineTo(w, yForValue(rightY))
                                    drawPath(edgePath, color, style = Stroke(width = 3f))
                                }

                                drawPath(mainPath, color, style = Stroke(width = 3f))
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
                        Spacer(modifier = Modifier.width(20.dp))
                        // 时间标签（跟随缩放和偏移）
                        Row(modifier = Modifier.weight(1f)) {
                            fun formatTime(seconds: Float): String {
                                val totalSecs = seconds.toInt()
                                val h = totalSecs / 3600
                                val m = (totalSecs % 3600) / 60
                                val s = totalSecs % 60
                                return String.format("%02d:%02d:%02d", h, m, s)
                            }
                            // 计算可见范围的时间
                            val visibleCount = (maxDataPoints / zoomLevel).coerceAtLeast(2f)
                            val visStart = panOffset
                            val visEnd = panOffset + visibleCount
                            val tStartIdx = visStart.toInt().coerceIn(0, (parsedData.times.size - 1).coerceAtLeast(0))
                            val tEndIdx = visEnd.toInt().coerceIn(0, (parsedData.times.size - 1).coerceAtLeast(0))
                            val tMidIdx = ((visStart + visEnd) / 2f).toInt().coerceIn(0, (parsedData.times.size - 1).coerceAtLeast(0))
                            val tStart = parsedData.times.getOrNull(tStartIdx) ?: parsedData.times.firstOrNull() ?: 0f
                            val tEnd = parsedData.times.getOrNull(tEndIdx) ?: parsedData.times.lastOrNull() ?: 0f
                            val tMid = parsedData.times.getOrNull(tMidIdx) ?: (tStart + tEnd) / 2f

                            Text(
                                text = formatTime(tStart),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (tEnd > tStart) {
                                Text(
                                    text = formatTime(tMid),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            Text(
                                text = formatTime(tEnd),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 可见范围 min/max 预聚合的块大小：块内扫一遍，帧内只聚合块级结果 */
private const val Y_RANGE_BLOCK = 256

/** 单列曲线数据：值数组 + 每 Y_RANGE_BLOCK 点的 min/max 预聚合 */
private class CsvColumn(val name: String, val values: FloatArray) {
    val blockMins: FloatArray
    val blockMaxs: FloatArray

    init {
        val nBlocks = (values.size + Y_RANGE_BLOCK - 1) / Y_RANGE_BLOCK
        val mins = FloatArray(nBlocks) { Float.MAX_VALUE }
        val maxs = FloatArray(nBlocks) { -Float.MAX_VALUE }
        for (i in values.indices) {
            val b = i / Y_RANGE_BLOCK
            if (values[i] < mins[b]) mins[b] = values[i]
            if (values[i] > maxs[b]) maxs[b] = values[i]
        }
        blockMins = mins
        blockMaxs = maxs
    }

    /** 闭区间 [startIdx, endIdx] 的 min/max：完整块用预聚合 O(1)，两端残余块内扫描 */
    fun minMax(startIdx: Int, endIdx: Int): Pair<Float, Float> {
        if (endIdx < startIdx) return Float.MAX_VALUE to -Float.MAX_VALUE
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        val bStart = startIdx / Y_RANGE_BLOCK
        val bEnd = endIdx / Y_RANGE_BLOCK
        if (bStart == bEnd) {
            for (i in startIdx..endIdx) {
                val v = values[i]
                if (v < min) min = v
                if (v > max) max = v
            }
        } else {
            for (i in startIdx until (bStart + 1) * Y_RANGE_BLOCK) {
                val v = values[i]
                if (v < min) min = v
                if (v > max) max = v
            }
            for (b in bStart + 1 until bEnd) {
                if (blockMins[b] < min) min = blockMins[b]
                if (blockMaxs[b] > max) max = blockMaxs[b]
            }
            for (i in bEnd * Y_RANGE_BLOCK..endIdx) {
                val v = values[i]
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        return min to max
    }
}

private class CsvParsed(val columns: List<CsvColumn>, val globalMin: Float, val globalMax: Float, val times: FloatArray)

/**
 * 从 Room 数据构建图表数据。
 * 按秒分组还原为"一行多列"结构（与历史 CSV 相同的语义）；某设备某秒缺失时沿用上一秒值，
 * 完全无数据的设备不显示为曲线。
 *
 * 单趟实现（getSamples 已按 second ASC, deviceAddress ASC 排序）：
 *  - 第一趟相邻去重提取秒序列，不再 groupBy 全量复制；
 *  - 每设备一趟顺序游标推进取本设备的样本（原实现每设备×每秒 firstOrNull 线性扫，O(设备×样本)）；
 *  - 缺失秒沿用上一值，首个样本前保持 0（与原实现一致，用 NaN 区分"未填"与真实 0）。
 */
private suspend fun buildParsedFromRoom(dao: RecordDao, sessionId: Long): CsvParsed? {
    val session = dao.getSession(sessionId) ?: return null
    val samples = dao.getSamples(sessionId)
    if (samples.isEmpty()) return null

    val order = session.deviceOrderList()
    val baseSecond = samples[0].second

    // 第一趟：相邻去重提取秒序列（SQL 已按 second 排序）
    var distinct = 0
    var prev = Long.MIN_VALUE
    for (s in samples) if (s.second != prev) { prev = s.second; distinct++ }
    val distinctSeconds = LongArray(distinct)
    val times = FloatArray(distinct)
    var w = 0
    prev = Long.MIN_VALUE
    for (s in samples) {
        if (s.second != prev) {
            prev = s.second
            distinctSeconds[w] = s.second
            times[w] = (s.second - baseSecond).toFloat()
            w++
        }
    }

    val columns = order.mapNotNull { addr ->
        val values = FloatArray(distinct) { Float.NaN }
        var hasData = false
        var p = 0   // distinctSeconds 游标（设备样本按 second 有序，只前进）
        for (s in samples) {
            if (s.deviceAddress != addr) continue
            while (distinctSeconds[p] < s.second) p++
            values[p] = s.heartRate.toFloat()
            hasData = true
        }
        if (!hasData) return@mapNotNull null
        // 前向填充缺失秒；首个样本前的秒保持 0
        var carry = Float.NaN
        for (i in values.indices) {
            val v = values[i]
            if (v.isNaN()) {
                values[i] = if (carry.isNaN()) 0f else carry
            } else {
                carry = v
            }
        }
        CsvColumn(session.displayNameFor(addr), values)
    }
    if (columns.isEmpty()) return null

    // 全局范围：min 只统计正样本，max 统计全部（与原实现一致）
    var globalMin = Float.MAX_VALUE
    var globalMax = -Float.MAX_VALUE
    var hasPositive = false
    for (col in columns) {
        for (v in col.values) {
            if (v > globalMax) globalMax = v
            if (v > 0f) {
                hasPositive = true
                if (v < globalMin) globalMin = v
            }
        }
    }
    if (!hasPositive) return null

    return CsvParsed(
        columns = columns,
        globalMin = globalMin,
        globalMax = globalMax,
        times = times
    )
}
