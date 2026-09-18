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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.math.roundToInt

private val chartColors = ChartColors

/** Y 轴占位宽度（Y 轴标签画布 / 底部时间行占位 / 滑块左缩进共用） */
private val Y_AXIS_WIDTH = 20.dp

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
    // scrub 读数悬浮层：数值标签文字（textSize 每帧按绘图区高度设置，颜色随曲线）
    val scrubTextPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    // scrub 读数时底部跟随手指的时间文字（样式与初末时间标签一致）
    val scrubTimeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val scrubTimePaint = remember(scrubTimeColor, density) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = scrubTimeColor.toArgb()
            textSize = with(density) { 10.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 全屏数据查看（沉浸横屏）时系统栏已隐藏，safeDrawing 即摄像头挖孔区域，内容须避开；
            // 分栏模式嵌在 HistoryScreen 内，父级已做过 safeDrawing 内边距，这里再加会双重避让
            .then(
                if (immersive) Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                else Modifier
            )
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
                        // 原 bottom 15dp padding 让位给下方滑块区 Box（总高度不变，滑块出现/消失无布局跳动）
                        .padding(start = 15.dp, top = 25.dp, end = 15.dp)
                ) {
                    // 缩放状态（图表和时间标签共享）
                    val maxDataPoints = parsedData.columns.maxOfOrNull { it.values.size } ?: 0
                    var zoomLevel by remember { mutableFloatStateOf(1f) }
                    var panOffset by remember { mutableFloatStateOf(0f) }
                    // scrub 读数状态（同 sportlink：单指按下即进入，抬起/双指退出）
                    var isScrubbing by remember { mutableStateOf(false) }
                    var scrubX by remember { mutableFloatStateOf(0f) }
                    val currentZoomLevel by rememberUpdatedState(zoomLevel)

                    // 图表区域（双指缩放 + 单指 scrub 读数）
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Y 轴标签（固定显示完整数据范围）
                        Canvas(
                            modifier = Modifier
                                .width(Y_AXIS_WIDTH)
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

                        // 曲线区域（单指按住显示当秒心率，双指缩放，双指按住 3 秒重置）


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
                                        val downTime = System.currentTimeMillis()
                                        var moved = false
                                        // 双指 pinch 一旦开始，本次手势内不再回到单指 scrub（同 sportlink）
                                        var multiStarted = false
                                        var pressedCount = 0

                                        try {
                                            do {
                                                // 静置也可触发"双指按住 3 秒重置"：对未移动的双指等待施加超时
                                                // （保留原 withTimeout 语义，无事件流时同样能在 3 秒触发）
                                                val remaining = 3000L - (System.currentTimeMillis() - downTime)
                                                val event = if (!moved && pressedCount >= 2 && remaining > 0) {
                                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                                } else {
                                                    awaitPointerEvent()
                                                }
                                                if (event == null) {
                                                    // 静置满 3 秒未动 → 重置缩放
                                                    zoomLevel = 1f
                                                    panOffset = 0f
                                                    break
                                                }
                                                val changes = event.changes

                                                for (change in changes) {
                                                    if ((change.position - downPos).getDistance() > 10f) {
                                                        moved = true
                                                    }
                                                }
                                                pressedCount = changes.count { it.pressed }

                                                if (!moved && pressedCount >= 2 &&
                                                    System.currentTimeMillis() - downTime >= 3000
                                                ) {
                                                    zoomLevel = 1f
                                                    panOffset = 0f
                                                    break
                                                }

                                                if (pressedCount >= 2) {
                                                    multiStarted = true
                                                    isScrubbing = false

                                                    // 双指捏合缩放（以可见区间中点为焦点）
                                                    val currentDist = (changes[0].position - changes[1].position).getDistance()
                                                    val prevDist = (changes[0].previousPosition - changes[1].previousPosition).getDistance()
                                                    if (prevDist > 0f) {
                                                        val zoom = currentDist / prevDist
                                                        val newZoom = (zoomLevel * zoom).coerceIn(1f, 20f)
                                                        val visibleCount = maxDataPoints / newZoom
                                                        val centerIndex = panOffset + visibleCount / 2f
                                                        zoomLevel = newZoom
                                                        val newVisibleCount = maxDataPoints / zoomLevel
                                                        panOffset = (centerIndex - newVisibleCount / 2f)
                                                            .coerceIn(0f, (maxDataPoints - newVisibleCount).coerceAtLeast(0f))
                                                    }
                                                } else if (!multiStarted) {
                                                    // 单指：按下即进入 scrub 读数（同 sportlink）；平移由底部滑块承担
                                                    val change = changes.first()
                                                    if (change.pressed) {
                                                        isScrubbing = true
                                                        scrubX = change.position.x.coerceIn(0f, size.width.toFloat())
                                                        change.consume()
                                                    }
                                                }
                                            } while (changes.any { it.pressed })
                                        } finally {
                                            isScrubbing = false
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

                            // ── Scrub 读数悬浮层（同 sportlink：竖线 + 各设备圆点 + 数值标签）──
                            if (isScrubbing) {
                                val clampedX = scrubX.coerceIn(0f, w)
                                drawLine(
                                    if (isDark) Color(0x80FFFFFF.toInt()) else Color(0x80000000.toInt()),
                                    Offset(clampedX, 0f),
                                    Offset(clampedX, h),
                                    strokeWidth = 2f
                                )

                                // 手指位置 → 数据索引（可见区间线性映射）
                                val scrubIdx = (visStart + clampedX / w * (visEnd - visStart))
                                    .roundToInt()
                                    .coerceIn(0, maxDataPoints - 1)

                                val scrubTextSize = (h * 0.032f).coerceIn(8f, 18f) * 3f
                                scrubTextPaint.textSize = scrubTextSize
                                val gap = scrubTextSize * 0.25f
                                val textH = scrubTextSize * 1.3f
                                val scrubBgColor = if (isDark) Color(0xCC222222.toInt()) else Color(0xCCFFFFFF.toInt())

                                var labelY = 10f
                                parsedData.columns.forEachIndexed { colIndex, col ->
                                    if (colIndex in hiddenDevices) return@forEachIndexed
                                    val v = col.values[scrubIdx]
                                    if (v <= 0f) return@forEachIndexed   // 首个样本前的填充 0 不显示

                                    val color = chartColors[colIndex % chartColors.size]
                                    // 圆点画在手指 X 处、Y 取该设备当秒值（同 sportlink，避免圆点横向跳变）
                                    drawCircle(color, radius = scrubTextSize * 0.25f, center = Offset(clampedX, yForValue(v)))

                                    // 标签「设备名 心率」：背景圆角块 + 曲线色文字，右侧放不下翻到线左侧
                                    val labelText = "${col.name} ${v.toInt()}"
                                    val textWidth = scrubTextPaint.measureText(labelText)
                                    val fitsRight = clampedX + textWidth + gap * 2f < w
                                    val bgX = if (fitsRight) clampedX + gap else clampedX - textWidth - gap * 3f
                                    drawRoundRect(
                                        scrubBgColor,
                                        topLeft = Offset(bgX, labelY),
                                        size = Size(textWidth + gap * 2f, textH + gap),
                                        cornerRadius = CornerRadius(gap, gap)
                                    )
                                    scrubTextPaint.color = color.toArgb()
                                    drawContext.canvas.nativeCanvas.drawText(labelText, bgX + gap, labelY + textH, scrubTextPaint)
                                    labelY += textH + gap * 1.5f
                                }
                            }
                        }
                    }

                    // 底部区：时间标签 + 滑块区（外层 Column 承载滑块拖动手势）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(maxDataPoints) {
                                // 底部滑块拖动/点击平移（放大后才响应；thumb 中心跟随手指，同 sportlink）
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (currentZoomLevel <= 1.005f) return@awaitEachGesture
                                    val plotLeft = Y_AXIS_WIDTH.toPx()
                                    if (down.position.x < plotLeft) return@awaitEachGesture
                                    val plotWidth = size.width - plotLeft

                                    fun applyDrag(x: Float) {
                                        val z = currentZoomLevel
                                        val visibleCount = (maxDataPoints / z).coerceAtLeast(2f)
                                        val span = (maxDataPoints - visibleCount).coerceAtLeast(1f)
                                        val thumbWidth = (plotWidth / z).coerceAtLeast(40.dp.toPx())
                                        val travel = (plotWidth - thumbWidth).coerceAtLeast(1f)
                                        val frac = ((x - thumbWidth / 2f - plotLeft) / travel).coerceIn(0f, 1f)
                                        panOffset = frac * span
                                    }

                                    applyDrag(down.position.x)
                                    down.consume()
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.pressed } ?: break
                                        applyDrag(change.position.x)
                                        change.consume()
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    ) {
                        // 底部时间标签
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                        ) {
                            // 与 Y 轴等宽的占位
                            Spacer(modifier = Modifier.width(Y_AXIS_WIDTH))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                fun formatTime(seconds: Float): String {
                                    val totalSecs = seconds.toInt()
                                    val h = totalSecs / 3600
                                    val m = (totalSecs % 3600) / 60
                                    val s = totalSecs % 60
                                    return String.format("%02d:%02d:%02d", h, m, s)
                                }
                                // 时间标签（跟随缩放和偏移）
                                Row(modifier = Modifier.fillMaxSize()) {
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

                                    // scrub 读数时隐藏初/中/末时间（由跟随手指的 scrub 时间替代）
                                    if (!isScrubbing) {
                                        Text(
                                            text = formatTime(tStart),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (tEnd > tStart && !isScrubbing) {
                                        Text(
                                            text = formatTime(tMid),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                    if (!isScrubbing) {
                                        Text(
                                            text = formatTime(tEnd),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }

                                // scrub 读数时：跟随手指的当秒时间（样式与初末标签一致，钳制在绘图区内）
                                if (isScrubbing) {
                                    Canvas(modifier = Modifier.matchParentSize()) {
                                        val plotWidth = size.width
                                        if (plotWidth > 0f) {
                                            val visibleCount = (maxDataPoints / zoomLevel).coerceAtLeast(2f)
                                            val visStart = panOffset
                                            val visEnd = panOffset + visibleCount
                                            val idx = (visStart + scrubX.coerceIn(0f, plotWidth) / plotWidth * (visEnd - visStart))
                                                .roundToInt()
                                                .coerceIn(0, maxDataPoints - 1)
                                            val seconds = parsedData.times.getOrNull(idx) ?: return@Canvas
                                            val text = formatTime(seconds)
                                            val half = scrubTimePaint.measureText(text) / 2f
                                            val x = scrubX.coerceIn(0f, plotWidth).coerceIn(half, (plotWidth - half).coerceAtLeast(half))
                                            val fm = scrubTimePaint.fontMetrics
                                            val baseline = size.height / 2f - (fm.ascent + fm.descent) / 2f
                                            drawContext.canvas.nativeCanvas.drawText(text, x, baseline, scrubTimePaint)
                                        }
                                    }
                                }
                            }
                        }

                        // 滑块区（占用原卡片底部 padding；放大后出现，点击/拖动平移时间轴）
                        Box(modifier = Modifier.fillMaxWidth().height(15.dp)) {
                            if (zoomLevel > 1.005f) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val plotLeft = Y_AXIS_WIDTH.toPx()
                                    val plotWidth = size.width - plotLeft
                                    if (plotWidth > 0f) {
                                        val visibleCount = (maxDataPoints / zoomLevel).coerceAtLeast(2f)
                                        val span = (maxDataPoints - visibleCount).coerceAtLeast(1f)
                                        val thumbWidth = (plotWidth / zoomLevel).coerceAtLeast(40.dp.toPx())
                                        val travel = (plotWidth - thumbWidth).coerceAtLeast(1f)
                                        val thumbLeft = plotLeft + (panOffset / span).coerceIn(0f, 1f) * travel
                                        val trackHeight = 4.dp.toPx()
                                        val thumbHeight = 6.dp.toPx()
                                        val centerY = size.height / 2f
                                        // track：绘图区全宽半透明胶囊；thumb：中心对齐 track 的胶囊滑块
                                        drawRoundRect(
                                            if (isDark) Color(0x24FFFFFF.toInt()) else Color(0x1E000000.toInt()),
                                            topLeft = Offset(plotLeft, centerY - trackHeight / 2f),
                                            size = Size(plotWidth, trackHeight),
                                            cornerRadius = CornerRadius(trackHeight / 2f)
                                        )
                                        drawRoundRect(
                                            if (isDark) Color(0x8CFFFFFF.toInt()) else Color(0x59000000.toInt()),
                                            topLeft = Offset(thumbLeft, centerY - thumbHeight / 2f),
                                            size = Size(thumbWidth, thumbHeight),
                                            cornerRadius = CornerRadius(thumbHeight / 2f)
                                        )
                                    }
                                }
                            }
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
