/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.ConfirmDialog
import dev.tricked.solidverdant.ui.components.EmptyState
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.LoadingState
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The compact, guided end-of-day review (gap analysis #18). Shows the day's facts (tracked time,
 * billable share, entries, largest untracked gap) and then walks the user through corrections one
 * at a time — a still-running timer, changes that failed to sync, and uncategorized entries — with
 * a progress indicator. Data comes from cached Room state, so it renders offline. Rendered both by
 * the "Review day" segment and, full-screen, by the end-of-day review route.
 */
@Composable
fun ReviewDayPane() {
    val viewModel: ReviewDayViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showAdjustDialog by remember { mutableStateOf(false) }
    var projectPickerFor by remember { mutableStateOf<ReviewItem?>(null) }
    var confirmSkipFailed by remember { mutableStateOf<ReviewItem?>(null) }

    LaunchedEffect(message) {
        val messageRes = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(resources.getString(messageRes))
        viewModel.consumeMessage()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingState(modifier = Modifier.fillMaxSize())
            !state.hasOrganization -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(text = stringResource(R.string.review_no_org), icon = Icons.Outlined.HourglassEmpty)
            }
            else -> ReviewContent(
                state = state,
                onStop = viewModel::stopRunningTimer,
                onKeepRunning = viewModel::keepRunning,
                onAdjustEnd = { showAdjustDialog = true },
                onRetry = viewModel::retryFailedSync,
                onKeepAsIs = { item ->
                    // Keeping a failed change as it is stops it from ever syncing; confirm that.
                    if (item.type == ReviewItemType.FAILED_SYNC) confirmSkipFailed = item else viewModel.keepAsIs(item)
                },
                onAssign = { item -> projectPickerFor = item },
                onReviewAgain = viewModel::reviewAgain,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showAdjustDialog) {
        // The account's clock, the same one the chosen end is saved in.
        val now = remember { viewModel.suggestedEndTime() }
        ReviewTimePickerDialog(
            title = stringResource(R.string.review_adjust_end_dialog_title),
            initialHour = now.hour,
            initialMinute = now.minute,
            onConfirm = { hour, minute ->
                showAdjustDialog = false
                viewModel.adjustEndTime(hour, minute)
            },
            onDismiss = { showAdjustDialog = false },
        )
    }

    projectPickerFor?.let { item ->
        ProjectPickerDialog(
            projects = state.projects,
            onSelect = { projectId ->
                projectPickerFor = null
                viewModel.assignProject(item, projectId)
            },
            onDismiss = { projectPickerFor = null },
        )
    }

    confirmSkipFailed?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.sweep_review_skip_failed_title),
            message = stringResource(R.string.sweep_review_skip_failed_message),
            confirmLabel = stringResource(R.string.sweep_review_skip_failed_confirm),
            onConfirm = {
                confirmSkipFailed = null
                viewModel.keepAsIs(item)
            },
            onDismiss = { confirmSkipFailed = null },
            destructive = true,
        )
    }
}

@Composable
internal fun ReviewContent(
    state: ReviewDayUiState,
    onStop: () -> Unit,
    onKeepRunning: () -> Unit,
    onAdjustEnd: () -> Unit,
    onRetry: (ReviewItem) -> Unit,
    onKeepAsIs: (ReviewItem) -> Unit,
    onAssign: (ReviewItem) -> Unit,
    onReviewAgain: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Dimens.Space8, bottom = Dimens.Space24),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space24),
    ) {
        SummarySection(state)

        val current = state.currentItem
        when {
            current != null -> Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
                ProgressHeader(state)
                ReviewItemSection(
                    item = current,
                    zone = state.zone,
                    onStop = onStop,
                    onKeepRunning = onKeepRunning,
                    onAdjustEnd = onAdjustEnd,
                    onRetry = onRetry,
                    onKeepAsIs = onKeepAsIs,
                    onAssign = onAssign,
                )
            }

            state.allCaughtUp -> CompletionSection(
                title = stringResource(R.string.review_all_caught_up_title),
                body = stringResource(R.string.review_all_caught_up_body),
                onReviewAgain = onReviewAgain,
            )

            state.nothingTracked -> CompletionSection(
                title = stringResource(R.string.review_nothing_tracked_title),
                body = stringResource(R.string.review_nothing_tracked_body),
                onReviewAgain = null,
            )

            else -> CompletionSection(
                title = stringResource(R.string.review_all_caught_up_title),
                body = stringResource(R.string.review_all_caught_up_body),
                onReviewAgain = null,
            )
        }
    }
}

/** The day's facts as value rows under the date, like the Settings rows. */
@Composable
private fun SummarySection(state: ReviewDayUiState) {
    val date = remember(state.dateEpochDay) { LocalDate.ofEpochDay(state.dateEpochDay) }
    val locale = appLocale()
    val dateLabel = remember(date, locale) {
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    }
    val rows = buildList {
        add(SummaryRow(R.string.review_summary_tracked_label, Icons.Outlined.Timer, formatDuration(state.totalTrackedSeconds)))
        add(
            SummaryRow(
                R.string.review_summary_billable_label,
                Icons.Outlined.AttachMoney,
                state.billablePercent?.let { stringResource(R.string.review_percent, it) } ?: stringResource(R.string.review_value_none),
            ),
        )
        add(SummaryRow(R.string.review_summary_entries_label, Icons.AutoMirrored.Outlined.FormatListBulleted, state.entryCount.toString()))
        if (state.largestGapSeconds > 0) {
            add(SummaryRow(R.string.review_summary_gap_label, Icons.Outlined.HourglassEmpty, formatDuration(state.largestGapSeconds)))
        }
        if (state.uncategorizedCount > 0) {
            add(SummaryRow(R.string.review_summary_uncategorized_label, Icons.Outlined.FolderOff, state.uncategorizedCount.toString()))
        }
        if (state.failedSyncCount > 0) {
            add(SummaryRow(R.string.review_summary_failed_label, Icons.Outlined.SyncProblem, state.failedSyncCount.toString()))
        }
    }
    GroupedSection(header = dateLabel) {
        rows.forEachIndexed { index, row ->
            if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(title = stringResource(row.labelRes), leadingIcon = row.icon, value = row.value)
        }
    }
}

private data class SummaryRow(val labelRes: Int, val icon: ImageVector, val value: String)

@Composable
private fun ProgressHeader(state: ReviewDayUiState) {
    val progress = state.progress
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Space32)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        Text(
            text = stringResource(R.string.review_progress_step, progress.position, progress.total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One correction: what it is about, then its choices as grouped action rows. */
@Composable
@Suppress("LongMethod")
private fun ReviewItemSection(
    item: ReviewItem,
    zone: ZoneId,
    onStop: () -> Unit,
    onKeepRunning: () -> Unit,
    onAdjustEnd: () -> Unit,
    onRetry: (ReviewItem) -> Unit,
    onKeepAsIs: (ReviewItem) -> Unit,
    onAssign: (ReviewItem) -> Unit,
) {
    GroupedSection {
        Column(
            modifier = Modifier.padding(Dimens.Space16),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
        ) {
            when (item.type) {
                ReviewItemType.RUNNING_TIMER -> {
                    ItemTitle(stringResource(R.string.review_item_running_title), stringResource(R.string.review_item_running_body))
                    EntryDescription(item.description)
                    item.startIso?.let { StartedAt(it, zone) }
                }
                ReviewItemType.FAILED_SYNC -> {
                    ItemTitle(
                        stringResource(R.string.review_item_failed_title),
                        item.detail ?: stringResource(R.string.review_item_failed_body),
                    )
                }
                ReviewItemType.UNCATEGORIZED -> {
                    ItemTitle(
                        stringResource(R.string.review_item_uncategorized_title),
                        stringResource(R.string.review_item_uncategorized_body),
                    )
                    EntryDescription(item.description)
                    TimeRange(item.startIso, item.endIso, zone)
                }
            }
        }
        GroupedDivider()
        when (item.type) {
            ReviewItemType.RUNNING_TIMER -> {
                ActionRow(R.string.review_action_stop, Icons.Outlined.StopCircle, ReviewTestTags.REVIEW_ACTION_STOP, onClick = onStop)
                GroupedDivider(inset = Dimens.SettingsIconInset)
                ActionRow(
                    R.string.review_action_adjust_end,
                    Icons.Outlined.Schedule,
                    ReviewTestTags.REVIEW_ACTION_ADJUST_END,
                    opensPicker = true,
                    onClick = onAdjustEnd,
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                ActionRow(
                    R.string.review_action_keep_running,
                    Icons.Outlined.PlayCircle,
                    ReviewTestTags.REVIEW_ACTION_KEEP_RUNNING,
                    onClick = onKeepRunning,
                )
            }
            ReviewItemType.FAILED_SYNC -> {
                ActionRow(R.string.review_action_retry, Icons.Outlined.Refresh, ReviewTestTags.REVIEW_ACTION_RETRY) { onRetry(item) }
                GroupedDivider(inset = Dimens.SettingsIconInset)
                ActionRow(R.string.review_action_skip, Icons.Outlined.DoneAll, ReviewTestTags.REVIEW_ACTION_SKIP) { onKeepAsIs(item) }
            }
            ReviewItemType.UNCATEGORIZED -> {
                ActionRow(
                    R.string.review_action_assign_project,
                    Icons.Outlined.Folder,
                    ReviewTestTags.REVIEW_ACTION_ASSIGN,
                    opensPicker = true,
                ) { onAssign(item) }
                GroupedDivider(inset = Dimens.SettingsIconInset)
                ActionRow(R.string.review_action_skip, Icons.Outlined.DoneAll, ReviewTestTags.REVIEW_ACTION_SKIP) { onKeepAsIs(item) }
            }
        }
    }
}

/** A choice for the current step; only choices that open a picker show the chevron. */
@Composable
private fun ActionRow(labelRes: Int, icon: ImageVector, testTag: String, opensPicker: Boolean = false, onClick: () -> Unit) {
    GroupedRow(
        title = stringResource(labelRes),
        leadingIcon = icon,
        onClick = onClick,
        showChevron = opensPicker,
        modifier = Modifier.testTag(testTag),
    )
}

@Composable
private fun ItemTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EntryDescription(description: String?) {
    val text = if (description.isNullOrBlank()) {
        stringResource(R.string.review_entry_no_description)
    } else {
        description
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = Dimens.Space4),
    )
}

@Composable
private fun StartedAt(startIso: String, zone: ZoneId) {
    val formatter = rememberTimeOfDayFormatter()
    val time = remember(startIso, zone, formatter) { formatClock(startIso, zone, formatter) }
    if (time != null) {
        Text(
            text = stringResource(R.string.review_started_at, time),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimeRange(startIso: String?, endIso: String?, zone: ZoneId) {
    val formatter = rememberTimeOfDayFormatter()
    val start = remember(startIso, zone, formatter) { startIso?.let { formatClock(it, zone, formatter) } }
    val end = remember(endIso, zone, formatter) { endIso?.let { formatClock(it, zone, formatter) } }
    if (start != null && end != null) {
        Text(
            text = stringResource(R.string.review_time_range, start, end),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Done (or nothing to do): the shared empty state on a grouped surface, with "Review again". */
@Composable
private fun CompletionSection(title: String, body: String, onReviewAgain: (() -> Unit)?) {
    GroupedSection {
        EmptyState(text = "$title\n$body", icon = Icons.Outlined.CheckCircle)
        if (onReviewAgain != null) {
            GroupedDivider()
            GroupedRow(
                title = stringResource(R.string.review_again),
                leadingIcon = Icons.Outlined.Replay,
                onClick = onReviewAgain,
                showChevron = false,
                modifier = Modifier.testTag(ReviewTestTags.REVIEW_AGAIN),
            )
        }
    }
}

@Composable
private fun formatDuration(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return if (hours > 0) {
        stringResource(R.string.review_duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.review_duration_minutes, minutes)
    }
}

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60

/** Format an ISO instant string as a wall-clock time in [zone], or null if it cannot be parsed. */
private fun formatClock(iso: String, zone: ZoneId, formatter: DateTimeFormatter): String? = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalTime().format(formatter)
}.recoverCatching {
    Instant.parse(iso).atZone(zone).toLocalTime().format(formatter)
}.getOrNull()
