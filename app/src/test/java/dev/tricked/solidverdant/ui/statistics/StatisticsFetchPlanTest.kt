/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import dev.tricked.solidverdant.data.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Dashboard's server query is bounded to the selected and comparison periods plus a short
 * carry-in (it used to page the whole history on every load), the result is cached per window with
 * a TTL, and Room rows are overlaid on it by id.
 */
class StatisticsFetchPlanTest {

    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun entry(id: String, description: String = id) =
        TimeEntry(id = id, description = description, userId = "u", start = "2026-07-06T09:00:00Z", duration = 60, organizationId = "o")

    private fun key(start: String = "a", end: String = "b") = StatisticsCacheKey("org", "member", StatisticsFetchWindow(start, end))

    @Test
    fun `window starts two local days before the comparison period and ends after the selected one`() {
        val current = LocalDate.parse("2026-07-06")..LocalDate.parse("2026-07-12")
        val window = statisticsFetchWindow(current, previousPeriod(current), amsterdam)

        // Previous period starts 29 Jun; two carry-in days reach back to local midnight 27 Jun (UTC+2).
        assertEquals("2026-06-26T22:00:00Z", window.start)
        // Exclusive end: local midnight after 12 Jul.
        assertEquals("2026-07-12T22:00:00Z", window.end)
    }

    @Test
    fun `window never reaches past the ranges it serves`() {
        val day = LocalDate.parse("2026-01-01")..LocalDate.parse("2026-01-01")
        val window = statisticsFetchWindow(day, previousPeriod(day), ZoneId.of("UTC"))

        // A single day compares with the day before, so three days of history in total.
        assertEquals("2025-12-29T00:00:00Z", window.start)
        assertEquals("2026-01-02T00:00:00Z", window.end)
    }

    @Test
    fun `cached window is reused within the TTL and only offered as stale after it`() {
        val cache = StatisticsRemoteCache(ttlMs = 1_000)
        val entries = listOf(entry("1"))
        cache.put(key(), entries, nowMs = 10_000)

        assertSame(entries, cache.fresh(key(), nowMs = 10_999))
        assertNull(cache.fresh(key(), nowMs = 11_000))
        assertSame(entries, cache.stale(key()))
        assertNull(cache.fresh(key(start = "other"), nowMs = 10_500))
    }

    @Test
    fun `a clock moving backwards does not keep a window fresh forever`() {
        val cache = StatisticsRemoteCache(ttlMs = 1_000)
        cache.put(key(), listOf(entry("1")), nowMs = 10_000)

        assertNull(cache.fresh(key(), nowMs = 9_000))
    }

    @Test
    fun `expireAll forces a refetch but keeps the stale result to show meanwhile`() {
        val cache = StatisticsRemoteCache(ttlMs = 1_000)
        cache.put(key(), listOf(entry("1")), nowMs = 10_000)

        cache.expireAll()

        assertNull(cache.fresh(key(), nowMs = 10_001))
        assertNotNull(cache.stale(key()))
        cache.put(key(), listOf(entry("2")), nowMs = 10_002)
        assertEquals(listOf("2"), cache.fresh(key(), nowMs = 10_003)?.map { it.id })
    }

    @Test
    fun `cache keeps only the most recently used windows`() {
        val cache = StatisticsRemoteCache(ttlMs = 1_000, maxWindows = 2)
        cache.put(key("1"), listOf(entry("1")), nowMs = 0)
        cache.put(key("2"), listOf(entry("2")), nowMs = 0)
        cache.stale(key("1"))
        cache.put(key("3"), listOf(entry("3")), nowMs = 0)

        assertNotNull(cache.stale(key("1")))
        assertNull(cache.stale(key("2")))
        assertNotNull(cache.stale(key("3")))
    }

    @Test
    fun `local rows override server rows by id and pending deletions drop out`() {
        val server = listOf(entry("edited", "server copy"), entry("deleted"), entry("server-only"))
        val local = listOf(entry("edited", "offline edit"), entry("local-create"))
        val known = mutableSetOf<String>()

        val merged = overlayLocalEntries(server, local, pendingDeleteIds = setOf("deleted"), known = known)

        assertEquals(listOf("edited", "local-create", "server-only"), merged.map { it.id })
        assertEquals("offline edit", merged.first().description)
    }

    @Test
    fun `an entry Room has shown stays hidden after its deletion syncs and the row is gone`() {
        val server = listOf(entry("1"), entry("2"))
        val known = mutableSetOf<String>()
        overlayLocalEntries(server, listOf(entry("1"), entry("2")), pendingDeleteIds = emptySet(), known = known)

        // The DELETE for "2" synced: its Room row and outbox op are gone, the snapshot still has it.
        val merged = overlayLocalEntries(server, listOf(entry("1")), pendingDeleteIds = emptySet(), known = known)

        assertEquals(listOf("1"), merged.map { it.id })
    }

    @Test
    fun `without a server result the Room rows are used as they are`() {
        val local = listOf(entry("1"))

        assertSame(local, mergeStatisticsEntries(null, local, emptySet()))
    }
}
