/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.domain.time.isWorkTimeEntry
import dev.tricked.solidverdant.domain.time.parseTimeEntryInstant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

enum class TrendGranularity { DAY, WEEK }

/** [projectName] is null when the entry has no project or its project is missing from the catalogue. */
data class ProjectTotal(val projectId: String?, val projectName: String?, val colorHex: String, val seconds: Long)

/**
 * One project's share of a [TrendBucket]; [projectId] null is the "no project" bucket. [isOther]
 * marks the segment [StatisticsAggregator.projectOverview] folds the smaller projects into.
 */
data class ProjectSegment(val projectId: String?, val colorHex: String, val seconds: Long, val isOther: Boolean = false)

/**
 * One bar of the trend chart. [segments] stack the bucket's non-zero time by project, ordered like
 * [StatisticsSummary.perProject] (range total descending) so a project sits at the same height in
 * every bar; their seconds sum to [seconds].
 */
data class TrendBucket(val label: String, val startDate: LocalDate, val seconds: Long, val segments: List<ProjectSegment> = emptyList())

data class StatisticsSummary(
    val totalSeconds: Long,
    val entryCount: Int,
    val avgSecondsPerDay: Long,
    val billableSeconds: Long,
    val nonBillableSeconds: Long,
    val perProject: List<ProjectTotal>,
    val trend: List<TrendBucket>,
)

/** Projects the Dashboard names in its legend and breakdown; smaller ones fold into "Other". */
const val DASHBOARD_TOP_PROJECTS = 6

/** Estimate rows the Dashboard shows, most urgent first. */
const val DASHBOARD_TOP_ESTIMATES = 6

/**
 * The Dashboard's per-project view: the [top] projects by time, the remaining [otherProjectIds]
 * folded into one [otherSeconds] total, and the [trend] with the same folding so the chart's
 * colours match the legend. Without folding, [top] is every project and [trend] is unchanged.
 */
data class ProjectOverview(
    val top: List<ProjectTotal>,
    val otherSeconds: Long,
    val otherProjectIds: Set<String?>,
    val trend: List<TrendBucket>,
) {
    val hasOther: Boolean get() = otherProjectIds.isNotEmpty()
}

/** Fraction at/above which a project is flagged as approaching its estimate (but not yet over). */
private const val NEAR_THRESHOLD = 0.9f

/**
 * Budget/progress for one project against its Solidtime estimate. [spentSeconds] and
 * [estimatedSeconds] are the SERVER'S authoritative project-level totals (seconds) — not recomputed
 * from local entries — so this is a reporting aid consistent across the whole organization. Derived,
 * display-only: nothing here is persisted.
 */
data class EstimateProgress(val id: String, val name: String, val colorHex: String?, val estimatedSeconds: Int, val spentSeconds: Int) {
    /** Seconds left against the estimate; negative when over budget. */
    val remainingSeconds: Int get() = estimatedSeconds - spentSeconds

    /** Spent as a fraction of the estimate; may exceed 1.0 when over budget. */
    val fraction: Float get() = if (estimatedSeconds <= 0) 0f else spentSeconds.toFloat() / estimatedSeconds

    val isOverBudget: Boolean get() = spentSeconds > estimatedSeconds

    /** Approaching (but not past) the estimate — an early warning, distinct from [isOverBudget]. */
    val isNearEstimate: Boolean get() = !isOverBudget && fraction >= NEAR_THRESHOLD
}

/** A single entry as surfaced in a drill-down list, clipped to the selected sub-range. */
data class DrillDownRow(
    val entryId: String,
    val description: String?,
    val projectId: String?,
    val projectName: String?,
    val colorHex: String,
    val taskName: String?,
    val startDate: LocalDate,
    val seconds: Long,
    val billable: Boolean,
)

object StatisticsAggregator {

    private const val NO_PROJECT_COLOR = "#9E9E9E"
    private val dayLabelFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

    /**
     * Applies the active [filters] to [entries] before aggregation. An empty dimension means no
     * restriction; the client dimension is resolved through each entry's project. Returns [entries]
     * Break entries are intentionally excluded from work statistics even when no filter is active.
     */
    fun applyFilters(entries: List<TimeEntry>, projects: List<Project>, filters: StatFilters): List<TimeEntry> {
        if (!filters.isActive) {
            return if (entries.any { !isWorkTimeEntry(it) }) entries.filter(::isWorkTimeEntry) else entries
        }
        val clientByProject: Map<String, String?> = projects.associate { it.id to it.clientId }
        return entries.filter { e ->
            if (!isWorkTimeEntry(e)) return@filter false
            val projectOk = filters.projectIds.isEmpty() ||
                (e.projectId != null && e.projectId in filters.projectIds)
            val taskOk = filters.taskIds.isEmpty() ||
                (e.taskId != null && e.taskId in filters.taskIds)
            val clientOk = filters.clientIds.isEmpty() ||
                (e.projectId != null && clientByProject[e.projectId]?.let { it in filters.clientIds } == true)
            val tagOk = filters.tagIds.isEmpty() || e.tags.any { it.id in filters.tagIds }
            val billableOk = when (filters.billable) {
                BillableFilter.All -> true
                BillableFilter.Billable -> e.billable
                BillableFilter.NonBillable -> !e.billable
            }
            projectOk && taskOk && clientOk && tagOk && billableOk
        }
    }

    /**
     * Keeps the [limit] projects with the most time and folds the rest, in every trend bucket too,
     * into one "Other" total. A single leftover project is shown by name instead: folding it would
     * hide its name without saving a row.
     */
    fun projectOverview(summary: StatisticsSummary, limit: Int = DASHBOARD_TOP_PROJECTS): ProjectOverview {
        if (summary.perProject.size <= limit + 1) {
            return ProjectOverview(summary.perProject, otherSeconds = 0, otherProjectIds = emptySet(), trend = summary.trend)
        }
        val top = summary.perProject.take(limit)
        val rest = summary.perProject.drop(limit)
        val topIds = top.mapTo(HashSet()) { it.projectId }
        val trend = summary.trend.map { bucket ->
            val (kept, folded) = bucket.segments.partition { it.projectId in topIds }
            if (folded.isEmpty()) {
                bucket
            } else {
                val other = ProjectSegment(projectId = null, colorHex = "", seconds = folded.sumOf { it.seconds }, isOther = true)
                bucket.copy(segments = kept + other)
            }
        }
        return ProjectOverview(top, rest.sumOf { it.seconds }, rest.mapTo(HashSet()) { it.projectId }, trend)
    }

    /**
     * Budget/progress for every in-scope project that carries a positive Solidtime estimate.
     *
     * Uses the server's authoritative project-level [Project.spentTime] vs [Project.estimatedTime]
     * (both seconds) rather than recomputing spent from local entries, so the numbers match the rest
     * of the Solidtime org (roadmap #83/#84). Archived projects and any without a non-null, positive
     * estimate are dropped. The active [filters] are honoured on the dimensions that apply to a whole
     * project — the explicit project set, the project's client, and its billable flag — so the
     * section's scope stays consistent with the by-project view above it; the entry-level task/tag
     * dimensions have no project-level meaning here and are ignored. Sorted over-budget first (most
     * urgent), then by consumed fraction descending, then by name for a stable order.
     *
     * The Dashboard passes [relevantProjectIds], the projects with time in the selected range after
     * every filter, so the section follows the range instead of listing every estimated project in
     * the organization, and caps it at the [limit] most urgent.
     */
    fun projectEstimateProgress(
        projects: List<Project>,
        filters: StatFilters,
        relevantProjectIds: Set<String>? = null,
        limit: Int = Int.MAX_VALUE,
    ): List<EstimateProgress> = projects.asSequence()
        .filter { !it.isArchived }
        .filter { (it.estimatedTime ?: 0) > 0 }
        .filter { relevantProjectIds == null || it.id in relevantProjectIds }
        .filter { p ->
            val projectOk = filters.projectIds.isEmpty() || p.id in filters.projectIds
            val clientOk = filters.clientIds.isEmpty() || (p.clientId != null && p.clientId in filters.clientIds)
            val billableOk = when (filters.billable) {
                BillableFilter.All -> true
                BillableFilter.Billable -> p.isBillable
                BillableFilter.NonBillable -> !p.isBillable
            }
            projectOk && clientOk && billableOk
        }
        .map { p ->
            EstimateProgress(
                id = p.id,
                name = p.name,
                colorHex = p.color,
                estimatedSeconds = p.estimatedTime ?: 0,
                spentSeconds = p.spentTime,
            )
        }
        .sortedWith(
            compareByDescending<EstimateProgress> { it.isOverBudget }
                .thenByDescending { it.fraction }
                .thenBy { it.name },
        )
        .take(limit)
        .toList()

    /** In-range contribution (seconds) of [e], or null when it does not overlap the window. */
    fun clippedSeconds(e: TimeEntry, zone: ZoneId, rangeStart: LocalDate, rangeEnd: LocalDate): Long? =
        clippedDailyBreakdown(e, zone, rangeStart, rangeEnd)?.sumOf { it.second }

    /**
     * Entries contributing to a tapped chart slice, each clipped to the selected [selStart]..[selEnd]
     * window (inclusive). When [matchProject] is true only entries whose project equals [projectId]
     * (including the null "no project" bucket) are kept, so tapping a donut segment lists just that
     * project; a trend bar passes [matchProject] false to list every entry within that time bucket.
     * Rows are ordered newest day first, then longest first.
     */
    fun drillDown(
        entries: List<TimeEntry>,
        projects: List<Project>,
        tasks: List<Task>,
        zone: ZoneId,
        selStart: LocalDate,
        selEnd: LocalDate,
        matchProject: Boolean,
        projectId: String?,
    ): List<DrillDownRow> {
        val projectById = projects.associateBy { it.id }
        val taskById = tasks.associateBy { it.id }
        return entries.mapNotNull { e ->
            if (matchProject && e.projectId != projectId) return@mapNotNull null
            val daily = clippedDailyBreakdown(e, zone, selStart, selEnd) ?: return@mapNotNull null
            val project = e.projectId?.let { projectById[it] }
            DrillDownRow(
                entryId = e.id,
                description = e.description,
                projectId = e.projectId,
                projectName = project?.name,
                colorHex = project?.color ?: NO_PROJECT_COLOR,
                taskName = e.taskId?.let { taskById[it]?.name },
                startDate = daily.first().first,
                seconds = daily.sumOf { it.second },
                billable = e.billable,
            )
        }.sortedWith(
            compareByDescending<DrillDownRow> { it.startDate }.thenByDescending { it.seconds },
        )
    }

    fun compute(
        entries: List<TimeEntry>,
        projects: List<Project>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        zone: ZoneId,
        granularity: TrendGranularity,
        firstDayOfWeek: DayOfWeek,
    ): StatisticsSummary {
        val projectById = projects.associateBy { it.id }

        // Per-entry clipped total plus its per-day breakdown within the range. An entry that begins
        // before rangeStart or ends after rangeEnd only contributes the overlapping seconds, and a
        // multi-day entry has those seconds split across each day it actually spans.
        data class Counted(val seconds: Long, val entry: TimeEntry, val daily: List<Pair<LocalDate, Long>>)

        val counted = entries.mapNotNull { e ->
            if (!isWorkTimeEntry(e)) return@mapNotNull null
            val daily = clippedDailyBreakdown(e, zone, rangeStart, rangeEnd) ?: return@mapNotNull null
            Counted(daily.sumOf { it.second }, e, daily)
        }

        val totalSeconds = counted.sumOf { it.seconds }
        val billable = counted.filter { it.entry.billable }.sumOf { it.seconds }
        val nonBillable = totalSeconds - billable

        val perProject = counted
            .groupBy { it.entry.projectId }
            .map { (pid, rows) ->
                val project = pid?.let { projectById[it] }
                ProjectTotal(
                    projectId = pid,
                    projectName = project?.name,
                    colorHex = project?.color ?: NO_PROJECT_COLOR,
                    seconds = rows.sumOf { it.seconds },
                )
            }
            .sortedWith(compareByDescending<ProjectTotal> { it.seconds }.thenBy { it.projectName ?: "" })

        val days = ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1
        val avgPerDay = if (days > 0) totalSeconds / days else totalSeconds

        val slices = counted.flatMap { c -> c.daily.map { (date, seconds) -> DaySlice(date, c.entry.projectId, seconds) } }
        val trend = buildTrend(slices, perProject, rangeStart, rangeEnd, granularity, firstDayOfWeek)

        return StatisticsSummary(
            totalSeconds = totalSeconds,
            entryCount = counted.size,
            avgSecondsPerDay = avgPerDay,
            billableSeconds = billable,
            nonBillableSeconds = nonBillable,
            perProject = perProject,
            trend = trend,
        )
    }

    /**
     * Clips an entry's [start, end) interval to the inclusive [rangeStart, rangeEnd] window (in
     * [zone]) and splits the overlapping seconds across every local day it spans.
     *
     * Returns null when the entry cannot be resolved to a finite interval (unparseable start, or an
     * active entry with neither duration nor end) or has no overlap with the range. An explicit end
     * is authoritative; duration is only a fallback for older cached completed entries.
     * A zero-length entry whose instant falls inside the range yields a single 0-second day so it
     * still counts and attributes to the correct project/day. The returned per-day seconds sum to
     * the entry's total in-range contribution.
     */
    @Suppress("ReturnCount")
    private fun clippedDailyBreakdown(
        e: TimeEntry,
        zone: ZoneId,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
    ): List<Pair<LocalDate, Long>>? {
        val startInstant = parseTimeEntryInstant(e.start) ?: return null
        val endInstant = when {
            e.end != null -> parseTimeEntryInstant(e.end) ?: return null
            e.duration != null && e.duration > 0 -> startInstant.plusSeconds(e.duration.toLong())
            else -> return null
        }

        val rangeStartInstant = rangeStart.atStartOfDay(zone).toInstant()
        val rangeEndExclusive = rangeEnd.plusDays(1).atStartOfDay(zone).toInstant()

        // Zero- or negative-length entry: attribute 0 seconds on its start day if that day is in range.
        if (!endInstant.isAfter(startInstant)) {
            if (startInstant < rangeStartInstant || !startInstant.isBefore(rangeEndExclusive)) return null
            return listOf(startInstant.atZone(zone).toLocalDate() to 0L)
        }

        val clipStart = maxOf(startInstant, rangeStartInstant)
        val clipEnd = minOf(endInstant, rangeEndExclusive)
        if (!clipEnd.isAfter(clipStart)) return null

        val out = mutableListOf<Pair<LocalDate, Long>>()
        var cursor = clipStart
        while (cursor < clipEnd) {
            val date = cursor.atZone(zone).toLocalDate()
            val nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant()
            val segmentEnd = minOf(nextDayStart, clipEnd)
            out += date to (segmentEnd.epochSecond - cursor.epochSecond)
            cursor = segmentEnd
        }
        return out
    }

    /** The in-range seconds one entry contributes to one local day. */
    private data class DaySlice(val date: LocalDate, val projectId: String?, val seconds: Long)

    /** Buckets [slices] and stacks each bucket by project in [perProject] order. */
    private fun buildTrend(
        slices: List<DaySlice>,
        perProject: List<ProjectTotal>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        granularity: TrendGranularity,
        firstDayOfWeek: DayOfWeek,
    ): List<TrendBucket> {
        fun bucket(label: String, start: LocalDate, rows: List<DaySlice>?): TrendBucket {
            val secondsByProject = rows.orEmpty().groupBy({ it.projectId }, { it.seconds }).mapValues { it.value.sum() }
            val segments = perProject.mapNotNull { p ->
                secondsByProject[p.projectId]?.takeIf { it > 0 }?.let { ProjectSegment(p.projectId, p.colorHex, it) }
            }
            return TrendBucket(label, start, segments.sumOf { it.seconds }, segments)
        }
        return when (granularity) {
            TrendGranularity.DAY -> {
                val byDay = slices.groupBy { it.date }
                generateSequence(rangeStart) { if (it < rangeEnd) it.plusDays(1) else null }
                    .map { d -> bucket(d.format(dayLabelFmt), d, byDay[d]) }
                    .toList()
            }
            TrendGranularity.WEEK -> {
                // Minimal-days pinned to ISO's 4 so a Monday firstDayOfWeek reproduces WeekFields.ISO
                // byte-for-byte (same week-start grouping AND same W## week numbers); only the
                // first-day-of-week shifts bucket boundaries for e.g. a Sunday-start account.
                val wf = WeekFields.of(firstDayOfWeek, WEEK_MIN_DAYS)
                val byWeekStart = slices.groupBy { it.date.with(wf.dayOfWeek(), 1) }
                val firstWeek = rangeStart.with(wf.dayOfWeek(), 1)
                generateSequence(firstWeek) { it.plusWeeks(1) }
                    .takeWhile { it <= rangeEnd }
                    .map { ws ->
                        val week = ws.get(wf.weekOfWeekBasedYear())
                        // Include the (week-based) year so labels don't collide across year
                        // boundaries, e.g. W52 of 2025 vs W52 of 2026 in a multi-year range.
                        val yy = ws.get(wf.weekBasedYear()) % PERCENT_YEAR_BASE
                        bucket("W$week '%02d".format(yy), ws, byWeekStart[ws])
                    }
                    .toList()
            }
        }
    }
}

private const val PERCENT_YEAR_BASE = 100

// Matches WeekFields.ISO.minimalDaysInFirstWeek so a Monday firstDayOfWeek reproduces ISO week
// numbering exactly; see the WEEK branch of buildTrend.
private const val WEEK_MIN_DAYS = 4
