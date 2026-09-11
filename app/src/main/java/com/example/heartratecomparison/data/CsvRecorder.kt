package com.example.heartratecomparison.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CsvRecorder"

/**
 * 心率录制器（Room 持久化版）— 低优先级 IO 线程架构
 *
 * 保留原公开 API（start / onHeartRate / stop），新增 recordBattery。
 * 数据安全设计：
 *   - 秒切换时批量写库（保持既有节奏）
 *   - **每 5 秒强制落库**（含当前秒缓冲 + 电量快照 + 汇总更新），进程被杀最多丢 ≤5 秒
 *   - Channel 保持 UNLIMITED（数据不丢），积压监控只告警不改行为
 *   - hr_samples 唯一索引 (sessionId, second, deviceAddress) + REPLACE：重复写只留最新值，
 *     每设备每秒恰一行，样本计数/平均心率据此精确统计
 */
class CsvRecorder(private val context: Context) {

    // ── 消息协议（所有状态变更通过 Channel 路由到单消费者） ──
    private sealed interface Msg {
        data class Start(
            val addresses: List<String>,
            val names: Map<String, String>,
            val initialBattery: Map<String, Int?>,
            val deferred: CompletableDeferred<Unit>
        ) : Msg
        data class Hr(val addr: String, val bpm: Int) : Msg
        data class Battery(val addr: String, val level: Int) : Msg
        data class Flush(val deferred: CompletableDeferred<Unit>? = null) : Msg
        data class Stop(val deferred: CompletableDeferred<Unit>) : Msg
    }

    // ── IO scope ──────────────────────────────────────────
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<Msg>(Channel.UNLIMITED)
    private val dao = HeartRateDatabase.getInstance(context).recordDao()

    // 积压计数：主线程 increment / IO 消费 decrement（仅监控告警，不改行为，保证数据不丢）
    private val backlog = AtomicLong(0L)

    // ── 只在 processingLoop 中访问的 mutable 状态 ─────────
    private var sessionId: Long = 0L
    private var deviceOrder: List<String> = emptyList()
    private var deviceNames: Map<String, String> = emptyMap()
    private val latestHr = mutableMapOf<String, Int?>()
    private var lastSecond: Long = 0L

    // ── 汇总统计（配合 REPLACE 唯一行，与 DB 实际内容一致） ──
    private val deviceSampleCounts = mutableMapOf<String, Int>()   // 每设备有效样本数
    private var totalSampleCount = 0L
    private var totalHrSum = 0L
    // 计数去重：每设备最近一次已计入的 second 与该 second 计的值（同秒 REPLACE 用新值修正累计和）
    private val countedSecond = mutableMapOf<String, Long>()
    private val countedValue = mutableMapOf<String, Int>()

    // ── 电量：最新已知值（Start 参数 + Msg.Battery）/ 已记录值（去重基准） ──
    private val latestBattery = mutableMapOf<String, Int>()
    private val lastRecordedBattery = mutableMapOf<String, Int>()

    init {
        ioScope.launch { processingLoop() }
    }

    // ── 会话级定时器（录制期间才存在，空闲零唤醒） ──────────
    /** 录制会话期间运行：5 秒强制落库 + 积压监控；随 Msg.Start/Msg.Stop 启停 */
    private var sessionTimersJob: Job? = null

    private fun startSessionTimers() {
        stopSessionTimers()
        sessionTimersJob = ioScope.launch {
            // 积压监控：不改行为，仅当消费明显跟不上时告警 IO 过载
            launch {
                while (isActive) {
                    delay(5_000)
                    val n = backlog.get()
                    if (n > 500) {
                        Log.w(TAG, "录制 Channel 积压 $n 条未消化，IO 可能过载（正常 6 设备 1Hz 应趋近 0）")
                    }
                }
            }
            // 每 5 秒强制落库（含当前秒缓冲 + 汇总更新 + 电量快照）；只 trySend，不破坏单消费者
            while (isActive) {
                delay(5_000)
                channel.trySend(Msg.Flush())
                backlog.incrementAndGet()
            }
        }
    }

    private fun stopSessionTimers() {
        sessionTimersJob?.cancel()
        sessionTimersJob = null
    }

    // ── 主线程 API（全部零阻塞） ──────────────────────────

    fun onHeartRate(deviceAddress: String, heartRate: Int) {
        channel.trySend(Msg.Hr(deviceAddress, heartRate))
        backlog.incrementAndGet()
    }

    /** 主线程调用：把电量读数送进 Channel（零阻塞），落库交给 5s 强刷/stop（去重） */
    fun recordBattery(deviceAddress: String, level: Int) {
        channel.trySend(Msg.Battery(deviceAddress, level))
        backlog.incrementAndGet()
    }

    /**
     * suspend — 等待会话创建完成（数据表就绪）后返回
     * @throws Exception 数据库写入失败时抛出
     */
    suspend fun start(
        connectedDeviceAddresses: List<String>,
        addressToName: Map<String, String> = emptyMap(),
        initialBattery: Map<String, Int?> = emptyMap()
    ) {
        val deferred = CompletableDeferred<Unit>()
        channel.trySend(Msg.Start(connectedDeviceAddresses, addressToName, initialBattery, deferred))
        deferred.await()
    }

    /**
     * suspend — 等待最后一批数据落库后返回（强制写结束电量 + 最终汇总）
     */
    suspend fun stop() {
        val deferred = CompletableDeferred<Unit>()
        channel.trySend(Msg.Stop(deferred))
        deferred.await()
    }

    // ── IO 消费者循环（唯一消费者，所有 mutable 状态单线程访问） ──

    private suspend fun processingLoop() {
        for (msg in channel) {
            backlog.decrementAndGet()
            when (msg) {
                is Msg.Start -> {
                    try {
                        initSession(msg.addresses, msg.names, msg.initialBattery)
                        startSessionTimers()
                        msg.deferred.complete(Unit)
                    } catch (e: Exception) {
                        Log.e(TAG, "启动录制失败", e)
                        msg.deferred.completeExceptionally(e)
                    }
                }
                is Msg.Hr -> handleHrSample(msg.addr, msg.bpm)
                is Msg.Battery -> handleBattery(msg.addr, msg.level)
                is Msg.Flush -> {
                    try {
                        handleFlush()
                    } catch (e: Exception) {
                        Log.e(TAG, "强制落库失败", e)
                    }
                    msg.deferred?.complete(Unit)
                }
                is Msg.Stop -> {
                    stopSessionTimers()   // 最终落库由下方显式执行，定时器先行停止
                    try {
                        if (lastSecond > 0L) flushSecond(lastSecond)   // 写最后秒（REPLACE 幂等）
                        writeBatterySnapshots(force = true)            // 强制写结束电量（含同值）
                        updateSessionSummary()                         // 最终汇总
                        finishSession()
                    } catch (e: Exception) {
                        Log.e(TAG, "停止录制失败", e)
                    }
                    msg.deferred.complete(Unit)
                }
            }
        }
    }

    @Throws(Exception::class)
    private suspend fun initSession(
        addresses: List<String>,
        names: Map<String, String>,
        initialBattery: Map<String, Int?>
    ) {
        deviceOrder = addresses.sorted()
        deviceNames = names
        val now = System.currentTimeMillis()
        sessionId = dao.insertSession(
            RecordSession(
                startTime = now,
                endTime = now,
                deviceOrder = deviceOrder.joinToString(","),
                deviceNames = RecordSession.encodeDeviceNames(names)
            )
        )
        latestHr.clear()
        deviceOrder.forEach { latestHr[it] = null }
        lastSecond = 0L
        deviceSampleCounts.clear(); totalSampleCount = 0L; totalHrSum = 0L
        countedSecond.clear(); countedValue.clear()
        latestBattery.clear(); lastRecordedBattery.clear()
        initialBattery.forEach { (addr, level) -> level?.let { latestBattery[addr] = it } }
        Log.d(TAG, "录制会话已创建: sessionId=$sessionId, devices=${deviceOrder.size}")
    }

    private suspend fun handleHrSample(addr: String, bpm: Int) {
        if (sessionId == 0L) return
        val currentSecond = System.currentTimeMillis() / 1000
        if (lastSecond == 0L) lastSecond = currentSecond

        if (currentSecond != lastSecond) {
            flushSecond(lastSecond)          // 秒切换照常 flush（REPLACE，天然幂等）
            latestHr.clear()
            deviceOrder.forEach { latestHr[it] = null }
            lastSecond = currentSecond
        }
        latestHr[addr] = bpm
    }

    private fun handleBattery(addr: String, level: Int) {
        latestBattery[addr] = level         // 只更新内存，落库交给 5s 强刷/stop（去重）
    }

    /**
     * 写该秒样本（唯一索引 + REPLACE：同 (second, device) 只留最新值）。
     * 同步维护每设备样本计数与累计心率（新秒 +1；同秒 REPLACE 用新值修正累计和）。
     */
    private suspend fun flushSecond(second: Long): Boolean {
        if (sessionId == 0L) return false
        val samples = deviceOrder.mapNotNull { addr ->
            latestHr[addr]?.let { hr ->
                HrSample(sessionId = sessionId, second = second, deviceAddress = addr, heartRate = hr)
            }
        }
        if (samples.isEmpty()) return false
        dao.insertSamples(samples)
        samples.forEach { s ->
            val lastSec = countedSecond[s.deviceAddress]
            if (lastSec == null || lastSec != s.second) {
                // 新秒：首次计数
                countedSecond[s.deviceAddress] = s.second
                countedValue[s.deviceAddress] = s.heartRate
                deviceSampleCounts[s.deviceAddress] = (deviceSampleCounts[s.deviceAddress] ?: 0) + 1
                totalSampleCount++
                totalHrSum += s.heartRate
            } else {
                // 同秒被 REPLACE 更新：用新值修正累计和（DB 中即新值）
                val old = countedValue[s.deviceAddress] ?: s.heartRate
                totalHrSum += (s.heartRate - old)
                countedValue[s.deviceAddress] = s.heartRate
            }
        }
        return true
    }

    /** 每 5 秒强刷：写当前秒缓冲 + 电量快照（去重）+ 汇总更新 */
    private suspend fun handleFlush() {
        if (sessionId == 0L) return
        if (lastSecond > 0L) flushSecond(lastSecond)
        writeBatterySnapshots(force = false)
        updateSessionSummary()
    }

    /** force=false 仅记录变化值；force=true 无条件写全部（结束电量） */
    private suspend fun writeBatterySnapshots(force: Boolean) {
        if (sessionId == 0L) return
        val now = System.currentTimeMillis()
        val toInsert = mutableListOf<BatterySnapshot>()
        deviceOrder.forEach { addr ->
            val level = latestBattery[addr] ?: return@forEach
            val last = lastRecordedBattery[addr]
            if (force || last == null || last != level) {
                toInsert += BatterySnapshot(
                    sessionId = sessionId,
                    deviceAddress = addr,
                    batteryLevel = level,
                    timestamp = now
                )
                lastRecordedBattery[addr] = level
            }
        }
        if (toInsert.isNotEmpty()) dao.insertBatterySnapshots(toInsert)
    }

    private suspend fun updateSessionSummary() {
        if (sessionId == 0L) return
        val countsJson = if (deviceSampleCounts.isEmpty()) null else countsToJson(deviceSampleCounts)
        val avg = if (totalSampleCount > 0) totalHrSum.toDouble() / totalSampleCount else null
        dao.updateSessionSummary(sessionId, countsJson, avg)
    }

    private fun countsToJson(counts: Map<String, Int>): String {
        val json = JSONObject()
        counts.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    private suspend fun finishSession() {
        if (sessionId == 0L) return
        dao.updateSessionEndTime(sessionId, System.currentTimeMillis())
        Log.d(TAG, "录制会话已结束: sessionId=$sessionId")
        sessionId = 0L
        latestHr.clear()
        lastSecond = 0L
        deviceOrder = emptyList()
        deviceNames = emptyMap()
        deviceSampleCounts.clear(); totalSampleCount = 0L; totalHrSum = 0L
        countedSecond.clear(); countedValue.clear()
        latestBattery.clear(); lastRecordedBattery.clear()
    }
}
