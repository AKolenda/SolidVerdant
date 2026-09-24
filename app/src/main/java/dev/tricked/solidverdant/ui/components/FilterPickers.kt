/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** One choice in a filter picker. */
@Immutable
data class FilterOption(val id: String, val name: String)

internal object FilterPickerTestTags {
    const val DATE_RANGE_APPLY = "filter_date_range_apply"
    const val DATE_RANGE_CLOSE = "filter_date_range_close"
    const val CLEAR_SELECTION = "filter_picker_clear"
    fun option(id: String) = "filter_picker_option_$id"
}

/**
 * A full-width filter row in a [GroupedSection]: icon, what it filters, the current choice, and a
 * chevron. It opens a searchable picker, like the project and task rows of the entry form.
 */
@Composable
fun FilterRow(label: String, icon: ImageVector, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GroupedRow(title = label, leadingIcon = icon, value = value, onClick = onClick, modifier = modifier)
}

/** A multi-select row's value: "All" when nothing is chosen, the name of one, or "N selected". */
@Composable
fun multiSelectSummary(selected: Set<String>, options: List<FilterOption>): String = when (selected.size) {
    0 -> stringResource(R.string.filter_all)
    1 -> options.firstOrNull { it.id in selected }?.name ?: pluralStringResource(R.plurals.filter_selected_count, 1, 1)
    else -> pluralStringResource(R.plurals.filter_selected_count, selected.size, selected.size)
}

/**
 * Searchable, lazily listed single choice for a filter, with "All" first. Large catalogues stay
 * fast because only the visible rows are composed.
 */
@Composable
fun SingleSelectFilterPicker(
    title: String,
    options: List<FilterOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    searchTestTag: String = "filter_picker_search",
    optionTestTag: (String) -> String = FilterPickerTestTags::option,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(options, query) { filterOptions(options, query) }
    PickerDialog(
        title = title,
        searchPlaceholder = stringResource(R.string.search_items, title),
        searchQuery = query,
        onSearchQueryChange = { query = it },
        onClose = onDismiss,
        listTestTag = "filter_picker_list",
        searchTestTag = searchTestTag,
    ) {
        if (query.isBlank()) {
            item(key = "all") {
                PickerItem(
                    text = stringResource(R.string.filter_all),
                    selected = selectedId == null,
                    onClick = {
                        onSelect(null)
                        onDismiss()
                    },
                )
            }
        }
        items(filtered, key = { it.id }) { option ->
            PickerItem(
                text = option.name,
                selected = option.id == selectedId,
                onClick = {
                    onSelect(option.id)
                    onDismiss()
                },
                modifier = Modifier.testTag(optionTestTag(option.id)),
            )
        }
        if (filtered.isEmpty()) item(key = "empty") { PickerEmptyText(query) }
    }
}

/**
 * Searchable, lazily listed multi-selection for a filter. Choices toggle in place so several can
 * be picked before closing; All, first, clears them.
 */
@Composable
fun MultiSelectFilterPicker(
    title: String,
    options: List<FilterOption>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    searchTestTag: String = "filter_picker_search",
    optionTestTag: (String) -> String = FilterPickerTestTags::option,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(options, query) { filterOptions(options, query) }
    PickerDialog(
        title = title,
        searchPlaceholder = stringResource(R.string.search_items, title),
        searchQuery = query,
        onSearchQueryChange = { query = it },
        onClose = onDismiss,
        listTestTag = "filter_picker_list",
        searchTestTag = searchTestTag,
    ) {
        // Always first, so ticking an option never shifts the list under the finger.
        if (query.isBlank()) {
            item(key = "all") {
                PickerItem(
                    text = stringResource(R.string.filter_all),
                    selected = selected.isEmpty(),
                    onClick = { onChange(emptySet()) },
                    modifier = Modifier.testTag(FilterPickerTestTags.CLEAR_SELECTION),
                )
            }
        }
        items(filtered, key = { it.id }) { option ->
            val isSelected = option.id in selected
            PickerItem(
                text = option.name,
                selected = isSelected,
                onClick = { onChange(if (isSelected) selected - option.id else selected + option.id) },
                modifier = Modifier.testTag(optionTestTag(option.id)),
            )
        }
        if (filtered.isEmpty()) item(key = "empty") { PickerEmptyText(query) }
    }
}

@Composable
private fun PickerEmptyText(query: String) {
    Text(
        text = stringResource(if (query.isBlank()) R.string.filter_nothing_to_choose else R.string.no_results_found),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.Space24, vertical = Dimens.Space16),
    )
}

internal fun filterOptions(options: List<FilterOption>, query: String): List<FilterOption> {
    val normalized = query.trim()
    return if (normalized.isEmpty()) options else options.filter { it.name.contains(normalized, ignoreCase = true) }
}

/**
 * Full-screen date-range picker, the Material layout for choosing a range: close, title and Apply
 * across the top, then the scrolling months. Squeezed into a date dialog, the range picker's
 * headline and month list are cut off and misaligned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(
    initialStart: LocalDate?,
    initialEnd: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart?.toUtcMillis(),
        initialSelectedEndDateMillis = initialEnd?.toUtcMillis(),
    )
    val colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = Dimens.Space4, end = Dimens.Space8, top = Dimens.Space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag(FilterPickerTestTags.DATE_RANGE_CLOSE)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                    Text(
                        text = stringResource(R.string.filter_select_dates),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    val start = state.selectedStartDateMillis
                    val end = state.selectedEndDateMillis
                    TextButton(
                        enabled = start != null && end != null,
                        onClick = { if (start != null && end != null) onConfirm(start.toUtcDate(), end.toUtcDate()) },
                        modifier = Modifier.testTag(FilterPickerTestTags.DATE_RANGE_APPLY),
                    ) { Text(stringResource(R.string.apply)) }
                }
                DateRangePicker(
                    state = state,
                    modifier = Modifier.weight(1f),
                    title = null,
                    showModeToggle = false,
                    colors = colors,
                )
            }
        }
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
