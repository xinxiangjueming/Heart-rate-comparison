package com.example.heartratecomparison.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 心率样本 — 某个会话中，某台设备在某个秒级时间戳下的心率值。
 * 同一 second 下多台设备各占一行；导出 CSV / 图表回放时按 second 分组还原为"一行多列"。
 * 唯一索引 (sessionId, second, deviceAddress) 保证 5 秒强刷与秒切换写同一行时 DB 只留最新值。
 */
@Entity(
    tableName = "hr_samples",
    foreignKeys = [
        ForeignKey(
            entity = RecordSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // 仅保留唯一复合索引：最左前缀 (sessionId) 已覆盖所有按会话的查询
        // （getSamples / JOIN 聚合 / 级联删除），单列索引纯写放大，已于 v3 迁移删除
        Index(value = ["sessionId", "second", "deviceAddress"], unique = true)
    ]
)
data class HrSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 秒级时间戳（epoch second） */
    val second: Long,
    val deviceAddress: String,
    val heartRate: Int
)
