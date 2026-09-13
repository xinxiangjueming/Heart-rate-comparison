package com.example.heartratecomparison.model

/**
 * BLE Device Information Service (0x180A) 读取结果（全稳定类型）。
 * 字段可能为 null：设备可能未实现 DIS 或个别特征缺失。
 */
data class DeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val serialNumber: String?,
    val firmwareVersion: String?,
    val softwareVersion: String?
)
