/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tricked.solidverdant.data.calendar.CalendarEventSource
import dev.tricked.solidverdant.data.calendar.CalendarOverlaySettings
import dev.tricked.solidverdant.data.calendar.DeviceCalendar
import dev.tricked.solidverdant.data.calendar.DeviceCalendarEvent
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.TimeEntryReader
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.TemporalPolicy
import dev.tricked.solidverdant.domain.time.TemporalPolicyProvider
import dev.tricked.solidverdant.domain.time.isWorkTimeEntry
import dev.tricked.solidverdant.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Which calendar layout the user is currently viewing. */
enum class CalendarViewMode { MONTH, WEEK, DAY }

data class DayBucket(val date: LocalDate, val entries: List<TimeEntry>, val totalSeconds: Long)

/** Keep the most actionable state when an entry has more than one queued local change. */
internal fun worstSyncOperationsByEntryId(
    operations: List<TimeEntryRepository.SyncOperation>,
): Map<String, TimeEntryRepository.SyncOperation> = operations
    .groupBy { it.entryId }
    .mapValues { (_, entryOperations) -> entryOperations.maxByOrNull { it.status.ordinal } ?: entryOperations.first() }

data class CalendarUiState(
    val viewMode: CalendarViewMode = CalendarViewMode.DAY,
    /** Account temporal-policy zone; day/week boundaries and "today" are computed in it. */
    val zone: ZoneId = ZoneId.systemDefault(),
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    /** Anchor day for the week/day page; [visibleDays] is derived from it. */
    val weekAnchor: LocalDate = LocalDate.now(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /** Columns shown in WEEK mode (7, or 3 on narrow screens). DAY mode always shows 1. */
    val dayCount: Int = 7,
    val visibleDays: List<LocalDate> = emptyList(),
    val bucketsByDate: Map<LocalDate, DayBucket> = emptyMap(),
    val syncOperations: List<TimeEntryRepository.SyncOperation> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: Boolean = false,
    val isStale: Boolean = false,
    val calendarSettings: CalendarGridSettings = CalendarGridSettings(),
    // --- Device-calendar overlay ---
    val overlayEnabled: Boolean = false,
    val hasCalendarPermission: Boolean = false,
    /** True once the user has been shown the system permission dialog at least once this session. */
    val permissionRequested: Boolean = false,
    val availableCalendars: List<DeviceCalendar> = emptyList(),
    val selectedCalendarIds: Set<String> = emptySet(),
    val overlayEvents: List<DeviceCalendarEvent> = emptyList(),
    val overlayLoading: Boolean = false,
    val overlayError: Boolean = false,
    val calendarListLoading: Boolean = false,
    val calendarListError: Boolean = false,
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val reader: TimeEntryReader,
    private val eventSource: CalendarEventSource,
    private val overlaySettings: CalendarOverlaySettings,
    private val temporalPolicyProvider: TemporalPolicyProvider,
    private val clock: Clock,
) : ViewModel() {

    private var organizationId: String? = null
    private var memberId: String? = null
    private var visibleLoadJob: Job? = null

    // The organization whose Room entries and sync state the calendar shows. The Room streams are
    // only collected while the screen observes [uiState] (see there), not for the ViewModel's life.
    private val organizationInput = MutableStateFlow<String?>(null)

    // When each month last finished loading, per organization and zone. Navigating within the
    // same weeks (the day view pages often) reuses the Room copy instead of re-downloading it.
    private val monthLoadedAtMs = HashMap<MonthKey, Long>()

    private data class MonthKey(val organizationId: String, val month: YearMonth, val zone: ZoneId)
    private var visibleLoadGeneration = 0L
    private var calendarSettingsWritesInFlight = 0

    // Account temporal policy (zone + week start). Seeded synchronously from the provider's cached
    // read so the first frame already uses the account zone/week-start (see StatisticsViewModel);
    // kept current by the collector in init. The provider owns the device-zone fallback.
    @Volatile
    private var currentPolicy: TemporalPolicy = runBlocking { temporalPolicyProvider.current() }
    private val zone: ZoneId get() = currentPolicy.zone
    private val weekStart: DayOfWeek get() = currentPolicy.firstDayOfWeek
    private fun nowInstant(): Instant = Instant.ofEpochMilli(clock.nowMs())
    private fun today(): LocalDate = nowInstant().atZone(zone).toLocalDate()

    // Navigation, settings and overlay state. The Room-backed entries and sync operations are
    // joined onto it in [uiState].
    private val _uiState = MutableStateFlow(
        CalendarUiState(
            zone = currentPolicy.zone,
            weekStart = currentPolicy.firstDayOfWeek,
            visibleMonth = YearMonth.from(nowInstant().atZone(currentPolicy.zone)),
            selectedDate = today(),
            weekAnchor = today(),
        ),
    )

    private data class EntrySnapshot(
        val organizationId: String?,
        val buckets: Map<LocalDate, DayBucket> = emptyMap(),
        val syncOperations: List<TimeEntryRepository.SyncOperation> = emptyList(),
    )

    // Day buckets are rebuilt when Room changes or the account zone moves the day boundaries.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val entrySnapshots: Flow<EntrySnapshot> =
        combine(organizationInput, _uiState.map { it.zone }.distinctUntilChanged()) { org, bucketZone -> org to bucketZone }
            .distinctUntilChanged()
            .flatMapLatest { (org, bucketZone) ->
                if (org == null) {
                    flowOf(EntrySnapshot(organizationId = null))
                } else {
                    combine(
                        reader.observeTimeEntries(org).map { entries -> buildDayBuckets(entries, bucketZone, nowInstant()) },
                        reader.observeSyncOperations(org),
                    ) { buckets, operations -> EntrySnapshot(org, buckets, operations) }
                }
            }

    // Overlay query inputs kept as flows so event queries react without recollecting time entries.
    // Opens on a single day; Week and Month are in the overflow menu.
    private val viewModeInput = MutableStateFlow(CalendarViewMode.DAY)
    private val weekAnchorInput = MutableStateFlow(today())
    private val dayCountInput = MutableStateFlow(FULL_WEEK_DAYS)
    private val hasPermissionInput = MutableStateFlow(false)
    private val retryCounter = MutableStateFlow(0)

    private data class OverlayInputs(
        val enabled: Boolean,
        val ids: Set<String>,
        val hasPermission: Boolean,
        val range: Pair<Long, Long>?,
        val retry: Int,
    )

    private data class CalendarListInputs(val ready: Boolean, val retry: Int)

    private data class OverlayResult(val events: List<DeviceCalendarEvent>, val loading: Boolean, val error: Boolean)

    init {
        recomputeVisibleDays()

        // React to account temporal-policy changes (login/logout/profile refresh): update the zone
        // and week start, then re-derive the visible days off the new week start.
        viewModelScope.launch {
            temporalPolicyProvider.policy.collect { policy ->
                currentPolicy = policy
                _uiState.update { it.copy(zone = policy.zone, weekStart = policy.firstDayOfWeek) }
                recomputeVisibleDays()
                loadForVisibleDays()
            }
        }

        // Mirror persisted overlay preferences into the UI state.
        viewModelScope.launch {
            overlaySettings.calendarOverlayEnabled.collect { enabled ->
                _uiState.update { it.copy(overlayEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            overlaySettings.selectedCalendarIds.collect { ids ->
                _uiState.update { it.copy(selectedCalendarIds = ids) }
            }
        }
        viewModelScope.launch {
            combine(
                overlaySettings.calendarSnapMinutes,
                overlaySettings.calendarStartHour,
                overlaySettings.calendarEndHour,
                overlaySettings.calendarDensity,
            ) { snapMinutes, startHour, endHour, density ->
                CalendarGridSettings(
                    snapMinutes = snapMinutes,
                    startHour = startHour,
                    endHour = endHour,
                    density = runCatching { CalendarGridDensity.valueOf(density) }
                        .getOrDefault(CalendarGridDensity.COMFORTABLE),
                ).normalized()
            }.collect { settings ->
                // A DataStore collector can deliver its initial default after an optimistic
                // control tap. Do not let that stale emission roll the visible selection back
                // while a user change is still being persisted.
                if (calendarSettingsWritesInFlight == 0) {
                    _uiState.update { it.copy(calendarSettings = settings) }
                }
            }
        }
        // Load the picker's calendar list only while the overlay is on and permission is granted.
        viewModelScope.launch {
            combine(overlaySettings.calendarOverlayEnabled, hasPermissionInput, retryCounter) { enabled, perm, retry ->
                CalendarListInputs(ready = enabled && perm, retry = retry)
            }
                .distinctUntilChanged()
                .collectLatest { input ->
                    if (input.ready) {
                        _uiState.update { it.copy(calendarListLoading = true, calendarListError = false) }
                        val calendars = refreshAvailableCalendars()
                        currentCoroutineContext().ensureActive()
                        _uiState.update {
                            it.copy(
                                availableCalendars = calendars.orEmpty(),
                                calendarListLoading = false,
                                calendarListError = calendars == null,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                availableCalendars = emptyList(),
                                calendarListLoading = false,
                                calendarListError = false,
                            )
                        }
                    }
                }
        }
        // Query events reactively whenever the page, selection, or permission changes.
        viewModelScope.launch { observeOverlayEvents() }
    }

    /**
     * What the calendar shows. The Room entry and sync-state streams behind it are collected only
     * while the screen observes this (plus a short grace period for configuration changes), so
     * after one visit the calendar no longer rebuilds its day buckets on every database write while
     * another tab is open. Data from a previous organization is never shown under the new one.
     */
    val uiState: StateFlow<CalendarUiState> =
        combine(_uiState, organizationInput, entrySnapshots) { control, org, snapshot ->
            val data = snapshot.takeIf { it.organizationId == org }
            val buckets = data?.buckets.orEmpty()
            control.copy(
                bucketsByDate = buckets,
                syncOperations = data?.syncOperations.orEmpty(),
                // A failed refresh over cached days keeps them visible with a notice.
                isStale = control.loadError && buckets.isNotEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS), _uiState.value)

    fun setOrganization(organizationId: String, memberId: String = "") {
        if (this.organizationId == organizationId && this.memberId == memberId) {
            // The screen came back: refresh only the months that went stale while it was away.
            loadForVisibleDays()
            return
        }
        this.organizationId = organizationId
        this.memberId = memberId
        visibleLoadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, loadError = false, isStale = false) }
        organizationInput.value = organizationId
        loadForVisibleDays()
    }

    // --- View-mode / navigation ---------------------------------------------------------------

    fun setViewMode(mode: CalendarViewMode) {
        if (viewModeInput.value == mode) return
        viewModeInput.value = mode
        _uiState.update { it.copy(viewMode = mode) }
        recomputeVisibleDays()
        loadForVisibleDays()
    }

    /** Called by the UI as the available width changes so WEEK mode can drop to a 3-day layout. */
    fun setVisibleDayCount(count: Int) {
        val safe = count.coerceIn(MIN_VISIBLE_DAYS, FULL_WEEK_DAYS)
        if (dayCountInput.value == safe) return
        dayCountInput.value = safe
        _uiState.update { it.copy(dayCount = safe) }
        recomputeVisibleDays()
        loadForVisibleDays()
    }

    fun pageForward() = page(1)

    fun pageBackward() = page(-1)

    /** Re-fetch the current calendar page without changing the user's current selection. */
    fun retryLoad() = loadForVisibleDays(force = true)

    private fun page(direction: Int) {
        val state = _uiState.value
        val newAnchor = when (state.viewMode) {
            CalendarViewMode.MONTH -> return
            CalendarViewMode.WEEK -> pageAnchor(state.weekAnchor, weekStart, state.dayCount, direction)
            // The day view pages its week strip, keeping the weekday.
            CalendarViewMode.DAY -> state.weekAnchor.plusWeeks(direction.toLong())
        }
        weekAnchorInput.value = newAnchor
        val newDays = visibleDaysFor(state.viewMode, newAnchor, state.dayCount)
        val newSelected = if (state.selectedDate in newDays) {
            state.selectedDate
        } else {
            newDays.firstOrNull() ?: newAnchor
        }
        _uiState.update {
            it.copy(
                weekAnchor = newAnchor,
                visibleDays = newDays,
                selectedDate = newSelected,
                visibleMonth = YearMonth.from(newAnchor),
            )
        }
        loadForVisibleDays()
    }

    fun jumpToToday() {
        val today = today()
        weekAnchorInput.value = today
        _uiState.update {
            it.copy(
                weekAnchor = today,
                selectedDate = today,
                visibleMonth = YearMonth.from(today),
                visibleDays = visibleDaysFor(it.viewMode, today, it.dayCount),
            )
        }
        loadForVisibleDays()
    }

    fun selectDate(date: LocalDate) {
        weekAnchorInput.value = date
        _uiState.update {
            it.copy(
                selectedDate = date,
                weekAnchor = date,
                visibleMonth = YearMonth.from(date),
                visibleDays = visibleDaysFor(it.viewMode, date, it.dayCount),
            )
        }
        loadForVisibleDays()
    }

    fun nextMonth() = moveMonth(1)

    fun previousMonth() = moveMonth(-1)

    private fun moveMonth(delta: Long) {
        val loadedDays = uiState.value.bucketsByDate.keys
        _uiState.update { state ->
            val month = state.visibleMonth.plusMonths(delta)
            val preferredDay = state.selectedDate.dayOfMonth.coerceAtMost(month.lengthOfMonth())
            val preferredDate = month.atDay(preferredDay)
            val dateWithEntries = loadedDays
                .filter { YearMonth.from(it) == month }
                .minByOrNull { kotlin.math.abs(it.dayOfMonth - preferredDay) }
            val selected = dateWithEntries ?: preferredDate
            state.copy(visibleMonth = month, selectedDate = selected, weekAnchor = selected)
        }
        weekAnchorInput.value = _uiState.value.weekAnchor
        loadForVisibleDays()
    }

    // --- Overlay controls ---------------------------------------------------------------------

    /** Called by the UI after checking/requesting the READ_CALENDAR runtime permission. */
    fun onCalendarPermissionChanged(granted: Boolean) {
        if (hasPermissionInput.value == granted && _uiState.value.hasCalendarPermission == granted) return
        hasPermissionInput.value = granted
        _uiState.update { it.copy(hasCalendarPermission = granted) }
    }

    fun onPermissionRequested() {
        _uiState.update { it.copy(permissionRequested = true) }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { overlaySettings.setCalendarOverlayEnabled(enabled) }
    }

    fun toggleCalendarSelected(id: String) {
        val current = _uiState.value.selectedCalendarIds
        val next = if (id in current) current - id else current + id
        viewModelScope.launch { overlaySettings.setSelectedCalendarIds(next) }
    }

    fun retryOverlay() {
        retryCounter.update { it + 1 }
    }

    fun updateCalendarSettings(settings: CalendarGridSettings) {
        updateCalendarSetting { settings }
    }

    /** Apply one control change to the latest UI snapshot so rapid selections cannot overwrite one another. */
    fun updateCalendarSetting(transform: (CalendarGridSettings) -> CalendarGridSettings) {
        val normalized = transform(_uiState.value.calendarSettings).normalized()
        _uiState.update { it.copy(calendarSettings = normalized) }
        calendarSettingsWritesInFlight++
        viewModelScope.launch {
            try {
                overlaySettings.setCalendarGridSettings(
                    snapMinutes = normalized.snapMinutes,
                    startHour = normalized.startHour,
                    endHour = normalized.endHour,
                    density = normalized.density.name,
                )
            } finally {
                calendarSettingsWritesInFlight--
            }
        }
    }

    private suspend fun refreshAvailableCalendars(): List<DeviceCalendar>? = try {
        eventSource.queryCalendars()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeOverlayEvents() {
        val rangeFlow: Flow<Pair<Long, Long>?> =
            combine(viewModeInput, weekAnchorInput, dayCountInput, temporalPolicyProvider.policy) { mode, anchor, dc, policy ->
                rangeFor(mode, anchor, dc, policy.zone, policy.firstDayOfWeek)
            }
        combine(
            overlaySettings.calendarOverlayEnabled,
            overlaySettings.selectedCalendarIds,
            hasPermissionInput,
            rangeFlow,
            retryCounter,
        ) { enabled, ids, perm, range, retry -> OverlayInputs(enabled, ids, perm, range, retry) }
            .distinctUntilChanged()
            .flatMapLatest { input ->
                if (!input.enabled || !input.hasPermission || input.ids.isEmpty() || input.range == null) {
                    flowOf(OverlayResult(emptyList(), loading = false, error = false))
                } else {
                    flow {
                        emit(OverlayResult(emptyList(), loading = true, error = false))
                        val result = try {
                            eventSource.queryEvents(input.ids, input.range.first, input.range.second)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        emit(
                            result?.let { OverlayResult(it, loading = false, error = false) }
                                ?: OverlayResult(emptyList(), loading = false, error = true),
                        )
                    }
                }
            }
            .collect { result ->
                _uiState.update {
                    it.copy(
                        overlayEvents = result.events,
                        overlayLoading = result.loading,
                        overlayError = result.error,
                    )
                }
            }
    }

    // --- Derivation helpers -------------------------------------------------------------------

    private fun recomputeVisibleDays() {
        val s = _uiState.value
        _uiState.update { it.copy(visibleDays = visibleDaysFor(s.viewMode, s.weekAnchor, s.dayCount)) }
    }

    private fun visibleDaysFor(
        mode: CalendarViewMode,
        anchor: LocalDate,
        dayCount: Int,
        startOfWeek: DayOfWeek = weekStart,
    ): List<LocalDate> = when (mode) {
        CalendarViewMode.MONTH -> emptyList()
        CalendarViewMode.WEEK -> visibleCalendarDays(anchor, startOfWeek, dayCount)
        CalendarViewMode.DAY -> listOf(anchor)
    }

    /** Epoch-milli half-open range covering the visible page, or null for MONTH mode. */
    private fun rangeFor(
        mode: CalendarViewMode,
        anchor: LocalDate,
        dayCount: Int,
        rangeZone: ZoneId = zone,
        startOfWeek: DayOfWeek = weekStart,
    ): Pair<Long, Long>? {
        val days = visibleDaysFor(mode, anchor, dayCount, startOfWeek)
        if (days.isEmpty()) return null
        val start = days.first().atStartOfDay(rangeZone).toInstant().toEpochMilli()
        val end = days.last().plusDays(1).atStartOfDay(rangeZone).toInstant().toEpochMilli()
        return start to end
    }

    private fun loadForVisibleDays(force: Boolean = false) {
        val org = organizationId ?: return
        val member = memberId ?: return
        val state = _uiState.value
        val now = clock.nowMs()
        val isFresh = { month: YearMonth ->
            !force && monthLoadedAtMs[MonthKey(org, month, state.zone)]?.let { now - it < MONTH_FRESH_MS } == true
        }
        val markLoaded = { month: YearMonth -> monthLoadedAtMs[MonthKey(org, month, state.zone)] = clock.nowMs() }
        val visibleMonths = when (state.viewMode) {
            CalendarViewMode.MONTH -> listOf(state.visibleMonth)
            else -> {
                val days = state.visibleDays
                if (days.isEmpty()) {
                    listOf(state.visibleMonth)
                } // A week can straddle two months; load both so no column is missing entries.
                else {
                    listOf(days.first(), days.last()).map { YearMonth.from(it) }.distinct()
                }
            }
        }
        val months = monthsWithAdjacentPeriods(visibleMonths)
        val staleVisible = visibleMonths.filterNot(isFresh)
        val stalePrefetch = months.filterNot { it in visibleMonths || isFresh(it) }
        if (staleVisible.isEmpty() && stalePrefetch.isEmpty() && visibleLoadJob?.isActive != true) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        _uiState.update { it.copy(isLoading = staleVisible.isNotEmpty(), loadError = false, isStale = false) }
        val requestGeneration = ++visibleLoadGeneration
        visibleLoadJob?.cancel()
        visibleLoadJob = viewModelScope.launch {
            try {
                staleVisible.forEach {
                    reader.loadMonth(org, member, it, state.zone)
                    markLoaded(it)
                    currentCoroutineContext().ensureActive()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestGeneration == visibleLoadGeneration && this@CalendarViewModel.organizationId == org) {
                    // uiState marks the page stale when cached days are still on screen.
                    _uiState.update { it.copy(isLoading = false, loadError = true) }
                }
                return@launch
            }
            if (requestGeneration == visibleLoadGeneration && this@CalendarViewModel.organizationId == org) {
                _uiState.update { it.copy(isLoading = false, loadError = false, isStale = false) }
            }
            // The neighbours wait until the visible page has loaded and had a moment to render, so
            // their Room writes do not rebuild the page the user is looking at, and quick paging
            // (which cancels this job) does not start downloads for months it passes through.
            if (stalePrefetch.isNotEmpty()) delay(PREFETCH_SETTLE_MS)
            stalePrefetch.forEach { month ->
                try {
                    reader.loadMonth(org, member, month, state.zone)
                    markLoaded(month)
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The visible page is already usable; a prefetch miss should not replace it
                    // with an error state. The next navigation or explicit refresh retries it.
                }
            }
        }
    }

    fun entriesForSelectedDay(): List<TimeEntry> = uiState.value.let { it.bucketsByDate[it.selectedDate]?.entries } ?: emptyList()

    /** Cancel Main-bound collectors so JVM tests can reset their test dispatcher safely. */
    @VisibleForTesting
    internal fun cancelScopeForTest(): Job? {
        val scopeJob = viewModelScope.coroutineContext[Job]
        scopeJob?.cancel()
        return scopeJob
    }
}

private const val FULL_WEEK_DAYS = 7

/**
 * A month loaded this recently is served from Room when navigating; Retry always reloads it, and
 * local edits reach Room directly, so the calendar does not need to re-download a month it has.
 */
private const val MONTH_FRESH_MS = 5 * 60_000L

/** How long the visible page settles before the neighbouring months are prefetched. */
private const val PREFETCH_SETTLE_MS = 500L

/** Keep the Room streams through a configuration change, then stop them off screen. */
private const val STATE_STOP_TIMEOUT_MS = 5_000L
private const val MIN_VISIBLE_DAYS = 1

/**
 * Groups [entries] into local-day buckets for [zone]; a multi-day entry appears in every day it
 * covers with only that day's seconds counted. Runs off the main thread: a large month can hold
 * thousands of entries and grouping them on the UI thread would jank or ANR.
 */
internal suspend fun buildDayBuckets(entries: List<TimeEntry>, zone: ZoneId, now: Instant): Map<LocalDate, DayBucket> =
    withContext(Dispatchers.Default) {
        entries
            .flatMap { entry -> entryDaySlices(entry, zone, now).map { slice -> slice to entry } }
            .groupBy({ it.first.date }, { it })
            .mapValues { (date, daySlices) ->
                DayBucket(
                    date = date,
                    entries = daySlices.map { it.second }.sortedByDescending { it.start },
                    totalSeconds = daySlices
                        .filter { (_, entry) -> isWorkTimeEntry(entry) }
                        .sumOf { it.first.seconds },
                )
            }
    }

internal fun monthsWithAdjacentPeriods(visibleMonths: List<YearMonth>): List<YearMonth> {
    if (visibleMonths.isEmpty()) return emptyList()
    val first = visibleMonths.minOrNull() ?: return emptyList()
    val last = visibleMonths.maxOrNull() ?: return emptyList()
    return buildList {
        var month = first.minusMonths(1)
        val end = last.plusMonths(1)
        while (!month.isAfter(end)) {
            add(month)
            month = month.plusMonths(1)
        }
    }
}
