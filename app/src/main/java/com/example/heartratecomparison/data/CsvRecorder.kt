package com.example.heartratecomparison.data

import android.content.Context
import com.example.heartratecomparison.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class CsvRecorder(
    private val scope: CoroutineScope,
    private val context: Context
) {
    private val hrChannel = Channel<Pair<String, Int>>(capacity = 1024)
    private var writer: BufferedWriter? = null
    private var deviceOrder: List<String> = emptyList()
    private var writeJob: Job? = null

    fun onHeartRate(deviceAddress: String, heartRate: Int) {
        hrChannel.trySend(deviceAddress to heartRate)
    }

    fun start(connectedDeviceAddresses: List<String>, onError: (String) -> Unit) {
        try {
            val dir = File(context.getExternalFilesDir(null), "Comparison")
            if (!dir.exists()) dir.mkdirs()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "heart_$dateStr.csv")
            writer = file.bufferedWriter()

            deviceOrder = connectedDeviceAddresses.sorted()

            val headers = mutableListOf("time")
            deviceOrder.forEach { addr ->
                val safeName = getSafeDeviceName(addr)
                headers.add("$safeName(hr)")
            }
            writer?.write(headers.joinToString(", ") + "\n")
            writer?.flush()

            writeJob = scope.launch(Dispatchers.IO) {
                consumeAndWrite()
            }
        } catch (e: Exception) {
            onError(e.message ?: context.getString(R.string.error_file_create_failed))
        }
    }

    suspend fun stop() {
        hrChannel.close()
        writeJob?.join()
        writer?.flush()
        writer?.close()
        writer = null
    }

    private suspend fun consumeAndWrite() {
        val builder = StringBuilder()
        val latestHr = mutableMapOf<String, Int?>()      // 改用普通 HashMap，允许 null
        deviceOrder.forEach { latestHr[it] = null }

        var lastSecond = System.currentTimeMillis() / 1000
        var lastFlush = System.currentTimeMillis()

        for ((addr, hr) in hrChannel) {
            val now = System.currentTimeMillis()
            val currentSecond = now / 1000

            if (currentSecond != lastSecond) {
                writeRow(builder, latestHr, lastSecond)
                deviceOrder.forEach { latestHr[it] = null }   // 现在允许 null
                lastSecond = currentSecond

                if (now - lastFlush >= 5000) {
                    writer?.flush()
                    lastFlush = now
                }
            }

            latestHr[addr] = hr
        }

        writeRow(builder, latestHr, lastSecond)
        writer?.flush()
    }

    private fun writeRow(builder: StringBuilder, hrMap: Map<String, Int?>, second: Long) {
        builder.clear()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(second * 1000))
        builder.append(time)
        deviceOrder.forEach { addr ->
            builder.append(", ")
            builder.append(hrMap[addr]?.toString() ?: "null")
        }
        builder.append("\n")
        writer?.write(builder.toString())
    }

    companion object {
        fun getSafeDeviceName(address: String): String {
            return address.replace(":", "-")
        }
    }
}