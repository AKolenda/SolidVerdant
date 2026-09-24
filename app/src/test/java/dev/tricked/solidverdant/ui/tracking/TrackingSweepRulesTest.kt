/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.SyncOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/** Pure rules behind the Time Tracker sweep: split times, jumps, search options, caches and merges. */
class TrackingSweepRulesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    private fun entry(id: String, start: String, end: String?, org: String = "org", type: TimeEntryType = TimeEntryType.WORK) =
        TimeEntry(id = id, userId = "user", start = start, end = end, organizationId = org, type = type)

    // --- B8: split an entry that runs past midnight ---

    @Test
    fun `a split after midnight lands on the day the entry reaches`() {
        val start = ZonedDateTime.of(2026, 9, 21, 22, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2026, 9, 22, 2, 0, 0, 0, zone)

        assertEquals(ZonedDateTime.of(2026, 9, 22, 1, 0, 0, 0, zone), resolveSplitInstant(start, end, 1, 0))
        assertEquals(ZonedDateTime.of(2026, 9, 21, 23, 30, 0, 0, zone), resolveSplitInstant(start, end, 23, 30))
    }

    @Test
    fun `a split outside the entry or on its bounds is refused`() {
        val start = ZonedDateTime.of(2026, 9, 21, 22, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2026, 9, 22, 2, 0, 0, 0, zone)

        assertNull(resolveSplitInstant(start, end, 3, 0))
        assertNull(resolveSplitInstant(start, end, 22, 0))
        assertNull(resolveSplitInstant(start, end, 2, 0))
    }

    @Test
    fun `a same-day entry splits on its own day`() {
        val start = ZonedDateTime.of(2026, 9, 21, 9, 0, 0, 0, zone)
        val end = ZonedDateTime.of(2026, 9, 21, 17, 0, 0, 0, zone)

        assertEquals(ZonedDateTime.of(2026, 9, 21, 12, 15, 0, 0, zone), resolveSplitInstant(start, end, 12, 15))
        assertNull(resolveSplitInstant(start, end, 8, 0))
    }

    // --- B5: the history jump waits for the list built from the jumped-to window ---

    @Test
    fun `a jump waits while the list still shows the previous window`() {
        val previousWindow = listOf(entry("old", "2026-09-01T08:00:00Z", "2026-09-01T09:00:00Z"))
        val jumpedWindow = listOf(entry("new", "2026-06-10T08:00:00Z", "2026-06-10T09:00:00Z"))
        val staleItems = items(previousWindow)

        assertNull(historyJumpHeaderIndex(LocalDate.of(2026, 6, 10), staleItems, previousWindow, jumpedWindow))

        val currentItems = items(jumpedWindow)
        assertEquals(1, historyJumpHeaderIndex(LocalDate.of(2026, 6, 10), currentItems, jumpedWindow, jumpedWindow))
        assertEquals(-1, historyJumpHeaderIndex(LocalDate.of(2026, 6, 10), emptyList(), jumpedWindow, jumpedWindow))
    }

    private fun items(entries: List<TimeEntry>): List<HistoryListItem> {
        val now = Instant.parse("2026-09-24T12:00:00Z")
        return buildHistoryListItems(
            days = groupEntriesByLocalDay(entries, ZoneOffset.UTC, now, includeRunning = false),
            firstDayOfWeek = DayOfWeek.MONDAY,
            today = LocalDate.of(2026, 9, 24),
            zone = ZoneOffset.UTC,
            now = now,
        )
    }

    // --- B4: the Running and Failed-to-sync search options ---

    @Test
    fun `the running option shows the running entry in the history`() {
        val now = Instant.parse("2026-09-24T12:00:00Z")
        val running = entry("running", "2026-09-24T09:00:00Z", null)
        val done = entry("done", "2026-09-24T07:00:00Z", "2026-09-24T08:00:00Z")
        val filtered = EntryTrustRules.filter(
            entries = listOf(running, done),
            filter = HistoryFilter(runningOnly = true),
            projects = emptyList(),
            tasks = emptyList(),
            zone = ZoneOffset.UTC,
            now = now,
        )
        assertEquals(listOf("running"), filtered.map { it.id })

        val shown = buildHistoryListItems(
            days = groupEntriesByLocalDay(filtered, ZoneOffset.UTC, now, includeRunning = true),
            firstDayOfWeek = DayOfWeek.MONDAY,
            today = LocalDate.of(2026, 9, 24),
            zone = ZoneOffset.UTC,
            now = now,
            includeRunning = true,
        )
        val group = shown.filterIsInstance<HistoryListItem.Group>().single()
        assertEquals("running", group.lead.id)
        assertEquals(3 * 3600L, group.totalSeconds)

        // Without the option the docked timer shows it, not the list.
        assertTrue(groupEntriesByLocalDay(listOf(running), ZoneOffset.UTC, now, includeRunning = false).isEmpty())
    }

    @Test
    fun `the failed option matches an entry whose older change failed behind a queued one`() {
        val failedBehindStop = entry("a", "2026-09-24T07:00:00Z", "2026-09-24T08:00:00Z")
        val onlyQueued = entry("b", "2026-09-24T09:00:00Z", "2026-09-24T10:00:00Z")
        val operations = listOf(
            SyncOperation(entryId = "a", type = OutboxOpType.UPDATE, status = EntrySyncStatus.FAILED, attemptCount = 5, error = null),
            SyncOperation(entryId = "a", type = OutboxOpType.STOP, status = EntrySyncStatus.PENDING, attemptCount = 0, error = null),
            SyncOperation(entryId = "b", type = OutboxOpType.UPDATE, status = EntrySyncStatus.PENDING, attemptCount = 0, error = null),
        )

        val filtered = EntryTrustRules.filter(
            entries = listOf(failedBehindStop, onlyQueued),
            filter = HistoryFilter(syncStatus = EntrySyncStatus.FAILED),
            projects = emptyList(),
            tasks = emptyList(),
            syncOperations = operations,
            zone = ZoneOffset.UTC,
        )

        assertEquals(listOf("a"), filtered.map { it.id })
    }

    // --- P3: suggested start and overlap checks without a pass over every entry ---

    @Test
    fun `a new entry starts at the latest end today`() {
        val now = ZonedDateTime.of(2026, 9, 24, 15, 0, 0, 0, ZoneOffset.UTC)
        val entries = listOf(
            entry("running", "2026-09-24T14:00:00Z", null),
            entry("late", "2026-09-24T12:00:00Z", "2026-09-24T13:30:00Z"),
            entry("early", "2026-09-24T08:00:00Z", "2026-09-24T09:00:00Z"),
            entry("overnight", "2026-09-23T22:00:00Z", "2026-09-24T01:00:00Z"),
        )

        assertEquals(ZonedDateTime.of(2026, 9, 24, 13, 30, 0, 0, ZoneOffset.UTC), suggestedManualEntryStart(entries, now))
    }

    @Test
    fun `without an ended entry today a new entry starts an hour ago`() {
        val now = ZonedDateTime.of(2026, 9, 24, 15, 0, 0, 0, ZoneOffset.UTC)
        val entries = listOf(
            entry("future", "2026-09-24T16:00:00Z", "2026-09-24T17:00:00Z"),
            entry("yesterday", "2026-09-23T08:00:00Z", "2026-09-23T09:00:00Z"),
        )

        assertEquals(now.minusHours(1), suggestedManualEntryStart(entries, now))
    }

    @Test
    fun `the overlap index agrees with the pairwise overlap rule`() {
        val random = Random(7)
        val base = Instant.parse("2026-09-20T00:00:00Z")
        val existing = (0 until 60).map { index ->
            val start = base.plusSeconds(random.nextLong(0, 5 * 86_400L))
            val length = if (index % 17 == 0) random.nextLong(86_400L, 3 * 86_400L) else random.nextLong(60L, 4 * 3_600L)
            entry(
                id = "e$index",
                start = start.toString(),
                end = if (index % 23 == 0) null else start.plusSeconds(length).toString(),
                org = if (index % 11 == 0) "other" else "org",
                type = if (index % 13 == 0) TimeEntryType.BREAK else TimeEntryType.WORK,
            )
        }
        val now = base.plusSeconds(6 * 86_400L)
        val index = EntryOverlapIndex.of(existing, now)

        repeat(300) { attempt ->
            val start = base.plusSeconds(random.nextLong(-86_400L, 6 * 86_400L))
            val end = start.plusSeconds(random.nextLong(-600L, 10 * 3_600L))
            val excludeId = if (attempt % 5 == 0) "e${random.nextInt(60)}" else ""
            val candidate = entry(excludeId, start.toString(), end.toString())
            val expected = existing.any { it.id != excludeId && EntryTrustRules.overlaps(candidate, it, now) }

            assertEquals("attempt $attempt", expected, index.overlaps(excludeId, "org", start, end))
        }
    }

    // --- P3: the paginated history merge in one pass ---

    @Test
    fun `one-pass insertion matches inserting each entry in turn`() {
        val random = Random(11)
        repeat(200) { attempt ->
            val entries = (0 until random.nextInt(0, 12)).map { entry("d$it", startAt(random.nextInt(0, 20)), "x") }
            val additions = (0 until random.nextInt(0, 6)).map { entry("a$it", startAt(random.nextInt(0, 20)), "x") }

            val expected = additions.fold(entries) { list, addition ->
                val insertionIndex = list.indexOfFirst { it.start < addition.start }
                if (insertionIndex == -1) list + addition else list.toMutableList().apply { add(insertionIndex, addition) }
            }

            assertEquals("attempt $attempt", expected.map { it.id }, HistoryWindow.insertNewestFirst(entries, additions).map { it.id })
        }
    }

    private fun startAt(hour: Int) = "2026-09-24T${hour.toString().padStart(2, '0')}:00:00Z"

    // --- P1: the first-frame cache holds only what the first frame shows ---

    @Test
    fun `the first-frame cache keeps only the catalogue the cached entries show`() {
        val shown = entry("shown", "2026-09-24T08:00:00Z", "2026-09-24T09:00:00Z")
            .copy(projectId = "p1", taskId = "t1", tags = listOf(Tag("tag1", "Deep")))
        val running = entry("running", "2026-09-24T10:00:00Z", null).copy(projectId = "p2")
        val older = (0 until FIRST_FRAME_CACHE_ENTRY_LIMIT).map {
            entry("old$it", "2026-08-01T08:00:00Z", "2026-08-01T09:00:00Z").copy(projectId = "p3")
        }

        val cache = firstFrameCacheOf(
            organizationId = "org",
            entries = listOf(shown) + older,
            active = running,
            projects = listOf(
                Project("p1", "One", "#000000", clientId = "c1"),
                Project("p2", "Two", "#000000"),
                Project("p3", "Three", "#000000"),
                Project("p4", "Unused", "#000000", clientId = "c2"),
            ),
            clients = listOf(Client("c1", "First"), Client("c2", "Second")),
            tasks = listOf(
                Task(id = "t1", name = "Task", projectId = "p1", createdAt = "", updatedAt = ""),
                Task(id = "t2", name = "Other", projectId = "p4", createdAt = "", updatedAt = ""),
            ),
            tags = listOf(Tag("tag1", "Deep"), Tag("tag2", "Unused")),
            overlapCount = 0,
        )

        assertEquals(FIRST_FRAME_CACHE_ENTRY_LIMIT, cache.timeEntries.size)
        assertEquals(setOf("p1", "p2", "p3"), cache.projects.map { it.id }.toSet())
        assertEquals(listOf("c1"), cache.clients.map { it.id })
        assertEquals(listOf("t1"), cache.tasks.map { it.id })
        assertEquals(listOf("tag1"), cache.tags.map { it.id })
        assertEquals("running", cache.activeEntry?.id)
    }

    // --- P2 and P4: equal state for work elsewhere ---

    @Test
    fun `a card status is unchanged by other entries' sync and review changes`() {
        val group = HistoryListItem.Group(
            date = LocalDate.of(2026, 9, 24),
            entries = listOf(entry("a", "2026-09-24T08:00:00Z", "2026-09-24T09:00:00Z")),
            entrySeconds = listOf(3600L),
        )
        val before = historyCardStatus(group, mapOf("a" to EntrySyncStatus.PENDING), mapOf("a" to setOf(EntryReviewIssue.NO_PROJECT)))
        val after = historyCardStatus(
            group,
            mapOf("a" to EntrySyncStatus.PENDING, "b" to EntrySyncStatus.FAILED),
            mapOf("a" to setOf(EntryReviewIssue.NO_PROJECT), "b" to setOf(EntryReviewIssue.OVERLAP)),
        )

        assertEquals(before, after)
        assertEquals(EntrySyncStatus.PENDING, after.groupSyncStatus)
        assertFalse(before == historyCardStatus(group, mapOf("a" to EntrySyncStatus.FAILED), emptyMap()))
    }

    @Test
    fun `typing an idle draft leaves the screen state equal`() {
        val idle = TrackingUiState(editingDescription = "Wri", editingProjectId = "p1")
        val typed = idle.copy(editingDescription = "Writing")

        assertEquals(idle.withoutIdleDraft(), typed.withoutIdleDraft())
        assertEquals("Writing", typed.entryDraft().description)
        assertEquals("p1", typed.entryDraft().projectId)

        val running = TrackingUiState(isTracking = true, editingDescription = "Running work")
        assertSame(running, running.withoutIdleDraft())
        assertNotSame(idle, idle.withoutIdleDraft())
    }

    // --- B1: a stale local id still names the running entry after START reconciliation ---

    @Test
    fun `a retired local id matches the reconciled running entry by identity`() {
        val local = entry("local-1", "2026-09-24T08:00:00Z", null)
        val reconciled = local.copy(id = "server-1")

        assertTrue(isSameRunningEntry(reconciled, local))
        assertFalse(isSameRunningEntry(reconciled.copy(start = "2026-09-24T08:05:00Z"), local))
        assertFalse(isSameRunningEntry(local, reconciled.copy(id = "server-2")))
    }
}
