/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.ValueChip
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.tabular
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * A time of day in the user's locale and the device's 12- or 24-hour setting, the same clock the
 * [dev.tricked.solidverdant.ui.components.AppTimePickerDialog] shows.
 */
@Composable
internal fun rememberTimeOfDayFormatter(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = appLocale()
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"), locale)
    }
}

/** Minutes since midnight as a clock time; the end-of-day bound 1440 reads as 24:00. */
internal fun formatMinuteOfDay(minuteOfDay: Int, formatter: DateTimeFormatter): String = if (minuteOfDay >= MINUTES_PER_DAY) {
    END_OF_DAY_LABEL
} else {
    LocalTime.of(minuteOfDay.coerceAtLeast(0) / MINUTES_PER_HOUR, minuteOfDay.coerceAtLeast(0) % MINUTES_PER_HOUR).format(formatter)
}

/**
 * A grouped row with a label and the time in a grey value chip that opens a time picker, like
 * the Start and End rows of the entry form.
 */
@Composable
internal fun GroupedTimeRow(label: String, icon: ImageVector, time: String, onClick: () -> Unit, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(horizontal = Dimens.Space16, vertical = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.IconSmall))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        ValueChip(text = time, onClick = onClick, testTag = testTag, description = label)
    }
}

/**
 * A grouped row holding a number: the label with the current value underneath, then -/+ buttons,
 * laid out like the entry form's duration row.
 */
@Composable
@Suppress("LongParameterList")
internal fun GroupedStepperRow(
    title: String,
    value: String,
    leadingIcon: ImageVector,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(start = Dimens.Space16, end = Dimens.Space8, top = Dimens.Space8, bottom = Dimens.Space8)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.IconSmall))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
            FilledTonalIconButton(
                onClick = onDecrease,
                enabled = decreaseEnabled,
                modifier = Modifier.size(Dimens.MinTouchTarget).testTag("${testTag}_decrease"),
            ) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.sweep_stepper_decrease, title))
            }
            FilledTonalIconButton(
                onClick = onIncrease,
                enabled = increaseEnabled,
                modifier = Modifier.size(Dimens.MinTouchTarget).testTag("${testTag}_increase"),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sweep_stepper_increase, title))
            }
        }
    }
}

/**
 * The next value for a stepper that moves in [step]s: it snaps to the step grid, so 1 goes up to
 * 5 rather than 6, and stays within [min]..[max].
 */
internal fun steppedValue(value: Int, step: Int, min: Int, max: Int, up: Boolean): Int {
    val next = if (up) (value / step + 1) * step else ((value - 1) / step) * step
    return next.coerceIn(min, max)
}

/**
 * Working days as a small multi-choice dialog in week order: each day toggles in place with a tick
 * on the right, like the option pickers, and Done closes it.
 */
@Composable
internal fun WorkDaysPickerDialog(
    selected: Set<DayOfWeek>,
    firstDayOfWeek: DayOfWeek,
    onChange: (Set<DayOfWeek>) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = appLocale()
    val days = remember(firstDayOfWeek) { weekFrom(firstDayOfWeek) }
    // Toggle locally so quick taps build on each other before the stored set round-trips.
    var current by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inbox_settings_work_days)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                days.forEach { day ->
                    val isSelected = day in current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.MinTouchTarget)
                            .testTag(ReviewTestTags.workDay(day))
                            .toggleable(
                                value = isSelected,
                                role = Role.Checkbox,
                                onValueChange = { on ->
                                    current = if (on) current + day else current - day
                                    onChange(current)
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = day.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(Dimens.IconSmall),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.testTag(ReviewTestTags.INBOX_WORK_DAYS_DONE)) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

/** The seven days starting at [first], the account's week start. */
internal fun weekFrom(first: DayOfWeek): List<DayOfWeek> = (0L until DAYS_PER_WEEK).map { first.plus(it) }

/**
 * Short summary of the working days in week order: "Mon–Fri" for a run of three or more days,
 * otherwise the day names, [every] for all seven and [none] for none.
 */
internal fun workDaysSummary(
    days: Set<DayOfWeek>,
    firstDayOfWeek: DayOfWeek,
    locale: Locale,
    none: String,
    every: String,
    rangeFormat: String,
): String {
    if (days.isEmpty()) return none
    if (days.size == DAYS_PER_WEEK.toInt()) return every
    val week = weekFrom(firstDayOfWeek)
    val chosen = week.filter { it in days }
    fun name(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, locale)
    val first = week.indexOf(chosen.first())
    val last = week.indexOf(chosen.last())
    val contiguous = last - first + 1 == chosen.size
    return if (contiguous && chosen.size >= MIN_RANGE_DAYS) {
        rangeFormat.format(name(chosen.first()), name(chosen.last()))
    } else {
        chosen.joinToString(", ") { name(it) }
    }
}

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1440
private const val DAYS_PER_WEEK = 7L
private const val MIN_RANGE_DAYS = 3
private const val END_OF_DAY_LABEL = "24:00"
