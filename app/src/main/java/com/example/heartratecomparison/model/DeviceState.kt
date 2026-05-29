package com.example.heartratecomparison.model

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt

data class DeviceState(
    val device: BluetoothDevice,
    var isConnected: Boolean = false,
    var heartRate: Int? = null,
    var batteryLevel: Int? = null,
    var gatt: BluetoothGatt? = null,
    val heartRateHistory: MutableList<Int> = mutableListOf()
)