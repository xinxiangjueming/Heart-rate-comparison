package com.example.heartratecomparison.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

/**
 * 会话 → CSV 导出（分享用）。
 * 从 Room 读取会话与样本，按秒分组还原为"一行多列"格式（与历史 CSV 格式兼容）：
 *   time, <设备名>(hr), ...
 *   HH:mm:ss, 值, ...
 * 数据区之后追加**汇总区**（"Summary:" 单独一行作标题，下面直接写标签，不破坏表头结构，Excel 可读）：
 *   Samples / Start Battery / End Battery / Avg HR（按设备列对齐）
 *   Duration / Start Time / End Time（单值行）
 * 输出到应用缓存目录 share/ 下（FileProvider 已注册 cache-path）。
 *
 * 流式实现：BufferedWriter 逐行写盘（原实现把整个 CSV 攒成 StringBuilder 再 writeText，
 * 长会话时内存约为文件体积的 2 倍）；统计聚合单趟完成，不再按设备 groupBy 全量复制样本。
 */
object CsvExporter {

    /** 分享临时文件保留时长：7 天（启动时兜底清理用，不影响导出前的全量清理） */
    private const val SHARE_FILE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * @return 生成的临时 CSV 文件；会话不存在或无数据时返回 null
     */
    suspend fun exportSession(context: Context, sessionId: Long): File? {
        val dao = HeartRateDatabase.getInstance(context).recordDao()
        val session = dao.getSession(sessionId) ?: return null
        // 两个查询均已在 SQL 层排序：样本按 second ASC, deviceAddress ASC；电量快照按 timestamp ASC
        val samples = dao.getSamples(sessionId)
        if (samples.isEmpty()) return null
        val batterySnapshots = dao.getBatterySnapshots(sessionId)

        val dir = File(context.cacheDir, "share").apply {
            mkdirs()
            // 清理上一次分享遗留的临时 CSV（避免缓存累积）；当前文件保留供接收方读取
            listFiles()?.forEach { it.delete() }
        }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startTime))
        val file = File(dir, "heart_$dateStr.csv")

        val order = session.deviceOrderList()
        val orderIndex = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { idx, addr -> orderIndex[addr] = idx }
        val displayNames = session.deviceNamesMap()
        fun displayName(addr: String): String =
            displayNames[addr]?.takeIf { it.isNotBlank() } ?: addr.replace(":", "-")
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

        BufferedWriter(OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8), 1 shl 16).use { w ->
            // UTF-8 BOM：让 Excel/WPS 等按 UTF-8 解码（Windows 默认按 ANSI/GBK 解码无 BOM 文件，中文会乱码）
            w.write("\uFEFF")

            // —— 数据区 ——
            w.write("time")
            order.forEach { addr -> w.write(", ${displayName(addr)}(hr)") }
            w.write("\n")

            // 逐秒写行：samples 按 second 连续分组，双指针切组；组内按 orderIndex 直接定位列（不逐列线性扫）
            val row = arrayOfNulls<String>(order.size)
            var i = 0
            while (i < samples.size) {
                val second = samples[i].second
                Arrays.fill(row, null)
                var j = i
                while (j < samples.size && samples[j].second == second) {
                    val s = samples[j]
                    orderIndex[s.deviceAddress]?.let { idx -> row[idx] = s.heartRate.toString() }
                    j++
                }
                w.write(timeFormat.format(Date(second * 1000)))
                for (c in row.indices) {
                    w.write(", ")
                    row[c]?.let { w.write(it) }
                }
                w.write("\n")
                i = j
            }

            // —— 汇总区（尾部汇总行） ——
            // 单趟聚合：每设备样本数 / 心率累计（替代按设备 groupBy 全量复制）
            val counts = IntArray(order.size)
            val hrSums = LongArray(order.size)
            for (s in samples) {
                val idx = orderIndex[s.deviceAddress] ?: continue
                counts[idx]++
                // LongArray 复合赋值不会隐式把 Int 拓宽为 Long（否则报 "No set method providing array access"）
                hrSums[idx] += s.heartRate.toLong()
            }
            // 每设备起始/结束电量：快照已按 timestamp ASC，取每设备首个/末个
            val startBattery = arrayOfNulls<BatterySnapshot>(order.size)
            val endBattery = arrayOfNulls<BatterySnapshot>(order.size)
            for (b in batterySnapshots) {
                val idx = orderIndex[b.deviceAddress] ?: continue
                if (startBattery[idx] == null) startBattery[idx] = b
                endBattery[idx] = b
            }

            w.write("\n")
            // 汇总区标题单独一行，下面直接写标签
            w.write("Summary:\n")

            fun summaryRow(label: String, cell: (Int) -> String?) {
                w.write(label)
                for (c in order.indices) {
                    w.write(", ")
                    cell(c)?.let { w.write(it) }
                }
                w.write("\n")
            }

            // 按设备列对齐：有效数据点 / 起始电量 / 结束电量 / 平均心率
            summaryRow("Samples") { c -> counts[c].toString() }
            summaryRow("Start Battery") { c -> startBattery[c]?.let { "${it.batteryLevel}%" } }
            summaryRow("End Battery") { c -> endBattery[c]?.let { "${it.batteryLevel}%" } }
            summaryRow("Avg HR") { c ->
                if (counts[c] > 0) Math.round(hrSums[c].toDouble() / counts[c]).toString() else null
            }

            // 单值行
            w.write("Duration, ${formatDuration(session.endTime - session.startTime)}\n")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            w.write("Start Time, ${dateFormat.format(Date(session.startTime))}\n")
            w.write("End Time, ${dateFormat.format(Date(session.endTime))}\n")
        }
        return file
    }

    /**
     * 清理分享缓存目录中超过保留时长（7 天）的临时 CSV。
     * 作为"导出前全量清理"的兜底：用户导出一次后不再导出时，遗留文件最多保留 7 天。
     * 正在分享中的文件远年轻于 7 天，不受影响；目录不存在时为空操作。
     * 调用方须在 IO 线程执行（如 lifecycleScope.launch(Dispatchers.IO)）。
     */
    fun cleanExpiredShareFiles(context: Context) {
        val expireBefore = System.currentTimeMillis() - SHARE_FILE_MAX_AGE_MS
        File(context.cacheDir, "share").listFiles()?.forEach { file ->
            if (file.lastModified() < expireBefore) file.delete()
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
