/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarViewMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.isRunningTimeEntry
import dev.tricked.solidverdant.ui.components.ConfirmDialog
import dev.tricked.solidverdant.ui.components.EditTimeEntryDialog
import dev.tricked.solidverdant.ui.components.ErrorState
import dev.tricked.solidverdant.ui.navigation.MainMenuButton
import dev.tricked.solidverdant.ui.navigation.MainTopBar
import dev.tricked.solidverdant.ui.theme.Dimens
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    organizationId: String,
    memberId: String,
    initialDate: LocalDate? = null,
    onInitialDateConsumed: () -> Unit = {},
    runningEntry: TimeEntry? = null,
    /**
     * Whether the tracker has a timer running or paused. A paused timer has no [runningEntry], so
     * without this the calendar would offer Continue, which cannot start while a timer is paused
     * and would overwrite the paused timer's details.
     */
    timerActive: Boolean = false,
    elapsedSeconds: StateFlow<Long>? = null,
    projects: List<Project>,
    clients: List<Client> = emptyList(),
    tasks: List<Task>,
    tags: List<Tag>,
    onSaveEntry: (TimeEntry, String?, String?, String?, List<String>, Boolean, String, String?) -> Unit,
    onMoveEntry: (TimeEntry, String, String) -> Unit = { _, _, _ -> },
    onCreateEntry: (String?, String?, String?, List<String>, Boolean, String, String) -> Unit = { _, _, _, _, _, _, _ -> },
    onCreateProject: ((String, String?, (Result<Project>) -> Unit) -> Unit)? = null,
    onCreateClient: ((String, (Result<Client>) -> Unit) -> Unit)? = null,
    onCreateTask: ((String, String, (Result<Task>) -> Unit) -> Unit)? = null,
    onCreateTag: ((String, (Result<Tag>) -> Unit) -> Unit)? = null,
    breaksEnabled: Boolean = false,
    onCreateBreakEntry: (String?, String, String) -> Unit = { _, _, _ -> },
    onDeleteEntry: (TimeEntry) -> Unit = {},
    onDuplicateEntry: (String) -> Unit = {},
    onSplitEntry: (String, String) -> Unit = { _, _ -> },
    onStopEntry: (TimeEntry) -> Unit = {},
    /** Start a new timer with the entry's details; offered only while no timer runs. */
    onContinueEntry: (TimeEntry) -> Unit = {},
    onUndoDelete: (TimeEntry) -> Unit = {},
    onRetrySyncEntry: (String) -> Unit = {},
    onDiscardFailedSync: (String) -> Unit = {},
    onOpenSyncCenter: () -> Unit = {},
    preventOverlap: Boolean = false,
    onBack: (() -> Unit)? = null,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    LaunchedEffect(organizationId, memberId) { viewModel.setOrganization(organizationId, memberId) }
    LaunchedEffect(initialDate) {
        initialDate?.let {
            viewModel.selectDate(it)
            onInitialDateConsumed()
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncOperationByEntryId = remember(state.syncOperations) { worstSyncOperationsByEntryId(state.syncOperations) }
    var editing by remember { mutableStateOf<TimeEntry?>(null) }
    var creatingRange by remember { mutableStateOf<CalendarTimeRange?>(null) }
    var creatingBreakRange by remember { mutableStateOf<CalendarTimeRange?>(null) }
    var contextEntry by remember { mutableStateOf<TimeEntry?>(null) }
    var splitTarget by remember { mutableStateOf<TimeEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<TimeEntry?>(null) }
    var deletedEntry by remember { mutableStateOf<TimeEntry?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val moveOverlapMessage = stringResource(R.string.entry_warning_overlap)
    val calendarEntries = remember(state.bucketsByDate) {
        state.bucketsByDate.values.asSequence()
            .flatMap { it.entries.asSequence() }
            .distinctBy { it.id }
            .toList()
    }
    val visibleRunningEntry = runningEntry?.takeIf(::isRunningTimeEntry)
    // Built once per status change rather than on every recomposition, so the grid can skip.
    val syncStatusByEntryId = remember(syncOperationByEntryId) { syncOperationByEntryId.mapValues { (_, operation) -> operation.status } }
    val moveEntryWithWarning: (TimeEntry, String, String) -> Unit = { entry, start, end ->
        if (calendarMoveOverlapsExisting(entry, start, end, calendarEntries)) {
            coroutineScope.launch { snackbarHostState.showSnackbar(moveOverlapMessage) }
        }
        onMoveEntry(entry, start, end)
    }
    // Progressive disclosure: the overlay controls live behind an app-bar toggle instead of a
    // persistent bar, so the default calendar keeps its full height for the grid.
    var showOverlaySheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? Activity
    // Read on resume and after a permission answer, the only times it can change, rather than on
    // every recomposition.
    var showRationale by remember { mutableStateOf(false) }
    val refreshRationale = { showRationale = activity?.let(::shouldShowCalendarRationale) == true }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Keep the overlay preference on regardless; the controls explain the current permission
        // state, so a denial surfaces a recovery path instead of silently disabling the toggle.
        viewModel.onCalendarPermissionChanged(granted)
        refreshRationale()
    }

    // Re-check the grant whenever the screen resumes (covers first composition and returning from
    // system settings), so a permission revoked or granted outside the app is reflected immediately.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onCalendarPermissionChanged(hasCalendarPermission(context))
                refreshRationale()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestPermission: () -> Unit = {
        viewModel.onPermissionRequested()
        permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
    }
    val openAppSettings: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
    val toggleOverlay: (Boolean) -> Unit = { want ->
        viewModel.setOverlayEnabled(want)
        if (want && !hasCalendarPermission(context)) requestPermission()
    }

    val entryDeletedMessage = stringResource(R.string.entry_deleted)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(deletedEntry) {
        val entry = deletedEntry ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = entryDeletedMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onUndoDelete(entry)
        deletedEntry = null
    }

    val openNewEntry = {
        creatingRange = defaultCalendarTimeRange(
            day = state.selectedDate,
            zone = state.zone,
            settings = state.calendarSettings,
        )
    }
    Scaffold(
        topBar = {
            CalendarTopBar(
                state = state,
                onBack = onBack,
                onToday = viewModel::jumpToToday,
                onModeSelected = viewModel::setViewMode,
                breaksEnabled = breaksEnabled,
                onAddBreak = {
                    creatingBreakRange = defaultCalendarTimeRange(
                        day = state.selectedDate,
                        zone = state.zone,
                        settings = state.calendarSettings,
                    )
                },
                onOpenOverlay = { showOverlaySheet = true },
                onOpenSettings = { showSettingsSheet = true },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = openNewEntry,
                shape = CircleShape,
                // A bright accent button rather than M3's tonal container.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag(CalendarTestTags.ADD_ENTRY),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_time_entry))
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            visibleRunningEntry?.let { entry ->
                CalendarRunningTimerCard(
                    entry = entry,
                    elapsedSeconds = elapsedSeconds,
                    onEdit = { editing = entry },
                    onStop = { onStopEntry(entry) },
                )
            }
            if (state.loadError && !state.isStale) {
                ErrorState(
                    text = stringResource(R.string.calendar_load_error),
                    onRetry = viewModel::retryLoad,
                    modifier = Modifier.weight(1f).testTag(CalendarTestTags.LOAD_ERROR),
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    if (state.loadError) {
                        ErrorState(
                            text = stringResource(R.string.calendar_load_error_cached),
                            onRetry = viewModel::retryLoad,
                            modifier = Modifier.testTag(CalendarTestTags.LOAD_ERROR),
                        )
                    }
                    CalendarBody(
                        state = state,
                        viewModel = viewModel,
                        projects = projects,
                        clients = clients,
                        tasks = tasks,
                        // A tap offers continue, edit, duplicate and delete.
                        onEntryClick = { contextEntry = it },
                        syncStatusByEntryId = syncStatusByEntryId,
                        onMoveEntry = moveEntryWithWarning,
                        onCreateRange = { creatingRange = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    contextEntry?.let { entry ->
        CalendarEntryActionsSheet(
            entry = entry,
            project = projects.firstOrNull { it.id == entry.projectId },
            task = tasks.firstOrNull { it.id == entry.taskId },
            client = projects.firstOrNull { it.id == entry.projectId }?.clientId?.let { clientId ->
                clients.firstOrNull { it.id == clientId }
            },
            syncOperation = syncOperationByEntryId[entry.id],
            onDismiss = { contextEntry = null },
            onContinue = if (canContinueCalendarEntry(entry, timerActive = timerActive || visibleRunningEntry != null)) {
                {
                    contextEntry = null
                    onContinueEntry(entry)
                }
            } else {
                null
            },
            onEdit = {
                contextEntry = null
                editing = entry
            },
            onDuplicate = {
                contextEntry = null
                onDuplicateEntry(entry.id)
            },
            onSplit = {
                contextEntry = null
                splitTarget = entry
            },
            onStop = {
                contextEntry = null
                onStopEntry(entry)
            },
            onDelete = {
                contextEntry = null
                deleteTarget = entry
            },
            onRetrySync = {
                contextEntry = null
                onRetrySyncEntry(entry.id)
            },
            onDiscardFailedSync = {
                contextEntry = null
                onDiscardFailedSync(entry.id)
            },
            onOpenSyncCenter = {
                contextEntry = null
                onOpenSyncCenter()
            },
        )
    }

    splitTarget?.let { entry ->
        CalendarSplitDialog(
            entry = entry,
            zone = state.zone,
            onDismiss = { splitTarget = null },
            onConfirm = { at ->
                splitTarget = null
                onSplitEntry(entry.id, at)
            },
        )
    }

    editing?.let { entry ->
        EditTimeEntryDialog(
            entry = entry,
            zone = state.zone,
            projects = projects,
            clients = clients,
            tasks = tasks,
            tags = tags,
            onDismiss = { editing = null },
            onSave = { desc, projectId, taskId, tagIds, billable, start, end ->
                onSaveEntry(entry, desc, projectId, taskId, tagIds, billable, start, end)
                editing = null
            },
            existingEntries = calendarEntries,
            preventOverlap = preventOverlap,
            isBreak = entry.type == TimeEntryType.BREAK,
            onCreateProject = onCreateProject,
            onCreateClient = onCreateClient,
            onCreateTask = onCreateTask,
            onCreateTag = onCreateTag,
            onDelete = {
                editing = null
                deleteTarget = entry
            },
        )
    }

    creatingRange?.let { range ->
        EditTimeEntryDialog(
            entry = null,
            zone = state.zone,
            projects = projects,
            clients = clients,
            tasks = tasks,
            tags = tags,
            suggestedStart = range.start,
            suggestedEnd = range.end,
            existingEntries = calendarEntries,
            preventOverlap = preventOverlap,
            onCreateProject = onCreateProject,
            onCreateClient = onCreateClient,
            onCreateTask = onCreateTask,
            onCreateTag = onCreateTag,
            onDismiss = { creatingRange = null },
            onSave = { desc, projectId, taskId, tagIds, billable, start, end ->
                end?.let {
                    onCreateEntry(desc, projectId, taskId, tagIds, billable, start, it)
                    creatingRange = null
                }
            },
        )
    }

    creatingBreakRange?.let { range ->
        EditTimeEntryDialog(
            entry = null,
            zone = state.zone,
            projects = emptyList(),
            clients = emptyList(),
            tasks = emptyList(),
            tags = emptyList(),
            suggestedStart = range.start,
            suggestedEnd = range.end,
            existingEntries = calendarEntries,
            preventOverlap = false,
            isBreak = true,
            onDismiss = { creatingBreakRange = null },
            onSave = { desc, _, _, _, _, start, end ->
                end?.let {
                    onCreateBreakEntry(desc, start, it)
                    creatingBreakRange = null
                }
            },
        )
    }

    if (showOverlaySheet) {
        CalendarOverlaySheet(
            state = state,
            showRationale = showRationale,
            onDismiss = { showOverlaySheet = false },
            onToggleOverlay = toggleOverlay,
            onRequestPermission = requestPermission,
            onOpenAppSettings = openAppSettings,
            onToggleCalendar = viewModel::toggleCalendarSelected,
            onRetry = viewModel::retryOverlay,
        )
    }

    if (showSettingsSheet) {
        CalendarSettingsSheet(
            settings = state.calendarSettings,
            onSettingsChanged = viewModel::updateCalendarSetting,
            onDismiss = { showSettingsSheet = false },
        )
    }

    deleteTarget?.let { entry ->
        val discard = entry.id.startsWith("local-")
        ConfirmDialog(
            title = stringResource(if (discard) R.string.calendar_discard_entry_title else R.string.calendar_delete_entry_title),
            message = stringResource(if (discard) R.string.calendar_discard_entry_message else R.string.calendar_delete_entry_message),
            confirmLabel = stringResource(if (discard) R.string.calendar_action_discard else R.string.delete),
            onConfirm = {
                onDeleteEntry(entry)
                deletedEntry = entry
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            destructive = true,
            confirmTestTag = CalendarTestTags.DELETE_CONFIRM,
            dismissTestTag = CalendarTestTags.DELETE_CANCEL,
        )
    }
}

/**
 * "Calendar" header with the side-menu button (or back when pushed) and the overflow menu:
 * Today, the Day / Week / Month views, then add break, the device-calendar overlay and settings.
 */
@Composable
private fun CalendarTopBar(
    state: CalendarUiState,
    onBack: (() -> Unit)?,
    onToday: () -> Unit,
    onModeSelected: (CalendarViewMode) -> Unit,
    breaksEnabled: Boolean,
    onAddBreak: () -> Unit,
    onOpenOverlay: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val choose: (() -> Unit) -> Unit = { action ->
        menuExpanded = false
        action()
    }
    MainTopBar(
        title = stringResource(R.string.nav_calendar),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag(CalendarTestTags.BACK)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.review_navigate_back),
                    )
                }
            } else {
                MainMenuButton()
            }
        },
        actions = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag(CalendarTestTags.MORE_ACTIONS),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.calendar_more_actions))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.calendar_today)) },
                        leadingIcon = { Icon(Icons.Default.Today, contentDescription = null) },
                        onClick = { choose(onToday) },
                    )
                    HorizontalDivider()
                    CALENDAR_MODES.forEach { (mode, labelRes) ->
                        val selected = state.viewMode == mode
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (mode) {
                                        CalendarViewMode.DAY -> Icons.Default.ViewDay
                                        CalendarViewMode.WEEK -> Icons.Default.ViewWeek
                                        CalendarViewMode.MONTH -> Icons.Default.CalendarViewMonth
                                    },
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = if (selected) {
                                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else {
                                null
                            },
                            onClick = { choose { onModeSelected(mode) } },
                            modifier = Modifier
                                .testTag(calendarModeTag(mode))
                                .semantics { this.selected = selected },
                        )
                    }
                    HorizontalDivider()
                    if (breaksEnabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.calendar_add_break)) },
                            leadingIcon = { Icon(Icons.Default.FreeBreakfast, contentDescription = null) },
                            modifier = Modifier.testTag(CalendarTestTags.ADD_BREAK_MENU),
                            onClick = { choose(onAddBreak) },
                        )
                    }
                    if (state.viewMode != CalendarViewMode.MONTH) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.calendar_overlay_settings)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = if (state.overlayEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            modifier = Modifier.testTag(CalendarTestTags.OVERLAY),
                            onClick = { choose(onOpenOverlay) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.calendar_settings)) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        modifier = Modifier.testTag(CalendarTestTags.SETTINGS),
                        onClick = { choose(onOpenSettings) },
                    )
                }
            }
        },
    )
}

private val CALENDAR_MODES = listOf(
    CalendarViewMode.DAY to R.string.calendar_view_day,
    CalendarViewMode.WEEK to R.string.calendar_view_week,
    CalendarViewMode.MONTH to R.string.calendar_view_month,
)

private fun calendarModeTag(mode: CalendarViewMode): String = when (mode) {
    CalendarViewMode.MONTH -> CalendarTestTags.MODE_MONTH
    CalendarViewMode.WEEK -> CalendarTestTags.MODE_WEEK
    CalendarViewMode.DAY -> CalendarTestTags.MODE_DAY
}

@Composable
private fun CalendarBody(
    state: CalendarUiState,
    viewModel: CalendarViewModel,
    projects: List<Project>,
    clients: List<Client>,
    tasks: List<Task>,
    onEntryClick: (TimeEntry) -> Unit,
    syncStatusByEntryId: Map<String, TimeEntryRepository.EntrySyncStatus>,
    onMoveEntry: (TimeEntry, String, String) -> Unit,
    onCreateRange: (CalendarTimeRange) -> Unit,
    modifier: Modifier,
) {
    val bodyModifier = modifier
        .fillMaxWidth()
        .then(if (!state.isLoading) Modifier.testTag(CalendarTestTags.CONTENT_READY) else Modifier)
    when (state.viewMode) {
        CalendarViewMode.MONTH -> MonthCalendarView(
            state = state,
            onSelectDate = viewModel::selectDate,
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onEntryClick = onEntryClick,
            syncStatusByEntryId = syncStatusByEntryId,
            onMoveEntry = onMoveEntry,
            onCreateRange = onCreateRange,
            projects = projects,
            tasks = tasks,
            clients = clients,
            modifier = bodyModifier,
        )

        else -> BoxWithConstraints(modifier = bodyModifier) {
            val availableWidth = maxWidth
            LaunchedEffect(availableWidth, state.viewMode) {
                if (state.viewMode == CalendarViewMode.WEEK) {
                    viewModel.setVisibleDayCount(
                        if (availableWidth < Dimens.NarrowCalendarWidth) NARROW_CALENDAR_DAYS else FULL_WEEK_DAYS,
                    )
                }
            }
            WeekCalendarView(
                state = state,
                onSelectDate = viewModel::selectDate,
                onEntryClick = onEntryClick,
                syncStatusByEntryId = syncStatusByEntryId,
                onMoveEntry = onMoveEntry,
                onCreateRange = onCreateRange,
                onPrevious = viewModel::pageBackward,
                onNext = viewModel::pageForward,
                projects = projects,
                tasks = tasks,
                clients = clients,
            )
        }
    }
}

private const val NARROW_CALENDAR_DAYS = 3
private const val FULL_WEEK_DAYS = 7

private fun hasCalendarPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

private fun shouldShowCalendarRationale(activity: Activity): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
