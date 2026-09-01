package com.erdman.erdtoday.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.erdman.erdtoday.TodayApp
import com.erdman.erdtoday.data.local.TodayDatabase
import com.erdman.erdtoday.data.credentials.CredentialsManager
import com.erdman.erdtoday.data.local.TaskWithDetails
import com.erdman.erdtoday.data.repo.TaskRepository
import com.erdman.erdtoday.data.settings.SettingsStore
import com.erdman.erdtoday.reminder.AlarmReminderScheduler
import com.erdman.erdtoday.reminder.applyReminderChannel
import com.erdman.erdtoday.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** Manual dependency container (no Hilt). Built once in [TodayApp.onCreate]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Outlives any screen — used for fire-and-forget cleanup (e.g. discarding empty drafts). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: TodayDatabase = Room.databaseBuilder(
        appContext,
        TodayDatabase::class.java,
        "today.db",
    ).addMigrations(TodayDatabase.MIGRATION_1_2, TodayDatabase.MIGRATION_2_3, TodayDatabase.MIGRATION_3_4).build()

    val settings: SettingsStore = SettingsStore(appContext)

    val credentialsManager: CredentialsManager = CredentialsManager(appContext)

    val repository: TaskRepository = TaskRepository(
        taskDao = database.taskDao(),
        tagDao = database.tagDao(),
        settings = settings,
        reminderScheduler = AlarmReminderScheduler(appContext),
    )

    /** Emits a snapshot of a just-deleted to-do so the shell can offer an Undo snackbar. */
    val deletedTaskEvents = MutableSharedFlow<TaskWithDetails>(extraBufferCapacity = 1)

    /** Emits once per "Sync now" tap (Settings) so the shell can show a quick confirmation
     *  snackbar -- same event-flow-into-AppShell pattern as [deletedTaskEvents]. */
    val syncNowEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** (Re)create the reminder notification channel for the currently-chosen sound. */
    fun applyReminderChannel() = applicationScope.launch {
        applyReminderChannel(appContext, settings.reminderSoundValue())
    }

    /** Persist the reminder sound and rebuild the channel so new reminders use it. */
    fun setReminderSound(uri: String?) = applicationScope.launch {
        settings.setReminderSound(uri)
        applyReminderChannel(appContext, settings.reminderSoundValue())
    }

    /** Called once Vikunja credentials are first saved (account setup) so periodic sync starts
     *  immediately rather than waiting for the next app relaunch. Synchronous -- WorkManager's
     *  enqueue call needs no coroutine. */
    fun onCredentialsSaved() {
        SyncScheduler.schedulePeriodic(appContext)
    }

    /** Called when the user disconnects Vikunja from Settings: clears the stored credentials
     *  (which flips [MainActivity] back to [com.erdman.erdtoday.ui.accountsetup.AccountSetupScreen]
     *  via its existing collectAsState on [credentialsManager]) and cancels the periodic sync
     *  schedule so a stale worker doesn't keep firing against now-cleared credentials. */
    fun onCredentialsCleared() {
        credentialsManager.clear()
        SyncScheduler.cancelPeriodic(appContext)
    }

    /** Called from the "Sync now" action in Settings: enqueues an immediate one-time sync and
     *  signals [syncNowEvents] so the shell can show a quick confirmation snackbar. */
    fun syncNow() {
        SyncScheduler.syncNow(appContext)
        syncNowEvents.tryEmit(Unit)
    }

    init {
        applicationScope.launch {
            // Sweep blank drafts left over from a previous force-quit.
            repository.deleteEmptyTasks()
            // Trim the logbook to the retention window, if the user set one.
            repository.pruneLogbook()
            // Re-arm reminders: alarms are cleared on reboot, app update, and force-stop.
            repository.rescheduleAllReminders()
            if (credentialsManager.credentials.value != null) {
                SyncScheduler.schedulePeriodic(appContext)
            }
        }
    }
}

/** Convenience accessor for the app-wide [AppContainer] from any composable. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as TodayApp).container

/** Tiny [androidx.lifecycle.ViewModelProvider.Factory] from a constructor lambda (manual DI). */
inline fun <reified VM : androidx.lifecycle.ViewModel> viewModelCreator(
    crossinline make: () -> VM,
): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = make() as T
    }
