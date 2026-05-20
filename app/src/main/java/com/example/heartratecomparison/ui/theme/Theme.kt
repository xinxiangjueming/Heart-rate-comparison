package com.example.heartratecomparison.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val RedHot = Color(0xFFE53935)
private val NeutralGray = Color(0xFFE0E0E0)
private val SoftWhite = Color(0xFFF5F5F5)

private val AppColors = lightColorScheme(
    primary = RedHot,
    onPrimary = Color.White,
    background = NeutralGray,
    surface = SoftWhite,
    onSurface = Color.Black,
    onBackground = Color.Black
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(28.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable
fun HeartRateComparisonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        shapes = AppShapes,
        content = content
    )
}