package com.example.heartratecomparison.model

import java.io.Serializable

data class UiDeviceState(
    val address: String,
    val name: String,
    val isConnected: Boolean,
    val heartRate: Int?,
    val batteryLevel: Int?,
    val heartRateHistory: List<Int>
) : Serializable