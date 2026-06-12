package com.example.heartratecomparison.data

import android.content.Context
import android.util.Log
import com.example.heartratecomparison.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "CsvRecorder"

/**
 * CSV 心率录制器 — 低优先级 IO 线程架构
 *
 * 线程模型：
 *   - 所有公开 API 从主线程调用，零阻塞（trySend 到 Channel）
 *   - 所有文件 IO & mutable 状态变更在唯一的 processingLoop 协程中顺序执行（Dispatchers.IO）
 *   - 无竞态、无 synchronized
 */
class CsvRecorder(private val context: Context) {

    // ── 消息协议（所有状态变更通过 Channel 路由到单消费者） ──
    private sealed interface Msg {
        data class Start(
            val addresses: List<String>,
            val names: Map<String, String>,
            val deferred: CompletableDeferred<Unit>
        ) : Msg
        data class Hr(val addr: String, val bpm: Int) : Msg
        data class Stop(val deferred: CompletableDeferred<Unit>) : Msg
    }

    // ── IO scope ──────────────────────────────────────────
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<Msg>(Channel.UNLIMITED)

    // ── 只在 processingLoop 中访问的 mutable 状态 ─────────
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var deviceOrder: List<String> = emptyList()
    private var deviceNames: Map<String, String> = emptyMap()
    private val latestHr = mutableMapOf<String, Int?>()
    private val builder = StringBuilder()
    private var lastSecond: Long = 0L

    init {
        ioScope.launch { processingLoop() }
    }

    // ── 主线程 API（全部零阻塞） ──────────────────────────

    fun onHeartRate(deviceAddress: String, heartRate: Int) {
        channel.trySend(Msg.Hr(deviceAddress, heartRate))
    }

    /**
     * suspend — 等待文件创建 & 表头写入完成后返回
     * @throws Exception 文件创建失败时抛出
     */
    suspend fun start(
        connectedDeviceAddresses: List<String>,
        addressToName: Map<String, String> = emptyMap()
    ) {
        val deferred = CompletableDeferred<Unit>()
        channel.trySend(Msg.Start(connectedDeviceAddresses, addressToName, deferred))
        deferred.await()  // 成功返回；失败则抛出 initFile 中的异常
    }

    /**
     * suspend — 等待所有缓冲数据落盘后返回
     */
    suspend fun stop() {
        val deferred = CompletableDeferred<Unit>()
        channel.trySend(Msg.Stop(deferred))
        deferred.await()
    }

    // ── IO 消费者循环（唯一消费者，所有 mutable 状态单线程访问） ──

    private suspend fun processingLoop() {
        for (msg in channel) {
            when (msg) {
                is Msg.Start -> {
                    try {
                        initFile(msg.addresses, msg.names)
                        msg.deferred.complete(Unit)
                    } catch (e: Exception) {
                        Log.e(TAG, "启动录制失败", e)
                        msg.deferred.completeExceptionally(e)
                    }
                }
                is Msg.Hr -> handleHrSample(msg.addr, msg.bpm)
                is Msg.Stop -> {
                    flushAndClose()
                    msg.deferred.complete(Unit)
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun initFile(addresses: List<String>, names: Map<String, String>) {
        val dir = File(context.getExternalFilesDir(null), "Comparison")
        if (!dir.exists()) dir.mkdirs()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "heart_$dateStr.csv")
        writer = file.bufferedWriter()
        currentFile = file

        deviceOrder = addresses.sorted()
        deviceNames = names

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

        Log.d(TAG, "CSV 录制已开始: ${file.absolutePath}")
    }

    private fun handleHrSample(addr: String, bpm: Int) {
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

        latestHr[addr] = bpm
    }

    private fun flushAndClose() {
        try {
            val w = writer ?: return
            if (lastSecond > 0) {
                writeRow(w, lastSecond)
            }
            w.flush()
            w.close()
            Log.d(TAG, "CSV 文件已关闭: ${currentFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "关闭文件失败", e)
        }
        writer = null
        currentFile = null
        latestHr.clear()
        lastSecond = 0L
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
