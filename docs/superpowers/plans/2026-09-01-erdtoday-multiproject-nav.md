# ErdToday Multi-Project Navigation Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ErdToday's single-project Vikunja sync and Things3-style 4-tab navigation (Today/Upcoming/Anytime/Logbook) with a Vikunja-shaped 6-tab navigation (Overview/Upcoming/Projects/Labels/My Open Tasks/Inbox) backed by full multi-project sync (every Vikunja project, not just one hardcoded "ErdToday" project).

**Architecture:** `SyncEngine` is rewritten to mirror every Vikunja project into a new local `ProjectEntity` table and push/pull tasks scoped to their own project (`TaskEntity` gains `vikunjaProjectId: Long?`). The old single-cached-project `SyncStateEntity` concept is dropped entirely — projects are always freshly listed and upserted each sync cycle (personal-scale project counts make this cheap; no caching complexity needed). Every new tab is a thin, mostly-reused `TaskListScreen`-style view over a new Room query (`dueToday`, `dueSoon`, `allOpen`, `byProject`, `inbox`) — no new sync work is needed for these views since they're all just different local filters over the same synced `tasks` table. "My Open Tasks" specifically corresponds to Vikunja's own real, dedicated `-2` pseudo-project (confirmed via this session's live API testing) but is implemented as a pure local query (`completed = 0` across all projects), not a live server call — matches the existing offline-first design.

**Tech Stack:** Same as the rest of the app — Room, Jetpack Compose, MMD components, the existing `VikunjaApi`/`VikunjaApiClient`/`SyncEngine`/`SyncWorker` infrastructure (all already built, reviewed, and live-verified).

**No separate spec document** — design was discussed and approved directly in chat, per the user's explicit "just implement" instruction (mirroring the earlier Vikunja-pivot plan's "move straight to plan" precedent).

## Global Constraints

- **Tab list, exact, in order**: Overview, Upcoming, Projects, Labels, My Open Tasks, Inbox. No Teams tab (explicitly excluded — the user doesn't use Vikunja Teams). No dedicated Logbook/completed-tasks-archive tab — Vikunja's own UI has no such concept either; instead, every list view gets a completed/incomplete filter toggle (mirroring how Vikunja's own project views filter by `done`). The existing "Keep completed to-dos" retention setting (Settings screen, `pruneLogbook`) stays as-is and continues to govern how long completed tasks are retained locally, just without a dedicated screen to browse them — they're visible via each view's completed-filter toggle instead.
- **Field mapping, confirmed from this session's real API research**: `deadline` (local) ↔ Vikunja `due_date` (already wired, unchanged). `scheduledDate` stays local-only, NOT synced (unchanged from the existing design — still true, still deliberate). "Overview" = tasks with `deadline == today`. "Upcoming" = tasks with `deadline` in the future, grouped by date (same grouping shape the old Upcoming view used for `scheduledDate`, just keyed on `deadline` now).
- **Vikunja's real, confirmed API facts this plan depends on** (verified live against the user's actual server earlier this session, not assumed): `GET /api/v1/projects` returns every project including the special ones (`id: 1` typically "Inbox", but identify Inbox by **title match "Inbox"**, not a hardcoded id — instance-specific ids aren't portable, same reasoning already used for finding "ErdToday" by title in the superseded single-project design); real project JSON fields `id`, `title`, `description`, `identifier`, `is_archived`, `hex_color`, `created`/`updated` (readonly). `PUT /projects/{id}/tasks` (create, unchanged), `POST /tasks/{id}` (update, unchanged) — both already correctly implemented in `VikunjaApiClient`/`SyncEngine` from the prior plan, this plan only changes WHICH project id each push targets and adds the project-listing/mirroring step.
- **Debug package**: `com.erdman.erdtoday.debug` (pre-existing `applicationIdSuffix`), device serial `MK20250402537`.
- **Gradle/Java environment**: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"` before any `./gradlew` invocation.
- Current toolchain (from prior plans, already landed): AGP 8.6.1, Gradle 8.7, Kotlin 1.9.22, Compose compiler 1.5.10. Do not change.
- Follow the existing codebase's established patterns exactly: `TaskListScreen`/`TaskRow`/`AppShell`/`Routes` for navigation and list rendering, `SettingsScreen`'s "screen calls `container.X()` directly for one-shot actions" pattern, MMD components everywhere an MMD equivalent exists (`LazyColumnMMD` is fine for these new screens — the pull-to-refresh-driven `LazyColumnMMD`→`LazyColumn` swap only applies to the four *old* list screens; new screens should default back to `LazyColumnMMD` unless they also need pull-to-refresh, in which case follow the same documented swap).
- **Read the real current file contents before modifying anything** — this plan describes the target shape and the verified external facts (API, field mappings), not necessarily byte-exact current code, since several files have changed across many prior tasks today. Adapt to what's actually there.

---

### Task 1: Schema — ProjectEntity, TaskEntity.vikunjaProjectId, new queries

**Files:**
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/Entities.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/TodayDatabase.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/Daos.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/repo/TaskRepository.kt`

**Interfaces:**
- Produces: `ProjectEntity(id: Long autogen, vikunjaProjectId: Long, title: String, hexColor: String)`. `TaskEntity.vikunjaProjectId: Long?` (replaces the old single-project assumption — every synced task now remembers which Vikunja project it lives in; null = not yet pushed anywhere). `SyncStateEntity` and its DAO are DELETED entirely (no longer needed — see Architecture note above). New `TaskDao` queries: `observeDueToday(today: LocalDate): Flow<List<TaskWithDetails>>`, `observeDueSoon(after: LocalDate): Flow<List<TaskWithDetails>>` (deadline in the future, any distance — grouping by date happens in the ViewModel same as the old Upcoming view), `observeAllOpen(): Flow<List<TaskWithDetails>>` (completed = 0, across all projects — "My Open Tasks"), `observeByVikunjaProjectId(id: Long): Flow<List<TaskWithDetails>>`, `observeInbox(): Flow<List<TaskWithDetails>>` (join against `ProjectEntity` where title = 'Inbox'). New `ProjectDao`: `observeProjects(): Flow<List<ProjectEntity>>`, `upsertProjects(projects: List<ProjectEntity>)` (`@Insert(onConflict = REPLACE)` keyed on a unique index over `vikunjaProjectId`), `getByVikunjaProjectId(id: Long): ProjectEntity?`, `getByTitle(title: String): ProjectEntity?`.
- Consumes: nothing from earlier tasks (this is the foundation).

- [ ] **Step 1: Add `ProjectEntity` and update `TaskEntity`**

In `Entities.kt`, replace the whole-app-syncs-one-project assumption. Add:
```kotlin
/** A local mirror of one Vikunja project. Kept in sync with the server's real project list. */
@Entity(tableName = "projects", indices = [Index(value = ["vikunjaProjectId"], unique = true)])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vikunjaProjectId: Long,
    val title: String,
    val hexColor: String = "",
)
```
In `TaskEntity`, replace whatever single-project tracking exists (read the current file — the prior plan's `vikunjaTaskId: Long?` stays, but confirm there's no longer any assumption of "the one project"; there shouldn't be, `vikunjaTaskId` alone was always project-agnostic) — add:
```kotlin
    /** Which Vikunja project this task lives in, once synced. Null until first successful push. */
    val vikunjaProjectId: Long? = null,
```
Delete `SyncStateEntity` entirely (its file location — check `Entities.kt`, it was defined there in the prior plan) — it's no longer needed once there's no single cached "the project" concept.

- [ ] **Step 2: Room migration**

Bump `@Database` version (check the current value first — should be 4 from the prior plan's work, so this becomes 5). Add `MIGRATION_4_5`:
```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                vikunjaProjectId INTEGER NOT NULL,
                title TEXT NOT NULL,
                hexColor TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_projects_vikunjaProjectId ON projects(vikunjaProjectId)")
        db.execSQL("ALTER TABLE tasks ADD COLUMN vikunjaProjectId INTEGER")
        db.execSQL("DROP TABLE IF EXISTS sync_state")
    }
}
```
Same DROP TABLE/FK-safety reasoning as the prior `MIGRATION_3_4` applies (this app never enables SQLite FK enforcement — confirmed and documented earlier this session; dropping `sync_state`, a table nothing else has a foreign key into, is unconditionally safe regardless). Add `ProjectEntity::class` to `@Database`'s `entities` list, remove `SyncStateEntity::class`. **Do NOT wire `MIGRATION_4_5` into `AppContainer.kt`'s builder yet** — same deferred-wiring pattern as every prior schema task this session, deferred to this plan's final task. `AppContainer.kt`'s builder currently chains `MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4` — leave that line untouched in this task.

- [ ] **Step 3: New DAOs**

Add `ProjectDao` to `Daos.kt`:
```kotlin
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
```
Add to `TaskDao` (read the existing `observeAll()`/`@Transaction` pattern first and match it exactly):
```kotlin
    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0 AND deadline = :today")
    fun observeDueToday(today: LocalDate): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0 AND deadline IS NOT NULL AND deadline > :today")
    fun observeDueSoon(today: LocalDate): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0")
    fun observeAllOpen(): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE vikunjaProjectId = :id")
    fun observeByVikunjaProjectId(id: Long): Flow<List<TaskWithDetails>>
```
(`observeInbox` doesn't need its own DAO query — it's `observeByVikunjaProjectId(inboxProject.vikunjaProjectId)` once the ViewModel has looked up the Inbox `ProjectEntity` by title via `ProjectDao.getByTitle("Inbox")`.) Remove `SyncStateDao` entirely from `Daos.kt`. Add `abstract fun projectDao(): ProjectDao` to `TodayDatabase.kt`, remove `abstract fun syncStateDao(): SyncStateDao`.

Note: these new queries don't filter by a completed/incomplete toggle yet — Task 5 adds that as a parameter once the shared list-screen infrastructure exists. Keep these queries simple for now (all default to `completed = 0` where that makes semantic sense, matching each view's primary purpose); Task 5 will parameterize.

- [ ] **Step 4: `TaskRepository` — no behavior change needed here, just confirm**

Read `TaskRepository.kt`'s existing soft-delete/dirty-tracking logic (`vikunjaTaskId != null` checks from the prior plan) — confirm nothing needs to change for THIS task specifically (project-awareness is a `SyncEngine`-level concern, Task 2's job, not `TaskRepository`'s). If you find any place `TaskRepository` assumed a single implicit project, note it in your report — there shouldn't be any (the prior single-project design never touched `TaskRepository`, only `SyncEngine`).

- [ ] **Step 5: Test suite + fresh-install verification**

Update `TaskRepositoryDirtyTrackingTest.kt`/`SyncEngineTest.kt`'s test fixtures if the `TaskEntity` constructor signature change (new field) breaks their compilation (it will, since Kotlin data class constructors are positional/named — using named args should already make old test code compile fine unless something relied on positional args; check and fix if needed).

```bash
./gradlew test
```
Then fresh-install verify (this task bumps `@Database` version with the migration deliberately unwired, same pattern as every prior schema task):
```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 uninstall com.erdman.erdtoday.debug
adb -s MK20250402537 install app/build/outputs/apk/debug/app-debug.apk
```
Spot-check the existing four views (Today/Upcoming/Anytime/Logbook — this task doesn't touch navigation yet, they're still there) still work: create a task, edit it, complete it, add a tag. Nothing should look different — this task only adds new, currently-unused schema.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add ProjectEntity, TaskEntity.vikunjaProjectId, and multi-project query infrastructure"
git push
```

---

### Task 2: SyncEngine — multi-project push/pull

**Files:**
- Modify: `app/src/main/java/com/erdman/erdtoday/sync/SyncEngine.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/vikunja/VikunjaProjectSetup.kt` (likely deleted/replaced — read first)
- Test: `app/src/test/java/com/erdman/erdtoday/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces: `SyncEngine(taskDao, tagDao, projectDao, api)` — note `syncStateDao` param is REMOVED (no longer needed), `projectDao` is NEW. `SyncEngine.sync(): SyncResult` keeps its existing signature/shape.
- Consumes: `ProjectEntity`/`ProjectDao` (Task 1), existing `VikunjaApi`/`VikunjaApiClient` (already built, unchanged).

**Design:**

Replace `resolveProjectId()`'s single-cached-id logic entirely with a `syncProjects()` step that runs first, every cycle:
```kotlin
private suspend fun syncProjects(): List<ProjectEntity>? {
    val remote = api.listProjects().getOrNull() ?: return null
    val local = remote.map { ProjectEntity(vikunjaProjectId = it.id, title = it.title, hexColor = "") }
    projectDao.upsertProjects(local)
    return projectDao.observeProjects().first() // re-read post-upsert to get local autogenerated ids alongside real vikunjaProjectIds
}
```
(Adjust: `upsertProjects` uses `OnConflictStrategy.REPLACE` keyed on the unique `vikunjaProjectId` index, so re-running this every cycle correctly updates titles/colors without creating duplicates — same idempotent-upsert pattern as everything else in this sync design.)

`push()` changes: `pushUpsert` needs a target Vikunja project id for `createTask`. A task being pushed for the FIRST TIME (`vikunjaTaskId == null`) must already have a **local** `vikunjaProjectId` set (Task 4's project-picker UI is responsible for setting this at task-creation time, defaulting to the Inbox project's id) — if a locally-dirty task somehow has no `vikunjaProjectId` at push time, treat it the same as any other per-item failure (skip it, count it as failed, log/report why — don't crash the loop). Read the actual current `pushUpsert` code before changing it; the shape should be close to:
```kotlin
private suspend fun pushUpsert(task: TaskEntity): Boolean {
    val targetProjectId = task.vikunjaProjectId ?: return false // no project assigned locally -- can't push
    val write = VikunjaTaskMapper.toWrite(task)
    val result = if (task.vikunjaTaskId == null) {
        api.createTask(targetProjectId, write)
    } else {
        api.updateTask(task.vikunjaTaskId, write)
    }
    // ... rest unchanged (labels sync, local persist) except also persist vikunjaProjectId if it
    // came back different (it shouldn't on update, but on create it's exactly what was targeted)
}
```

`pull()` changes: iterate every synced `ProjectEntity`, not one hardcoded id:
```kotlin
private suspend fun pull(projects: List<ProjectEntity>): PullResult {
    var count = 0
    var failed = 0
    for (project in projects) {
        val remoteTasks = api.listTasks(project.vikunjaProjectId).getOrNull() ?: run { failed++; continue }
        for (remote in remoteTasks) {
            // ... existing per-task apply-or-insert logic unchanged, except every inserted/updated
            // task also gets vikunjaProjectId = project.vikunjaProjectId set
        }
        count++
    }
    return PullResult(count, failed)
}
```

`sync()`'s overall shape: `syncProjects()` first (if it fails entirely — e.g. `listProjects()` itself fails — return `SyncResult.Failure` immediately, nothing else can proceed without knowing what projects exist); then `push()`; then `pull(projects)`.

- [ ] **Step 1: Rewrite `syncProjects`/project resolution**

Per the design above. Delete `VikunjaProjectSetup.kt` entirely (its find-or-create-ErdToday-specifically logic is now obsolete — every real project gets mirrored, none gets specially created by this app anymore; if the user wants a NEW project, they create it in Vikunja directly, same as any other project). Confirm nothing else references `VikunjaProjectSetup` before deleting it (grep first).

- [ ] **Step 2: Rewrite `push()`/`pushUpsert`/`pull()`**

Per the design above. Read the full current `SyncEngine.kt` first — adapt exactly, don't guess at surrounding code you haven't read.

- [ ] **Step 3: Compile check**

```bash
./gradlew :app:compileDebugKotlin -q
```

- [ ] **Step 4: Update `SyncEngineTest.kt`**

The existing tests assume a single project; rewrite the fixtures/tests for the multi-project shape. Test cases to add/adapt:
```
// syncProjects mirrors every remote project locally, upserting on re-run (no duplicates)
// push: a task with a local vikunjaProjectId but no vikunjaTaskId creates in the RIGHT project
// push: a task with no local vikunjaProjectId at all fails that item (not a crash) and is counted
// pull: iterates every project, not just one; tasks land with the correct vikunjaProjectId
// pull: a project whose listTasks() call fails is counted as failed but doesn't abort other projects
```
Reuse `FakeVikunjaApi`/`FakeTaskDao`/`FakeTagDao` patterns already established; add a `FakeProjectDao`.

- [ ] **Step 5: Run tests, commit**

```bash
./gradlew test
git add -A
git commit -m "Rewrite SyncEngine for multi-project sync (mirror every Vikunja project, push/pull per-project)"
git push
```

---

### Task 3: Task creation/edit — project picker

**Files:**
- Modify: `app/src/main/java/com/erdman/erdtoday/ui/detail/TaskDetailScreen.kt` (or wherever task create/edit actually lives — find it first)
- Modify: whatever ViewModel backs that screen
- Modify: `app/src/main/java/com/erdman/erdtoday/data/repo/TaskRepository.kt`

**Interfaces:**
- Produces: `TaskRepository.setProject(taskId: Long, vikunjaProjectId: Long?)` — marks the task dirty (project assignment is a synced concern) the same way `setTitle`/`setDeadline` already do, following the exact `markDirtyIfChanged`-adjacent pattern already established (read `TaskRepository.kt`'s existing methods first and match the style precisely).
- Consumes: `ProjectDao.observeProjects()` (Task 1).

- [ ] **Step 1: Find the real task creation/edit flow**

Read the actual current detail/edit screen and its ViewModel in full — cite the real file/function names in your report, don't guess.

- [ ] **Step 2: Add a project picker**

A simple selector (following whatever pattern this app already uses for a similar choice — check how `Recurrence` or tag selection is presented, since those are the closest existing precedents for "pick one of several options" in this codebase) showing every `ProjectEntity` by title. New tasks default to the "Inbox" project (`ProjectDao.getByTitle("Inbox")`) if nothing else is picked — matches Vikunja's own default-project behavior. Changing a task's project on an already-synced task should be supported too (calls `setProject`, which marks it dirty so the next sync pushes the change — Vikunja's `updateTask` on a task whose `project_id` changed moves it between projects server-side, confirmed by this session's earlier live testing showing `project_id` is accepted on update... actually re-check: the prior plan's `VikunjaTaskWriteJson` deliberately EXCLUDES `project_id` from the write body, per a real live-tested bug where sending it broke `createTask`. Moving a task between projects via `updateTask`'s body may not work the same way `POST /tasks/{id}` doesn't carry project in the write DTO at all currently. **Investigate this specifically**: check whether Vikunja's real task-move semantics need a different endpoint/mechanism (e.g., some task-management APIs use a dedicated "move" endpoint distinct from a general update) or whether `project_id` genuinely needs to be added back to the write DTO for the update path specifically (but NOT the create path, where it caused the original bug) — this needs live verification against the real server before shipping, don't assume either way.

- [ ] **Step 3: `TaskRepository.setProject`**

Per the design above — mirror `setDeadline`'s exact shape (`markDirtyIfChanged`-style, or unconditional dirty-mark if project changes aren't cheaply comparable — match whichever pattern fits given what you find in Step 1).

- [ ] **Step 4: On-device verification**

Build/install, create a new task, confirm it defaults to Inbox, change its project, confirm the picker UI works. Full live-sync verification (does a project change actually move the task on the real server) belongs in Task 6's final end-to-end pass — don't attempt live-server testing in this task unless you're specifically investigating the Step 2 question above, in which case use the real credentials already known from this session (ask the controller if you don't have them handed to you directly in your dispatch).

- [ ] **Step 5: Test suite, commit**

```bash
./gradlew test
git add -A
git commit -m "Add project picker to task create/edit, defaulting new tasks to Inbox"
git push
```

---

### Task 4: New list views — Overview, Upcoming (deadline-based), My Open Tasks, Inbox

**Files:**
- Create or heavily adapt from `TaskListScreen.kt`/`TaskListViewModel.kt` — your call whether to parameterize the existing shared screen further or create focused new ones; whichever keeps the code cleanest given what you find when you read the current file.

**Interfaces:**
- Produces: four working list views backed by Task 1's new DAO queries.
- Consumes: `TaskDao.observeDueToday`/`observeDueSoon`/`observeAllOpen`/`observeByVikunjaProjectId` (Task 1), `ProjectDao.getByTitle` (Task 1, for Inbox).

- [ ] **Step 1: Extend or replace `TaskListViewModel`**

The existing `TaskListViewModel` takes a `TaskView` enum and calls `repo.observeView(view, tagFilter)`. Decide (read the current file first): extend `TaskView`/`observeView` to cover the four new cases, or introduce a more general "list source" abstraction. Prefer the smallest change that keeps `TaskRow`/`TaskListScreen`'s existing rendering code fully reusable — these new views are just different underlying queries, not different visual designs.

- [ ] **Step 2: Wire each new view's query**

Overview → `observeDueToday`. Upcoming → `observeDueSoon`, grouped by date (reuse the exact grouping UI the old Upcoming view already has, just re-keyed to `deadline` instead of `scheduledDate`). My Open Tasks → `observeAllOpen`. Inbox → `observeByVikunjaProjectId(inboxProject.vikunjaProjectId)`, looked up via `ProjectDao.getByTitle("Inbox")` (handle the "Inbox" project not existing yet — e.g. before the first sync ever completes — with a sensible empty state, not a crash).

- [ ] **Step 3: Completed/incomplete filter toggle**

Since there's no dedicated Logbook tab anymore, every one of these views (and Task 5's Projects/Labels drill-in views) needs a way to see completed tasks too, not just the default incomplete-only queries from Task 1. Add a simple toggle (a segmented control or two chips, following whatever selection-UI pattern this codebase already uses — check `TagFilterBar` for a close precedent) that switches between "Open" and "Completed" — this likely means Task 1's queries need an `includeCompleted`/`onlyCompleted` parameter variant, or a second query per view. Use your judgment on the cleanest implementation; if Task 1's queries need adjusting to support this cleanly, that's fine — note the change in your report.

- [ ] **Step 4: On-device verification**

Build/install fresh (schema unwired-migration caveat still applies if this is still within the same overall app version bump — check whether Task 1's migration has been wired yet; if not, keep using fresh-install, not `-r`). Create tasks with various deadlines (today, future, none), various projects, some completed — confirm each of the four views shows the right set, confirm the completed/incomplete toggle works on at least two of them.

- [ ] **Step 5: Test suite, commit**

```bash
./gradlew test
git add -A
git commit -m "Add Overview, Upcoming (deadline-based), My Open Tasks, and Inbox list views"
git push
```

---

### Task 5: Projects tab and Labels tab

**Files:**
- Create: a projects-list screen + wiring to drill into a project's task list (Task 4's `observeByVikunjaProjectId`, generalized to accept any project, not just Inbox)
- Create: a labels-list screen + wiring to drill into a label's task list (reuse the existing tag infrastructure — `TagDao`, `TagFilterBar`'s existing filter-by-tag query path already used inside `TaskListScreen`)

**Interfaces:**
- Produces: two new top-level screens, each navigating to a filtered task list on tap.
- Consumes: `ProjectDao.observeProjects()` (Task 1), `TagDao.observeTags()` (existing), `TaskDao.observeByVikunjaProjectId` (Task 1/4), existing tag-filtered task query (find the real existing one — `TaskRepository.observeView` with a tag filter already does this, per `TaskListViewModel.tasks`'s `tagFilter.flatMapLatest { repo.observeView(view, it) }`).

- [ ] **Step 1: Projects list screen**

A simple `LazyColumnMMD` of `ProjectEntity` rows (title, maybe `hexColor` as a small dot — check how the Vikunja web sidebar showed "RV" with an orange dot, matching `hex_color` from the real API data this session already captured). Tapping a project navigates to a task list filtered to that project (reuse Task 4's `observeByVikunjaProjectId` view, generalized to take a project id parameter instead of being hardcoded to Inbox).

- [ ] **Step 2: Labels list screen**

Same shape, over `TagDao.observeTags()`. Tapping a label navigates to a task list filtered to that tag — reuse the exact existing tag-filter query path (`TaskListViewModel`'s `tagFilter`/`observeView` mechanism), don't build a new one.

- [ ] **Step 3: On-device verification**

Build/install, confirm Projects tab lists real projects (from Task 2's synced `ProjectEntity` rows — you'll need real or test data synced first; ask the controller for real credentials if you want to test against the live server, or just create local test tasks/tags to verify the UI mechanics without needing a live sync), confirm tapping in filters correctly, confirm Labels tab does the same for tags.

- [ ] **Step 4: Test suite, commit**

```bash
./gradlew test
git add -A
git commit -m "Add Projects and Labels top-level tabs, each drilling into a filtered task list"
git push
```

---

### Task 6: New 6-tab navigation shell, migration wiring, full end-to-end verification

**Files:**
- Modify: `app/src/main/java/com/erdman/erdtoday/ui/nav/AppShell.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/ui/nav/Routes.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/di/AppContainer.kt`

**Interfaces:**
- Produces: the final, real 6-tab bottom navigation (Overview/Upcoming/Projects/Labels/My Open Tasks/Inbox), replacing the old 4-tab Today/Upcoming/Anytime/Logbook entirely. `MIGRATION_4_5` wired into the Room builder alongside the existing three.

- [ ] **Step 1: Rewrite `Routes.kt`**

Replace `TODAY`/`ANYTIME`/`LOGBOOK` route constants with `OVERVIEW`/`PROJECTS`/`LABELS`/`MY_OPEN_TASKS`/`INBOX` (keep `UPCOMING`, it's unchanged in concept). Update `TAB_ROUTES` to the new set of 6. Add routes for the Projects/Labels drill-in screens (a project's or label's filtered task list needs its own route with a parameter, e.g. `"project/{projectId}"`/`"label/{tagId}"`) and Task 5's list/detail screens.

- [ ] **Step 2: Rewrite `AppShell`'s bottom nav and `NavHost`**

Six `TabItem`s (reuse the existing `TabItem` composable unchanged — it's already generic over route/label/icon). Pick reasonable Material icons for Overview/Projects/Labels/My Open Tasks/Inbox (check `androidx.compose.material.icons.filled` for sensible matches — `Dashboard` or `Home` for Overview, `Folder`/`Layers` for Projects, `Label` for Labels, `Checklist`/`TaskAlt` for My Open Tasks, `Inbox`/`MoveToInbox` for Inbox — use your judgment, these are cosmetic). `startDestination` changes from `Routes.TODAY` to `Routes.OVERVIEW`. Wire in Task 4/5's new screens.

- [ ] **Step 3: Wire `MIGRATION_4_5` into `AppContainer.kt`**

```kotlin
.addMigrations(TodayDatabase.MIGRATION_1_2, TodayDatabase.MIGRATION_2_3, TodayDatabase.MIGRATION_3_4, TodayDatabase.MIGRATION_4_5)
```

- [ ] **Step 4: Full build, fresh install**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean
./gradlew :app:assembleDebug -q
./gradlew :app:assembleRelease -q
adb -s MK20250402537 uninstall com.erdman.erdtoday.debug
adb -s MK20250402537 install app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Full end-to-end live verification**

Ask the controller for real Vikunja credentials if not already provided directly in your dispatch (same account used throughout this session, `http://192.168.1.213:3456`). Connect, trigger a sync (Sync now, already built), and confirm:
- All 6 tabs render without crashing.
- Overview shows a task with today's deadline; Upcoming shows one with a future deadline.
- My Open Tasks shows every incomplete task regardless of project.
- Inbox shows tasks in the real "Inbox" project.
- Projects tab lists the real projects (Inbox, RV, ErdToday, etc. — whatever exists on the account) and drilling into one shows the right tasks.
- Labels tab lists real labels and drilling into one filters correctly.
- Create a new task on-device, confirm it defaults into Inbox, change its project, sync, confirm the move actually happened server-side (checking directly via the Vikunja API/web UI) — this closes out Task 3's investigation question about how project-reassignment needs to be sent to the server.
- Completed/incomplete toggle works somewhere.
- Full logcat crash check throughout.

- [ ] **Step 6: Test suite, commit**

```bash
./gradlew test
git add -A
git commit -m "Wire 6-tab Vikunja-shaped navigation (Overview/Upcoming/Projects/Labels/My Open Tasks/Inbox), replacing the old 4-tab shell"
git push
```

---
