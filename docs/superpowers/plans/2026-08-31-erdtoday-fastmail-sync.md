# ErdToday Fastmail CalDAV Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two-way Fastmail CalDAV (VTODO) sync to ErdToday (a fork of
Yvdriel/mudita-today), syncing title/scheduled date/deadline/completion/tags
while leaving notes, checklist, recurrence, and reminders local-only.

**Architecture:** A `dav4jvm`-based CalDAV client (independent of DAVx5)
talks directly to a dedicated Fastmail task collection. A Room migration
adds sync-tracking fields to the existing `TaskEntity`; `TaskRepository`'s
existing mutation methods mark rows dirty; a WorkManager-driven sync engine
pushes dirty/pending-delete rows and pulls remote changes via an RFC 6578
`sync-collection` REPORT, with a simple "local wins until it collides, then
server wins" conflict rule.

**Tech Stack:** Kotlin, Jetpack Compose, `com.mudita:MMD:1.0.0`, Room 2.6.1,
Preferences DataStore, `androidx.security.crypto` (EncryptedSharedPreferences),
`com.github.bitfireAT:dav4jvm:4.0.1` (Ktor-based, JitPack-distributed),
WorkManager.

**Spec:** `docs/superpowers/specs/2026-08-31-erdtoday-fastmail-sync-design.md`

## Global Constraints

- The debug build variant has `applicationIdSuffix = ".debug"` (a
  pre-existing setting from upstream, confirmed on-device during Task
  1) — the actual installed package for every debug build in this plan
  is `com.erdman.erdtoday.debug`, not the bare `com.erdman.erdtoday`.
  Every `adb` command that targets an installed package (`shell monkey
  -p ...`, `uninstall ...`) must use the `.debug`-suffixed name; `adb
  install <path-to-apk>` itself is unaffected (it takes a file path,
  not a package name). Kotlin source code (`package
  com.erdman.erdtoday...`, imports) is unaffected too — the suffix is
  purely a Gradle/build-output concern.
- Fields that sync: `title`, `scheduledDate`, `deadline`, `completed`,
  tags. Fields that do NOT sync, ever, in this plan: `notes`, checklist,
  `recurrence`, `reminderTime`, `sortOrder`.
- `applicationId`/package: `com.erdman.erdtoday` (renamed from
  `com.mosquishe.today` in Task 1 — every later task's code is written
  directly in the new package, never the old one).
- Credentials (Fastmail email + app password) go in
  `EncryptedSharedPreferences` via a new `CredentialsManager`, never
  `SettingsStore`/DataStore (that's for non-secret UI settings only).
- `dav4jvm` is Ktor-based (not OkHttp) as of the version this plan pins —
  confirmed by direct source inspection, not assumed. Its `HttpClient` must
  be configured with `followRedirects = false` (dav4jvm handles redirects
  itself).
- ETags passed in an `If-Match` header must be wrapped with
  `QuotedStringUtils.asQuotedString(etag)` — a raw ETag string is not a
  valid header value.
- Every task's on-device verification step (where the task has one) must
  actually be exercised on the Mudita Kompakt — a build succeeding is not
  sufficient evidence for anything touching the UI or a live CalDAV round
  trip against the real Fastmail account.

---

### Task 1: Rebrand the fork

**Files:**
- Modify: every file under
  `app/src/main/java/com/mosquishe/today/` (28+ files — full package rename)
- Modify: `app/build.gradle.kts` (`namespace`, `applicationId`)
- Modify: `app/src/main/AndroidManifest.xml` if it references the package
  explicitly anywhere
- Modify: `app/src/main/res/values/strings.xml` (`app_name`)
- Modify: `README.md` (package name mentioned in its own docs)

**Interfaces:**
- Produces: an identical, behavior-preserving app at package
  `com.erdman.erdtoday`, app name "ErdToday" — every later task's new code
  is written directly against this package, never `com.mosquishe.today`.

This is a pure rename — no behavior change, no logic change. Follow the
same approach already proven in this session's KofC6650Kompakt fork:

- [ ] **Step 1: Move the package directory and rewrite every package
  declaration/import**

```bash
cd ~/Projects/ErdToday
mkdir -p app/src/main/java/com/erdman/erdtoday
git mv app/src/main/java/com/mosquishe/today/* app/src/main/java/com/erdman/erdtoday/ 2>/dev/null \
  || mv app/src/main/java/com/mosquishe/today/* app/src/main/java/com/erdman/erdtoday/
rmdir app/src/main/java/com/mosquishe/today 2>/dev/null

# Do the same for the test source sets if they mirror the package structure
find app/src -type d -path "*mosquishe/today*"

grep -rl 'com\.mosquishe\.today' app/src --include='*.kt' | \
  xargs sed -i '' -E 's/com\.mosquishe\.today([^a-zA-Z0-9_]|$)/com.erdman.erdtoday\1/g'
```

The `([^a-zA-Z0-9_]|$)` pattern (not a literal `\b`) matters — this
codebase's own macOS `sed` doesn't support `\b` word-boundary syntax (a
prior task in this session's history found the same thing while forking
KofC6650); this alternation-based pattern is the portable equivalent and
is idempotent (safe to re-run without double-applying).

- [ ] **Step 2: Update `app/build.gradle.kts`**

Find the `android { namespace = "com.mosquishe.today" ... defaultConfig {
applicationId = "com.mosquishe.today" ... } }` block and change both
values to `"com.erdman.erdtoday"`.

- [ ] **Step 3: Update the app display name**

In `app/src/main/res/values/strings.xml`, find the `app_name` string
(currently "Today" or similar) and change it to `"ErdToday"`.

- [ ] **Step 4: Check the manifest and README for any other literal
  package references**

```bash
grep -n "com.mosquishe.today" app/src/main/AndroidManifest.xml README.md
```

Fix any matches found (the manifest usually doesn't need one if it uses
`${applicationId}` placeholders, but confirm rather than assume).

- [ ] **Step 5: Build and install, verify identical behavior to upstream**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug -q
adb -s MK20250402537 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s MK20250402537 shell monkey -p com.erdman.erdtoday.debug -c android.intent.category.LAUNCHER 1
```

Expected: builds clean, installs, launches to the Today view exactly as
`com.mosquishe.today` did before the rename — same four tabs, same tasks
(if any existed from prior testing), same everything. This task changes
nothing user-visible.

- [ ] **Step 6: Run the existing unit test suite to confirm nothing broke**

```bash
./gradlew test
```

Expected: same pass/fail state as before the rename (the existing
`domain/` tests for `DateLogic`, `Completion`, `Recurrence` etc. should
all still pass — a pure rename shouldn't touch their behavior).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Rebrand fork as ErdToday (com.erdman.erdtoday)"
git push
```

---

### Task 2: Sync-tracking schema and dirty tracking

**Files:**
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/Entities.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/TodayDatabase.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/Daos.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/repo/TaskRepository.kt`
- Test: `app/src/test/java/com/erdman/erdtoday/data/repo/TaskRepositoryDirtyTrackingTest.kt` (new)

**Interfaces:**
- Produces: `TaskEntity` gains `caldavUid: String?`, `caldavHref: String?`,
  `caldavEtag: String?`, `syncDirty: Boolean` (default `true`),
  `syncPendingDelete: Boolean` (default `false`). New DAO methods
  `TaskDao.tasksNeedingSync(): List<TaskEntity>`,
  `TaskDao.getTaskByCaldavUid(uid: String): TaskEntity?`, and
  `TaskDao.getTaskByCaldavHref(href: String): TaskEntity?`. A new
  `SyncStateEntity`/`SyncStateDao` (single row, holds the CalDAV
  sync-token between cycles). Existing
  `TaskRepository` methods `setTitle`, `setScheduledDate`, `setDeadline`,
  `setTaskCompleted`, `setTaskTags` set `syncDirty = true` when they
  change a synced field; `captureAndDelete`/`deleteTask` set
  `syncPendingDelete = true` instead of deleting outright when the task
  has a `caldavUid`.
- Consumes: nothing new (this task only touches already-existing files).

- [ ] **Step 1: Add the five fields to `TaskEntity`**

In `Entities.kt`, add to the `TaskEntity` data class (after `sortOrder`):

```kotlin
    /** CalDAV UID (RFC 5545 UID) once this task has been pushed to Fastmail; null until then. */
    val caldavUid: String? = null,
    /** This task's resource path on the Fastmail CalDAV server; null until first successful push. */
    val caldavHref: String? = null,
    /** The resource's ETag as of the last successful sync; null for a not-yet-pushed task. */
    val caldavEtag: String? = null,
    /** True if a synced field changed locally since the last successful push. */
    val syncDirty: Boolean = true,
    /** True if deleted locally but the server DELETE hasn't succeeded yet. */
    val syncPendingDelete: Boolean = false,
```

`syncDirty` defaults to `true` deliberately — every pre-existing task (from
before this migration) should sync up the first time sync runs after the
app updates, not be silently skipped forever.

- [ ] **Step 2: Add the Room migration**

In `TodayDatabase.kt`, bump `version = 2` to `version = 3`, and add a new
migration following the exact shape of the existing `MIGRATION_1_2`:

```kotlin
        /** v3 adds CalDAV sync-tracking columns to tasks. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN caldavUid TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN caldavHref TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN caldavEtag TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN syncPendingDelete INTEGER NOT NULL DEFAULT 0")
            }
        }
```

Do NOT wire `MIGRATION_2_3` into the `Room.databaseBuilder(...)` call in
`AppContainer.kt` yet in this task — that file already has
`.addMigrations(TodayDatabase.MIGRATION_1_2)`; leave that line alone here
and let Task 7 (which touches `AppContainer.kt` for other reasons anyway)
add `TodayDatabase.MIGRATION_2_3` to it. This keeps this task's diff
scoped to schema + repository logic only.

- [ ] **Step 3: Add the two new DAO methods**

In `Daos.kt`, inside the `TaskDao` interface, add:

```kotlin
    /** Rows that need a sync push: locally dirty, or deleted-pending-server-confirmation. */
    @Query("SELECT * FROM tasks WHERE syncDirty = 1 OR syncPendingDelete = 1")
    suspend fun tasksNeedingSync(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE caldavUid = :uid LIMIT 1")
    suspend fun getTaskByCaldavUid(uid: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE caldavHref = :href LIMIT 1")
    suspend fun getTaskByCaldavHref(href: String): TaskEntity?
```

(`getTaskByCaldavUid` isn't used until Task 6, but add it here alongside
its sibling — both are simple, obviously-related lookups on the same
new columns.)

- [ ] **Step 3b: Add a one-row table for the stored CalDAV sync-token**

The sync engine (Task 6) needs to persist the RFC 6578 sync-token
between cycles. Rather than introducing DataStore for one string (this
isn't a user-facing setting, so it doesn't belong in `SettingsStore`
either), add a tiny dedicated Room entity — consistent with this
codebase already using Room for everything else it persists:

In `Entities.kt`:
```kotlin
/** Single-row table holding the CalDAV sync-collection token between sync cycles. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0, // always 0 -- this app has exactly one CalDAV collection
    val syncToken: String? = null,
)
```

In `Daos.kt`, a new small DAO:
```kotlin
@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(state: SyncStateEntity)
}
```

In `TodayDatabase.kt`: add `SyncStateEntity::class` to the `@Database`
annotation's `entities` array (alongside the existing four), and add
`abstract fun syncStateDao(): SyncStateDao` next to the existing
`taskDao()`/`tagDao()` abstract methods. `MIGRATION_2_3` (Step 2 above)
needs one more line to create the new table, since Room migrations must
account for every schema change in one version bump:
```kotlin
db.execSQL("CREATE TABLE IF NOT EXISTS sync_state (id INTEGER NOT NULL PRIMARY KEY, syncToken TEXT)")
```
Add this as the sixth `execSQL` call inside `MIGRATION_2_3`, alongside the
five `ALTER TABLE` calls already specified there.

- [ ] **Step 4: Add dirty tracking to `TaskRepository`**

In `TaskRepository.kt`, add a private helper:

```kotlin
    /** Marks a row dirty (needing a sync push) only if a *synced* field actually changed. */
    private suspend fun markDirtyIfChanged(before: TaskEntity, after: TaskEntity) {
        val changed = before.title != after.title ||
            before.scheduledDate != after.scheduledDate ||
            before.deadline != after.deadline ||
            before.completed != after.completed
        if (changed && !after.syncDirty) {
            taskDao.updateTask(after.copy(syncDirty = true))
        }
    }
```

Then call it from the end of each of these four existing methods (after
the existing `taskDao.updateTask(...)` call in each — read each method's
current body first, since the exact call site varies):

- `setTitle` (currently: `edit(taskId) { it.copy(title = title) }`) — the
  `edit` helper needs to change to also call `markDirtyIfChanged`. Since
  `edit` is used by `setTitle` only (check this is still true — `setNotes`
  and `setDeadline`/`setRecurrence` also currently use `edit`, per the
  existing code), the cleanest fix is: don't change `edit` itself (that
  would wrongly mark `setNotes`/`setRecurrence` dirty too); instead give
  `setTitle` and `setDeadline` their own explicit bodies that call
  `markDirtyIfChanged`, and leave `setNotes`/`setRecurrence` using the
  unchanged `edit` helper as before:

```kotlin
    suspend fun setTitle(taskId: Long, title: String) {
        val before = taskDao.getTaskEntity(taskId) ?: return
        val after = before.copy(title = title)
        taskDao.updateTask(after)
        markDirtyIfChanged(before, after)
    }

    suspend fun setDeadline(taskId: Long, date: LocalDate?) {
        val before = taskDao.getTaskEntity(taskId) ?: return
        val after = before.copy(deadline = date)
        taskDao.updateTask(after)
        markDirtyIfChanged(before, after)
    }
```

(`setNotes` and `setRecurrence` keep using the existing `edit(taskId) { ... }`
one-liner, unmodified — they must NOT call `markDirtyIfChanged`, since
neither field syncs.)

- `setScheduledDate` — currently builds `updated` itself and calls
  `taskDao.updateTask(updated)` then `syncReminder(updated)`. Capture the
  pre-edit entity as `t` (already done, it's the existing local var) and
  add `markDirtyIfChanged(t, updated)` right after `syncReminder(updated)`.

- `applyCompletion` (called by `setTaskCompleted`, and internally by
  `recomputeCompletion`) — currently builds `updated` and calls
  `taskDao.updateTask(updated)`. Add `markDirtyIfChanged(task, updated)`
  right after that call, before `syncReminder(updated)`. This correctly
  also covers completion changes that come from checklist auto-complete
  (`recomputeCompletion` → `applyCompletion`), which is desired — an
  auto-completed task should sync its completion too.

- `setTaskTags` — currently:
  ```kotlin
  suspend fun setTaskTags(taskId: Long, tagIds: List<Long>) {
      taskDao.clearTaskTags(taskId)
      tagIds.forEach { taskDao.addTagToTask(TaskTagCrossRef(taskId, it)) }
  }
  ```
  Add an unconditional dirty-mark at the end (tags live in a join table,
  not on `TaskEntity`, so there's no cheap before/after `TaskEntity`
  comparison available — see the spec's reasoning for why "always dirty
  on any call" is the accepted simplification here):
  ```kotlin
  suspend fun setTaskTags(taskId: Long, tagIds: List<Long>) {
      taskDao.clearTaskTags(taskId)
      tagIds.forEach { taskDao.addTagToTask(TaskTagCrossRef(taskId, it)) }
      val t = taskDao.getTaskEntity(taskId) ?: return
      if (!t.syncDirty) taskDao.updateTask(t.copy(syncDirty = true))
  }
  ```

- `createTask` — already always creates a task with `syncDirty = true`
  by virtue of `TaskEntity`'s new default (Step 1) — no change needed to
  `createTask` itself, but double check its `TaskEntity(...)` constructor
  call doesn't explicitly pass `syncDirty = false` anywhere (it shouldn't;
  confirm by reading the current method body).

- [ ] **Step 5: Change delete to soft-delete when previously synced**

Replace `deleteTask` and `captureAndDelete`'s unconditional
`taskDao.deleteTaskById(taskId)` calls:

```kotlin
    suspend fun deleteTask(taskId: Long) {
        reminderScheduler.cancel(taskId)
        val t = taskDao.getTaskEntity(taskId)
        if (t?.caldavUid != null) {
            taskDao.updateTask(t.copy(syncPendingDelete = true))
        } else {
            taskDao.deleteTaskById(taskId)
        }
    }

    suspend fun captureAndDelete(taskId: Long): TaskWithDetails? {
        val snapshot = taskDao.getTask(taskId) ?: return null
        reminderScheduler.cancel(taskId)
        if (snapshot.task.caldavUid != null) {
            taskDao.updateTask(snapshot.task.copy(syncPendingDelete = true))
        } else {
            taskDao.deleteTaskById(taskId)
        }
        return snapshot
    }
```

Leave `restore(snapshot)` as-is for now — restoring a soft-deleted task
(one with `syncPendingDelete = true`) is an edge case (undo-after-delete
racing a sync cycle) explicitly out of scope for this task; note it as a
known gap in this task's self-review rather than trying to solve it here.

- [ ] **Step 6: Write the dirty-tracking test**

Create `TaskRepositoryDirtyTrackingTest.kt` following the existing test
suite's conventions for testing `TaskRepository` (check
`app/src/test/java/com/erdman/erdtoday/` for an existing
`TaskRepository`-adjacent test file to match its exact setup/fixture
style — e.g. an in-memory Room database or a fake DAO). Test cases:

```kotlin
// setTitle marks dirty
// setDeadline marks dirty
// setScheduledDate marks dirty
// setTaskCompleted marks dirty
// setTaskTags marks dirty
// setNotes does NOT mark dirty
// setRecurrence does NOT mark dirty
// addChecklistItem / setChecklistItemDone do NOT mark dirty
// setReminder does NOT mark dirty
// deleteTask on a task with caldavUid=null deletes it for real (row gone)
// deleteTask on a task with caldavUid!=null sets syncPendingDelete=true, row still present
```

- [ ] **Step 7: Run the test suite and verify**

```bash
./gradlew test
```

Expected: all new tests pass, plus every pre-existing test still passes
(no regression to untouched behavior).

- [ ] **Step 8: Build and install, spot-check no regression**

**Uninstall first, do not reinstall-in-place (`-r`) over Task 1's build.**
This task bumps `@Database`'s `version` to 3, but deliberately defers
wiring `MIGRATION_2_3` into the builder until Task 7 (see Step 2's note).
If Task 1's already-installed v2-schema app is upgraded in place, Room
will require a 2→3 migration path that doesn't exist yet and crash on
first launch. Installing fresh sidesteps this entirely — Room just
creates a new v3 database directly, no migration needed:

```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 uninstall com.erdman.erdtoday.debug
adb -s MK20250402537 install app/build/outputs/apk/debug/app-debug.apk
```

(Tasks 4 and 5's later on-device steps can go back to plain
`install -r`, since nothing changes `@Database`'s version again until
Task 7 — only this task's install needs the uninstall-first treatment,
because it's the one that changes the version number itself.)

On-device: create a task, edit its title, set a deadline, complete it,
add a tag, edit its notes, add a checklist item — confirm all of this
still works exactly as before (this task adds tracking fields the UI
never reads yet, so nothing should look different).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add CalDAV sync-tracking schema and dirty tracking"
git push
```

---

### Task 3: VTodoMapper (VTODO ↔ TaskEntity, pure functions)

**Files:**
- Create: `app/src/main/java/com/erdman/erdtoday/caldav/VTodoMapper.kt`
- Test: `app/src/test/java/com/erdman/erdtoday/caldav/VTodoMapperTest.kt`

**Interfaces:**
- Produces:
  `VTodoMapper.toVTodoText(task: TaskEntity, tagNames: List<String>): String`
  and
  `VTodoMapper.parseVTodo(icalText: String): ParsedVTodo` where
  `ParsedVTodo` is a new data class:
  ```kotlin
  data class ParsedVTodo(
      val uid: String,
      val title: String,
      val scheduledDate: LocalDate?,
      val deadline: LocalDate?,
      val completed: Boolean,
      val completedAt: Instant?,
      val tagNames: List<String>,
  )
  ```
- Consumes: nothing (pure, no DB/network access — this is exactly the
  "unit-testable, no network" piece the spec calls out).

This task has no on-device step — it's fully covered by unit tests.

- [ ] **Step 1: Write `toVTodoText`**

RFC 5545 iCalendar text, built directly (no XML/iCal library dependency
needed for this direction — it's simple enough to build as a string, and
avoids pulling in a whole iCal parsing library for output-only use):

```kotlin
package com.erdman.erdtoday.caldav

import com.erdman.erdtoday.data.local.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE // yyyyMMdd, per RFC 5545 DATE value type
private val UTC_STAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

object VTodoMapper {

    fun toVTodoText(task: TaskEntity, tagNames: List<String>): String {
        require(task.caldavUid != null) { "toVTodoText requires a task with caldavUid already assigned" }
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//erdman//ErdToday//EN",
            "BEGIN:VTODO",
            "UID:${task.caldavUid}",
            "SUMMARY:${escapeText(task.title)}",
        )
        task.scheduledDate?.let { lines += "DTSTART;VALUE=DATE:${it.format(DATE_FMT)}" }
        task.deadline?.let { lines += "DUE;VALUE=DATE:${it.format(DATE_FMT)}" }
        lines += "STATUS:${if (task.completed) "COMPLETED" else "NEEDS-ACTION"}"
        if (task.completed) {
            val completedInstant = task.completedAt ?: Instant.now()
            lines += "COMPLETED:${UTC_STAMP_FMT.format(completedInstant.atZone(java.time.ZoneOffset.UTC))}"
        }
        if (tagNames.isNotEmpty()) {
            lines += "CATEGORIES:${tagNames.joinToString(",") { escapeText(it) }}"
        }
        lines += listOf("END:VTODO", "END:VCALENDAR")
        return lines.joinToString("\r\n") // RFC 5545 requires CRLF line endings
    }

    /** RFC 5545 §3.3.11 TEXT escaping: backslash, semicolon, comma, and newline. */
    private fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
}
```

- [ ] **Step 2: Write `parseVTodo`**

A small hand-rolled line parser is sufficient here (no need for a full
iCal parser dependency) — VTODO from a real CalDAV server is
well-formed, and this only needs to read the six properties this app
actually uses. Handle RFC 5545 line folding (a continuation line starts
with a single space or tab) and CRLF/LF line endings:

```kotlin
data class ParsedVTodo(
    val uid: String,
    val title: String,
    val scheduledDate: LocalDate?,
    val deadline: LocalDate?,
    val completed: Boolean,
    val completedAt: Instant?,
    val tagNames: List<String>,
)

fun parseVTodo(icalText: String): ParsedVTodo {
    // Unfold: a line starting with a space/tab is a continuation of the previous line.
    val unfolded = icalText.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "")
    var uid: String? = null
    var title = ""
    var scheduledDate: LocalDate? = null
    var deadline: LocalDate? = null
    var status = "NEEDS-ACTION"
    var completedAt: Instant? = null
    var tagNames: List<String> = emptyList()

    for (rawLine in unfolded.lines()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) continue
        val nameAndParams = line.substring(0, colonIdx)
        val value = unescapeText(line.substring(colonIdx + 1))
        val name = nameAndParams.substringBefore(';')
        when (name) {
            "UID" -> uid = value
            "SUMMARY" -> title = value
            "DTSTART" -> scheduledDate = parseDateValue(value)
            "DUE" -> deadline = parseDateValue(value)
            "STATUS" -> status = value
            "COMPLETED" -> completedAt = parseUtcStamp(value)
            "CATEGORIES" -> tagNames = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    return ParsedVTodo(
        uid = requireNotNull(uid) { "VTODO has no UID" },
        title = title,
        scheduledDate = scheduledDate,
        deadline = deadline,
        completed = status == "COMPLETED",
        completedAt = completedAt,
        tagNames = tagNames,
    )
}

private fun parseDateValue(value: String): LocalDate =
    LocalDate.parse(value.take(8), DATE_FMT) // tolerate a trailing time component if a server sends DATE-TIME

private fun parseUtcStamp(value: String): Instant =
    java.time.LocalDateTime.parse(value, UTC_STAMP_FMT).toInstant(java.time.ZoneOffset.UTC)

private fun unescapeText(s: String): String = s
    .replace("\\n", "\n").replace("\\N", "\n")
    .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
```

(Add these as members of the `VTodoMapper` object alongside `toVTodoText`,
not as free functions — the brief shows them split out for readability
here, but they belong in the same `object VTodoMapper { ... }` block.)

- [ ] **Step 3: Write the round-trip tests**

```kotlin
class VTodoMapperTest {
    @Test fun `round-trips a task with all fields set`() {
        val task = TaskEntity(
            id = 1, title = "Buy milk", caldavUid = "abc-123",
            scheduledDate = LocalDate.of(2026, 9, 1),
            deadline = LocalDate.of(2026, 9, 3),
            completed = true, completedAt = Instant.parse("2026-08-31T12:00:00Z"),
        )
        val text = VTodoMapper.toVTodoText(task, tagNames = listOf("errand", "home"))
        val parsed = VTodoMapper.parseVTodo(text)

        assertEquals("abc-123", parsed.uid)
        assertEquals("Buy milk", parsed.title)
        assertEquals(LocalDate.of(2026, 9, 1), parsed.scheduledDate)
        assertEquals(LocalDate.of(2026, 9, 3), parsed.deadline)
        assertTrue(parsed.completed)
        assertEquals(Instant.parse("2026-08-31T12:00:00Z"), parsed.completedAt)
        assertEquals(listOf("errand", "home"), parsed.tagNames)
    }

    @Test fun `round-trips a minimal task with no dates, no tags, not completed`() { /* ... */ }

    @Test fun `escapes and unescapes commas, semicolons, and newlines in title and tags`() { /* ... */ }

    @Test fun `handles line-folded input from a real server`() {
        // A SUMMARY long enough that a real CalDAV server would fold it across lines,
        // continuation lines prefixed with a single space, per RFC 5545 §3.1.
        val folded = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\n" +
            "UID:xyz\r\nSUMMARY:This is a very long title that a real server\r\n would fold across multiple lines\r\n" +
            "STATUS:NEEDS-ACTION\r\nEND:VTODO\r\nEND:VCALENDAR"
        val parsed = VTodoMapper.parseVTodo(folded)
        assertEquals("This is a very long title that a real serverwould fold across multiple lines", parsed.title)
    }
}
```

- [ ] **Step 4: Run and verify**

```bash
./gradlew test --tests "com.erdman.erdtoday.caldav.VTodoMapperTest"
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add VTodoMapper: VTODO text <-> TaskEntity round-trip"
git push
```

---

### Task 4: CredentialsManager and account setup UI

**Files:**
- Create: `app/src/main/java/com/erdman/erdtoday/data/credentials/CredentialsManager.kt`
- Create: `app/src/main/java/com/erdman/erdtoday/ui/accountsetup/AccountSetupScreen.kt`
- Create: `app/src/main/java/com/erdman/erdtoday/ui/accountsetup/AccountSetupViewModel.kt`
- Modify: `app/build.gradle.kts` (add `androidx.security:security-crypto` if not
  already a dependency — check first, ErdStream and other sibling apps
  already use it, but this fork may not)
- Modify: navigation (find and read the existing `ui/nav/` `AppShell`/`NavHost`
  setup to learn its real pattern before adding a new destination — cite
  exact file names once found, don't guess)

**Interfaces:**
- Produces: `CredentialsManager(context).credentials: StateFlow<FastmailCredentials?>`,
  `CredentialsManager(context).save(email: String, appPassword: String)`,
  where `FastmailCredentials(val email: String, val appPassword: String)`.
  A Compose screen shown instead of the normal app shell when
  `credentials.value == null`.

- [ ] **Step 1: Check whether `security-crypto` is already a dependency**

```bash
grep -n "security-crypto" app/build.gradle.kts
```

If absent, add it: `implementation("androidx.security:security-crypto:1.1.0-alpha06")`
(the same version ErdStream and the other sibling apps in this session
use — check `~/Projects/ErdStream/app/build.gradle.kts` for the exact
pinned version and match it, rather than picking one independently).

- [ ] **Step 2: Write `CredentialsManager`**

Mirror ErdStream's `CredentialsManager.kt` pattern exactly (same
`MasterKey`/`EncryptedSharedPreferences` setup, same `StateFlow` shape),
adapted for two fields instead of three:

```kotlin
package com.erdman.erdtoday.data.credentials

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FastmailCredentials(
    val email: String,
    val appPassword: String,
)

/** Fastmail account email + app-specific password, in encrypted SharedPreferences. */
class CredentialsManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _credentials = MutableStateFlow(readCredentials())
    val credentials: StateFlow<FastmailCredentials?> = _credentials.asStateFlow()

    private fun readCredentials(): FastmailCredentials? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val appPassword = prefs.getString(KEY_APP_PASSWORD, null) ?: return null
        return FastmailCredentials(email, appPassword)
    }

    fun save(email: String, appPassword: String) {
        val trimmedEmail = email.trim()
        prefs.edit()
            .putString(KEY_EMAIL, trimmedEmail)
            .putString(KEY_APP_PASSWORD, appPassword)
            .apply()
        _credentials.value = FastmailCredentials(trimmedEmail, appPassword)
    }

    fun clear() {
        prefs.edit().clear().apply()
        _credentials.value = null
    }

    companion object {
        private const val PREFS_NAME = "erdtoday_credentials"
        private const val KEY_EMAIL = "fastmail_email"
        private const val KEY_APP_PASSWORD = "fastmail_app_password"
    }
}
```

- [ ] **Step 2b: Wire it into `AppContainer`**

Add `val credentialsManager: CredentialsManager = CredentialsManager(appContext)`
to `AppContainer.kt`, next to the existing `val settings: SettingsStore = ...`
line.

- [ ] **Step 3: Read the existing navigation setup before adding a screen**

Find and read the real nav files:

```bash
find app/src/main/java/com/erdman/erdtoday/ui -iname "*shell*" -o -iname "*nav*"
```

Read whatever `AppShell`/`NavHost`-equivalent file(s) that finds, in
full, to learn the existing pattern (composable routes, how the shell
decides what to show) before writing Step 4 — cite the exact real
function/composable names in your commit and report, don't invent
plausible-sounding ones.

- [ ] **Step 4: Write `AccountSetupViewModel` and `AccountSetupScreen`**

A simple two-field form (email, app password) with a "Connect" button.
Follow this codebase's existing screen patterns exactly (check an
existing simple screen like `SettingsScreen.kt` for the real MMD
component usage — `TextFieldMMD` if it exists in this MMD version, or
whatever this codebase already uses for text input elsewhere; check
before assuming). On submit, call
`appContainer().credentialsManager.save(email, appPassword)` — this task
does NOT need to trigger discovery/collection-creation yet (that's Task
5); saving credentials alone is a complete, testable unit for this task.

- [ ] **Step 5: Show this screen in place of the normal app shell when
  there are no credentials yet**

In whatever top-level composable Step 3 identified as the real entry
point, branch on `appContainer().credentialsManager.credentials.collectAsState()`:
null → `AccountSetupScreen()`, non-null → the existing app shell,
unchanged.

- [ ] **Step 6: Build, install, and verify on-device**

```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s MK20250402537 shell monkey -p com.erdman.erdtoday.debug -c android.intent.category.LAUNCHER 1
```

Expected: first launch (or after clearing app data) shows the account
setup screen instead of the task list. Enter an email and app password,
tap Connect, confirm the app then shows the normal Today view. Force-quit
and relaunch — confirm it goes straight to the Today view (credentials
persisted), not back to setup.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add CredentialsManager and first-run account setup screen"
git push
```

---

### Task 5: Gradle setup, CalDAV discovery, and collection creation

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/erdman/erdtoday/caldav/CalDavDiscovery.kt`

**Interfaces:**
- Produces:
  `CalDavDiscovery.discoverOrCreateTaskCollection(email: String, appPassword: String): Result<Url>`
  — returns the task collection's URL (existing or newly created), or a
  failure.
- Consumes: nothing from earlier tasks directly (this is the CalDAV
  networking foundation Task 6 builds on).

- [ ] **Step 1: Add the JitPack repository and dav4jvm/Ktor dependencies**

In `settings.gradle.kts`, inside `dependencyResolutionManagement { repositories { ... } }`,
add:

```kotlin
maven("https://jitpack.io")
```

In `app/build.gradle.kts`, add:

```kotlin
implementation("com.github.bitfireAT:dav4jvm:4.0.1")
implementation("io.ktor:ktor-client-core:2.3.12")
implementation("io.ktor:ktor-client-okhttp:2.3.12")
implementation("io.ktor:ktor-client-auth:2.3.12")
```

(Ktor's OkHttp engine, not a raw OkHttp dependency — dav4jvm itself talks
to Ktor's `HttpClient` abstraction, not directly to OkHttp, even though
OkHttp is what's underneath.)

- [ ] **Step 2: Confirm the dependency resolves**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug -q
```

Expected: clean build (nothing uses the new dependency yet, this just
confirms it resolves and doesn't conflict with anything already in the
dependency graph — Room, Compose, etc.).

- [ ] **Step 3: Build the authenticated `HttpClient`**

```kotlin
package com.erdman.erdtoday.caldav

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic

fun buildCalDavHttpClient(email: String, appPassword: String): HttpClient =
    HttpClient(OkHttp) {
        followRedirects = false // dav4jvm handles redirects itself -- required, not optional
        install(Auth) {
            basic {
                credentials { BasicAuthCredentials(username = email, password = appPassword) }
                sendWithoutRequest { true } // CalDAV servers expect Basic auth on the first request
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
        }
    }
```

- [ ] **Step 4: Write the discovery + collection-creation logic**

```kotlin
package com.erdman.erdtoday.caldav

import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

object CalDavDiscovery {

    private const val FASTMAIL_CALDAV_HOST = "caldav.fastmail.com"

    suspend fun discoverOrCreateTaskCollection(httpClient: HttpClient): Result<Url> = runCatching {
        val wellKnownUrl = Url("https://$FASTMAIL_CALDAV_HOST/.well-known/caldav")
        val principal = getCurrentUserPrincipal(httpClient, wellKnownUrl)
            ?: error("Could not discover current-user-principal")
        val homeSet = getCalendarHomeSet(httpClient, principal)
            ?: error("Could not discover calendar-home-set")
        findVTodoCollection(httpClient, homeSet)
            ?: createVTodoCollection(httpClient, homeSet)
    }

    private suspend fun getCurrentUserPrincipal(httpClient: HttpClient, url: Url): Url? {
        var principal: Url? = null
        DavResource(httpClient, url).propfind(0, WebDAV.CurrentUserPrincipal)
            .filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .firstOrNull()
            ?.let { response ->
                response[CurrentUserPrincipal::class.java]?.href?.let { href ->
                    principal = response.requestedUrl.resolve(href)
                }
            }
        return principal
    }

    private suspend fun getCalendarHomeSet(httpClient: HttpClient, principal: Url): Url? {
        var homeSet: Url? = null
        DavResource(httpClient, principal).propfind(0, CalDAV.CalendarHomeSet)
            .filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .firstOrNull()
            ?.let { response ->
                response[at.bitfire.dav4jvm.property.caldav.CalendarHomeSet::class.java]
                    ?.hrefs?.firstOrNull()
                    ?.let { href -> homeSet = response.requestedUrl.resolve(href) }
            }
        return homeSet
    }

    private suspend fun findVTodoCollection(httpClient: HttpClient, homeSet: Url): Url? {
        var found: Url? = null
        DavResource(httpClient, homeSet).propfind(
            1,
            WebDAV.ResourceType,
            CalDAV.SupportedCalendarComponentSet,
        ).filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .collect { response ->
                // SupportedCalendarComponentSet is a flat data class with three booleans --
                // no nested Comp list to walk (verified directly against dav4jvm source).
                val supportsVTodo = response[CalDAV.SupportedCalendarComponentSet::class.java]?.supportsTasks
                if (supportsVTodo == true && found == null) found = response.requestedUrl
            }
        return found
    }

    private suspend fun createVTodoCollection(httpClient: HttpClient, homeSet: Url): Url {
        val folderName = UUID.randomUUID().toString()
        val collectionUrl = homeSet.let { Url("$it$folderName/") }
        val xmlBody = buildMkCalendarXml()
        DavResource(httpClient, collectionUrl).mkCol(
            xmlBody = xmlBody,
            methodName = "MKCALENDAR",
        )
        return collectionUrl
    }

    /**
     * Builds the MKCALENDAR request body for a VTODO-only collection named "ErdToday".
     * Adapted from DAVx5's real generateMkColXml (DavCollectionRepository.kt:354-463) --
     * trimmed to ErdToday's fixed case (always a calendar, never an address book; VTODO
     * only, no VEVENT/VJOURNAL; no color, no timezone, no description) rather than carrying
     * over parameters this app will never vary.
     */
    private fun buildMkCalendarXml(): String {
        val writer = java.io.StringWriter()
        val serializer = at.bitfire.dav4jvm.XmlUtils.newSerializer()
        serializer.apply {
            setOutput(writer)
            startDocument("UTF-8", null)
            setPrefix("", WebDAV.NS_WEBDAV)
            setPrefix("CAL", CalDAV.NS_CALDAV)
            startTag(CalDAV.NS_CALDAV, "mkcalendar")
            at.bitfire.dav4jvm.XmlUtils.insertTag(this, WebDAV.Set) {
                at.bitfire.dav4jvm.XmlUtils.insertTag(this, WebDAV.Prop) {
                    at.bitfire.dav4jvm.XmlUtils.insertTag(this, WebDAV.ResourceType) {
                        at.bitfire.dav4jvm.XmlUtils.insertTag(this, WebDAV.Collection)
                        at.bitfire.dav4jvm.XmlUtils.insertTag(this, CalDAV.Calendar)
                    }
                    at.bitfire.dav4jvm.XmlUtils.insertTag(this, WebDAV.DisplayName) { text("ErdToday") }
                    at.bitfire.dav4jvm.XmlUtils.insertTag(this, CalDAV.SupportedCalendarComponentSet) {
                        at.bitfire.dav4jvm.XmlUtils.insertTag(this, CalDAV.Comp) {
                            attribute(null, "name", "VTODO")
                        }
                    }
                }
            }
            endTag(CalDAV.NS_CALDAV, "mkcalendar")
            endDocument()
        }
        return writer.toString()
    }
}
```

Add `import at.bitfire.dav4jvm.XmlUtils.insertTag` alongside this file's
other imports and call it as a plain `insertTag(WebDAV.Set) { ... }`
extension-function call rather than the fully-qualified
`at.bitfire.dav4jvm.XmlUtils.insertTag(this, ...)` form written out above
— the fully-qualified form is there only so this brief's code block
reads unambiguously; write it idiomatically as an extension call in the
real file.

- [ ] **Step 5: On-device verification against the real Fastmail account**

This requires real Fastmail credentials (already entered via Task 4's
screen) and network access. Add a temporary debug entry point (a button
on the account setup screen, or a log call triggered from
`AccountSetupViewModel` right after `save()`) that calls
`CalDavDiscovery.discoverOrCreateTaskCollection` and logs the result.

```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s MK20250402537 logcat -s ErdToday:D # or whatever tag the debug log uses
```

On-device: enter real Fastmail credentials, trigger discovery, confirm
in logcat that a collection URL comes back with no errors. Run it twice
in a row — the second run should find the collection `findVTodoCollection`
just created, not create a second one (confirms the find-before-create
logic actually works, not just the create path).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add CalDAV discovery and task-collection auto-creation"
git push
```

---

### Task 6: Sync engine (push, pull, conflict handling)

**Files:**
- Create: `app/src/main/java/com/erdman/erdtoday/caldav/SyncEngine.kt`

**Interfaces:**
- Consumes: `VTodoMapper` (Task 3), `TaskDao.tasksNeedingSync()`/
  `getTaskByCaldavUid()` (Task 2), the authenticated `HttpClient` and
  collection `Url` (Task 5).
- Produces: `suspend fun SyncEngine.sync(): SyncResult` where
  `SyncResult` is a small sealed result type (`Success`,
  `Failure(reason: String)`) — the single entry point Task 7's
  `SyncWorker` calls.

- [ ] **Step 1: Read dav4jvm's real `DavCollection.reportChanges` and
  PUT/DELETE signatures once more before writing this file**

```bash
grep -n "fun reportChanges" -A 10 /tmp/dav4jvm/**/DavCollection.kt
grep -n "fun put\|fun delete" -A 15 /tmp/dav4jvm/**/DavResource.kt
```

(Re-clone from `github.com/bitfireAT/dav4jvm` if `/tmp/dav4jvm` is gone —
same as Task 5.) Confirm the exact parameter names/order before writing
Step 2 — this plan's earlier research already captured the shape, but
re-verify directly against source rather than trusting a paraphrase two
tasks removed from when it was captured.

- [ ] **Step 2: Write the push phase**

```kotlin
package com.erdman.erdtoday.caldav

import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.QuotedStringUtils
import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import com.erdman.erdtoday.data.local.SyncStateDao
import com.erdman.erdtoday.data.local.SyncStateEntity
import com.erdman.erdtoday.data.local.TaskDao
import com.erdman.erdtoday.data.local.TaskEntity
import com.erdman.erdtoday.data.local.TagDao
import com.erdman.erdtoday.data.local.TagEntity
import com.erdman.erdtoday.data.local.TaskTagCrossRef
import io.ktor.client.HttpClient
import io.ktor.http.Url
import io.ktor.http.headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

sealed class SyncResult {
    object Success : SyncResult()
    data class Failure(val reason: String) : SyncResult()
}

class SyncEngine(
    private val httpClient: HttpClient,
    private val collectionUrl: Url,
    private val taskDao: TaskDao,
    private val tagDao: TagDao,
    private val syncStateDao: SyncStateDao,
) {

    suspend fun sync(): SyncResult = runCatching {
        push()
        pull()
        SyncResult.Success
    }.getOrElse { SyncResult.Failure(it.message ?: it.javaClass.simpleName) }

    private suspend fun push() {
        for (task in taskDao.tasksNeedingSync()) {
            when {
                task.syncPendingDelete -> pushDelete(task)
                task.syncDirty -> pushUpsert(task)
            }
        }
    }

    private suspend fun pushDelete(task: TaskEntity) {
        val href = task.caldavHref
        if (href == null) {
            // Never actually pushed to the server -- nothing to delete remotely, just remove locally.
            taskDao.deleteTaskById(task.id)
            return
        }
        try {
            DavResource(httpClient, Url(href)).delete(
                additionalHeaders = headers {
                    task.caldavEtag?.let { append(HttpHeaders.IfMatch, QuotedStringUtils.asQuotedString(it)) }
                },
            )
            taskDao.deleteTaskById(task.id)
        } catch (e: at.bitfire.dav4jvm.ktor.exception.NotFoundException) {
            taskDao.deleteTaskById(task.id) // already gone server-side, same outcome
        }
        // Any other exception: swallow it here (sync() catches at the top level and reports
        // Failure for the whole cycle), leaving syncPendingDelete=true for a retry next cycle.
    }

    private suspend fun pushUpsert(task: TaskEntity) {
        val uid = task.caldavUid ?: UUID.randomUUID().toString()
        val href = task.caldavHref ?: "$collectionUrl$uid.ics"
        // TaskDao.tagIdsFor(taskId) (existing method, Daos.kt:68) returns this task's tag IDs;
        // TagDao.getById(id) (existing method, Daos.kt:76) resolves each to its TagEntity.
        val tagNames = taskDao.tagIdsFor(task.id).mapNotNull { tagDao.getById(it)?.name }
        val body = VTodoMapper.toVTodoText(task.copy(caldavUid = uid), tagNames)
        try {
            DavResource(httpClient, Url(href)).put(
                content = body,
                additionalHeaders = headers {
                    task.caldavEtag?.let { append(HttpHeaders.IfMatch, QuotedStringUtils.asQuotedString(it)) }
                },
            ) { response ->
                val newEtag = response.headers[HttpHeaders.ETag]
                taskDao.updateTask(
                    task.copy(
                        caldavUid = uid, caldavHref = href, caldavEtag = newEtag,
                        syncDirty = false,
                    ),
                )
            }
        } catch (e: PreconditionFailedException) {
            resolveConflict(href)
        }
    }

    private suspend fun resolveConflict(href: String) {
        // Server rejected our If-Match: someone/something changed this resource since our last
        // sync. Server wins -- fetch the current version via multiget (a sync-collection REPORT
        // with one URL returns the same MultiStatusItem.Response shape pull()'s applyRemoteChange
        // already handles, so this reuses that function directly rather than duplicating it) and
        // overwrite local, discarding the conflicting local edit (per the spec's conflict rule).
        DavCalendar(httpClient, collectionUrl).multiget(
            urls = listOf(Url(href)),
            contentType = "text/calendar",
            version = "2.0",
        ).filterIsInstance<at.bitfire.dav4jvm.ktor.MultiStatusItem.Response>()
            .firstOrNull()
            ?.let { applyRemoteChange(it, forceOverwrite = true) }
    }
}
```

- [ ] **Step 3: Write the pull phase and the shared apply-remote-change
  helper**

```kotlin
    private suspend fun pull() {
        val syncToken = syncStateDao.get()?.syncToken
        var newToken: String? = null
        DavCollection(httpClient, collectionUrl).reportChanges(
            syncToken = syncToken,
            infiniteDepth = false,
            limit = null,
            at.bitfire.dav4jvm.property.webdav.WebDAV.GetETag,
            at.bitfire.dav4jvm.property.caldav.CalDAV.CalendarData,
        ).collect { item ->
            when (item) {
                is at.bitfire.dav4jvm.ktor.MultiStatusItem.Response -> applyRemoteChange(item)
                is at.bitfire.dav4jvm.ktor.MultiStatusItem.ExtraProperty -> {
                    // SyncToken.token is the expected field name -- every property class this
                    // plan's research verified follows the same "one descriptive field, no
                    // wrapper boilerplate" shape (GetETag.eTag, CurrentUserPrincipal.href,
                    // CalendarHomeSet.hrefs, CalendarData.iCalendar,
                    // SupportedCalendarComponentSet.supportsTasks/Events/Journal). Confirm
                    // against at.bitfire.dav4jvm.property.webdav.SyncToken's real source in
                    // /tmp/dav4jvm before relying on it -- one line to check, not a redesign,
                    // but this specific field name is inferred from a strong pattern rather than
                    // independently verified the way the rest of this plan's dav4jvm code was.
                    (item.property as? at.bitfire.dav4jvm.property.webdav.SyncToken)?.token?.let { newToken = it }
                }
                // else: ignore -- there may be other MultiStatusItem variants this plan's
                // research didn't enumerate; handle them as no-ops rather than crashing.
            }
        }
        newToken?.let { syncStateDao.set(SyncStateEntity(syncToken = it)) }
    }

    /** Applies one changed-or-deleted remote resource to the local DB. Used by both pull() and
     *  resolveConflict() (Step 2), so conflict resolution and normal pulls share one code path. */
    private suspend fun applyRemoteChange(
        response: at.bitfire.dav4jvm.ktor.MultiStatusItem.Response,
        forceOverwrite: Boolean = false,
    ) {
        val href = response.requestedUrl.toString()
        val existing = taskDao.getTaskByCaldavHref(href)
        if (!forceOverwrite && existing != null && (existing.syncDirty || existing.syncPendingDelete)) {
            return // local wins this cycle, per the documented conflict rule
        }
        // forceOverwrite=true (only set by resolveConflict) intentionally skips this guard --
        // a 412 on push already means the local edit lost the race, so it must be discarded here
        // rather than protected the way a normal pull-cycle local edit would be.
        val calendarData = response[at.bitfire.dav4jvm.property.caldav.CalDAV.CalendarData::class.java]
        if (calendarData == null) {
            // No CalendarData in the response = this href was deleted server-side.
            existing?.let { taskDao.deleteTaskById(it.id) }
            return
        }
        val parsed = VTodoMapper.parseVTodo(calendarData.iCalendar ?: return)
        val etag = response[at.bitfire.dav4jvm.property.webdav.WebDAV.GetETag::class.java]?.eTag
        val tagIds = parsed.tagNames.map { resolveOrCreateTag(it) }

        val taskId = if (existing != null) {
            taskDao.updateTask(
                existing.copy(
                    title = parsed.title, scheduledDate = parsed.scheduledDate,
                    deadline = parsed.deadline, completed = parsed.completed,
                    completedAt = parsed.completedAt, caldavEtag = etag,
                    syncDirty = false, syncPendingDelete = false,
                ),
            )
            existing.id
        } else {
            taskDao.insertTask(
                TaskEntity(
                    title = parsed.title, scheduledDate = parsed.scheduledDate,
                    deadline = parsed.deadline, completed = parsed.completed,
                    completedAt = parsed.completedAt, createdAt = java.time.Instant.now(),
                    sortOrder = taskDao.maxSortOrder() + 1,
                    caldavUid = parsed.uid, caldavHref = href, caldavEtag = etag,
                    syncDirty = false, syncPendingDelete = false,
                ),
            )
        }
        taskDao.clearTaskTags(taskId)
        tagIds.forEach { taskDao.addTagToTask(TaskTagCrossRef(taskId, it)) }
    }

    /**
     * Case-insensitive get-or-create, mirroring TaskRepository.createTag's exact dedup logic
     * (data/repo/TaskRepository.kt:264-270) -- reproduced here rather than called from there,
     * since SyncEngine talks to TagDao directly (see the dependency note below) and
     * TaskRepository's version is a private implementation detail of that class, not something
     * this file should reach into.
     */
    private suspend fun resolveOrCreateTag(name: String): Long {
        val trimmed = name.trim()
        tagDao.getByName(trimmed)?.let { return it.id }
        val id = tagDao.insert(TagEntity(name = trimmed, sortOrder = tagDao.maxSortOrder() + 1))
        return if (id == -1L) tagDao.getByName(trimmed)?.id ?: -1 else id
    }
```

**Dependency decision, made here rather than left open**: `SyncEngine`
takes `TaskDao`/`TagDao` directly (as its constructor already showed in
Step 2), not `TaskRepository`. This is a deliberate, narrow exception to
`TaskRepository`'s "single gateway" convention documented at the top of
`TaskRepository.kt`: that convention exists for *UI-facing* business
logic (view filtering, checklist auto-complete, recurrence spawning) —
sync's concerns (hrefs, etags, dirty flags, raw upsert-from-remote) are a
different, protocol-level layer that no UI code will ever call, and
routing it through `TaskRepository` would mean adding a dozen
sync-specific methods to a class whose whole purpose is being the UI's
narrow, curated gateway. The cost of this choice is the small amount of
duplicated logic above (`resolveOrCreateTag` mirrors `createTag`); that's
an acceptable, explicitly-noted tradeoff, not an oversight.

- [ ] **Step 4: Build, and confirm the file compiles**

Steps 2 and 3 above already include `resolveConflict`'s full body (a
`DavCalendar.multiget` call with one URL, reusing `applyRemoteChange` via
its `forceOverwrite` parameter) and the sync-token read/write (via the
`SyncStateDao` Task 2 added) — there's no assembly left to do here. This
step is just the checkpoint: build the module in isolation to catch any
mismatch between what Steps 2/3 wrote and what Task 2/Task 3 actually
produced (e.g. a renamed field, an import that doesn't resolve) before
moving on to the harder-to-debug on-device step.

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: no compile errors. If there are any, they're almost certainly
either the `SyncStateDao`/`SyncStateEntity` wiring from Task 2's Step 3b,
or the `TaskRepository`-vs-DAO dependency shape from Step 3's decision
above — check those two spots first.

- [ ] **Step 5: Handle the sync-token-rejected fallback**

Wrap the `reportChanges(...)` call in Step 3 with a catch for whatever
exception/response dav4jvm surfaces when the server rejects a stale
sync-token (check `/tmp/dav4jvm`'s exception types for the real one — the
spec calls this out as unverified against a live server, so the exact
type needs confirming during this task, not guessed). On that specific
failure: clear the stored token and retry `pull()` once with
`syncToken = null`, which per RFC 6578 triggers a full listing instead of
an incremental one — dav4jvm's `reportChanges` with a null token should
already do this correctly (confirm, don't assume) rather than needing a
separate code path.

- [ ] **Step 6: On-device end-to-end sync test**

```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 install -r app/build/outputs/apk/debug/app-debug.apk
```

Manually trigger a sync (a temporary debug button is fine here too, same
as Task 5 — Task 7 replaces it with the real automatic trigger). On the
Mudita: create a task with a title, scheduled date, and a tag → trigger
sync → confirm (via a raw `curl -u email:apppassword
https://caldav.fastmail.com/...` `PROPFIND`/`GET` against the collection
URL Task 5 discovered, or any other CalDAV client) that it actually
appears on Fastmail with the right `SUMMARY`/`DTSTART`/`CATEGORIES`.
Then edit that same VTODO directly via `curl -X PUT` (or another CalDAV
client) to change its title, trigger sync again, confirm the change
appears in the ErdToday UI. Complete and delete it locally, sync, confirm
it's gone/marked completed on the server.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add CalDAV sync engine: push, pull, conflict resolution"
git push
```

---

### Task 7: Background sync scheduling

**Files:**
- Create: `app/src/main/java/com/erdman/erdtoday/sync/SyncWorker.kt`
- Create: `app/src/main/java/com/erdman/erdtoday/sync/SyncScheduler.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/di/AppContainer.kt`
- Modify: `app/build.gradle.kts` (WorkManager dependency, if not already
  present — check first)
- Modify: `app/src/main/AndroidManifest.xml` (`INTERNET` permission, if not
  already present — check first, this app has had no networking before
  this plan)

**Interfaces:**
- Produces: `SyncScheduler.schedulePeriodic(context)` (called once at
  startup), `SyncScheduler.scheduleOneOff(context)` (called after any
  local edit that sets `syncDirty`/`syncPendingDelete`).
- Consumes: `SyncEngine.sync()` (Task 6), `TodayDatabase.MIGRATION_2_3`
  (Task 2, wired into the builder here as originally deferred).

- [ ] **Step 1: Check for the `INTERNET` permission and WorkManager
  dependency**

```bash
grep -n "INTERNET" app/src/main/AndroidManifest.xml
grep -n "androidx.work" app/build.gradle.kts
```

Add whichever is missing: `<uses-permission android:name="android.permission.INTERNET" />`
in the manifest; `implementation("androidx.work:work-runtime-ktx:2.9.1")`
(or whatever version the project's other `androidx.*` dependencies are
pinned near, for consistency) in `build.gradle.kts`.

- [ ] **Step 2: Wire `MIGRATION_2_3` into the database builder**

In `AppContainer.kt`, change:
```kotlin
    ).addMigrations(TodayDatabase.MIGRATION_1_2).build()
```
to:
```kotlin
    ).addMigrations(TodayDatabase.MIGRATION_1_2, TodayDatabase.MIGRATION_2_3).build()
```

- [ ] **Step 3: Write `SyncWorker`**

```kotlin
package com.erdman.erdtoday.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.erdman.erdtoday.TodayApp
import com.erdman.erdtoday.caldav.CalDavDiscovery
import com.erdman.erdtoday.caldav.SyncEngine
import com.erdman.erdtoday.caldav.SyncResult
import com.erdman.erdtoday.caldav.buildCalDavHttpClient
import androidx.work.ListenableWorker.Result as WorkResult
// Aliased: CalDavDiscovery.discoverOrCreateTaskCollection returns kotlin.Result<Url>, and
// CoroutineWorker.doWork() must return androidx.work.ListenableWorker.Result -- both named
// "Result", so one needs an alias to use both in the same file without qualifying every use.

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): WorkResult {
        val container = (applicationContext as TodayApp).container
        val creds = container.credentialsManager.credentials.value ?: return WorkResult.success()
        // No credentials yet = nothing to sync, not a failure.
        val httpClient = buildCalDavHttpClient(creds.email, creds.appPassword)
        val collectionUrl = CalDavDiscovery.discoverOrCreateTaskCollection(httpClient)
            .getOrElse { return WorkResult.retry() }
        val engine = SyncEngine(
            httpClient = httpClient,
            collectionUrl = collectionUrl,
            taskDao = container.database.taskDao(),
            tagDao = container.database.tagDao(),
            syncStateDao = container.database.syncStateDao(),
        )
        return when (engine.sync()) {
            is SyncResult.Success -> WorkResult.success()
            is SyncResult.Failure -> WorkResult.retry()
        }
    }
}
```

`container.database` needs to be accessible from outside `AppContainer` for
this — check whether `AppContainer.kt`'s `database` property is currently
`private val database: TodayDatabase = ...` (it is, per the file this plan
already read). Change it to `val database: TodayDatabase = ...` (drop
`private`) as part of this step, since `SyncWorker` now needs it and it's
otherwise only exposed indirectly through `repository`, which doesn't
expose the three DAOs `SyncEngine` needs.

(Re-running discovery on every sync is intentionally simple for this
version rather than caching the collection URL somewhere — it's one
extra PROPFIND round trip per sync cycle, cheap, and avoids a second
place to invalidate a cache if the collection ever needs recreating.
Note this as an acceptable tradeoff in this task's self-review, not a
gap to fix.)

- [ ] **Step 4: Write `SyncScheduler`**

```kotlin
package com.erdman.erdtoday.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val PERIODIC_WORK_NAME = "erdtoday_periodic_sync"
    private const val ONE_OFF_WORK_NAME = "erdtoday_one_off_sync"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleOneOff(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_OFF_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
```

- [ ] **Step 5: Trigger sync from `AppContainer`'s init block and after
  edits**

In `AppContainer.kt`'s existing `init { applicationScope.launch { ... } }`
block, add `SyncScheduler.schedulePeriodic(appContext)` alongside the
existing `pruneLogbook()`/`rescheduleAllReminders()` calls (this
schedules the periodic worker; it does not need to be inside the
`launch` coroutine, since `WorkManager.enqueueUniquePeriodicWork` isn't
suspending — move it just outside that block if that's cleaner).

For the one-off trigger: every place `TaskRepository` sets `syncDirty`/
`syncPendingDelete` needs to also call `SyncScheduler.scheduleOneOff`.
Rather than adding this call to five+ separate methods, add it once at
the end of `markDirtyIfChanged` (Task 2, Step 4) and the equivalent spot
in `setTaskTags`/`deleteTask`/`captureAndDelete` — but `TaskRepository`
doesn't currently have a `Context` to call `SyncScheduler.scheduleOneOff(context)`
with. Give `TaskRepository`'s constructor a new parameter,
`private val onSyncNeeded: () -> Unit = {}`, and call `onSyncNeeded()`
from `markDirtyIfChanged` and the tag/delete dirty-marking spots instead
of calling `SyncScheduler` directly (keeps `TaskRepository` free of an
Android `Context`/WorkManager dependency, consistent with its existing
`reminderScheduler: ReminderScheduler` constructor parameter, which uses
the same "inject a callback interface, don't reach for Context directly"
shape). Wire it in `AppContainer.kt`'s `TaskRepository(...)` construction:
`onSyncNeeded = { SyncScheduler.scheduleOneOff(appContext) }`.

- [ ] **Step 6: On-device verification of automatic sync**

```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 install -r app/build/outputs/apk/debug/app-debug.apk
```

On the Mudita: create a task, wait ~30 seconds (WorkManager's one-off
dispatch isn't instant), confirm via `curl`/another client that it
appeared on Fastmail without touching any debug button this time (the
temporary debug triggers from Tasks 5/6 can be removed now that the real
automatic path works — remove them as part of this task if they're still
present). Airplane-mode test: enable airplane mode, create/edit a few
tasks, disable airplane mode, confirm they push within the next periodic
or one-off cycle.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Wire automatic background sync via WorkManager"
git push
```

---

### Task 8: Final full end-to-end verification pass

**Files:** none (verification only)

**Interfaces:** none.

- [ ] **Step 1: Fresh install from a clean build**

```bash
cd ~/Projects/ErdToday
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew clean :app:assembleDebug -q
adb -s MK20250402537 uninstall com.erdman.erdtoday.debug
adb -s MK20250402537 install app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Walk the full flow on-device**

Account setup (real Fastmail credentials) → confirm the Today view loads
→ create several tasks across different views (one due today, one
upcoming, one with no date, tag a couple of them) → confirm they sync to
Fastmail (check via another CalDAV client or `curl`) → edit one via that
other client → confirm ErdToday picks up the change → complete a task
on-device → confirm it moves to the Logbook locally and shows
`STATUS:COMPLETED` on the server → delete a task → confirm it's gone on
both sides → add notes, a checklist item, and a recurrence to a task →
confirm none of that triggers a sync push (check the server-side VTODO
for that task doesn't gain a `DESCRIPTION` or anything checklist-related)
→ set the logbook retention to a short window in Settings, complete a
task, confirm the existing `pruneLogbook()` behavior still works
unaffected by anything this plan added.

- [ ] **Step 3: Confirm test suite is green**

```bash
./gradlew test
```

- [ ] **Step 4: Fix any regression found, otherwise done**

If a step in the walkthrough doesn't match expectations, go back to
whichever earlier task owns that code and fix it in a new commit there
(don't patch it silently in this task) — keep the git history
attributable. Once every step in Step 2 passes, this plan is complete.
