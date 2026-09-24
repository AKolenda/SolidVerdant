/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.local.AppThemeMode
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.data.model.isLocalTimeEntryId
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.TemporalPolicy
import dev.tricked.solidverdant.domain.time.TemporalPolicyProvider
import dev.tricked.solidverdant.domain.time.isCompletedTimeEntry
import dev.tricked.solidverdant.domain.time.isRunningTimeEntry
import dev.tricked.solidverdant.domain.time.parseTimeEntryInstant
import dev.tricked.solidverdant.domain.time.timeEntryLocalDaySlices
import dev.tricked.solidverdant.domain.time.timeEntryOverlapsLocalDateRange
import dev.tricked.solidverdant.service.TimeTrackingNotificationService
import dev.tricked.solidverdant.sync.SyncTrigger
import dev.tricked.solidverdant.util.Clock
import dev.tricked.solidverdant.widget.TimeTrackingWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val RATE_LIMIT_RETRY_ATTEMPTS = 3
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val MIN_RETRY_AFTER_SECONDS = 1
private const val MAX_RETRY_AFTER_SECONDS = 60
private const val DEFAULT_RETRY_AFTER_SECONDS = 5
private const val MILLIS_PER_SECOND = 1_000L
private const val TIMER_TICK_INTERVAL_MS = 1_000L
private const val HISTORY_PROGRESS_CAP = 0.9f
internal const val SYNC_STATUS_REVEAL_DELAY_MS = 3_000L

/** Which slice of history the user is currently looking at. */
internal enum class HistoryWindowMode { RECENT, PAGINATED }

internal enum class HistoryMembershipChange { COMPLETED_ENTRY_PRESENT, ENTRY_ABSENT }

internal fun resolvedHistoryMembershipChangeIds(changes: Map<String, HistoryMembershipChange>, collected: List<TimeEntry>): Set<String> {
    if (changes.isEmpty()) return emptySet()
    val collectedById = collected.associateBy { it.id }
    return changes.mapNotNullTo(mutableSetOf()) { (entryId, change) ->
        val collectedEntry = collectedById[entryId]
        when (change) {
            HistoryMembershipChange.COMPLETED_ENTRY_PRESENT -> entryId.takeIf {
                collectedEntry != null && isCompletedTimeEntry(collectedEntry)
            }
            HistoryMembershipChange.ENTRY_ABSENT -> entryId.takeIf { collectedEntry == null }
        }
    }
}

/**
 * Single source of truth for how a Room emission from the recent-window collector combines with
 * the list currently on screen.
 *
 * In [HistoryWindowMode.RECENT] the collector owns the list and replaces it wholesale, so live
 * edits and the active-entry poll stay fresh. Once the user pages or jumps to an off-window slice
 * ([HistoryWindowMode.PAGINATED]) the network-fetched window preserves entries Room does not hold
 * so scroll position survives a poll emission. Inside the window's start range Room is
 * authoritative: completed rows it holds are shown, rows it has dropped under a `local-` id are
 * removed. That covers sync rekeying a local CREATE/START to its server id, which otherwise left
 * a frozen local row on screen and never surfaced the server copy. [includesNewestHistory] opens
 * the upper bound when the window reaches the newest entries, so a just-added entry appears.
 */
internal object HistoryWindow {
    fun merge(
        mode: HistoryWindowMode,
        displayed: List<TimeEntry>,
        collected: List<TimeEntry>,
        locallyMutatedEntryIds: Set<String> = emptySet(),
        includesNewestHistory: Boolean = true,
    ): List<TimeEntry> = when (mode) {
        HistoryWindowMode.RECENT -> collected
        HistoryWindowMode.PAGINATED -> {
            val collectedById = collected.associateBy { it.id }
            val refreshed = displayed.mapNotNull { displayedEntry ->
                collectedById[displayedEntry.id]
                    ?: displayedEntry.takeUnless { it.id in locallyMutatedEntryIds || isLocalTimeEntryId(it.id) }
            }
            val displayedIds = displayed.mapTo(mutableSetOf()) { it.id }
            val oldestStart = displayed.minOfOrNull { it.start }
            val newestStart = displayed.maxOfOrNull { it.start }
            fun insideWindow(entry: TimeEntry): Boolean = oldestStart != null &&
                newestStart != null &&
                entry.start >= oldestStart &&
                (includesNewestHistory || entry.start <= newestStart)
            val completedAdditions = collected.filter {
                it.id !in displayedIds &&
                    isCompletedTimeEntry(it) &&
                    (it.id in locallyMutatedEntryIds || insideWindow(it))
            }
            insertNewestFirst(refreshed, completedAdditions)
        }
    }

    /**
     * Places each of [additions] before the first entry of [entries] that started earlier, in one
     * pass: the additions are stably sorted newest first, then merged in. The result equals
     * inserting them one by one in their given order, without copying the list per addition.
     */
    internal fun insertNewestFirst(entries: List<TimeEntry>, additions: List<TimeEntry>): List<TimeEntry> {
        if (additions.isEmpty()) return entries
        val pending = additions.sortedByDescending { it.start }
        val merged = ArrayList<TimeEntry>(entries.size + pending.size)
        var next = 0
        entries.forEach { entry ->
            while (next < pending.size && pending[next].start > entry.start) merged += pending[next++]
            merged += entry
        }
        while (next < pending.size) merged += pending[next++]
        return merged
    }
}

/**
 * Whether [candidate] is [entry]: the same id, or, for a `local-` [entry] that START reconciliation
 * has since rekeyed, the same organization, user and start (the identity a reconcile preserves).
 */
internal fun isSameRunningEntry(candidate: TimeEntry, entry: TimeEntry): Boolean = candidate.id == entry.id ||
    (
        isLocalTimeEntryId(entry.id) &&
            candidate.organizationId == entry.organizationId &&
            candidate.userId == entry.userId &&
            candidate.start == entry.start
        )

/** Preserve a server-missing active row only while its creating START has not reached the server. */
internal fun shouldPreserveLocallyStartedEntry(entryId: String, operations: List<TimeEntryRepository.SyncOperation>): Boolean =
    operations.any { operation ->
        operation.entryId == entryId &&
            operation.type == OutboxOpType.START &&
            operation.status != TimeEntryRepository.EntrySyncStatus.SYNCED
    }

/** Do not resurrect the server's still-active copy while its local STOP is in flight. */
internal fun shouldDeferServerActiveWhileStopping(entryId: String, operations: List<TimeEntryRepository.SyncOperation>): Boolean =
    operations.any { operation ->
        operation.entryId == entryId &&
            operation.type == OutboxOpType.STOP &&
            operation.status in setOf(
                TimeEntryRepository.EntrySyncStatus.PENDING,
                TimeEntryRepository.EntrySyncStatus.RETRYING,
            )
    }

/**
 * A poll may return an older active row after sync has already written a replacement into Room.
 * Keep the newer timer visible when the ids differ; for the same id, the server response remains
 * authoritative so start-time edits and metadata refreshes still surface immediately. A polled or
 * override row still under a `local-` id has been rekeyed once Room's active row carries a server
 * id, so Room wins regardless of start times.
 */
internal fun selectVisibleActiveEntry(roomActive: TimeEntry?, polledActive: TimeEntry?): TimeEntry? {
    if (roomActive == null || polledActive == null || roomActive.id == polledActive.id) return polledActive
    if (isLocalTimeEntryId(polledActive.id) && !isLocalTimeEntryId(roomActive.id)) return roomActive
    val roomStart = parseTimeEntryInstant(roomActive.start) ?: return polledActive
    val polledStart = parseTimeEntryInstant(polledActive.start) ?: return roomActive
    return if (roomStart > polledStart) {
        roomActive
    } else {
        polledActive
    }
}

/**
 * UI state for tracking screen
 */
@Stable
data class TrackingUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val currentTimeEntry: TimeEntry? = null,
    val timeEntries: List<TimeEntry> = emptyList(),
    val overlapCount: Int = 0,
    val hasLoadedTimeEntries: Boolean = false,
    val isLoadingMoreTimeEntries: Boolean = false,
    val hasMoreTimeEntries: Boolean = false,
    val totalTimeEntries: Int? = null,
    val historyJumpDate: LocalDate? = null,
    val historyJumpTarget: LocalDate? = null,
    val historyJumpProgress: Float? = null,
    val historyRateLimitWaitSeconds: Int? = null,
    val canLoadNewerHistory: Boolean = false,
    val cachedContinueEntry: TimeEntry? = null,
    val projects: List<Project> = emptyList(),
    val clients: List<Client> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val elapsedSeconds: Long = 0,
    val error: String? = null,
    // Current entry editing state
    val editingDescription: String = "",
    val editingProjectId: String? = null,
    val editingTaskId: String? = null,
    val editingTags: List<String> = emptyList(),
    val editingBillable: Boolean = false,
    val syncOperations: List<TimeEntryRepository.SyncOperation> = emptyList(),
    val syncStatusVisible: Boolean = false,
    val conflictedEntryIds: Set<String> = emptySet(),
    /** Account temporal-policy zone; history filtering and new-entry pickers use it. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Account temporal-policy week start; history week headers begin on this day. */
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    /**
     * Roadmap #13: id of an entry the UI should open for editing right after a duplicate/split
     * (the freshly created copy / second half). One-shot: cleared via [TrackingViewModel.consumeEntryToEdit].
     */
    val entryToEditId: String? = null,
) {
    /** Mutations retain the legacy internal flag; refresh/sync have independent flags. */
    val isMutating: Boolean get() = isLoading
}

/** The next entry's fields as typed into the start-timer sheet. */
@Immutable
data class EntryDraft(
    val description: String = "",
    val projectId: String? = null,
    val taskId: String? = null,
    val tags: List<String> = emptyList(),
    val billable: Boolean = false,
)

fun TrackingUiState.entryDraft(): EntryDraft = EntryDraft(
    description = editingDescription,
    projectId = editingProjectId,
    taskId = editingTaskId,
    tags = editingTags,
    billable = editingBillable,
)

/**
 * This state without the idle start-timer draft. Typing into the start sheet then leaves it equal,
 * so the app shell and the history skip recomposition and only the form, fed by [entryDraft],
 * redraws. A running or paused timer keeps its fields, which the docked timer shows.
 */
fun TrackingUiState.withoutIdleDraft(): TrackingUiState = if (isTracking || isPaused || currentTimeEntry != null) {
    this
} else {
    copy(
        editingDescription = "",
        editingProjectId = null,
        editingTaskId = null,
        editingTags = emptyList(),
        editingBillable = false,
    )
}

/**
 * Pending fields of the running entry's details sheet. A Pause or Stop tapped in that sheet commits
 * them with the stop, so nothing typed there is lost when the sheet closes.
 */
data class RunningEntryEdits(
    val description: String?,
    val projectId: String?,
    val taskId: String?,
    val tagIds: List<String>,
    val billable: Boolean,
    val start: String,
)

/**
 * ViewModel for time tracking operations
 */
@HiltViewModel
@Suppress("LargeClass", "TooManyFunctions")
class TrackingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsDataStore: SettingsDataStore,
    private val timeEntryRepository: TimeEntryRepository,
    private val syncTrigger: SyncTrigger,
    private val temporalPolicyProvider: TemporalPolicyProvider,
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : ViewModel() {

    // Account temporal-policy zone. The policy decodes the cached profile, so it is read off the main
    // thread by the collector in init; until it arrives the screen uses the provider's own fallback
    // (device zone, Monday). The history is built off the main thread too, so it rebuilds with the
    // account zone before its first result is shown.
    private val initialPolicy = TemporalPolicy(zone = ZoneId.systemDefault(), firstDayOfWeek = DayOfWeek.MONDAY)

    // A small first-frame cache (see [firstFrameCacheOf]): the running timer, the newest entries and
    // only the catalogue items they show. Room replaces it as soon as the collectors start.
    private val cachedTrackingState = settingsDataStore.getCachedTrackingState()
    private val cachedTrackingDraft = settingsDataStore.getCachedTrackingDraft()
        ?.takeIf { draft -> draft.organizationId == cachedTrackingState?.organizationId }
    private var autoClearEntryFieldsAfterStopEnabled = settingsDataStore.getCachedAutoClearEntryFieldsAfterStop()
    private var clearDescriptionAfterStopEnabled = settingsDataStore.getCachedClearDescriptionAfterStop()
    private val _uiState = MutableStateFlow(
        cachedTrackingState?.let { cached ->
            TrackingUiState(
                isTracking = cached.activeEntry != null,
                currentTimeEntry = cached.activeEntry,
                timeEntries = cached.timeEntries,
                overlapCount = cached.overlapCount,
                hasLoadedTimeEntries = true,
                cachedContinueEntry = cached.timeEntries
                    .firstOrNull { isCompletedTimeEntry(it) && !it.description.isNullOrBlank() }
                    ?: settingsDataStore.getCachedContinueEntry(),
                projects = cached.projects,
                clients = cached.clients,
                tasks = cached.tasks,
                tags = cached.tags,
                editingDescription = cached.activeEntry?.description ?: cachedTrackingDraft?.description.orEmpty(),
                editingProjectId = cached.activeEntry?.projectId ?: cachedTrackingDraft?.projectId,
                editingTaskId = cached.activeEntry?.taskId ?: cachedTrackingDraft?.taskId,
                editingTags = cached.activeEntry?.tags?.map { it.id }.orEmpty(),
                editingBillable = cached.activeEntry?.billable ?: false,
                zone = initialPolicy.zone,
                firstDayOfWeek = initialPolicy.firstDayOfWeek,
            )
        } ?: TrackingUiState(
            cachedContinueEntry = settingsDataStore.getCachedContinueEntry(),
            zone = initialPolicy.zone,
            firstDayOfWeek = initialPolicy.firstDayOfWeek,
        ),
    )
    val uiState: StateFlow<TrackingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            temporalPolicyProvider.policy.flowOn(Dispatchers.Default).collect { policy ->
                val state = _uiState.value
                if (state.zone != policy.zone || state.firstDayOfWeek != policy.firstDayOfWeek) {
                    _uiState.value = state.copy(zone = policy.zone, firstDayOfWeek = policy.firstDayOfWeek)
                }
            }
        }
    }

    val alwaysShowNotifications = settingsDataStore.alwaysShowNotification
    val appTheme = settingsDataStore.appTheme
    val optimisticRefresh = settingsDataStore.optimisticRefresh
    val liveUpdateEnabled = settingsDataStore.liveUpdateEnabled
    val autoClearEntryFieldsAfterStop = settingsDataStore.autoClearEntryFieldsAfterStop
    val clearDescriptionAfterStop = settingsDataStore.clearDescriptionAfterStop
    val longTimerHours = settingsDataStore.longTimerHours
    private val _snapshotHydrated = MutableStateFlow(false)
    val snapshotHydrated: StateFlow<Boolean> = _snapshotHydrated.asStateFlow()
    private val _hasSnapshot = MutableStateFlow(false)
    val hasSnapshot: StateFlow<Boolean> = _hasSnapshot.asStateFlow()

    private var timerJob: Job? = null
    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()
    private var loadDataJob: Job? = null
    private var loadingOrganizationId: String? = null
    private var userRefreshPending = false
    private var activeEntryMonitorJob: Job? = null
    private var monitoredOrganizationId: String? = null
    private var activeEntryRequestGeneration = 0L
    private var dataCollectorJob: Job? = null
    private var syncCollectorJob: Job? = null
    private var syncVisibilityJob: Job? = null
    private var latestSyncOperations: List<TimeEntryRepository.SyncOperation> = emptyList()
    private var firstFrameCacheJob: Job? = null
    private var trackingDraftCacheJob: Job? = null
    private var hasCachedContinueEntry = false
    private var lastCachedContinueEntry: TimeEntry? = null
    private var collectingOrganizationId: String? = null
    private var lastCollectedActiveId: String? = null

    // The active endpoint can learn about a timer before the Room history pull does. Keep that
    // trusted response above an older Room emission; otherwise the collector can immediately
    // erase an externally started timer with its stale "no active row" snapshot.
    private var hasActivePollOverride = false
    private var activePollOverrideOrganizationId: String? = null
    private var activePollOverride: TimeEntry? = null
    private val locallyStoppingEntryIds = mutableSetOf<String>()

    // A deleted running timer stays active on the server until its deferred DELETE syncs; the poll
    // must not bring it back meanwhile. Cleared by undo, or once the server reports no timer.
    private val locallyDeletedEntryIds = mutableSetOf<String>()

    // The Room active row last seen by the collector, to notice edits made to the running entry
    // (details sheet, Calendar, a pull) and keep the editing fields that Stop commits in step.
    private var lastRoomActive: TimeEntry? = null

    // What the tracking notification shows now; the active-entry poll re-posts it only on change.
    private var postedTrackingNotification: TrackingNotificationContent? = null

    // Snapshots for the first-frame cache; written debounced, and only when what they hold changed.
    private val firstFrameSources = MutableStateFlow<FirstFrameSource?>(null)

    @Volatile
    private var lastWrittenFirstFrame: SettingsDataStore.CachedTrackingState? = cachedTrackingState
    private var entryToEditExpiryJob: Job? = null

    // Room emissions can arrive before a mutation coroutine reaches its success/failure branch.
    // Keep this guard separate from [TrackingUiState.isLoading] so a collector cannot re-enable
    // Start/Stop and allow a second callback while the first write is still in flight.
    private var timerMutationInProgress = false
    private var historyOrganizationId: String? = null
    private var historyMemberId: String? = null
    private var historyRequestGeneration = 0L
    private var historyLoadStage = 0
    private var historyOffset = 0
    private var historyWindowStartOffset = 0
    private var historyWindowMode = HistoryWindowMode.RECENT
    private val pendingHistoryMembershipChanges = mutableMapOf<String, HistoryMembershipChange>()
    private var isInitialized = false

    /**
     * Wall-clock of the last foreground-triggered full refresh. Returning to the app (or a rapid
     * start/stop) within [FOREGROUND_REFRESH_DEBOUNCE_MS] reuses the just-fetched data instead of
     * re-hitting the network, while an explicit user refresh remains unthrottled.
     */
    private var lastForegroundRefreshMs: Long? = null

    /**
     * SV-019: one in-flight "commit the delete once the undo window closes" job per soft-deleted
     * entry id. Only the local soft-delete happens synchronously in [deleteTimeEntry]; the actual
     * outbox commit (server DELETE, or cancelling an unsynced entry's START/CREATE - see
     * [TimeEntryRepository.commitDelete]) is deferred to this job so it can be cancelled outright
     * by [undoDelete] with nothing ever having reached the outbox to race the sync worker.
     */
    private val pendingDeleteCommitJobs = mutableMapOf<String, Job>()

    init {
        // Room is now the read source-of-truth; there is no separate snapshot to hydrate.
        _snapshotHydrated.value = true
        _hasSnapshot.value = cachedTrackingState != null
        // Monitor settings changes and update notification state
        viewModelScope.launch {
            settingsDataStore.alwaysShowNotification.collect { enabled ->
                // Only update notification state after initial data load
                if (isInitialized) {
                    updateNotificationState()
                }
            }
        }
        viewModelScope.launch {
            settingsDataStore.autoClearEntryFieldsAfterStop.collect { enabled ->
                autoClearEntryFieldsAfterStopEnabled = enabled
            }
        }
    }

    /**
     * Set whether to always show notifications
     */
    fun setAlwaysShowNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAlwaysShowNotification(enabled)
            // updateNotificationState() is called automatically by the collector in init
        }
    }

    fun setAppTheme(theme: AppThemeMode) {
        viewModelScope.launch { settingsDataStore.setAppTheme(theme) }
    }

    fun setOptimisticRefresh(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setOptimisticRefresh(enabled) }
    }

    fun setLiveUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setLiveUpdateEnabled(enabled) }
    }

    fun setAutoClearEntryFieldsAfterStop(enabled: Boolean) {
        autoClearEntryFieldsAfterStopEnabled = enabled
        viewModelScope.launch { settingsDataStore.setAutoClearEntryFieldsAfterStop(enabled) }
    }

    fun setClearDescriptionAfterStop(enabled: Boolean) {
        clearDescriptionAfterStopEnabled = enabled
        viewModelScope.launch { settingsDataStore.setClearDescriptionAfterStop(enabled) }
    }

    private fun shouldClearDescriptionAfterStop(): Boolean = autoClearEntryFieldsAfterStopEnabled || clearDescriptionAfterStopEnabled

    fun setLongTimerHours(hours: Int) {
        viewModelScope.launch {
            settingsDataStore.setLongTimerHours(hours)
            if (_uiState.value.isTracking) {
                TimeTrackingNotificationService.refreshLongTimerWarning(context)
            }
        }
    }

    /**
     * Update notification state based on tracking status and settings
     */
    private suspend fun updateNotificationState() {
        val alwaysShow = settingsDataStore.alwaysShowNotification.first()
        val isTracking = _uiState.value.isTracking

        if (isTracking) {
            // Active tracking always owns a foreground notification.
            return
        }
        postedTrackingNotification = null
        if (alwaysShow) {
            TimeTrackingNotificationService.showIdle(context)
        } else {
            TimeTrackingNotificationService.hide(context)
        }
    }

    /**
     * Post the running-timer notification for [entry] and return what it shows. With
     * [onlyIfChanged] an unchanged notification is left alone, so the 10-second poll does not
     * restart the foreground service for nothing.
     */
    private fun showTrackingNotification(entry: TimeEntry, onlyIfChanged: Boolean = false): TrackingNotificationContent {
        val content = TrackingNotificationContent(
            start = entry.start,
            projectName = _uiState.value.projects.find { it.id == entry.projectId }?.name,
            taskName = _uiState.value.tasks.find { it.id == entry.taskId }?.name,
            description = entry.description,
            projectId = entry.projectId,
            taskId = entry.taskId,
            organizationId = entry.organizationId,
        )
        if (onlyIfChanged && content == postedTrackingNotification) return content
        postedTrackingNotification = content
        TimeTrackingNotificationService.startTracking(
            context = context,
            startTime = Instant.parse(entry.start),
            projectName = content.projectName,
            taskName = content.taskName,
            description = entry.description,
            projectId = entry.projectId,
            taskId = entry.taskId,
            organizationId = entry.organizationId,
        )
        return content
    }

    private suspend fun showTrackingWidget(entry: TimeEntry, content: TrackingNotificationContent) {
        settingsDataStore.setWidgetTrackingState(
            isTracking = true,
            startTimeEpochMillis = Instant.parse(entry.start).toEpochMilli(),
            projectName = content.projectName,
            taskName = content.taskName,
            description = entry.description,
        )
        TimeTrackingWidget.requestUpdate(context)
    }

    /**
     * Load all data needed for the tracking screen
     */
    fun loadAllData(organizationId: String, memberId: String, userInitiated: Boolean = false) {
        // A refresh can overlap an active poll. Invalidate that response before switching the
        // selected account or starting another refresh; a slow/non-cooperative HTTP call must not
        // write stale tracking state into the next screen.
        activeEntryRequestGeneration++

        // Reads: continuously project the Room source-of-truth into UI state.
        observeLocalData(organizationId, memberId)

        // Refresh: pull fresh data from the network into Room in the background. The
        // collectors above surface the upserts automatically.
        if (loadDataJob?.isActive == true && loadingOrganizationId == organizationId) {
            if (userInitiated) {
                // The initial pull can expose its Room rows before its coroutine has finished.
                // Do not drop an explicit refresh made in that window: queue one pull after the
                // current request so a user asking for fresh server state gets fresh server state.
                userRefreshPending = true
                _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            }
            return
        }
        if (loadingOrganizationId != organizationId) {
            userRefreshPending = false
        }
        loadDataJob?.cancel()
        loadingOrganizationId = organizationId
        loadDataJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isRefreshing = userInitiated,
                error = null,
            )
            // Pull-to-refresh also refetches the catalogue; automatic refreshes reuse a fresh one.
            val refreshResult = timeEntryRepository.refreshAll(organizationId, memberId, forceCatalog = userInitiated)
            // Refresh implementations and test doubles may finish after cancellation. Do not let
            // that stale completion start a monitor or clear a newer screen's refresh state.
            currentCoroutineContext().ensureActive()
            refreshResult.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            refreshResult
                .onFailure { error ->
                    Timber.w(error, "Background refresh failed; showing cached data")
                    if (userInitiated) _uiState.value = _uiState.value.copy(error = error.message)
                }
            currentCoroutineContext().ensureActive()
            if (userRefreshPending && loadingOrganizationId == organizationId) {
                userRefreshPending = false
                loadDataJob = null
                loadAllData(organizationId, memberId, userInitiated = true)
            } else {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
                if (loadingOrganizationId == organizationId) {
                    loadingOrganizationId = null
                }
            }
            startActiveEntryMonitoring(organizationId)
        }
    }

    /**
     * Project the organization's Room data into [TrackingUiState] without refreshing from the
     * network. The app shell calls this as soon as the membership is known, so local history and
     * the full catalogue replace the small first-frame cache even while a refresh waits for account
     * revalidation or the network. [loadAllData] does the same before it refreshes.
     */
    fun observeLocalData(organizationId: String, memberId: String) {
        val historyContextChanged = historyOrganizationId != organizationId || historyMemberId != memberId
        if (historyContextChanged) {
            activeEntryRequestGeneration++
            historyRequestGeneration++
            val canKeepCachedFirstFrame = historyOrganizationId == null && cachedTrackingState?.organizationId == organizationId
            if (!canKeepCachedFirstFrame) resetHistoryForContextSwitch()
        }
        historyOrganizationId = organizationId
        historyMemberId = memberId
        startDataCollectors(organizationId)
    }

    /** Collect the Room-backed flows for an organization into [TrackingUiState]. */
    @Suppress("LongMethod")
    private fun startDataCollectors(organizationId: String) {
        if (collectingOrganizationId == organizationId && dataCollectorJob?.isActive == true) {
            return
        }
        dataCollectorJob?.cancel()
        syncCollectorJob?.cancel()
        syncVisibilityJob?.cancel()
        syncVisibilityJob = null
        firstFrameCacheJob?.cancel()
        latestSyncOperations = emptyList()
        _uiState.value = _uiState.value.copy(syncOperations = emptyList(), syncStatusVisible = false)
        collectingOrganizationId = organizationId
        lastCollectedActiveId = null
        lastRoomActive = null
        historyLoadStage = 1
        historyWindowStartOffset = 0
        historyOffset = 0
        historyWindowMode = HistoryWindowMode.RECENT
        pendingHistoryMembershipChanges.clear()
        clearActivePollOverride()
        firstFrameSources.value = null
        firstFrameCacheJob = launchFirstFrameCacheWriter(organizationId)
        dataCollectorJob = viewModelScope.launch {
            combine(
                combine(
                    timeEntryRepository.observeTimeEntries(organizationId),
                    timeEntryRepository.observeConflicts(organizationId),
                ) { entries, conflicts -> entries to conflicts.map { it.local.id }.toSet() },
                timeEntryRepository.observeProjects(organizationId),
                timeEntryRepository.observeTasks(organizationId),
                combine(
                    timeEntryRepository.observeTags(organizationId),
                    timeEntryRepository.observeClients(organizationId),
                ) { tags, clients -> tags to clients },
                timeEntryRepository.observeActiveEntry(organizationId),
            ) { entriesAndConflicts, projects, tasks, catalog, active ->
                TrackingData(
                    entries = entriesAndConflicts.first,
                    conflictedEntryIds = entriesAndConflicts.second,
                    projects = projects.filterNot { it.isArchived },
                    tasks = tasks.filterNot { it.isDone },
                    tags = catalog.first,
                    clients = catalog.second,
                    active = active,
                )
            }.distinctUntilChanged().map { data ->
                // Per-emission analysis is O(n) over the full entry window; flowOn(Default) below
                // keeps it (plus combine's TrackingData construction and the equality checks of
                // distinctUntilChanged) off the main thread so a Room emission burst cannot
                // produce a long frame mid-scroll.
                CollectedTracking(
                    data = data,
                    overlapCount = EntryTrustRules.overlapCount(data.entries),
                    continueEntry = data.entries
                        .filter { isCompletedTimeEntry(it) && !it.description.isNullOrBlank() }
                        .maxByOrNull { it.start },
                )
            }.flowOn(Dispatchers.Default).conflate().collect { (data, overlapCount, continueEntry) ->
                // Single source of truth: the collector only owns the displayed list (and the
                // paging offset) while the recent slice is on screen. Once the user has paged or
                // jumped, loadMore/jump own the window and offset; here we merely refresh visible
                // entries in place so a poll emission cannot wipe the window or reset scroll.
                val resolvedMembershipChanges = resolvedHistoryMembershipChangeIds(
                    pendingHistoryMembershipChanges,
                    data.entries,
                )
                val displayedEntries = when (historyWindowMode) {
                    HistoryWindowMode.RECENT -> data.entries
                    HistoryWindowMode.PAGINATED -> mergePaginatedWindow(data.entries, resolvedMembershipChanges)
                }
                // Nothing below suspends, so it works on one consistent state.
                val active = activePollOverrideFor(organizationId, data.active)
                val currentState = _uiState.value
                val activeChanged = active?.id != lastCollectedActiveId || active?.start != currentState.currentTimeEntry?.start
                val mode = historyWindowMode
                // The running entry's own row was edited (details sheet, Calendar, a pull): keep the
                // editing fields in step, or the next Stop would commit the old values back.
                val previousRoomActive = lastRoomActive
                lastRoomActive = data.active
                val editedActiveRow = data.active?.takeIf { row ->
                    !activeChanged &&
                        active?.id == row.id &&
                        previousRoomActive?.id == row.id &&
                        !row.hasSameEditableFields(previousRoomActive)
                }
                resolvedMembershipChanges.forEach(pendingHistoryMembershipChanges::remove)
                if (mode == HistoryWindowMode.RECENT) {
                    historyOffset = data.entries.size
                }
                val nextState = currentState.copy(
                    timeEntries = displayedEntries,
                    overlapCount = overlapCount,
                    projects = data.projects,
                    tasks = data.tasks,
                    tags = data.tags,
                    clients = data.clients,
                    conflictedEntryIds = data.conflictedEntryIds,
                    currentTimeEntry = active,
                    isTracking = active != null,
                    hasLoadedTimeEntries = true,
                    // Heuristic: if we filled the refresh window there may be older history
                    // to page in from the network (see loadMoreTimeEntries). Preserve the flag
                    // maintained by loadMore/jump once the user is viewing a paginated window.
                    hasMoreTimeEntries = if (mode == HistoryWindowMode.RECENT) {
                        data.entries.size >= HISTORY_REFRESH_LIMIT
                    } else {
                        currentState.hasMoreTimeEntries
                    },
                    cachedContinueEntry = continueEntry,
                    isLoading = currentState.isLoading || timerMutationInProgress,
                    // Only reset in-progress edits when the active entry itself changes,
                    // so a user's typing is not clobbered by a background emission.
                    editingDescription = when {
                        activeChanged && active != null -> active.description.orEmpty()
                        activeChanged && shouldClearDescriptionAfterStop() -> ""
                        editedActiveRow != null -> editedActiveRow.description.orEmpty()
                        else -> currentState.editingDescription
                    },
                    editingProjectId = when {
                        activeChanged && active != null -> active.projectId
                        activeChanged && autoClearEntryFieldsAfterStopEnabled -> null
                        editedActiveRow != null -> editedActiveRow.projectId
                        else -> currentState.editingProjectId
                    },
                    editingTaskId = when {
                        activeChanged && active != null -> active.taskId
                        activeChanged && autoClearEntryFieldsAfterStopEnabled -> null
                        editedActiveRow != null -> editedActiveRow.taskId
                        else -> currentState.editingTaskId
                    },
                    editingTags = when {
                        activeChanged -> active?.tags?.map { it.id }.orEmpty()
                        editedActiveRow != null -> editedActiveRow.tags.map { it.id }
                        else -> currentState.editingTags
                    },
                    editingBillable = when {
                        activeChanged -> active?.billable ?: false
                        editedActiveRow != null -> editedActiveRow.billable
                        else -> currentState.editingBillable
                    },
                )
                _uiState.value = nextState
                if (activeChanged && active == null) cacheTrackingDraft(nextState)
                // Both caches JSON-encode sizable object graphs; keep that (and the
                // SharedPreferences write) off the main thread, and skip no-op continue writes.
                if (!hasCachedContinueEntry || continueEntry != lastCachedContinueEntry) {
                    hasCachedContinueEntry = true
                    lastCachedContinueEntry = continueEntry
                    viewModelScope.launch(Dispatchers.IO) {
                        settingsDataStore.cacheContinueEntry(continueEntry)
                    }
                }
                firstFrameSources.value = FirstFrameSource(organizationId, data, active, overlapCount)
                if (activeChanged) {
                    lastCollectedActiveId = active?.id
                    if (active != null) startTimer(active.start) else stopTimer()
                }
            }
        }
        syncCollectorJob = viewModelScope.launch {
            timeEntryRepository.observeSyncOperations(organizationId)
                .distinctUntilChanged()
                .collect { operations ->
                    operations.filter { it.type == OutboxOpType.STOP }.forEach { operation ->
                        if (operation.status !in setOf(
                                TimeEntryRepository.EntrySyncStatus.PENDING,
                                TimeEntryRepository.EntrySyncStatus.RETRYING,
                            )
                        ) {
                            locallyStoppingEntryIds.remove(operation.entryId)
                        }
                    }
                    latestSyncOperations = operations
                    updateSyncStatusVisibility(operations)
                    _uiState.value = _uiState.value.copy(syncOperations = operations)
                }
        }
    }

    /**
     * Merges a Room emission into the paginated window on a background thread. loadMore, a jump or
     * a manual create can replace the window while that runs; then the merge is redone against the
     * new window rather than overwriting it with a stale one.
     */
    private suspend fun mergePaginatedWindow(collected: List<TimeEntry>, locallyMutatedEntryIds: Set<String>): List<TimeEntry> {
        repeat(PAGINATED_MERGE_ATTEMPTS) {
            val displayed = _uiState.value.timeEntries
            val canLoadNewerHistory = _uiState.value.canLoadNewerHistory
            val merged = withContext(Dispatchers.Default) {
                HistoryWindow.merge(
                    mode = HistoryWindowMode.PAGINATED,
                    displayed = displayed,
                    collected = collected,
                    locallyMutatedEntryIds = locallyMutatedEntryIds,
                    includesNewestHistory = !canLoadNewerHistory,
                )
            }
            val now = _uiState.value
            if (now.timeEntries === displayed && now.canLoadNewerHistory == canLoadNewerHistory) return merged
        }
        val state = _uiState.value
        return HistoryWindow.merge(
            mode = HistoryWindowMode.PAGINATED,
            displayed = state.timeEntries,
            collected = collected,
            locallyMutatedEntryIds = locallyMutatedEntryIds,
            includesNewestHistory = !state.canLoadNewerHistory,
        )
    }

    /**
     * Writes the first-frame cache for [organizationId] off the main thread: debounced, trimmed to
     * what the first frame shows, and skipped when that did not change, so ordinary Room and sync
     * updates no longer re-encode and rewrite the whole snapshot.
     */
    @OptIn(FlowPreview::class)
    private fun launchFirstFrameCacheWriter(organizationId: String): Job = viewModelScope.launch(Dispatchers.Default) {
        firstFrameSources
            .filterNotNull()
            .filter { it.organizationId == organizationId }
            .debounce(FIRST_FRAME_CACHE_DEBOUNCE_MS)
            .map { source ->
                firstFrameCacheOf(
                    organizationId = source.organizationId,
                    entries = source.data.entries,
                    active = source.active,
                    projects = source.data.projects,
                    clients = source.data.clients,
                    tasks = source.data.tasks,
                    tags = source.data.tags,
                    overlapCount = source.overlapCount,
                )
            }
            .distinctUntilChanged()
            .collect { snapshot ->
                if (snapshot != lastWrittenFirstFrame) {
                    withContext(Dispatchers.IO) { settingsDataStore.cacheTrackingState(snapshot) }
                    lastWrittenFirstFrame = snapshot
                }
                _hasSnapshot.value = true
            }
    }

    private fun clearActivePollOverride() {
        // A local timer mutation supersedes any request that was started before the mutation. A
        // late response from that request must not restore the pre-mutation server timer.
        activeEntryRequestGeneration++
        hasActivePollOverride = false
        activePollOverrideOrganizationId = null
        activePollOverride = null
    }

    private fun setActivePollOverride(organizationId: String, active: TimeEntry?) {
        hasActivePollOverride = true
        activePollOverrideOrganizationId = organizationId
        activePollOverride = active
    }

    private fun activePollOverrideFor(organizationId: String, roomActive: TimeEntry?): TimeEntry? =
        if (hasActivePollOverride && activePollOverrideOrganizationId == organizationId) {
            selectVisibleActiveEntry(roomActive, activePollOverride)
        } else {
            roomActive
        }

    private fun resetHistoryForContextSwitch() {
        historyLoadStage = 1
        historyWindowStartOffset = 0
        historyOffset = 0
        historyWindowMode = HistoryWindowMode.RECENT
        _uiState.value = _uiState.value.copy(
            isTracking = false,
            currentTimeEntry = null,
            timeEntries = emptyList(),
            overlapCount = 0,
            hasLoadedTimeEntries = false,
            isLoadingMoreTimeEntries = false,
            hasMoreTimeEntries = false,
            totalTimeEntries = null,
            historyJumpDate = null,
            historyJumpTarget = null,
            historyJumpProgress = null,
            historyRateLimitWaitSeconds = null,
            canLoadNewerHistory = false,
            cachedContinueEntry = null,
            projects = emptyList(),
            clients = emptyList(),
            tasks = emptyList(),
            tags = emptyList(),
            editingDescription = "",
            editingProjectId = null,
            editingTaskId = null,
            editingTags = emptyList(),
            editingBillable = false,
            conflictedEntryIds = emptySet(),
        )
        trackingDraftCacheJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { settingsDataStore.cacheTrackingDraft(null) }
    }

    private fun isCurrentHistoryRequest(organizationId: String, memberId: String, generation: Long): Boolean =
        historyOrganizationId == organizationId && historyMemberId == memberId && historyRequestGeneration == generation

    private fun updateSyncStatusVisibility(operations: List<TimeEntryRepository.SyncOperation>) {
        val hasError = operations.any { operation ->
            operation.status in setOf(
                TimeEntryRepository.EntrySyncStatus.FAILED,
                TimeEntryRepository.EntrySyncStatus.CONFLICT,
            )
        }
        if (hasError) {
            syncVisibilityJob?.cancel()
            syncVisibilityJob = null
            _uiState.value = _uiState.value.copy(syncStatusVisible = true)
            return
        }

        val hasPendingWork = operations.any { operation ->
            operation.status in setOf(
                TimeEntryRepository.EntrySyncStatus.PENDING,
                TimeEntryRepository.EntrySyncStatus.RETRYING,
            )
        }
        if (!hasPendingWork) {
            syncVisibilityJob?.cancel()
            syncVisibilityJob = null
            _uiState.value = _uiState.value.copy(syncStatusVisible = false)
            return
        }

        if (_uiState.value.syncStatusVisible || syncVisibilityJob?.isActive == true) return
        syncVisibilityJob = viewModelScope.launch {
            delay(SYNC_STATUS_REVEAL_DELAY_MS)
            if (latestSyncOperations.any { operation ->
                    operation.status in setOf(
                        TimeEntryRepository.EntrySyncStatus.PENDING,
                        TimeEntryRepository.EntrySyncStatus.RETRYING,
                    )
                }
            ) {
                _uiState.value = _uiState.value.copy(syncStatusVisible = true)
            }
            syncVisibilityJob = null
        }
    }

    /** Deterministic seam for timing tests; production sync updates use the same state transition. */
    internal fun acceptSyncOperationsForTest(operations: List<TimeEntryRepository.SyncOperation>) {
        latestSyncOperations = operations
        updateSyncStatusVisibility(operations)
        _uiState.value = _uiState.value.copy(syncOperations = operations)
    }

    private data class TrackingData(
        val entries: List<TimeEntry>,
        val conflictedEntryIds: Set<String>,
        val projects: List<Project>,
        val clients: List<Client>,
        val tasks: List<Task>,
        val tags: List<Tag>,
        val active: TimeEntry?,
    )

    /** [TrackingData] plus the derived values computed off the main thread. */
    private data class CollectedTracking(val data: TrackingData, val overlapCount: Int, val continueEntry: TimeEntry?)

    /**
     * One collector emission handed to the first-frame cache writer. Not a data class: publishing
     * it must not compare whole entry lists on the main thread.
     */
    private class FirstFrameSource(val organizationId: String, val data: TrackingData, val active: TimeEntry?, val overlapCount: Int)

    /** The content of the running-timer notification; the poll re-posts it only when this changes. */
    private data class TrackingNotificationContent(
        val start: String,
        val projectName: String?,
        val taskName: String?,
        val description: String?,
        val projectId: String?,
        val taskId: String?,
        val organizationId: String,
    )

    /**
     * Keep notification state in sync with timers started or stopped on another device while
     * this ViewModel is alive. Changing organizations replaces the old monitor immediately.
     */
    private fun startActiveEntryMonitoring(organizationId: String) {
        if (monitoredOrganizationId == organizationId && activeEntryMonitorJob?.isActive == true) {
            return
        }

        activeEntryMonitorJob?.cancel()
        monitoredOrganizationId = organizationId
        activeEntryMonitorJob = viewModelScope.launch {
            while (true) {
                delay(ACTIVE_ENTRY_REFRESH_INTERVAL_MS)
                loadActiveTimeEntry(organizationId, onlyIfChanged = true)
            }
        }
    }

    /** Pause network polling while the app is not visible. */
    fun onAppBackgrounded() {
        // Invalidate one-shot foreground lookups as well as the repeating monitor. The lookup is
        // intentionally not cancelled here because an HTTP implementation may ignore coroutine
        // cancellation; its late result is still stale once the screen is backgrounded.
        activeEntryRequestGeneration++
        activeEntryMonitorJob?.cancel()
        activeEntryMonitorJob = null
    }

    /**
     * Resume polling, refreshing all visible data after a longer background pause.
     *
     * The full-refresh path is debounced on wall-clock so that returning within a few seconds (or a
     * rapid start/stop) does not spam the network; the in-screen poll and the collectors already
     * keep an open screen fresh, so the foreground refresh only matters when the screen has gone
     * stale. Pending local changes are flushed via [SyncTrigger.requestSync] on the same schedule.
     */
    fun onAppForegrounded(organizationId: String, memberId: String, refreshAll: Boolean) {
        // The notification may have been dismissed or its service stopped while the app was away;
        // let the next poll post it once more even if the timer did not change.
        postedTrackingNotification = null
        if (refreshAll) {
            val now = clock.nowMs()
            val last = lastForegroundRefreshMs
            if (last != null && now - last < FOREGROUND_REFRESH_DEBOUNCE_MS) {
                // Debounced: the data fetched moments ago is still fresh. Keep polling alive.
                startActiveEntryMonitoring(organizationId)
                return
            }
            lastForegroundRefreshMs = now
            // Flush any queued local changes to the server before we re-read fresh state.
            syncTrigger.requestSync()
            loadAllData(organizationId, memberId)
        } else {
            // Returning from the tile picker is usually too brief to trigger a full refresh,
            // but its quick-start request may have changed the active entry.
            viewModelScope.launch {
                loadActiveTimeEntry(organizationId, onlyIfChanged = true)
            }
            startActiveEntryMonitoring(organizationId)
        }
    }

    /**
     * Cancels [viewModelScope] for unit tests that install a test Main dispatcher; mirrors the
     * sync-center VM teardown so no Main-bound collector straggles past `Dispatchers.resetMain()`.
     * Returns the canceled scope job so tests can await completion before closing Room resources.
     * Not used in production.
     */
    @VisibleForTesting
    internal fun cancelScopeForTest(): Job? {
        val scopeJob = viewModelScope.coroutineContext[Job]
        scopeJob?.cancel()
        return scopeJob
    }

    /**
     * Load the active time entry for the current user
     */
    private suspend fun loadActiveTimeEntry(organizationId: String, onlyIfChanged: Boolean = false) {
        val requestGeneration = ++activeEntryRequestGeneration
        authRepository.getActiveTimeEntry()
            .onSuccess { timeEntry ->
                if (requestGeneration != activeEntryRequestGeneration) return@onSuccess
                // The active-entry endpoint is account-wide. Only surface an entry for the
                // organization currently selected in the app.
                val serverTimeEntry = timeEntry?.takeIf { it.organizationId == organizationId }
                if (serverTimeEntry == null) {
                    locallyStoppingEntryIds.clear()
                    locallyDeletedEntryIds.clear()
                }
                val polledTimeEntry = serverTimeEntry
                    ?.takeUnless {
                        it.id in locallyStoppingEntryIds ||
                            it.id in locallyDeletedEntryIds ||
                            shouldDeferServerActiveWhileStopping(it.id, _uiState.value.syncOperations)
                    }
                // A locally started timer whose START/CREATE is still in the outbox does not
                // exist on the server yet. A poll or foreground refresh answering "no active
                // entry" must not clear it, or the user's running timer silently disappears
                // until the next sync (found by TrackingLifecycleE2eTest recreation flow).
                val local = _uiState.value.currentTimeEntry
                if (polledTimeEntry == null &&
                    local != null &&
                    shouldPreserveLocallyStartedEntry(local.id, _uiState.value.syncOperations)
                ) {
                    return@onSuccess
                }
                val currentTimeEntry = selectVisibleActiveEntry(local, polledTimeEntry)
                setActivePollOverride(organizationId, currentTimeEntry)
                if (onlyIfChanged &&
                    currentTimeEntry?.id == _uiState.value.currentTimeEntry?.id
                ) {
                    // Nothing changed hands: re-post the notification only if what it shows changed,
                    // instead of restarting the foreground service every poll.
                    if (currentTimeEntry != null) {
                        showTrackingNotification(currentTimeEntry, onlyIfChanged = true)
                    } else {
                        updateNotificationState()
                    }
                    return@onSuccess
                }
                val isTracking = currentTimeEntry != null
                _uiState.value = _uiState.value.copy(
                    isTracking = isTracking,
                    currentTimeEntry = currentTimeEntry,
                    editingDescription = currentTimeEntry?.description ?: "",
                    editingProjectId = currentTimeEntry?.projectId,
                    editingTaskId = currentTimeEntry?.taskId,
                    editingTags = currentTimeEntry?.tags?.map { it.id } ?: emptyList(),
                    editingBillable = currentTimeEntry?.billable ?: false,
                )

                // Update notification state based on tracking status and settings
                if (currentTimeEntry != null) {
                    val content = showTrackingNotification(currentTimeEntry)
                    settingsDataStore.setWidgetTrackingState(
                        isTracking = true,
                        startTimeEpochMillis = Instant.parse(currentTimeEntry.start).toEpochMilli(),
                        projectName = content.projectName,
                        taskName = content.taskName,
                        description = currentTimeEntry.description,
                    )
                } else {
                    // Update notification and widget state for non-tracking cases
                    updateNotificationState()
                    settingsDataStore.setWidgetTrackingState(
                        isTracking = false,
                    )
                }
                // Request widget update
                TimeTrackingWidget.requestUpdate(context)

                // Start timer if tracking
                if (isTracking) {
                    startTimer(currentTimeEntry.start)
                } else {
                    stopTimer()
                }

                // Mark as initialized after first load
                isInitialized = true
            }
            .onFailure { error ->
                if (requestGeneration != activeEntryRequestGeneration) return@onFailure
                if (error is CancellationException) throw error
                Timber.e(error, "Failed to load active time entry")
                // An intermittent poll failure must not turn a cached/running timer into an idle
                // state. Keep the last trusted UI and notification surface; the next poll or
                // foreground refresh can replace it when the network recovers.
                _uiState.value = _uiState.value.copy(
                    error = error.message ?: context.getString(R.string.error_load_tracking_state),
                )

                // Mark as initialized even on failure
                isInitialized = true
            }
    }

    /** Load history progressively while retaining fetched entries for this app session. */
    fun loadMoreTimeEntries() {
        val organizationId = historyOrganizationId ?: return
        val memberId = historyMemberId ?: return
        val state = _uiState.value
        if (state.isLoadingMoreTimeEntries || !state.hasMoreTimeEntries) return

        // Set the guard before launching. Two scroll callbacks can arrive in the same main-loop
        // turn before the coroutine gets its first slice; setting it inside the coroutine lets
        // both callbacks issue the same page request.
        _uiState.value = state.copy(isLoadingMoreTimeEntries = true)
        val requestGeneration = historyRequestGeneration
        viewModelScope.launch {
            val (limit, offset) = when (historyLoadStage) {
                0 -> FIRST_SCROLL_TOTAL to 0
                1 -> MAX_PAGE_SIZE to 0
                else -> MAX_PAGE_SIZE to historyOffset
            }
            authRepository.getTimeEntries(organizationId, memberId, limit, offset)
                .onSuccess { response ->
                    if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@onSuccess
                    val currentEntries = _uiState.value.timeEntries
                    val currentTags = _uiState.value.tags
                    // Tag resolution plus the dedupe/sort of a growing window is O(n log n);
                    // keep it off the main thread so paging in more history cannot jank a
                    // scroll that is still settling.
                    val (incoming, computed) = withContext(Dispatchers.Default) {
                        val tagsById = currentTags.associateBy { it.id }
                        val resolved = response.data.map { entry ->
                            entry.copy(tags = entry.tags.map { tagsById[it.id] ?: it })
                        }
                        resolved to (currentEntries + resolved)
                            .distinctBy { it.id }
                            .sortedByDescending { it.start }
                    }
                    if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@onSuccess
                    // A Room emission may have refreshed the window during the merge; keep its rows.
                    val latest = _uiState.value.timeEntries
                    val merged = if (latest === currentEntries) {
                        computed
                    } else {
                        (latest + incoming).distinctBy { it.id }.sortedByDescending { it.start }
                    }
                    val total = response.meta?.total ?: _uiState.value.totalTimeEntries
                    historyOffset = if (historyLoadStage <= 1) {
                        incoming.size
                    } else {
                        historyOffset + incoming.size
                    }
                    historyLoadStage++
                    // The user has explicitly asked for more than the recent slice; the collector
                    // must now preserve this grown window instead of replacing it on every poll.
                    historyWindowMode = HistoryWindowMode.PAGINATED
                    _uiState.value = _uiState.value.copy(
                        timeEntries = merged,
                        isLoadingMoreTimeEntries = false,
                        error = null,
                        hasMoreTimeEntries = incoming.isNotEmpty() &&
                            (total?.let { merged.size < it } ?: true),
                        totalTimeEntries = total,
                    )
                    // Once the user asks for more history, quickly fill the first maximum-sized
                    // buffer so continued scrolling does not catch the network boundary.
                    if (historyLoadStage == 1 && _uiState.value.hasMoreTimeEntries) {
                        loadMoreTimeEntries()
                    }
                }
                .onFailure { error ->
                    if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@onFailure
                    if (error is CancellationException) throw error
                    Timber.e(error, "Failed to load more time entries")
                    _uiState.value = _uiState.value.copy(
                        isLoadingMoreTimeEntries = false,
                        error = error.message ?: context.getString(R.string.error_load_more_entries),
                    )
                }
        }
    }

    @Suppress("LongMethod", "LoopWithTooManyJumpStatements", "ThrowsCount")
    fun jumpToHistoryDate(date: LocalDate) {
        val organizationId = historyOrganizationId ?: return
        val memberId = historyMemberId ?: return
        if (_uiState.value.isLoadingMoreTimeEntries) return
        _uiState.value = _uiState.value.copy(
            isLoadingMoreTimeEntries = true,
            historyJumpTarget = date,
            historyJumpProgress = 0f,
        )
        val requestGeneration = historyRequestGeneration
        viewModelScope.launch {
            val total = _uiState.value.totalTimeEntries ?: getHistoryPageWithRateLimit(
                organizationId,
                memberId,
                limit = 1,
                offset = 0,
            ).getOrElse { error ->
                if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
                if (error is CancellationException) throw error
                _uiState.value = _uiState.value.copy(
                    isLoadingMoreTimeEntries = false,
                    historyJumpTarget = null,
                    historyJumpProgress = null,
                    historyRateLimitWaitSeconds = null,
                    error = error.message,
                )
                return@launch
            }.meta?.total ?: 0
            var low = 0
            var high = (total - 1).coerceAtLeast(0)
            var matchOffset = 0
            var exactMatch = false
            val expectedProbes = if (total > 1) {
                kotlin.math.ceil(kotlin.math.log2(total.toDouble())).toInt()
            } else {
                1
            }
            var completedProbes = 0
            while (low <= high) {
                val middle = (low + high) ushr 1
                val probe = getHistoryPageWithRateLimit(
                    organizationId,
                    memberId,
                    limit = 1,
                    offset = middle,
                ).getOrElse { error ->
                    if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(
                        isLoadingMoreTimeEntries = false,
                        historyJumpTarget = null,
                        historyJumpProgress = null,
                        historyRateLimitWaitSeconds = null,
                        error = error.message ?: context.getString(R.string.error_load_date),
                    )
                    return@launch
                }
                if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
                val probeDate = probe.data.firstOrNull()?.let { entry -> historyEntryStartDate(entry, _uiState.value.zone) }
                    ?: break
                completedProbes++
                _uiState.value = _uiState.value.copy(
                    historyJumpProgress = (completedProbes.toFloat() / (expectedProbes + 1))
                        .coerceAtMost(HISTORY_PROGRESS_CAP),
                )
                matchOffset = middle
                when {
                    probeDate > date -> low = middle + 1
                    probeDate < date -> high = middle - 1
                    else -> {
                        exactMatch = true
                        break
                    }
                }
            }
            if (!exactMatch) matchOffset = low.coerceIn(0, (total - 1).coerceAtLeast(0))
            _uiState.value = _uiState.value.copy(historyJumpProgress = 0.92f)
            val windowStart = (matchOffset - MAX_PAGE_SIZE / 2).coerceAtLeast(0)
            val response = getHistoryPageWithRateLimit(
                organizationId,
                memberId,
                limit = MAX_PAGE_SIZE,
                offset = windowStart,
            ).getOrElse { error ->
                if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
                if (error is CancellationException) throw error
                _uiState.value = _uiState.value.copy(
                    isLoadingMoreTimeEntries = false,
                    historyJumpTarget = null,
                    historyJumpProgress = null,
                    historyRateLimitWaitSeconds = null,
                    error = error.message,
                )
                return@launch
            }
            if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
            val tagsById = _uiState.value.tags.associateBy { it.id }
            val carryInEntries = loadEntriesOverlappingDate(organizationId, memberId, date).getOrElse { error ->
                if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
                if (error is CancellationException) throw error
                _uiState.value = _uiState.value.copy(
                    isLoadingMoreTimeEntries = false,
                    historyJumpTarget = null,
                    historyJumpProgress = null,
                    historyRateLimitWaitSeconds = null,
                    error = error.message,
                )
                return@launch
            }
            if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
            val fetchedWindow = (response.data + carryInEntries).distinctBy { it.id }.map { entry ->
                entry.copy(tags = entry.tags.map { tagsById[it.id] ?: it })
            }
            // Unsynced local rows exist only in Room; the merge keeps them inside the fetched range.
            val window = HistoryWindow.merge(
                mode = HistoryWindowMode.PAGINATED,
                displayed = fetchedWindow,
                collected = timeEntryRepository.observeTimeEntries(organizationId).first(),
                includesNewestHistory = windowStart == 0,
            ).sortedByDescending { it.start }
            if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@launch
            historyWindowStartOffset = windowStart
            historyOffset = windowStart + response.data.size
            historyLoadStage = 2
            // The jumped-to window is authoritative; keep the collector from replacing it.
            historyWindowMode = HistoryWindowMode.PAGINATED
            _uiState.value = _uiState.value.copy(
                timeEntries = window,
                totalTimeEntries = response.meta?.total ?: total,
                hasMoreTimeEntries = historyOffset < total,
                canLoadNewerHistory = windowStart > 0,
                isLoadingMoreTimeEntries = false,
                historyJumpTarget = null,
                historyJumpProgress = null,
                historyRateLimitWaitSeconds = null,
                historyJumpDate = date,
            )
        }
    }

    fun loadNewerTimeEntries() {
        val organizationId = historyOrganizationId ?: return
        val memberId = historyMemberId ?: return
        if (_uiState.value.isLoadingMoreTimeEntries || historyWindowStartOffset <= 0) return
        _uiState.value = _uiState.value.copy(isLoadingMoreTimeEntries = true)
        val requestGeneration = historyRequestGeneration
        viewModelScope.launch {
            val newStart = (historyWindowStartOffset - MAX_PAGE_SIZE).coerceAtLeast(0)
            authRepository.getTimeEntries(
                organizationId,
                memberId,
                limit = historyWindowStartOffset - newStart,
                offset = newStart,
            ).onSuccess { response ->
                if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@onSuccess
                val currentTags = _uiState.value.tags
                val displayed = _uiState.value.timeEntries
                val (incoming, merged) = withContext(Dispatchers.Default) {
                    val tagsById = currentTags.associateBy { it.id }
                    val incoming = response.data.map { entry ->
                        entry.copy(tags = entry.tags.map { tagsById[it.id] ?: it })
                    }
                    incoming to (incoming + displayed).distinctBy { it.id }.sortedByDescending { it.start }
                }
                if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@onSuccess
                historyWindowStartOffset = newStart
                historyWindowMode = HistoryWindowMode.PAGINATED
                val latest = _uiState.value.timeEntries
                _uiState.value = _uiState.value.copy(
                    // A Room emission may have refreshed the window meanwhile; merge onto that one.
                    timeEntries = if (latest === displayed) {
                        merged
                    } else {
                        (incoming + latest).distinctBy { it.id }.sortedByDescending { it.start }
                    },
                    isLoadingMoreTimeEntries = false,
                    canLoadNewerHistory = newStart > 0,
                )
            }.onFailure { error ->
                if (!isCurrentHistoryRequest(organizationId, memberId, requestGeneration)) return@onFailure
                if (error is CancellationException) throw error
                _uiState.value = _uiState.value.copy(isLoadingMoreTimeEntries = false, error = error.message)
            }
        }
    }

    fun consumeHistoryJump() {
        _uiState.value = _uiState.value.copy(historyJumpDate = null)
    }

    private suspend fun getHistoryPageWithRateLimit(
        organizationId: String,
        memberId: String,
        limit: Int,
        offset: Int,
        start: String? = null,
        end: String? = null,
    ): Result<dev.tricked.solidverdant.data.model.TimeEntriesResponse> {
        repeat(RATE_LIMIT_RETRY_ATTEMPTS) {
            val result = authRepository.getTimeEntries(
                organizationId,
                memberId,
                limit,
                offset,
                start = start,
                end = end,
            )
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            if (error !is retrofit2.HttpException || error.code() != HTTP_TOO_MANY_REQUESTS) return result
            val waitSeconds = error.response()?.headers()?.get("Retry-After")
                ?.toIntOrNull()?.coerceIn(MIN_RETRY_AFTER_SECONDS, MAX_RETRY_AFTER_SECONDS)
                ?: DEFAULT_RETRY_AFTER_SECONDS
            _uiState.value = _uiState.value.copy(historyRateLimitWaitSeconds = waitSeconds)
            delay(waitSeconds * MILLIS_PER_SECOND)
            _uiState.value = _uiState.value.copy(historyRateLimitWaitSeconds = null)
        }
        return Result.failure(IllegalStateException("Rate limit retry exhausted"))
    }

    /**
     * Solidtime's date bounds filter entry starts, not interval intersection. A date jump therefore
     * has to scan every entry that started before the target day's end and retain the intervals
     * that actually overlap the day; otherwise a long carry-in can sit arbitrarily far before the
     * normal binary-search window.
     */
    private suspend fun loadEntriesOverlappingDate(organizationId: String, memberId: String, date: LocalDate): Result<List<TimeEntry>> =
        try {
            val zone = _uiState.value.zone
            val queryEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toString()
            val now = Instant.ofEpochMilli(clock.nowMs())
            val matches = mutableListOf<TimeEntry>()
            val seenIds = mutableSetOf<String>()
            var offset = 0
            while (true) {
                val page = getHistoryPageWithRateLimit(
                    organizationId = organizationId,
                    memberId = memberId,
                    limit = MAX_PAGE_SIZE,
                    offset = offset,
                    start = null,
                    end = queryEnd,
                ).getOrThrow()
                val newEntries = page.data.filter { seenIds.add(it.id) }
                matches += newEntries.filter {
                    timeEntryOverlapsLocalDateRange(it, date, date, zone, now)
                }
                offset += page.data.size
                val total = page.meta?.total
                if (page.data.size < MAX_PAGE_SIZE || (total != null && offset >= total) || newEntries.isEmpty()) break
            }
            Result.success(matches)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Start the elapsed time timer
     */
    private fun startTimer(startTimeString: String) {
        stopTimer() // Stop any existing timer

        timerJob = viewModelScope.launch {
            try {
                // Parse the start time
                val startTime =
                    ZonedDateTime.parse(startTimeString, DateTimeFormatter.ISO_DATE_TIME)
                val startInstant = startTime.toInstant()

                while (true) {
                    val now = Instant.now()
                    // Clamp: a device clock behind the entry's start would otherwise yield a
                    // negative elapsed value and render as garbage (e.g. "-1:-5:-3").
                    val elapsed = (now.epochSecond - startInstant.epochSecond).coerceAtLeast(0)
                    _elapsedSeconds.value = elapsed
                    delay(TIMER_TICK_INTERVAL_MS) // Update every second
                }
            } catch (e: CancellationException) {
                // Expected when timer is stopped, don't log as error
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse start time or run timer")
            }
        }
    }

    /**
     * Stop the elapsed time timer
     */
    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _elapsedSeconds.value = 0
    }

    /**
     * Update editing description
     */
    fun updateDescription(description: String) {
        _uiState.value = _uiState.value.copy(editingDescription = description)
        cacheTrackingDraft(_uiState.value)
    }

    /**
     * Update editing project
     */
    fun updateProject(projectId: String?) {
        _uiState.value = _uiState.value.copy(
            editingProjectId = projectId,
            // Clear task if project changed
            editingTaskId = if (projectId != _uiState.value.editingProjectId) null else _uiState.value.editingTaskId,
        )
        cacheTrackingDraft(_uiState.value)
    }

    /**
     * Update editing task
     */
    fun updateTask(taskId: String?) {
        _uiState.value = _uiState.value.copy(editingTaskId = taskId)
        cacheTrackingDraft(_uiState.value)
    }

    /** Clear only the reusable entry identity fields; tags and billable remain unchanged. */
    fun resetEntryFields() {
        val currentState = _uiState.value
        if (currentState.isTracking || currentState.isPaused) return
        _uiState.value = currentState.copy(
            editingDescription = "",
            editingProjectId = null,
            editingTaskId = null,
        )
        cacheTrackingDraft(_uiState.value)
    }

    private fun cacheTrackingDraft(state: TrackingUiState) {
        val organizationId = collectingOrganizationId ?: cachedTrackingState?.organizationId ?: return
        trackingDraftCacheJob?.cancel()
        trackingDraftCacheJob = viewModelScope.launch(Dispatchers.IO) {
            delay(TRACKING_DRAFT_CACHE_DEBOUNCE_MS)
            settingsDataStore.cacheTrackingDraft(
                SettingsDataStore.CachedTrackingDraft(
                    organizationId = organizationId,
                    description = state.editingDescription,
                    projectId = state.editingProjectId,
                    taskId = state.editingTaskId,
                ),
            )
        }
    }

    /**
     * Update editing tags
     */
    fun updateTags(tags: List<String>) {
        _uiState.value = _uiState.value.copy(editingTags = tags)
    }

    /**
     * Update editing billable
     */
    fun updateBillable(billable: Boolean) {
        _uiState.value = _uiState.value.copy(editingBillable = billable)
    }

    /**
     * Start a new time entry with current editing state
     */
    fun startTimeEntry(organizationId: String, memberId: String, userId: String) {
        if (_uiState.value.currentTimeEntry != null || _uiState.value.isTracking || _uiState.value.isPaused) {
            Timber.d("Ignoring start while a timer is already active or paused")
            return
        }
        if (!beginTimerMutation()) return
        clearActivePollOverride()
        viewModelScope.launch {
            try {
                // Optimistic local write + outbox enqueue. The Room collector surfaces the
                // new active entry and starts the timer.
                val timeEntry = timeEntryRepository.startEntry(
                    organizationId = organizationId,
                    memberId = memberId,
                    userId = userId,
                    projectId = _uiState.value.editingProjectId,
                    taskId = _uiState.value.editingTaskId,
                    description = _uiState.value.editingDescription,
                    tagIds = _uiState.value.editingTags,
                    billable = _uiState.value.editingBillable,
                )
                // startEntry returns the row that was committed to Room. Project that durable
                // value before notification/widget side effects so a fresh-login Stop action
                // never depends on the slower combined Room collector winning the race.
                _uiState.value = _uiState.value.copy(
                    isTracking = true,
                    currentTimeEntry = timeEntry,
                )
                syncTrigger.requestSync()

                // Active timers always have a foreground notification.
                showTrackingWidget(timeEntry, showTrackingNotification(timeEntry))

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                )
                timerMutationInProgress = false
                Timber.d("Time entry started successfully (optimistic)")
            } catch (e: Exception) {
                handleTimerMutationFailure(e, R.string.error_start_entry)
            }
        }
    }

    /**
     * Update the current active time entry
     */
    fun updateCurrentTimeEntry(timeEntry: TimeEntry? = null, tags: List<String>? = null) {
        val entryToUpdate = timeEntry ?: _uiState.value.currentTimeEntry
        if (entryToUpdate == null) {
            Timber.w("No active time entry to update")
            return
        }

        clearActivePollOverride()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val editingTags = tags ?: _uiState.value.editingTags
                val updatedEntry = entryToUpdate.copy(
                    description = _uiState.value.editingDescription,
                    projectId = _uiState.value.editingProjectId,
                    taskId = _uiState.value.editingTaskId,
                    billable = _uiState.value.editingBillable,
                    tags = editingTags.map { Tag(it) },
                )

                timeEntryRepository.updateEntry(updatedEntry, editingTags)
                syncTrigger.requestSync()

                // Reassert the foreground notification with the edited details.
                if (isRunningTimeEntry(updatedEntry)) {
                    showTrackingWidget(updatedEntry, showTrackingNotification(updatedEntry))
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    editingTags = editingTags,
                )
                Timber.d("Time entry updated successfully (optimistic)")
            } catch (e: Exception) {
                handleMutationFailure(e, R.string.error_update_entry)
            }
        }
    }

    /**
     * Stop the active time entry. [edits] are the running entry's details-sheet fields when Stop is
     * tapped there; they are committed with the stop instead of being dropped with the sheet.
     */
    fun stopTimeEntry(edits: RunningEntryEdits? = null) {
        val currentEntry = _uiState.value.currentTimeEntry

        // If paused, the entry is already stopped - just clear the paused state
        if (currentEntry == null && _uiState.value.isPaused) {
            val currentState = _uiState.value
            _uiState.value = currentState.copy(
                isPaused = false,
                editingDescription = if (shouldClearDescriptionAfterStop()) "" else currentState.editingDescription,
                editingProjectId = if (autoClearEntryFieldsAfterStopEnabled) null else currentState.editingProjectId,
                editingTaskId = if (autoClearEntryFieldsAfterStopEnabled) null else currentState.editingTaskId,
                editingTags = emptyList(),
                editingBillable = false,
            )
            cacheTrackingDraft(_uiState.value)
            viewModelScope.launch {
                updateNotificationState()
                settingsDataStore.setWidgetTrackingState(isTracking = false)
                TimeTrackingWidget.requestUpdate(context)
            }
            Timber.d("Cleared paused state")
            return
        }

        if (currentEntry == null) {
            Timber.w("No active time entry to stop")
            return
        }

        if (!beginTimerMutation()) return
        edits?.let(::applyRunningEntryEdits)

        // Active polling can complete between the local STOP transaction and the outbox observer
        // emission. Suppress that exact server id synchronously while the STOP is being queued.
        locallyStoppingEntryIds += currentEntry.id
        pendingHistoryMembershipChanges[currentEntry.id] = HistoryMembershipChange.COMPLETED_ENTRY_PRESENT
        clearActivePollOverride()

        viewModelScope.launch {
            try {
                // Commit the editable running-entry fields atomically with the stop. Otherwise a
                // fast Stop can leave metadata only in UI state while timestamp sync wins.
                val stopTarget = retimeRunningEntry(currentEntry, edits)
                val editingTags = _uiState.value.editingTags
                val editedEntry = stopTarget.copy(
                    description = _uiState.value.editingDescription,
                    projectId = _uiState.value.editingProjectId,
                    taskId = _uiState.value.editingTaskId,
                    billable = _uiState.value.editingBillable,
                    tags = editingTags.map(::Tag),
                )
                timeEntryRepository.stopEntryWithEdits(
                    entry = stopTarget,
                    userId = stopTarget.userId,
                    editedEntry = editedEntry,
                    tagIds = editingTags,
                )
                syncTrigger.requestSync()

                val currentState = _uiState.value
                _uiState.value = currentState.copy(
                    isTracking = false,
                    isPaused = false,
                    currentTimeEntry = null,
                    editingDescription = if (shouldClearDescriptionAfterStop()) "" else currentState.editingDescription,
                    editingProjectId = if (autoClearEntryFieldsAfterStopEnabled) null else currentState.editingProjectId,
                    editingTaskId = if (autoClearEntryFieldsAfterStopEnabled) null else currentState.editingTaskId,
                    editingTags = emptyList(),
                    editingBillable = false,
                )
                cacheTrackingDraft(_uiState.value)
                stopTimer()
                lastCollectedActiveId = null
                Timber.d("Time entry stopped successfully (optimistic)")

                // Update notification state (will switch to idle or hide based on settings)
                updateNotificationState()

                // Update widget state to idle
                settingsDataStore.setWidgetTrackingState(isTracking = false)
                TimeTrackingWidget.requestUpdate(context)

                timerMutationInProgress = false
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                locallyStoppingEntryIds.remove(currentEntry.id)
                handleTimerMutationFailure(e, R.string.error_stop_entry)
            }
        }
    }

    /**
     * Pause the active time entry - stops it via API but keeps notification in paused state
     * preserving the project/task/description for easy resume. [edits] are the details-sheet fields
     * when Pause is tapped there: they are saved on the paused entry and resumed with.
     */
    fun pauseTimeEntry(edits: RunningEntryEdits? = null) {
        val currentEntry = _uiState.value.currentTimeEntry
        if (currentEntry == null) {
            Timber.w("No active time entry to pause")
            return
        }

        if (!beginTimerMutation()) return
        edits?.let(::applyRunningEntryEdits)

        // Like Stop: keep a poll racing the STOP from showing the paused timer as running again.
        locallyStoppingEntryIds += currentEntry.id
        pendingHistoryMembershipChanges[currentEntry.id] = HistoryMembershipChange.COMPLETED_ENTRY_PRESENT
        clearActivePollOverride()
        viewModelScope.launch {
            try {
                // Optimistic local stop + outbox enqueue; keep editing state for resume.
                if (edits == null) {
                    timeEntryRepository.stopEntry(currentEntry, currentEntry.userId)
                } else {
                    val stopTarget = retimeRunningEntry(currentEntry, edits)
                    timeEntryRepository.stopEntryWithEdits(
                        entry = stopTarget,
                        userId = stopTarget.userId,
                        editedEntry = stopTarget.copy(
                            description = edits.description,
                            projectId = edits.projectId,
                            taskId = edits.taskId,
                            billable = edits.billable,
                            tags = edits.tagIds.map(::Tag),
                        ),
                        tagIds = edits.tagIds,
                    )
                }
                syncTrigger.requestSync()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isTracking = false,
                    isPaused = true,
                    currentTimeEntry = null,
                )
                timerMutationInProgress = false
                stopTimer()
                lastCollectedActiveId = null
                Timber.d("Time entry paused successfully (optimistic)")

                // Update notification to paused state
                postedTrackingNotification = null
                TimeTrackingNotificationService.showPaused(context)

                // Update widget state to idle
                settingsDataStore.setWidgetTrackingState(isTracking = false)
                TimeTrackingWidget.requestUpdate(context)
            } catch (e: Exception) {
                locallyStoppingEntryIds.remove(currentEntry.id)
                pendingHistoryMembershipChanges.remove(currentEntry.id)
                handleTimerMutationFailure(e, R.string.error_pause_entry)
            }
        }
    }

    /** Put the running entry's details-sheet fields into the editing state that Stop and Resume use. */
    private fun applyRunningEntryEdits(edits: RunningEntryEdits) {
        _uiState.value = _uiState.value.copy(
            editingDescription = edits.description.orEmpty(),
            editingProjectId = edits.projectId,
            editingTaskId = edits.taskId,
            editingTags = edits.tagIds,
            editingBillable = edits.billable,
        )
    }

    /**
     * Save a start time changed in the details sheet before stopping, since the stop keeps the
     * row's start. Returns the entry to stop.
     */
    private suspend fun retimeRunningEntry(entry: TimeEntry, edits: RunningEntryEdits?): TimeEntry {
        if (edits == null) return entry
        val editedStart = parseTimeEntryInstant(edits.start)
        if (editedStart == null || editedStart.epochSecond == parseTimeEntryInstant(entry.start)?.epochSecond) return entry
        val retimed = entry.copy(
            start = edits.start,
            end = null,
            description = edits.description,
            projectId = edits.projectId,
            taskId = edits.taskId,
            billable = edits.billable,
            tags = edits.tagIds.map(::Tag),
        )
        timeEntryRepository.updateEntry(retimed, edits.tagIds)
        return retimed
    }

    /**
     * Resume tracking after pause - starts a new time entry with the same project/task/description
     */
    fun resumeTimeEntry(organizationId: String, memberId: String, userId: String) {
        if (!_uiState.value.isPaused || _uiState.value.currentTimeEntry != null) {
            Timber.d("Ignoring resume without a paused timer")
            return
        }
        if (!beginTimerMutation()) return
        clearActivePollOverride()
        val wasPaused = _uiState.value.isPaused
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPaused = false)
            try {
                val timeEntry = timeEntryRepository.startEntry(
                    organizationId = organizationId,
                    memberId = memberId,
                    userId = userId,
                    projectId = _uiState.value.editingProjectId,
                    taskId = _uiState.value.editingTaskId,
                    description = _uiState.value.editingDescription,
                    tagIds = _uiState.value.editingTags,
                    billable = _uiState.value.editingBillable,
                )
                syncTrigger.requestSync()

                // Update notification to tracking state
                showTrackingWidget(timeEntry, showTrackingNotification(timeEntry))

                _uiState.value = _uiState.value.copy(isLoading = false, isTracking = true)
                timerMutationInProgress = false
                Timber.d("Time entry resumed successfully with new entry (optimistic)")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isPaused = wasPaused)
                handleTimerMutationFailure(e, R.string.error_resume_entry)
            }
        }
    }

    /**
     * Create a manual (already-completed) time entry without affecting the running timer.
     */
    fun createManualTimeEntry(
        organizationId: String,
        memberId: String,
        userId: String,
        description: String?,
        projectId: String?,
        taskId: String?,
        tags: List<String>,
        billable: Boolean,
        start: String,
        end: String,
        type: TimeEntryType = TimeEntryType.WORK,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val created = timeEntryRepository.createCompletedEntry(
                    organizationId = organizationId,
                    memberId = memberId,
                    userId = userId,
                    description = description ?: "",
                    projectId = projectId,
                    taskId = taskId,
                    tagIds = tags,
                    billable = billable,
                    start = start,
                    end = end,
                    type = type,
                )
                syncTrigger.requestSync()
                // The Room collector will reconcile this row in the normal path. Updating the
                // cached list here also keeps the history responsive before its next emission.
                val updatedList = (_uiState.value.timeEntries + created)
                    .distinctBy { it.id }
                    .sortedByDescending { java.time.OffsetDateTime.parse(it.start) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    timeEntries = updatedList,
                )
                Timber.d("Manual time entry created successfully")
            } catch (e: Exception) {
                handleMutationFailure(e, R.string.error_create_entry)
            }
        }
    }

    /** Create a catalogue item from a calendar editor and immediately cache the server response. */
    fun createCalendarProject(organizationId: String, name: String, clientId: String?, onResult: (Result<Project>) -> Unit) {
        viewModelScope.launch { onResult(timeEntryRepository.createProject(organizationId, name, clientId)) }
    }

    fun createCalendarClient(organizationId: String, name: String, onResult: (Result<Client>) -> Unit) {
        viewModelScope.launch { onResult(timeEntryRepository.createClient(organizationId, name)) }
    }

    fun createCalendarTask(organizationId: String, name: String, projectId: String, onResult: (Result<Task>) -> Unit) {
        viewModelScope.launch { onResult(timeEntryRepository.createTask(organizationId, name, projectId)) }
    }

    fun createCalendarTag(organizationId: String, name: String, onResult: (Result<Tag>) -> Unit) {
        viewModelScope.launch { onResult(timeEntryRepository.createTag(organizationId, name)) }
    }

    /**
     * Update a past time entry.
     *
     * A [timeEntry] that was running when its editor opened may have ended since: Pause, Stop from
     * the notification or another surface. Room decides, not the editor's copy; saving such an entry
     * as running would clear the recorded end and restart the timer from its old start, so the
     * edits are saved onto the ended row with its real end instead.
     */
    @Suppress("LongMethod")
    fun updatePastTimeEntry(
        timeEntry: TimeEntry,
        description: String?,
        projectId: String?,
        taskId: String?,
        tags: List<String>,
        billable: Boolean,
        start: String,
        end: String?,
    ) {
        val savesRunningEntry = isRunningTimeEntry(timeEntry)
        if (savesRunningEntry) clearActivePollOverride()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val endedCopy = if (savesRunningEntry) endedCopyOf(timeEntry) else null
                val keepRunning = savesRunningEntry && endedCopy == null
                val endedAt = endedCopy?.end
                if (endedAt != null && !startsBefore(start, endedAt)) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = context.getString(R.string.running_entry_ended_start_after_end),
                    )
                    return@launch
                }
                val updatedEntry = (endedCopy ?: timeEntry).copy(
                    description = description,
                    projectId = projectId,
                    taskId = taskId,
                    billable = billable,
                    start = start,
                    end = when {
                        keepRunning -> null
                        endedAt != null -> endedAt
                        else -> requireNotNull(end) { "A completed time entry must have an end time" }
                    },
                    tags = tags.map { Tag(it) },
                )

                // Optimistic local update + outbox enqueue; the collector refreshes the list.
                timeEntryRepository.updateEntry(updatedEntry, tags)
                syncTrigger.requestSync()
                _uiState.value = _uiState.value.copy(isLoading = false)

                if (keepRunning) {
                    // The docked timer shows, and Stop commits, the editing fields: keep them in
                    // step with what was just saved, or Stop would write the old values back.
                    if (_uiState.value.currentTimeEntry?.let { isSameRunningEntry(it, timeEntry) } == true) {
                        _uiState.value = _uiState.value.copy(
                            editingDescription = description.orEmpty(),
                            editingProjectId = projectId,
                            editingTaskId = taskId,
                            editingTags = tags,
                            editingBillable = billable,
                        )
                    }
                    showTrackingWidget(updatedEntry, showTrackingNotification(updatedEntry))
                }

                Timber.d("Time entry updated successfully (optimistic)")
            } catch (e: Exception) {
                handleMutationFailure(e, R.string.error_update_entry)
            }
        }
    }

    /**
     * Room's ended copy of [entry] when an editor still holds it as running, or null while it runs.
     * A `local-` id retired by START reconciliation is matched by identity, like the repository does.
     */
    private suspend fun endedCopyOf(entry: TimeEntry): TimeEntry? {
        if (timeEntryRepository.isEntryRunning(entry.id)) return null
        fun List<TimeEntry>.copyOf(): TimeEntry? = firstOrNull { it.id == entry.id }
            ?: firstOrNull { isLocalTimeEntryId(entry.id) && isSameRunningEntry(it, entry) }
        val stored = try {
            timeEntryRepository.observeTimeEntries(entry.organizationId).first().copyOf()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not read the entry before saving it")
            null
        } ?: _uiState.value.timeEntries.copyOf()
        return stored?.takeIf(::isCompletedTimeEntry)
    }

    private fun startsBefore(start: String, end: String): Boolean {
        val startInstant = parseTimeEntryInstant(start) ?: return false
        val endInstant = parseTimeEntryInstant(end) ?: return false
        return startInstant < endInstant
    }

    /**
     * Roadmap #13: duplicate a completed entry, then open the copy for immediate editing. Running
     * and conflicted entries are guarded by the repository; a failure surfaces as an error message.
     * Surfaces with their own editor (Calendar) pass [openEditor] false, so the Time Tracker does
     * not open the copy the next time it is shown.
     */
    fun duplicateTimeEntry(entryId: String, openEditor: Boolean = true) {
        val memberId = historyMemberId ?: return
        viewModelScope.launch {
            timeEntryRepository.duplicateEntry(entryId, memberId)
                .onSuccess { created ->
                    syncTrigger.requestSync()
                    if (openEditor) requestEntryEditor(created.id)
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to duplicate time entry")
                    _uiState.value = _uiState.value.copy(error = error.message ?: context.getString(R.string.error_duplicate_entry))
                }
        }
    }

    /**
     * Roadmap #13: split a completed entry at [atIso]; on success open the new second half for
     * immediate editing. Validation (interior instant, running/conflict guards) lives in the repo.
     * [openEditor] as for [duplicateTimeEntry].
     */
    fun splitTimeEntry(entryId: String, atIso: String, openEditor: Boolean = true) {
        val memberId = historyMemberId ?: return
        viewModelScope.launch {
            timeEntryRepository.splitEntry(entryId, atIso, memberId)
                .onSuccess { newId ->
                    syncTrigger.requestSync()
                    if (openEditor) requestEntryEditor(newId)
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to split time entry")
                    _uiState.value = _uiState.value.copy(error = error.message ?: context.getString(R.string.error_split_entry))
                }
        }
    }

    /**
     * Ask the Time Tracker to open [entryId]. The request expires: an editor must not pop up on its
     * own long after the action, for example when the id changed on sync before the list showed it.
     */
    private fun requestEntryEditor(entryId: String) {
        _uiState.value = _uiState.value.copy(entryToEditId = entryId)
        entryToEditExpiryJob?.cancel()
        entryToEditExpiryJob = viewModelScope.launch {
            delay(ENTRY_TO_EDIT_TTL_MS)
            if (_uiState.value.entryToEditId == entryId) consumeEntryToEdit()
        }
    }

    /** One-shot consume of [TrackingUiState.entryToEditId] once the UI has opened the editor, or given up. */
    fun consumeEntryToEdit() {
        entryToEditExpiryJob?.cancel()
        entryToEditExpiryJob = null
        if (_uiState.value.entryToEditId != null) {
            _uiState.value = _uiState.value.copy(entryToEditId = null)
        }
    }

    /**
     * Start a new timer with [entry]'s fields from a surface without the start sheet, such as the
     * Calendar's Continue. Refused while a timer runs or is paused: that would overwrite the
     * paused timer's fields, which Resume starts with. Returns whether the start was requested.
     */
    fun continueEntry(entry: TimeEntry, organizationId: String, memberId: String, userId: String): Boolean {
        val state = _uiState.value
        if (state.currentTimeEntry != null || state.isTracking || state.isPaused || timerMutationInProgress) {
            Timber.d("Ignoring continue while a timer is running or paused")
            return false
        }
        _uiState.value = state.copy(
            editingDescription = entry.description.orEmpty(),
            editingProjectId = entry.projectId,
            editingTaskId = entry.taskId,
            editingTags = entry.tags.map { it.id },
            editingBillable = entry.billable,
        )
        cacheTrackingDraft(_uiState.value)
        startTimeEntry(organizationId, memberId, userId)
        return true
    }

    /**
     * Delete a time entry.
     *
     * SV-019: only the local soft-delete happens here, synchronously with no outbox op created -
     * so nothing exists yet for the sync worker to race against. The server-facing commit (DELETE
     * for a synced entry, or cancelling the START/CREATE for a never-synced one - SV-008) is
     * deferred behind the undo window in [pendingDeleteCommitJobs] and only runs if [undoDelete]
     * doesn't cancel it first.
     */
    fun deleteTimeEntry(timeEntryId: String) {
        viewModelScope.launch {
            val entry = _uiState.value.timeEntries.firstOrNull { it.id == timeEntryId }
                ?: _uiState.value.currentTimeEntry?.takeIf { it.id == timeEntryId }
            if (entry == null) {
                Timber.w("No time entry found to delete: $timeEntryId")
                return@launch
            }
            deleteTimeEntryInternal(entry)
        }
    }

    /** Delete an entry supplied by a calendar/history surface that may be outside the recent window. */
    fun deleteTimeEntry(entry: TimeEntry) {
        viewModelScope.launch { deleteTimeEntryInternal(entry) }
    }

    private suspend fun deleteTimeEntryInternal(entry: TimeEntry) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val runningTimer = _uiState.value.currentTimeEntry
            ?.takeIf { isRunningTimeEntry(entry) && isSameRunningEntry(it, entry) }
        if (runningTimer != null) {
            // The server keeps the timer running until the deferred DELETE syncs: keep the poll
            // and any earlier poll result from showing the deleted timer again meanwhile.
            locallyDeletedEntryIds += setOf(entry.id, runningTimer.id)
            clearActivePollOverride()
        }

        // Optimistic local-only soft-delete; the collector removes it from the list. No outbox op
        // exists yet, so there is nothing here for the sync worker to act on.
        pendingHistoryMembershipChanges[entry.id] = HistoryMembershipChange.ENTRY_ABSENT
        timeEntryRepository.softDeleteLocal(entry)
        if (runningTimer != null) endDeletedTimer()

        pendingDeleteCommitJobs.remove(entry.id)?.cancel()
        pendingDeleteCommitJobs[entry.id] = viewModelScope.launch {
            // Give the Snackbar undo action a real cancellation window before committing.
            delay(DELETE_UNDO_WINDOW_MS)
            timeEntryRepository.commitDelete(entry)
            pendingDeleteCommitJobs.remove(entry.id)
            syncTrigger.requestSync()
        }

        _uiState.value = _uiState.value.copy(isLoading = false)
        Timber.d("Time entry soft-deleted successfully (optimistic)")
    }

    /**
     * The running timer was deleted: end it everywhere now, as Stop does, rather than leaving the
     * notification and widget running until something else refreshes them.
     */
    private suspend fun endDeletedTimer() {
        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            isTracking = false,
            currentTimeEntry = null,
            editingDescription = if (shouldClearDescriptionAfterStop()) "" else currentState.editingDescription,
            editingProjectId = if (autoClearEntryFieldsAfterStopEnabled) null else currentState.editingProjectId,
            editingTaskId = if (autoClearEntryFieldsAfterStopEnabled) null else currentState.editingTaskId,
            editingTags = emptyList(),
            editingBillable = false,
        )
        cacheTrackingDraft(_uiState.value)
        stopTimer()
        lastCollectedActiveId = null
        updateNotificationState()
        settingsDataStore.setWidgetTrackingState(isTracking = false)
        TimeTrackingWidget.requestUpdate(context)
    }

    fun undoDelete(entry: TimeEntry) {
        viewModelScope.launch {
            // Cancel the deferred server-facing commit first: if the window hasn't closed yet,
            // this guarantees nothing was ever enqueued to the outbox for the repository undo path
            // to race against.
            pendingDeleteCommitJobs.remove(entry.id)?.cancel()
            pendingHistoryMembershipChanges[entry.id] = HistoryMembershipChange.COMPLETED_ENTRY_PRESENT
            val restoresTimer = entry.id in locallyDeletedEntryIds
            if (restoresTimer) {
                // The timer is back: stop hiding it from the poll, and drop a poll result that
                // still says no timer runs.
                locallyDeletedEntryIds.clear()
                clearActivePollOverride()
            }
            if (!timeEntryRepository.undoDelete(entry, historyMemberId)) {
                pendingHistoryMembershipChanges.remove(entry.id)
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.undo_delete_too_late))
            } else {
                syncTrigger.requestSync()
                // The restored row is active again; the collector brings back the docked timer,
                // and the notification and widget come back here.
                if (restoresTimer && isRunningTimeEntry(entry)) showTrackingWidget(entry, showTrackingNotification(entry))
            }
        }
    }

    fun retrySync() = syncTrigger.requestSync()

    fun retryAllSync(organizationId: String): Job = viewModelScope.launch {
        timeEntryRepository.prepareRetryAll(organizationId)
        syncTrigger.requestSync()
    }

    fun retrySync(entryId: String) {
        viewModelScope.launch {
            if (timeEntryRepository.prepareRetry(entryId)) syncTrigger.requestSync()
        }
    }

    /** Permanently drop a failed outbox change while keeping the cached entry available for editing. */
    fun discardFailedSync(entryId: String) {
        viewModelScope.launch { timeEntryRepository.discardFailedSync(entryId) }
    }

    private fun handleMutationFailure(error: Exception, @StringRes fallbackMessageRes: Int) {
        if (error is CancellationException) throw error
        val fallbackMessage = context.getString(fallbackMessageRes)
        Timber.e(error, fallbackMessage)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = error.message ?: fallbackMessage,
        )
    }

    private fun beginTimerMutation(): Boolean {
        if (timerMutationInProgress) {
            Timber.d("Ignoring repeated timer mutation while the first is in flight")
            return false
        }
        timerMutationInProgress = true
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        return true
    }

    private fun handleTimerMutationFailure(error: Exception, @StringRes fallbackMessageRes: Int) {
        timerMutationInProgress = false
        handleMutationFailure(error, fallbackMessageRes)
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Get grouped time entries by date
     */
    fun getGroupedTimeEntries(): Map<LocalDate, List<TimeEntry>> = _uiState.value.timeEntries
        .asSequence()
        .filter(::isCompletedTimeEntry)
        .flatMap { entry ->
            timeEntryLocalDaySlices(entry, _uiState.value.zone, Instant.ofEpochMilli(clock.nowMs()))
                .asSequence()
                .map { it.date to entry }
        }
        .groupBy({ it.first }, { it.second })
        .toSortedMap(compareByDescending { it })

    override fun onCleared() {
        loadDataJob?.cancel()
        activeEntryMonitorJob?.cancel()
        dataCollectorJob?.cancel()
        stopTimer()
    }

    private companion object {
        const val ACTIVE_ENTRY_REFRESH_INTERVAL_MS = 10_000L
        const val FOREGROUND_REFRESH_DEBOUNCE_MS = 5_000L
        const val FIRST_SCROLL_TOTAL = 150
        const val MAX_PAGE_SIZE = 500
        const val HISTORY_REFRESH_LIMIT = 250
        const val FIRST_FRAME_CACHE_DEBOUNCE_MS = 500L
        const val TRACKING_DRAFT_CACHE_DEBOUNCE_MS = 300L
        const val DELETE_UNDO_WINDOW_MS = 5_000L
        const val PAGINATED_MERGE_ATTEMPTS = 3

        /** How long a duplicate/split waits to be opened by the Time Tracker before it is dropped. */
        const val ENTRY_TO_EDIT_TTL_MS = 10_000L
    }
}

internal fun historyEntryStartDate(entry: TimeEntry, zone: ZoneId): LocalDate? =
    parseTimeEntryInstant(entry.start)?.atZone(zone)?.toLocalDate()

/**
 * The first-frame cache: the running timer, the newest [FIRST_FRAME_CACHE_ENTRY_LIMIT] entries, and
 * only the projects, clients, tasks and tags those show. The complete catalogue is read from Room
 * once the collectors start, so it does not have to be decoded before the first frame.
 */
@Suppress("LongParameterList")
internal fun firstFrameCacheOf(
    organizationId: String,
    entries: List<TimeEntry>,
    active: TimeEntry?,
    projects: List<Project>,
    clients: List<Client>,
    tasks: List<Task>,
    tags: List<Tag>,
    overlapCount: Int,
): SettingsDataStore.CachedTrackingState {
    val shown = entries.take(FIRST_FRAME_CACHE_ENTRY_LIMIT)
    val referenced = if (active != null) shown + active else shown
    val projectIds = referenced.mapNotNullTo(HashSet()) { it.projectId }
    val taskIds = referenced.mapNotNullTo(HashSet()) { it.taskId }
    val tagIds = referenced.flatMapTo(HashSet()) { entry -> entry.tags.map { it.id } }
    val shownProjects = projects.filter { it.id in projectIds }
    val clientIds = shownProjects.mapNotNullTo(HashSet()) { it.clientId }
    return SettingsDataStore.CachedTrackingState(
        organizationId = organizationId,
        timeEntries = shown,
        projects = shownProjects,
        clients = clients.filter { it.id in clientIds },
        tasks = tasks.filter { it.id in taskIds },
        tags = tags.filter { it.id in tagIds },
        activeEntry = active,
        overlapCount = overlapCount,
    )
}

internal const val FIRST_FRAME_CACHE_ENTRY_LIMIT = 30

/** Whether [other] has the same fields the running-timer editing state mirrors. */
private fun TimeEntry.hasSameEditableFields(other: TimeEntry?): Boolean = other != null &&
    description.orEmpty() == other.description.orEmpty() &&
    projectId == other.projectId &&
    taskId == other.taskId &&
    billable == other.billable &&
    tags.mapTo(HashSet()) { it.id } == other.tags.mapTo(HashSet()) { it.id }
