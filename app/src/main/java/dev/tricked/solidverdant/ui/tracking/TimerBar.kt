/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.toColorInt
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSwitchRow
import dev.tricked.solidverdant.ui.components.SelectorStyle
import dev.tricked.solidverdant.ui.components.TagsSelector
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.readableOn
import dev.tricked.solidverdant.ui.theme.tabular

/**
 * The running timer, docked at the bottom of Time Tracker: description and
 * "Project: Task" on the left, then the elapsed time, pause or resume, and the orange-red stop
 * button. Tapping the details or swiping the bar up opens the running entry's full details.
 */
@Composable
internal fun ActiveTimerBar(
    uiState: TrackingUiState,
    elapsedSeconds: Long,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEditActiveEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canEdit = uiState.currentTimeEntry != null && !uiState.isMutating
    val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val swipeUpThresholdPx = with(LocalDensity.current) { Dimens.TimerSwipeUpThreshold.toPx() }
    // The swipe gesture outlives recompositions; it must open the timer that is running now.
    val openDetails by rememberUpdatedState(onEditActiveEntry)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TrackingTestTags.ACTIVE_TIMER_BAR)
            .pointerInput(canEdit, swipeUpThresholdPx) {
                if (!canEdit) return@pointerInput
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged <= -swipeUpThresholdPx) openDetails() },
                    onVerticalDrag = { change, dragAmount ->
                        dragged += dragAmount
                        change.consume()
                    },
                )
            },
        color = barColor,
        shape = RoundedCornerShape(topStart = Dimens.RadiusXl, topEnd = Dimens.RadiusXl),
        shadowElevation = Dimens.SheetShadow,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // The handle says the bar pulls up into the entry's details, like a bottom sheet.
            Box(
                modifier = Modifier
                    .padding(top = Dimens.Space8)
                    .align(Alignment.CenterHorizontally)
                    .size(width = Dimens.SheetHandleWidth, height = Dimens.SheetHandleHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA)),
            )
            ActiveTimerRow(
                uiState = uiState,
                elapsedSeconds = elapsedSeconds,
                canEdit = canEdit,
                barColor = barColor,
                onStop = onStop,
                onPause = onPause,
                onResume = onResume,
                onEditActiveEntry = onEditActiveEntry,
            )
        }
    }
}

@Composable
private fun ActiveTimerRow(
    uiState: TrackingUiState,
    elapsedSeconds: Long,
    canEdit: Boolean,
    barColor: Color,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEditActiveEntry: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val project = remember(uiState.projects, uiState.editingProjectId) {
        uiState.projects.firstOrNull { it.id == uiState.editingProjectId }
    }
    val task = remember(uiState.tasks, uiState.editingTaskId) {
        uiState.tasks.firstOrNull { it.id == uiState.editingTaskId }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.Space4, end = Dimens.Space12, bottom = Dimens.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.ControlHeightLarge)
                .clip(MaterialTheme.shapes.medium)
                .testTag(TrackingTestTags.EDIT_ACTIVE_ENTRY)
                .clickable(
                    enabled = canEdit,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.edit),
                    onClick = onEditActiveEntry,
                )
                .padding(horizontal = Dimens.Space12, vertical = Dimens.Space8),
            verticalArrangement = Arrangement.Center,
        ) {
            if (uiState.isPaused) {
                Text(
                    text = stringResource(R.string.paused),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                text = uiState.editingDescription.ifBlank { stringResource(R.string.no_description) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (uiState.editingDescription.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProjectTaskLine(project = project, task = task, background = barColor)
        }
        if (uiState.isTracking) {
            Text(
                text = formatElapsedTime(elapsedSeconds),
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(TrackingTestTags.ELAPSED_TIMER),
            )
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPause()
                },
                enabled = !uiState.isMutating,
                modifier = Modifier.size(Dimens.MinTouchTarget),
            ) {
                Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.pause))
            }
        } else {
            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onResume()
                },
                enabled = !uiState.isMutating,
                modifier = Modifier.size(Dimens.MinTouchTarget),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.resume))
            }
        }
        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onStop()
            },
            enabled = !uiState.isMutating,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.size(Dimens.MinTouchTarget).testTag(TrackingTestTags.STOP_BUTTON),
        ) {
            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop))
        }
    }
}

/**
 * Top of the running entry's details: the live elapsed time with pause or resume and stop, so the
 * timer stays controllable while its description, project, tags and start are edited below.
 */
@Composable
internal fun RunningTimerControls(
    elapsedSeconds: Long,
    isPaused: Boolean,
    enabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Space16)
            .testTag(TrackingTestTags.RUNNING_TIMER_CONTROLS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(if (isPaused) R.string.paused else R.string.running_entries),
                style = MaterialTheme.typography.labelMedium,
                color = if (isPaused) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = formatElapsedTime(elapsedSeconds),
                style = MaterialTheme.typography.displaySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (isPaused) {
            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onResume()
                },
                enabled = enabled,
                modifier = Modifier.size(Dimens.MinTouchTarget),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.resume))
            }
        } else {
            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPause()
                },
                enabled = enabled,
                modifier = Modifier.size(Dimens.MinTouchTarget),
            ) {
                Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.pause))
            }
        }
        FilledIconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onStop()
            },
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.size(Dimens.MinTouchTarget).testTag(TrackingTestTags.RUNNING_TIMER_STOP),
        ) {
            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop))
        }
    }
}

private const val HANDLE_ALPHA = 0.4f

/**
 * The new-entry button. Idle, "+" unfolds into Manual (add a finished entry) and Timer
 * (open the start-timer sheet); while a timer runs there is nothing to start, so "+" adds a manual
 * entry directly.
 */
@Composable
internal fun TimerFab(
    timerActive: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStartTimer: () -> Unit,
    onAddManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val addLabel = stringResource(R.string.add_time_entry)
    if (timerActive) {
        FloatingActionButton(
            onClick = onAddManual,
            modifier = modifier.testTag(TrackingTestTags.ADD_ENTRY_BUTTON),
            shape = CircleShape,
            // A bright accent button rather than M3's tonal container.
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Default.Add, contentDescription = addLabel)
        }
        return
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
    ) {
        if (expanded) {
            FabOption(label = stringResource(R.string.timer_manual)) {
                SmallFloatingActionButton(
                    onClick = {
                        onExpandedChange(false)
                        onAddManual()
                    },
                    // Smaller than the Timer button, but still a full touch target.
                    modifier = Modifier.size(Dimens.MinTouchTarget).testTag(TrackingTestTags.ADD_ENTRY_BUTTON),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Outlined.Keyboard, contentDescription = addLabel)
                }
            }
            FabOption(label = stringResource(R.string.nav_timer)) {
                FloatingActionButton(
                    onClick = {
                        onExpandedChange(false)
                        onStartTimer()
                    },
                    modifier = Modifier.testTag(TrackingTestTags.START_TIMER_ACTION),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.start_timer_title))
                }
            }
        } else {
            FloatingActionButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.testTag(TrackingTestTags.TIMER_FAB),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.timer_fab_open))
            }
        }
    }
}

@Composable
private fun FabOption(label: String, button: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        button()
    }
}

/**
 * Start-timer sheet opened from the new-entry button: the next entry's fields, then [shortcuts]
 * (continue the last entry, favourites) for starting in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartTimerSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Dimens.Space24)
                .testTag(TrackingTestTags.START_TIMER_SHEET),
        ) {
            Text(
                text = stringResource(R.string.start_timer_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = Dimens.Space16, vertical = Dimens.Space8),
            )
            content()
        }
    }
}

/**
 * The next entry's fields: a borderless "what are you working on" field with the play button,
 * then grouped project, task, tag and billable rows, and a reset row when fields were kept after
 * the last stop.
 */
@Composable
internal fun StartTimerForm(
    uiState: TrackingUiState,
    onDescriptionChange: (String) -> Unit,
    onProjectChange: (String?) -> Unit,
    onTaskChange: (String?) -> Unit,
    onResetEntryFields: () -> Unit = {},
    autoClearEntryFieldsAfterStop: Boolean = true,
    onTagsChange: (List<String>) -> Unit,
    onBillableChange: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val enabled = !uiState.isMutating
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Dimens.Space4, end = Dimens.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                DescriptionFieldWithSuggestions(
                    description = uiState.editingDescription,
                    onDescriptionChange = onDescriptionChange,
                    timeEntries = uiState.timeEntries,
                    projects = uiState.projects,
                    tags = uiState.tags,
                    enabled = enabled,
                    borderless = true,
                    onEntryCopied = { entry ->
                        onDescriptionChange(entry.description ?: "")
                        onProjectChange(entry.projectId)
                        onTaskChange(entry.taskId)
                        onTagsChange(entry.tags.map { it.id })
                        onBillableChange(entry.billable)
                    },
                )
            }
            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStart()
                },
                enabled = enabled,
                modifier = Modifier.size(Dimens.MinTouchTarget).testTag(TrackingTestTags.START_BUTTON),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.start))
            }
        }
        GroupedDivider()
        ProjectTaskDropdown(
            selectedProjectId = uiState.editingProjectId,
            selectedTaskId = uiState.editingTaskId,
            projects = uiState.projects,
            tasks = uiState.tasks,
            onSelectionChanged = { projectId, taskId ->
                onProjectChange(projectId)
                onTaskChange(taskId)
            },
            enabled = enabled,
            style = SelectorStyle.Grouped,
        )
        GroupedDivider(inset = Dimens.SettingsIconInset)
        TagsSelector(
            selectedTagIds = uiState.editingTags,
            availableTags = uiState.tags,
            onTagsChanged = onTagsChange,
            enabled = enabled,
            style = SelectorStyle.Grouped,
        )
        GroupedDivider(inset = Dimens.SettingsIconInset)
        GroupedSwitchRow(
            title = stringResource(R.string.billable),
            leadingIcon = Icons.Outlined.AttachMoney,
            checked = uiState.editingBillable,
            onCheckedChange = onBillableChange,
            enabled = enabled,
        )
        val hasRetainedFields = uiState.editingDescription.isNotEmpty() ||
            uiState.editingProjectId != null ||
            uiState.editingTaskId != null
        if (!autoClearEntryFieldsAfterStop && hasRetainedFields) {
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(
                title = stringResource(R.string.reset_entry_fields),
                leadingIcon = Icons.Outlined.RestartAlt,
                onClick = if (enabled) onResetEntryFields else null,
                showChevron = false,
                modifier = Modifier.testTag(TrackingTestTags.RESET_FIELDS_BUTTON),
            )
        }
    }
}

/** "Project: Task" in the project's colour, kept legible on [background]; nothing without a project. */
@Composable
internal fun ProjectTaskLine(project: Project?, task: Task?, background: Color, modifier: Modifier = Modifier) {
    if (project == null) return
    val onSurface = MaterialTheme.colorScheme.onSurface
    val projectColor = remember(project.color, background, onSurface) {
        runCatching { Color(project.color.toColorInt()) }
            .map { it.readableOn(background = background, towards = onSurface) }
            .getOrDefault(onSurface)
    }
    Text(
        text = if (task == null) project.name else stringResource(R.string.history_project_task, project.name, task.name),
        style = MaterialTheme.typography.bodySmall,
        color = projectColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
