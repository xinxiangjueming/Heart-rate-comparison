package com.example.heartratecomparison.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

/**
 * 记录会话 — 一次"开始记录 → 停止记录"的完整数据集合。
 * 设备列顺序与名称以字符串形式存储（供 CSV 导出与图表回放还原列结构）。
 */
@Entity(tableName = "sessions")
data class RecordSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 记录开始时间（epoch ms） */
    val startTime: Long,
    /** 记录结束时间（epoch ms） */
    val endTime: Long,
    /** 设备列顺序：逗号分隔的蓝牙地址（已排序），如 "AA:BB:CC,DD:EE:FF" */
    val deviceOrder: String,
    /** 设备名映射：JSON 字符串 {"地址":"名称"} */
    val deviceNames: String,
    /** 每设备有效样本数：JSON {"设备地址": 数量}，null=旧数据/无 */
    val deviceSampleCounts: String? = null,
    /** 全局平均心率（所有设备所有样本合并），null=旧数据/无 */
    val avgHeartRate: Double? = null
) {
    fun deviceOrderList(): List<String> =
        deviceOrder.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun deviceNamesMap(): Map<String, String> = try {
        val json = JSONObject(deviceNames)
        json.keys().asSequence().associateWith { json.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    fun displayNameFor(address: String): String =
        deviceNamesMap()[address]?.takeIf { it.isNotBlank() }
            ?: address.replace(":", "-")

    companion object {
        fun encodeDeviceNames(names: Map<String, String>): String {
            val json = JSONObject()
            names.forEach { (k, v) -> json.put(k, v) }
            return json.toString()
        }
    }
}
