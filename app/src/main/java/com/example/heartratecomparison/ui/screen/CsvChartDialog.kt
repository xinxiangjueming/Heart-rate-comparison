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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val chartColors = listOf(
    Color.Red, Color(0xFF00BFFF), Color.Green, Color(0xFF800080), Color(0xFFFF9800)
)

@Composable
fun CsvChartScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isDark = isSystemInDarkTheme()

    // 进入时锁定横屏、隐藏状态栏，退出时恢复
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val win = activity?.window
            if (win != null) {
                val ctrl = WindowInsetsControllerCompat(win, win.decorView)
                ctrl.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler { onBack() }

    val parsed = remember(file) { parseCsv(file) }
    var hiddenDevices by remember { mutableStateOf(setOf<Int>()) }

    val axisColor = if (isDark) Color(0xFF888888) else Color.DarkGray
    val gridColor = if (isDark) Color(0xFF444444) else Color.LightGray
    val labelColor = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK

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
                        Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = file.nameWithoutExtension.removePrefix("heart_"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                parsed?.columns?.forEachIndexed { index, col ->
                    val isHidden = index in hiddenDevices
                    val visibleCount = (parsed.columns.size - hiddenDevices.size)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .padding(top = 14.dp)
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
            if (parsed == null || parsed.columns.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("无法解析 CSV 数据", color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                val yMin = parsed.globalMin * 0.95f
                val yMax = parsed.globalMax * 1.05f

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(15.dp)
                ) {
                    // 图表区域（支持手势缩放和滑动）
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Y 轴标签（固定显示完整数据范围）
                        Canvas(
                            modifier = Modifier
                                .width(20.dp)
                                .fillMaxHeight()
                        ) {
                            val textPaint = android.graphics.Paint().apply {
                                color = labelColor
                                textSize = 28f
                                textAlign = android.graphics.Paint.Align.RIGHT
                            }
                            val h = size.height
                            for (bpm in listOf(yMax, (yMin + yMax) / 2, yMin)) {
                                val y = h - (bpm - yMin) / (yMax - yMin) * h
                                drawContext.canvas.nativeCanvas.drawText(
                                    "${bpm.toInt()}",
                                    size.width - 4.dp.toPx(),
                                    y + 8f,
                                    textPaint
                                )
                            }
                        }

                        // 曲线区域（支持缩放、滑动、长按重置）
                        val maxDataPoints = parsed.columns.maxOfOrNull { it.values.size } ?: 0
                        var zoomLevel by remember { mutableFloatStateOf(1f) }
                        var panOffset by remember { mutableFloatStateOf(0f) }

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
                            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                            if (maxDataPoints < 2) return@Canvas

                            // 可见范围
                            val visibleCount = (maxDataPoints / zoomLevel).coerceAtLeast(2f)
                            val visStart = panOffset
                            val visEnd = panOffset + visibleCount

                            // 可见数据的 Y 范围
                            var visMin = Float.MAX_VALUE
                            var visMax = Float.MIN_VALUE
                            parsed.columns.forEachIndexed { colIndex, col ->
                                if (colIndex in hiddenDevices) return@forEachIndexed
                                val startIdx = visStart.toInt().coerceIn(0, col.values.size - 1)
                                val endIdx = visEnd.toInt().coerceIn(0, col.values.size - 1)
                                for (i in startIdx..endIdx) {
                                    val v = col.values[i]
                                    if (v < visMin) visMin = v
                                    if (v > visMax) visMax = v
                                }
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

                            parsed.columns.forEachIndexed { colIndex, col ->
                                if (colIndex in hiddenDevices) return@forEachIndexed
                                if (col.values.size < 2) return@forEachIndexed
                                val color = chartColors[colIndex % chartColors.size]
                                val mainPath = Path()
                                var started = false

                                val iStart = visStart.toInt().coerceIn(0, col.values.size - 1)
                                val iEnd = (visEnd.toInt() + 1).coerceIn(0, col.values.size - 1)

                                for (i in iStart..iEnd) {
                                    val x = scaledX(i)
                                    val y = yForValue(col.values[i])
                                    if (!started) { mainPath.moveTo(x, y); started = true }
                                    else mainPath.lineTo(x, y)
                                }

                                // 左边界插值（独立线段）
                                if (iStart > 0 && visStart > iStart) {
                                    val prev = col.values[iStart - 1]
                                    val curr = col.values[iStart]
                                    val frac = visStart - iStart
                                    val interpY = prev + (curr - prev) * frac
                                    val leftPath = Path().apply {
                                        moveTo(0f, yForValue(interpY))
                                        lineTo(scaledX(iStart), yForValue(curr))
                                    }
                                    drawPath(leftPath, color, style = Stroke(width = 3f))
                                }

                                // 右边界插值（独立线段）
                                val lastIdx = col.values.size - 1
                                if (iEnd < lastIdx && visEnd < lastIdx) {
                                    val frac = visEnd - iEnd
                                    val interpY = col.values[iEnd] + (col.values[iEnd + 1] - col.values[iEnd]) * frac
                                    val rightPath = Path().apply {
                                        moveTo(scaledX(iEnd), yForValue(col.values[iEnd]))
                                        lineTo(w, yForValue(interpY))
                                    }
                                    drawPath(rightPath, color, style = Stroke(width = 3f))
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
                        // 时间标签
                        Row(modifier = Modifier.weight(1f)) {
                            fun formatTime(seconds: Float): String {
                                val totalSecs = seconds.toInt()
                                val h = totalSecs / 3600
                                val m = (totalSecs % 3600) / 60
                                val s = totalSecs % 60
                                return String.format("%02d:%02d:%02d", h, m, s)
                            }
                            val tStart = parsed.times.firstOrNull() ?: 0f
                            val tEnd = parsed.times.lastOrNull() ?: 0f
                            val tMid = (tStart + tEnd) / 2f

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

private data class CsvColumn(val name: String, val values: List<Float>)
private class CsvParsed(val columns: List<CsvColumn>, val globalMin: Float, val globalMax: Float, val times: List<Float>)

private fun parseTimeToSeconds(timeStr: String): Float? {
    return try {
        val parts = timeStr.split(":")
        if (parts.size == 3) {
            parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toFloat()
        } else if (parts.size == 2) {
            parts[0].toInt() * 60 + parts[1].toFloat()
        } else {
            timeStr.toFloatOrNull()
        }
    } catch (e: Exception) {
        null
    }
}

private fun parseCsv(file: File): CsvParsed? {
    return try {
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return null

        val header = lines[0].split(",").map { it.trim() }
        if (header.size < 2) return null

        val dataColumns = header.size - 1
        val columns = Array<MutableList<Float>>(dataColumns) { mutableListOf() }
        val times = mutableListOf<Float>()

        for (i in 1 until lines.size) {
            val parts = lines[i].split(",").map { it.trim() }
            // 提取第一列时间值（HH:mm:ss 格式转为秒数）
            parts.getOrNull(0)?.let { timeStr ->
                val secs = parseTimeToSeconds(timeStr)
                if (secs != null) times.add(secs)
            }
            for (col in 0 until dataColumns) {
                val value = parts.getOrNull(col + 1)?.toFloatOrNull()
                if (value != null) {
                    columns[col].add(value)
                }
            }
        }

        val csvColumns = columns.mapIndexed { index, values ->
            CsvColumn(header[index + 1], values)
        }.filter { it.values.isNotEmpty() }

        val allValues = csvColumns.flatMap { it.values }
        if (allValues.isEmpty()) return null

        CsvParsed(
            columns = csvColumns,
            globalMin = allValues.min(),
            globalMax = allValues.max(),
            times = times
        )
    } catch (e: Exception) {
        null
    }
}
