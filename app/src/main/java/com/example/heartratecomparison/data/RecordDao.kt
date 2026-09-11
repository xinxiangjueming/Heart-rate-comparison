package com.example.heartratecomparison.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 会话 + 样本数（历史列表展示用） */
data class SessionWithCount(
    @Embedded val session: RecordSession,
    val sampleCount: Int
)

@Dao
interface RecordDao {

    @Insert
    suspend fun insertSession(session: RecordSession): Long

    /** 唯一索引 (sessionId, second, deviceAddress) + REPLACE：同秒同设备重复写只留最新值 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<HrSample>)

    @Insert
    suspend fun insertBatterySnapshots(snapshots: List<BatterySnapshot>)

    @Query("UPDATE sessions SET endTime = :endTime WHERE id = :sessionId")
    suspend fun updateSessionEndTime(sessionId: Long, endTime: Long)

    @Query("UPDATE sessions SET deviceSampleCounts = :deviceSampleCounts, avgHeartRate = :avgHeartRate WHERE id = :sessionId")
    suspend fun updateSessionSummary(sessionId: Long, deviceSampleCounts: String?, avgHeartRate: Double?)

    /** LEFT JOIN + GROUP BY 聚合样本数：单次扫描替代每行相关子查询（会话多时更快）；LEFT JOIN 保留零样本会话 */
    @Query(
        "SELECT s.*, COUNT(h.id) AS sampleCount " +
            "FROM sessions s LEFT JOIN hr_samples h ON h.sessionId = s.id " +
            "GROUP BY s.id ORDER BY s.startTime DESC"
    )
    suspend fun getSessionsWithCount(): List<SessionWithCount>

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: Long): RecordSession?

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY second ASC, deviceAddress ASC")
    suspend fun getSamples(sessionId: Long): List<HrSample>

    /** 电量快照（验证/排障用） */
    @Query("SELECT * FROM battery_snapshots WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBatterySnapshots(sessionId: Long): List<BatterySnapshot>

    /** 删除会话（hr_samples 与 battery_snapshots 通过外键级联删除） */
    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    /** 删除全部历史数据 */
    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}
