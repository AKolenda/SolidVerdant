/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import dev.tricked.solidverdant.ui.theme.tabular

/**
 * The timer bar at the top of the Timer tab.
 *
 * Running: one compact row (description, project, elapsed time, pause and a red stop button); tapping
 * the text opens the running entry in the editor. Paused: the same row with resume. Idle: a
 * borderless "what are you working on" field with a play button, then grouped project, task, tag
 * and billable rows for the next entry.
 */
@Composable
internal fun TrackingControls(
    uiState: TrackingUiState,
    elapsedSeconds: Long = 0L,
    onDescriptionChange: (String) -> Unit,
    onProjectChange: (String?) -> Unit,
    onTaskChange: (String?) -> Unit,
    onResetEntryFields: () -> Unit = {},
    autoClearEntryFieldsAfterStop: Boolean = true,
    onTagsChange: (List<String>) -> Unit,
    onBillableChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEditActiveEntry: () -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        when {
            uiState.isTracking || uiState.isPaused -> ActiveTimerRow(
                uiState = uiState,
                elapsedSeconds = elapsedSeconds,
                onStop = onStop,
                onPause = onPause,
                onResume = onResume,
                onEditActiveEntry = onEditActiveEntry,
            )
            else -> IdleTimerComposer(
                uiState = uiState,
                onDescriptionChange = onDescriptionChange,
                onProjectChange = onProjectChange,
                onTaskChange = onTaskChange,
                onResetEntryFields = onResetEntryFields,
                autoClearEntryFieldsAfterStop = autoClearEntryFieldsAfterStop,
                onTagsChange = onTagsChange,
                onBillableChange = onBillableChange,
                onStart = onStart,
            )
        }
    }
}

@Composable
private fun ActiveTimerRow(
    uiState: TrackingUiState,
    elapsedSeconds: Long,
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
    val canEdit = uiState.currentTimeEntry != null && !uiState.isMutating
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.Space4, end = Dimens.Space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.TimerBarHeight)
                .clip(MaterialTheme.shapes.medium)
                .testTag(TrackingTestTags.EDIT_ACTIVE_ENTRY)
                .clickable(
                    enabled = canEdit,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.edit),
                    onClick = onEditActiveEntry,
                )
                .padding(horizontal = Dimens.Space12, vertical = Dimens.Space12),
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
                style = MaterialTheme.typography.titleSmall,
                color = if (uiState.editingDescription.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProjectTaskLine(project = project, task = task)
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

@Composable
private fun IdleTimerComposer(
    uiState: TrackingUiState,
    onDescriptionChange: (String) -> Unit,
    onProjectChange: (String?) -> Unit,
    onTaskChange: (String?) -> Unit,
    onResetEntryFields: () -> Unit,
    autoClearEntryFieldsAfterStop: Boolean,
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

/** "● Project · Task" in secondary text; nothing when no project is set. */
@Composable
internal fun ProjectTaskLine(project: Project?, task: Task?, modifier: Modifier = Modifier) {
    if (project == null) return
    val projectColor = remember(project.color) { runCatching { Color(project.color.toColorInt()) }.getOrNull() }
    val label = remember(project.name, task?.name) {
        if (task == null) project.name else "${project.name} · ${task.name}"
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space4 + Dimens.Space2),
    ) {
        Box(
            Modifier
                .size(Dimens.ProjectDotSmall)
                .clip(CircleShape)
                .background(projectColor ?: MaterialTheme.colorScheme.outline),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
