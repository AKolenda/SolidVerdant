/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.isBreakTimeEntry
import dev.tricked.solidverdant.domain.time.isRunningTimeEntry
import dev.tricked.solidverdant.domain.time.resolveTimeEntryInterval
import dev.tricked.solidverdant.domain.time.timeEntryOverlapsLocalDateRange
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.PriorityQueue

data class HistoryFilter(
    val query: String = "",
    val billable: Boolean? = null,
    val projectId: String? = null,
    val clientId: String? = null,
    val taskId: String? = null,
    val tagId: String? = null,
    val runningOnly: Boolean = false,
    val missingProjectOnly: Boolean = false,
    val missingDescriptionOnly: Boolean = false,
    val needsCategorization: Boolean = false,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val syncStatus: TimeEntryRepository.EntrySyncStatus? = null,
)

/** A review check shown on the entry's own history card, where the entry can be fixed in place. */
enum class EntryReviewIssue { NO_PROJECT, NO_DESCRIPTION, OVERLAP, LONG_DURATION }

/** Deterministic, local checks. Server policy remains authoritative. */
object EntryTrustRules {
    /**
     * Review checks for completed work entries, keyed by entry id; entries without an issue are
     * absent. Overlaps are found in one pass over the entries sorted by start: an entry overlaps
     * when it starts before the latest end seen so far, and so does the entry holding that end.
     * Breaks are never flagged. Advisory only: nothing is changed without the user.
     */
    fun reviewIssues(entries: List<TimeEntry>, longThreshold: Duration, now: Instant = Instant.now()): Map<String, Set<EntryReviewIssue>> {
        val issues = HashMap<String, MutableSet<EntryReviewIssue>>()
        fun flag(entry: TimeEntry, issue: EntryReviewIssue) {
            issues.getOrPut(entry.id) { mutableSetOf() } += issue
        }
        val completedWork = entries.filter { !isRunningTimeEntry(it) && !isBreakTimeEntry(it) }
        completedWork.forEach { entry ->
            if (entry.projectId == null) flag(entry, EntryReviewIssue.NO_PROJECT)
            if (entry.description.isNullOrBlank()) flag(entry, EntryReviewIssue.NO_DESCRIPTION)
        }
        completedWork.groupBy { it.organizationId }.values.forEach { organizationEntries ->
            val intervals = organizationEntries
                .mapNotNull { entry -> resolveTimeEntryInterval(entry, now)?.let { (start, end) -> Triple(entry, start, end) } }
                .sortedBy { it.second }
            var latest: Triple<TimeEntry, Instant, Instant>? = null
            intervals.forEach { interval ->
                val (entry, start, end) = interval
                val holder = latest
                if (holder != null && start < holder.third) {
                    flag(entry, EntryReviewIssue.OVERLAP)
                    flag(holder.first, EntryReviewIssue.OVERLAP)
                }
                if (holder == null || end > holder.third) latest = interval
                if (!longThreshold.isZero && !longThreshold.isNegative && Duration.between(start, end) >= longThreshold) {
                    flag(entry, EntryReviewIssue.LONG_DURATION)
                }
            }
        }
        return issues
    }

    fun overlapCount(entries: List<TimeEntry>, now: Instant = Instant.now()): Int =
        entries.groupBy { it.organizationId }.values.sumOf { organizationEntries ->
            val intervals = organizationEntries.mapNotNull { entry ->
                resolveTimeEntryInterval(entry, now)
            }.sortedBy { it.first }
            val activeEnds = PriorityQueue<Instant>()
            var count = 0
            intervals.forEach { (start, end) ->
                while (activeEnds.peek()?.let { !it.isAfter(start) } == true) activeEnds.poll()
                count += activeEnds.size
                activeEnds += end
            }
            count
        }

    fun overlaps(first: TimeEntry, second: TimeEntry, now: Instant = Instant.now()): Boolean {
        if (first.organizationId != second.organizationId ||
            first.id == second.id ||
            isBreakTimeEntry(first) ||
            isBreakTimeEntry(second)
        ) {
            return false
        }
        val (firstStart, firstEnd) = resolveTimeEntryInterval(first, now) ?: return false
        val (secondStart, secondEnd) = resolveTimeEntryInterval(second, now) ?: return false
        return firstStart < secondEnd && secondStart < firstEnd
    }

    fun isLongRunning(entry: TimeEntry, threshold: Duration, now: Instant = Instant.now()): Boolean {
        if (!isRunningTimeEntry(entry) || threshold.isNegative || threshold.isZero) return false
        val start = entry.start.toInstantOrNull() ?: return false
        return Duration.between(start, now) >= threshold
    }

    fun filter(
        entries: List<TimeEntry>,
        filter: HistoryFilter,
        projects: List<Project>,
        tasks: List<Task>,
        clients: List<Client> = emptyList(),
        syncOperations: List<TimeEntryRepository.SyncOperation> = emptyList(),
        zone: ZoneId,
        now: Instant = Instant.now(),
    ): List<TimeEntry> {
        val query = filter.query.trim()
        val projectNames = projects.associate { it.id to it.name }
        val taskNames = tasks.associate { it.id to it.name }
        val clientNames = clients.associate { it.id to it.name }
        val clientIdByProject = projects.associate { it.id to it.clientId }
        val syncStatusByEntryId = if (filter.syncStatus == null) emptyMap() else worstSyncStatusByEntryId(syncOperations)
        return entries.filter { entry ->
            val searchable = buildList {
                add(entry.description.orEmpty())
                add(projectNames[entry.projectId].orEmpty())
                add(taskNames[entry.taskId].orEmpty())
                add(clientNames[clientIdByProject[entry.projectId]].orEmpty())
                addAll(entry.tags.map { it.name })
            }
            // The entry's worst change, as its card shows: a failed UPDATE behind a newer queued
            // STOP still counts as failed.
            val status = syncStatusByEntryId[entry.id]
            (query.isBlank() || searchable.any { it.contains(query, ignoreCase = true) }) &&
                (filter.billable == null || entry.billable == filter.billable) &&
                (filter.projectId == null || entry.projectId == filter.projectId) &&
                (filter.clientId == null || clientIdByProject[entry.projectId] == filter.clientId) &&
                (filter.taskId == null || entry.taskId == filter.taskId) &&
                (filter.tagId == null || entry.tags.any { it.id == filter.tagId }) &&
                (!filter.runningOnly || isRunningTimeEntry(entry)) &&
                (!filter.missingProjectOnly || entry.projectId == null) &&
                (!filter.missingDescriptionOnly || entry.description.isNullOrBlank()) &&
                (!filter.needsCategorization || entry.projectId == null || entry.description.isNullOrBlank()) &&
                timeEntryOverlapsLocalDateRange(
                    entry = entry,
                    startDate = filter.startDate,
                    endDate = filter.endDate,
                    zone = zone,
                    now = now,
                ) &&
                (filter.syncStatus == null || status == filter.syncStatus)
        }
    }

    private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
}

/**
 * The entries' intervals parsed once and sorted by start, for the entry form's overlap warning:
 * each start/end change then compares instants only, and only with the entries that start within
 * the longest entry's span of the new interval. Same rules as [EntryTrustRules.overlaps].
 */
internal class EntryOverlapIndex private constructor(private val intervals: List<Interval>, private val longest: Duration) {
    private class Interval(val id: String, val organizationId: String, val start: Instant, val end: Instant)

    fun overlaps(excludeId: String, organizationId: String, start: Instant, end: Instant): Boolean {
        if (!end.isAfter(start) || intervals.isEmpty()) return false
        // Nothing that starts before this can still be running at [start].
        val earliest = start.minus(longest)
        var index = firstStartingAtOrAfter(earliest)
        while (index < intervals.size) {
            val interval = intervals[index]
            if (!interval.start.isBefore(end)) return false
            if (interval.id != excludeId && interval.organizationId == organizationId && start < interval.end) return true
            index++
        }
        return false
    }

    private fun firstStartingAtOrAfter(instant: Instant): Int {
        var low = 0
        var high = intervals.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (intervals[middle].start < instant) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        fun of(entries: List<TimeEntry>, now: Instant = Instant.now()): EntryOverlapIndex {
            val intervals = entries.mapNotNull { entry ->
                if (isBreakTimeEntry(entry)) return@mapNotNull null
                resolveTimeEntryInterval(entry, now)?.let { (start, end) -> Interval(entry.id, entry.organizationId, start, end) }
            }.sortedBy { it.start }
            val longest = intervals.maxOfOrNull { Duration.between(it.start, it.end) } ?: Duration.ZERO
            return EntryOverlapIndex(intervals, longest)
        }
    }
}
