package com.openingtip.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        TodoItemEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class TipDatabase : RoomDatabase() {
    abstract fun tipControlDao(): TipControlDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun sessionDao(): SessionDao
    abstract fun usageDao(): UsageDao
    abstract fun policyDao(): PolicyDao
    abstract fun todoDao(): TodoDao

    companion object {
        @Volatile
        private var INSTANCE: TipDatabase? = null

        fun getInstance(context: Context): TipDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TipDatabase::class.java,
                    "tip_database.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
