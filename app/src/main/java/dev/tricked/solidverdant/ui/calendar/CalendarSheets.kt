/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import dev.tricked.solidverdant.domain.time.isCompletedTimeEntry
import dev.tricked.solidverdant.domain.time.isRunningTimeEntry
import dev.tricked.solidverdant.domain.time.parseTimeEntryInstant
import dev.tricked.solidverdant.ui.components.AppSheet
import dev.tricked.solidverdant.ui.components.DestructiveActionRow
import dev.tricked.solidverdant.ui.components.EntryDatePickerDialog
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.OptionPickerDialog
import dev.tricked.solidverdant.ui.components.SegmentedControl
import dev.tricked.solidverdant.ui.components.SyncChip
import dev.tricked.solidverdant.ui.components.ValueChip
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.tabular
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What a tap on an entry offers, in the app's sheet layout: a short summary under the title, the
 * sync state with its recovery actions when the entry has an unsent change, then Continue, Edit
 * (or Edit start time and Stop while it runs), Duplicate and Split, and Delete on its own.
 */
@Composable
internal fun CalendarEntryActionsSheet(
    entry: TimeEntry,
    project: Project?,
    task: Task?,
    client: Client?,
    syncOperation: TimeEntryRepository.SyncOperation?,
    onDismiss: () -> Unit,
    onContinue: (() -> Unit)?,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onSplit: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onRetrySync: () -> Unit,
    onDiscardFailedSync: () -> Unit,
    onOpenSyncCenter: () -> Unit,
) {
    val running = isRunningTimeEntry(entry)
    val unsynced = entry.id.startsWith("local-")
    val metadata = remember(entry, project?.name, task?.name, client?.name) {
        calendarEntryMetadata(entry, project?.name, task?.name, client?.name)
    }
    AppSheet(
        title = stringResource(R.string.calendar_entry_actions),
        onDismiss = onDismiss,
        modifier = Modifier.testTag(CalendarTestTags.ENTRY_ACTIONS),
    ) {
        CalendarEntrySummary(metadata)
        syncOperation?.let { operation ->
            CalendarEntrySyncSection(
                status = operation.status,
                onRetrySync = onRetrySync,
                onDiscardFailedSync = onDiscardFailedSync,
                onOpenSyncCenter = onOpenSyncCenter,
            )
        }
        // The entry sheet: Continue first, then edit, duplicate and split.
        val actions = buildList {
            onContinue?.let { add(EntryAction(R.string.entry_continue, Icons.Default.PlayArrow, CalendarTestTags.CONTINUE_ENTRY, it)) }
            if (running) {
                add(EntryAction(R.string.edit_start_time, Icons.Default.Edit, CalendarTestTags.EDIT_START_TIME, onEdit))
                add(EntryAction(R.string.stop_tracking, Icons.Default.Stop, CalendarTestTags.STOP_ENTRY, onStop))
            } else {
                add(EntryAction(R.string.edit, Icons.Default.Edit, CalendarTestTags.EDIT_ENTRY, onEdit))
            }
            if (!running && isCompletedTimeEntry(entry)) {
                add(EntryAction(R.string.duplicate_entry, Icons.Outlined.ContentCopy, CalendarTestTags.DUPLICATE_ENTRY, onDuplicate))
                add(EntryAction(R.string.split_entry, Icons.AutoMirrored.Outlined.CallSplit, CalendarTestTags.SPLIT_ENTRY, onSplit))
            }
        }
        GroupedSection {
            actions.forEachIndexed { index, action ->
                if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(action.label),
                    leadingIcon = action.icon,
                    showChevron = false,
                    onClick = action.onClick,
                    modifier = Modifier.testTag(action.testTag),
                )
            }
        }
        DestructiveActionRow(
            label = stringResource(if (unsynced) R.string.calendar_action_discard else R.string.delete),
            onClick = onDelete,
            testTag = CalendarTestTags.DELETE_ENTRY,
        )
    }
}

private data class EntryAction(val label: Int, val icon: ImageVector, val testTag: String, val onClick: () -> Unit)

/** The entry's description, catalogue context and length, aligned with the sheet title. */
@Composable
private fun CalendarEntrySummary(metadata: CalendarEntryMetadata) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.Space16 + Dimens.Space16, end = Dimens.Space16),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space2),
    ) {
        Text(
            text = metadata.title ?: stringResource(R.string.calendar_entry_untitled),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        metadata.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        metadata.durationSeconds?.let { duration ->
            Text(
                text = "${stringResource(R.string.total_time)}: ${formatDuration(duration)}",
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The entry's unsent change: its state, what can be done about it, and why, as the footer. */
@Composable
private fun CalendarEntrySyncSection(
    status: EntrySyncStatus,
    onRetrySync: () -> Unit,
    onDiscardFailedSync: () -> Unit,
    onOpenSyncCenter: () -> Unit,
) {
    val detail = when (status) {
        EntrySyncStatus.PENDING, EntrySyncStatus.RETRYING -> R.string.calendar_sync_pending_detail
        EntrySyncStatus.FAILED -> R.string.calendar_sync_failed_detail
        EntrySyncStatus.CONFLICT -> R.string.calendar_sync_conflict_detail
        EntrySyncStatus.SYNCED -> R.string.calendar_sync_synced_detail
    }
    GroupedSection(
        footer = stringResource(detail),
        modifier = Modifier.testTag(CalendarTestTags.SYNC_STATUS),
    ) {
        GroupedRow(title = stringResource(R.string.calendar_sync_status), trailing = { SyncChip(status = status) })
        when (status) {
            // A queued or backing-off change can be sent again now instead of waiting.
            EntrySyncStatus.PENDING, EntrySyncStatus.RETRYING -> {
                GroupedDivider()
                SyncActionRow(R.string.sync_retry, Icons.Default.Refresh, CalendarTestTags.SYNC_RETRY, onRetrySync)
            }
            EntrySyncStatus.FAILED -> {
                GroupedDivider()
                SyncActionRow(R.string.sync_retry, Icons.Default.Refresh, CalendarTestTags.SYNC_RETRY, onRetrySync)
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(R.string.sync_discard),
                    leadingIcon = Icons.AutoMirrored.Outlined.Undo,
                    destructive = true,
                    onClick = onDiscardFailedSync,
                    modifier = Modifier.testTag(CalendarTestTags.SYNC_DISCARD),
                )
            }
            EntrySyncStatus.CONFLICT -> {
                GroupedDivider()
                // Opens another screen, so it keeps the chevron.
                GroupedRow(title = stringResource(R.string.sync_center_title), leadingIcon = Icons.Default.Sync, onClick = onOpenSyncCenter)
            }
            EntrySyncStatus.SYNCED -> Unit
        }
    }
}

@Composable
private fun SyncActionRow(label: Int, icon: ImageVector, testTag: String, onClick: () -> Unit) {
    GroupedRow(
        title = stringResource(label),
        leadingIcon = icon,
        showChevron = false,
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

/**
 * The running timer above the grid, as a grouped cell like the Time Tracker's timer bar: what is
 * running, the live elapsed time, edit start time, and the red stop.
 */
@Composable
internal fun CalendarRunningTimerCard(entry: TimeEntry, elapsedSeconds: StateFlow<Long>?, onEdit: () -> Unit, onStop: () -> Unit) {
    val elapsedState = elapsedSeconds?.collectAsStateWithLifecycle()
    val liveElapsedSeconds = elapsedState?.value ?: run {
        val now = rememberCalendarNow(secondPrecision = true)
        entryDurationSeconds(entry, now)
    }
    val title = entry.description?.trim()?.takeIf(String::isNotEmpty)
        ?: stringResource(R.string.calendar_entry_untitled)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.Space16)
            .padding(bottom = Dimens.Space8)
            .testTag(CalendarTestTags.RUNNING_TIMER),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .padding(start = Dimens.Space16, end = Dimens.Space8, top = Dimens.Space8, bottom = Dimens.Space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.calendar_timer_running),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatRunningDuration(liveElapsedSeconds),
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onEdit, modifier = Modifier.testTag(CalendarTestTags.RUNNING_TIMER_EDIT)) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit_start_time),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(
                onClick = onStop,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.size(Dimens.MinTouchTarget).testTag(CalendarTestTags.RUNNING_TIMER_STOP),
            ) {
                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop))
            }
        }
    }
}

/**
 * Choose where to split an entry: its date as a value chip opening the app's date picker, and the
 * time on the device's 12- or 24-hour clock, like the app's time picker. The split must fall
 * strictly inside the entry; it is written in the app's UTC timestamp shape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarSplitDialog(entry: TimeEntry, zone: ZoneId, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val locale = appLocale()
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val originalStart = remember(entry.id, entry.start, zone) { parseTimeEntryInstant(entry.start)?.atZone(zone) }
    val originalEnd = remember(entry.id, entry.end, zone) { entry.end?.let(::parseTimeEntryInstant)?.atZone(zone) }
    if (originalStart == null || originalEnd == null || !originalEnd.isAfter(originalStart)) {
        LaunchedEffect(entry.id) { onDismiss() }
        return
    }

    val midpoint = remember(originalStart, originalEnd) {
        originalStart.plusSeconds(Duration.between(originalStart, originalEnd).seconds / 2)
    }
    var selectedDate by remember(entry.id, zone) { mutableStateOf(midpoint.toLocalDate()) }
    val timeState = rememberTimePickerState(initialHour = midpoint.hour, initialMinute = midpoint.minute, is24Hour = is24Hour)
    var invalid by remember(entry.id) { mutableStateOf(false) }
    var showDatePicker by remember(entry.id) { mutableStateOf(false) }

    if (showDatePicker) {
        EntryDatePickerDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                selectedDate = date
                invalid = false
                showDatePicker = false
            },
        )
        return
    }

    val dateLabel = stringResource(R.string.calendar_split_date)
    val dateText = remember(selectedDate, locale) {
        selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.split_entry_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
            ) {
                Text(
                    text = stringResource(R.string.calendar_split_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(dateLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    ValueChip(
                        text = dateText,
                        onClick = { showDatePicker = true },
                        testTag = CalendarTestTags.SPLIT_DATE,
                        description = dateLabel,
                    )
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timeState, modifier = Modifier.testTag(CalendarTestTags.SPLIT_PICKER))
                }
                if (invalid) {
                    Text(
                        text = stringResource(R.string.calendar_split_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val at = calendarSplitTimestamp(selectedDate, timeState.hour, timeState.minute, zone, originalStart, originalEnd)
                    if (at != null) onConfirm(at) else invalid = true
                },
                modifier = Modifier.testTag(CalendarTestTags.SPLIT_CONFIRM),
            ) {
                Text(stringResource(R.string.split_entry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(CalendarTestTags.SPLIT_CANCEL)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private enum class CalendarHourSetting { START, END }

/**
 * Calendar settings in the app's sheet layout: grid size and snap interval as segmented controls,
 * and the visible hours as rows that open an hour list.
 */
@Composable
internal fun CalendarSettingsSheet(
    settings: CalendarGridSettings,
    onSettingsChanged: (((CalendarGridSettings) -> CalendarGridSettings) -> Unit),
    onDismiss: () -> Unit,
) {
    var pickingHour by remember { mutableStateOf<CalendarHourSetting?>(null) }
    AppSheet(
        title = stringResource(R.string.calendar_settings),
        onDismiss = onDismiss,
        onDone = onDismiss,
        modifier = Modifier.testTag(CalendarTestTags.SETTINGS_SHEET),
    ) {
        GroupedSection(header = stringResource(R.string.calendar_settings_density)) {
            SegmentedControl(
                options = CalendarGridDensity.entries,
                selected = settings.density,
                onSelect = { density -> onSettingsChanged { current -> current.copy(density = density) } },
                label = { density -> stringResource(densityLabel(density)) },
                optionTestTag = ::densityTestTag,
                modifier = Modifier.padding(horizontal = Dimens.Space8, vertical = Dimens.Space4),
            )
        }
        GroupedSection(header = stringResource(R.string.calendar_settings_snap)) {
            SegmentedControl(
                options = CalendarGridSettings.SNAP_MINUTES,
                selected = settings.snapMinutes,
                onSelect = { minutes -> onSettingsChanged { current -> current.copy(snapMinutes = minutes) } },
                label = { minutes -> stringResource(R.string.calendar_settings_minutes, minutes) },
                optionTestTag = { minutes -> CalendarTestTags.settingsOption(CalendarTestTags.SETTINGS_SNAP, minutes.toString()) },
                modifier = Modifier
                    .testTag(CalendarTestTags.SETTINGS_SNAP)
                    .padding(horizontal = Dimens.Space8, vertical = Dimens.Space4),
            )
        }
        GroupedSection(header = stringResource(R.string.calendar_settings_visible_hours)) {
            CalendarSettingValueRow(
                label = stringResource(R.string.calendar_settings_start),
                value = stringResource(R.string.calendar_settings_hour, settings.startHour),
                controlTestTag = CalendarTestTags.SETTINGS_START,
                onClick = { pickingHour = CalendarHourSetting.START },
            )
            GroupedDivider()
            CalendarSettingValueRow(
                label = stringResource(R.string.calendar_settings_end),
                value = stringResource(R.string.calendar_settings_hour, settings.endHour),
                controlTestTag = CalendarTestTags.SETTINGS_END,
                onClick = { pickingHour = CalendarHourSetting.END },
            )
        }
    }

    when (pickingHour) {
        CalendarHourSetting.START -> OptionPickerDialog(
            title = stringResource(R.string.calendar_settings_start_title),
            options = (CalendarGridSettings.MIN_START_HOUR until settings.endHour).toList(),
            selected = settings.startHour,
            label = { hour -> stringResource(R.string.calendar_settings_hour, hour) },
            onSelect = { hour -> onSettingsChanged { current -> current.copy(startHour = hour.coerceAtMost(current.endHour - 1)) } },
            onDismiss = { pickingHour = null },
            optionTag = { hour -> CalendarTestTags.settingsOption(CalendarTestTags.SETTINGS_START, hour.toString()) },
        )
        CalendarHourSetting.END -> OptionPickerDialog(
            title = stringResource(R.string.calendar_settings_end_title),
            options = ((settings.startHour + 1)..CalendarGridSettings.MAX_END_HOUR).toList(),
            selected = settings.endHour,
            label = { hour -> stringResource(R.string.calendar_settings_hour, hour) },
            onSelect = { hour -> onSettingsChanged { current -> current.copy(endHour = hour.coerceAtLeast(current.startHour + 1)) } },
            onDismiss = { pickingHour = null },
            optionTag = { hour -> CalendarTestTags.settingsOption(CalendarTestTags.SETTINGS_END, hour.toString()) },
        )
        null -> Unit
    }
}

/** A grouped row showing a setting's current value, which automation reads by its own tag. */
@Composable
private fun CalendarSettingValueRow(label: String, value: String, controlTestTag: String, onClick: () -> Unit) {
    GroupedRow(
        title = label,
        onClick = onClick,
        modifier = Modifier.testTag(controlTestTag),
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(max = Dimens.GroupedValueMaxWidth)
                    .testTag(CalendarTestTags.settingsValue(controlTestTag)),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(Dimens.IconSmall),
            )
        },
    )
}

private fun densityLabel(density: CalendarGridDensity): Int = when (density) {
    CalendarGridDensity.COMPACT -> R.string.calendar_settings_density_compact
    CalendarGridDensity.COMFORTABLE -> R.string.calendar_settings_density_comfortable
    CalendarGridDensity.SPACIOUS -> R.string.calendar_settings_density_spacious
}

private fun densityTestTag(density: CalendarGridDensity): String = when (density) {
    CalendarGridDensity.COMPACT -> CalendarTestTags.SETTINGS_DENSITY_COMPACT
    CalendarGridDensity.COMFORTABLE -> CalendarTestTags.SETTINGS_DENSITY_COMFORTABLE
    CalendarGridDensity.SPACIOUS -> CalendarTestTags.SETTINGS_DENSITY_SPACIOUS
}

/** The device-calendar overlay controls in the app's sheet layout. */
@Composable
internal fun CalendarOverlaySheet(
    state: CalendarUiState,
    showRationale: Boolean,
    onDismiss: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onToggleCalendar: (String) -> Unit,
    onRetry: () -> Unit,
) {
    AppSheet(
        title = stringResource(R.string.calendar_overlay_title),
        onDismiss = onDismiss,
        onDone = onDismiss,
    ) {
        CalendarOverlayControls(
            state = state,
            showRationale = showRationale,
            onToggleOverlay = onToggleOverlay,
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
            onToggleCalendar = onToggleCalendar,
            onRetry = onRetry,
        )
    }
}
