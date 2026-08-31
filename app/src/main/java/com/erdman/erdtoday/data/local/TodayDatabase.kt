package com.erdman.erdtoday.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        ChecklistItemEntity::class,
        TagEntity::class,
        TaskTagCrossRef::class,
        SyncStateEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TodayDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun tagDao(): TagDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        /** v2 adds the nullable reminder time-of-day (second-of-day) column to tasks. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminderTime INTEGER")
            }
        }

        /** v3 adds CalDAV sync-tracking columns to tasks. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN caldavUid TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN caldavHref TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN caldavEtag TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncPendingDelete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_state (id INTEGER NOT NULL PRIMARY KEY, syncToken TEXT)")
            }
        }
    }
}
