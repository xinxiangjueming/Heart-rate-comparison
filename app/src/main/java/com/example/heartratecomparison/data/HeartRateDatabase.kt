package com.example.heartratecomparison.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 心率记录数据库 — 位于应用私有目录（/data/data/<pkg>/databases/），天然满足"数据保存到软件私有目录"。
 */
@Database(
    entities = [RecordSession::class, HrSample::class, BatterySnapshot::class],
    version = 3,
    exportSchema = false
)
abstract class HeartRateDatabase : RoomDatabase() {

    abstract fun recordDao(): RecordDao

    companion object {
        @Volatile
        private var INSTANCE: HeartRateDatabase? = null

        /** v1 → v2：sessions 加 2 列汇总字段；新建 battery_snapshots；hr_samples 加唯一索引（存量无重复，安全） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `deviceSampleCounts` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `avgHeartRate` REAL DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `battery_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`deviceAddress` TEXT NOT NULL, " +
                        "`batteryLevel` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_battery_snapshots_sessionId` " +
                        "ON `battery_snapshots` (`sessionId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_hr_samples_sessionId_second_deviceAddress` " +
                        "ON `hr_samples` (`sessionId`, `second`, `deviceAddress`)"
                )
            }
        }

        /**
         * v2 → v3：hr_samples 删除单列索引 index_hr_samples_sessionId / index_hr_samples_second。
         * 唯一复合索引 (sessionId, second, deviceAddress) 的最左前缀已覆盖所有按 sessionId 的查询，
         * 单列索引只增加每行插入的索引维护开销与 DB 体积。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_hr_samples_sessionId`")
                db.execSQL("DROP INDEX IF EXISTS `index_hr_samples_second`")
            }
        }

        fun getInstance(context: Context): HeartRateDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HeartRateDatabase::class.java,
                    "heartrate.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
