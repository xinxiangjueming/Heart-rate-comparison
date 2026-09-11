package com.example.heartratecomparison.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val PARTICLE_COUNT = 160
private const val ANIM_DURATION_MS = 3800L

private data class Particle(
    val startX: Float,
    val startY: Float,
    val targetX: Float,
    val targetY: Float,
    val size: Float,
    val delayFraction: Float,
    val brightness: Float
)

private fun generateParticles(
    screenW: Float, screenH: Float,
    heartCx: Float, heartCy: Float, heartScale: Float
): List<Particle> {
    val rng = java.util.Random(42)
    return List(PARTICLE_COUNT) { i ->
        val t = i * (2.0 * Math.PI / PARTICLE_COUNT)
        val rawX = 16.0 * sin(t).pow(3.0)
        val rawY = -(13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t))
        val tx = heartCx + rawX.toFloat() * heartScale
        val ty = heartCy + rawY.toFloat() * heartScale

        val edge = rng.nextInt(4)
        val (sx, sy) = when (edge) {
            0 -> Pair(rng.nextFloat() * screenW, -30f)
            1 -> Pair(screenW + 30f, rng.nextFloat() * screenH)
            2 -> Pair(rng.nextFloat() * screenW, screenH + 30f)
            else -> Pair(-30f, rng.nextFloat() * screenH)
        }

        Particle(
            startX = sx, startY = sy,
            targetX = tx, targetY = ty,
            size = 2f + rng.nextFloat() * 3f,
            delayFraction = rng.nextFloat() * 0.3f,
            brightness = 0.6f + rng.nextFloat() * 0.4f
        )
    }
}

/**
 * ECG waveform: one heartbeat cycle, normalized to -1..1
 */
private fun ecgY(t: Float): Float {
    val x = t % 1f
    var y = 0f
    y += 0.12f * gaussian(x, 0.12f, 0.04f)   // P wave
    y -= 0.1f * gaussian(x, 0.22f, 0.012f)    // Q dip
    y += 1.0f * gaussian(x, 0.27f, 0.018f)    // R peak
    y -= 0.25f * gaussian(x, 0.32f, 0.015f)   // S dip
    y += 0.2f * gaussian(x, 0.52f, 0.06f)     // T wave
    return y
}

private fun gaussian(x: Float, center: Float, sigma: Float): Float {
    val d = x - center
    return exp(-(d * d) / (2f * sigma * sigma))
}

private fun exp(x: Float): Float = kotlin.math.exp(x.toDouble()).toFloat()

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        // 使用真实窗口/组合约束尺寸，而非 LocalConfiguration：自由窗口/分屏下后者返回的是
        // 整块设备屏幕的尺寸，会使心形中心被算到窗口之外，从而偏移到角落并脱离可视区
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val heartScale = screenW.coerceAtMost(screenH) * 0.0065f
        val heartCx = screenW / 2f
        val heartCy = screenH / 2f

        val particles = remember(screenW.toInt(), screenH.toInt()) {
            generateParticles(screenW, screenH, heartCx, heartCy, heartScale)
        }

    val progress = remember { Animatable(0f) }
    val smoothEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    val ecgBlue = Color(0xFF00BFFF)
    val shortSide = screenW.coerceAtMost(screenH)
    val ecgAmplitude = shortSide * 0.10f
    val ecgBaselineY = heartCy + heartScale * 18f
    val ecgCycles = 2.5f
    val ecgSampleCount = 400

    // ECG 全路径只构建一次（原实现每帧重建最多 400 点的 Path）；
    // 播放进度用 clipRect 揭示，末端平切边缘被发光点覆盖，视觉与逐段绘制一致
    val ecgPath = remember(screenW, screenH) {
        Path().apply {
            for (i in 0 until ecgSampleCount) {
                val tNorm = i.toFloat() / (ecgSampleCount - 1)
                val x = -screenW * 0.1f + tNorm * screenW * 1.2f
                val y = ecgBaselineY - ecgY(tNorm * ecgCycles) * ecgAmplitude
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            animationSpec = tween(ANIM_DURATION_MS.toInt(), easing = smoothEasing)
        )
        onFinished()
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        val p = progress.value

        // Background: fades out first (70%→90%)
        val bgAlpha = when {
            p < 0.08f -> smoothStep(p / 0.08f)
            p > 0.70f -> smoothStep((0.90f - p) / 0.20f).coerceAtLeast(0f)
            else -> 1f
        }
        // Content: fades out later (85%→100%)
        val contentAlpha = when {
            p < 0.08f -> smoothStep(p / 0.08f)
            p > 0.85f -> smoothStep((1f - p) / 0.15f).coerceAtLeast(0f)
            else -> 1f
        }

        drawRect(Color(bg.red, bg.green, bg.blue, bgAlpha))

        // === Particles ===
        particles.forEach { particle ->
            val localP = ((p - particle.delayFraction * 0.5f) / (1f - particle.delayFraction * 0.5f))
                .coerceIn(0f, 1f)
            val eased = 1f - (1f - localP).pow(3f)
            val cx = particle.startX + (particle.targetX - particle.startX) * eased
            val cy = particle.startY + (particle.targetY - particle.startY) * eased

            val particleAlpha = when {
                localP < 0.2f -> smoothStep(localP / 0.2f) * particle.brightness
                else -> particle.brightness
            } * contentAlpha

            val breathe = if (localP < 0.85f) {
                1f + 0.25f * sin(localP * Math.PI.toFloat() * 1.5f)
            } else 1f
            val radius = particle.size * breathe * density.density

            drawCircle(
                color = Color(primary.red, primary.green, primary.blue, particleAlpha * 0.25f),
                radius = radius * 2.5f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color(primary.red, primary.green, primary.blue, particleAlpha),
                radius = radius,
                center = Offset(cx, cy)
            )
        }

        // === Pulse rings ===
        val ringP = ((p - 0.45f) / 0.25f).coerceIn(0f, 1f)
        if (ringP > 0.01f && ringP < 0.99f) {
            val ringAlpha = sin(ringP * Math.PI.toFloat()) * 0.12f * contentAlpha
            drawCircle(
                color = Color(primary.red, primary.green, primary.blue, ringAlpha),
                radius = heartScale * 20f * ringP,
                center = Offset(heartCx, heartCy),
                style = Stroke(width = 1.5f * density.density)
            )
        }

        val ring2P = ((p - 0.52f) / 0.25f).coerceIn(0f, 1f)
        if (ring2P > 0.01f && ring2P < 0.99f) {
            val ringAlpha = sin(ring2P * Math.PI.toFloat()) * 0.08f * contentAlpha
            drawCircle(
                color = Color(primary.red, primary.green, primary.blue, ringAlpha),
                radius = heartScale * 20f * ring2P,
                center = Offset(heartCx, heartCy),
                style = Stroke(width = 1f * density.density)
            )
        }

        // === ECG line ===
        val ecgDrawProgress = ((p - 0.50f) / 0.32f).coerceIn(0f, 1f)
        val ecgAlpha = when {
            p < 0.52f -> smoothStep((p - 0.50f) / 0.02f)
            p > 0.75f -> smoothStep((1f - p) / 0.25f).coerceAtLeast(0f)
            else -> 1f
        } * contentAlpha

        if (ecgDrawProgress > 0.001f && ecgAlpha > 0.001f) {
            val totalSamples = (ecgSampleCount * ecgDrawProgress).toInt().coerceAtLeast(2)
            val tipI = (totalSamples - 1).coerceAtLeast(0)
            val tipTNorm = tipI.toFloat() / (ecgSampleCount - 1)
            val tipX = -screenW * 0.1f + tipTNorm * screenW * 1.2f
            val tipY = ecgBaselineY - ecgY(tipTNorm * ecgCycles) * ecgAmplitude

            // 按进度裁剪整条预构建路径（替代每帧重建裁剪段 Path）
            clipRect(left = 0f, top = 0f, right = tipX, bottom = size.height) {
                drawPath(
                    path = ecgPath,
                    color = Color(ecgBlue.red, ecgBlue.green, ecgBlue.blue, ecgAlpha * 0.2f),
                    style = Stroke(width = 6f * density.density)
                )
                drawPath(
                    path = ecgPath,
                    color = Color(ecgBlue.red, ecgBlue.green, ecgBlue.blue, ecgAlpha * 0.85f),
                    style = Stroke(width = 2f * density.density)
                )
            }

            if (ecgDrawProgress < 0.98f) {
                drawCircle(
                    color = Color(ecgBlue.red, ecgBlue.green, ecgBlue.blue, ecgAlpha * 0.4f),
                    radius = 8f * density.density,
                    center = Offset(tipX, tipY)
                )
                drawCircle(
                    color = Color(1f, 1f, 1f, ecgAlpha * 0.9f),
                    radius = 3f * density.density,
                    center = Offset(tipX, tipY)
                )
            }
        }
    }
    }
}

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
