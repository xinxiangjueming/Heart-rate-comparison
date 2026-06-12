package com.example.heartratecomparison.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val HEART_RATE_SERVICE_UUID = ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB")
val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

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
    private val onBatteryLevelReceived: (String, Int) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothConnector"
    }

    private val gattMap = mutableMapOf<String, BluetoothGatt>()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (gattMap.containsKey(device.address)) return

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
                    handleBatteryRead(characteristic.uuid, characteristic.value, status)
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                scope.launch {
                    handleBatteryRead(characteristic.uuid, value, status)
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

            private fun handleBatteryRead(uuid: UUID, value: ByteArray?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && uuid == BATTERY_LEVEL_UUID) {
                    val level = value?.getOrNull(0)?.toInt()?.and(0xFF)
                    if (level != null) {
                        onBatteryLevelReceived(device.address, level)
                    }
                }
            }

            private fun onDeviceReady(gatt: BluetoothGatt) {
                // Battery Service (0x180F)
                val batteryChar = gatt.getService(BATTERY_SERVICE_UUID)
                    ?.getCharacteristic(BATTERY_LEVEL_UUID)
                if (batteryChar != null) {
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
        }

        device.connectGatt(context, false, gattCallback)?.also { gatt ->
            gattMap[device.address] = gatt
        }
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
