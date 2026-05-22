package com.example.heartratecomparison.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 系统屏幕圆角，通过 CompositionLocal 传递
val LocalCornerRadius = compositionLocalOf { 28.dp }
val LocalDeviceCardBg = compositionLocalOf { Color.White }
val LocalDeviceCardBorder = compositionLocalOf { Color.Black }
val LocalChartAxis = compositionLocalOf { Color.DarkGray }
val LocalChartGrid = compositionLocalOf { Color.LightGray }

@SuppressLint("NewApi")
private fun getScreenCornerRadius(context: Context): Dp {
    val density = context.resources.displayMetrics.density

    // Android 12+ 通用 API
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        val display = context.display
        if (display != null) {
            // Display.Corner.TOP_LEFT = 0
            val corner = display.getRoundedCorner(0)
            if (corner != null) {
                val radiusPx = corner.radius
                if (radiusPx > 0) return (radiusPx / density).dp
            }
        }
    }

    // 小米设备专属参数
    val topRadiusId = context.resources.getIdentifier(
        "rounded_corner_radius_top", "dimen", "android"
    )
    val radiusPx = if (topRadiusId > 0) {
        context.resources.getDimensionPixelSize(topRadiusId)
    } else 0
    return if (radiusPx > 0) {
        (radiusPx / density).dp
    } else {
        28.dp // 兜底值
    }
}

private val RedHot = Color(0xFFE53935)
private val NeutralGray = Color(0xFFE0E0E0)
private val SoftWhite = Color(0xFFF5F5F5)

private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkOnSurface = Color(0xFFE0E0E0)

private val LightColors = lightColorScheme(
    primary = RedHot,
    onPrimary = Color.White,
    background = NeutralGray,
    surface = SoftWhite,
    onSurface = Color.Black,
    onBackground = Color.Black
)

private val DarkColors = darkColorScheme(
    primary = RedHot,
    onPrimary = Color.White,
    background = DarkBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    onBackground = DarkOnSurface
)

@Composable
fun HeartRateComparisonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val cornerRadius = remember { getScreenCornerRadius(context) }
    val appShapes = Shapes(
        small = RoundedCornerShape(cornerRadius),
        medium = RoundedCornerShape(cornerRadius),
        large = RoundedCornerShape(cornerRadius)
    )
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val deviceCardBg = if (darkTheme) DeviceBgDark else DeviceBgLight
    val deviceCardBorder = if (darkTheme) DeviceBorderDark else DeviceBorderLight
    val chartAxis = if (darkTheme) ChartAxisDark else ChartAxisLight
    val chartGrid = if (darkTheme) ChartGridDark else ChartGridLight

    CompositionLocalProvider(
        LocalCornerRadius provides cornerRadius,
        LocalDeviceCardBg provides deviceCardBg,
        LocalDeviceCardBorder provides deviceCardBorder,
        LocalChartAxis provides chartAxis,
        LocalChartGrid provides chartGrid
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = appShapes,
            content = content
        )
    }
}