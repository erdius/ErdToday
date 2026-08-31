# ErdToday — Fastmail CalDAV sync design

## Summary

A fork of [Yvdriel/mudita-today](https://github.com/Yvdriel/mudita-today) ("Today"),
an Apache-2.0-licensed Things3-style to-do app for the Mudita Kompakt, rebranded
as **ErdToday** to match David's other Kompakt apps. The fork adds two-way sync
of tasks with Fastmail over CalDAV (VTODO), using `dav4jvm` (bitfireAT's
open-source CalDAV library — the same one DAVx5 itself is built on) as a direct
client, independent of DAVx5.

Today's existing feature set — the Today/Upcoming/Anytime/Logbook views, tags,
and logbook auto-retention — is already complete and working. This fork does
not rebuild any of that; it adds a sync layer alongside it.

## Why fork Today instead of building fresh

An earlier version of this plan scoped a from-scratch minimal task app
(`ErdTasks`). That plan is superseded: Today already implements everything
that from-scratch plan wanted (the four views, a flat task list, due dates)
and more (tags, a logbook retention setting that already does exactly what
was being planned as new work), on the same tech stack as David's other
Kompakt apps (Kotlin, Compose, `com.mudita:MMD:1.0.0`, Room, manual DI).
Building fresh would have meant re-implementing working, polished code for
no benefit. The Apache-2.0 license permits forking freely.

## Why not go through DAVx5

(Carried over from the earlier `ErdTasks` spec, still the correct reasoning.)
DAVx5 doesn't expose synced tasks itself — it hands them to a separate task
app's own storage. Of the three it supports, only **OpenTasks** publishes a
real third-party-readable `ContentProvider` (`TaskContract`), and OpenTasks is
unmaintained; the two actively-maintained options (Tasks.org, jtx Board) don't
expose a documented provider. `dav4jvm` — the library DAVx5 itself is built
on — is published standalone, so ErdToday uses it directly against Fastmail's
CalDAV server, with no DAVx5/OpenTasks dependency at all.

## Scope

**Syncs to Fastmail**: `title`, `scheduledDate`, `deadline`, `completed`,
and `tags`. These map reasonably cleanly onto standard VTODO fields (see
"CalDAV field mapping" below).

**Stays local-only, not synced** (explicitly scoped out — CalDAV's VTODO
format has no clean standard representation for any of these, and none of it
was asked for): `notes`, the checklist, `recurrence`, `reminderTime`. All
four keep working exactly as they do in upstream Today today; this fork
doesn't touch that code.

**Not in scope**: multiple task lists/collections (one Fastmail VTODO
collection, matching the single local database), sharing, multi-account.

## What's already done (no new work needed)

Confirmed directly against the forked source, not assumed:
- **Today/Upcoming/Anytime/Logbook views**: `domain/DateLogic.kt` +
  `TaskRepository.observeView()`.
- **Tags**: `TagEntity`/`TaskTagCrossRef`, `TaskRepository`'s tag methods,
  tag filtering in `observeView()`.
- **Logbook retention**: `SettingsStore.logbookRetentionDays` (DataStore,
  default 0 = keep everything) → `TaskRepository.pruneLogbook()` → run once
  on every cold start (`AppContainer.kt` `init` block). Exposed in
  `SettingsScreen.kt`. This is exactly the "keep completed to-dos for N
  days" feature that was being scoped as new work before the fork
  decision — it isn't new work, it's already shipped.

## CalDAV field mapping

| `TaskEntity` field | VTODO property | Notes |
|---|---|---|
| `title` | `SUMMARY` | |
| `scheduledDate` | `DTSTART` (`VALUE=DATE`) | Today's "Anytime" (no date) maps to no `DTSTART` |
| `deadline` | `DUE` (`VALUE=DATE`) | |
| `completed` | `STATUS` | `COMPLETED` ↔ `true`, `NEEDS-ACTION` ↔ `false` |
| `completedAt` | `COMPLETED` | Only set when `STATUS:COMPLETED` |
| tags (via `TaskTagCrossRef`) | `CATEGORIES` | Comma-separated tag names, per RFC 5545 §3.8.1.2. A tag renamed locally renames it in every task's `CATEGORIES` on next push; a `CATEGORIES` value that doesn't match any local `TagEntity` on pull creates one (same case-insensitive dedup as `TaskRepository.createTag()`) |
| new field: `uid` | `UID` | Generated locally (random UUID) the first time a task is pushed; permanent after that |

Not mapped (local-only, per Scope above): `notes`, checklist, `recurrence`,
`reminderTime`, `sortOrder`.

## Local schema changes

`TaskEntity` gains sync-tracking fields, added via a Room migration
(`MIGRATION_2_3`, following the existing `MIGRATION_1_2` pattern in
`TodayDatabase.kt`):

| Field | Type | Purpose |
|---|---|---|
| `caldavUid` | `String?` | CalDAV `UID`; null until first successful push |
| `caldavHref` | `String?` | Resource path on the server; null until first successful push |
| `caldavEtag` | `String?` | ETag as of the last successful sync; null for a locally-created, not-yet-pushed task |
| `syncDirty` | `Boolean` (default `true`) | true if locally modified (or created) since the last successful push. Defaults true so every pre-existing local task syncs up on first run after upgrading. |
| `syncPendingDelete` | `Boolean` (default `false`) | true if deleted locally but the server `DELETE` hasn't succeeded yet |

A task with `notes`/checklist/`recurrence`/`reminderTime` changes only
(nothing in the synced-field list) shouldn't mark `syncDirty` — see
"Dirty tracking" below.

## Architecture

New packages alongside the existing ones, following the fork's own
established structure (`domain/`, `data/local`, `data/repo`, `di/`, `ui/`):

```
caldav/          dav4jvm-based CalDAV client: discovery, VTODO mapping (VTodoMapper,
                  pure functions, unit-testable), the sync engine itself.
data/credentials CredentialsManager — Fastmail email + app password, EncryptedSharedPreferences
                  (mirrors ErdStream's CredentialsManager, not DataStore — this is a secret,
                  unlike everything already in SettingsStore).
sync/            SyncWorker (WorkManager CoroutineWorker) + SyncScheduler.
```

**Dirty tracking**: rather than touching every existing `TaskRepository`
mutation method, add one repository method,
`private suspend fun markDirtyIfChanged(before: TaskEntity, after: TaskEntity)`,
called at the end of `setTitle`, `setScheduledDate`, `setDeadline`, and
`setTaskCompleted` — the four existing methods that touch a synced
`TaskEntity` field — comparing before/after on just `title`/`scheduledDate`/
`deadline`/`completed` and setting `syncDirty = true` only if one of them
actually changed. `setTaskTags` is handled separately, since tags live in
the `TaskTagCrossRef` join table, not on `TaskEntity`: it always sets
`syncDirty = true` unconditionally (the method already replaces the full
tag set on every call — `taskDao.clearTaskTags` then re-adds — so there's no
cheap before/after comparison available without an extra query, and calling
it always means "the tags changed" is a reasonable simplification). `setNotes`,
`setRecurrence`, checklist methods, and reminder methods are untouched.
`createTask` always sets `syncDirty = true` (new tasks always need a first
push). `captureAndDelete`/`deleteTask` set `syncPendingDelete = true` instead
of actually deleting the row when `caldavUid != null` (a task that's been
synced before needs its deletion pushed); a task that was never synced
(`caldavUid == null`) deletes immediately as today, nothing to push.

**Sync engine** (`caldav/SyncEngine.kt`), invoked by `SyncWorker`: same
push-then-pull algorithm as the original `ErdTasks` spec (preserved here
since the reasoning didn't change with the fork decision):

1. **Push**: every `syncDirty = true` row → build a VTODO body via
   `VTodoMapper`, `PUT` (with `If-Match` on the `caldavEtag` if present,
   `QuotedStringUtils.asQuotedString`-wrapped per dav4jvm's requirement) to
   `caldavHref`, or to a newly-allocated href under the collection if
   `caldavHref` is null (new task). On success, clear `syncDirty`, store the
   returned `caldavEtag`/`caldavHref`/`caldavUid`. Every
   `syncPendingDelete = true` row → `DELETE` at `caldavHref` with
   `If-Match`; on success or 404, delete the local row for real. Any other
   response leaves the row's flags untouched for a retry next cycle — both
   requests are safe to retry (PUT sends full current state, DELETE targets
   a specific ETag).
2. **Pull**: `DavCalendar.reportChanges()` (dav4jvm's `sync-collection`
   REPORT helper) with the stored sync-token; for each changed href, the
   returned `CalendarData` property carries the VTODO body directly (no
   separate `multiget` needed — request `CalDAV.CalendarData` in the
   `reportChanges` properties vararg). Parse via `VTodoMapper`, upsert into
   `TaskEntity` by `caldavUid` — unless that `caldavUid` is currently
   `syncDirty` or `syncPendingDelete` locally, in which case skip it this
   cycle (Push, next cycle, resolves it). For hrefs the REPORT reports
   deleted, delete the local row (unless `syncDirty`, meaning the user's
   local edit becomes a re-creation on the next Push).
3. **Conflict**: a `PreconditionFailedException` (412) during Push means the
   resource changed server-side since this cycle's view of it — fetch the
   current server version via `DavCalendar.multiget()` and overwrite local
   with it, discarding the conflicting local edit. Same "local wins until
   it collides, then server wins" rule as the original design.
4. **Sync-token rejected** (server returns a condition dav4jvm surfaces as
   requiring a full resync): drop the stored token, fall back to a full
   `propfind`/multiget listing of the collection, diff returned hrefs
   against local `caldavHref` values to find deletions.

**Trigger**: `SyncWorker` runs periodically via WorkManager (15-minute
minimum interval) and once via `OneTimeWorkRequest` right after any local
edit that sets `syncDirty` or `syncPendingDelete`, so pushes go out promptly
rather than waiting for the next periodic tick — same reasoning as the
original spec. Also run once at cold start, in `AppContainer`'s existing
`init` block, alongside `pruneLogbook()`/`rescheduleAllReminders()`.

## First-run collection setup

Same as the original `ErdTasks` spec — Fastmail's CalDAV server supports
VTODO but Fastmail's own web UI has no native task list, so a fresh account
has nothing to sync to. On first run (right after the user enters their
Fastmail email + app password), ErdToday:

1. Standard CalDAV bootstrap discovery, using dav4jvm's real, verified
   pattern (`DavResource(...).propfind(0, WebDAV.CurrentUserPrincipal)`
   against `https://<fastmail-caldav-host>/.well-known/caldav`, then a
   second `propfind` on the resolved principal for `CalDAV.CalendarHomeSet`)
   — this mirrors `DavResourceFinder.kt`'s real discovery code in DAVx5's
   own source, verified directly rather than guessed.
2. `propfind` the calendar-home-set (`Depth: 1`), checking each returned
   collection's `CalDAV.SupportedCalendarComponentSet` for `VTODO`. Use the
   first one found.
3. If none exists: `DavResource(...).mkCol(xmlBody = ..., methodName = "MKCALENDAR")`
   under the calendar-home-set, with the request body naming a `VTODO`-only
   collection called "ErdToday" — verified real XML shape (from DAVx5's own
   `DavCollectionRepository.kt`) to build with `XmlUtils.newSerializer()`,
   not guessed.

## Credentials

Fastmail app-specific password + account email, entered once on first run.
`CredentialsManager` (new class, `data/credentials/`), `EncryptedSharedPreferences`
— same pattern as ErdStream's `CredentialsManager`, deliberately not
`SettingsStore`/DataStore, since this is a secret and everything currently in
`SettingsStore` isn't.

## Dependency change: Ktor, not OkHttp

**Verified directly against dav4jvm's own source and DAVx5's real usage**,
not assumed: dav4jvm migrated from OkHttp to **Ktor** as of its 3.0.0
release, and its callback-based API became `suspend`/`Flow`-based as of
4.0.0. The classes ErdToday needs live under `at.bitfire.dav4jvm.ktor` and
`at.bitfire.dav4jvm.ktor.property.{caldav,webdav}`. This means ErdToday adds
a Ktor `HttpClient` dependency for the CalDAV layer specifically — Today's
existing code has no networking at all today, so this isn't replacing
anything, just a new dependency.

```kotlin
// settings.gradle.kts — dav4jvm is JitPack-distributed, not on Maven Central
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```
```kotlin
// app/build.gradle.kts
implementation("com.github.bitfireAT:dav4jvm:4.0.1")
// + a Ktor client engine (e.g. ktor-client-okhttp, since the app already
// runs on Android) and the Ktor Basic Auth plugin
```

The `HttpClient` must be configured with `followRedirects = false` — dav4jvm
handles redirects itself internally; this is explicitly required by its own
documentation, confirmed by direct source inspection.

## Rebranding

- `applicationId`/package: `com.erdman.erdtoday` (the existing
  `com.mosquishe.today` package is renamed throughout, following the same
  full-rename approach KofC6650Kompakt used — this fork changes enough
  behavior, via a whole new sync layer, that keeping the upstream package
  name would be confusing).
- App display name: "ErdToday".
- App icon: the existing monochrome rounded-square/checkmark icon is
  Today's own design, already solid-fill (no strokes, matches the Kompakt
  launcher's rendering constraint already documented from the ErdCal icon
  fix) — kept as-is unless David wants it changed later; not part of this
  fork's scope.
- New repo: `~/Projects/ErdToday`, pushed to a new `github.com/erdius/ErdToday`
  — a fork in the GitHub sense (repository history preserved from
  `Yvdriel/mudita-today`), not a from-scratch `git init`.
- Sideload-only distribution, matching every other personal Kompakt app —
  no store listing, no release-signing pipeline for this version.
- Apache-2.0 license and its NOTICE file (attributing Yoran van Driel's
  original work) are kept and carried forward, per the license's own
  requirements for a derivative work.

## Testing

Same split as the original `ErdTasks` spec, for the same reason (no live
CalDAV test server available for an automated suite; correctness can only
really be verified against Fastmail's real server):
- **Unit-testable, no network**: `VTodoMapper`'s VTODO text ↔ `TaskEntity`
  round-trip (the five synced fields + `CATEGORIES`↔tags), the dirty-tracking
  decision logic, the sync engine's conflict-resolution branching — all as
  pure functions over fixture data, following the existing fork's own
  pattern of unit-testing `domain/` logic (`DateLogic`, `Completion`,
  `Recurrence` already have tests to follow as examples).
- **On-device, against the real Fastmail account**: first-run collection
  discovery/creation, a full create → edit → complete → delete cycle
  confirmed via another CalDAV client (e.g. a raw `curl`/script check
  against the collection), the reverse (edit via another client, confirm
  ErdToday picks it up), the offline case (airplane mode, local edits,
  reconnect, confirm they push), and confirming `notes`/checklist/
  `recurrence`/`reminderTime` edits do *not* trigger a sync (per Scope).
