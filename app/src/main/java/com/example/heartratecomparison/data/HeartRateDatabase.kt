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
    version = 2,
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

        fun getInstance(context: Context): HeartRateDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HeartRateDatabase::class.java,
                    "heartrate.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
