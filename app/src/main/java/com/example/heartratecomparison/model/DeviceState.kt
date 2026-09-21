package com.example.heartratecomparison.model

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt

/**
 * 心率历史窗口长度：单设备内存最多保留的点数。每秒最多一点，数值上即窗口秒数。
 * HeartRateService 的滑动淘汰与 MultiHeartRateChart 的显示时间窗共用此常量：
 * 满 300 点的设备窗口跨度 ≥ 299 秒，恰好覆盖图表 [最新秒-299, 最新秒] 的显示窗。
 */
const val HISTORY_WINDOW = 300

data class DeviceState(
    val device: BluetoothDevice,
    var isConnected: Boolean = false,
    var heartRate: Int? = null,
    var batteryLevel: Int? = null,
    var gatt: BluetoothGatt? = null,
    /** Device Information Service 读取结果（连接后填充，仅设备信息弹窗展示用） */
    var deviceInfo: DeviceInfo? = null,
    /** 内存中的心率历史（每秒最多一点，上限 HISTORY_WINDOW 点）。ArrayDeque 使 removeFirst() 为 O(1) */
    val heartRateHistory: ArrayDeque<HeartRatePoint> = ArrayDeque(),
    /** 自上次 emitState 后是否有变化（增量快照用，仅主线程访问） */
    var dirty: Boolean = false
)

/**
 * 实时图表用的心率采样点：epoch 秒 + 心率值。
 * 每台设备每秒最多保留一点（同一秒内的多次通知覆盖为最新值），
 * 保证设备内秒值严格递增，图表可按真实时间对齐多设备曲线。
 */
data class HeartRatePoint(
    /** 秒级时间戳（epoch second） */
    val second: Long,
    val heartRate: Int
)