/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.core.graphics.toColorInt
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.SyncChip
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.readableOn
import dev.tricked.solidverdant.ui.theme.syncFailed
import dev.tricked.solidverdant.ui.theme.syncPending
import dev.tricked.solidverdant.ui.theme.tabular
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bold week label ("This week", "Last week" or "Aug 17 – Aug 23") with the week total. */
@Composable
internal fun HistoryWeekHeader(week: HistoryListItem.Week) {
    val locale = appLocale()
    val label = when (week.label) {
        HistoryWeekLabel.THIS_WEEK -> stringResource(R.string.history_this_week)
        HistoryWeekLabel.LAST_WEEK -> stringResource(R.string.history_last_week)
        HistoryWeekLabel.DATE_RANGE -> {
            // The range omits the year; the day headers below carry it.
            val formatter = remember(locale) {
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
            }
            stringResource(R.string.history_week_range, week.start.format(formatter), week.end.format(formatter))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.Space16, end = Dimens.Space16, top = Dimens.Space16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = remember(week.totalSeconds) { formatClockDuration(week.totalSeconds) },
            style = MaterialTheme.typography.titleMedium.tabular(),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Grey "Today" / "Yesterday" / "Wed, Jun 10" label with the day total; tapping opens the jump-to-date picker. */
@Composable
internal fun HistoryDayHeader(day: HistoryDay, zone: ZoneId, onClick: () -> Unit) {
    val context = LocalContext.current
    val locale = appLocale()
    val label = remember(day.date, zone, locale) { formatHistoryDayLabel(day.date, context, zone, locale) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable(onClickLabel = stringResource(R.string.jump_to_date), onClick = onClick)
            .padding(horizontal = Dimens.Space16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = remember(day.totalSeconds) { formatClockDuration(day.totalSeconds) },
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Card for one [HistoryListItem.Group]. A single entry shows its time range and a
 * "⋯" menu and edits on tap. A stack of identical entries shows the summed duration, a count with a
 * chevron that expands one sub-row per entry, and a sliver peeking below while collapsed.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun HistoryEntryCard(
    group: HistoryListItem.Group,
    zone: ZoneId,
    project: Project?,
    task: Task?,
    client: Client?,
    status: HistoryCardStatus,
    onEdit: (TimeEntry) -> Unit,
    onDelete: (TimeEntry) -> Unit,
    onDuplicate: ((TimeEntry) -> Unit)?,
    onRetrySync: (TimeEntry) -> Unit,
    onContinue: ((TimeEntry) -> Unit)?,
    onDeleteStack: ((List<TimeEntry>) -> Unit)? = null,
) {
    val lead = group.lead
    val groupIssues = status.reviewIssues
    val stacked = group.entries.size > 1
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    val groupSyncStatus = status.groupSyncStatus
    val retryGroup = {
        group.entries
            .filterIndexed { index, _ -> status.entrySyncStatuses.getOrNull(index)?.let(::canRetrySync) == true }
            .forEach(onRetrySync)
    }
    val toggleLabel = if (expanded) {
        stringResource(R.string.history_hide_entries)
    } else {
        pluralStringResource(R.plurals.history_show_entries, group.entries.size, group.entries.size)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.Space8, end = Dimens.Space8, bottom = Dimens.Space8),
    ) {
        SwipeableHistoryRow(
            shape = shape,
            // Swiping a stack away deletes every entry in it, after the caller's confirmation.
            onDelete = if (stacked) onDeleteStack?.let { deleteStack -> { deleteStack(group.entries) } } else ({ onDelete(lead) }),
            onContinue = onContinue?.let { continueEntry -> { continueEntry(lead) } },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .testTag(TrackingTestTags.ENTRY_ROW),
            ) {
                val headerClick = if (stacked) {
                    Modifier.clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                } else {
                    Modifier
                        .testTag(TrackingTestTags.ENTRY_EDIT_BUTTON)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit)) { onEdit(lead) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(headerClick)
                        .padding(start = Dimens.Space16, end = Dimens.Space4, top = Dimens.Space4),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EntryTitle(project = project, task = task, client = client, modifier = Modifier.weight(1f))
                        Text(
                            text = remember(group.entrySeconds) { formatClockDuration(group.totalSeconds) },
                            style = MaterialTheme.typography.bodyLarge.tabular(),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = Dimens.Space8),
                        )
                        if (stacked) {
                            GroupToggle(
                                count = group.entries.size,
                                expanded = expanded,
                                label = toggleLabel,
                                modifier = Modifier.testTag(TrackingTestTags.entryGroupToggle(lead.id)),
                                onToggle = { expanded = !expanded },
                            )
                        } else {
                            EntryActionsMenu(entry = lead, onContinue = onContinue, onDuplicate = onDuplicate, onDelete = onDelete)
                        }
                    }
                    Text(
                        text = lead.description?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.no_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (lead.description.isNullOrEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = Dimens.Space12),
                    )
                    if (groupIssues.isNotEmpty()) EntryReviewLine(groupIssues)
                    EntryMetaRow(
                        entry = lead,
                        timeRangeEntry = lead.takeUnless { stacked },
                        zone = zone,
                        syncStatus = groupSyncStatus,
                        retryTag = TrackingTestTags.entryRetrySyncButton(lead.id),
                        onRetrySync = if (stacked) retryGroup else ({ onRetrySync(lead) }),
                        onContinue = onContinue?.let { continueEntry -> { continueEntry(lead) } },
                    )
                }
                if (stacked && expanded) {
                    group.entries.forEachIndexed { index, entry ->
                        GroupedDivider()
                        GroupedEntryRow(
                            entry = entry,
                            seconds = group.entrySeconds[index],
                            zone = zone,
                            syncStatus = status.entrySyncStatuses.getOrNull(index),
                            onEdit = onEdit,
                            onDelete = onDelete,
                            onDuplicate = onDuplicate,
                            onContinue = onContinue,
                        )
                    }
                }
            }
        }
        if (stacked && !expanded) {
            Box(
                Modifier
                    .padding(horizontal = Dimens.Space12)
                    .fillMaxWidth()
                    .height(Dimens.HistoryStackPeek)
                    .clip(RoundedCornerShape(bottomStart = Dimens.RadiusLg, bottomEnd = Dimens.RadiusLg))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** "Project: Task" in the project colour, then " - Client"; "No Project" when unassigned. */
@Composable
private fun EntryTitle(project: Project?, task: Task?, client: Client?, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    if (project == null) {
        Text(
            text = stringResource(R.string.no_project),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val projectColor = remember(project.color, onSurface, cardColor) {
        runCatching { Color(project.color.toColorInt()) }
            .map { it.readableOn(background = cardColor, towards = onSurface) }
            .getOrDefault(onSurface)
    }
    val projectLabel = if (task == null) project.name else stringResource(R.string.history_project_task, project.name, task.name)
    val clientSuffix = client?.let { stringResource(R.string.history_client_suffix, it.name) }
    val title = remember(projectLabel, clientSuffix, projectColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = projectColor)) { append(projectLabel) }
            clientSuffix?.let(::append)
        }
    }
    Text(
        text = title,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Tags, billable and sync state on the left; the time range (single entries only) and continue
 * button on the right.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun EntryMetaRow(
    entry: TimeEntry,
    timeRangeEntry: TimeEntry?,
    zone: ZoneId,
    syncStatus: TimeEntryRepository.EntrySyncStatus?,
    retryTag: String,
    onRetrySync: () -> Unit,
    onContinue: (() -> Unit)?,
) {
    val hasTags = entry.tags.isNotEmpty()
    val tagNames = remember(entry.tags) { entry.tags.joinToString(", ") { it.name } }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocalOffer,
                contentDescription = if (hasTags) null else stringResource(R.string.history_no_tags),
                tint = if (hasTags) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            if (hasTags) {
                Text(
                    text = tagNames,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Icon(
                imageVector = Icons.Outlined.Paid,
                contentDescription = stringResource(if (entry.billable) R.string.billable else R.string.history_not_billable),
                tint = if (entry.billable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            // Healthy entries stay clean; only pending, retrying or failed changes surface, and a
            // change that has not reached the server gets a tappable retry.
            if (syncStatus != null && canRetrySync(syncStatus)) {
                IconButton(onClick = onRetrySync, modifier = Modifier.size(Dimens.MinTouchTarget).testTag(retryTag)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.sync_retry_entry),
                        tint = if (syncStatus == TimeEntryRepository.EntrySyncStatus.FAILED) {
                            MaterialTheme.colorScheme.syncFailed
                        } else {
                            MaterialTheme.colorScheme.syncPending
                        },
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                }
            } else {
                syncStatus?.let { SyncChip(status = it, showLabel = false) }
            }
        }
        if (timeRangeEntry != null) {
            EntryTimeRangeText(
                entry = timeRangeEntry,
                zone = zone,
                modifier = Modifier.weight(1f).padding(start = Dimens.Space8),
                textAlign = TextAlign.End,
            )
        }
        if (onContinue != null) {
            IconButton(onClick = onContinue, modifier = Modifier.testTag(TrackingTestTags.entryContinueButton(entry.id))) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.resume),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Spacer(Modifier.width(Dimens.Space12))
        }
    }
}

@Composable
private fun EntryTimeRangeText(entry: TimeEntry, zone: ZoneId, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val locale = appLocale()
    val nowLabel = stringResource(R.string.tracking_now)
    val invalidTimeLabel = stringResource(R.string.tracking_invalid_time)
    val timeRange = remember(entry.start, entry.end, zone, locale, nowLabel, invalidTimeLabel) {
        formatTimeRange(entry.start, entry.end, zone, locale, nowLabel, invalidTimeLabel)
    }
    Text(
        text = timeRange,
        style = MaterialTheme.typography.bodySmall.tabular(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier.testTag(TrackingTestTags.entryTimeRange(entry.id)),
    )
}

/** One entry of an expanded stack: time range, duration and the "⋯" menu; tapping edits it. */
@Composable
private fun GroupedEntryRow(
    entry: TimeEntry,
    seconds: Long,
    zone: ZoneId,
    syncStatus: TimeEntryRepository.EntrySyncStatus?,
    onEdit: (TimeEntry) -> Unit,
    onDelete: (TimeEntry) -> Unit,
    onDuplicate: ((TimeEntry) -> Unit)?,
    onContinue: ((TimeEntry) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .testTag(TrackingTestTags.ENTRY_EDIT_BUTTON)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit)) { onEdit(entry) }
            .padding(start = Dimens.Space16, end = Dimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        EntryTimeRangeText(entry = entry, zone = zone, modifier = Modifier.weight(1f))
        syncStatus?.let { SyncChip(status = it, showLabel = false) }
        Text(
            text = remember(seconds) { formatClockDuration(seconds) },
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        EntryActionsMenu(entry = entry, onContinue = onContinue, onDuplicate = onDuplicate, onDelete = onDelete)
    }
}

/** Entry count and a chevron that flips while the stack is expanded. */
@Composable
private fun GroupToggle(count: Int, expanded: Boolean, label: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .widthIn(min = Dimens.MinTouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label }
            .padding(horizontal = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space2, Alignment.CenterHorizontally),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.rotate(if (expanded) HALF_TURN_DEGREES else 0f),
        )
    }
}

/** The entry's review checks in one warning line, so problems are fixed from the card itself. */
@Composable
private fun EntryReviewLine(issues: Set<EntryReviewIssue>) {
    val longHours = LocalLongEntryHours.current
    val labels = issues.map { issue ->
        when (issue) {
            EntryReviewIssue.NO_PROJECT -> stringResource(R.string.review_issue_no_project)
            EntryReviewIssue.NO_DESCRIPTION -> stringResource(R.string.review_issue_no_description)
            EntryReviewIssue.OVERLAP -> stringResource(R.string.review_issue_overlap)
            EntryReviewIssue.LONG_DURATION -> pluralStringResource(R.plurals.review_issue_long, longHours, longHours)
        }
    }
    Row(
        modifier = Modifier.padding(top = Dimens.Space4, end = Dimens.Space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space4 + Dimens.Space2),
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(Dimens.IconSmall),
        )
        Text(
            text = labels.joinToString(stringResource(R.string.review_issue_separator)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(TrackingTestTags.ENTRY_REVIEW_ISSUES),
        )
    }
}

/** Hours after which a finished entry counts as long; the Time Tracker provides the preference. */
internal val LocalLongEntryHours = androidx.compose.runtime.staticCompositionLocalOf { DEFAULT_LONG_ENTRY_HOURS }

private const val DEFAULT_LONG_ENTRY_HOURS = 10

/** "⋯" button anchoring Continue (while no timer runs), Duplicate and Delete for one entry. */
@Composable
private fun EntryActionsMenu(
    entry: TimeEntry,
    onContinue: ((TimeEntry) -> Unit)?,
    onDuplicate: ((TimeEntry) -> Unit)?,
    onDelete: (TimeEntry) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TrackingTestTags.entryActionsButton(entry.id))) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = stringResource(R.string.history_entry_actions),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onContinue != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.entry_continue)) },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    onClick = {
                        open = false
                        onContinue(entry)
                    },
                    modifier = Modifier.testTag(TrackingTestTags.ENTRY_CONTINUE_ACTION),
                )
            }
            if (onDuplicate != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.duplicate_entry)) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    onClick = {
                        open = false
                        onDuplicate(entry)
                    },
                    modifier = Modifier.testTag(TrackingTestTags.ENTRY_DUPLICATE_ACTION),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDelete(entry)
                },
                modifier = Modifier.testTag(TrackingTestTags.ENTRY_DELETE_ACTION),
            )
        }
    }
}

/**
 * Card wrapped in iOS-style swipe actions: swipe left to delete, swipe right to continue the entry
 * as a new timer (only offered while no timer runs). Both are also TalkBack custom actions. A null
 * [onDelete] disables the delete swipe; the caller confirms a delete before anything is removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableHistoryRow(shape: Shape, onDelete: (() -> Unit)?, onContinue: (() -> Unit)?, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> onDelete?.invoke()
            SwipeToDismissBoxValue.StartToEnd -> onContinue?.invoke()
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        // A refused delete (e.g. a conflict-locked entry) must not leave the row swiped away.
        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
    }
    val deleteLabel = stringResource(R.string.delete)
    val continueLabel = stringResource(R.string.resume)
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = onContinue != null,
        enableDismissFromEndToStart = onDelete != null,
        modifier = Modifier.semantics {
            customActions = listOfNotNull(
                onDelete?.let {
                    CustomAccessibilityAction(deleteLabel) {
                        it()
                        true
                    }
                },
                onContinue?.let {
                    CustomAccessibilityAction(continueLabel) {
                        it()
                        true
                    }
                },
            )
        },
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val continuing = direction == SwipeToDismissBoxValue.StartToEnd
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(
                        when (direction) {
                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                            SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                            SwipeToDismissBoxValue.Settled -> Color.Transparent
                        },
                    )
                    .padding(horizontal = Dimens.Space24),
                contentAlignment = if (continuing) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (direction != SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = if (continuing) Icons.Default.PlayArrow else Icons.Default.Delete,
                        contentDescription = if (continuing) continueLabel else deleteLabel,
                        tint = if (continuing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                    )
                }
            }
        },
        content = { content() },
    )
}

private const val HALF_TURN_DEGREES = 180f

/** The day label: the weekday plus day and month, with the year only outside the current year. */
internal fun formatHistoryDayLabel(date: LocalDate, context: android.content.Context, zone: ZoneId, locale: Locale): String {
    val today = LocalDate.now(zone)
    return when (date) {
        today -> context.getString(R.string.today)
        today.minusDays(1) -> context.getString(R.string.yesterday)
        else -> {
            val skeleton = if (date.year == today.year) "EEEMMMd" else "EEEMMMdy"
            date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
        }
    }
}
