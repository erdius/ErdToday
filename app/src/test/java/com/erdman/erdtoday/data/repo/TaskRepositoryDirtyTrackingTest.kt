package com.erdman.erdtoday.data.repo

import com.erdman.erdtoday.data.local.ChecklistItemEntity
import com.erdman.erdtoday.data.local.TagDao
import com.erdman.erdtoday.data.local.TagEntity
import com.erdman.erdtoday.data.local.TaskDao
import com.erdman.erdtoday.data.local.TaskEntity
import com.erdman.erdtoday.data.local.TaskTagCrossRef
import com.erdman.erdtoday.data.local.TaskWithDetails
import com.erdman.erdtoday.data.settings.SettingsStore
import com.erdman.erdtoday.domain.Recurrence
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Exercises [TaskRepository]'s CalDAV dirty-tracking: which mutations flip `syncDirty`
 * (title/scheduledDate/deadline/completed/tags — the fields the sync engine actually pushes)
 * and which must NOT (notes/recurrence/checklist/reminderTime — out of sync scope), plus
 * soft-delete-when-previously-synced behavior.
 */
class TaskRepositoryDirtyTrackingTest {

    private lateinit var taskDao: FakeTaskDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var repository: TaskRepository

    @Before
    fun setUp() {
        taskDao = FakeTaskDao()
        tagDao = FakeTagDao()
        val settings = mockk<SettingsStore>()
        coEvery { settings.autoCompleteValue() } returns true
        coEvery { settings.dayStartValue() } returns LocalTime.of(3, 0)
        repository = TaskRepository(taskDao, tagDao, settings)
    }

    /** Inserts a task already marked clean (syncDirty = false), so a later mutation's effect is observable. */
    private suspend fun insertCleanTask(vikunjaTaskId: Long? = null): Long =
        taskDao.insertTask(
            TaskEntity(
                title = "Test task",
                createdAt = Instant.EPOCH,
                syncDirty = false,
                vikunjaTaskId = vikunjaTaskId,
            ),
        )

    private suspend fun isDirty(id: Long): Boolean = taskDao.getTaskEntity(id)!!.syncDirty

    // ---- Fields that DO sync -----------------------------------------------

    @Test
    fun `setTitle marks dirty`() = runTest {
        val id = insertCleanTask()
        repository.setTitle(id, "New title")
        assertTrue(isDirty(id))
    }

    @Test
    fun `setDeadline marks dirty`() = runTest {
        val id = insertCleanTask()
        repository.setDeadline(id, LocalDate.of(2026, 1, 1))
        assertTrue(isDirty(id))
    }

    @Test
    fun `setScheduledDate marks dirty`() = runTest {
        val id = insertCleanTask()
        repository.setScheduledDate(id, LocalDate.of(2026, 1, 1))
        assertTrue(isDirty(id))
    }

    @Test
    fun `setTaskCompleted marks dirty`() = runTest {
        val id = insertCleanTask()
        repository.setTaskCompleted(id, true)
        assertTrue(isDirty(id))
    }

    @Test
    fun `setTaskTags marks dirty`() = runTest {
        val id = insertCleanTask()
        val tagId = tagDao.insert(TagEntity(name = "Work"))
        repository.setTaskTags(id, listOf(tagId))
        assertTrue(isDirty(id))
    }

    // ---- Fields that do NOT sync --------------------------------------------

    @Test
    fun `setNotes does NOT mark dirty`() = runTest {
        val id = insertCleanTask()
        repository.setNotes(id, "Some notes")
        assertFalse(isDirty(id))
    }

    @Test
    fun `setRecurrence does NOT mark dirty`() = runTest {
        val id = insertCleanTask()
        repository.setRecurrence(id, Recurrence.DAILY)
        assertFalse(isDirty(id))
    }

    @Test
    fun `addChecklistItem does NOT mark dirty`() = runTest {
        val id = insertCleanTask()
        repository.addChecklistItem(id, "Buy milk")
        assertFalse(isDirty(id))
    }

    @Test
    fun `setChecklistItemDone does NOT mark dirty, when it does not also complete the task`() = runTest {
        // Two items: checking only one leaves the checklist (and therefore `completed`) unchanged,
        // isolating the checklist-item mutation itself from applyCompletion's auto-complete path
        // (which intentionally *does* mark dirty when it flips `completed` — see the "setTaskCompleted
        // marks dirty" case above, which also covers that path via direct completion).
        val id = insertCleanTask()
        repository.addChecklistItem(id, "Buy milk")
        repository.addChecklistItem(id, "Buy eggs")
        taskDao.updateTask(taskDao.getTaskEntity(id)!!.copy(syncDirty = false)) // isolate from the adds above
        val firstItem = taskDao.checklistFor(id).first()
        repository.setChecklistItemDone(firstItem, true)
        assertFalse(isDirty(id))
    }

    @Test
    fun `setReminder does NOT mark dirty`() = runTest {
        val id = insertCleanTask()
        repository.setReminder(id, LocalTime.of(9, 0))
        assertFalse(isDirty(id))
    }

    // ---- Delete / soft-delete ------------------------------------------------

    @Test
    fun `deleteTask on a task with no vikunjaTaskId deletes it for real`() = runTest {
        val id = insertCleanTask(vikunjaTaskId = null)
        repository.deleteTask(id)
        assertNull(taskDao.getTaskEntity(id))
    }

    @Test
    fun `deleteTask on a task with a vikunjaTaskId soft-deletes it`() = runTest {
        val id = insertCleanTask(vikunjaTaskId = 123L)
        repository.deleteTask(id)
        val t = taskDao.getTaskEntity(id)
        assertNotNull(t)
        assertTrue(t!!.syncPendingDelete)
    }
}

/** In-memory [TaskDao] fake — no Room/Android dependency, so this runs as a plain JVM unit test. */
class FakeTaskDao : TaskDao {
    private val tasks = LinkedHashMap<Long, TaskEntity>()
    private val checklistItems = LinkedHashMap<Long, ChecklistItemEntity>()
    private val taskTags = mutableListOf<TaskTagCrossRef>()
    private var nextTaskId = 1L
    private var nextChecklistId = 1L

    // Tags aren't resolved to TagEntity here (this fake has no TagDao access) — not needed by these
    // tests, which only assert on TaskEntity.syncDirty/syncPendingDelete.
    private fun snapshot(id: Long): TaskWithDetails? {
        val task = tasks[id] ?: return null
        val checklist = checklistItems.values.filter { it.taskId == id }.sortedBy { it.sortOrder }
        return TaskWithDetails(task = task, checklist = checklist, tags = emptyList())
    }

    override fun observeAll(): Flow<List<TaskWithDetails>> =
        flowOf(tasks.keys.mapNotNull { snapshot(it) })

    override fun observeTask(id: Long): Flow<TaskWithDetails?> = flowOf(snapshot(id))

    override suspend fun getTask(id: Long): TaskWithDetails? = snapshot(id)

    override suspend fun getTaskEntity(id: Long): TaskEntity? = tasks[id]

    override suspend fun tasksWithReminders(): List<TaskEntity> =
        tasks.values.filter { !it.completed && it.reminderTime != null && it.scheduledDate != null }

    override suspend fun insertTask(task: TaskEntity): Long {
        val id = if (task.id != 0L) task.id else nextTaskId++
        tasks[id] = task.copy(id = id)
        return id
    }

    override suspend fun updateTask(task: TaskEntity) {
        tasks[task.id] = task
    }

    override suspend fun deleteTaskById(id: Long) {
        tasks.remove(id)
        checklistItems.values.filter { it.taskId == id }.forEach { checklistItems.remove(it.id) }
        taskTags.removeAll { it.taskId == id }
    }

    override suspend fun maxSortOrder(): Long = tasks.values.maxOfOrNull { it.sortOrder } ?: 0

    override suspend fun deleteAllCompleted() {
        tasks.values.filter { it.completed }.forEach { tasks.remove(it.id) }
    }

    override suspend fun deleteCompletedBefore(threshold: Instant) {
        tasks.values.filter { it.completed && (it.completedAt ?: Instant.MAX) < threshold }
            .forEach { tasks.remove(it.id) }
    }

    override suspend fun deleteEmptyTasks() {
        tasks.values
            .filter { it.title.isBlank() && it.notes.isBlank() && checklistItems.values.none { c -> c.taskId == it.id } }
            .forEach { tasks.remove(it.id) }
    }

    override suspend fun checklistFor(taskId: Long): List<ChecklistItemEntity> =
        checklistItems.values.filter { it.taskId == taskId }.sortedBy { it.sortOrder }

    override suspend fun insertChecklistItem(item: ChecklistItemEntity): Long {
        val id = if (item.id != 0L) item.id else nextChecklistId++
        checklistItems[id] = item.copy(id = id)
        return id
    }

    override suspend fun updateChecklistItem(item: ChecklistItemEntity) {
        checklistItems[item.id] = item
    }

    override suspend fun deleteChecklistItem(item: ChecklistItemEntity) {
        checklistItems.remove(item.id)
    }

    override suspend fun maxChecklistOrder(taskId: Long): Int =
        checklistItems.values.filter { it.taskId == taskId }.maxOfOrNull { it.sortOrder } ?: -1

    override suspend fun addTagToTask(ref: TaskTagCrossRef) {
        if (taskTags.none { it.taskId == ref.taskId && it.tagId == ref.tagId }) taskTags.add(ref)
    }

    override suspend fun removeTagFromTask(ref: TaskTagCrossRef) {
        taskTags.removeAll { it.taskId == ref.taskId && it.tagId == ref.tagId }
    }

    override suspend fun clearTaskTags(taskId: Long) {
        taskTags.removeAll { it.taskId == taskId }
    }

    override suspend fun tagIdsFor(taskId: Long): List<Long> =
        taskTags.filter { it.taskId == taskId }.map { it.tagId }

    override suspend fun tasksNeedingSync(): List<TaskEntity> =
        tasks.values.filter { it.syncDirty || it.syncPendingDelete }

    override suspend fun getTaskByVikunjaTaskId(id: Long): TaskEntity? =
        tasks.values.firstOrNull { it.vikunjaTaskId == id }

    override fun observeDueToday(today: java.time.LocalDate): Flow<List<TaskWithDetails>> =
        flowOf(tasks.values.filter { !it.completed && it.deadline == today }.mapNotNull { snapshot(it.id) })

    override fun observeDueSoon(after: java.time.LocalDate): Flow<List<TaskWithDetails>> =
        flowOf(
            tasks.values.filter { val d = it.deadline; !it.completed && d != null && d > after }
                .mapNotNull { snapshot(it.id) },
        )

    override fun observeAllOpen(): Flow<List<TaskWithDetails>> =
        flowOf(tasks.values.filter { !it.completed }.mapNotNull { snapshot(it.id) })

    override fun observeByVikunjaProjectId(id: Long): Flow<List<TaskWithDetails>> =
        flowOf(tasks.values.filter { it.vikunjaProjectId == id }.mapNotNull { snapshot(it.id) })
}

/** In-memory [TagDao] fake. */
class FakeTagDao : TagDao {
    private val tags = LinkedHashMap<Long, TagEntity>()
    private var nextId = 1L

    override fun observeTags(): Flow<List<TagEntity>> =
        flowOf(tags.values.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() })))

    override suspend fun getById(id: Long): TagEntity? = tags[id]

    override suspend fun getByName(name: String): TagEntity? =
        tags.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    override suspend fun insert(tag: TagEntity): Long {
        if (tags.values.any { it.name.equals(tag.name, ignoreCase = true) }) return -1
        val id = if (tag.id != 0L) tag.id else nextId++
        tags[id] = tag.copy(id = id)
        return id
    }

    override suspend fun update(tag: TagEntity) {
        tags[tag.id] = tag
    }

    override suspend fun delete(tag: TagEntity) {
        tags.remove(tag.id)
    }

    override suspend fun maxSortOrder(): Int = tags.values.maxOfOrNull { it.sortOrder } ?: -1
}
