/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import dev.tricked.solidverdant.data.model.TimeEntry
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Days fetched before the earliest shown day. Solidtime's `start` query parameter filters by the
 * entry's start timestamp rather than interval overlap, so an entry that began shortly before the
 * window (an overnight shift, a timer left running) must still be requested to be clipped in.
 * Longer carry-ins are covered by the Room overlay, which holds every cached entry regardless of
 * when it started.
 */
internal const val STATISTICS_CARRY_IN_DAYS = 2L

/** How long a fetched window is reused before the Dashboard asks the server again. */
internal const val STATISTICS_CACHE_TTL_MS = 5 * 60 * 1_000L

/** Windows kept in memory: the current and previous period of a few recently viewed ranges. */
internal const val STATISTICS_CACHE_MAX_WINDOWS = 8

/** The server query for one Dashboard load: entries whose start lies in [start, end). */
internal data class StatisticsFetchWindow(val start: String, val end: String)

/**
 * The bounded server window covering both the selected [current] period and its comparison
 * [previous] period, reaching [STATISTICS_CARRY_IN_DAYS] before the earlier of the two so carry-in
 * entries are clipped in rather than silently dropped. The end is exclusive: the local midnight
 * after the later period ends.
 */
internal fun statisticsFetchWindow(current: ClosedRange<LocalDate>, previous: ClosedRange<LocalDate>, zone: ZoneId): StatisticsFetchWindow {
    val earliest = minOf(current.start, previous.start)
    val latest = maxOf(current.endInclusive, previous.endInclusive)
    return StatisticsFetchWindow(
        start = earliest.minusDays(STATISTICS_CARRY_IN_DAYS).atStartOfDay(zone).toInstant().toString(),
        end = latest.plusDays(1).atStartOfDay(zone).toInstant().toString(),
    )
}

/** Identifies one cached server window for one account membership. */
internal data class StatisticsCacheKey(val organizationId: String, val memberId: String, val window: StatisticsFetchWindow)

/**
 * In-memory, ViewModel-scoped cache of fetched Dashboard windows. A window younger than [ttlMs] is
 * reused without a request, so switching ranges back and forth or returning to the tab does not
 * re-page the server. An older (or [expireAll]ed) window is still offered as stale data to show
 * while it refreshes, or to keep showing when the refresh fails. At most [maxWindows] windows are
 * kept, least recently used first out.
 *
 * It also remembers, per organization, every entry id Room has shown (or queued for deletion) since
 * the ViewModel started: a server row with one of those ids is only ever shown through Room, so an
 * entry deleted locally cannot reappear from an older snapshot once its deletion has synced.
 */
internal class StatisticsRemoteCache(
    private val ttlMs: Long = STATISTICS_CACHE_TTL_MS,
    private val maxWindows: Int = STATISTICS_CACHE_MAX_WINDOWS,
) {
    private class Snapshot(val entries: List<TimeEntry>, val fetchedAtMs: Long, var expired: Boolean = false)

    private val windows = object : LinkedHashMap<StatisticsCacheKey, Snapshot>(maxWindows, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StatisticsCacheKey, Snapshot>?): Boolean = size > maxWindows
    }
    private val localIdsByOrganization = ConcurrentHashMap<String, MutableSet<String>>()

    /** The window's entries when fetched less than [ttlMs] ago and not expired, else null. */
    @Synchronized
    fun fresh(key: StatisticsCacheKey, nowMs: Long): List<TimeEntry>? {
        val snapshot = windows[key] ?: return null
        val age = nowMs - snapshot.fetchedAtMs
        return snapshot.entries.takeIf { !snapshot.expired && age in 0 until ttlMs }
    }

    /** The window's last fetched entries regardless of age, or null when it was never fetched. */
    @Synchronized
    fun stale(key: StatisticsCacheKey): List<TimeEntry>? = windows[key]?.entries

    @Synchronized
    fun put(key: StatisticsCacheKey, entries: List<TimeEntry>, nowMs: Long) {
        windows[key] = Snapshot(entries, nowMs)
    }

    /** Marks every window stale, so the next load refetches while still showing the old result. */
    @Synchronized
    fun expireAll() {
        windows.values.forEach { it.expired = true }
    }

    /** Ids Room has held or queued for deletion in [organizationId]; see the class documentation. */
    fun locallyKnownIds(organizationId: String): MutableSet<String> =
        localIdsByOrganization.getOrPut(organizationId) { ConcurrentHashMap.newKeySet() }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * The entries the Dashboard aggregates: every Room row (the local source of truth, carrying
 * pending creates and edits), plus the server rows Room does not know about. A server row is
 * dropped when its id is in [locallyKnownIds], which must already include every [local] id and
 * every id queued for deletion — so a pending or synced local edit replaces the server copy by id,
 * and a locally deleted row disappears immediately.
 */
internal fun mergeStatisticsEntries(server: List<TimeEntry>?, local: List<TimeEntry>, locallyKnownIds: Set<String>): List<TimeEntry> {
    if (server.isNullOrEmpty()) return local
    val serverOnly = server.filter { it.id !in locallyKnownIds }
    return if (serverOnly.isEmpty()) local else local + serverOnly
}

/**
 * Records [localIds] and [pendingDeleteIds] into [known] and merges. Kept next to
 * [mergeStatisticsEntries] so the ViewModel's single call site cannot forget either half.
 */
internal fun overlayLocalEntries(
    server: List<TimeEntry>?,
    local: List<TimeEntry>,
    pendingDeleteIds: Set<String>,
    known: MutableSet<String>,
): List<TimeEntry> {
    local.forEach { known += it.id }
    known += pendingDeleteIds
    return mergeStatisticsEntries(server, local, known)
}
