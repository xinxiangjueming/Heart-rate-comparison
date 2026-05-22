package com.example.heartratecomparison.data

import android.content.Context
import android.util.Log
import com.example.heartratecomparison.R
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "CsvRecorder"

class CsvRecorder(
    private val scope: CoroutineScope,
    private val context: Context
) {
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var deviceOrder: List<String> = emptyList()
    private var deviceNames: Map<String, String> = emptyMap()

    private val latestHr = mutableMapOf<String, Int?>()
    private val builder = StringBuilder()
    private var lastSecond: Long = 0L

    fun onHeartRate(deviceAddress: String, heartRate: Int) {
        synchronized(this) {
            val w = writer ?: return
            val now = System.currentTimeMillis()
            val currentSecond = now / 1000

            if (lastSecond == 0L) {
                lastSecond = currentSecond
            }

            if (currentSecond != lastSecond) {
                writeRow(w, lastSecond)
                latestHr.clear()
                deviceOrder.forEach { latestHr[it] = null }
                lastSecond = currentSecond
            }

            latestHr[deviceAddress] = heartRate
        }
    }

    fun start(connectedDeviceAddresses: List<String>, addressToName: Map<String, String> = emptyMap(), onError: (String) -> Unit) {
        try {
            val dir = File(context.getExternalFilesDir(null), "Comparison")
            if (!dir.exists()) dir.mkdirs()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "heart_$dateStr.csv")
            writer = file.bufferedWriter()
            currentFile = file

            deviceOrder = connectedDeviceAddresses.sorted()
            deviceNames = addressToName

            val headers = mutableListOf("time")
            deviceOrder.forEach { addr ->
                val displayName = deviceNames[addr] ?: getSafeDeviceName(addr)
                headers.add("$displayName(hr)")
            }
            writer?.write(headers.joinToString(", ") + "\n")
            writer?.flush()

            latestHr.clear()
            deviceOrder.forEach { latestHr[it] = null }
            lastSecond = 0L
        } catch (e: Exception) {
            Log.e(TAG, "启动录制失败", e)
            onError(e.message ?: context.getString(R.string.error_file_create_failed))
        }
    }

    fun stop() {
        synchronized(this) {
            try {
                val w = writer ?: return
                // 写入最后一行
                if (lastSecond > 0) {
                    writeRow(w, lastSecond)
                }
                w.flush()
                w.close()
            } catch (e: Exception) {
                Log.e(TAG, "关闭文件失败", e)
            }
            writer = null
            currentFile = null
            latestHr.clear()
            lastSecond = 0L
        }
    }

    private fun writeRow(w: BufferedWriter, second: Long) {
        builder.clear()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(second * 1000))
        builder.append(time)
        deviceOrder.forEach { addr ->
            builder.append(", ")
            builder.append(latestHr[addr]?.toString() ?: "")
        }
        builder.append("\n")
        w.write(builder.toString())
        w.flush()
    }

    companion object {
        fun getSafeDeviceName(address: String): String {
            return address.replace(":", "-")
        }
    }
}
