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
        ProjectEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class TodayDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun tagDao(): TagDao
    abstract fun projectDao(): ProjectDao

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

        /** v4 replaces CalDAV identity columns with a single Vikunja task id; repurposes
         *  sync_state's syncToken column for the Vikunja project id.
         *
         *  Uses the rebuild-and-copy pattern rather than `ALTER TABLE ... DROP COLUMN`:
         *  DROP COLUMN needs SQLite 3.35+, but the framework SQLite bundled with real
         *  devices at this app's minSdk (28) can be far older (confirmed failing with a
         *  "near DROP: syntax error" on an API 31 device running SQLite 3.32.2) -- so the
         *  direct approach isn't safe to rely on across the app's supported OS range.
         *
         *  `DROP TABLE tasks` below is safe against checklist_items/task_tag's
         *  `ON DELETE CASCADE` foreign keys into tasks(id) ONLY because this app never
         *  enables SQLite foreign-key enforcement (no `setForeignKeyConstraintsEnabled`
         *  anywhere in AppContainer's Room.databaseBuilder call, and the platform default
         *  is off) -- with enforcement on, SQLite's implicit DELETE-before-DROP would fire
         *  those cascades before tasks_new's copy of the same ids exists under the final
         *  `tasks` name, wiping the child rows. Verified empirically (row counts unchanged
         *  across a real on-device migration) but that empirical result is fragile: if this
         *  app ever turns FK enforcement on, this migration needs `PRAGMA foreign_keys=OFF`
         *  wrapped around the DROP/RENAME pair, or it will silently delete data. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE tasks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        scheduledDate INTEGER,
                        deadline INTEGER,
                        recurrence TEXT,
                        reminderTime INTEGER,
                        completed INTEGER NOT NULL,
                        completedAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        vikunjaTaskId INTEGER,
                        syncDirty INTEGER NOT NULL,
                        syncPendingDelete INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO tasks_new (
                        id, title, notes, scheduledDate, deadline, recurrence, reminderTime,
                        completed, completedAt, createdAt, sortOrder, vikunjaTaskId, syncDirty, syncPendingDelete
                    )
                    SELECT
                        id, title, notes, scheduledDate, deadline, recurrence, reminderTime,
                        completed, completedAt, createdAt, sortOrder, NULL, syncDirty, syncPendingDelete
                    FROM tasks
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE tasks")
                db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")

                db.execSQL("CREATE TABLE sync_state_new (id INTEGER NOT NULL PRIMARY KEY, vikunjaProjectId INTEGER)")
                db.execSQL("INSERT INTO sync_state_new (id, vikunjaProjectId) SELECT id, NULL FROM sync_state")
                db.execSQL("DROP TABLE sync_state")
                db.execSQL("ALTER TABLE sync_state_new RENAME TO sync_state")
            }
        }

        /** v5 adds a local mirror of Vikunja's project list (`projects`) and tags each task with
         *  which project it lives in, replacing the old single-project `sync_state` cache with a
         *  proper per-project table -- the schema foundation for syncing every Vikunja project
         *  instead of one hardcoded project.
         *
         *  `DROP TABLE IF EXISTS sync_state` is safe for the same reason documented on
         *  [MIGRATION_3_4] above: this app never enables SQLite foreign-key enforcement, and
         *  nothing has a foreign key into sync_state regardless. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS projects (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        vikunjaProjectId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        hexColor TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_projects_vikunjaProjectId ON projects(vikunjaProjectId)")
                db.execSQL("ALTER TABLE tasks ADD COLUMN vikunjaProjectId INTEGER")
                db.execSQL("DROP TABLE IF EXISTS sync_state")
            }
        }
    }
}
