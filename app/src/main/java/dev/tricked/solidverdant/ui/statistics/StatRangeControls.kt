/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.SegmentedControl
import dev.tricked.solidverdant.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal object StatRangeTestTags {
    fun period(period: StatPeriod) = "stats_period_${period.name.lowercase()}"
    fun offset(offset: StatOffset) = "stats_offset_${offset.name.lowercase()}"
}

/**
 * The Dashboard's two segmented controls: the period length, then whether to show the current or
 * previous one. Choosing Custom opens a date-range picker and hides the Current/Previous control;
 * switching period keeps the chosen offset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatRangeControls(range: StatRange, onSelect: (StatRange) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val offset = (range as? StatRange.Preset)?.offset ?: StatOffset.Current
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.Space16)) {
        SegmentedControl(
            options = StatPeriod.entries,
            selected = range.period,
            onSelect = { period ->
                val preset = StatRange.preset(period, offset)
                if (preset == null) showPicker = true else onSelect(preset)
            },
            label = { stringResource(it.labelRes) },
            optionTestTag = StatRangeTestTags::period,
        )
        if (range is StatRange.Preset) {
            SegmentedControl(
                options = StatOffset.entries,
                selected = range.offset,
                onSelect = { StatRange.preset(range.period, it)?.let(onSelect) },
                label = { stringResource(it.labelRes) },
                optionTestTag = StatRangeTestTags::offset,
            )
        }
    }
    if (showPicker) {
        val selected = range as? StatRange.Custom
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = selected?.start?.toUtcMillis(),
            initialSelectedEndDateMillis = selected?.end?.toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis?.toUtcDate()
                        val end = pickerState.selectedEndDateMillis?.toUtcDate()
                        if (start != null && end != null) onSelect(StatRange.Custom(start, end))
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DateRangePicker(state = pickerState) }
    }
}

private val StatPeriod.labelRes: Int
    get() = when (this) {
        StatPeriod.Day -> R.string.stats_period_day
        StatPeriod.Week -> R.string.stats_period_week
        StatPeriod.Month -> R.string.stats_period_month
        StatPeriod.Half -> R.string.stats_period_half
        StatPeriod.Custom -> R.string.stats_custom
    }

private val StatOffset.labelRes: Int
    get() = when (this) {
        StatOffset.Current -> R.string.stats_offset_current
        StatOffset.Previous -> R.string.stats_offset_previous
    }

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
