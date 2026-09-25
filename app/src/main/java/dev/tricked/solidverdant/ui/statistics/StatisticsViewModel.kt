/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tricked.solidverdant.data.export.CsvExporter
import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.db.CatalogDao
import dev.tricked.solidverdant.data.local.db.MembershipEntity
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.TemporalPolicy
import dev.tricked.solidverdant.domain.time.TemporalPolicyProvider
import dev.tricked.solidverdant.domain.time.isWorkTimeEntry
import dev.tricked.solidverdant.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val REMOTE_PAGE_SIZE = 500
private const val UI_STATE_STOP_TIMEOUT_MS = 5_000L
private const val HTTP_FORBIDDEN = 403

data class StatisticsUiState(
    val isLoading: Boolean = true,
    val range: StatRange = StatRange.ThisWeek,
    val filters: StatFilters = StatFilters(),
    val catalog: StatCatalog = StatCatalog(),
    val summary: StatisticsSummary = EMPTY_SUMMARY,
    val estimateProgress: List<EstimateProgress> = emptyList(),
    val comparison: PeriodComparison? = null,
    val filteredEntries: List<TimeEntry> = emptyList(),
    val rangeStart: LocalDate? = null,
    val rangeEnd: LocalDate? = null,
    val granularity: TrendGranularity = TrendGranularity.DAY,
    val isEmpty: Boolean = false,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
) {
    companion object {
        val EMPTY_SUMMARY = StatisticsSummary(0, 0, 0, 0, 0, emptyList(), emptyList())
    }
}

/** One-shot state for the CSV export/share flow, consumed by the screen once handled. */
sealed interface ExportState {
    data object Idle : ExportState
    data object Running : ExportState
    data class Ready(val uri: Uri, val fileName: String) : ExportState
    data object Empty : ExportState
    data object Error : ExportState
}

/** What the user tapped to open a drill-down list. */
sealed interface DrillDownTarget {
    /** A donut slice / project legend row; [projectId] null is the "no project" bucket. */
    data class ProjectSlice(val projectId: String?, val projectName: String?, val colorHex: String) : DrillDownTarget

    /** A trend bar covering the inclusive [start]..[end] window it represents. */
    data class TrendSlice(val label: String, val start: LocalDate, val end: LocalDate) : DrillDownTarget

    /** The Dashboard's "Other" row: every project folded out of the top list. */
    data class OtherProjects(val projectIds: Set<String?>) : DrillDownTarget

    /**
     * An Estimates row: every entry on the project, whatever its date, as the server's spent total
     * counts them. The selected range and the filters do not apply.
     */
    data class ProjectHistory(val projectId: String, val projectName: String, val colorHex: String) : DrillDownTarget
}

/** Contents of the drill-down bottom sheet for the currently tapped [target]. */
data class DrillDownUiState(
    val target: DrillDownTarget,
    val isLoading: Boolean = true,
    val rows: List<DrillDownRow> = emptyList(),
    val totalSeconds: Long = 0L,
    /** More entries are still arriving from the server; the rows so far are listed. */
    val isRefreshing: Boolean = false,
    /** The server could not be reached, so only the entries stored on this phone are listed. */
    val loadFailed: Boolean = false,
    /** The account may not see other members' time, so only its own entries are listed. */
    val ownEntriesOnly: Boolean = false,
)

/**
 * ViewModel for the Statistics screen.
 *
 * Room supplies an immediate offline result while one bounded server request (the selected and
 * comparison periods plus a short carry-in, see [statisticsFetchWindow]) fills in history Room has
 * not cached. The server result is reused for [STATISTICS_CACHE_TTL_MS] per window, and Room rows
 * are overlaid on it by id so local creates, edits and deletions show immediately. Filters are
 * applied locally to the merged entries and drive every chart, KPI, the previous-period comparison
 * and the CSV export. A failed refresh never turns cached data into a false empty state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val csvExporter: CsvExporter,
    private val authDataStore: AuthDataStore,
    private val catalogDao: CatalogDao,
    private val temporalPolicyProvider: TemporalPolicyProvider,
    private val clock: Clock,
) : ViewModel() {

    // Latest account temporal policy (zone + first-day-of-week), kept for the non-reactive callers
    // (remote fetch bounds, drill-down/export clipping, zone()). The reactive uiState pipeline reads
    // the policy directly from the combined flow so it recomputes when the account changes; this
    // volatile mirror only serves calls that fire outside that pipeline. Seeded synchronously from
    // the provider's cached-auth read (a SharedPreferences lookup, no real I/O — see
    // SettingsDataStore.observeCachedAuth) so zone()/remote-fetch have the real policy on first use,
    // then kept current by the collector in init. No ZoneId.systemDefault() here: the provider owns
    // the device-zone fallback.
    @Volatile
    private var currentPolicy: TemporalPolicy = runBlocking { temporalPolicyProvider.current() }
    private val zone: ZoneId get() = currentPolicy.zone

    init {
        viewModelScope.launch {
            temporalPolicyProvider.policy.collect { currentPolicy = it }
        }
    }

    /**
     * Cancels [viewModelScope] for unit tests. Production uses [ViewModel.clear], but that is
     * internal in lifecycle-viewmodel 2.8.x and cannot be called from tests; a test that sets a test
     * [Dispatchers.Main] must still tear down every Main-bound coroutine this ViewModel launched (the
     * init policy collector and the [uiState] pipeline) before `Dispatchers.resetMain()`, otherwise a
     * straggling continuation races the reset with "Main is used concurrently with setting it".
     *
     * This only *requests* cancellation (no join): the coroutines run on the test's Main dispatcher,
     * so the caller must advance that dispatcher's scheduler to idle afterwards to actually run the
     * cancellation to completion — joining here would block on a scheduler this method cannot drive.
     * Not used in production.
     */
    @VisibleForTesting
    internal fun cancelScopeForTest() {
        viewModelScope.coroutineContext[Job]?.cancel()
    }

    private val rangeFlow = MutableStateFlow<StatRange>(StatRange.ThisWeek)
    private val filtersFlow = MutableStateFlow(StatFilters())
    private val refreshTrigger = MutableStateFlow(0)

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val drillDownTarget = MutableStateFlow<DrillDownTarget?>(null)

    /** Bumped by Retry in a project history list to fetch it again. */
    private val projectHistoryReload = MutableStateFlow(0)

    /** Latest inputs needed to build a CSV of the current filtered range, refreshed by [uiState]. */
    @Volatile
    private var exportInput: ExportInput? = null

    private data class ExportInput(
        val entries: List<TimeEntry>,
        val catalog: StatCatalog,
        val rangeStart: LocalDate,
        val rangeEnd: LocalDate,
        val zone: ZoneId,
        val organizationName: String,
    )

    private data class RemoteEntries(val entries: List<TimeEntry>? = null, val isLoading: Boolean = false, val failed: Boolean = false)

    /** Room's rows for the organization plus the ids of entries queued for deletion. */
    private data class LocalEntries(val entries: List<TimeEntry>, val pendingDeleteIds: Set<String>)

    /** Off-main-thread result bundle for one uiState emission. */
    private data class EstimateComputation(
        val summary: StatisticsSummary,
        val comparison: PeriodComparison?,
        val filtered: List<TimeEntry>,
        val estimates: List<EstimateProgress>,
    )

    private val remoteCache = StatisticsRemoteCache()

    /**
     * The server's entries for [key]'s bounded window. A window fetched within the cache TTL is
     * reused without a request; otherwise any older copy is shown while it refreshes, and is kept
     * (flagged failed) when the refresh fails, so a flaky network never blanks the Dashboard.
     */
    private fun loadRemoteEntries(key: StatisticsCacheKey): Flow<RemoteEntries> = flow {
        remoteCache.fresh(key, clock.nowMs())?.let { cached ->
            emit(RemoteEntries(entries = cached))
            return@flow
        }
        val stale = remoteCache.stale(key)
        emit(RemoteEntries(entries = stale, isLoading = true))
        val fetched = try {
            fetchWindow(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Statistics refresh failed: %s", e.javaClass.simpleName)
            null
        }
        if (fetched == null) {
            emit(RemoteEntries(entries = stale, failed = true))
        } else {
            remoteCache.put(key, fetched, clock.nowMs())
            emit(RemoteEntries(entries = fetched))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchWindow(key: StatisticsCacheKey): List<TimeEntry> {
        val entries = mutableListOf<TimeEntry>()
        val pageSize = REMOTE_PAGE_SIZE
        var offset = 0
        while (true) {
            val page = authRepository.getTimeEntries(
                key.organizationId,
                key.memberId,
                limit = pageSize,
                offset = offset,
                start = key.window.start,
                end = key.window.end,
            ).getOrThrow()
            entries += page.data
            offset += page.data.size
            if (!shouldFetchNextPage(pageSize, page.data.size, offset, page.meta?.total)) break
        }
        return entries
    }

    private fun observeLocalEntries(organizationId: String): Flow<LocalEntries> = combine(
        timeEntryRepository.observeTimeEntries(organizationId),
        timeEntryRepository.observeSyncOperations(organizationId)
            .map { operations -> operations.filter { it.type == OutboxOpType.DELETE }.mapTo(HashSet()) { it.entryId } }
            .distinctUntilChanged(),
    ) { entries, pendingDeleteIds -> LocalEntries(entries, pendingDeleteIds) }

    /**
     * Best-effort display name for the current organization, used only to label the CSV export.
     * Backed by a one-shot network call and cached per organization id for the life of the
     * ViewModel; a failed/offline lookup falls back to the organization id rather than blocking or
     * emptying the reactive [uiState] below, which never depends on this succeeding.
     */
    @Volatile
    private var cachedOrgName: Pair<String, String>? = null

    private suspend fun resolveOrgName(organizationId: String): String {
        cachedOrgName?.let { (id, name) -> if (id == organizationId) return name }
        val name = try {
            authRepository.getCurrentMembership()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
            ?.takeIf { it.organizationId == organizationId }
            ?.organization?.name
            ?: organizationId
        cachedOrgName = organizationId to name
        return name
    }

    /**
     * Reactive current membership, backed by Room-cached data so Statistics reflects cached state
     * immediately when opened offline and re-scopes as soon as the user switches organization —
     * mirroring [dev.tricked.solidverdant.ui.templates.ManageTemplatesViewModel]. Unlike the previous
     * one-shot `authRepository.getCurrentMembership()` network call, this never yields a false empty
     * state purely because the network is unavailable, and it re-emits on org switch without
     * requiring ViewModel recreation.
     */
    private val membershipFlow: Flow<MembershipEntity?> = combine(
        authDataStore.currentMembershipId,
        catalogDao.observeMemberships(),
    ) { selectedId, memberships ->
        memberships.firstOrNull { it.id == selectedId } ?: memberships.firstOrNull()
    }.distinctUntilChanged()

    val uiState: StateFlow<StatisticsUiState> =
        membershipFlow.flatMapLatest { membership ->
            if (membership == null) {
                flowOf(StatisticsUiState(isLoading = false, isEmpty = true))
            } else {
                val orgId = membership.organizationId
                val memberId = membership.id
                val catalogFlow = combine(
                    timeEntryRepository.observeProjects(orgId),
                    timeEntryRepository.observeClients(orgId),
                    timeEntryRepository.observeTasks(orgId),
                    timeEntryRepository.observeTags(orgId),
                ) { projects, clients, tasks, tags -> StatCatalog(projects, clients, tasks, tags) }

                // Only the range, the account temporal policy and an explicit refresh trigger a
                // server fetch; filters are applied locally to the already-fetched entries, so
                // toggling a filter never re-downloads the range. combine memoizes on exactly these
                // inputs, and a policy change (zone / week-start) re-resolves and re-fetches.
                combine(rangeFlow, temporalPolicyProvider.policy, refreshTrigger) { range, policy, _ ->
                    range to policy
                }
                    .flatMapLatest { (range, policy) ->
                        val zone = policy.zone
                        val resolved = range.resolve(LocalDate.now(zone), policy.firstDayOfWeek)
                        val previous = previousPeriod(resolved)
                        val key = StatisticsCacheKey(orgId, memberId, statisticsFetchWindow(resolved, previous, zone))
                        val knownLocalIds = remoteCache.locallyKnownIds(orgId)
                        combine(
                            observeLocalEntries(orgId),
                            catalogFlow,
                            loadRemoteEntries(key),
                            filtersFlow,
                        ) { local, catalog, remote, filters ->
                            val orgName = resolveOrgName(orgId)
                            val computed = withContext(Dispatchers.Default) {
                                // Room rows override the server snapshot by id and locally deleted
                                // rows drop out, so offline edits show before the next fetch.
                                val entries = overlayLocalEntries(remote.entries, local.entries, local.pendingDeleteIds, knownLocalIds)
                                val filtered = StatisticsAggregator.applyFilters(entries, catalog.projects, filters)
                                val current = StatisticsAggregator.compute(
                                    entries = filtered,
                                    projects = catalog.projects,
                                    rangeStart = resolved.start,
                                    rangeEnd = resolved.endInclusive,
                                    zone = zone,
                                    granularity = granularityFor(resolved),
                                    firstDayOfWeek = policy.firstDayOfWeek,
                                )
                                val prior = StatisticsAggregator.compute(
                                    entries = filtered,
                                    projects = catalog.projects,
                                    rangeStart = previous.start,
                                    rangeEnd = previous.endInclusive,
                                    zone = zone,
                                    granularity = granularityFor(previous),
                                    firstDayOfWeek = policy.firstDayOfWeek,
                                )
                                val estimates = StatisticsAggregator.projectEstimateProgress(
                                    projects = catalog.projects,
                                    filters = filters,
                                    relevantProjectIds = current.perProject.mapNotNullTo(HashSet()) { it.projectId },
                                    limit = DASHBOARD_TOP_ESTIMATES,
                                )
                                EstimateComputation(current, computeComparison(current, prior, previous), filtered, estimates)
                            }
                            val exportEntries = withContext(Dispatchers.Default) {
                                computed.filtered.filter {
                                    StatisticsAggregator.clippedSeconds(
                                        it,
                                        zone,
                                        resolved.start,
                                        resolved.endInclusive,
                                    ) != null
                                }
                            }
                            exportInput = ExportInput(
                                entries = exportEntries,
                                catalog = catalog,
                                rangeStart = resolved.start,
                                rangeEnd = resolved.endInclusive,
                                zone = zone,
                                organizationName = orgName,
                            )
                            StatisticsUiState(
                                isLoading = false,
                                isRefreshing = remote.isLoading,
                                refreshFailed = remote.failed,
                                range = range,
                                filters = filters,
                                catalog = catalog,
                                summary = computed.summary,
                                estimateProgress = computed.estimates,
                                comparison = computed.comparison,
                                filteredEntries = computed.filtered,
                                rangeStart = resolved.start,
                                rangeEnd = resolved.endInclusive,
                                granularity = granularityFor(resolved),
                                isEmpty = computed.summary.entryCount == 0,
                            )
                        }
                    }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(UI_STATE_STOP_TIMEOUT_MS),
            initialValue = StatisticsUiState(),
        )

    fun setRange(range: StatRange) {
        rangeFlow.value = range
    }

    fun setFilters(filters: StatFilters) {
        filtersFlow.value = filters
    }

    fun clearFilters() {
        filtersFlow.value = StatFilters()
    }

    /**
     * Re-fetches the selected range (pull to refresh or Retry), bypassing the cache TTL while the
     * previous result stays visible until the new one arrives.
     */
    fun refresh() {
        remoteCache.expireAll()
        refreshTrigger.value += 1
    }

    /** The current filter's [zone], exposed so drill-down clipping matches the aggregated view. */
    fun zone(): ZoneId = zone

    /** Opens the drill-down list for a tapped project donut slice / legend row. */
    fun openProjectDrillDown(projectId: String?, projectName: String?, colorHex: String) {
        openDrillDown(DrillDownTarget.ProjectSlice(projectId, projectName, colorHex))
    }

    /** Opens the drill-down list for the Dashboard's "Other" row covering [projectIds]. */
    fun openOtherProjectsDrillDown(projectIds: Set<String?>) {
        openDrillDown(DrillDownTarget.OtherProjects(projectIds))
    }

    /** Opens the drill-down list for a tapped trend bar covering [start]..[end] (inclusive). */
    fun openTrendDrillDown(label: String, start: LocalDate, end: LocalDate) {
        openDrillDown(DrillDownTarget.TrendSlice(label, start, end))
    }

    /** Opens every entry on an Estimates row's project, all dates and every visible member. */
    fun openEstimateDrillDown(estimate: EstimateProgress) {
        openDrillDown(DrillDownTarget.ProjectHistory(estimate.id, estimate.name, estimate.colorHex.orEmpty()))
    }

    fun closeDrillDown() {
        drillDownTarget.value = null
    }

    /** Retry in the drill-down: a project history is fetched again, a range list refreshes the range. */
    fun retryDrillDown() {
        if (drillDownTarget.value is DrillDownTarget.ProjectHistory) projectHistoryReload.value += 1 else refresh()
    }

    private fun openDrillDown(target: DrillDownTarget) {
        drillDownTarget.value = target
    }

    /**
     * The open drill-down, recomputed off the main thread while it is shown. A list opened before
     * the range finished loading fills in when the server's entries arrive, and local edits show at
     * once; closing the sheet or opening another slice cancels the previous computation.
     */
    val drillDown: StateFlow<DrillDownUiState?> = drillDownTarget
        .flatMapLatest { target ->
            when (target) {
                null -> flowOf(null)
                is DrillDownTarget.ProjectHistory -> projectHistoryDrillDown(target)
                else -> uiState.map { state -> rangeDrillDown(target, state) }.flowOn(Dispatchers.Default)
            }.onStart { if (target != null) emit(DrillDownUiState(target = target, isLoading = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The rows of a range list: the Dashboard's filtered entries, clipped to the tapped slice. */
    private fun rangeDrillDown(target: DrillDownTarget, state: StatisticsUiState): DrillDownUiState {
        val rangeStart = state.rangeStart ?: return DrillDownUiState(target = target, isLoading = true)
        val rangeEnd = state.rangeEnd ?: return DrillDownUiState(target = target, isLoading = true)
        fun rows(entries: List<TimeEntry>, selStart: LocalDate, selEnd: LocalDate, projectId: String?, matchProject: Boolean) =
            StatisticsAggregator.drillDown(
                entries = entries,
                projects = state.catalog.projects,
                tasks = state.catalog.tasks,
                zone = zone,
                selStart = selStart,
                selEnd = selEnd,
                matchProject = matchProject,
                projectId = projectId,
            )
        val rows = when (target) {
            is DrillDownTarget.ProjectSlice -> rows(state.filteredEntries, rangeStart, rangeEnd, target.projectId, matchProject = true)
            is DrillDownTarget.OtherProjects ->
                rows(state.filteredEntries.filter { it.projectId in target.projectIds }, rangeStart, rangeEnd, null, matchProject = false)
            is DrillDownTarget.TrendSlice -> rows(
                state.filteredEntries,
                selStart = if (target.start.isAfter(rangeStart)) target.start else rangeStart,
                selEnd = if (target.end.isBefore(rangeEnd)) target.end else rangeEnd,
                projectId = null,
                matchProject = false,
            )
            is DrillDownTarget.ProjectHistory -> emptyList()
        }
        return DrillDownUiState(
            target = target,
            isLoading = false,
            rows = rows,
            totalSeconds = rows.sumOf { it.seconds },
            isRefreshing = state.isRefreshing,
            loadFailed = state.refreshFailed,
        )
    }

    /** The server's answer for one project's history; see [fetchProjectHistory]. */
    private sealed interface ProjectHistoryLoad {
        data object Loading : ProjectHistoryLoad
        data object Failed : ProjectHistoryLoad
        data class Loaded(val entries: List<TimeEntry>, val everyone: Boolean, val memberNames: Map<String, String>) : ProjectHistoryLoad
    }

    /**
     * Every entry on [target]'s project. Room's rows replace the server copies by id and queued
     * deletions drop out, so unsynced changes show; while the server is loading or unreachable,
     * Room's own entries are listed.
     */
    private fun projectHistoryDrillDown(target: DrillDownTarget.ProjectHistory): Flow<DrillDownUiState> =
        membershipFlow.flatMapLatest { membership ->
            if (membership == null) return@flatMapLatest flowOf(DrillDownUiState(target = target, isLoading = false, loadFailed = true))
            val organizationId = membership.organizationId
            val server = projectHistoryReload.flatMapLatest {
                flow {
                    emit(ProjectHistoryLoad.Loading)
                    emit(fetchProjectHistory(organizationId, membership.id, target.projectId))
                }
            }
            combine(
                server,
                observeLocalEntries(organizationId),
                uiState.map { it.catalog }.distinctUntilChanged(),
            ) { load, local, catalog ->
                val localIds = local.entries.mapTo(HashSet()) { it.id }
                val serverOnly = (load as? ProjectHistoryLoad.Loaded)?.entries.orEmpty()
                    .filter { it.id !in localIds && it.id !in local.pendingDeleteIds }
                val entries = (local.entries.filter { it.projectId == target.projectId } + serverOnly).filter(::isWorkTimeEntry)
                val rows = StatisticsAggregator.historyRows(
                    entries = entries,
                    projects = catalog.projects,
                    tasks = catalog.tasks,
                    zone = zone,
                    memberNames = (load as? ProjectHistoryLoad.Loaded)?.memberNames.orEmpty(),
                )
                DrillDownUiState(
                    target = target,
                    isLoading = load == ProjectHistoryLoad.Loading && rows.isEmpty(),
                    rows = rows,
                    totalSeconds = rows.sumOf { it.seconds },
                    isRefreshing = load == ProjectHistoryLoad.Loading,
                    loadFailed = load == ProjectHistoryLoad.Failed,
                    ownEntriesOnly = (load as? ProjectHistoryLoad.Loaded)?.everyone == false,
                )
            }.flowOn(Dispatchers.Default)
        }

    /**
     * Every member's entries on [projectId] when the account may see them, which is what the
     * server's spent total counts; a refused request (403) falls back to the account's own entries.
     * Authors are named only when the list holds more than one person's time.
     */
    private suspend fun fetchProjectHistory(organizationId: String, memberId: String, projectId: String): ProjectHistoryLoad =
        withContext(Dispatchers.IO) {
            val everyone = authRepository.getAllProjectTimeEntries(organizationId, projectId, memberId = null)
            everyone.getOrNull()?.let { entries ->
                val names = if (entries.mapTo(HashSet()) { it.userId }.size > 1) {
                    authRepository.getMembers(organizationId).getOrNull().orEmpty().associate { it.userId to it.name }
                } else {
                    emptyMap()
                }
                return@withContext ProjectHistoryLoad.Loaded(entries, everyone = true, memberNames = names)
            }
            if ((everyone.exceptionOrNull() as? HttpException)?.code() != HTTP_FORBIDDEN) return@withContext ProjectHistoryLoad.Failed
            authRepository.getAllProjectTimeEntries(organizationId, projectId, memberId).fold(
                onSuccess = { ProjectHistoryLoad.Loaded(it, everyone = false, memberNames = emptyMap()) },
                onFailure = { ProjectHistoryLoad.Failed },
            )
        }

    /**
     * Builds a CSV of the currently filtered range off the main thread, writes it to the export
     * cache and surfaces a shareable URI. Repeated taps while running are ignored; an empty result
     * yields [ExportState.Empty] rather than an empty file.
     */
    fun export() {
        if (_exportState.value == ExportState.Running) return
        val input = exportInput
        if (input == null || input.entries.isEmpty()) {
            _exportState.value = ExportState.Empty
            return
        }
        _exportState.value = ExportState.Running
        viewModelScope.launch {
            try {
                val csv = withContext(Dispatchers.Default) {
                    csvExporter.formatCsv(
                        entries = input.entries,
                        projects = input.catalog.projects,
                        clients = input.catalog.clients,
                        tasks = input.catalog.tasks,
                        tags = input.catalog.tags,
                        zone = input.zone,
                        organizationName = input.organizationName,
                    )
                }
                val baseName = exportBaseName(input.rangeStart, input.rangeEnd)
                val uri = csvExporter.writeToCache(csv, baseName)
                _exportState.value = ExportState.Ready(uri, "$baseName.csv")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Never log entry contents; the message alone is safe.
                Timber.e(t, "CSV export failed")
                _exportState.value = ExportState.Error
            }
        }
    }

    fun onExportHandled() {
        _exportState.value = ExportState.Idle
    }

    private fun exportBaseName(start: LocalDate, end: LocalDate): String {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
        return "solidverdant-timeentries-${start.format(fmt)}-${end.format(fmt)}"
    }
}

/**
 * Whether another page must be fetched after receiving one of [lastPageSize] entries.
 *
 * A full page (== [pageSize]) means the server may have more rows: when the total is unknown
 * (meta null) that alone triggers the next request; when the total is known we continue only while
 * [offsetAfterPage] has not reached it. A short or empty page always stops the loop. Extracted as a
 * pure function so the previous under-fetch (defaulting an unknown total to the page size, which
 * made `offset < total` false right after the first full page) is directly testable.
 */
internal fun shouldFetchNextPage(pageSize: Int, lastPageSize: Int, offsetAfterPage: Int, total: Int?): Boolean =
    lastPageSize == pageSize && (total == null || offsetAfterPage < total)
