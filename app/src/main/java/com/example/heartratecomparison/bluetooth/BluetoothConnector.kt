package com.example.heartratecomparison.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.heartratecomparison.model.DeviceInfo
import kotlinx.coroutines.*
import java.util.UUID

val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val HEART_RATE_SERVICE_UUID = ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB")
val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
val MANUFACTURER_NAME_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
val MODEL_NUMBER_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
val SERIAL_NUMBER_UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
val FIRMWARE_REVISION_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
val SOFTWARE_REVISION_UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")

/**
 * BLE 连接管理器 — 高优先级 BLE 线程架构
 *
 * 线程模型：
 *   - scope 注入 bleScope（Dispatchers.Default.limitedParallelism(1)，单线程）
 *   - GATT 回调通过 scope.launch 在 BLE 线程处理（序列化，无并发）
 *   - onHeartRateReceived / onDeviceConnected 等回调在 BLE 线程触发
 *   - HeartRateService 负责在回调中 withContext(Main) 更新 UI
 */
class BluetoothConnector(
    private val context: Context,
    private val scope: CoroutineScope,  // bleScope，BLE 单线程调度器
    private val onDeviceConnected: (String) -> Unit,
    private val onDeviceDisconnected: (String) -> Unit,
    private val onHeartRateReceived: (String, Int) -> Unit,
    private val onBatteryLevelReceived: (String, Int) -> Unit,
    private val onDeviceInfoReceived: (String, DeviceInfo) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothConnector"
    }

    private val gattMap = mutableMapOf<String, BluetoothGatt>()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (gattMap.containsKey(device.address)) return

        // DIS 读取链状态（每条连接独立，回调均在 BLE 单线程处理，无并发）：
        // 就绪后逐个读取 DIS 特征，全部读完汇总回调一次，最后转读电量
        val infoUuids = setOf(
            MANUFACTURER_NAME_UUID, MODEL_NUMBER_UUID, SERIAL_NUMBER_UUID,
            FIRMWARE_REVISION_UUID, SOFTWARE_REVISION_UUID
        )
        val infoQueue = ArrayDeque<BluetoothGattCharacteristic>()
        val infoValues = HashMap<UUID, String?>()

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                scope.launch {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            gatt.discoverServices()
                        } else {
                            Log.e(TAG, "GATT 连接失败, status=$status")
                            gattMap.remove(device.address)
                            gatt.close()
                            onDeviceDisconnected(device.address)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gattMap.remove(device.address)
                        onDeviceDisconnected(device.address)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // 收集 DIS 上存在的特征，就绪后逐个读取（厂商/型号/序列号/固件/软件版本）
                        gatt.getService(DEVICE_INFO_SERVICE_UUID)?.let { dis ->
                            listOf(
                                MANUFACTURER_NAME_UUID, MODEL_NUMBER_UUID, SERIAL_NUMBER_UUID,
                                FIRMWARE_REVISION_UUID, SOFTWARE_REVISION_UUID
                            ).mapNotNull { dis.getCharacteristic(it) }.forEach { infoQueue.add(it) }
                        }
                        val service = gatt.getService(HEART_RATE_SERVICE_UUID.uuid)
                        val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
                        if (characteristic != null) {
                            gatt.setCharacteristicNotification(characteristic, true)
                            val descriptor = characteristic.getDescriptor(CCCD_UUID)
                            if (descriptor != null) {
                                @Suppress("DEPRECATION")
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    gatt.writeDescriptor(descriptor)
                                }
                            } else {
                                onDeviceReady(gatt)
                            }
                            onDeviceConnected(device.address)
                        } else {
                            onDeviceReady(gatt)
                            onDeviceConnected(device.address)
                        }
                    } else {
                        Log.e(TAG, "服务发现失败, status=$status")
                        gattMap.remove(device.address)
                        gatt.close()
                        onDeviceDisconnected(device.address)
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                scope.launch {
                    if (descriptor.uuid == CCCD_UUID) {
                        onDeviceReady(gatt)
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                scope.launch {
                    @Suppress("DEPRECATION")
                    handleRead(gatt, characteristic.uuid, characteristic.value, status)
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                scope.launch {
                    handleRead(gatt, characteristic.uuid, value, status)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                    scope.launch {
                        @Suppress("DEPRECATION")
                        val hr = parseHeartRate(characteristic.value)
                        onHeartRateReceived(device.address, hr)
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                    scope.launch {
                        val hr = parseHeartRate(value)
                        onHeartRateReceived(device.address, hr)
                    }
                }
            }

            private fun handleRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray?, status: Int) {
                if (uuid in infoUuids) {
                    // DIS 特征：记录读取结果并驱动读取链前进
                    infoValues[uuid] = if (status == BluetoothGatt.GATT_SUCCESS) {
                        value?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                    readNextInfo(gatt)
                } else {
                    handleBatteryRead(uuid, value, status)
                }
            }

            private fun handleBatteryRead(uuid: UUID, value: ByteArray?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && uuid == BATTERY_LEVEL_UUID) {
                    val level = value?.getOrNull(0)?.toInt()?.and(0xFF)
                    if (level != null) {
                        onBatteryLevelReceived(device.address, level)
                    }
                }
            }

            private fun onDeviceReady(gatt: BluetoothGatt) {
                readNextInfo(gatt)
            }

            /** DIS 读取链：弹出一个特征读取；读完（或无 DIS）汇总回调一次，再转读电量 */
            @SuppressLint("MissingPermission")
            private fun readNextInfo(gatt: BluetoothGatt) {
                val next = infoQueue.removeFirstOrNull()
                if (next != null) {
                    @Suppress("DEPRECATION")
                    gatt.readCharacteristic(next)
                    return
                }
                if (infoValues.isNotEmpty()) {
                    val info = DeviceInfo(
                        manufacturer = infoValues[MANUFACTURER_NAME_UUID],
                        model = infoValues[MODEL_NUMBER_UUID],
                        serialNumber = infoValues[SERIAL_NUMBER_UUID],
                        firmwareVersion = infoValues[FIRMWARE_REVISION_UUID],
                        softwareVersion = infoValues[SOFTWARE_REVISION_UUID]
                    )
                    if (info.manufacturer != null || info.model != null || info.serialNumber != null ||
                        info.firmwareVersion != null || info.softwareVersion != null
                    ) {
                        onDeviceInfoReceived(device.address, info)
                    }
                }
                readBatteryLevel(gatt)
            }
        }

        device.connectGatt(context, false, gattCallback)?.also { gatt ->
            gattMap[device.address] = gatt
        }
    }

    /** 读取电量（须在 BLE 线程调用，与 GATT 回调串行）。onDeviceReady 与 refreshBattery 共用 */
    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(gatt: BluetoothGatt) {
        // Battery Service (0x180F)
        val batteryChar = gatt.getService(BATTERY_SERVICE_UUID)
            ?.getCharacteristic(BATTERY_LEVEL_UUID)
        if (batteryChar != null) {
            @Suppress("DEPRECATION")
            gatt.readCharacteristic(batteryChar)
            return
        }
        // Device Information Service (0x180A) 备选
        val disChar = gatt.getService(DEVICE_INFO_SERVICE_UUID)
            ?.getCharacteristic(BATTERY_LEVEL_UUID)
        if (disChar != null) {
            gatt.readCharacteristic(disChar)
        }
    }

    /**
     * 周期性重读某设备电量。必须在 bleScope（BLE 单线程调度器）中调用，
     * 与 GATT 回调共享同一串行队列，不会并发读同一 gatt。
     */
    fun refreshBattery(address: String) {
        gattMap[address]?.let { readBatteryLevel(it) }
    }

    fun disconnect(address: String) {
        gattMap[address]?.disconnect()
        gattMap[address]?.close()
        gattMap.remove(address)
    }

    fun disconnectAll() {
        gattMap.values.toList().forEach { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        gattMap.clear()
    }
}
