package com.openingtip.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openingtip.core.database.dao.*
import com.openingtip.core.database.entity.*

@Database(
    entities = [
        TipControlEntity::class,
        WhitelistEntryEntity::class,
        WhitelistRevisionEntity::class,
        WhitelistRevisionEntryEntity::class,
        SessionEntity::class,
        SessionSegmentEntity::class,
        UsageSliceEntity::class,
        SessionAppSummaryEntity::class,
        LocalEventEntity::class,
        UsageCheckpointEntity::class,
        PolicyCommandEntity::class,
        AppliedPolicyEntity::class,
        TodoItemEntity::class,
        FocusTimerEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class TipDatabase : RoomDatabase() {
    abstract fun tipControlDao(): TipControlDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun sessionDao(): SessionDao
    abstract fun usageDao(): UsageDao
    abstract fun policyDao(): PolicyDao
    abstract fun todoDao(): TodoDao
    abstract fun focusTimerDao(): FocusTimerDao

    companion object {
        @Volatile
        private var INSTANCE: TipDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `focus_timer` (
                        `singletonId` INTEGER NOT NULL PRIMARY KEY,
                        `sessionId` TEXT,
                        `timerStartedWallMs` INTEGER NOT NULL,
                        `timerStartedElapsedMs` INTEGER NOT NULL,
                        `timerDeadlineWallMs` INTEGER NOT NULL,
                        `timerDeadlineElapsedMs` INTEGER NOT NULL,
                        `timerTotalSeconds` INTEGER NOT NULL,
                        `timerIntentText` TEXT NOT NULL,
                        `timerStatus` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): TipDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TipDatabase::class.java,
                    "tip_database.db"
                )
                    .addMigrations(MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

