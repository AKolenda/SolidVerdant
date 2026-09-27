/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.tabular
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** iOS sheet header: Cancel on the left, the title centred, a pill Save on the right. */
@Composable
fun EntrySheetHeader(title: String, onCancel: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean, cancelTag: String, saveTag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel, modifier = Modifier.testTag(cancelTag)) {
            Text(stringResource(R.string.cancel), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onSave,
            enabled = saveEnabled,
            shape = CircleShape,
            modifier = Modifier.testTag(saveTag),
        ) {
            Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The description as a single borderless grouped cell. */
@Composable
fun EntryDescriptionField(value: String, onValueChange: (String) -> Unit, testTag: String) {
    val label = stringResource(R.string.description)
    GroupedSection {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(label) },
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
                .semantics { contentDescription = label },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/** "Start  [1 Jul 2026] [09:05]" row: a label and tappable date and time chips. */
@Composable
fun EntryTimeRow(
    label: String,
    value: ZonedDateTime,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    dateTag: String,
    timeTag: String,
    dateLabel: String,
    timeLabel: String,
) {
    val locale = appLocale()
    val dateText = remember(value.toLocalDate(), locale) {
        value.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
    val timeText = remember(value.hour, value.minute) { value.format(DateTimeFormatter.ofPattern("HH:mm")) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(horizontal = Dimens.Space16, vertical = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        ValueChip(text = dateText, onClick = onDateClick, testTag = dateTag, description = dateLabel)
        ValueChip(text = timeText, onClick = onTimeClick, testTag = timeTag, description = timeLabel)
    }
}

/** Grey rounded value pill, like iOS inline date/time pickers. */
@Composable
fun ValueChip(text: String, onClick: () -> Unit, testTag: String, description: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .testTag(testTag)
            .semantics { contentDescription = "$description, $text" }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.tabular(),
                modifier = Modifier.padding(horizontal = Dimens.Space12),
            )
        }
    }
}

/**
 * Duration row: label with the clock-style total, then a minutes field between -/+15 minute steps.
 * The field keeps typed minutes as text so partial input can be edited before it is valid.
 */
@Composable
fun EntryDurationRow(
    minutesText: String,
    totalLabel: String,
    isValid: Boolean,
    onMinutesTextChange: (String) -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    fieldTag: String,
) {
    val minutesLabel = stringResource(R.string.minutes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.Space16, end = Dimens.Space8, top = Dimens.Space8, bottom = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.duration), style = MaterialTheme.typography.bodyLarge)
            Text(
                totalLabel,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(onClick = onDecrease, modifier = Modifier.size(Dimens.MinTouchTarget)) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.decrease_15_minutes))
        }
        OutlinedTextField(
            value = minutesText,
            onValueChange = { value -> if (value.all(Char::isDigit)) onMinutesTextChange(value) },
            suffix = { Text(stringResource(R.string.minutes_short)) },
            isError = !isValid,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.tabular(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .width(Dimens.DurationFieldWidth)
                .testTag(fieldTag)
                .semantics { contentDescription = minutesLabel },
        )
        FilledTonalIconButton(onClick = onIncrease, modifier = Modifier.size(Dimens.MinTouchTarget)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.increase_15_minutes))
        }
    }
}

/** Full-width destructive action in its own grouped cell, like "Delete entry" in iOS sheets. */
@Composable
fun DestructiveActionRow(label: String, onClick: () -> Unit, testTag: String) {
    GroupedSection {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget).testTag(testTag),
        ) {
            Text(label, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
