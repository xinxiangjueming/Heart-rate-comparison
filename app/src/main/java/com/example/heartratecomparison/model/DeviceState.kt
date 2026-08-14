package com.example.heartratecomparison.model

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt

data class DeviceState(
    val device: BluetoothDevice,
    var isConnected: Boolean = false,
    var heartRate: Int? = null,
    var batteryLevel: Int? = null,
    var gatt: BluetoothGatt? = null,
    /** 内存中的心率历史（上限 300 点）。ArrayDeque 使 removeFirst() 为 O(1) */
    val heartRateHistory: ArrayDeque<Int> = ArrayDeque(),
    /** 自上次 emitState 后是否有变化（增量快照用，仅主线程访问） */
    var dirty: Boolean = false
)