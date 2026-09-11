package com.example.heartratecomparison.model

/**
 * 设备卡片状态（仅卡片显示所需字段，全稳定类型：Compose 可按值相等跳过重组）。
 * 心率历史已拆分到 HeartRateService.globalHistoryState，仅图表消费。
 */
data class UiDeviceState(
    val address: String,
    val name: String,
    val isConnected: Boolean,
    val heartRate: Int?,
    val batteryLevel: Int?
)
