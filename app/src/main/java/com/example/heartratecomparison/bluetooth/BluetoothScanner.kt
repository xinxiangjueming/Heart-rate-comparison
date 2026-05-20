package com.example.heartratecomparison.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.heartratecomparison.model.DeviceState
import java.util.UUID

class BluetoothScanner(
    private val context: Context
) {
    companion object {
        private const val TAG = "BluetoothScanner"
        val HEART_RATE_SERVICE_UUID = ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        val HEART_RATE_SERVICE_UUID_16 = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter = bluetoothManager.adapter
    val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null
    private var stopRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun startScan(
        onDeviceDiscovered: (DeviceState) -> Unit,
        onScanFailed: (Int) -> Unit
    ) {
        // 权限检查
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少 BLUETOOTH_SCAN 权限")
                onScanFailed(-1)
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "缺少位置权限")
                onScanFailed(-1)
                return
            }
        }

        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner 为空")
            return
        }

        stopScan()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (isHeartRateDevice(result)) {
                    val device = result.device
                    Log.d(TAG, "发现心率设备: ${device.name ?: "未知"} (${device.address})")
                    onDeviceDiscovered(DeviceState(device = device))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "扫描失败, 错误码: $errorCode")
                onScanFailed(errorCode)
            }
        }

        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(null, settings, callback)
            Log.d(TAG, "开始 BLE 扫描（过滤心率设备）")
        } catch (e: SecurityException) {
            Log.e(TAG, "没有扫描权限", e)
            onScanFailed(-1)
        }

        stopRunnable = Runnable { stopScan() }
        handler.postDelayed(stopRunnable!!, 15_000L)
    }

    fun stopScan() {
        try {
            scanCallback?.let { scanner?.stopScan(it) }
        } catch (e: Exception) {
            Log.e(TAG, "停止扫描异常", e)
        }
        stopRunnable?.let { handler.removeCallbacks(it) }
        scanCallback = null
        stopRunnable = null
    }

    private fun isHeartRateDevice(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false

        // 1. 检查顶层 Service UUIDs
        record.serviceUuids?.forEach { uuid ->
            if (uuid.uuid == HEART_RATE_SERVICE_UUID.uuid) {
                Log.d(TAG, "通过 serviceUuids 匹配到心率设备: ${result.device.address}")
                return true
            }
        }

        // 2. 检查 Service Data 中的键
        record.serviceData?.let { serviceData ->
            for (key in serviceData.keys) {
                if (key.uuid == HEART_RATE_SERVICE_UUID.uuid || key.uuid == HEART_RATE_SERVICE_UUID_16) {
                    Log.d(TAG, "通过 serviceData 匹配到心率设备: ${result.device.address}, key=${key.uuid}")
                    return true
                }
            }
        }

        return false
    }
}