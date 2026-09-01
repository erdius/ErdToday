# ErdToday Vikunja Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ErdToday's Fastmail/CalDAV sync (Tasks 1-5b of the superseded plan) with sync against a self-hosted Vikunja instance, using Vikunja's native JSON REST API.

**Architecture:** A plain Ktor `HttpClient` (Bearer-token auth) talks directly to Vikunja's REST API from a new `vikunja` package inside `:app` — no separate Gradle module needed this time (see "Why no `:vikunja` module" below). A `VikunjaTaskMapper` converts between `TaskEntity` and Vikunja's JSON `Task` shape. `VikunjaProjectSetup` finds-or-creates one Vikunja project ("ErdToday") to hold every synced task, mirroring the CalDAV-era "one collection" design. `SyncEngine` pushes dirty/pending-delete rows then pulls the project's current task list, reusing Task 2's dirty-tracking schema (`syncDirty`/`syncPendingDelete`) with the CalDAV-specific identity columns swapped for a single `vikunjaTaskId`. `SyncWorker`/`WorkManager` scheduling is unchanged in shape from the original design.

**Tech Stack:** Ktor 2.3.12 client (OkHttp engine, Bearer auth), kotlinx.serialization for JSON, Room (existing), WorkManager (existing), `EncryptedSharedPreferences` for credentials (existing pattern).

**Supersedes:** `docs/superpowers/plans/2026-08-31-erdtoday-fastmail-sync.md` (Tasks 6-8 of that plan are never dispatched; Tasks 1-5b already landed and are kept — see Global Constraints). Sync-scope decisions from `docs/superpowers/specs/2026-08-31-erdtoday-fastmail-sync-design.md` still apply (title/scheduledDate/deadline/completed/tags only; tasks never appear in calendar views — moot now since there's no CalDAV/VEVENT surface at all) except everything specific to Fastmail/CalDAV/dav4jvm, which this plan replaces outright. No separate spec document was written for this pivot — the user explicitly asked to move straight to the plan; the design was presented and approved in chat (see the SDD ledger's "Vikunja pivot" entry for the record).

## Why no `:vikunja` module

The `:caldav` module (Task 5) existed *specifically* because `dav4jvm`'s own compiled jar is JVM 21 bytecode, which kapt's javac stub-compilation pass refuses to link against under this project's Java 17 toolchain — an unrelated-to-Kotlin-version problem that forced a whole separate non-kapt module. Verified directly against Maven Central POMs before writing this plan: **Ktor 2.3.12** (not the 3.5.1 that `dav4jvm` forced onto the old `:caldav` module) declares `kotlin-stdlib 1.8.22`, `kotlinx-coroutines-core-jvm 1.7.1`, `okhttp 4.12.0`, `okio-jvm 3.7.0` — all *older than or equal to* this project's existing Kotlin 1.9.22/coroutines 1.8.1 pins. There is no newer-Kotlin-metadata problem and no JVM-21-bytecode problem with Ktor 2.3.12 itself. Since Vikunja's own API is plain JSON REST (no WebDAV, no `dav4jvm` needed at all), the entire module-split/version-force/`-Xskip-metadata-version-check` apparatus from Tasks 5/5b's fix rounds is dead weight for this pivot and gets deleted in Task 1, not reused.

If a step in this plan discovers Ktor 2.3.12 (or `kotlinx-serialization`, or the Ktor content-negotiation/serialization-json artifacts pulled in alongside it) actually does conflict with something in this project's toolchain, that is new information this plan's authors didn't have — stop and report rather than silently reintroducing the module-split pattern; the fix is more likely a version pin adjustment than resurrecting the whole workaround stack.

## Global Constraints

- Sync scope is unchanged from the original spec: `title`, `scheduledDate` (→ Vikunja `start_date`... **actually see Task 4's note — this plan maps `deadline` to Vikunja's `due_date` and deliberately does NOT sync `scheduledDate` to any Vikunja field; see rationale there**), `deadline`, `completed`, `tags`. `notes`, `checklist`, `recurrence`, `reminderTime` are NOT synced (local-only), same as before.
- Tasks never appear in a calendar view — moot now (no CalDAV/VEVENT surface exists in this design at all).
- The debug build variant has a pre-existing `applicationIdSuffix = ".debug"` — the real installed package for every debug build is `com.erdman.erdtoday.debug`. Every `adb` command that names the package (`adb shell monkey -p ...`, `adb uninstall ...`) must use the `.debug` suffix. `adb install <path>` itself and Kotlin `package`/`import` statements are unaffected.
- Device for on-device verification: Mudita Kompakt, adb serial `MK20250402537`.
- **Gradle/Java environment**: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"` before any `./gradlew` invocation. (The `:caldav` module's separate JDK-21 test-execution requirement disappears once Task 1 deletes that module — don't carry that quirk forward.)
- Current toolchain state (from the superseded plan's Tasks 1-5b, already landed on `feature/fastmail-sync` and kept as-is): AGP 8.6.1, Gradle 8.7, Kotlin 1.9.22, Compose compiler 1.5.10, `compileSdk`/`targetSdk` 35, `minSdk` 28. Do not change any of these as part of this plan unless a task explicitly says to.
- Manual DI via `AppContainer` (no Hilt) — established pattern, continue it.
- Vikunja API base facts, verified directly against `go-vikunja/vikunja`'s real Go source on GitHub before writing this plan (not paraphrased docs) — treat these as ground truth for every task below:
  - Auth: `Authorization: Bearer <token>` header (works identically for a login-obtained JWT and a scoped personal API token — the user's token, which starts with `tk_`, is the latter).
  - Real `Task` JSON fields: `id` (int64, server-assigned), `title` (string, required), `description` (string), `done` (bool), `done_at` (RFC3339 timestamp string, **server-controlled, read-only** — never send it), `due_date` (RFC3339 timestamp string; **always present in every response**, using Go's zero-time sentinel `"0001-01-01T00:00:00Z"` when unset — never absent/null), `project_id` (int64), `labels` (array, **read-only on the Task object itself** — adding/removing a label requires the separate label-task endpoints below, sending a `labels` array in a task create/update body has no effect), `created`/`updated` (RFC3339, read-only).
  - Real registered routes: `PUT /projects/{project}/tasks` (create a task in a project, body = partial `Task` JSON, `title` required), `POST /tasks/{id}` (update a task, body = `Task` JSON), `DELETE /tasks/{id}` (delete), `GET /tasks/{id}` (read one), `GET /projects/{project}/tasks` (list a project's tasks), `PUT /tasks/{id}/labels` (add a label to a task, body `{"label_id": <id>}`), `DELETE /tasks/{id}/labels/{label}` (remove), `GET /tasks/{id}/labels` (list a task's labels), `GET /labels` (list all labels visible to the user), `PUT /labels` (create a label, body `{"title": "<name>"}`), `GET /projects` (list projects), `PUT /projects` (create a project, body `{"title": "<name>"}`).
  - Real `Label` JSON fields: `id`, `title`, `description`, `hex_color`, `created`/`updated` (read-only).
  - Real `Project` JSON fields: `id`, `title`, `description`, `identifier`, `is_archived`, `created`/`updated` (read-only). `PUT /projects` needs only `{"title": "ErdToday"}` in the body.
  - No ETags, no conditional-request mechanism (`If-Match` etc.) exists in this API — conflict handling in this plan is a simple in-app dirty-flag guard, not an HTTP-level conditional dance (see Task 5).

---

### Task 1: Delete the CalDAV/Fastmail-specific code and the `:caldav` module

**Files:**
- Delete: `app/src/main/java/com/erdman/erdtoday/caldav/VTodoMapper.kt`
- Delete: `app/src/test/java/com/erdman/erdtoday/caldav/VTodoMapperTest.kt`
- Delete: `caldav/` (the entire directory — `build.gradle.kts`, `src/main/kotlin/com/erdman/erdtoday/caldav/CalDavDiscovery.kt`, `src/main/kotlin/com/erdman/erdtoday/caldav/CalDavHttpClient.kt`, `src/test/kotlin/com/erdman/erdtoday/caldav/CalDavDiscoveryTest.kt`, and its `build/` output directory)
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `.gitignore` (remove the `/caldav/build` line Task 5 added, now dead)

**Interfaces:** None produced — this is pure deletion/cleanup. Nothing later in this plan depends on anything from this task beyond "the repo builds and tests pass with all of the above gone."

- [ ] **Step 1: Delete the dead files and the whole `:caldav` module**

```bash
git rm app/src/main/java/com/erdman/erdtoday/caldav/VTodoMapper.kt
git rm app/src/test/java/com/erdman/erdtoday/caldav/VTodoMapperTest.kt
git rm -r caldav/
```

(`caldav/build/` is git-ignored, so `git rm -r caldav/` may report it doesn't track everything under `build/` — that's fine, just confirm the directory is gone from the working tree afterward with `ls caldav 2>&1` expecting "No such file or directory".)

- [ ] **Step 2: Remove `:caldav` from `settings.gradle.kts`**

Remove the line `include(":caldav")`. Also remove the now-unused JitPack repository block:
```kotlin
        // dav4jvm (bitfireAT's CalDAV/CardDAV library, used by DAVx5) is published via JitPack.
        // (dav4jvm's Gradle module metadata declares itself Java-21-compatible, which needs a
        // component metadata rule to work around -- see caldav/build.gradle.kts.)
        maven("https://jitpack.io")
```
Leave the `google()`, `mavenCentral()`, and Mudita MMD `maven { ... }` blocks untouched.

- [ ] **Step 3: Strip the CalDAV-era machinery from `app/build.gradle.kts`**

Remove, in full:
- The entire `configurations.matching { it.name.endsWith("CompileClasspath") }.configureEach { ... }` block (including its long explanatory comment above it) — this whole apparatus existed only because of `dav4jvm`'s newer-Kotlin-metadata transitive dependencies, which are gone once `:caldav` is deleted.
- The `implementation(project(":caldav")) { exclude(group = "org.ogce", module = "xpp3") }` dependency block and its explanatory comment.
- The `// Secure credential storage (Fastmail CalDAV account email + app password)` comment above the `security-crypto` dependency — leave the dependency itself (credentials storage is still needed, just update the comment to say "Fastmail" → drop the Fastmail-specific wording, since Task 3 repurposes this for Vikunja credentials): change the comment to `// Secure credential storage (Vikunja server URL + API token)`.

Do NOT remove `kotlinOptions { freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api" }` — that's unrelated to CalDAV, keep it. Confirm no `-Xskip-metadata-version-check` line remains anywhere in this file (Task 5's fix round already removed it from `:app`; just confirm, don't assume).

- [ ] **Step 4: Check `gradle/libs.versions.toml` for now-dead entries**

The `kotlin-jvm` plugin alias (`kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }` in `[plugins]`) was added in Task 5 specifically for the `:caldav` module's Kotlin/JVM plugin. Remove it — nothing else in this project applies `kotlin-jvm` (the app module uses `kotlin-android`). Also remove the corresponding `alias(libs.plugins.kotlin.jvm) apply false` line from the root `build.gradle.kts`.

Leave `mockk` in place — Task 2 added it for `TaskRepository` tests, unrelated to CalDAV.

- [ ] **Step 5: Clean build and full test suite**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean
./gradlew :app:assembleDebug -q
./gradlew :app:assembleRelease -q
./gradlew test
```

Expected: both builds succeed, and the test suite drops by exactly the 4 tests `VTodoMapperTest` contributed (49 → 45; confirm the actual before/after count yourself rather than assuming this exact arithmetic — if it differs, understand why before moving on). No `:caldav` task should appear anywhere in Gradle's output.

- [ ] **Step 6: Grep for stragglers**

```bash
grep -rn "caldav\|dav4jvm\|jitpack" --include="*.kts" --include="*.toml" . 2>/dev/null | grep -v "^\./caldav"
grep -rln "com.erdman.erdtoday.caldav" app/src
```
Expected: no hits (the `caldav` package directory itself should no longer exist under `app/src` either, since Step 1 deleted its only two files there).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Remove Fastmail/CalDAV sync code ahead of Vikunja sync (dead: VTodoMapper, :caldav module, dav4jvm)"
git push
```

---

### Task 2: Schema — rename CalDAV identity columns to a single Vikunja task ID

**Files:**
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/Entities.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/TodayDatabase.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/local/Daos.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/data/repo/TaskRepository.kt`
- Modify: `app/src/test/java/com/erdman/erdtoday/data/repo/TaskRepositoryDirtyTrackingTest.kt`

**Interfaces:**
- Produces: `TaskEntity.vikunjaTaskId: Long?` (replaces `caldavUid`/`caldavHref`/`caldavEtag`). `TaskDao.getTaskByVikunjaTaskId(id: Long): TaskEntity?` (replaces `getTaskByCaldavUid`/`getTaskByCaldavHref`). `TaskDao.tasksNeedingSync()` unchanged (still backend-agnostic — it only reads `syncDirty`/`syncPendingDelete`). `SyncStateEntity`/`SyncStateDao` unchanged in shape but repurposed: Task 4/V5 will store the Vikunja "ErdToday" project's id there instead of a CalDAV sync-token (rename the column — see Step 1).
- Consumes: nothing from Task 1 directly (independent cleanup), but must be applied to the post-V1 tree (branch/commit order: V1 then V2).

- [ ] **Step 1: Replace the CalDAV identity columns in `TaskEntity`**

In `Entities.kt`, replace:
```kotlin
    /** CalDAV UID (RFC 5545 UID) once this task has been pushed to Fastmail; null until then. */
    val caldavUid: String? = null,
    /** This task's resource path on the Fastmail CalDAV server; null until first successful push. */
    val caldavHref: String? = null,
    /** The resource's ETag as of the last successful sync; null for a not-yet-pushed task. */
    val caldavEtag: String? = null,
```
with:
```kotlin
    /** This task's id on the Vikunja server once pushed; null until first successful push. */
    val vikunjaTaskId: Long? = null,
```
Leave `syncDirty`/`syncPendingDelete` untouched — they're already backend-agnostic.

Also update `SyncStateEntity`'s doc comment and field (it held a CalDAV sync-token; Vikunja has no equivalent token, but this plan still needs somewhere to persist the "ErdToday" project's id between sync cycles rather than re-resolving it every time):
```kotlin
/** Single-row table holding the id of the Vikunja project ("ErdToday") every synced task lives in. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0, // always 0 -- this app syncs against exactly one Vikunja project
    val vikunjaProjectId: Long? = null,
)
```

- [ ] **Step 2: Add the Room migration**

In `TodayDatabase.kt`, bump `version = 3` to `version = 4`:
```kotlin
        /** v4 replaces CalDAV identity columns with a single Vikunja task id; repurposes
         *  sync_state's syncToken column for the Vikunja project id. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN vikunjaTaskId INTEGER")
                db.execSQL("ALTER TABLE tasks DROP COLUMN caldavUid")
                db.execSQL("ALTER TABLE tasks DROP COLUMN caldavHref")
                db.execSQL("ALTER TABLE tasks DROP COLUMN caldavEtag")
                db.execSQL("ALTER TABLE sync_state ADD COLUMN vikunjaProjectId INTEGER")
                db.execSQL("ALTER TABLE sync_state DROP COLUMN syncToken")
            }
        }
```
(SQLite added native `ALTER TABLE ... DROP COLUMN` support in 3.35.0 (2021); Room's bundled SQLite on `minSdk 28` is well past that floor via `androidx.sqlite`'s bundled driver — but if this fails when actually run, don't just delete the DROP COLUMN lines and leave orphaned columns; instead fall back to the standard Room-recommended rebuild-and-copy pattern (`CREATE TABLE tasks_new (...)`, `INSERT INTO tasks_new SELECT ... FROM tasks`, `DROP TABLE tasks`, `ALTER TABLE tasks_new RENAME TO tasks`) and report which path you had to take.)

**Do NOT wire `MIGRATION_3_4` into `AppContainer.kt`'s builder yet** — same deferred-wiring pattern as the original Task 2's `MIGRATION_2_3`, for the same reason: keeps this task's diff scoped to schema + repository logic. Task 6 wires all migrations at once. `AppContainer.kt`'s builder currently only has `.addMigrations(TodayDatabase.MIGRATION_1_2)` — leave that line completely alone here.

Update `TodayDatabase.kt`'s `@Database` `version` to `4`. `MIGRATION_2_3` itself (added in the superseded plan's Task 2, already landed) stays in the file unchanged — it's still a real, valid part of this app's migration history from schema v2 to v3; only its *content* is now stale relative to `MIGRATION_3_4`'s changes, which is normal and expected for a migration chain.

- [ ] **Step 3: Update `Daos.kt`**

Replace:
```kotlin
    @Query("SELECT * FROM tasks WHERE caldavUid = :uid LIMIT 1")
    suspend fun getTaskByCaldavUid(uid: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE caldavHref = :href LIMIT 1")
    suspend fun getTaskByCaldavHref(href: String): TaskEntity?
```
with:
```kotlin
    @Query("SELECT * FROM tasks WHERE vikunjaTaskId = :id LIMIT 1")
    suspend fun getTaskByVikunjaTaskId(id: Long): TaskEntity?
```
Leave `tasksNeedingSync()` untouched. Update the `// CalDAV sync` section comment above these to `// Vikunja sync`.

`SyncStateDao` is unchanged in shape (`get()`/`set()`) — its `SyncStateEntity` parameter type already reflects Step 1's field rename automatically.

- [ ] **Step 4: Update `TaskRepository`'s soft-delete check**

`deleteTask` and `captureAndDelete` currently check `t?.caldavUid != null` / `snapshot.task.caldavUid != null` to decide soft-delete-pending-sync vs. hard-delete. Change both to `vikunjaTaskId != null`:
```kotlin
    suspend fun deleteTask(taskId: Long) {
        reminderScheduler.cancel(taskId)
        val t = taskDao.getTaskEntity(taskId)
        if (t?.vikunjaTaskId != null) {
            taskDao.updateTask(t.copy(syncPendingDelete = true))
        } else {
            taskDao.deleteTaskById(taskId)
        }
    }

    suspend fun captureAndDelete(taskId: Long): TaskWithDetails? {
        val snapshot = taskDao.getTask(taskId) ?: return null
        reminderScheduler.cancel(taskId)
        if (snapshot.task.vikunjaTaskId != null) {
            taskDao.updateTask(snapshot.task.copy(syncPendingDelete = true))
        } else {
            taskDao.deleteTaskById(taskId)
        }
        return snapshot
    }
```
Nothing else in `TaskRepository` referenced `caldavUid`/`caldavHref`/`caldavEtag` — `markDirtyIfChanged`, `setTitle`, `setDeadline`, `setScheduledDate`, `applyCompletion`, `setTaskTags` all only touch `syncDirty`, which is untouched by this rename. Read the file to confirm this is still true before finishing this step (the codebase may have changed since this plan was written).

- [ ] **Step 5: Update the existing dirty-tracking test**

`TaskRepositoryDirtyTrackingTest.kt`'s two soft-delete test cases (`deleteTask on a task with caldavUid=null deletes it for real` / `deleteTask on a task with caldavUid!=null sets syncPendingDelete=true`) construct a `TaskEntity` with `caldavUid = "..."` to exercise the soft-delete path. Update these to use `vikunjaTaskId = 123L` (or any non-null `Long`) instead, and rename the test case names to say `vikunjaTaskId` instead of `caldavUid`. Everything else in this test file is unaffected — confirm the fake DAOs (`FakeTaskDao`/`FakeTagDao`) don't reference the removed columns anywhere (they shouldn't, since Room's real column set isn't visible to a plain in-memory fake, but check anyway).

- [ ] **Step 6: Run the test suite**

```bash
./gradlew test
```
Expected: same count as after Task 1 (all passing, no new failures).

- [ ] **Step 7: Build and install fresh, spot-check no regression**

This task bumps `@Database` version to 4 with the migration deliberately unwired (same reasoning as the original plan's Task 2) — install fresh, not `-r`, to sidestep the missing migration path entirely:
```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 uninstall com.erdman.erdtoday.debug
adb -s MK20250402537 install app/build/outputs/apk/debug/app-debug.apk
```
On-device: create a task, edit its title, set a deadline, complete it, add a tag, edit notes, add a checklist item — confirm everything still works exactly as before (this task only renames invisible tracking columns; nothing should look different).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Replace CalDAV identity columns with a single vikunjaTaskId"
git push
```

---

### Task 3: CredentialsManager and account-setup UI for Vikunja

**Files:**
- Modify: `app/src/main/java/com/erdman/erdtoday/data/credentials/CredentialsManager.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/ui/accountsetup/AccountSetupViewModel.kt`
- Modify: `app/src/main/java/com/erdman/erdtoday/ui/accountsetup/AccountSetupScreen.kt`

**Interfaces:**
- Produces: `CredentialsManager(context).credentials: StateFlow<VikunjaCredentials?>`, `CredentialsManager(context).save(baseUrl: String, apiToken: String)`, where `VikunjaCredentials(val baseUrl: String, val apiToken: String)`. `baseUrl` is stored **normalized with no trailing slash** (Task 4's API client depends on this — document it clearly, since a stray trailing slash would silently double up when building request URLs).
- Consumes: nothing new.

- [ ] **Step 1: Rewrite `CredentialsManager`**

Same `MasterKey`/`EncryptedSharedPreferences` pattern, two fields instead of three, different field names and normalization:
```kotlin
package com.erdman.erdtoday.data.credentials

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VikunjaCredentials(
    val baseUrl: String,
    val apiToken: String,
)

/** Self-hosted Vikunja server URL + API token, in encrypted SharedPreferences. */
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
    val credentials: StateFlow<VikunjaCredentials?> = _credentials.asStateFlow()

    private fun readCredentials(): VikunjaCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val apiToken = prefs.getString(KEY_API_TOKEN, null) ?: return null
        return VikunjaCredentials(baseUrl, apiToken)
    }

    /** [baseUrl] is normalized here (trimmed, trailing slash stripped) so every caller gets a
     *  consistent, slash-free base to build request paths onto. */
    fun save(baseUrl: String, apiToken: String) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val trimmedToken = apiToken.trim()
        prefs.edit()
            .putString(KEY_BASE_URL, normalizedUrl)
            .putString(KEY_API_TOKEN, trimmedToken)
            .apply()
        _credentials.value = VikunjaCredentials(normalizedUrl, trimmedToken)
    }

    fun clear() {
        prefs.edit().clear().apply()
        _credentials.value = null
    }

    companion object {
        private const val PREFS_NAME = "erdtoday_credentials"
        private const val KEY_BASE_URL = "vikunja_base_url"
        private const val KEY_API_TOKEN = "vikunja_api_token"
    }
}
```
(`AppContainer.kt`'s `val credentialsManager: CredentialsManager = CredentialsManager(appContext)` line needs no change — it constructs the class by name, not by its old field shape.)

- [ ] **Step 2: Rewrite `AccountSetupViewModel`**

```kotlin
package com.erdman.erdtoday.ui.accountsetup

import androidx.lifecycle.ViewModel
import com.erdman.erdtoday.data.credentials.CredentialsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backs the first-run Vikunja account setup form (server URL + API token). */
class AccountSetupViewModel(
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiToken = MutableStateFlow("")
    val apiToken: StateFlow<String> = _apiToken.asStateFlow()

    fun setBaseUrl(value: String) {
        _baseUrl.value = value
    }

    fun setApiToken(value: String) {
        _apiToken.value = value
    }

    /** Persists the entered credentials; the app shell reacts to [CredentialsManager.credentials]. */
    fun connect() {
        credentialsManager.save(_baseUrl.value, _apiToken.value)
    }
}
```

- [ ] **Step 3: Rewrite `AccountSetupScreen`**

Same MMD components (`TextFieldMMD`, `ButtonMMD`, `TopAppBarMMD`, `TextMMD`) and layout shape as the Fastmail version, different copy and field types. The API token is sensitive like a password (mask it), but the server URL is not:
```kotlin
package com.erdman.erdtoday.ui.accountsetup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.erdman.erdtoday.di.appContainer
import com.erdman.erdtoday.di.viewModelCreator
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/** First-run screen: collect the self-hosted Vikunja server URL + API token, then connect. */
@Composable
fun AccountSetupScreen() {
    val container = appContainer()
    val vm: AccountSetupViewModel = viewModel(
        factory = viewModelCreator { AccountSetupViewModel(container.credentialsManager) },
    )

    val baseUrl by vm.baseUrl.collectAsState()
    val apiToken by vm.apiToken.collectAsState()
    val canConnect = baseUrl.isNotBlank() && apiToken.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        TopAppBarMMD(title = { TextMMD("Connect Vikunja") })

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TextMMD("Enter your Vikunja server address and an API token to sync to-dos.")
            Spacer(Modifier.height(16.dp))

            TextFieldMMD(
                value = baseUrl,
                onValueChange = vm::setBaseUrl,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { TextMMD("Server URL (e.g. http://192.168.1.213:3456)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))

            TextFieldMMD(
                value = apiToken,
                onValueChange = vm::setApiToken,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { TextMMD("API token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConnect) vm.connect() }),
            )
            Spacer(Modifier.height(24.dp))

            ButtonMMD(
                onClick = vm::connect,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) { TextMMD("Connect") }
        }
    }
}
```

- [ ] **Step 4: Build, install, verify on-device**

```bash
./gradlew :app:assembleDebug -q
adb -s MK20250402537 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s MK20250402537 shell monkey -p com.erdman.erdtoday.debug -c android.intent.category.LAUNCHER 1
```
Expected: first launch (or after clearing app data) shows the Vikunja setup screen. Enter a server URL and API token, tap Connect, confirm it switches to the Today view. Force-quit and relaunch — confirm it goes straight to Today (credentials persisted). You do NOT need real Vikunja credentials for this step — any non-blank strings exercise the save/load/UI-switch path; Task 4 is where a real connection first gets tested.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Replace Fastmail account setup with Vikunja server URL + API token"
git push
```

---

### Task 4: VikunjaApiClient — HTTP client, task/label/project JSON mapping

**Files:**
- Modify: `app/build.gradle.kts` (Ktor + kotlinx.serialization dependencies, serialization Gradle plugin)
- Modify: `gradle/libs.versions.toml`
- Create: `app/src/main/java/com/erdman/erdtoday/vikunja/VikunjaModels.kt`
- Create: `app/src/main/java/com/erdman/erdtoday/vikunja/VikunjaApiClient.kt`
- Create: `app/src/main/java/com/erdman/erdtoday/vikunja/VikunjaTaskMapper.kt`
- Test: `app/src/test/java/com/erdman/erdtoday/vikunja/VikunjaTaskMapperTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class VikunjaTaskWrite(
      val title: String,
      val dueDate: Instant?, // null -> Vikunja's zero-time sentinel, see VikunjaTaskMapper
      val done: Boolean,
  )
  data class VikunjaTaskRead(
      val id: Long,
      val title: String,
      val dueDate: LocalDate?,
      val done: Boolean,
      val doneAt: Instant?,
      val updatedAt: Instant,
      val labelIds: List<Long>,
  )
  class VikunjaApiClient(baseUrl: String, apiToken: String) {
      suspend fun listProjects(): Result<List<VikunjaProject>>
      suspend fun createProject(title: String): Result<VikunjaProject>
      suspend fun listTasks(projectId: Long): Result<List<VikunjaTaskRead>>
      suspend fun createTask(projectId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead>
      suspend fun updateTask(taskId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead>
      suspend fun deleteTask(taskId: Long): Result<Unit>
      suspend fun listLabels(): Result<List<VikunjaLabel>>
      suspend fun createLabel(title: String): Result<VikunjaLabel>
      suspend fun addLabelToTask(taskId: Long, labelId: Long): Result<Unit>
      suspend fun removeLabelFromTask(taskId: Long, labelId: Long): Result<Unit>
  }
  data class VikunjaProject(val id: Long, val title: String)
  data class VikunjaLabel(val id: Long, val title: String)
  object VikunjaTaskMapper {
      fun toWrite(task: TaskEntity): VikunjaTaskWrite
      fun applyRead(read: VikunjaTaskRead, into: TaskEntity): TaskEntity // preserves local-only fields
  }
  ```
- Consumes: nothing from earlier tasks in this plan directly, but `VikunjaTaskMapper.applyRead`'s signature is shaped for Task 5's `SyncEngine` to call directly.

**Design notes for the implementer:**
- **Auth failures**: every `VikunjaApiClient` method returns `Result<T>` — a non-2xx response (401 for a bad token, 404 for a bad base URL/wrong path, connection refused for an unreachable server) should surface as `Result.failure` with a message useful for logging, not throw uncaught.
- **`due_date` zero-time handling**: Vikunja's `due_date` field is *always present* in every JSON response (Go's zero-value convention, no `omitempty`), using `"0001-01-01T00:00:00Z"` to mean "unset." When parsing a response, treat any `due_date` whose year is `1` as `null`. When writing, if the local `deadline` is `null`, either omit the field from your outgoing JSON entirely (Vikunja's own default for a missing field on create/update is presumably its own zero value — confirm this empirically in Step 5's live test rather than assuming) or send the exact zero-time string explicitly if omission doesn't clear an existing due date on update. Note which behavior you observed in your report.
- **`scheduledDate` is intentionally NOT synced.** The original CalDAV design synced `scheduledDate` to `DTSTART`. Vikunja's closest analog is `start_date`, but Vikunja's UI/semantics for `start_date` don't cleanly match this app's "Anytime vs. a specific day" scheduling model the way DTSTART did for a calendar client, and the user did not ask for it specifically. Sync scope for this pivot is `title`/`deadline`/`completed`/`tags` only — `scheduledDate` stays local-only, same bucket as `notes`/`checklist`/`recurrence`/`reminderTime`. (If this turns out wrong once real usage reveals a need, that's a follow-up task, not a mid-task scope call.)
- **JSON date/time fields** (`due_date`, `done_at`, `created`, `updated`) are RFC3339 strings kotlinx.serialization can decode via a custom serializer using `java.time.Instant.parse(...)`/`.toString()` — write a small reusable `InstantIso8601Serializer` rather than hand-parsing in multiple places.

- [ ] **Step 1: Add Ktor 2.3.12 + kotlinx.serialization dependencies**

In `gradle/libs.versions.toml`, add to `[versions]`:
```toml
ktor = "2.3.12"
kotlinxSerialization = "1.6.3"
```
Add to `[plugins]`:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```
(The serialization *compiler plugin* version tracks the Kotlin version, not `kotlinxSerialization` — use `version.ref = "kotlin"` here, matching `kotlin-android`'s own `version.ref`, not a separate pin.)

In `app/build.gradle.kts`'s `plugins { }` block, add:
```kotlin
    alias(libs.plugins.kotlin.serialization)
```
In `dependencies { }`, add:
```kotlin
    // Vikunja REST API client. Ktor 2.3.12 (not 3.x) deliberately -- verified against Maven
    // Central POMs that it depends on kotlin-stdlib 1.8.22 / kotlinx-coroutines-core-jvm 1.7.1 /
    // okio-jvm 3.7.0, all at or below this project's existing pins, so none of the JVM-21-bytecode
    // or newer-Kotlin-metadata problems the old dav4jvm-based :caldav module needed workarounds
    // for apply here. If a build error suggests otherwise, that's new information -- report it,
    // don't reflexively resurrect the old module-split/version-force pattern.
    implementation("io.ktor:ktor-client-core:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-okhttp:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-client-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinxSerialization.get()}")
```

- [ ] **Step 2: Confirm the dependencies resolve and don't conflict**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug -q
```
Expected: clean build (nothing uses the new dependencies yet). If this fails with a Kotlin-metadata or bytecode-version error like Task 5's original `:caldav` problems, STOP — this plan's core assumption (Ktor 2.3.12 needs none of that machinery) was wrong, and you need to investigate and report back with the real error rather than guessing a fix; do not silently re-add `-Xskip-metadata-version-check` or resurrect a module split without understanding why first.

- [ ] **Step 3: Write `VikunjaModels.kt`**

```kotlin
package com.erdman.erdtoday.vikunja

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.format.DateTimeParseException

/** RFC3339 <-> [Instant], as Vikunja (a Go server) encodes every timestamp field. */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Go's zero time.Time, the sentinel Vikunja sends for "unset" on fields with no `omitempty`. */
val VIKUNJA_ZERO_TIME: Instant = Instant.parse("0001-01-01T00:00:00Z")

@Serializable
data class VikunjaTaskJson(
    val id: Long = 0,
    val title: String,
    val done: Boolean = false,
    @Serializable(with = InstantIso8601Serializer::class)
    val done_at: Instant = VIKUNJA_ZERO_TIME,
    @Serializable(with = InstantIso8601Serializer::class)
    val due_date: Instant = VIKUNJA_ZERO_TIME,
    val project_id: Long = 0,
    @Serializable(with = InstantIso8601Serializer::class)
    val updated: Instant = VIKUNJA_ZERO_TIME,
)

@Serializable
data class VikunjaLabelJson(
    val id: Long = 0,
    val title: String,
)

@Serializable
data class VikunjaProjectJson(
    val id: Long = 0,
    val title: String,
)

@Serializable
data class VikunjaLabelTaskWriteJson(
    val label_id: Long,
)

data class VikunjaProject(val id: Long, val title: String)
data class VikunjaLabel(val id: Long, val title: String)
```

(`VikunjaTaskJson` is the wire type for both reading and writing a task; it deliberately omits `labels` — per the Global Constraints, that field is read-only on the Task object and never round-trips through a task create/update body. `description`/`start_date`/`priority`/etc. from the real Vikunja `Task` model are omitted entirely since they're not part of this app's sync scope and kotlinx.serialization ignores unknown JSON fields on decode by default *only* if `ignoreUnknownKeys = true` is set on the `Json` instance — set that explicitly in Step 4's `HttpClient` config, since Vikunja's real `Task` response has far more fields than this subset.)

- [ ] **Step 4: Write `VikunjaApiClient`**

```kotlin
package com.erdman.erdtoday.vikunja

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class VikunjaTaskWrite(
    val title: String,
    val dueDate: Instant?,
    val done: Boolean,
)

data class VikunjaTaskRead(
    val id: Long,
    val title: String,
    val dueDate: LocalDate?,
    val done: Boolean,
    val doneAt: Instant?,
    val updatedAt: Instant,
    val labelIds: List<Long>,
)

/** Talks to one self-hosted Vikunja instance's REST API using a scoped API token. */
class VikunjaApiClient(baseUrl: String, apiToken: String) {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false // we check status codes ourselves, not via exceptions
        defaultRequest {
            header("Authorization", "Bearer $apiToken")
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 20_000
        }
    }

    private val api = "$baseUrl/api/v1"

    suspend fun listProjects(): Result<List<VikunjaProject>> = runCatching {
        val resp = client.get("$api/projects")
        requireSuccess(resp)
        resp.body<List<VikunjaProjectJson>>().map { VikunjaProject(it.id, it.title) }
    }

    suspend fun createProject(title: String): Result<VikunjaProject> = runCatching {
        val resp = client.put("$api/projects") {
            contentType(ContentType.Application.Json)
            setBody(VikunjaProjectJson(title = title))
        }
        requireSuccess(resp)
        val json = resp.body<VikunjaProjectJson>()
        VikunjaProject(json.id, json.title)
    }

    suspend fun listTasks(projectId: Long): Result<List<VikunjaTaskRead>> = runCatching {
        val resp = client.get("$api/projects/$projectId/tasks")
        requireSuccess(resp)
        resp.body<List<VikunjaTaskJson>>().map { toRead(it) }
    }

    suspend fun createTask(projectId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead> = runCatching {
        val resp = client.put("$api/projects/$projectId/tasks") {
            contentType(ContentType.Application.Json)
            setBody(toWriteJson(task))
        }
        requireSuccess(resp)
        toRead(resp.body<VikunjaTaskJson>())
    }

    suspend fun updateTask(taskId: Long, task: VikunjaTaskWrite): Result<VikunjaTaskRead> = runCatching {
        val resp = client.post("$api/tasks/$taskId") {
            contentType(ContentType.Application.Json)
            setBody(toWriteJson(task))
        }
        requireSuccess(resp)
        toRead(resp.body<VikunjaTaskJson>())
    }

    suspend fun deleteTask(taskId: Long): Result<Unit> = runCatching {
        val resp = client.delete("$api/tasks/$taskId")
        requireSuccess(resp)
    }

    suspend fun listLabels(): Result<List<VikunjaLabel>> = runCatching {
        val resp = client.get("$api/labels")
        requireSuccess(resp)
        resp.body<List<VikunjaLabelJson>>().map { VikunjaLabel(it.id, it.title) }
    }

    suspend fun createLabel(title: String): Result<VikunjaLabel> = runCatching {
        val resp = client.put("$api/labels") {
            contentType(ContentType.Application.Json)
            setBody(VikunjaLabelJson(title = title))
        }
        requireSuccess(resp)
        val json = resp.body<VikunjaLabelJson>()
        VikunjaLabel(json.id, json.title)
    }

    suspend fun addLabelToTask(taskId: Long, labelId: Long): Result<Unit> = runCatching {
        val resp = client.put("$api/tasks/$taskId/labels") {
            contentType(ContentType.Application.Json)
            setBody(VikunjaLabelTaskWriteJson(label_id = labelId))
        }
        requireSuccess(resp)
    }

    suspend fun removeLabelFromTask(taskId: Long, labelId: Long): Result<Unit> = runCatching {
        val resp = client.delete("$api/tasks/$taskId/labels/$labelId")
        requireSuccess(resp)
    }

    private fun toWriteJson(task: VikunjaTaskWrite): VikunjaTaskJson = VikunjaTaskJson(
        title = task.title,
        done = task.done,
        due_date = task.dueDate ?: VIKUNJA_ZERO_TIME,
    )

    private fun toRead(json: VikunjaTaskJson): VikunjaTaskRead = VikunjaTaskRead(
        id = json.id,
        title = json.title,
        dueDate = if (json.due_date.atZone(ZoneOffset.UTC).year <= 1) null else json.due_date.atZone(ZoneOffset.UTC).toLocalDate(),
        done = json.done,
        doneAt = if (json.done_at.atZone(ZoneOffset.UTC).year <= 1) null else json.done_at,
        updatedAt = json.updated,
        labelIds = emptyList(), // labels are read-only on the task JSON -- see fetchLabelIds below
    )

    /** The task JSON's own `labels` field never round-trips (read-only on the server model) --
     *  fetch a task's current label ids from the dedicated endpoint when the caller needs them. */
    suspend fun fetchLabelIds(taskId: Long): Result<List<Long>> = runCatching {
        val resp = client.get("$api/tasks/$taskId/labels")
        requireSuccess(resp)
        resp.body<List<VikunjaLabelJson>>().map { it.id }
    }

    private fun requireSuccess(resp: HttpResponse) {
        check(resp.status.isSuccess()) { "Vikunja API ${resp.request.method.value} ${resp.request.url} failed: ${resp.status}" }
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299
}
```

Note the design gap flagged in `toRead`: the real Vikunja `Task` JSON's `labels` field is populated on read by the server (it's only *write*-side that's ignored, per the model comment "This property is read-only, you must use the separate endpoint to add labels to a task" — meaning read-only *for writing*, but the server DOES include current labels when *reading* a task). Verify this empirically in Step 6's live test: fetch a task that has a label attached and check whether the raw JSON response actually contains a populated `labels` array despite `VikunjaTaskJson` not declaring that field (kotlinx.serialization with `ignoreUnknownKeys = true` would silently drop it if present). If the live test shows labels DO come back on read, add a `labels: List<VikunjaLabelJson> = emptyList()` field to `VikunjaTaskJson` and populate `labelIds` directly from it in `toRead`, removing the need for the separate `fetchLabelIds` round-trip per task during a pull (a real efficiency win worth taking if it's available). If labels do NOT come back on a task read, keep `fetchLabelIds` as the fallback. Report which behavior you observed.

- [ ] **Step 5: Write `VikunjaTaskMapper`**

```kotlin
package com.erdman.erdtoday.vikunja

import com.erdman.erdtoday.data.local.TaskEntity
import java.time.Instant
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
```

- [ ] **Step 6: Write the mapper unit tests**

```kotlin
package com.erdman.erdtoday.vikunja

import com.erdman.erdtoday.data.local.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class VikunjaTaskMapperTest {

    @Test fun `toWrite maps title, deadline, and completed`() {
        val task = TaskEntity(id = 1, title = "Buy milk", deadline = LocalDate.of(2026, 9, 5), completed = true)
        val write = VikunjaTaskMapper.toWrite(task)
        assertEquals("Buy milk", write.title)
        assertEquals(LocalDate.of(2026, 9, 5).atStartOfDay(ZoneOffset.UTC).toInstant(), write.dueDate)
        assertEquals(true, write.done)
    }

    @Test fun `toWrite maps a null deadline to a null dueDate`() {
        val task = TaskEntity(id = 1, title = "No deadline", deadline = null)
        assertNull(VikunjaTaskMapper.toWrite(task).dueDate)
    }

    @Test fun `applyRead updates synced fields and preserves local-only fields`() {
        val local = TaskEntity(
            id = 1, title = "old title", notes = "keep me", deadline = null, completed = false,
            scheduledDate = LocalDate.of(2026, 9, 1),
        )
        val read = VikunjaTaskRead(
            id = 42, title = "new title from server", dueDate = LocalDate.of(2026, 9, 10),
            done = true, doneAt = Instant.parse("2026-09-01T12:00:00Z"),
            updatedAt = Instant.parse("2026-09-01T12:00:00Z"), labelIds = emptyList(),
        )
        val result = VikunjaTaskMapper.applyRead(read, local)
        assertEquals("new title from server", result.title)
        assertEquals(LocalDate.of(2026, 9, 10), result.deadline)
        assertEquals(true, result.completed)
        assertEquals(42L, result.vikunjaTaskId)
        assertEquals("keep me", result.notes) // local-only, preserved
        assertEquals(LocalDate.of(2026, 9, 1), result.scheduledDate) // local-only, preserved
    }
}
```

Run: `./gradlew test --tests "com.erdman.erdtoday.vikunja.VikunjaTaskMapperTest"`, then the full suite.

- [ ] **Step 7: Live verification against the real Vikunja instance**

This requires the user's real Vikunja server and API token. Ask the controller for them if they weren't already provided directly to you — do not guess or fabricate a token. **Security handling**: never write the API token into any file, commit message, code comment, log statement, or your reply — reference "the provided token" only.

Write a small, temporary throwaway test harness (NOT a checked-in file — e.g. run it via a scratch `main()` in a temporary file you delete afterward, or a temporary unit test you remove before committing) that: constructs a `VikunjaApiClient` with the real base URL/token, calls `listProjects()`, `createProject("ErdToday-verify-temp")`, `createTask(...)` with a due date, `listTasks(...)` to confirm it comes back, `createLabel(...)`, `addLabelToTask(...)`, then fetches the task again to check Step 4's open question about whether labels come back on read, then `deleteTask(...)` and manually delete the temp project via the Vikunja web UI or a `DELETE` you add temporarily (there's no `deleteProject` in this plan's client interface — that's fine, a stray verification-only project can just be deleted by hand afterward, or ask the user to do it). Confirm every call succeeds with no errors and the response shapes match what `VikunjaModels.kt` expects.

Report exactly what you verified, including the labels-on-read finding from Step 4, and remove all throwaway verification code/files before committing.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add VikunjaApiClient, VikunjaTaskMapper, and JSON models"
git push
```

---

### Task 5: SyncEngine — push and pull against Vikunja

**Files:**
- Create: `app/src/main/java/com/erdman/erdtoday/sync/SyncEngine.kt`
- Create: `app/src/main/java/com/erdman/erdtoday/vikunja/VikunjaProjectSetup.kt`
- Test: `app/src/test/java/com/erdman/erdtoday/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object VikunjaProjectSetup {
      suspend fun findOrCreateErdTodayProject(api: VikunjaApiClient): Result<Long>
  }
  sealed class SyncResult {
      data class Success(val pushed: Int, val pulled: Int) : SyncResult()
      data class Failure(val reason: String) : SyncResult()
  }
  class SyncEngine(
      private val taskDao: TaskDao,
      private val tagDao: TagDao,
      private val syncStateDao: SyncStateDao,
      private val api: VikunjaApiClient,
  ) {
      suspend fun sync(): SyncResult
  }
  ```
- Consumes: `TaskDao.tasksNeedingSync()`/`getTaskByVikunjaTaskId()` (Task 2), `VikunjaApiClient` (Task 4), `VikunjaTaskMapper.toWrite`/`applyRead` (Task 4), `SyncStateDao`/`SyncStateEntity.vikunjaProjectId` (Task 2).

**Design — deliberately simpler than the CalDAV-era conflict handling:** Vikunja's API has no ETags/conditional requests, so there's no server-side "reject if changed" mechanism to build a 412-retry loop around. This plan uses a simple in-app guard instead: **push first, then pull; on pull, only apply a remote task's state to a local row that is NOT currently `syncDirty`.** If a row is dirty when the pull phase reaches it, skip applying the remote version this cycle — the next `sync()` call's push phase will overwrite the server with the local edit anyway, so silently dropping a same-cycle pull for a dirty row can't lose data, it just means that row's remote-vs-local reconciliation happens one cycle later. This replaces the entire `resolveConflict`/`multiget`-on-409 mechanism the original CalDAV design needed.

**Why `SyncEngine` takes `TaskDao`/`TagDao`/`SyncStateDao` directly, not `TaskRepository`:** carried over from the original plan's own explicit ruling — sync is a protocol-level background concern, not UI-facing business logic. The cost is a small amount of duplicated tag-dedup logic in `resolveOrCreateTag`, an accepted tradeoff.

- [ ] **Step 1: Write `VikunjaProjectSetup`**

```kotlin
package com.erdman.erdtoday.vikunja

object VikunjaProjectSetup {

    private const val PROJECT_TITLE = "ErdToday"

    /** Finds the existing "ErdToday" project by title, or creates it if none exists yet. */
    suspend fun findOrCreateErdTodayProject(api: VikunjaApiClient): Result<Long> = runCatching {
        val existing = api.listProjects().getOrThrow().firstOrNull { it.title == PROJECT_TITLE }
        existing?.id ?: api.createProject(PROJECT_TITLE).getOrThrow().id
    }
}
```

- [ ] **Step 2: Write `SyncEngine.push()`**

```kotlin
package com.erdman.erdtoday.sync

import com.erdman.erdtoday.data.local.SyncStateEntity
import com.erdman.erdtoday.data.local.TagDao
import com.erdman.erdtoday.data.local.TaskDao
import com.erdman.erdtoday.data.local.TaskEntity
import com.erdman.erdtoday.vikunja.VikunjaApiClient
import com.erdman.erdtoday.vikunja.VikunjaProjectSetup
import com.erdman.erdtoday.vikunja.VikunjaTaskMapper

sealed class SyncResult {
    data class Success(val pushed: Int, val pulled: Int) : SyncResult()
    data class Failure(val reason: String) : SyncResult()
}

class SyncEngine(
    private val taskDao: TaskDao,
    private val tagDao: TagDao,
    private val syncStateDao: SyncStateDao,
    private val api: VikunjaApiClient,
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
}
```

**Note on `resolveOrCreateVikunjaLabel`/`syncLabels` cost**: this calls `listLabels()` and `fetchLabelIds()` once per synced task per cycle, which is O(n) API round-trips for an n-task push. Acceptable for a personal-scale task list (the spec's own stated scope); if this ever becomes a real bottleneck, caching `listLabels()` for the duration of one `sync()` call is the obvious follow-up — not required for this task.

- [ ] **Step 3: Write `SyncEngine.pull()`**

Add to the same class:
```kotlin
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
```

- [ ] **Step 4: Compile check**

```bash
./gradlew :app:compileDebugKotlin -q
```

- [ ] **Step 5: Write `SyncEngineTest`**

Follow `TaskRepositoryDirtyTrackingTest`'s existing fake-DAO convention (`FakeTaskDao`/`FakeTagDao` — reuse them if they're accessible from this test's package, or write minimal equivalents scoped to this test file if they're private to the other test's package). Add a `FakeSyncStateDao` (single mutable nullable field) and a `FakeVikunjaApiClient`-style test double for `VikunjaApiClient` — since `VikunjaApiClient` is a concrete class (not an interface) making real network calls, either (a) extract a minimal interface `VikunjaApi` that `VikunjaApiClient` implements and `SyncEngine` depends on, purely for testability, or (b) use a mocking library (`mockk`, already a project dependency) to stub `VikunjaApiClient`'s suspend methods. Prefer (a) if it's a small, clean change to `VikunjaApiClient`'s declaration (`class VikunjaApiClient(...) : VikunjaApi`) — it keeps the test file simpler and avoids `mockk`'s coroutine-mocking ceremony for ~10 methods. Use your judgment; either is acceptable, note which you chose and why in your report.

Test cases:
```
// push: a dirty task with no vikunjaTaskId gets created, vikunjaTaskId set, syncDirty cleared
// push: a dirty task with an existing vikunjaTaskId gets updated, not created
// push: a syncPendingDelete task with a vikunjaTaskId gets deleted server-side, then removed locally
// push: a syncPendingDelete task with no vikunjaTaskId (never synced) just gets removed locally, no API call
// push: tags on a task get synced as labels (resolve-or-create, add missing, remove stale)
// pull: a clean (non-dirty) local task gets updated from a remote task with a newer title/deadline/done
// pull: a DIRTY local task is NOT overwritten by a remote pull (the critical conflict-avoidance case)
// pull: a remote task with no local match creates a new local row
// sync: resolveProjectId reuses a stored vikunjaProjectId from SyncStateDao rather than calling findOrCreateErdTodayProject again
```

- [ ] **Step 6: Run the test suite**

```bash
./gradlew test
```
Expected: all new tests pass, no regressions.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add SyncEngine: push/pull against Vikunja, tag<->label sync, dirty-guard conflict avoidance"
git push
```

---

### Task 6: SyncWorker, AppContainer wiring, periodic scheduling, final on-device verification

**Files:**
- Create: `app/src/main/java/com/erdman/erdtoday/sync/SyncWorker.kt`
- Create: `app/src/main/java/com/erdman/erdtoday/sync/SyncScheduler.kt`
- Modify: `app/build.gradle.kts` (WorkManager dependency, if not already present — check first)
- Modify: `app/src/main/java/com/erdman/erdtoday/di/AppContainer.kt`

**Interfaces:**
- Produces: `SyncScheduler.schedulePeriodic(context)`, wired to run on app start whenever credentials are present. `AppContainer.database` becomes public (was `private val`) so `SyncWorker` can reach `.taskDao()`/`.tagDao()`/`.syncStateDao()` — same pattern the original plan's Task 7 specified.
- Consumes: `SyncEngine` (Task 5), `CredentialsManager` (Task 3), `MIGRATION_3_4` (Task 2, wired in here).

- [ ] **Step 1: Check for an existing WorkManager dependency**

```bash
grep -n "work-runtime" app/build.gradle.kts gradle/libs.versions.toml
```
If absent, add to `gradle/libs.versions.toml`:
```toml
workManager = "2.9.1"
```
```toml
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "workManager" }
```
And to `app/build.gradle.kts`'s `dependencies { }`:
```kotlin
    implementation(libs.androidx.work.runtime.ktx)
```

- [ ] **Step 2: Wire `MIGRATION_3_4` and make `database` public in `AppContainer`**

```kotlin
    val database: TodayDatabase = Room.databaseBuilder(
        appContext,
        TodayDatabase::class.java,
        "today.db",
    ).addMigrations(TodayDatabase.MIGRATION_1_2, TodayDatabase.MIGRATION_2_3, TodayDatabase.MIGRATION_3_4).build()
```
(Changed from `private val` to `val`, and both migrations added to the chain — read the current file first, since `MIGRATION_2_3` may or may not already be listed there depending on whether the superseded plan's Task 7 ever partially landed; it didn't, per the SDD ledger, so this is likely adding all three for the first time.)

- [ ] **Step 3: Write `SyncWorker`**

```kotlin
package com.erdman.erdtoday.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result as WorkResult
import com.erdman.erdtoday.TodayApp
import com.erdman.erdtoday.vikunja.VikunjaApiClient

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): WorkResult {
        val container = (applicationContext as TodayApp).container
        val credentials = container.credentialsManager.credentials.value ?: return WorkResult.success() // nothing to sync yet
        val api = VikunjaApiClient(credentials.baseUrl, credentials.apiToken)
        val engine = SyncEngine(
            taskDao = container.database.taskDao(),
            tagDao = container.database.tagDao(),
            syncStateDao = container.database.syncStateDao(),
            api = api,
        )
        return when (val result = engine.sync()) {
            is SyncResult.Success -> WorkResult.success()
            is SyncResult.Failure -> WorkResult.retry()
        }
    }
}
```

- [ ] **Step 4: Write `SyncScheduler`**

```kotlin
package com.erdman.erdtoday.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val WORK_NAME = "vikunja-periodic-sync"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
```

- [ ] **Step 5: Wire scheduling into `AppContainer`'s `init` block**

```kotlin
    init {
        applicationScope.launch {
            repository.deleteEmptyTasks()
            repository.pruneLogbook()
            repository.rescheduleAllReminders()
            if (credentialsManager.credentials.value != null) {
                SyncScheduler.schedulePeriodic(appContext)
            }
        }
    }
```
(Add the necessary `import com.erdman.erdtoday.sync.SyncScheduler`.) Note this only schedules periodic sync if credentials already exist at app-start time (e.g. app relaunch after setup). Also schedule it the moment credentials are first saved — check `AccountSetupViewModel.connect()` (Task 3) and add a call there too, e.g. by having `connect()` also invoke `SyncScheduler.schedulePeriodic(...)` (it'll need a `Context`; thread one through from `AccountSetupScreen`'s `appContainer()` call, or have `AppContainer` expose a small `fun onCredentialsSaved()` helper that both wires the scheduler and can be unit-tested more easily than a Composable-adjacent call site — use your judgment on the cleanest wiring given the actual current code, note your choice in your report).

- [ ] **Step 6: Build, install, full live sync verification on-device**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug -q
adb -s MK20250402537 uninstall com.erdman.erdtoday.debug
adb -s MK20250402537 install app/build/outputs/apk/debug/app-debug.apk
```
(Fresh install, not `-r` — this task's migration chain is now fully wired, but starting clean avoids any doubt about migration correctness muddying a first end-to-end sync test.)

Ask the controller for the real Vikunja server URL/API token if not already available to you (same security handling as Task 4: never log/write/echo it).

On-device:
1. Enter real credentials on the setup screen, tap Connect — confirm it switches to Today.
2. Create a task with a title, deadline, and a tag on-device. Trigger a sync (either wait for WorkManager's periodic schedule, or add a temporary manual trigger the same way Task 5's original CalDAV verification did — a button or a debug hook you strip before your final commit, same "temporary, not left disabled" discipline as before).
3. Check the Vikunja web UI (or the API directly) to confirm the task appears in the "ErdToday" project with the right title, due date, and label.
4. Mark the task done on-device, sync again, confirm it shows done on Vikunja.
5. Edit the task directly in the Vikunja web UI (change the title), trigger a pull-side sync, confirm the change appears on-device.
6. Delete the task on-device, sync, confirm it's gone from Vikunja.
7. `adb logcat -d` checked for `FATAL`/`AndroidRuntime`/`Room` errors throughout — none expected.

- [ ] **Step 7: Full test suite one more time**

```bash
./gradlew test
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add SyncWorker, periodic scheduling, wire migrations; Vikunja sync end-to-end verified"
git push
```

---
