package com.erdman.erdtoday.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.erdman.erdtoday.domain.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A to-do. Dates use [LocalDate] (null scheduledDate = Anytime). Times stored via [Converters]. */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val scheduledDate: LocalDate? = null,
    val deadline: LocalDate? = null,
    val recurrence: Recurrence? = null,
    /** Things3-style reminder: a time-of-day on [scheduledDate]. Null = no reminder. */
    val reminderTime: LocalTime? = null,
    val completed: Boolean = false,
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.EPOCH,
    val sortOrder: Long = 0,
    /** This task's id on the Vikunja server once pushed; null until first successful push. */
    val vikunjaTaskId: Long? = null,
    /** Which Vikunja project this task lives in, once synced. Null until first successful push. */
    val vikunjaProjectId: Long? = null,
    /** True if a synced field changed locally since the last successful push. */
    val syncDirty: Boolean = true,
    /** True if deleted locally but the server DELETE hasn't succeeded yet. */
    val syncPendingDelete: Boolean = false,
)

/** One line of a to-do's checklist. */
@Entity(
    tableName = "checklist_items",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class ChecklistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val text: String,
    val done: Boolean = false,
    val sortOrder: Int = 0,
)

/** A user-created tag. Name is unique. */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

/** Many-to-many join: a to-do can carry several tags. */
@Entity(
    tableName = "task_tag",
    primaryKeys = ["taskId", "tagId"],
    foreignKeys = [
        ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class TaskTagCrossRef(
    val taskId: Long,
    val tagId: Long,
)

/** A local mirror of one Vikunja project. Kept in sync with the server's real project list. */
@Entity(tableName = "projects", indices = [Index(value = ["vikunjaProjectId"], unique = true)])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vikunjaProjectId: Long,
    val title: String,
    val hexColor: String = "",
)
