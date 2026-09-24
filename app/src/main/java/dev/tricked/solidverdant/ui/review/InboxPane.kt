/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.domain.inbox.InboxIssue
import dev.tricked.solidverdant.domain.inbox.InboxIssueType
import dev.tricked.solidverdant.domain.inbox.MissingField
import dev.tricked.solidverdant.ui.components.EditTimeEntryDialog
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
import java.util.Locale

/**
 * Time Inbox (gap analysis #16/#17). Renders the deterministic review checks derived by
 * [dev.tricked.solidverdant.domain.inbox.InboxAnalyzer] as a triage list of grouped cards, each with
 * a one-tap quick-fix (the shared edit/create dialog) and swipe-to-dismiss persisted in
 * `inbox_dismissals`. Handles loading, "all caught up", no-data, offline/stale and action-error
 * states — not just the happy path.
 */
private data class InboxEditTarget(val entry: TimeEntry, val isGap: Boolean)

internal data class InboxIssueCardActions(
    val onQuickFix: () -> Unit,
    val onDismiss: () -> Unit,
    val onKeepMine: () -> Unit = {},
    val onKeepTheirs: () -> Unit = {},
)

@Composable
fun InboxPane() {
    val viewModel: InboxViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var editTarget by remember { mutableStateOf<InboxEditTarget?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    InboxUndoSnackbarEffect(
        pendingUndoKey = state.pendingUndoKey,
        hostState = snackbarHostState,
        onUndo = viewModel::undoDismiss,
        onExpired = viewModel::consumeUndo,
    )

    // One-shot action failures.
    val errorRefresh = stringResource(R.string.inbox_error_refresh)
    val errorCreate = stringResource(R.string.inbox_error_create)
    val errorResolve = stringResource(R.string.inbox_error_resolve)
    LaunchedEffect(state.actionError) {
        val message = when (state.actionError) {
            InboxActionError.REFRESH_FAILED -> errorRefresh
            InboxActionError.CREATE_FAILED -> errorCreate
            InboxActionError.RESOLVE_FAILED -> errorResolve
            null -> return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(message)
        viewModel.consumeActionError()
    }

    val zone = state.zone
    val locale = appLocale()
    val projectsById = remember(state.projects) { state.projects.associateBy { it.id } }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            InboxHeader(
                issueCount = state.issues.size,
                isRefreshing = state.isRefreshing,
                showHorizonChip = state.horizonChosen,
                horizonLabel = horizonChipLabel(state.horizonStartMs, zone, locale),
                onHorizonChipClick = { showSettings = true },
                onRefresh = viewModel::refresh,
                onOpenSettings = { showSettings = true },
            )

            if (state.refreshError) {
                StaleBanner(onRetry = viewModel::refresh, onDismiss = viewModel::consumeRefreshError)
            }

            when {
                state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize())
                !state.horizonChosen -> HorizonPicker(onChoose = viewModel::chooseHorizon)
                state.isCaughtUp && state.hasEntries -> InboxMessage(
                    icon = Icons.Filled.CheckCircle,
                    title = stringResource(R.string.inbox_caught_up_title),
                    body = stringResource(R.string.inbox_caught_up_body),
                )
                state.isCaughtUp -> InboxMessage(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.inbox_empty_title),
                    body = stringResource(R.string.inbox_empty_body),
                )
                else -> InboxIssueList(
                    state = state,
                    projectsById = projectsById,
                    zone = zone,
                    viewModel = viewModel,
                    onEdit = { editTarget = it },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    editTarget?.let { target ->
        EditTimeEntryDialog(
            entry = target.entry,
            zone = zone,
            projects = state.projects,
            tasks = state.tasks,
            tags = state.tags,
            preventOverlap = state.preventOverlap,
            onDismiss = { editTarget = null },
            onSave = { description, projectId, taskId, tags, billable, start, end ->
                if (target.isGap) {
                    end?.let { viewModel.fillGap(description, projectId, taskId, tags, billable, start, it) }
                } else {
                    viewModel.resolveEntryEdit(target.entry, description, projectId, taskId, tags, billable, start, end)
                }
                editTarget = null
            },
        )
    }

    if (showSettings) {
        InboxSettingsSheet(
            state = state,
            viewModel = viewModel,
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * Offers Undo for the latest dismissal. A snackbar with an action defaults to an indefinite
 * duration, so without [SnackbarDuration.Long] the undo prompt would never go away on its own.
 */
@Composable
internal fun InboxUndoSnackbarEffect(
    pendingUndoKey: String?,
    hostState: SnackbarHostState,
    onUndo: (String) -> Unit,
    onExpired: () -> Unit,
) {
    val dismissedMessage = stringResource(R.string.inbox_dismissed_snackbar)
    val undoLabel = stringResource(R.string.inbox_undo)
    LaunchedEffect(pendingUndoKey) {
        val key = pendingUndoKey ?: return@LaunchedEffect
        offerUndo(hostState, dismissedMessage, undoLabel, key, onUndo, onExpired)
    }
}

/** Shows the undo snackbar for [key] for a limited time, then reports whether Undo was tapped. */
internal suspend fun offerUndo(
    hostState: SnackbarHostState,
    message: String,
    undoLabel: String,
    key: String,
    onUndo: (String) -> Unit,
    onExpired: () -> Unit,
) {
    val result = hostState.showSnackbar(message = message, actionLabel = undoLabel, duration = SnackbarDuration.Long)
    if (result == SnackbarResult.ActionPerformed) onUndo(key) else onExpired()
}

/**
 * The triage list (SV-005 T4.3). CONFLICT issues stay pinned at the top as their own non-dismissible
 * cards; every other issue is grouped by day (in the account [zone]), newest day first, under a
 * sticky day header carrying the per-day "Dismiss all" and durable "Dismiss everything before this"
 * actions. Individual per-card swipe/button dismiss still works within each day group.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxIssueList(
    state: InboxUiState,
    projectsById: Map<String, Project>,
    zone: ZoneId,
    viewModel: InboxViewModel,
    onEdit: (InboxEditTarget?) -> Unit,
) {
    val conflicts = remember(state.issues) { state.issues.filter { it.type == InboxIssueType.CONFLICT } }
    val dayGroups = remember(state.issues, zone) {
        state.issues
            .filterNot { it.type == InboxIssueType.CONFLICT }
            .groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it }) // newest day first
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Dimens.Space8, bottom = Dimens.Space24),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        items(items = conflicts, key = { it.key }) { issue ->
            IssueCard(issue, state, projectsById, zone, viewModel, onEdit)
        }
        dayGroups.forEach { (date, issues) ->
            stickyHeader(key = "header:$date") {
                InboxDayHeader(
                    date = date,
                    onDismissAll = { viewModel.dismissDay(issues) },
                    onDismissBefore = {
                        viewModel.dismissBefore(date.atStartOfDay(zone).toInstant().toEpochMilli())
                    },
                )
            }
            items(items = issues, key = { it.key }) { issue ->
                DismissibleIssue(
                    onDismiss = { viewModel.dismiss(issue) },
                    content = { IssueCard(issue, state, projectsById, zone, viewModel, onEdit) },
                )
            }
        }
    }
}

@Composable
private fun IssueCard(
    issue: InboxIssue,
    state: InboxUiState,
    projectsById: Map<String, Project>,
    zone: ZoneId,
    viewModel: InboxViewModel,
    onEdit: (InboxEditTarget?) -> Unit,
) {
    InboxIssueCard(
        issue = issue,
        preventOverlap = state.preventOverlap,
        projectsById = projectsById,
        zone = zone,
        actions = InboxIssueCardActions(
            onQuickFix = {
                onEdit(
                    when (issue.type) {
                        InboxIssueType.CONFLICT -> null
                        InboxIssueType.GAP -> {
                            val entry = viewModel.blankEntryFor(
                                startIso = isoOf(issue.startMs, zone),
                                endIso = isoOf(issue.endMs, zone),
                            )
                            entry?.let { InboxEditTarget(it, isGap = true) }
                        }
                        else -> issue.primaryEntry?.let { InboxEditTarget(it, isGap = false) }
                    },
                )
            },
            onDismiss = { viewModel.dismiss(issue) },
            onKeepMine = { viewModel.keepMine(issue) },
            onKeepTheirs = { viewModel.keepTheirs(issue) },
        ),
    )
}

/**
 * Sticky day label in the Time Tracker's history style: grey text on the page background (so it
 * covers the cards scrolling underneath), aligned with the card content, with the day's actions.
 */
@Composable
private fun InboxDayHeader(date: LocalDate, onDismissAll: () -> Unit, onDismissBefore: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val moreCd = stringResource(R.string.inbox_day_more_cd)
    val locale = appLocale()
    val label = remember(date, locale) {
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) to
            date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(start = Dimens.Space32, end = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.inbox_day_header_format, label.first, label.second),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismissAll) { Text(stringResource(R.string.inbox_day_dismiss_all)) }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = moreCd)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inbox_day_dismiss_before)) },
                    onClick = {
                        menuOpen = false
                        onDismissBefore()
                    },
                )
            }
        }
    }
}

@Composable
internal fun InboxHeader(
    issueCount: Int,
    isRefreshing: Boolean,
    showHorizonChip: Boolean,
    horizonLabel: String,
    onHorizonChipClick: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val horizonChipCd = stringResource(R.string.inbox_horizon_chip_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.Space32, end = Dimens.Space8, top = Dimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (issueCount == 0) {
                    stringResource(R.string.inbox_summary_none)
                } else {
                    pluralStringResource(R.plurals.inbox_summary_count, issueCount, issueCount)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (showHorizonChip) {
                // How far back the list looks; tapping it opens the settings where it is chosen.
                Box(
                    modifier = Modifier
                        .heightIn(min = Dimens.MinTouchTarget)
                        .clickable(role = Role.Button, onClick = onHorizonChipClick)
                        .semantics { contentDescription = horizonChipCd },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = horizonLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSmall), strokeWidth = Dimens.Space2)
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(Dimens.MinTouchTarget)) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.inbox_refresh_action))
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(Dimens.MinTouchTarget)) {
            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.inbox_settings_action))
        }
    }
}

/** Couldn't refresh: a grouped banner row with Retry and a close button, on the page background. */
@Composable
private fun StaleBanner(onRetry: () -> Unit, onDismiss: () -> Unit) {
    GroupedSection(modifier = Modifier.padding(vertical = Dimens.Space4)) {
        GroupedRow(
            title = stringResource(R.string.inbox_stale_banner),
            leadingIcon = Icons.Outlined.CloudOff,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRetry, modifier = Modifier.testTag(ReviewTestTags.INBOX_STALE_RETRY)) {
                        Text(stringResource(R.string.inbox_retry))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag(ReviewTestTags.INBOX_STALE_DISMISS)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_dismiss))
                    }
                }
            },
        )
    }
}

/** The shared empty state, centred in the pane, with the message's title and explanation. */
@Composable
private fun InboxMessage(icon: ImageVector, title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(text = "$title\n$body", icon = icon)
    }
}

/**
 * First-run horizon onboarding (SV-005). Shown instead of the list until the user picks how far back
 * Review should look; each choice calls back into [InboxViewModel.chooseHorizon] and unlocks the list.
 */
@Composable
private fun HorizonPicker(onChoose: (HorizonOption) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Dimens.Space16),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Dimens.Space32),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
        ) {
            Text(
                text = stringResource(R.string.inbox_horizon_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.inbox_horizon_picker_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GroupedSection {
            HorizonOption.entries.forEachIndexed { index, option ->
                if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(
                        when (option) {
                            HorizonOption.TODAY -> R.string.inbox_horizon_today
                            HorizonOption.THIS_WEEK -> R.string.inbox_horizon_this_week
                            HorizonOption.LAST_30_DAYS -> R.string.inbox_horizon_last_30_days
                            HorizonOption.EVERYTHING -> R.string.inbox_horizon_everything
                        },
                    ),
                    leadingIcon = when (option) {
                        HorizonOption.TODAY -> Icons.Outlined.Today
                        HorizonOption.THIS_WEEK -> Icons.Outlined.DateRange
                        HorizonOption.LAST_30_DAYS -> Icons.Outlined.CalendarMonth
                        HorizonOption.EVERYTHING -> Icons.Outlined.AllInclusive
                    },
                    onClick = { onChoose(option) },
                    modifier = Modifier.testTag(ReviewTestTags.horizonOption(option)),
                )
            }
        }
    }
}

/**
 * Swipe-to-dismiss wrapper for one issue card. Undo brings a swiped card back under the same key,
 * and the lazy list restores that key's saved swipe position (already dismissed); such a card is
 * put back in place instead of being dismissed again the moment it reappears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DismissibleIssue(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    val restoredDismissed = remember { dismissState.currentValue != SwipeToDismissBoxValue.Settled }
    var armed by remember { mutableStateOf(!restoredDismissed) }
    LaunchedEffect(Unit) {
        if (restoredDismissed) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            armed = true
        }
    }
    LaunchedEffect(dismissState.currentValue, armed) {
        if (armed && dismissState.currentValue != SwipeToDismissBoxValue.Settled) onDismiss()
    }
    val dismissCd = stringResource(R.string.inbox_swipe_dismiss_cd)
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.Space32),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = dismissCd, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        content = { content() },
    )
}

@Composable
internal fun InboxIssueCard(
    issue: InboxIssue,
    preventOverlap: Boolean,
    projectsById: Map<String, Project>,
    zone: ZoneId,
    actions: InboxIssueCardActions,
) {
    if (issue.type == InboxIssueType.CONFLICT) {
        ConflictIssueCard(
            issue = issue,
            projectsById = projectsById,
            onKeepMine = actions.onKeepMine,
            onKeepTheirs = actions.onKeepTheirs,
        )
    } else {
        InboxIssueContent(
            issue = issue,
            preventOverlap = preventOverlap,
            projectsById = projectsById,
            zone = zone,
            onQuickFix = actions.onQuickFix,
            onDismiss = actions.onDismiss,
        )
    }
}

/** A card on the grouped-list surface: the issue's text, then its actions aligned to the end. */
@Composable
private fun IssueSurface(content: @Composable () -> Unit, actions: @Composable () -> Unit) {
    GroupedSection {
        Column(
            modifier = Modifier.padding(start = Dimens.Space16, end = Dimens.Space8, top = Dimens.Space12, bottom = Dimens.Space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
        ) {
            Column(
                modifier = Modifier.padding(end = Dimens.Space8),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space2),
            ) { content() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Space8, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun InboxIssueContent(
    issue: InboxIssue,
    preventOverlap: Boolean,
    projectsById: Map<String, Project>,
    zone: ZoneId,
    onQuickFix: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = appLocale()
    val dateTimeTemplate = stringResource(R.string.inbox_date_time_format)
    val title: String
    val body: String
    val actionLabel: String
    when (issue.type) {
        InboxIssueType.CONFLICT -> error("Conflict issues are rendered by ConflictIssueCard")
        InboxIssueType.OVERLAP -> {
            title = stringResource(R.string.inbox_issue_overlap_title)
            body = if (preventOverlap) {
                stringResource(R.string.inbox_issue_overlap_body_policy)
            } else {
                stringResource(R.string.inbox_issue_overlap_body)
            }
            actionLabel = stringResource(R.string.inbox_action_adjust)
        }
        InboxIssueType.GAP -> {
            title = stringResource(R.string.inbox_issue_gap_title)
            body = stringResource(R.string.inbox_issue_gap_body)
            actionLabel = stringResource(R.string.inbox_action_add_entry)
        }
        InboxIssueType.MISSING_METADATA -> {
            title = stringResource(R.string.inbox_issue_missing_title)
            body = stringResource(R.string.inbox_issue_missing_body, missingFieldsText(issue.missingFields))
            actionLabel = stringResource(R.string.inbox_action_add_details)
        }
        InboxIssueType.LONG_DURATION -> {
            title = stringResource(R.string.inbox_issue_long_title)
            body = stringResource(R.string.inbox_issue_long_body, durationText(issue.endMs - issue.startMs))
            actionLabel = stringResource(R.string.inbox_action_review)
        }
    }

    val subject = issue.primaryEntry?.let { entrySubject(it, projectsById) }
    val timeRange = remember(issue.startMs, issue.endMs, zone, locale, dateTimeTemplate) {
        timeRangeText(issue.startMs, issue.endMs, zone, locale, dateTimeTemplate)
    }

    IssueSurface(
        content = {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = timeRange,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subject != null) {
                Text(
                    text = subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        actions = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.inbox_action_dismiss)) }
            FilledTonalButton(onClick = onQuickFix) { Text(actionLabel) }
        },
    )
}

@Composable
private fun ConflictIssueCard(issue: InboxIssue, projectsById: Map<String, Project>, onKeepMine: () -> Unit, onKeepTheirs: () -> Unit) {
    val mine = issue.primaryEntry
    IssueSurface(
        content = {
            Text(
                text = stringResource(R.string.inbox_issue_conflict_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.inbox_issue_conflict_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.inbox_conflict_mine,
                    when {
                        issue.conflictLocalDeleted -> stringResource(R.string.inbox_conflict_deleted)
                        mine == null -> stringResource(R.string.inbox_conflict_unavailable)
                        else -> conflictVersionText(mine, projectsById)
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Dimens.Space4),
            )
            Text(
                text = stringResource(
                    R.string.inbox_conflict_theirs,
                    when {
                        issue.conflictServerDeleted -> stringResource(R.string.inbox_conflict_deleted)
                        issue.conflictServer == null -> stringResource(R.string.inbox_conflict_unavailable)
                        else -> conflictVersionText(issue.conflictServer, projectsById)
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        actions = {
            TextButton(onClick = onKeepTheirs) { Text(stringResource(R.string.inbox_conflict_keep_theirs)) }
            FilledTonalButton(onClick = onKeepMine) {
                Text(
                    stringResource(
                        if (issue.conflictLocalDeleted) {
                            R.string.inbox_conflict_confirm_delete
                        } else {
                            R.string.inbox_conflict_keep_mine
                        },
                    ),
                )
            }
        },
    )
}

@Composable
private fun conflictVersionText(entry: TimeEntry, projectsById: Map<String, Project>): String {
    val empty = stringResource(R.string.inbox_conflict_empty_value)
    val description = entry.description?.takeIf { it.isNotBlank() } ?: empty
    val project = entry.projectId?.let { projectsById[it]?.name ?: it } ?: empty
    val tags = entry.tags.joinToString(", ") { it.name.ifBlank { it.id } }.ifBlank { empty }
    return stringResource(R.string.inbox_conflict_version_summary, description, project, tags)
}

@Composable
private fun missingFieldsText(fields: Set<MissingField>): String {
    val labels = fields.sortedBy { it.ordinal }.map {
        when (it) {
            MissingField.PROJECT -> stringResource(R.string.inbox_field_project)
            MissingField.TASK -> stringResource(R.string.inbox_field_task)
            MissingField.DESCRIPTION -> stringResource(R.string.inbox_field_description)
            MissingField.TAGS -> stringResource(R.string.inbox_field_tags)
        }
    }
    return labels.joinToString(", ")
}

@Composable
private fun entrySubject(entry: TimeEntry, projectsById: Map<String, Project>): String {
    val description = entry.description?.takeIf { it.isNotBlank() }
    val project = entry.projectId?.let { projectsById[it]?.name }
    return description ?: project ?: stringResource(R.string.inbox_entry_untitled)
}

/** Label for the current horizon: "Everything" when unbounded, else "Since <short date>". */
@Composable
private fun horizonChipLabel(horizonStartMs: Long?, zone: ZoneId, locale: Locale): String = if (horizonStartMs == null) {
    stringResource(R.string.inbox_horizon_everything)
} else {
    val date = remember(horizonStartMs, zone, locale) {
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(horizonStartMs), zone)
            .format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
    stringResource(R.string.inbox_horizon_chip_since, date)
}

private fun timeRangeText(startMs: Long, endMs: Long, zone: ZoneId, locale: Locale, dateTimeTemplate: String): String {
    val start = OffsetDateTime.ofInstant(Instant.ofEpochMilli(startMs), zone)
    val end = OffsetDateTime.ofInstant(Instant.ofEpochMilli(endMs), zone)
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm", locale)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val startText = dateTimeTemplate.format(start.format(dateFormat), start.format(timeFormat))
    val endText = if (start.toLocalDate() == end.toLocalDate()) {
        end.format(timeFormat)
    } else {
        dateTimeTemplate.format(end.format(dateFormat), end.format(timeFormat))
    }
    return "$startText – $endText"
}

@Composable
private fun durationText(durationMs: Long): String {
    val totalMinutes = (durationMs / MILLIS_PER_MINUTE).coerceAtLeast(0)
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 && minutes > 0 -> stringResource(R.string.inbox_duration_hm, hours, minutes)
        hours > 0 -> stringResource(R.string.inbox_duration_h, hours)
        else -> stringResource(R.string.inbox_duration_m, minutes)
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60

private fun isoOf(epochMs: Long, zone: ZoneId): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
