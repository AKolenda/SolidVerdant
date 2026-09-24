/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.domain.inbox.InboxCheckConfig
import dev.tricked.solidverdant.domain.inbox.InboxIssue
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Transient, one-shot failures the Inbox surfaces as a snackbar. Mapped to strings in the pane. */
enum class InboxActionError { REFRESH_FAILED, CREATE_FAILED, RESOLVE_FAILED }

/**
 * SV-005 first-run horizon choices ("how far back should Review look?"). The ViewModel maps each to
 * an epoch-millis lower bound in the account zone / week-start, or null for [EVERYTHING].
 */
enum class HorizonOption { TODAY, THIS_WEEK, LAST_30_DAYS, EVERYTHING }

/**
 * Everything the [InboxPane] renders. The list of [issues] is already filtered for dismissals and
 * ordered by [dev.tricked.solidverdant.domain.inbox.InboxAnalyzer]; the catalogue lists back the
 * reused edit dialog so a quick-fix can reassign project/task/tags.
 */
data class InboxUiState(
    val isLoading: Boolean = true,
    val organizationId: String? = null,
    val issues: List<InboxIssue> = emptyList(),
    /** True when the org has any cached entries at all (distinguishes "caught up" from "no data"). */
    val hasEntries: Boolean = false,
    val isRefreshing: Boolean = false,
    /** A background refresh failed; cached results are still shown (offline / stale). */
    val refreshError: Boolean = false,
    val actionError: InboxActionError? = null,
    /** Key of the just-dismissed issue awaiting an undo window. */
    val pendingUndoKey: String? = null,
    val config: InboxCheckConfig = InboxCheckConfig(),
    /** Whether the org enforces `prevent_overlapping_time_entries` (affects overlap wording). */
    val preventOverlap: Boolean = false,
    val projects: List<Project> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val tags: List<Tag> = emptyList(),
    /** Account temporal-policy zone for gap windows and shown-in-zone formatting. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * SV-005 inbox horizon. Before the user picks ([horizonChosen] false) the pane shows the one-time
     * picker instead of the list. Once chosen, [horizonStartMs] drives the chip: null = "Everything".
     */
    val horizonChosen: Boolean = false,
    val horizonStartMs: Long? = null,
    /** The choice [horizonStartMs] still matches today, or null when it has drifted or was moved by hand. */
    val horizonOption: HorizonOption? = null,
    /** Account week start, so working days list in the user's week order. */
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** All issues resolved: show the reassuring "all caught up" state. */
    val isCaughtUp: Boolean get() = !isLoading && issues.isEmpty()
}

/**
 * The lower bound [option] stands for at [nowMs] in the account [zone]: the start of today, the
 * start of this week (from [firstDayOfWeek]), 30 days ago, or null for [HorizonOption.EVERYTHING].
 */
internal fun horizonStartFor(option: HorizonOption, nowMs: Long, zone: ZoneId, firstDayOfWeek: DayOfWeek): Long? {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (option) {
        HorizonOption.TODAY -> today.startMs(zone)
        HorizonOption.THIS_WEEK -> {
            val daysBack = ((today.dayOfWeek.value - firstDayOfWeek.value) + DAYS_PER_WEEK) % DAYS_PER_WEEK
            today.minusDays(daysBack.toLong()).startMs(zone)
        }
        HorizonOption.LAST_30_DAYS -> nowMs - TimeUnit.DAYS.toMillis(LAST_N_DAYS)
        HorizonOption.EVERYTHING -> null
    }
}

/**
 * Which choice the stored horizon still is, so the settings show it selected. A bound chosen on an
 * earlier day (or moved by "Dismiss everything before this") matches nothing and returns null.
 */
internal fun matchHorizonOption(chosen: Boolean, startMs: Long?, nowMs: Long, zone: ZoneId, firstDayOfWeek: DayOfWeek): HorizonOption? {
    if (!chosen) return null
    if (startMs == null) return HorizonOption.EVERYTHING
    return HorizonOption.entries.firstOrNull { option ->
        val bound = horizonStartFor(option, nowMs, zone, firstDayOfWeek) ?: return@firstOrNull false
        if (option == HorizonOption.LAST_30_DAYS) {
            // "30 days ago" moves with the clock; it still reads as chosen on the day it was picked.
            Instant.ofEpochMilli(bound).atZone(zone).toLocalDate() == Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        } else {
            bound == startMs
        }
    }
}

private fun LocalDate.startMs(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

private const val DAYS_PER_WEEK = 7
private const val LAST_N_DAYS = 30L
