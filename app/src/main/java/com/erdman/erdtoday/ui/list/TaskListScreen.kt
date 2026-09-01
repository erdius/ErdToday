package com.erdman.erdtoday.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.erdman.erdtoday.di.appContainer
import com.erdman.erdtoday.di.viewModelCreator
import com.erdman.erdtoday.domain.TaskView
import com.erdman.erdtoday.ui.common.ConfirmSheet
import com.erdman.erdtoday.ui.common.EmptyState
import com.erdman.erdtoday.ui.common.TagFilterBar
import com.erdman.erdtoday.ui.common.TaskRow
import com.erdman.erdtoday.util.Dates
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.menus.DropdownMenuItemMMD
import com.mudita.mmd.components.menus.DropdownMenuMMD
import com.mudita.mmd.components.progress_indicator.LinearProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The four views share this one screen; behavior (grouping, FAB, default date) varies by [view]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskListScreen(
    view: TaskView,
    onOpenTask: (taskId: Long, defaultEpochDay: Long?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = appContainer()
    val vm: TaskListViewModel = viewModel(
        key = view.name,
        factory = viewModelCreator { TaskListViewModel(container.repository, view) },
    )
    val tasks by vm.tasks.collectAsState()
    val tags by vm.tags.collectAsState()
    val selectedTag by vm.selectedTagId.collectAsState()
    val today by vm.today.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // AppContainer.syncNow() just enqueues a one-time WorkManager job and returns immediately --
    // there's no "sync finished" signal to observe (see AppContainer.kt/SyncScheduler.kt), so the
    // refreshing indicator is shown for a short fixed duration rather than tracking the real job's
    // completion. The sync itself runs correctly in the background regardless of this timing.
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBarMMD(
                title = { TextMMD(title(view)) },
                actions = {
                    if (view == TaskView.LOGBOOK) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenuMMD(menuOpen, { menuOpen = false }) {
                            DropdownMenuItemMMD(
                                { TextMMD("Clear logbook") },
                                { menuOpen = false; showClearConfirm = true },
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
            if (view == TaskView.LOGBOOK) {
                TextFieldMMD(
                    value = searchQuery,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { TextMMD("Search logbook") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                )
                HorizontalDividerMMD()
            }

            if (tags.isNotEmpty()) {
                TagFilterBar(tags, selectedTag, vm::selectTag)
                HorizontalDividerMMD()
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    container.syncNow()
                    isRefreshing = true
                    refreshScope.launch {
                        delay(1_500)
                        isRefreshing = false
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                state = pullToRefreshState,
                indicator = {
                    // The stock PullToRefreshDefaults.Indicator is a smoothly spinning arc, which
                    // conflicts with this app's no-animation e-ink rule. MMD's own
                    // LinearProgressIndicatorMMD is determinate (its fill is driven directly by
                    // pull distance / refresh state, not a self-running animation loop), so it
                    // just redraws like any other e-ink content change -- pull down to see the
                    // label, release past the threshold to trigger a sync.
                    Column(
                        Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val label = when {
                            isRefreshing -> "Syncing…"
                            pullToRefreshState.distanceFraction >= 1f -> "Release to sync"
                            pullToRefreshState.distanceFraction > 0f -> "Pull to sync"
                            else -> ""
                        }
                        if (label.isNotEmpty()) {
                            TextMMD(label, fontSize = 13.sp)
                            LinearProgressIndicatorMMD(
                                progress = {
                                    if (isRefreshing) 1f else pullToRefreshState.distanceFraction.coerceIn(0f, 1f)
                                },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                },
            ) {
                if (tasks.isEmpty()) {
                    // Known limitation: PullToRefreshBox detects the pull gesture via nested
                    // scroll dispatched by a scrollable descendant. EmptyState has none, so pull-
                    // to-refresh isn't reachable while a view is fully empty (an earlier attempt to
                    // fix this by adding Modifier.verticalScroll here broke EmptyState's vertical
                    // centering -- its own internal fillMaxSize() chains after any modifier passed
                    // in, so it measured against verticalScroll's unbounded height and collapsed to
                    // content size). Not a functional loss: the four views this app actually shows
                    // day to day are the ones with tasks in them.
                    EmptyState(
                        if (view == TaskView.LOGBOOK && searchQuery.isNotBlank()) "No matches." else emptyMessage(view),
                    )
                } else {
                    // Deliberately a plain Compose LazyColumn here, not LazyColumnMMD. Verified
                    // on-device: LazyColumnMMD's own drag-to-scroll doesn't dispatch unconsumed
                    // scroll through the standard Modifier.nestedScroll chain (a debug probe on
                    // PullToRefreshState.distanceFraction showed it never leaving 0f while dragging
                    // over LazyColumnMMD, vs. reliably reaching 1.0+ and firing onRefresh with a
                    // stock LazyColumn in the same spot), so PullToRefreshBox's pull gesture can
                    // never reach it. This follows the same precedent as PullToRefreshBox itself
                    // (see design notes): fall back to plain Compose Foundation/Material3 where MMD
                    // has no equivalent -- MMD's custom scrollbar decoration is the visual cost.
                    // Also disable the default edge-stretch overscroll effect (Android 12+), which
                    // otherwise consumes the boundary drag locally for its own animation before it
                    // reaches PullToRefreshBox's nested-scroll connection -- fits the no-animation
                    // e-ink rule better than the stretch effect itself did, too.
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (view == TaskView.UPCOMING) {
                                tasks.groupBy { it.task.scheduledDate }.forEach { (date, group) ->
                                    if (date != null) {
                                        item(key = "header-$date") {
                                            TextMMD(
                                                Dates.relativeLabel(date, today),
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                            )
                                            HorizontalDividerMMD()
                                        }
                                    }
                                    items(group, key = { it.task.id }) { row(it, view, today, vm, onOpenTask) }
                                }
                            } else {
                                items(tasks, key = { it.task.id }) { row(it, view, today, vm, onOpenTask) }
                            }
                        }
                    }
                }
            }
        }

        if (view != TaskView.LOGBOOK) {
            FloatingActionButtonMMD(
                onClick = { onOpenTask(0L, defaultEpochDay(view, today)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New to-do")
            }
        }

        if (showClearConfirm) {
            ConfirmSheet(
                title = "Clear logbook?",
                message = "This permanently deletes every completed to-do.",
                confirmLabel = "Clear",
                onConfirm = { vm.clearLogbook(); showClearConfirm = false },
                onDismiss = { showClearConfirm = false },
            )
        }
    }
}

@Composable
private fun row(
    task: com.erdman.erdtoday.data.local.TaskWithDetails,
    view: TaskView,
    today: LocalDate,
    vm: TaskListViewModel,
    onOpenTask: (Long, Long?) -> Unit,
) {
    TaskRow(
        task = task,
        view = view,
        today = today,
        onToggle = { checked -> vm.setCompleted(task.task.id, checked) },
        onClick = { onOpenTask(task.task.id, null) },
        onToggleItem = { item, done -> vm.setItemDone(item, done) },
    )
    HorizontalDividerMMD()
}

private fun title(view: TaskView): String = when (view) {
    TaskView.TODAY -> "Today"
    TaskView.UPCOMING -> "Upcoming"
    TaskView.ANYTIME -> "Anytime"
    TaskView.LOGBOOK -> "Logbook"
}

private fun emptyMessage(view: TaskView): String = when (view) {
    TaskView.TODAY -> "Nothing for today.\nTap + to add a to-do."
    TaskView.UPCOMING -> "No scheduled to-dos yet."
    TaskView.ANYTIME -> "No undated to-dos.\nTap + to add one."
    TaskView.LOGBOOK -> "Completed to-dos will appear here."
}

private fun defaultEpochDay(view: TaskView, today: LocalDate): Long? = when (view) {
    TaskView.TODAY -> today.toEpochDay()
    TaskView.UPCOMING -> today.plusDays(1).toEpochDay()
    TaskView.ANYTIME -> null
    TaskView.LOGBOOK -> null
}
