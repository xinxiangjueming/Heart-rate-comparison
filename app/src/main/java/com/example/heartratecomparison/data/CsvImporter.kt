package com.example.heartratecomparison.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Calendar

/**
 * CSV 导入器：解析本 App 自身导出的 CSV（CsvExporter 格式），写入 Room，
 * 返回新建 sessionId；文件不符合解析规律（非本应用导出）时返回 null。
 *
 * 期望格式（与 CsvExporter 对齐）：
 *   time, <设备名>(hr), <设备名2>(hr), ...
 *   HH:mm:ss, 心率值, 心率值, ...
 *   ...（数据区，空单元格表示该设备该秒无读数）
 *   （空行）
 *   Summary:
 *   ...
 *
 * 解析规律（"符合规律"的判定）：
 *   - 首个非空行表头，首列必须为 `time`，其余列为设备名（带可选 " (hr)" 后缀）；
 *   - 数据行以 HH:mm:ss 开头，逗号分隔的数值（可缺测留空）；
 *   - 遇到空行或 `Summary:` 结束数据区。
 *
 * 流式实现：逐行读取解析，任一时刻内存中只有当前行 + 累积的样本列表，
 * 不再把整个文件读成 String（原实现大文件时内存占用约为文件体积的 3 倍）。
 */
object CsvImporter {

    private const val TAG = "CsvImporter"

    /** 从 URI 读取并导入；读取失败（无权限/文件不存在）返回 null */
    suspend fun importUri(context: Context, uri: Uri): Long? {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "open csv failed: ${e.message}")
            return null
        } ?: return null

        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                importLines(context, reader.lineSequence())   // lineSequence 自动处理 \r\n，BOM 在 importLines 内剥离
            }
        }
    }

    /** 纯文本解析（便于单元测试与复用）；不符合规律返回 null */
    internal suspend fun importText(context: Context, text: String): Long? =
        importLines(context, text.lineSequence())

    /**
     * 流式逐行解析（Sequence 只能消费一次）；不符合规律返回 null。
     * 边读边累积 HrSample（sessionId=0，落库前统一替换为真实 id），
     * 不保留原始字符串行，避免中间列表。
     */
    private suspend fun importLines(context: Context, lines: Sequence<String>): Long? {
        // 定位表头（首个非空行），首列必须为 time；文件首行去掉 UTF-8 BOM（导出时写入，避免 Excel 中文乱码）
        var headerLine: String? = null
        var first = true
        for (line in lines) {
            val candidate = if (first) { first = false; line.removePrefix("\uFEFF") } else line
            if (candidate.isNotBlank()) {
                headerLine = candidate
                break
            }
        }
        val header = headerLine?.split(',')?.map { it.trim() }
        if (header == null || header.isEmpty() || !header[0].equals("time", ignoreCase = true)) return null
        val rawDeviceNames = header.drop(1)
        if (rawDeviceNames.isEmpty()) return null
        val deviceNames = rawDeviceNames.map { it.removeSuffix("(hr)").trim() }

        // 导入数据无真实蓝牙地址，合成稳定地址 imported-0/1/... 并携带设备名
        val addresses = deviceNames.indices.map { "imported-$it" }
        val namesMap = addresses.mapIndexed { idx, addr ->
            addr to (deviceNames[idx].takeIf { it.isNotBlank() } ?: "设备${idx + 1}")
        }.toMap()

        // 以"今天本地零点"为基准，把相对秒映射为 epoch second（图表只关心相对时间，
        // 但会话标题需一个可读日期，故用导入当天零点推算）
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val midnightSec = cal.timeInMillis / 1000

        val samples = mutableListOf<HrSample>()
        var prevRowSec = -1L     // 上一有效数据行的当天秒（累计秒基准）
        var cumSec = 0L          // 当前行的累计秒（相对首行）
        var firstCumSec = 0L     // 首行累计秒（会话起点）
        var hasRows = false

        // 数据行解析（空行或 Summary: 结束数据区）
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) break
            if (line.startsWith("Summary:", ignoreCase = true)) break
            val parts = line.split(',').map { it.trim() }
            if (parts.size < 2) continue
            val secOfDay = parseHms(parts[0])?.toLong() ?: continue

            // 累计秒（后一行时间小于前一行视为跨午夜进入下一天）
            if (hasRows) {
                val delta = secOfDay - prevRowSec
                cumSec += if (delta < 0) delta + 86400 else delta
            } else {
                cumSec = secOfDay
                firstCumSec = secOfDay
                hasRows = true
            }
            prevRowSec = secOfDay

            for (d in addresses.indices) {
                // parts[0] 为时间列，值列从 d+1 开始
                val cell = parts.getOrNull(d + 1)?.trim() ?: ""
                if (cell.isEmpty()) continue
                val hr = cell.toIntOrNull() ?: continue
                samples.add(
                    HrSample(
                        sessionId = 0,
                        second = midnightSec + cumSec,
                        deviceAddress = addresses[d],
                        heartRate = hr
                    )
                )
            }
        }
        if (!hasRows || samples.isEmpty()) return null

        val dao = HeartRateDatabase.getInstance(context).recordDao()
        val session = RecordSession(
            startTime = (midnightSec + firstCumSec) * 1000,
            endTime = (midnightSec + cumSec) * 1000,
            deviceOrder = addresses.joinToString(","),
            deviceNames = RecordSession.encodeDeviceNames(namesMap),
            deviceSampleCounts = null,
            avgHeartRate = null
        )
        val sessionId = dao.insertSession(session)
        dao.insertSamples(samples.map { it.copy(sessionId = sessionId) })
        return sessionId
    }

    /** "HH:mm:ss" → 当天秒数；格式不符返回 null */
    private fun parseHms(s: String): Int? {
        val parts = s.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val sec = parts[2].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59 || sec !in 0..59) return null
        return h * 3600 + m * 60 + sec
    }
}
