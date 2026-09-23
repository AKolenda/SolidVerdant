/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.data.repository.EntryTemplate
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.formatTimeEntryInstant
import dev.tricked.solidverdant.domain.time.isCompletedTimeEntry
import dev.tricked.solidverdant.domain.time.isRunningTimeEntry
import dev.tricked.solidverdant.domain.time.isWorkTimeEntry
import dev.tricked.solidverdant.domain.time.timeEntryLocalDaySlices
import dev.tricked.solidverdant.service.TimeTrackingNotificationService
import dev.tricked.solidverdant.ui.components.DestructiveActionRow
import dev.tricked.solidverdant.ui.components.EditTimeEntryTestTags
import dev.tricked.solidverdant.ui.components.EntryDatePickerDialog
import dev.tricked.solidverdant.ui.components.EntryDescriptionField
import dev.tricked.solidverdant.ui.components.EntryDurationRow
import dev.tricked.solidverdant.ui.components.EntrySheetHeader
import dev.tricked.solidverdant.ui.components.EntryTimeRow
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.GroupedSwitchRow
import dev.tricked.solidverdant.ui.components.SearchableSingleSelectDialog
import dev.tricked.solidverdant.ui.components.SectionCard
import dev.tricked.solidverdant.ui.components.SelectorStyle
import dev.tricked.solidverdant.ui.components.SyncChip
import dev.tricked.solidverdant.ui.components.TagsSelector
import dev.tricked.solidverdant.ui.components.retimedEnd
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.navigation.MainTopBar
import dev.tricked.solidverdant.ui.templates.FavoriteTemplatesRow
import dev.tricked.solidverdant.ui.templates.ManageTemplatesViewModel
import dev.tricked.solidverdant.ui.templates.TemplateDraft
import dev.tricked.solidverdant.ui.templates.TemplateResolver
import dev.tricked.solidverdant.ui.templates.templateDisplayLabel
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.util.NotificationPermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import dev.tricked.solidverdant.ui.components.ProjectTaskDropdown as SharedProjectTaskDropdown

/**
 * Tracking screen displaying current time tracking state and history
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@SuppressLint("InlinedApi")
@Composable
fun TrackingScreen(
    user: User?,
    currentMembership: Membership?,
    uiState: TrackingUiState,
    elapsedSeconds: StateFlow<Long> = MutableStateFlow(uiState.elapsedSeconds),
    autoClearEntryFieldsAfterStop: Boolean,
    longTimerHours: Int,
    editActiveEntryRequested: Boolean,
    onEditActiveEntryConsumed: () -> Unit,
    onRefresh: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onPauseTracking: () -> Unit,
    onResumeTracking: () -> Unit,
    onDescriptionChange: (String) -> Unit,
    onProjectChange: (String?) -> Unit,
    onTaskChange: (String?) -> Unit,
    onResetEntryFields: () -> Unit,
    onTagsChange: (List<String>) -> Unit,
    onBillableChange: (Boolean) -> Unit,
    onUpdatePastEntry: (TimeEntry, String?, String?, String?, List<String>, Boolean, String, String?) -> Unit,
    onCreateEntry: (String?, String?, String?, List<String>, Boolean, String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onDuplicateEntry: (String) -> Unit,
    onSplitEntry: (String, String) -> Unit,
    onEntryToEditConsumed: () -> Unit,
    onUndoDelete: (TimeEntry) -> Unit,
    onRetrySync: () -> Unit,
    onRetrySyncEntry: (String) -> Unit,
    onOpenSyncCenter: () -> Unit,
    onLoadMoreEntries: () -> Unit,
    onLoadNewerEntries: () -> Unit,
    onJumpToDate: (LocalDate) -> Unit,
    onHistoryJumpConsumed: () -> Unit,
    onClearError: () -> Unit = {},
) {
    var showEditDialog by remember { mutableStateOf<TimeEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var hasUserScrolledHistory by remember { mutableStateOf(false) }
    var calendarInitialDate by remember { mutableStateOf<LocalDate?>(null) }
    var historyFilter by remember { mutableStateOf(HistoryFilter()) }
    // Search lives behind the header's search button, not in the history list.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filterOptionsOpen by remember { mutableStateOf(false) }
    var deletedEntries by remember { mutableStateOf<List<TimeEntry>>(emptyList()) }
    // Entries waiting for the delete confirmation: one entry, or a whole stack.
    var pendingDelete by remember { mutableStateOf<List<TimeEntry>>(emptyList()) }
    val snackbarHostState = remember { SnackbarHostState() }
    var longTimerSnoozedUntil by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val templatesSavedMessage = stringResource(R.string.templates_saved)
    val entryDeletedMessage = stringResource(R.string.entry_deleted)
    val conflictEditLockedMessage = stringResource(R.string.sync_conflict_edit_locked)
    val undoLabel = stringResource(R.string.undo)
    val scope = rememberCoroutineScope()
    val historyListState = rememberLazyListState()
    val routineSyncInProgress = uiState.isLoading ||
        uiState.isRefreshing ||
        uiState.syncOperations.any { operation ->
            operation.status == TimeEntryRepository.EntrySyncStatus.PENDING ||
                operation.status == TimeEntryRepository.EntrySyncStatus.RETRYING
        }

    // Favorites & templates (gap analysis #1, #9). The template ViewModel resolves the current
    // organization itself; its catalogue includes archived/done items so availability can be shown.
    val templateViewModel: ManageTemplatesViewModel = hiltViewModel()
    val templateState by templateViewModel.uiState.collectAsState()
    val onSaveTemplateFromForm: (TemplateDraft) -> Unit = { draft ->
        templateViewModel.saveNewTemplate(draft)
        scope.launch { snackbarHostState.showSnackbar(templatesSavedMessage) }
    }

    LaunchedEffect(editActiveEntryRequested, uiState.currentTimeEntry) {
        if (editActiveEntryRequested) {
            val entry = uiState.currentTimeEntry ?: return@LaunchedEffect
            onEditActiveEntryConsumed()
            if (entry.id in uiState.conflictedEntryIds) {
                snackbarHostState.showSnackbar(conflictEditLockedMessage, withDismissAction = true)
            } else {
                showEditDialog = entry
            }
        }
    }

    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = message, withDismissAction = true)
        onClearError()
    }

    // Roadmap #13: after a duplicate/split the VM emits the new entry's id; open it for editing
    // once it surfaces in the observed list, then consume the one-shot signal.
    LaunchedEffect(uiState.entryToEditId, uiState.timeEntries) {
        val id = uiState.entryToEditId ?: return@LaunchedEffect
        uiState.timeEntries.firstOrNull { it.id == id }?.let {
            showEditDialog = it
            onEntryToEditConsumed()
        }
    }

    LaunchedEffect(historyFilter.startDate) {
        historyFilter.startDate?.let(onJumpToDate)
    }

    LaunchedEffect(deletedEntries) {
        val entries = deletedEntries.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = entryDeletedMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) entries.forEach(onUndoDelete)
        deletedEntries = emptyList()
    }
    val historyScrollConnection = remember(onLoadMoreEntries) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) {
                    hasUserScrolledHistory = true
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(historyListState, uiState.timeEntries.size, uiState.hasMoreTimeEntries) {
        snapshotFlow {
            val info = historyListState.layoutInfo
            hasUserScrolledHistory &&
                info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                info.totalItemsCount - HISTORY_PREFETCH_ITEMS
        }.filter { it }.collect { onLoadMoreEntries() }
    }
    LaunchedEffect(
        currentMembership?.organizationId,
        uiState.hasLoadedTimeEntries,
        uiState.hasMoreTimeEntries,
        uiState.timeEntries.size,
    ) {
        if (uiState.hasLoadedTimeEntries &&
            uiState.hasMoreTimeEntries &&
            uiState.timeEntries.size <= HISTORY_INITIAL_PREFETCH_MAX_ENTRIES
        ) {
            onLoadMoreEntries()
        }
    }
    LaunchedEffect(
        historyListState,
        uiState.timeEntries.size,
        uiState.canLoadNewerHistory,
    ) {
        snapshotFlow {
            hasUserScrolledHistory &&
                uiState.canLoadNewerHistory &&
                (
                    historyListState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                        ?: Int.MAX_VALUE
                    ) <= HISTORY_PREFETCH_ITEMS
        }.filter { it }.collect { onLoadNewerEntries() }
    }

    // Permission launcher for notification permission (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        // Permission result is handled, no action needed
    }

    // SV-014: Do not auto-request POST_NOTIFICATIONS when tracking starts — that force-prompts a
    // system dialog over the UI (breaks E2E robots, poor UX). The permission is instead requested
    // from explicit user actions: the "Enable notifications" app-bar action below, and the
    // "Always show notifications" toggle opt-in (see onCheckedChange on that Switch). The
    // persistent notification still shows normally if permission is already granted.

    val timerActive = uiState.isTracking || uiState.isPaused
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    var showStartTimerSheet by rememberSaveable { mutableStateOf(false) }
    // Starting from any surface (sheet, favourite, history play button, notification) closes the
    // start controls; the running timer then docks at the bottom.
    LaunchedEffect(timerActive) {
        if (timerActive) {
            fabExpanded = false
            showStartTimerSheet = false
        }
    }
    BackHandler(enabled = fabExpanded) { fabExpanded = false }
    val serverLastEntry = remember(uiState.timeEntries) {
        uiState.timeEntries
            .filter { isCompletedTimeEntry(it) && !it.description.isNullOrBlank() }
            .maxByOrNull { it.start }
    }
    val lastEntry = remember(serverLastEntry, uiState.cachedContinueEntry, currentMembership) {
        serverLastEntry ?: uiState.cachedContinueEntry?.takeIf {
            it.organizationId == currentMembership?.organizationId
        }
    }
    val continueEntry: (TimeEntry) -> Unit = { entry ->
        onDescriptionChange(entry.description ?: "")
        onProjectChange(entry.projectId)
        onTaskChange(entry.taskId)
        onTagsChange(entry.tags.map { it.id })
        onBillableChange(entry.billable)
        onStartTracking()
    }
    val showConflictLocked: () -> Unit = {
        scope.launch { snackbarHostState.showSnackbar(conflictEditLockedMessage, withDismissAction = true) }
    }
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TimeTrackerTopBar(
                    searchOpen = searchOpen,
                    onSearch = { searchOpen = true },
                    syncing = routineSyncInProgress,
                    onRefresh = onRefresh,
                    onRequestNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !NotificationPermissionHelper.hasNotificationPermission(context)
                    ) {
                        { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    } else {
                        null
                    },
                )
                if (searchOpen) {
                    HistorySearchBar(
                        filter = historyFilter,
                        onChange = { historyFilter = it },
                        onOpenOptions = { filterOptionsOpen = true },
                        onClose = {
                            searchOpen = false
                            historyFilter = HistoryFilter()
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (timerActive) {
                val elapsed by elapsedSeconds.collectAsState()
                ActiveTimerBar(
                    uiState = uiState,
                    elapsedSeconds = elapsed,
                    onStop = onStopTracking,
                    onPause = onPauseTracking,
                    onResume = onResumeTracking,
                    onEditActiveEntry = {
                        uiState.currentTimeEntry?.let { entry ->
                            if (entry.id in uiState.conflictedEntryIds) showConflictLocked() else showEditDialog = entry
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            TimerFab(
                timerActive = timerActive,
                expanded = fabExpanded,
                onExpandedChange = { fabExpanded = it },
                onStartTimer = { showStartTimerSheet = true },
                onAddManual = { showAddDialog = true },
                // Without the docked timer the button sits directly above the system navigation bar.
                modifier = if (timerActive) Modifier else Modifier.navigationBarsPadding(),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.padding(paddingValues),
        ) {
            val historyListItems by produceState(
                initialValue = emptyList<HistoryListItem>(),
                uiState.timeEntries,
                uiState.projects,
                uiState.tasks,
                uiState.clients,
                historyFilter,
                uiState.zone,
                uiState.firstDayOfWeek,
            ) {
                value = withContext(Dispatchers.Default) {
                    val filtered = EntryTrustRules.filter(
                        entries = uiState.timeEntries,
                        filter = historyFilter,
                        projects = uiState.projects,
                        tasks = uiState.tasks,
                        clients = uiState.clients,
                        syncOperations = uiState.syncOperations,
                        zone = uiState.zone,
                    )
                    val now = Instant.now()
                    buildHistoryListItems(
                        days = groupCompletedEntriesByLocalDay(filtered, uiState.zone, now),
                        firstDayOfWeek = uiState.firstDayOfWeek,
                        today = LocalDate.now(uiState.zone),
                        zone = uiState.zone,
                        now = now,
                    )
                }
            }
            // The Review checks, shown on each entry's card.
            val reviewIssues by produceState(emptyMap<String, Set<EntryReviewIssue>>(), uiState.timeEntries, longTimerHours) {
                value = withContext(Dispatchers.Default) {
                    EntryTrustRules.reviewIssues(uiState.timeEntries, Duration.ofHours(longTimerHours.toLong()))
                }
            }
            val historyProjectsById = remember(uiState.projects) { uiState.projects.associateBy { it.id } }
            val historyTasksById = remember(uiState.tasks) { uiState.tasks.associateBy { it.id } }
            val historyClientsById = remember(uiState.clients) { uiState.clients.associateBy { it.id } }
            val visibleSyncOperations = remember(uiState.syncOperations, uiState.syncStatusVisible) {
                if (uiState.syncStatusVisible) uiState.syncOperations else emptyList()
            }
            val syncStatusByEntryId = remember(visibleSyncOperations) {
                worstSyncStatusByEntryId(visibleSyncOperations)
            }
            val showSyncCenter = uiState.syncStatusVisible && uiState.syncOperations.isNotEmpty()
            val onHistoryEdit = remember(uiState.conflictedEntryIds) {
                { entry: TimeEntry ->
                    if (entry.id in uiState.conflictedEntryIds) showConflictLocked() else showEditDialog = entry
                }
            }
            val requestDelete = remember(uiState.conflictedEntryIds) {
                { entries: List<TimeEntry> ->
                    if (entries.any { it.id in uiState.conflictedEntryIds }) showConflictLocked() else pendingDelete = entries
                }
            }
            val onHistoryDelete = remember(requestDelete) { { entry: TimeEntry -> requestDelete(listOf(entry)) } }
            val onHistoryDateClick = remember<(LocalDate) -> Unit> { { date -> calendarInitialDate = date } }

            LaunchedEffect(uiState.historyJumpDate, historyListItems) {
                val target = uiState.historyJumpDate ?: return@LaunchedEffect
                val historyIndex = historyHeaderIndex(target, historyListItems)
                if (historyIndex >= 0) {
                    // The long-timer warning row, then the sync card when shown.
                    val leadingItemCount = 1 + (if (showSyncCenter) 1 else 0)
                    historyListState.scrollToItem(leadingItemCount + historyIndex)
                }
                onHistoryJumpConsumed()
            }

            val sectionInset = Modifier.fillMaxWidth().padding(horizontal = Dimens.Space16, vertical = Dimens.Space8)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CompositionLocalProvider(LocalLongEntryHours provides longTimerHours) {
                    LazyColumn(
                        state = historyListState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = Dimens.ContentMaxWidth)
                            .fillMaxWidth()
                            .testTag(TrackingTestTags.HISTORY_LIST)
                            .nestedScroll(historyScrollConnection),
                        contentPadding = PaddingValues(
                            bottom = Dimens.FabClearance + if (timerActive) 0.dp else navigationBarInset,
                        ),
                    ) {
                        item(key = "long_timer_warning") {
                            val elapsed by elapsedSeconds.collectAsState()
                            if (uiState.isTracking &&
                                elapsed >= longTimerHours * SECONDS_PER_HOUR_LONG &&
                                elapsed >= longTimerSnoozedUntil
                            ) {
                                LongTimerWarning(
                                    modifier = sectionInset,
                                    hours = longTimerHours,
                                    onStop = onStopTracking,
                                    onKeepRunning = {
                                        longTimerSnoozedUntil = elapsed + SECONDS_PER_HOUR_LONG
                                        TimeTrackingNotificationService.snoozeLongTimerWarning(context)
                                    },
                                    onAdjust = { uiState.currentTimeEntry?.let { showEditDialog = it } },
                                )
                            }
                        }
                        if (showSyncCenter) {
                            item(key = "sync_center") {
                                Box(sectionInset) {
                                    SyncCenter(uiState.syncOperations, onRetrySync, onRetrySyncEntry, onOpenSyncCenter)
                                }
                            }
                        }
                        trackingHistoryItems(
                            uiState = uiState,
                            historyItems = historyListItems,
                            projectsById = historyProjectsById,
                            tasksById = historyTasksById,
                            clientsById = historyClientsById,
                            syncStatusByEntryId = syncStatusByEntryId,
                            onEdit = onHistoryEdit,
                            onDelete = onHistoryDelete,
                            onDateClick = onHistoryDateClick,
                            onRetrySync = { onRetrySyncEntry(it.id) },
                            onContinue = continueEntry.takeIf { !timerActive },
                            onDuplicate = { onDuplicateEntry(it.id) },
                            onDeleteStack = requestDelete,
                            reviewIssues = reviewIssues,
                        )
                    }
                }
                if (fabExpanded) {
                    val closeLabel = stringResource(R.string.timer_fab_close)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = FAB_SCRIM_ALPHA))
                            .clickable(onClickLabel = closeLabel) { fabExpanded = false },
                    )
                }
            }
        }
    }

    if (filterOptionsOpen) {
        HistoryFiltersSheet(
            filter = historyFilter,
            uiState = uiState,
            onChange = { historyFilter = it },
            onDismiss = { filterOptionsOpen = false },
        )
    }

    if (showStartTimerSheet && !timerActive) {
        val startAndClose: (() -> Unit) -> Unit = { start ->
            showStartTimerSheet = false
            start()
        }
        StartTimerSheet(onDismiss = { showStartTimerSheet = false }) {
            StartTimerForm(
                uiState = uiState,
                onDescriptionChange = onDescriptionChange,
                onProjectChange = onProjectChange,
                onTaskChange = onTaskChange,
                onResetEntryFields = onResetEntryFields,
                autoClearEntryFieldsAfterStop = autoClearEntryFieldsAfterStop,
                onTagsChange = onTagsChange,
                onBillableChange = onBillableChange,
                onStart = { startAndClose(onStartTracking) },
            )
            val sheetInset = Modifier.fillMaxWidth().padding(horizontal = Dimens.Space16, vertical = Dimens.Space8)
            lastEntry?.let { entry ->
                Box(sheetInset) {
                    ContinueLastEntryButton(
                        entry = entry,
                        projects = uiState.projects,
                        onContinue = { startAndClose { continueEntry(entry) } },
                    )
                }
            }
            if (templateState.quickStart.isNotEmpty()) {
                Box(sheetInset) {
                    FavoriteTemplatesRow(
                        templates = templateState.quickStart,
                        projects = templateState.projects,
                        tasks = templateState.tasks,
                        tags = templateState.tags,
                        onStart = { start ->
                            startAndClose {
                                onDescriptionChange(start.description ?: "")
                                onProjectChange(start.projectId)
                                onTaskChange(start.taskId)
                                onTagsChange(start.tagIds)
                                onBillableChange(start.billable)
                                onStartTracking()
                            }
                        },
                    )
                }
            }
        }
    }

    if (pendingDelete.isNotEmpty()) {
        val entries = pendingDelete
        DeleteEntriesDialog(
            count = entries.size,
            onConfirm = {
                pendingDelete = emptyList()
                deletedEntries = entries
                entries.forEach { onDeleteEntry(it.id) }
            },
            onDismiss = { pendingDelete = emptyList() },
        )
    }

    // Edit dialog
    showEditDialog?.let { entry ->
        TimeEntryFormSheet(
            entry = entry,
            zone = uiState.zone,
            suggestedStart = null,
            projects = uiState.projects,
            tasks = uiState.tasks,
            tags = uiState.tags,
            onDismiss = { showEditDialog = null },
            onSave = { description, projectId, taskId, tagIds, billable, start, end ->
                onUpdatePastEntry(entry, description, projectId, taskId, tagIds, billable, start, end)
                showEditDialog = null
            },
            existingEntries = uiState.timeEntries,
            preventOverlap = currentMembership?.organization?.preventOverlappingTimeEntries == true,
            templates = templateState.templates,
            onSaveAsTemplate = onSaveTemplateFromForm,
            saveEnabled = entry.id !in uiState.conflictedEntryIds,
            onDuplicate = { onDuplicateEntry(entry.id) },
            onSplit = { atIso -> onSplitEntry(entry.id, atIso) },
            onDelete = {
                deletedEntries = listOf(entry)
                onDeleteEntry(entry.id)
            },
        )
    }

    // Add-entry dialog
    if (showAddDialog) {
        val suggestedStart = remember(uiState.timeEntries, uiState.zone) {
            val now = ZonedDateTime.now(uiState.zone)
            uiState.timeEntries
                .mapNotNull { it.end }
                .mapNotNull { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME) }.getOrNull() }
                .maxOrNull()
                ?.withZoneSameInstant(uiState.zone)
                ?.takeIf { it.toLocalDate() == now.toLocalDate() && it.isBefore(now) }
                ?: now.minusHours(1)
        }
        TimeEntryFormSheet(
            entry = null,
            zone = uiState.zone,
            suggestedStart = suggestedStart,
            projects = uiState.projects,
            tasks = uiState.tasks,
            tags = uiState.tags,
            existingEntries = uiState.timeEntries,
            preventOverlap = currentMembership?.organization?.preventOverlappingTimeEntries == true,
            onDismiss = { showAddDialog = false },
            onSave = { description, projectId, taskId, tagIds, billable, start, end ->
                end?.let {
                    onCreateEntry(description, projectId, taskId, tagIds, billable, start, it)
                    showAddDialog = false
                }
            },
            templates = templateState.templates,
            onSaveAsTemplate = onSaveTemplateFromForm,
        )
    }

    calendarInitialDate?.let { initialDate ->
        androidx.compose.runtime.key(initialDate) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { calendarInitialDate = null },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            onJumpToDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        calendarInitialDate = null
                    }) { Text(stringResource(R.string.jump_to_date)) }
                },
                dismissButton = {
                    TextButton(onClick = { calendarInitialDate = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            ) { DatePicker(state = datePickerState) }
        }
    }

    uiState.historyJumpTarget?.let { targetDate ->
        Dialog(onDismissRequest = { }) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.finding_date_entries,
                            formatDate(targetDate, LocalContext.current, uiState.zone, appLocale()),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(
                        progress = { uiState.historyJumpProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = uiState.historyRateLimitWaitSeconds?.let { seconds ->
                            pluralStringResource(R.plurals.rate_limit_wait, seconds, seconds)
                        } ?: stringResource(R.string.finding_date_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** How many search options beyond the text query are active; shown on the filter button. */
private fun HistoryFilter.activeOptionsCount(): Int = listOfNotNull(
    billable, runningOnly.takeIf { it },
    syncStatus, startDate, endDate, clientId, projectId, taskId, tagId,
    missingProjectOnly.takeIf { it }, missingDescriptionOnly.takeIf { it },
    needsCategorization.takeIf { it },
).size

/**
 * The search bar under the Time Tracker header, opened from its search button: the query, a filter
 * button (with the active option count) that opens the options sheet, and close, which clears both.
 */
@Composable
private fun HistorySearchBar(filter: HistoryFilter, onChange: (HistoryFilter) -> Unit, onOpenOptions: () -> Unit, onClose: () -> Unit) {
    val activeOptions = filter.activeOptionsCount()
    val transparent = Color.Transparent
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Dimens.Space4, end = Dimens.Space4, bottom = Dimens.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = filter.query,
                onValueChange = { onChange(filter.copy(query = it)) },
                placeholder = { Text(stringResource(R.string.search_history)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = transparent,
                    unfocusedContainerColor = transparent,
                    focusedIndicatorColor = transparent,
                    unfocusedIndicatorColor = transparent,
                ),
                modifier = Modifier.weight(1f).testTag(TrackingTestTags.FILTER_SEARCH_FIELD),
            )
            IconButton(onClick = onOpenOptions, modifier = Modifier.testTag(TrackingTestTags.FILTER_OPEN_BUTTON)) {
                BadgedBox(badge = { if (activeOptions > 0) Badge { Text(activeOptions.toString()) } }) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = if (activeOptions > 0) {
                            pluralStringResource(R.plurals.active_filters_count, activeOptions, activeOptions)
                        } else {
                            stringResource(R.string.search_options)
                        },
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag(TrackingTestTags.SEARCH_CLOSE_BUTTON)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
            }
        }
    }
}

/** The search options in a sheet: status, date and categorisation chips, then catalogue pickers. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
private fun HistoryFiltersSheet(
    filter: HistoryFilter,
    uiState: TrackingUiState,
    onChange: (HistoryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var showDateRangePicker by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.Space16)
                .padding(bottom = Dimens.Space24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            Text(
                text = stringResource(R.string.search_options),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter.billable == true,
                    onClick = { onChange(filter.copy(billable = if (filter.billable == true) null else true)) },
                    label = { Text(stringResource(R.string.billable)) },
                )
                FilterChip(
                    selected = filter.billable == false,
                    onClick = { onChange(filter.copy(billable = if (filter.billable == false) null else false)) },
                    label = { Text(stringResource(R.string.non_billable)) },
                )
                FilterChip(
                    selected = filter.runningOnly,
                    onClick = { onChange(filter.copy(runningOnly = !filter.runningOnly)) },
                    label = { Text(stringResource(R.string.running_entries)) },
                )
                FilterChip(
                    selected = filter.syncStatus == TimeEntryRepository.EntrySyncStatus.FAILED,
                    onClick = {
                        val toggled = if (filter.syncStatus == TimeEntryRepository.EntrySyncStatus.FAILED) {
                            null
                        } else {
                            TimeEntryRepository.EntrySyncStatus.FAILED
                        }
                        onChange(filter.copy(syncStatus = toggled))
                    },
                    label = { Text(stringResource(R.string.sync_failed)) },
                )
                val today = LocalDate.now(uiState.zone)
                FilterChip(
                    selected = filter.startDate == today && filter.endDate == today,
                    onClick = { onChange(filter.copy(startDate = today, endDate = today)) },
                    label = { Text(stringResource(R.string.today)) },
                )
                FilterChip(
                    selected = filter.startDate == today.minusDays(LAST_7_DAYS_OFFSET) && filter.endDate == today,
                    onClick = { onChange(filter.copy(startDate = today.minusDays(LAST_7_DAYS_OFFSET), endDate = today)) },
                    label = { Text(stringResource(R.string.stats_last_7_days)) },
                )
                FilterChip(
                    selected = filter.startDate != null &&
                        filter.endDate != null &&
                        !(filter.startDate == today && filter.endDate == today) &&
                        !(filter.startDate == today.minusDays(LAST_7_DAYS_OFFSET) && filter.endDate == today),
                    onClick = { showDateRangePicker = true },
                    label = { Text(stringResource(R.string.stats_custom)) },
                )
                FilterChip(
                    selected = filter.needsCategorization,
                    onClick = { onChange(filter.copy(needsCategorization = !filter.needsCategorization)) },
                    label = { Text(stringResource(R.string.needs_categorization)) },
                )
                FilterChip(
                    selected = filter.missingProjectOnly,
                    onClick = { onChange(filter.copy(missingProjectOnly = !filter.missingProjectOnly)) },
                    label = { Text(stringResource(R.string.without_project)) },
                )
                FilterChip(
                    selected = filter.missingDescriptionOnly,
                    onClick = { onChange(filter.copy(missingDescriptionOnly = !filter.missingDescriptionOnly)) },
                    label = { Text(stringResource(R.string.without_description)) },
                )
            }
            FilterDropdown(
                label = stringResource(R.string.client),
                selectedId = filter.clientId,
                options = uiState.clients.map { it.id to it.name },
                onSelect = { onChange(filter.copy(clientId = it, projectId = null, taskId = null)) },
            )
            FilterDropdown(
                label = stringResource(R.string.project),
                selectedId = filter.projectId,
                options = uiState.projects
                    .filter { filter.clientId == null || it.clientId == filter.clientId }
                    .map { it.id to it.name },
                onSelect = { onChange(filter.copy(projectId = it, taskId = null)) },
            )
            if (filter.projectId != null) {
                FilterDropdown(
                    label = stringResource(R.string.task),
                    selectedId = filter.taskId,
                    options = uiState.tasks.filter { it.projectId == filter.projectId }.map { it.id to it.name },
                    onSelect = { onChange(filter.copy(taskId = it)) },
                )
            }
            FilterDropdown(
                label = stringResource(R.string.tags),
                selectedId = filter.tagId,
                options = uiState.tags.map { it.id to it.name },
                onSelect = { onChange(filter.copy(tagId = it)) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Dimens.Space8),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Space8, Alignment.End),
            ) {
                if (filter.activeOptionsCount() > 0) {
                    TextButton(onClick = { onChange(HistoryFilter(query = filter.query)) }) {
                        Text(stringResource(R.string.clear_filters))
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.testTag(TrackingTestTags.FILTER_CLOSE_BUTTON)) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
    if (showDateRangePicker) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filter.startDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = filter.endDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDateRangePicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis?.let(::utcDateOf)
                        val end = pickerState.selectedEndDateMillis?.let(::utcDateOf)
                        if (start != null && end != null) onChange(filter.copy(startDate = start, endDate = end))
                        showDateRangePicker = false
                    },
                ) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = {
                TextButton(onClick = { showDateRangePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DateRangePicker(state = pickerState) }
    }
}

@Composable
private fun FilterDropdown(label: String, selectedId: String?, options: List<Pair<String, String>>, onSelect: (String?) -> Unit) {
    if (options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    FilterChip(
        selected = selectedId != null,
        onClick = { expanded = true },
        label = {
            Text(
                options.firstOrNull { it.first == selectedId }?.second ?: label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
    if (expanded) {
        SearchableSingleSelectDialog(
            title = label,
            searchPlaceholder = stringResource(R.string.search_items, label),
            allLabel = stringResource(R.string.all_items, label),
            options = options,
            selectedId = selectedId,
            onSelect = onSelect,
            onDismiss = { expanded = false },
        )
    }
}

@Composable
private fun SyncCenter(
    operations: List<TimeEntryRepository.SyncOperation>,
    onRetry: () -> Unit,
    onRetryEntry: (String) -> Unit,
    onOpenSyncCenter: () -> Unit,
) {
    val failed = operations.count { it.status == TimeEntryRepository.EntrySyncStatus.FAILED }
    val retrying = operations.count { it.status == TimeEntryRepository.EntrySyncStatus.RETRYING }
    val conflicts = operations.count { it.status == TimeEntryRepository.EntrySyncStatus.CONFLICT }
    val summaryStatus = when {
        failed > 0 -> TimeEntryRepository.EntrySyncStatus.FAILED
        conflicts > 0 -> TimeEntryRepository.EntrySyncStatus.CONFLICT
        retrying > 0 -> TimeEntryRepository.EntrySyncStatus.RETRYING
        else -> TimeEntryRepository.EntrySyncStatus.PENDING
    }
    SectionCard(
        modifier = Modifier.testTag(TrackingTestTags.SYNC_STATUS_CARD),
        title = stringResource(R.string.sync_status_card_title),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
            ) {
                SyncChip(status = summaryStatus, showLabel = false)
                Text(
                    when {
                        failed > 0 -> pluralStringResource(R.plurals.sync_failed_count, failed, failed)
                        conflicts > 0 -> pluralStringResource(R.plurals.sync_conflict_count, conflicts, conflicts)
                        retrying > 0 -> pluralStringResource(R.plurals.sync_retrying_count, retrying, retrying)
                        else -> pluralStringResource(R.plurals.sync_pending_count, operations.size, operations.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed > 0 || retrying > 0) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        operations.filter { it.status == TimeEntryRepository.EntrySyncStatus.FAILED }.forEach { operation ->
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
            ) {
                Text(
                    stringResource(R.string.sync_entry_failed),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { onRetryEntry(operation.entryId) }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
        HorizontalDivider()
        TextButton(
            onClick = onOpenSyncCenter,
            modifier = Modifier.fillMaxWidth().testTag(TrackingTestTags.SYNC_DETAILS_BUTTON),
        ) {
            Text(stringResource(R.string.sync_center_open))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LongTimerWarning(
    hours: Int,
    onStop: () -> Unit,
    onKeepRunning: () -> Unit,
    onAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                pluralStringResource(R.plurals.timer_running_long, hours, hours),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onStop) { Text(stringResource(R.string.stop_now)) }
                TextButton(onClick = onKeepRunning) { Text(stringResource(R.string.keep_running)) }
                TextButton(onClick = onAdjust) { Text(stringResource(R.string.adjust_end_time)) }
            }
        }
    }
}

private const val HISTORY_PREFETCH_ITEMS = 75
private const val HISTORY_INITIAL_PREFETCH_MAX_ENTRIES = 250

@Suppress("LongParameterList")
internal fun LazyListScope.trackingHistoryItems(
    uiState: TrackingUiState,
    historyItems: List<HistoryListItem>,
    projectsById: Map<String, Project>,
    tasksById: Map<String, Task>,
    clientsById: Map<String, Client>,
    syncStatusByEntryId: Map<String, TimeEntryRepository.EntrySyncStatus>,
    onEdit: (TimeEntry) -> Unit,
    onDelete: (TimeEntry) -> Unit,
    onDateClick: (LocalDate) -> Unit,
    onRetrySync: (TimeEntry) -> Unit = {},
    onContinue: ((TimeEntry) -> Unit)? = null,
    onDuplicate: ((TimeEntry) -> Unit)? = null,
    onDeleteStack: ((List<TimeEntry>) -> Unit)? = null,
    reviewIssues: Map<String, Set<EntryReviewIssue>> = emptyMap(),
) {
    if (!uiState.hasLoadedTimeEntries && uiState.timeEntries.isEmpty()) {
        item(key = "history_loading_header") { HistoryLoadingHeader() }
        repeat(HISTORY_PLACEHOLDER_COUNT) { index ->
            item(key = "history_loading_$index") { HistoryLoadingEntry(index) }
        }
        return
    }

    items(
        items = historyItems,
        key = { it.key },
        contentType = {
            when (it) {
                is HistoryListItem.Week -> "history_week"
                is HistoryListItem.Header -> "history_header"
                is HistoryListItem.Group -> "history_group"
            }
        },
    ) { historyItem ->
        when (historyItem) {
            is HistoryListItem.Week -> HistoryWeekHeader(week = historyItem)
            is HistoryListItem.Header -> HistoryDayHeader(
                day = historyItem.day,
                zone = uiState.zone,
                onClick = { onDateClick(historyItem.day.date) },
            )
            is HistoryListItem.Group -> {
                val project = projectsById[historyItem.lead.projectId]
                HistoryEntryCard(
                    group = historyItem,
                    zone = uiState.zone,
                    project = project,
                    task = tasksById[historyItem.lead.taskId],
                    client = project?.clientId?.let(clientsById::get),
                    syncStatusByEntryId = syncStatusByEntryId,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onDeleteStack = onDeleteStack,
                    reviewIssues = reviewIssues,
                    onDuplicate = onDuplicate,
                    onRetrySync = onRetrySync,
                    onContinue = onContinue,
                )
            }
        }
    }

    if (uiState.hasMoreTimeEntries || uiState.isLoadingMoreTimeEntries) {
        item(key = "history_pagination") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                uiState.totalTimeEntries?.let { total ->
                    Text(
                        text = "  ${uiState.timeEntries.size} / $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (uiState.timeEntries.isEmpty() && uiState.hasLoadedTimeEntries && !uiState.isLoading) {
        item {
            Text(
                text = stringResource(R.string.no_time_entries),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}

/** Compatibility entry point for previews and screenshot tests that provide grouped history. */
internal fun LazyListScope.trackingHistoryItems(
    uiState: TrackingUiState,
    groupedEntries: Map<LocalDate, List<TimeEntry>>,
    onEdit: (TimeEntry) -> Unit,
    onDelete: (TimeEntry) -> Unit,
    onDateClick: (LocalDate) -> Unit,
    onRetrySync: (TimeEntry) -> Unit = {},
    onContinue: ((TimeEntry) -> Unit)? = null,
    onDuplicate: ((TimeEntry) -> Unit)? = null,
    onDeleteStack: ((List<TimeEntry>) -> Unit)? = null,
    reviewIssues: Map<String, Set<EntryReviewIssue>> = emptyMap(),
) {
    val now = Instant.now()
    trackingHistoryItems(
        uiState = uiState,
        historyItems = buildHistoryListItems(
            days = groupedEntries,
            firstDayOfWeek = uiState.firstDayOfWeek,
            today = LocalDate.now(uiState.zone),
            zone = uiState.zone,
            now = now,
        ),
        projectsById = uiState.projects.associateBy { it.id },
        tasksById = uiState.tasks.associateBy { it.id },
        clientsById = uiState.clients.associateBy { it.id },
        syncStatusByEntryId = worstSyncStatusByEntryId(uiState.syncOperations),
        onEdit = onEdit,
        onDelete = onDelete,
        onDateClick = onDateClick,
        onRetrySync = onRetrySync,
        onContinue = onContinue,
        onDuplicate = onDuplicate,
        onDeleteStack = onDeleteStack,
        reviewIssues = reviewIssues,
    )
}

@Composable
private fun rememberGhostAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "history loading")
    val alpha by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ghost alpha",
    )
    return alpha
}

@Composable
private fun GhostBlock(modifier: Modifier, alpha: Float) {
    Box(
        modifier = modifier
            .alpha(alpha)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                RoundedCornerShape(6.dp),
            ),
    )
}

@Composable
private fun HistoryLoadingHeader() {
    val alpha = rememberGhostAlpha()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostBlock(Modifier.width(72.dp).height(12.dp), alpha)
        GhostBlock(Modifier.width(150.dp).height(10.dp), alpha)
    }
}

@Composable
private fun HistoryLoadingEntry(index: Int) {
    val alpha = rememberGhostAlpha()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostBlock(Modifier.size(10.dp), alpha)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                GhostBlock(
                    Modifier
                        .fillMaxWidth(if (index % ALTERNATING_ROW_COUNT == 0) GHOST_PRIMARY_WIDTH else GHOST_SECONDARY_WIDTH)
                        .height(13.dp),
                    alpha,
                )
                GhostBlock(Modifier.width(if (index % 2 == 0) 96.dp else 128.dp).height(10.dp), alpha)
            }
            GhostBlock(Modifier.width(58.dp).height(14.dp), alpha)
        }
    }
}

/**
 * Tracking controls card with timer and input fields
 */
/**
 * "Continue last entry" button that starts tracking with the same params as the last entry.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinueLastEntryButton(entry: TimeEntry, projects: List<Project>, onContinue: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val project = projects.find { it.id == entry.projectId }

    OutlinedButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onContinue()
        },
        modifier = Modifier.fillMaxWidth().testTag(TrackingTestTags.CONTINUE_BUTTON),
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.continue_last_entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.description ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProjectTaskLine(project = project, task = null, background = MaterialTheme.colorScheme.background)
            if (entry.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    entry.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            tonalElevation = 0.dp,
                        ) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Description field with dropdown suggestions from recent time entries.
 * Shows the last 5 unique entries (deduplicated by description+project+task+tags).
 * Filters out entries with no description.
 * Tapping a suggestion copies all fields (description, project, task, tags, billable).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DescriptionFieldWithSuggestions(
    description: String,
    onDescriptionChange: (String) -> Unit,
    timeEntries: List<TimeEntry>,
    projects: List<Project>,
    tags: List<Tag>,
    enabled: Boolean,
    onEntryCopied: (TimeEntry) -> Unit,
    borderless: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val descriptionLabel = stringResource(R.string.description)

    // Compute last 5 unique recent entries, filtering out empty descriptions
    val recentEntries = remember(timeEntries) {
        timeEntries
            .filter { isCompletedTimeEntry(it) && !it.description.isNullOrBlank() }
            .sortedByDescending { it.start }
            .distinctBy { entry ->
                "${entry.description}|${entry.projectId}|${entry.taskId}|${entry.tags.map { it.id }.sorted()}"
            }
            .take(RECENT_ENTRIES_LIMIT)
    }

    ExposedDropdownMenuBox(
        expanded = expanded && recentEntries.isNotEmpty(),
        onExpandedChange = {
            if (enabled) expanded = it
        },
    ) {
        val fieldModifier = Modifier
            .fillMaxWidth()
            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled)
            .onFocusChanged {
                if (it.isFocused && enabled) expanded = true
            }
        val onValueChange: (String) -> Unit = {
            onDescriptionChange(it)
            expanded = false
        }
        if (borderless) {
            val transparent = androidx.compose.ui.graphics.Color.Transparent
            TextField(
                value = description,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.what_are_you_working_on)) },
                modifier = fieldModifier.semantics { contentDescription = descriptionLabel },
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = transparent,
                    unfocusedContainerColor = transparent,
                    disabledContainerColor = transparent,
                    focusedIndicatorColor = transparent,
                    unfocusedIndicatorColor = transparent,
                    disabledIndicatorColor = transparent,
                ),
            )
        } else {
            OutlinedTextField(
                value = description,
                onValueChange = onValueChange,
                label = { Text(descriptionLabel) },
                placeholder = { Text(stringResource(R.string.what_are_you_working_on)) },
                modifier = fieldModifier,
                singleLine = true,
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
            )
        }

        ExposedDropdownMenu(
            expanded = expanded && recentEntries.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            Text(
                stringResource(R.string.recent_entries),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            recentEntries.forEach { entry ->
                val project = projects.find { it.id == entry.projectId }
                val entryTagNames = entry.tags.map { it.name }

                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Description
                            Text(
                                text = entry.description!!,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Project row
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (project != null) {
                                    ProjectTaskLine(
                                        project = project,
                                        task = null,
                                        background = MaterialTheme.colorScheme.surfaceContainer,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.no_project_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Tag chips
                            if (entryTagNames.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    entryTagNames.forEach { tagName ->
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            tonalElevation = 0.dp,
                                        ) {
                                            Text(
                                                text = tagName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(
                                                    horizontal = 6.dp,
                                                    vertical = 2.dp,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onClick = {
                        onEntryCopied(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Separately searchable project and task selectors shared by Track entry forms. */
@Composable
internal fun ProjectTaskDropdown(
    selectedProjectId: String?,
    selectedTaskId: String?,
    projects: List<Project>,
    tasks: List<Task>,
    onSelectionChanged: (projectId: String?, taskId: String?) -> Unit,
    enabled: Boolean,
    onCreateProject: ((String) -> Unit)? = null,
    onCreateTask: ((String, String) -> Unit)? = null,
    style: SelectorStyle = SelectorStyle.Field,
) {
    SharedProjectTaskDropdown(
        projects = projects,
        tasks = tasks,
        selectedProjectId = selectedProjectId,
        selectedTaskId = selectedTaskId,
        onSelectionChanged = onSelectionChanged,
        enabled = enabled,
        showProjectColors = true,
        rounded = true,
        onCreateProject = onCreateProject,
        onCreateTask = onCreateTask,
        style = style,
    )
}

/** Confirms deleting one history entry, or every entry of a stack ([count] > 1). */
@Composable
private fun DeleteEntriesDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (count > 1) {
                    pluralStringResource(R.plurals.history_delete_stack_title, count, count)
                } else {
                    stringResource(R.string.calendar_delete_entry_title)
                },
            )
        },
        text = {
            Text(stringResource(if (count > 1) R.string.history_delete_stack_message else R.string.calendar_delete_entry_message))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(TrackingTestTags.DELETE_CONFIRM),
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TrackingTestTags.DELETE_CANCEL)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The Time Tracker header: the side-menu button and title, then search, enable-notifications
 * (when not granted) and refresh, which spins while a sync runs.
 */
@Composable
internal fun TimeTrackerTopBar(
    syncing: Boolean,
    onRefresh: () -> Unit,
    onRequestNotifications: (() -> Unit)?,
    searchOpen: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    MainTopBar(
        title = stringResource(R.string.nav_time_tracker),
        actions = {
            if (onSearch != null && !searchOpen) {
                IconButton(onClick = onSearch, modifier = Modifier.testTag(TrackingTestTags.SEARCH_BUTTON)) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_history))
                }
            }
            if (onRequestNotifications != null) {
                IconButton(onClick = onRequestNotifications) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = stringResource(R.string.enable_notifications),
                    )
                }
            }
            // Only compose the infinite spin while syncing; an idle infinite transition keeps the
            // frame clock busy forever.
            val syncRotation = if (syncing) {
                val syncTransition = rememberInfiniteTransition(label = "sync")
                val rotation by syncTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = FULL_ROTATION_DEGREES,
                    animationSpec = infiniteRepeatable(tween(SYNC_ROTATION_DURATION_MS), RepeatMode.Restart),
                    label = "sync rotation",
                )
                rotation
            } else {
                0f
            }
            IconButton(onClick = onRefresh, modifier = Modifier.testTag(TrackingTestTags.REFRESH_BUTTON)) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = stringResource(if (syncing) R.string.syncing else R.string.refresh),
                    modifier = Modifier.rotate(syncRotation),
                )
            }
        },
    )
}

/**
 * Create/edit time entry bottom sheet. [entry] null = create mode.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TimeEntryFormSheet(
    entry: TimeEntry?, // null = create mode
    zone: ZoneId, // account temporal-policy zone for the new-entry fallback start
    suggestedStart: ZonedDateTime?, // create mode: pre-filled start (end of last entry / now-1h)
    projects: List<Project>,
    tasks: List<Task>,
    tags: List<Tag>,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?, List<String>, Boolean, String, String?) -> Unit,
    existingEntries: List<TimeEntry> = emptyList(),
    preventOverlap: Boolean = false,
    templates: List<EntryTemplate> = emptyList(),
    onSaveAsTemplate: ((TemplateDraft) -> Unit)? = null,
    saveEnabled: Boolean = true,
    onDuplicate: (() -> Unit)? = null,
    onSplit: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var description by remember { mutableStateOf(entry?.description ?: "") }
    var projectId by remember { mutableStateOf(entry?.projectId) }
    var taskId by remember { mutableStateOf(entry?.taskId) }
    var selectedTags by remember { mutableStateOf(entry?.tags?.map { it.id } ?: emptyList<String>()) }
    var billable by remember { mutableStateOf(entry?.billable ?: false) }
    val isRunningEntry = remember(entry?.id, entry?.end, entry?.duration) {
        entry?.let(::isRunningTimeEntry) == true
    }
    var templateMenuExpanded by remember { mutableStateOf(false) }
    val originalStart = remember(entry?.id, zone) {
        entry?.let { ZonedDateTime.parse(it.start, DateTimeFormatter.ISO_DATE_TIME).withZoneSameInstant(zone) }
            ?: (suggestedStart ?: ZonedDateTime.now(zone).minusHours(1))
                .withSecond(0).withNano(0)
    }
    val originalEnd = remember(entry?.id, zone) {
        when {
            entry?.end != null -> ZonedDateTime.parse(entry.end, DateTimeFormatter.ISO_DATE_TIME).withZoneSameInstant(zone)
            entry != null -> originalStart.plusSeconds((entry.duration ?: 0).toLong())
            else -> ZonedDateTime.now(originalStart.zone).withSecond(0).withNano(0)
                .let { if (it.isAfter(originalStart)) it else originalStart.plusMinutes(1) }
        }
    }
    var startTime by remember(entry?.id, zone) { mutableStateOf(originalStart) }
    var endTime by remember(entry?.id, zone) { mutableStateOf(originalEnd) }
    var durationMinutes by remember(entry?.id, zone) {
        mutableStateOf(java.time.Duration.between(originalStart, originalEnd).toMinutes().coerceAtLeast(1).toString())
    }
    var editingTime by remember { mutableStateOf<TimeField?>(null) }
    var editingDate by remember { mutableStateOf<TimeField?>(null) }
    var showSplitPicker by remember { mutableStateOf(false) }
    val durationIsValid = isRunningEntry || durationMinutes.toLongOrNull()?.let { it > 0 } == true
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val overlaps = remember(startTime, endTime, existingEntries, entry, isRunningEntry) {
        val org = entry?.organizationId ?: existingEntries.firstOrNull()?.organizationId
        if (isRunningEntry || org == null || existingEntries.isEmpty()) {
            false
        } else {
            val candidate = TimeEntry(
                id = entry?.id ?: "",
                userId = entry?.userId ?: "",
                start = formatTimeEntryInstant(startTime),
                end = formatTimeEntryInstant(endTime),
                organizationId = org,
            )
            existingEntries.any { it.id != candidate.id && EntryTrustRules.overlaps(candidate, it) }
        }
    }
    val validation = remember(startTime, endTime, overlaps, preventOverlap, isRunningEntry) {
        if (isRunningEntry) {
            EntryTimeValidator.Result(error = null, warnings = emptyList())
        } else {
            EntryTimeValidator.evaluate(startTime, endTime, overlaps, preventOverlap)
        }
    }
    val durationHours = remember(startTime, endTime) {
        java.time.Duration.between(startTime, endTime).toHours().coerceAtLeast(0)
    }

    fun setDuration(minutes: Long) {
        val safeMinutes = minutes.coerceAtLeast(1)
        durationMinutes = safeMinutes.toString()
        endTime = startTime.plusMinutes(safeMinutes)
    }

    val saveEntry = {
        onSave(
            description.ifEmpty { null },
            projectId,
            taskId,
            selectedTags,
            billable,
            formatTimeEntryInstant(startTime),
            endTime.takeUnless { isRunningEntry }?.let(::formatTimeEntryInstant),
        )
    }
    ModalBottomSheet(
        // Child date/time/split pickers use separate dialog windows. Do not let the parent sheet
        // interpret their focus change as a request to close the whole editor.
        onDismissRequest = {
            if (canDismissTimeEntryFormSheet(editingTime != null, editingDate != null, showSplitPicker)) onDismiss()
        },
        modifier = Modifier.testTag(TrackingTestTags.SHEET),
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = Dimens.RadiusXl, topEnd = Dimens.RadiusXl),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = Dimens.Space24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
        ) {
            Box(Modifier.padding(horizontal = Dimens.Space8)) {
                EntrySheetHeader(
                    title = stringResource(if (entry == null) R.string.add_time_entry else R.string.edit_time_entry),
                    onCancel = onDismiss,
                    onSave = saveEntry,
                    saveEnabled = saveEnabled && durationIsValid && validation.canSave,
                    cancelTag = EditTimeEntryTestTags.CANCEL_BUTTON,
                    saveTag = TrackingTestTags.SHEET_SAVE_BUTTON,
                )
            }

            EntryDescriptionField(
                value = description,
                onValueChange = { description = it },
                testTag = TrackingTestTags.SHEET_DESCRIPTION_FIELD,
            )

            GroupedSection {
                ProjectTaskDropdown(
                    selectedProjectId = projectId,
                    selectedTaskId = taskId,
                    projects = projects,
                    tasks = tasks,
                    onSelectionChanged = { newProjectId, newTaskId ->
                        projectId = newProjectId
                        taskId = newTaskId
                    },
                    enabled = true,
                    style = SelectorStyle.Grouped,
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                TagsSelector(
                    selectedTagIds = selectedTags,
                    availableTags = tags,
                    onTagsChanged = { selectedTags = it },
                    enabled = true,
                    style = SelectorStyle.Grouped,
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedSwitchRow(
                    title = stringResource(R.string.billable),
                    leadingIcon = Icons.Outlined.AttachMoney,
                    checked = billable,
                    onCheckedChange = { billable = it },
                    modifier = Modifier.testTag(TrackingTestTags.SHEET_BILLABLE),
                )
            }

            GroupedSection(
                header = stringResource(R.string.time_section),
                footer = stringResource(R.string.running_entry_start_edit_hint).takeIf { isRunningEntry },
            ) {
                EntryTimeRow(
                    label = stringResource(R.string.start),
                    value = startTime,
                    onDateClick = { editingDate = TimeField.Start },
                    onTimeClick = { editingTime = TimeField.Start },
                    dateTag = TrackingTestTags.SHEET_START_DATE,
                    timeTag = TrackingTestTags.SHEET_START_TIME,
                    dateLabel = stringResource(R.string.start_date),
                    timeLabel = stringResource(R.string.start_time),
                )
                if (!isRunningEntry) {
                    GroupedDivider()
                    EntryTimeRow(
                        label = stringResource(R.string.end),
                        value = endTime,
                        onDateClick = { editingDate = TimeField.End },
                        onTimeClick = { editingTime = TimeField.End },
                        dateTag = TrackingTestTags.SHEET_END_DATE,
                        timeTag = TrackingTestTags.SHEET_END_TIME,
                        dateLabel = stringResource(R.string.end_date),
                        timeLabel = stringResource(R.string.end_time),
                    )
                    GroupedDivider()
                    EntryDurationRow(
                        minutesText = durationMinutes,
                        totalLabel = formatElapsedTime((durationMinutes.toLongOrNull() ?: 0) * SECONDS_PER_MINUTE_LONG),
                        isValid = durationIsValid,
                        onMinutesTextChange = { value ->
                            durationMinutes = value
                            value.toLongOrNull()
                                ?.takeIf { it >= MINIMUM_DURATION_MINUTES }
                                ?.let { endTime = startTime.plusMinutes(it) }
                        },
                        onDecrease = {
                            setDuration((durationMinutes.toLongOrNull() ?: MINIMUM_DURATION_MINUTES) - DURATION_STEP_MINUTES)
                        },
                        onIncrease = { setDuration((durationMinutes.toLongOrNull() ?: 0) + DURATION_STEP_MINUTES) },
                        fieldTag = TrackingTestTags.SHEET_DURATION_FIELD,
                    )
                }
            }

            if (!isRunningEntry) {
                Box(Modifier.padding(horizontal = Dimens.Space16)) {
                    EntryValidationBanner(result = validation, durationHours = durationHours)
                }
            }

            if (!saveEnabled) {
                Text(
                    text = stringResource(R.string.sync_conflict_edit_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Dimens.Space32),
                )
            }

            // Save-as-template / start-from-template affordance (gap analysis #1, #9).
            if (templates.isNotEmpty() || onSaveAsTemplate != null) {
                GroupedSection {
                    if (templates.isNotEmpty()) {
                        Box {
                            GroupedRow(
                                title = stringResource(R.string.templates_use_template),
                                leadingIcon = Icons.Outlined.StarOutline,
                                onClick = { templateMenuExpanded = true },
                            )
                            DropdownMenu(
                                expanded = templateMenuExpanded,
                                onDismissRequest = { templateMenuExpanded = false },
                            ) {
                                templates.forEach { template ->
                                    val label = templateDisplayLabel(template, projects)
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            val resolution = TemplateResolver.resolve(template, projects, tasks, tags)
                                            description = template.description ?: ""
                                            projectId = resolution.projectId
                                            taskId = resolution.taskId
                                            selectedTags = resolution.tagIds
                                            billable = resolution.billable
                                            templateMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (templates.isNotEmpty() && onSaveAsTemplate != null) {
                        GroupedDivider(inset = Dimens.SettingsIconInset)
                    }
                    if (onSaveAsTemplate != null) {
                        val canSaveTemplate = projectId != null || description.isNotBlank() || selectedTags.isNotEmpty()
                        GroupedRow(
                            title = stringResource(R.string.templates_save_as_template),
                            leadingIcon = Icons.Outlined.BookmarkAdd,
                            showChevron = false,
                            onClick = if (canSaveTemplate) {
                                {
                                    onSaveAsTemplate(
                                        TemplateDraft(
                                            name = null,
                                            projectId = projectId,
                                            taskId = taskId,
                                            description = description.trim().takeIf { it.isNotEmpty() },
                                            tagIds = selectedTags,
                                            billable = billable,
                                            isFavorite = false,
                                        ),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            // Roadmap #13: Duplicate/Split act on the stored entry. Only for completed
            // (has end) and editable (not conflicted) entries; hidden otherwise.
            val canDuplicateOrSplit = entry?.let(::isCompletedTimeEntry) == true && saveEnabled
            if (canDuplicateOrSplit && (onDuplicate != null || onSplit != null)) {
                GroupedSection {
                    if (onDuplicate != null) {
                        GroupedRow(
                            title = stringResource(R.string.duplicate_entry),
                            leadingIcon = Icons.Outlined.ContentCopy,
                            showChevron = false,
                            onClick = {
                                onDuplicate()
                                onDismiss()
                            },
                            modifier = Modifier.testTag(EditTimeEntryTestTags.DUPLICATE_BUTTON),
                        )
                    }
                    if (onDuplicate != null && onSplit != null) GroupedDivider(inset = Dimens.SettingsIconInset)
                    if (onSplit != null) {
                        GroupedRow(
                            title = stringResource(R.string.split_entry),
                            leadingIcon = Icons.AutoMirrored.Outlined.CallSplit,
                            showChevron = false,
                            onClick = { showSplitPicker = true },
                            modifier = Modifier.testTag(EditTimeEntryTestTags.SPLIT_BUTTON),
                        )
                    }
                }
            }

            if (entry != null && onDelete != null && saveEnabled) {
                DestructiveActionRow(
                    label = stringResource(R.string.delete_entry),
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    testTag = EditTimeEntryTestTags.DELETE_BUTTON,
                )
            }
        }
    }

    editingTime?.let { field ->
        val current = if (field == TimeField.Start) startTime else endTime
        EntryTimePickerDialog(
            title = stringResource(if (field == TimeField.Start) R.string.start_time else R.string.end_time),
            initial = current,
            onDismiss = { editingTime = null },
            onConfirm = { hour, minute ->
                if (field == TimeField.Start) {
                    val newStart = startTime.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    if (!isRunningEntry) endTime = retimedEnd(startTime, newStart, endTime)
                    startTime = newStart
                } else {
                    val sameDayEnd = endTime.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    // Do not silently roll an earlier clock-time into a ~24h entry: only a plausible
                    // overnight span becomes cross-midnight, otherwise keep it same-day so the
                    // validation banner surfaces the end-before-start error for the user to fix.
                    endTime = EntryTimeValidator.resolveEnd(startTime, sameDayEnd) ?: sameDayEnd
                    durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes().toString()
                }
                editingTime = null
            },
        )
    }

    editingDate?.let { field ->
        val current = if (field == TimeField.Start) startTime else endTime
        EntryDatePickerDialog(
            initialDate = current.toLocalDate(),
            onDismiss = { editingDate = null },
            onConfirm = { date ->
                if (field == TimeField.Start) {
                    val newStart = startTime.with(date)
                    if (!isRunningEntry) endTime = retimedEnd(startTime, newStart, endTime)
                    startTime = newStart
                } else {
                    endTime = endTime.with(date)
                    durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes().toString()
                }
                editingDate = null
            },
        )
    }

    // Roadmap #13/#64: split-time picker constrained to the strictly-interior (start, end) window.
    if (showSplitPicker && entry?.let(::isCompletedTimeEntry) == true && onSplit != null) {
        // Midpoint is a sensible default and always a valid interior instant.
        val midpoint = originalStart.plus(java.time.Duration.between(originalStart, originalEnd).dividedBy(2))
        EntryTimePickerDialog(
            title = stringResource(R.string.split_entry_title),
            initial = midpoint,
            testTag = EditTimeEntryTestTags.SPLIT_TIME_PICKER,
            onDismiss = { showSplitPicker = false },
            onConfirm = { hour, minute ->
                showSplitPicker = false
                val candidate = originalStart.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                // Clamp into the open interval: reject boundary/out-of-range picks (half-open
                // semantics) - the repository re-validates, this just avoids an obvious no-op.
                if (candidate.isAfter(originalStart) && candidate.isBefore(originalEnd)) {
                    onSplit(formatTimeEntryInstant(candidate))
                    onDismiss()
                }
            },
        )
    }
}

private enum class TimeField { Start, End }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryTimePickerDialog(
    title: String,
    initial: ZonedDateTime,
    testTag: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TimePicker(
                state = state,
                modifier = testTag?.let(Modifier::testTag) ?: Modifier,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(state.hour, state.minute) },
                modifier = Modifier.testTag(EditTimeEntryTestTags.TIME_PICKER_CONFIRM),
            ) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun canDismissTimeEntryFormSheet(hasTimePicker: Boolean, hasDatePicker: Boolean, hasSplitPicker: Boolean): Boolean =
    !hasTimePicker && !hasDatePicker && !hasSplitPicker

private const val FULL_ROTATION_DEGREES = 360f
private const val SECONDS_PER_HOUR_LONG = 3600L
private const val SECONDS_PER_MINUTE_LONG = 60L
private const val FILTER_ENTER_DURATION_MS = 180
private const val FILTER_EXPAND_DURATION_MS = 220
private const val FILTER_EXIT_DURATION_MS = 120
private const val FILTER_COLLAPSE_DURATION_MS = 180
private const val SYNC_ROTATION_DURATION_MS = 900
private const val ALTERNATING_ROW_COUNT = 2
private const val GHOST_PRIMARY_WIDTH = 0.62f
private const val GHOST_SECONDARY_WIDTH = 0.45f
private const val FAB_SCRIM_ALPHA = 0.32f
private const val HISTORY_PLACEHOLDER_COUNT = 4
private const val RECENT_ENTRIES_LIMIT = 5
private const val DURATION_STEP_MINUTES = 15L
private const val MINIMUM_DURATION_MINUTES = 1L
private const val MAX_CROSS_MIDNIGHT_HOURS = 18L
private const val LONG_DURATION_WARNING_HOURS = 12L
private const val LAST_7_DAYS_OFFSET = 6L

/** Clock-style duration like the iOS timer: "37:03" under an hour, "1:05:00" from an hour. */
internal fun formatElapsedTime(seconds: Long): String {
    // Defensive floor: a device clock behind the entry's start must never render as "-1:-5:-3".
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / SECONDS_PER_HOUR_LONG
    val minutes = (safeSeconds % SECONDS_PER_HOUR_LONG) / SECONDS_PER_MINUTE_LONG
    val secs = safeSeconds % SECONDS_PER_MINUTE_LONG
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, secs)
    }
}

/**
 * Deterministic client-side validation for a manually edited or created time entry. Server policy
 * stays authoritative (see [EntryTrustRules]); these are local guards so the user never silently
 * creates an invalid interval, and is warned before saving an unusually long or overlapping entry.
 * Warnings do not block: an explicit Save is the user's confirmation. Multi-day entries are valid;
 * the server remains authoritative about any organization-specific duration limit.
 */
internal object EntryTimeValidator {
    /** An end clock-time earlier than start rolls to the next day only within this span; beyond it
     *  the inversion is treated as a mistake rather than an intended overnight shift. */
    val MAX_CROSS_MIDNIGHT: java.time.Duration = java.time.Duration.ofHours(MAX_CROSS_MIDNIGHT_HOURS)

    /** Durations at or above this are plausible but worth confirming before saving. */
    val LONG_DURATION_WARNING: java.time.Duration = java.time.Duration.ofHours(LONG_DURATION_WARNING_HOURS)
    enum class Error { END_NOT_AFTER_START }
    enum class Warning { LONG_DURATION, OVERLAP, OVERLAP_POLICY }

    data class Result(val error: Error?, val warnings: List<Warning>) {
        val canSave: Boolean get() = error == null
    }

    /**
     * Resolve an end clock-time [sameDayEnd] that shares [start]'s date. Returns the same-day value
     * when it is after start, the next-day value for a plausible overnight entry, or null when the
     * only rollover interpretation would be implausibly long — signalling the caller to surface an
     * end-before-start error instead of silently rolling over into a ~24h entry.
     */
    fun resolveEnd(start: ZonedDateTime, sameDayEnd: ZonedDateTime): ZonedDateTime? {
        if (sameDayEnd.isAfter(start)) return sameDayEnd
        val rolled = sameDayEnd.plusDays(1)
        return rolled.takeIf { java.time.Duration.between(start, it) <= MAX_CROSS_MIDNIGHT }
    }

    fun evaluate(start: ZonedDateTime, end: ZonedDateTime, overlaps: Boolean = false, overlapProhibited: Boolean = false): Result {
        val duration = java.time.Duration.between(start, end)
        val error = when {
            !end.isAfter(start) -> Error.END_NOT_AFTER_START
            else -> null
        }
        val warnings = buildList {
            if (error == null && duration >= LONG_DURATION_WARNING) add(Warning.LONG_DURATION)
            if (overlaps) add(if (overlapProhibited) Warning.OVERLAP_POLICY else Warning.OVERLAP)
        }
        return Result(error, warnings)
    }
}

/** Inline error/warning banner for the create/edit time-entry sheets. */
@Composable
internal fun EntryValidationBanner(result: EntryTimeValidator.Result, durationHours: Long) {
    if (result.canSave && result.warnings.isEmpty()) return
    val isError = !result.canSave
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag(dev.tricked.solidverdant.ui.components.EditTimeEntryTestTags.VALIDATION_BANNER),
    ) {
        val contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            result.error?.let { error ->
                Text(
                    text = stringResource(
                        when (error) {
                            EntryTimeValidator.Error.END_NOT_AFTER_START -> R.string.entry_error_end_before_start
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
            result.warnings.forEach { warning ->
                Text(
                    text = when (warning) {
                        EntryTimeValidator.Warning.LONG_DURATION ->
                            pluralStringResource(R.plurals.entry_warning_long_duration, durationHours.toInt(), durationHours)
                        EntryTimeValidator.Warning.OVERLAP ->
                            stringResource(R.string.entry_warning_overlap)
                        EntryTimeValidator.Warning.OVERLAP_POLICY ->
                            stringResource(R.string.entry_warning_overlap_policy)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
    }
}

/**
 * Format date for display
 */
internal fun formatDate(date: LocalDate, context: android.content.Context, zone: ZoneId, locale: Locale): String {
    val today = LocalDate.now(zone)
    return when {
        date == today -> context.getString(R.string.today)
        date == today.minusDays(1) -> context.getString(R.string.yesterday)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

// Shared formatter instances: DateTimeFormatter.ofPattern() builds a new parser every call,
// which is measurable when every visible history row formats its time range during a scroll.
private val hourMinuteFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Format time range
 */
internal fun formatTimeRange(
    start: String,
    end: String?,
    zone: ZoneId,
    locale: Locale = Locale.getDefault(),
    nowLabel: String = "now",
    invalidLabel: String = "Invalid time",
): String {
    val startValue = runCatching { ZonedDateTime.parse(start).withZoneSameInstant(zone) }.getOrNull()
        ?: return invalidLabel
    val startFormatted = startValue.format(hourMinuteFormatter)
    return if (end != null) {
        val endValue = runCatching { ZonedDateTime.parse(end).withZoneSameInstant(zone) }.getOrNull()
            ?: return invalidLabel
        val endFormatted = endValue.format(hourMinuteFormatter)
        val startDate = startValue.toLocalDate()
        val endDate = endValue.toLocalDate()
        if (startDate == endDate) {
            "$startFormatted - $endFormatted"
        } else {
            "${startDate.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale))} $startFormatted - " +
                "${endDate.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale))} $endFormatted"
        }
    } else {
        "$startFormatted - $nowLabel"
    }
}

internal fun groupCompletedEntriesByLocalDay(entries: List<TimeEntry>, zone: ZoneId, now: Instant): Map<LocalDate, List<TimeEntry>> =
    entries
        .asSequence()
        .filter(::isCompletedTimeEntry)
        .filter(::isWorkTimeEntry)
        .flatMap { entry -> timeEntryLocalDaySlices(entry, zone, now).asSequence().map { it.date to entry } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap(compareByDescending { it })

/** Date-picker millis are UTC-midnight instants; resolve them back to the picked date. */
private fun utcDateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
