package com.example.heartratecomparison.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.example.heartratecomparison.model.DeviceState
import kotlinx.coroutines.*
import java.util.UUID

val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val HEART_RATE_SERVICE_UUID = ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB")

class BluetoothConnector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDeviceConnected: (String) -> Unit,
    private val onDeviceDisconnected: (String) -> Unit,
    private val onHeartRateReceived: (String, Int) -> Unit
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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gatt.discoverServices()
                    } else {
                        Log.e(TAG, "GATT 连接失败, status=$status")
                        gattMap.remove(device.address)
                        gatt.close()
                        scope.launch(Dispatchers.Main) {
                            onDeviceDisconnected(device.address)
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gattMap.remove(device.address)
                    scope.launch(Dispatchers.Main) {
                        onDeviceDisconnected(device.address)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
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
                        }
                        scope.launch(Dispatchers.Main) {
                            onDeviceConnected(device.address)
                        }
                    }
                } else {
                    Log.e(TAG, "服务发现失败, status=$status")
                    gattMap.remove(device.address)
                    gatt.close()
                    scope.launch(Dispatchers.Main) {
                        onDeviceDisconnected(device.address)
                    }
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                    scope.launch(Dispatchers.Default) {
                        @Suppress("DEPRECATION")
                        val hr = parseHeartRate(characteristic.value)
                        withContext(Dispatchers.Main) {
                            onHeartRateReceived(device.address, hr)
                        }
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                    scope.launch(Dispatchers.Default) {
                        val hr = parseHeartRate(value)
                        withContext(Dispatchers.Main) {
                            onHeartRateReceived(device.address, hr)
                        }
                    }
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
