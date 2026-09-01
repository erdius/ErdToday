package com.erdman.erdtoday.sync

import com.erdman.erdtoday.data.local.SyncStateDao
import com.erdman.erdtoday.data.local.SyncStateEntity
import com.erdman.erdtoday.data.local.TagEntity
import com.erdman.erdtoday.data.local.TaskEntity
import com.erdman.erdtoday.data.local.TaskTagCrossRef
import com.erdman.erdtoday.data.repo.FakeTagDao
import com.erdman.erdtoday.data.repo.FakeTaskDao
import com.erdman.erdtoday.vikunja.VikunjaApi
import com.erdman.erdtoday.vikunja.VikunjaLabel
import com.erdman.erdtoday.vikunja.VikunjaProject
import com.erdman.erdtoday.vikunja.VikunjaTaskRead
import com.erdman.erdtoday.vikunja.VikunjaTaskWrite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Exercises [SyncEngine]'s push/pull cycle against a fake [VikunjaApi] (see [FakeVikunjaApi]
 * below) and the [FakeTaskDao]/[FakeTagDao] doubles already established by
 * `TaskRepositoryDirtyTrackingTest`. `push()`/`pull()` are private, so every case drives them
 * through the public `sync()` entry point -- see individual test comments for how each case
 * isolates the phase it's actually testing.
 */
class SyncEngineTest {

    private lateinit var taskDao: FakeTaskDao
    private lateinit var tagDao: FakeTagDao
    private lateinit var syncStateDao: FakeSyncStateDao
    private lateinit var api: FakeVikunjaApi
    private lateinit var engine: SyncEngine

    private val projectId = 1L

    @Before
    fun setUp() {
        taskDao = FakeTaskDao()
        tagDao = FakeTagDao()
        syncStateDao = FakeSyncStateDao()
        api = FakeVikunjaApi()
        engine = SyncEngine(taskDao, tagDao, syncStateDao, api)
        // Most cases care about push/pull behavior, not project resolution -- pre-seed a
        // resolved project id so resolveProjectId() short-circuits via SyncStateDao. The one
        // case that exercises project resolution itself (below) overrides this.
        syncStateDao.state = SyncStateEntity(vikunjaProjectId = projectId)
    }

    @Test
    fun `push - dirty task with no vikunjaTaskId gets created, vikunjaTaskId set, syncDirty cleared`() = runTest {
        val id = taskDao.insertTask(TaskEntity(title = "Buy milk", syncDirty = true, vikunjaTaskId = null))

        engine.sync()

        assertEquals(1, api.createdTasks.size)
        assertEquals(projectId, api.createdTasks[0].first)
        assertEquals("Buy milk", api.createdTasks[0].second.title)
        val local = taskDao.getTaskEntity(id)!!
        assertNotNull(local.vikunjaTaskId)
        assertFalse(local.syncDirty)
    }

    @Test
    fun `push - dirty task with an existing vikunjaTaskId gets updated, not created`() = runTest {
        val vikunjaId = 50L
        api.tasks[vikunjaId] = VikunjaTaskRead(vikunjaId, "Old", null, false, null, Instant.EPOCH, emptyList())
        api.taskLabels[vikunjaId] = mutableSetOf()
        val id = taskDao.insertTask(TaskEntity(title = "New title", syncDirty = true, vikunjaTaskId = vikunjaId))

        engine.sync()

        assertEquals(0, api.createdTasks.size)
        assertEquals(1, api.updatedTasks.size)
        assertEquals(vikunjaId, api.updatedTasks[0].first)
        assertEquals("New title", api.updatedTasks[0].second.title)
        assertFalse(taskDao.getTaskEntity(id)!!.syncDirty)
    }

    @Test
    fun `push - syncPendingDelete task with a vikunjaTaskId gets deleted server-side, then removed locally`() = runTest {
        val vikunjaId = 77L
        api.tasks[vikunjaId] = VikunjaTaskRead(vikunjaId, "Gone", null, false, null, Instant.EPOCH, emptyList())
        api.taskLabels[vikunjaId] = mutableSetOf()
        val id = taskDao.insertTask(
            TaskEntity(title = "Gone", syncDirty = false, syncPendingDelete = true, vikunjaTaskId = vikunjaId),
        )

        engine.sync()

        assertTrue(api.deletedTaskIds.contains(vikunjaId))
        assertNull(taskDao.getTaskEntity(id))
    }

    @Test
    fun `push - syncPendingDelete task with no vikunjaTaskId just gets removed locally, no API call`() = runTest {
        val id = taskDao.insertTask(
            TaskEntity(title = "Never synced", syncDirty = false, syncPendingDelete = true, vikunjaTaskId = null),
        )

        engine.sync()

        assertTrue(api.deletedTaskIds.isEmpty())
        assertNull(taskDao.getTaskEntity(id))
    }

    @Test
    fun `push - tags on a task get synced as labels (resolve-or-create, add missing, remove stale)`() = runTest {
        val vikunjaId = 5L
        // Server-side starting state: label 10 ("Work") already attached, plus a stale label
        // (99) that's no longer in the task's local tag set and must be removed.
        api.labels.add(VikunjaLabel(id = 10L, title = "Work"))
        api.tasks[vikunjaId] = VikunjaTaskRead(vikunjaId, "Tagged", null, false, null, Instant.EPOCH, emptyList())
        api.taskLabels[vikunjaId] = mutableSetOf(10L, 99L)

        val workTagId = tagDao.insert(TagEntity(name = "Work"))
        val homeTagId = tagDao.insert(TagEntity(name = "Home")) // no matching Vikunja label yet -- must be created
        val id = taskDao.insertTask(TaskEntity(title = "Tagged", syncDirty = true, vikunjaTaskId = vikunjaId))
        taskDao.addTagToTask(TaskTagCrossRef(id, workTagId))
        taskDao.addTagToTask(TaskTagCrossRef(id, homeTagId))

        engine.sync()

        val homeLabel = api.labels.firstOrNull { it.title == "Home" }
        assertNotNull("expected a 'Home' label to have been created", homeLabel)
        assertTrue(api.addLabelCalls.contains(vikunjaId to homeLabel!!.id))
        assertTrue(api.removeLabelCalls.contains(vikunjaId to 99L))
        assertEquals(setOf(10L, homeLabel.id), api.taskLabels[vikunjaId])
    }

    @Test
    fun `pull - a clean local task gets updated from a remote task with newer title, deadline, done`() = runTest {
        val vikunjaId = 20L
        val id = taskDao.insertTask(
            TaskEntity(title = "Old title", deadline = null, completed = false, syncDirty = false, vikunjaTaskId = vikunjaId),
        )
        val newDeadline = LocalDate.of(2026, 3, 1)
        api.tasks[vikunjaId] = VikunjaTaskRead(
            id = vikunjaId,
            title = "New title",
            dueDate = newDeadline,
            done = true,
            doneAt = Instant.parse("2026-03-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
            labelIds = emptyList(),
        )

        engine.sync()

        val local = taskDao.getTaskEntity(id)!!
        assertEquals("New title", local.title)
        assertEquals(newDeadline, local.deadline)
        assertTrue(local.completed)
    }

    @Test
    fun `pull - a DIRTY local task is NOT overwritten by a remote pull`() = runTest {
        // The row is dirty AND its push fails (simulating e.g. a network flake) -- so it's still
        // dirty when the pull phase reaches it in the same sync() call. This is the realistic way
        // the guard triggers: push always runs first and clears syncDirty on success, so the only
        // way pull sees a still-dirty row for a task that has a vikunjaTaskId is if this cycle's
        // own push attempt for it didn't succeed.
        val vikunjaId = 21L
        api.tasks[vikunjaId] = VikunjaTaskRead(vikunjaId, "Remote title", null, true, null, Instant.EPOCH, emptyList())
        api.taskLabels[vikunjaId] = mutableSetOf()
        api.updateTaskShouldFail.add(vikunjaId)
        val id = taskDao.insertTask(
            TaskEntity(title = "Local edit", completed = false, syncDirty = true, vikunjaTaskId = vikunjaId),
        )

        val result = engine.sync()

        assertTrue(result is SyncResult.Failure)
        val local = taskDao.getTaskEntity(id)!!
        assertEquals("Local edit", local.title)
        assertFalse(local.completed)
        assertTrue(local.syncDirty)
    }

    @Test
    fun `pull - a remote task with no local match creates a new local row`() = runTest {
        val vikunjaId = 30L
        api.tasks[vikunjaId] = VikunjaTaskRead(vikunjaId, "Remote only", null, false, null, Instant.EPOCH, emptyList())

        engine.sync()

        val local = taskDao.getTaskByVikunjaTaskId(vikunjaId)
        assertNotNull(local)
        assertEquals("Remote only", local!!.title)
        assertFalse(local.syncDirty)
    }

    @Test
    fun `sync - resolveProjectId reuses a stored vikunjaProjectId rather than calling findOrCreateErdTodayProject again`() = runTest {
        // setUp() already seeded SyncStateDao with a project id -- assert that resolving it does
        // NOT fall through to VikunjaProjectSetup's listProjects/createProject calls.
        engine.sync()

        assertEquals(0, api.listProjectsCalls)
        assertEquals(0, api.createProjectCalls)
        assertEquals(projectId, api.lastListTasksProjectId)
    }

    @Test
    fun `sync - cold start finds-or-creates and persists the ErdToday project, then reuses it on the next sync`() = runTest {
        // Unlike every other case, start with NO sync_state row at all -- the real first-ever-
        // install shape -- so resolveProjectId() actually falls through to
        // VikunjaProjectSetup.findOrCreateErdTodayProject on this sync() call.
        syncStateDao.state = null

        val first = engine.sync()

        assertTrue(first is SyncResult.Success)
        assertEquals(1, api.listProjectsCalls)
        assertEquals(1, api.createProjectCalls)
        val createdProjectId = api.projects.single().id
        assertEquals(createdProjectId, syncStateDao.state?.vikunjaProjectId)

        // Second sync: the resolved id is now persisted, so this must reuse it via SyncStateDao
        // rather than calling findOrCreateErdTodayProject (and therefore listProjects/
        // createProject) again -- the write-then-reuse round trip.
        val second = engine.sync()

        assertTrue(second is SyncResult.Success)
        assertEquals(1, api.listProjectsCalls)
        assertEquals(1, api.createProjectCalls)
        assertEquals(createdProjectId, syncStateDao.state?.vikunjaProjectId)
    }
}

/** In-memory [SyncStateDao] fake -- a single mutable nullable field, matching the real table's
 *  single-row (id = 0) shape. */
class FakeSyncStateDao : SyncStateDao {
    var state: SyncStateEntity? = null
    override suspend fun get(): SyncStateEntity? = state
    override suspend fun set(state: SyncStateEntity) {
        this.state = state
    }
}

/** In-memory [VikunjaApi] fake driving [SyncEngine]'s tests without any real network calls.
 *  Exposes both its data (tasks/labels/projects, and the labels currently attached per task) and
 *  call-tracking lists/counters so tests can assert on what [SyncEngine] actually sent. */
class FakeVikunjaApi : VikunjaApi {
    val projects = mutableListOf<VikunjaProject>()
    private var nextProjectId = 1L
    var listProjectsCalls = 0
    var createProjectCalls = 0

    val labels = mutableListOf<VikunjaLabel>()
    private var nextLabelId = 1L

    /** taskId -> current read state. */
    val tasks = LinkedHashMap<Long, VikunjaTaskRead>()
    private var nextTaskId = 1000L

    /** taskId -> currently-attached label ids (mirrors what `/tasks/{id}/labels` would report). */
    val taskLabels = mutableMapOf<Long, MutableSet<Long>>()

    var lastListTasksProjectId: Long? = null
    val createdTasks = mutableListOf<Pair<Long, VikunjaTaskWrite>>() // projectId, write
    val updatedTasks = mutableListOf<Pair<Long, VikunjaTaskWrite>>() // taskId, write
    val deletedTaskIds = mutableListOf<Long>()
    val addLabelCalls = mutableListOf<Pair<Long, Long>>() // taskId, labelId
    val removeLabelCalls = mutableListOf<Pair<Long, Long>>() // taskId, labelId

    /** taskIds for which [updateTask] should simulate a server-side failure. */
    val updateTaskShouldFail = mutableSetOf<Long>()

    var closeCalls = 0
    override fun close() {
        closeCalls++
    }

    override suspend fun listProjects(): Result<List<VikunjaProject>> {
        listProjectsCalls++
        return Result.success(projects.toList())
    }

    override suspend fun createProject(title: String): Result<VikunjaProject> {
        createProjectCalls++
        val project = VikunjaProject(nextProjectId++, title)
        projects.add(project)
        return Result.success(project)
    }

    override suspend fun listTasks(projectId: Long): Result<List<VikunjaTaskRead>> {
        lastListTasksProjectId = projectId
        return Result.success(tasks.values.map { it.copy(labelIds = taskLabels[it.id]?.toList().orEmpty()) })
    }

    override suspend fun createTask(projectId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead> {
        createdTasks.add(projectId to task)
        val id = nextTaskId++
        val read = task.toRead(id)
        tasks[id] = read
        taskLabels.getOrPut(id) { mutableSetOf() }
        return Result.success(read)
    }

    override suspend fun updateTask(taskId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead> {
        updatedTasks.add(taskId to task)
        if (taskId in updateTaskShouldFail) return Result.failure(RuntimeException("simulated update failure"))
        val read = task.toRead(taskId)
        tasks[taskId] = read
        taskLabels.getOrPut(taskId) { mutableSetOf() }
        return Result.success(read)
    }

    override suspend fun deleteTask(taskId: Long): Result<Unit> {
        deletedTaskIds.add(taskId)
        tasks.remove(taskId)
        taskLabels.remove(taskId)
        return Result.success(Unit)
    }

    override suspend fun listLabels(): Result<List<VikunjaLabel>> = Result.success(labels.toList())

    override suspend fun createLabel(title: String): Result<VikunjaLabel> {
        val label = VikunjaLabel(nextLabelId++, title)
        labels.add(label)
        return Result.success(label)
    }

    override suspend fun addLabelToTask(taskId: Long, labelId: Long): Result<Unit> {
        addLabelCalls.add(taskId to labelId)
        taskLabels.getOrPut(taskId) { mutableSetOf() }.add(labelId)
        return Result.success(Unit)
    }

    override suspend fun removeLabelFromTask(taskId: Long, labelId: Long): Result<Unit> {
        removeLabelCalls.add(taskId to labelId)
        taskLabels[taskId]?.remove(labelId)
        return Result.success(Unit)
    }

    override suspend fun fetchLabelIds(taskId: Long): Result<List<Long>> =
        Result.success(taskLabels[taskId]?.toList().orEmpty())

    private fun VikunjaTaskWrite.toRead(id: Long): VikunjaTaskRead = VikunjaTaskRead(
        id = id,
        title = title,
        dueDate = dueDate?.atZone(ZoneOffset.UTC)?.toLocalDate(),
        done = done,
        doneAt = null,
        updatedAt = Instant.EPOCH,
        labelIds = taskLabels[id]?.toList().orEmpty(),
    )
}
