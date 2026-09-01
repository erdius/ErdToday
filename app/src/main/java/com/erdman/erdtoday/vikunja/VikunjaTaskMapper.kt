package com.erdman.erdtoday.vikunja

import com.erdman.erdtoday.data.local.TaskEntity
import java.time.ZoneOffset

object VikunjaTaskMapper {

    /** Maps the locally-owned, syncable fields of [task] to a write payload. Requires nothing
     *  Vikunja-side to already exist -- the caller supplies project/label association separately. */
    fun toWrite(task: TaskEntity): VikunjaTaskWrite = VikunjaTaskWrite(
        title = task.title,
        dueDate = task.deadline?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
        done = task.completed,
    )

    /** Applies a fetched Vikunja task's synced fields onto [into], preserving every local-only
     *  field ([TaskEntity.notes], checklist-adjacent state lives elsewhere, [TaskEntity.recurrence],
     *  [TaskEntity.reminderTime], [TaskEntity.scheduledDate], sortOrder, createdAt, id). */
    fun applyRead(read: VikunjaTaskRead, into: TaskEntity): TaskEntity = into.copy(
        title = read.title,
        deadline = read.dueDate,
        completed = read.done,
        completedAt = read.doneAt,
        vikunjaTaskId = read.id,
    )
}
