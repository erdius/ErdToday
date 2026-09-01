package com.erdman.erdtoday.sync

import com.erdman.erdtoday.data.local.SyncStateDao
import com.erdman.erdtoday.data.local.SyncStateEntity
import com.erdman.erdtoday.data.local.TagDao
import com.erdman.erdtoday.data.local.TaskDao
import com.erdman.erdtoday.data.local.TaskEntity
import com.erdman.erdtoday.vikunja.VikunjaApi
import com.erdman.erdtoday.vikunja.VikunjaProjectSetup
import com.erdman.erdtoday.vikunja.VikunjaTaskMapper

sealed class SyncResult {
    data class Success(val pushed: Int, val pulled: Int) : SyncResult()
    data class Failure(val reason: String) : SyncResult()
}

/**
 * Push-then-pull sync against one Vikunja project ("ErdToday"). Conflict handling is
 * deliberately simple compared to the CalDAV-era design: Vikunja has no ETags/conditional
 * requests, so there's nothing to build a 412-retry loop around. Instead, pull only applies a
 * remote task's state onto a local row that is NOT currently `syncDirty` -- a dirty row is left
 * alone this cycle, and the next `sync()` call's push phase overwrites the server with the local
 * edit anyway, so a same-cycle pull skip can't lose data.
 */
class SyncEngine(
    private val taskDao: TaskDao,
    private val tagDao: TagDao,
    private val syncStateDao: SyncStateDao,
    private val api: VikunjaApi,
) {
    suspend fun sync(): SyncResult {
        val projectId = resolveProjectId() ?: return SyncResult.Failure("Could not resolve the ErdToday Vikunja project")
        val pushed = push(projectId)
        val pulled = pull(projectId)
        return if (pushed.failed == 0 && pulled.failed == 0) {
            SyncResult.Success(pushed.count, pulled.count)
        } else {
            SyncResult.Failure("push failed=${pushed.failed}, pull failed=${pulled.failed}")
        }
    }

    private suspend fun resolveProjectId(): Long? {
        syncStateDao.get()?.vikunjaProjectId?.let { return it }
        val id = VikunjaProjectSetup.findOrCreateErdTodayProject(api).getOrNull() ?: return null
        syncStateDao.set(SyncStateEntity(vikunjaProjectId = id))
        return id
    }

    private data class PushResult(val count: Int, val failed: Int)

    private suspend fun push(projectId: Long): PushResult {
        var count = 0
        var failed = 0
        for (task in taskDao.tasksNeedingSync()) {
            val ok = if (task.syncPendingDelete) pushDelete(task) else pushUpsert(task, projectId)
            if (ok) count++ else failed++
        }
        return PushResult(count, failed)
    }

    private suspend fun pushDelete(task: TaskEntity): Boolean {
        val vikunjaId = task.vikunjaTaskId
        if (vikunjaId == null) {
            // Never successfully pushed in the first place -- nothing to delete server-side.
            taskDao.deleteTaskById(task.id)
            return true
        }
        val result = api.deleteTask(vikunjaId)
        if (result.isSuccess) {
            taskDao.deleteTaskById(task.id)
            return true
        }
        return false
    }

    private suspend fun pushUpsert(task: TaskEntity, projectId: Long): Boolean {
        val write = VikunjaTaskMapper.toWrite(task)
        val result = if (task.vikunjaTaskId == null) {
            api.createTask(projectId, write)
        } else {
            api.updateTask(task.vikunjaTaskId, write)
        }
        val read = result.getOrNull() ?: return false
        val tagNames = taskDao.tagIdsFor(task.id).mapNotNull { tagDao.getById(it)?.name }
        if (!syncLabels(read.id, tagNames)) return false
        taskDao.updateTask(task.copy(vikunjaTaskId = read.id, syncDirty = false))
        return true
    }

    /** Replaces every label on the Vikunja task with exactly [tagNames] (resolve-or-create each,
     *  then diff the task's current label ids against the target set). */
    private suspend fun syncLabels(vikunjaTaskId: Long, tagNames: List<String>): Boolean {
        val targetIds = tagNames.map { name ->
            resolveOrCreateVikunjaLabel(name) ?: return false
        }.toSet()
        val currentIds = api.fetchLabelIds(vikunjaTaskId).getOrNull()?.toSet() ?: return false
        for (id in targetIds - currentIds) {
            if (api.addLabelToTask(vikunjaTaskId, id).isFailure) return false
        }
        for (id in currentIds - targetIds) {
            if (api.removeLabelFromTask(vikunjaTaskId, id).isFailure) return false
        }
        return true
    }

    private suspend fun resolveOrCreateVikunjaLabel(name: String): Long? {
        val existing = api.listLabels().getOrNull()?.firstOrNull { it.title == name }
        return existing?.id ?: api.createLabel(name).getOrNull()?.id
    }

    private data class PullResult(val count: Int, val failed: Int)

    private suspend fun pull(projectId: Long): PullResult {
        val remoteTasks = api.listTasks(projectId).getOrNull() ?: return PullResult(0, 1)
        var count = 0
        for (remote in remoteTasks) {
            val local = taskDao.getTaskByVikunjaTaskId(remote.id)
            if (local != null && local.syncDirty) {
                // Dirty locally -- skip applying this cycle; the next push overwrites the server.
                continue
            }
            if (local != null) {
                taskDao.updateTask(VikunjaTaskMapper.applyRead(remote, local))
            } else {
                // A task that exists on the server but not locally yet (e.g. created from another
                // client). Create a minimal local row for it.
                val newId = taskDao.insertTask(
                    TaskEntity(title = remote.title, syncDirty = false, vikunjaTaskId = remote.id),
                )
                taskDao.updateTask(
                    VikunjaTaskMapper.applyRead(remote, taskDao.getTaskEntity(newId)!!),
                )
            }
            count++
        }
        return PullResult(count, 0)
    }
}
