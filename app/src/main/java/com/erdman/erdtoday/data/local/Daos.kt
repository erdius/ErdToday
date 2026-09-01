package com.erdman.erdtoday.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
interface TaskDao {

    @Transaction
    @Query("SELECT * FROM tasks")
    fun observeAll(): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeTask(id: Long): Flow<TaskWithDetails?>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: Long): TaskWithDetails?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskEntity(id: Long): TaskEntity?

    /** Active to-dos carrying a reminder on a real date — used to (re)schedule alarms (e.g. after reboot). */
    @Query("SELECT * FROM tasks WHERE completed = 0 AND reminderTime IS NOT NULL AND scheduledDate IS NOT NULL")
    suspend fun tasksWithReminders(): List<TaskEntity>

    @Insert suspend fun insertTask(task: TaskEntity): Long
    @Update suspend fun updateTask(task: TaskEntity)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTaskById(id: Long)
    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM tasks") suspend fun maxSortOrder(): Long

    /** Empty the logbook (every completed to-do). */
    @Query("DELETE FROM tasks WHERE completed = 1") suspend fun deleteAllCompleted()

    /** Drop completed to-dos finished before [threshold] (logbook auto-prune). */
    @Query("DELETE FROM tasks WHERE completed = 1 AND completedAt IS NOT NULL AND completedAt < :threshold")
    suspend fun deleteCompletedBefore(threshold: Instant)

    /** Sweep blank drafts orphaned by a force-quit (no title, notes, or checklist). */
    @Query(
        "DELETE FROM tasks WHERE TRIM(title) = '' AND TRIM(notes) = '' " +
            "AND id NOT IN (SELECT taskId FROM checklist_items)",
    )
    suspend fun deleteEmptyTasks()

    // Checklist
    @Query("SELECT * FROM checklist_items WHERE taskId = :taskId ORDER BY sortOrder")
    suspend fun checklistFor(taskId: Long): List<ChecklistItemEntity>

    @Insert suspend fun insertChecklistItem(item: ChecklistItemEntity): Long
    @Update suspend fun updateChecklistItem(item: ChecklistItemEntity)
    @Delete suspend fun deleteChecklistItem(item: ChecklistItemEntity)
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM checklist_items WHERE taskId = :taskId")
    suspend fun maxChecklistOrder(taskId: Long): Int

    // Tag links
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addTagToTask(ref: TaskTagCrossRef)
    @Delete suspend fun removeTagFromTask(ref: TaskTagCrossRef)
    @Query("DELETE FROM task_tag WHERE taskId = :taskId") suspend fun clearTaskTags(taskId: Long)
    @Query("SELECT tagId FROM task_tag WHERE taskId = :taskId") suspend fun tagIdsFor(taskId: Long): List<Long>

    // Vikunja sync
    /** Rows that need a sync push: locally dirty, or deleted-pending-server-confirmation. */
    @Query("SELECT * FROM tasks WHERE syncDirty = 1 OR syncPendingDelete = 1")
    suspend fun tasksNeedingSync(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE vikunjaTaskId = :id LIMIT 1")
    suspend fun getTaskByVikunjaTaskId(id: Long): TaskEntity?

    // Multi-project views
    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0 AND deadline = :today")
    fun observeDueToday(today: LocalDate): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0 AND deadline IS NOT NULL AND deadline > :after")
    fun observeDueSoon(after: LocalDate): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0")
    fun observeAllOpen(): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE vikunjaProjectId = :id")
    fun observeByVikunjaProjectId(id: Long): Flow<List<TaskWithDetails>>
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id") suspend fun getById(id: Long): TagEntity?
    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): TagEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(tag: TagEntity): Long
    @Update suspend fun update(tag: TagEntity)
    @Delete suspend fun delete(tag: TagEntity)
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM tags") suspend fun maxSortOrder(): Int
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY title COLLATE NOCASE")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjects(projects: List<ProjectEntity>)

    @Query("SELECT * FROM projects WHERE vikunjaProjectId = :id LIMIT 1")
    suspend fun getByVikunjaProjectId(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun getByTitle(title: String): ProjectEntity?
}
