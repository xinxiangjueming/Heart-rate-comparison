package com.example.heartratecomparison.ui.theme

import androidx.compose.ui.graphics.Color

// 设备列表颜色
val DeviceBgLight = Color(0xFFFFFFFF)
val DeviceBgDark = Color(0xFF424242)
val DeviceBorderLight = Color.Black
val DeviceBorderDark = Color.White

// 曲线颜色池（统一定义，MainScreen 和 CsvChartDialog 共用）
val ChartColors = listOf(
    Color.Red,              // 1 红
    Color(0xFF00BFFF),      // 2 天蓝
    Color.Green,            // 3 绿
    Color(0xFF800080),      // 4 紫
    Color(0xFFFF9800),      // 5 橙
    Color(0xFFFFD700),      // 6 金黄
    Color(0xFFFF69B4),      // 7 粉红
    Color(0xFF00CED1),      // 8 青色
    Color(0xFFA0522D),      // 9 棕
    Color(0xFF708090)       // 10 灰蓝
)

// 图表颜色
val ChartAxisLight = Color.DarkGray
val ChartAxisDark = Color(0xFF888888)
val ChartGridLight = Color.LightGray
val ChartGridDark = Color(0xFF444444)

// 蓝牙图标色
val BluetoothConnected = Color(0xFF64B5F6)
val BluetoothDisconnected = Color(0xFFBDBDBD)
