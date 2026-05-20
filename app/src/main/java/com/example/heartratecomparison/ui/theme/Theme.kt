package com.example.heartratecomparison.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

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

private val AppShapes = Shapes(
    small = RoundedCornerShape(28.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable
fun HeartRateComparisonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content
    )
}