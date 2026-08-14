package com.example.heartratecomparison.data

import android.content.Context
import java.io.File
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
 */
object CsvExporter {

    /**
     * @return 生成的临时 CSV 文件；会话不存在或无数据时返回 null
     */
    suspend fun exportSession(context: Context, sessionId: Long): File? {
        val dao = HeartRateDatabase.getInstance(context).recordDao()
        val session = dao.getSession(sessionId) ?: return null
        val samples = dao.getSamples(sessionId)
        if (samples.isEmpty()) return null
        val batterySnapshots = dao.getBatterySnapshots(sessionId)

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startTime))
        val file = File(dir, "heart_$dateStr.csv")

        val order = session.deviceOrderList()
        val bySecond = samples.groupBy { it.second }.toSortedMap()
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val sb = StringBuilder()

        // —— 数据区 ——
        sb.append("time")
        order.forEach { addr -> sb.append(", ${session.displayNameFor(addr)}(hr)") }
        sb.append("\n")

        for ((second, list) in bySecond) {
            sb.append(timeFormat.format(Date(second * 1000)))
            order.forEach { addr ->
                sb.append(", ")
                sb.append(list.firstOrNull { it.deviceAddress == addr }?.heartRate?.toString() ?: "")
            }
            sb.append("\n")
        }

        // —— 汇总区（尾部汇总行） ——
        appendSummary(sb, session, samples, batterySnapshots, order)

        // UTF-8 BOM：让 Excel/WPS 等按 UTF-8 解码（Windows 默认按 ANSI/GBK 解码无 BOM 文件，中文会乱码）
        file.writeText("\uFEFF$sb")
        return file
    }

    private fun cell(value: Any?): String = value?.toString() ?: ""

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** 追加尾部汇总行：按设备列对齐的行 + 单值行 */
    private fun appendSummary(
        sb: StringBuilder,
        session: RecordSession,
        samples: List<HrSample>,
        batterySnapshots: List<BatterySnapshot>,
        order: List<String>
    ) {
        val byDevice = samples.groupBy { it.deviceAddress }
        val batByDevice = batterySnapshots.groupBy { it.deviceAddress }

        sb.append("\n")
        // 汇总区标题单独一行，下面直接写标签
        sb.append("Summary:\n")

        fun row(label: String, perDevice: (String) -> Any?) {
            sb.append(label)
            order.forEach { addr -> sb.append(", ").append(cell(perDevice(addr))) }
            sb.append("\n")
        }

        // 按设备列对齐：有效数据点 / 起始电量 / 结束电量 / 平均心率
        row("Samples") { addr -> byDevice[addr]?.size ?: 0 }
        row("Start Battery") { addr ->
            batByDevice[addr]?.firstOrNull()?.let { "${it.batteryLevel}%" }
        }
        row("End Battery") { addr ->
            batByDevice[addr]?.lastOrNull()?.let { "${it.batteryLevel}%" }
        }
        row("Avg HR") { addr ->
            byDevice[addr]?.let { list -> Math.round(list.map { it.heartRate }.average()).toInt() }
        }

        // 单值行
        sb.append("Duration").append(", ").append(formatDuration(session.endTime - session.startTime)).append("\n")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.append("Start Time").append(", ").append(dateFormat.format(Date(session.startTime))).append("\n")
        sb.append("End Time").append(", ").append(dateFormat.format(Date(session.endTime))).append("\n")
    }
}
