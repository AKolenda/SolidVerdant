/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.runtime.Immutable
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.clipTimeEntryToLocalDay
import dev.tricked.solidverdant.domain.time.isCompletedTimeEntry
import dev.tricked.solidverdant.domain.time.isWorkTimeEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How a week header names its week; ranges are formatted in composition with the app locale. */
internal enum class HistoryWeekLabel { THIS_WEEK, LAST_WEEK, DATE_RANGE }

@Immutable
internal data class HistoryDay(val date: LocalDate, val totalSeconds: Long, val groups: List<HistoryListItem.Group>)

/** Flat rows of the history list: week header, day header, then one card per entry group. */
@Immutable
internal sealed interface HistoryListItem {
    val key: String

    @Immutable
    data class Week(val start: LocalDate, val end: LocalDate, val totalSeconds: Long, val label: HistoryWeekLabel) : HistoryListItem {
        override val key: String get() = "week_$start"
    }

    @Immutable
    data class Header(val day: HistoryDay) : HistoryListItem {
        override val key: String get() = "header_${day.date}"
    }

    /**
     * Entries of one local day that share every visible field. [entrySeconds] is aligned with
     * [entries] and holds each entry's work time clipped to [date] (breaks count as zero).
     */
    @Immutable
    data class Group(val date: LocalDate, val entries: List<TimeEntry>, val entrySeconds: List<Long>) : HistoryListItem {
        override val key: String get() = "group_${date}_${entries.first().id}"
        val lead: TimeEntry get() = entries.first()
        val totalSeconds: Long get() = entrySeconds.sum()
    }
}

/** Fields a stacked card shows once for all of its entries. */
private data class HistoryGroupKey(
    val description: String,
    val projectId: String?,
    val taskId: String?,
    val tagIds: Set<String>,
    val billable: Boolean,
    val type: TimeEntryType,
)

/**
 * Stack a day's entries: entries with the same description, project, task,
 * tag set and billable flag collapse into one card. Groups keep the order in which their first entry
 * appears, and entries keep their order inside a group. A missing and an empty description match
 * because both render as "No description".
 */
internal fun historyEntryGroups(entries: List<TimeEntry>): List<List<TimeEntry>> = entries
    .groupBy { entry ->
        HistoryGroupKey(
            description = entry.description.orEmpty(),
            projectId = entry.projectId,
            taskId = entry.taskId,
            tagIds = entry.tags.mapTo(HashSet()) { it.id },
            billable = entry.billable,
            type = entry.type,
        )
    }
    .values
    .toList()

/**
 * The single builder for the history list. [days] maps each local day to the completed entries that
 * overlap it (see [groupCompletedEntriesByLocalDay]); days are emitted newest first, with a week
 * header whenever a new week starting on [firstDayOfWeek] begins.
 */
internal fun buildHistoryListItems(
    days: Map<LocalDate, List<TimeEntry>>,
    firstDayOfWeek: DayOfWeek,
    today: LocalDate,
    zone: ZoneId,
    now: Instant,
): List<HistoryListItem> {
    val historyDays = days.entries
        .sortedByDescending { it.key }
        .mapNotNull { (date, entries) ->
            val completed = entries.filter(::isCompletedTimeEntry)
            if (completed.isEmpty()) return@mapNotNull null
            val groups = historyEntryGroups(completed).map { groupEntries ->
                HistoryListItem.Group(
                    date = date,
                    entries = groupEntries,
                    entrySeconds = groupEntries.map { entryWorkSecondsOnDay(it, date, zone, now) },
                )
            }
            HistoryDay(date = date, totalSeconds = groups.sumOf { it.totalSeconds }, groups = groups)
        }
    val thisWeekStart = today.startOfWeek(firstDayOfWeek)
    return buildList {
        historyDays.groupBy { it.date.startOfWeek(firstDayOfWeek) }.forEach { (weekStart, weekDays) ->
            add(
                HistoryListItem.Week(
                    start = weekStart,
                    end = weekStart.plusDays(DAYS_PER_WEEK - 1),
                    totalSeconds = weekDays.sumOf { it.totalSeconds },
                    label = when (weekStart) {
                        thisWeekStart -> HistoryWeekLabel.THIS_WEEK
                        thisWeekStart.minusWeeks(1) -> HistoryWeekLabel.LAST_WEEK
                        else -> HistoryWeekLabel.DATE_RANGE
                    },
                ),
            )
            weekDays.forEach { day ->
                add(HistoryListItem.Header(day))
                addAll(day.groups)
            }
        }
    }
}

/** Index of the day header nearest to [requestedDate] in [items], or -1 when there is no day. */
internal fun historyHeaderIndex(requestedDate: LocalDate, items: List<HistoryListItem>): Int {
    var bestIndex = -1
    var bestDistance = Long.MAX_VALUE
    items.forEachIndexed { index, item ->
        if (item is HistoryListItem.Header) {
            val distance = kotlin.math.abs(item.day.date.toEpochDay() - requestedDate.toEpochDay())
            if (distance < bestDistance) {
                bestIndex = index
                bestDistance = distance
            }
        }
    }
    return bestIndex
}

/** Fixed-width duration, "00:56:51"; hours grow past two digits instead of wrapping. */
internal fun formatClockDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d",
        safeSeconds / SECONDS_PER_HOUR,
        (safeSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE,
        safeSeconds % SECONDS_PER_MINUTE,
    )
}

/**
 * One status per entry: a dead-lettered UPDATE queued behind a pending STOP must not read as merely
 * queued, so the entry shows its worst operation.
 */
private val syncStatusSeverity = listOf(
    TimeEntryRepository.EntrySyncStatus.FAILED,
    TimeEntryRepository.EntrySyncStatus.CONFLICT,
    TimeEntryRepository.EntrySyncStatus.RETRYING,
    TimeEntryRepository.EntrySyncStatus.PENDING,
    TimeEntryRepository.EntrySyncStatus.SYNCED,
)

internal fun worstSyncStatus(statuses: Iterable<TimeEntryRepository.EntrySyncStatus>): TimeEntryRepository.EntrySyncStatus? =
    statuses.minByOrNull { syncStatusSeverity.indexOf(it) }

internal fun worstSyncStatusByEntryId(
    operations: List<TimeEntryRepository.SyncOperation>,
): Map<String, TimeEntryRepository.EntrySyncStatus> = operations
    .groupBy { it.entryId }
    .mapValues { (_, entryOperations) -> entryOperations.minBy { syncStatusSeverity.indexOf(it.status) }.status }

/** Statuses a user can act on from the card; PENDING is queued and will upload on its own. */
internal fun canRetrySync(status: TimeEntryRepository.EntrySyncStatus): Boolean =
    status == TimeEntryRepository.EntrySyncStatus.FAILED || status == TimeEntryRepository.EntrySyncStatus.RETRYING

private fun entryWorkSecondsOnDay(entry: TimeEntry, date: LocalDate, zone: ZoneId, now: Instant): Long =
    if (isWorkTimeEntry(entry)) clipTimeEntryToLocalDay(entry, date, zone, now)?.seconds ?: 0L else 0L

private fun LocalDate.startOfWeek(firstDayOfWeek: DayOfWeek): LocalDate = with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

private const val DAYS_PER_WEEK = 7L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
