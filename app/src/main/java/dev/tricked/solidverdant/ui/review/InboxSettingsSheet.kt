/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.domain.inbox.InboxSettingsDataStore.InboxCheck
import dev.tricked.solidverdant.ui.components.AppSheet
import dev.tricked.solidverdant.ui.components.AppTimePickerDialog
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.GroupedSwitchRow
import dev.tricked.solidverdant.ui.components.SegmentedControl
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.theme.Dimens
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The local Time Inbox configuration (gap analysis #17) in the app's sheet layout: how far back to
 * review as a segmented control, working days and hours as value rows that open pickers, the
 * thresholds as steppers, and each check as a switch. Every change is written straight through the
 * ViewModel, which persists it; the sheet reads the effective values back from [state].
 */
@Composable
@Suppress("LongMethod")
fun InboxSettingsSheet(state: InboxUiState, viewModel: InboxViewModel, onDismiss: () -> Unit) {
    val config = state.config
    val locale = appLocale()
    val timeFormatter = rememberTimeOfDayFormatter()
    var editingWindow by remember { mutableStateOf<WorkField?>(null) }
    var showWorkDays by remember { mutableStateOf(false) }
    var windowInvalid by remember { mutableStateOf(false) }

    AppSheet(
        title = stringResource(R.string.inbox_settings_title),
        onDismiss = onDismiss,
        onDone = onDismiss,
        doneTestTag = ReviewTestTags.INBOX_SETTINGS_DONE,
        // The pickers open in their own windows; their focus change must not close the sheet.
        dismissible = { editingWindow == null && !showWorkDays },
    ) {
        GroupedSection(
            header = stringResource(R.string.inbox_settings_horizon),
            footer = horizonFooter(state.horizonStartMs, state.zone, locale),
        ) {
            SegmentedControl<HorizonOption?>(
                options = HorizonOption.entries,
                selected = state.horizonOption,
                onSelect = { option -> option?.let(viewModel::chooseHorizon) },
                label = { option -> option?.let { horizonShortLabel(it) }.orEmpty() },
                modifier = Modifier.padding(horizontal = Dimens.Space8, vertical = Dimens.Space4),
                optionTestTag = { option -> option?.let(ReviewTestTags::horizonSegment) },
            )
        }

        GroupedSection(
            header = stringResource(R.string.inbox_settings_working_hours),
            footer = if (windowInvalid) stringResource(R.string.sweep_inbox_work_window_invalid) else null,
        ) {
            GroupedRow(
                title = stringResource(R.string.inbox_settings_work_days),
                leadingIcon = Icons.Outlined.DateRange,
                value = workDaysSummary(
                    days = config.workDays,
                    firstDayOfWeek = state.firstDayOfWeek,
                    locale = locale,
                    none = stringResource(R.string.sweep_inbox_work_days_none),
                    every = stringResource(R.string.sweep_inbox_work_days_every),
                    rangeFormat = stringResource(R.string.sweep_inbox_work_days_range),
                ),
                onClick = { showWorkDays = true },
                modifier = Modifier.testTag(ReviewTestTags.INBOX_WORK_DAYS),
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedTimeRow(
                label = stringResource(R.string.inbox_settings_work_start),
                icon = Icons.Outlined.WbSunny,
                time = formatMinuteOfDay(config.workStartMinute, timeFormatter),
                onClick = { editingWindow = WorkField.START },
                testTag = ReviewTestTags.INBOX_WORK_START,
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedTimeRow(
                label = stringResource(R.string.inbox_settings_work_end),
                icon = Icons.Outlined.NightsStay,
                time = formatMinuteOfDay(config.workEndMinute, timeFormatter),
                onClick = { editingWindow = WorkField.END },
                testTag = ReviewTestTags.INBOX_WORK_END,
            )
        }

        GroupedSection(header = stringResource(R.string.sweep_inbox_thresholds)) {
            GroupedStepperRow(
                title = stringResource(R.string.inbox_settings_min_gap),
                value = stringResource(R.string.inbox_minutes_value, config.minGapMinutes),
                leadingIcon = Icons.Outlined.HourglassEmpty,
                onDecrease = { viewModel.setMinGapMinutes(steppedMinGap(config.minGapMinutes, up = false)) },
                onIncrease = { viewModel.setMinGapMinutes(steppedMinGap(config.minGapMinutes, up = true)) },
                decreaseEnabled = config.minGapMinutes > MIN_GAP_MINUTES,
                increaseEnabled = config.minGapMinutes < MAX_GAP_MINUTES,
                testTag = ReviewTestTags.INBOX_MIN_GAP,
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedStepperRow(
                title = stringResource(R.string.inbox_settings_max_duration),
                value = stringResource(R.string.inbox_hours_value, config.maxDurationHours),
                leadingIcon = Icons.Outlined.Timer,
                onDecrease = { viewModel.setMaxDurationHours(steppedDuration(config.maxDurationHours, up = false)) },
                onIncrease = { viewModel.setMaxDurationHours(steppedDuration(config.maxDurationHours, up = true)) },
                decreaseEnabled = config.maxDurationHours > MIN_DURATION_HOURS,
                increaseEnabled = config.maxDurationHours < MAX_DURATION_HOURS,
                testTag = ReviewTestTags.INBOX_MAX_DURATION,
            )
        }

        val checks = listOf(
            CheckRow(InboxCheck.GAPS, R.string.inbox_settings_check_gaps, Icons.Outlined.HourglassEmpty, config.checkGaps),
            CheckRow(InboxCheck.OVERLAPS, R.string.inbox_settings_check_overlaps, Icons.Outlined.Layers, config.checkOverlaps),
            CheckRow(
                InboxCheck.MISSING_PROJECT,
                R.string.inbox_settings_check_missing_project,
                Icons.Outlined.FolderOff,
                config.checkMissingProject,
            ),
            CheckRow(
                InboxCheck.MISSING_TASK,
                R.string.inbox_settings_check_missing_task,
                Icons.AutoMirrored.Outlined.List,
                config.checkMissingTask,
            ),
            CheckRow(
                InboxCheck.MISSING_DESCRIPTION,
                R.string.inbox_settings_check_missing_description,
                Icons.AutoMirrored.Outlined.Notes,
                config.checkMissingDescription,
            ),
            CheckRow(
                InboxCheck.MISSING_TAGS,
                R.string.inbox_settings_check_missing_tags,
                Icons.AutoMirrored.Outlined.Label,
                config.checkMissingTags,
            ),
            CheckRow(InboxCheck.LONG_DURATION, R.string.inbox_settings_check_long, Icons.Outlined.Timer, config.checkLongDuration),
        )
        GroupedSection(header = stringResource(R.string.inbox_settings_checks)) {
            checks.forEachIndexed { index, check ->
                if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedSwitchRow(
                    title = stringResource(check.titleRes),
                    leadingIcon = check.icon,
                    checked = check.checked,
                    onCheckedChange = { viewModel.setCheckEnabled(check.check, it) },
                    modifier = Modifier.testTag(ReviewTestTags.inboxCheck(check.check.name)),
                )
            }
        }
    }

    editingWindow?.let { field ->
        val initial = (if (field == WorkField.START) config.workStartMinute else config.workEndMinute)
            .coerceIn(0, LAST_MINUTE_OF_DAY)
        AppTimePickerDialog(
            title = stringResource(
                if (field == WorkField.START) R.string.sweep_inbox_work_start_title else R.string.sweep_inbox_work_end_title,
            ),
            initialHour = initial / MINUTES_PER_HOUR,
            initialMinute = initial % MINUTES_PER_HOUR,
            onDismiss = { editingWindow = null },
            onConfirm = { hour, minute ->
                val picked = hour * MINUTES_PER_HOUR + minute
                val start = if (field == WorkField.START) picked else config.workStartMinute
                val end = if (field == WorkField.END) picked else config.workEndMinute
                // Say why nothing changed instead of silently keeping an empty working day.
                windowInvalid = end <= start
                if (!windowInvalid) viewModel.setWorkWindow(start, end)
                editingWindow = null
            },
            confirmTestTag = ReviewTestTags.INBOX_TIME_CONFIRM,
        )
    }

    if (showWorkDays) {
        WorkDaysPickerDialog(
            selected = config.workDays,
            firstDayOfWeek = state.firstDayOfWeek,
            onChange = viewModel::setWorkDays,
            onDismiss = { showWorkDays = false },
        )
    }
}

private enum class WorkField { START, END }

private data class CheckRow(val check: InboxCheck, val titleRes: Int, val icon: ImageVector, val checked: Boolean)

@Composable
private fun horizonShortLabel(option: HorizonOption): String = stringResource(
    when (option) {
        HorizonOption.TODAY -> R.string.sweep_inbox_horizon_short_today
        HorizonOption.THIS_WEEK -> R.string.sweep_inbox_horizon_short_week
        HorizonOption.LAST_30_DAYS -> R.string.sweep_inbox_horizon_short_30_days
        HorizonOption.EVERYTHING -> R.string.sweep_inbox_horizon_short_all
    },
)

/** "Since 3 Jul" under the horizon choices, so a bound that no longer matches a choice is still visible. */
@Composable
private fun horizonFooter(horizonStartMs: Long?, zone: java.time.ZoneId, locale: java.util.Locale): String? {
    if (horizonStartMs == null) return null
    val date = remember(horizonStartMs, zone, locale) {
        Instant.ofEpochMilli(horizonStartMs).atZone(zone)
            .format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
    }
    return stringResource(R.string.inbox_horizon_chip_since, date)
}

private fun steppedMinGap(value: Int, up: Boolean) = steppedValue(value, MIN_GAP_STEP_MINUTES, MIN_GAP_MINUTES, MAX_GAP_MINUTES, up)

private fun steppedDuration(value: Int, up: Boolean) = steppedValue(value, DURATION_STEP_HOURS, MIN_DURATION_HOURS, MAX_DURATION_HOURS, up)

private const val MIN_GAP_STEP_MINUTES = 5
private const val MIN_GAP_MINUTES = 1

// The same bound the ViewModel accepts: a gap as long as a whole day.
private const val MAX_GAP_MINUTES = 24 * 60
private const val DURATION_STEP_HOURS = 1
private const val MIN_DURATION_HOURS = 1
private const val MAX_DURATION_HOURS = 24
private const val MINUTES_PER_HOUR = 60
private const val LAST_MINUTE_OF_DAY = 1439
