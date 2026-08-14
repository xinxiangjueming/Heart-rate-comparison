package com.example.heartratecomparison.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 电量快照 — 某会话中某设备某时刻的电量读数。
 * 去重插入（同值不重复写）；stop 时强制写一条结束电量。
 */
@Entity(
    tableName = "battery_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = RecordSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class BatterySnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val deviceAddress: String,
    /** 电量百分比 0-100 */
    val batteryLevel: Int,
    /** 记录时刻（epoch ms） */
    val timestamp: Long
)
