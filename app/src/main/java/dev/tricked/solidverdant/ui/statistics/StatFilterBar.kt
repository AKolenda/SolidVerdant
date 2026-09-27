/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.AppSheet
import dev.tricked.solidverdant.ui.components.FilterOption
import dev.tricked.solidverdant.ui.components.FilterRow
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.MultiSelectFilterPicker
import dev.tricked.solidverdant.ui.components.SegmentedControl
import dev.tricked.solidverdant.ui.components.multiSelectSummary
import dev.tricked.solidverdant.ui.theme.Dimens

internal object StatisticsFilterTestTags {
    const val OPEN = "stats_filter_open"
    const val CLEAR = "stats_filter_clear"
    const val PROJECT_SEARCH = "stats_project_filter_search"
    const val DONE = "stats_filter_done"
    fun projectOption(id: String) = "stats_project_filter_$id"
    fun section(section: StatFilterSection) = "stats_filter_section_${section.name.lowercase()}"
}

internal enum class StatFilterSection { BILLABLE, TASKS, TAGS, CLIENTS, PROJECTS }

internal val statFilterSectionOrder = listOf(
    StatFilterSection.BILLABLE,
    StatFilterSection.TASKS,
    StatFilterSection.TAGS,
    StatFilterSection.CLIENTS,
    StatFilterSection.PROJECTS,
)

internal fun filterProjectOptions(options: List<Pair<String, String>>, query: String): List<Pair<String, String>> {
    val normalized = query.trim()
    return if (normalized.isEmpty()) {
        options
    } else {
        options.filter { (_, name) ->
            name.contains(normalized, ignoreCase = true)
        }
    }
}

/**
 * The Dashboard's filter row: a grouped card naming the current scope ("All projects", the single
 * filtered project, or "N filters active") that opens the filter sheet, with a one-tap clear while
 * any filter is active.
 */
@Composable
fun StatFilterBar(
    filters: StatFilters,
    catalog: StatCatalog,
    onFiltersChange: (StatFilters) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val singleProjectName = filters.projectIds.singleOrNull()
        ?.takeIf { filters.activeCount == 1 }
        ?.let { id -> catalog.projects.firstOrNull { it.id == id }?.name }
    val title = when {
        !filters.isActive -> stringResource(R.string.stats_all_projects)
        singleProjectName != null -> singleProjectName
        else -> pluralStringResource(R.plurals.stats2_active_filters, filters.activeCount, filters.activeCount)
    }
    // A custom row rather than GroupedRow: the clear button's 48 dp target sits inside the row's
    // own vertical padding, so the row keeps one height whether or not a filter is active.
    GroupedSection(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .clickable(role = Role.Button) { showSheet = true }
                .testTag(StatisticsFilterTestTags.OPEN)
                .padding(start = Dimens.Space16, end = Dimens.Space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Icon(
                Icons.Default.FilterList,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconSmall),
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = Dimens.Space12),
            )
            if (filters.isActive) {
                IconButton(onClick = onClearFilters, modifier = Modifier.testTag(StatisticsFilterTestTags.CLEAR)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.stats2_clear_filters_content_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                }
            }
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(Dimens.IconSmall),
            )
        }
    }
    if (showSheet) {
        StatFilterSheet(
            filters = filters,
            catalog = catalog,
            onFiltersChange = onFiltersChange,
            onReset = onClearFilters,
            onDismiss = { showSheet = false },
        )
    }
}

/**
 * The statistics filters, laid out like the entry form: billable as a segmented choice, then
 * projects, clients, tasks and tags as full-width rows. Each row opens a searchable, lazily listed
 * picker, so a large project catalogue opens as quickly as the entry form's project picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatFilterSheet(
    filters: StatFilters,
    catalog: StatCatalog,
    onFiltersChange: (StatFilters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var openSection by rememberSaveable { mutableStateOf<StatFilterSection?>(null) }
    val projects = remember(catalog.projects) { catalog.projects.map { FilterOption(it.id, it.name) } }
    val clients = remember(catalog.clients) { catalog.clients.map { FilterOption(it.id, it.name) } }
    val tasks = remember(catalog.tasks) { catalog.tasks.map { FilterOption(it.id, it.name) } }
    val tags = remember(catalog.tags) { catalog.tags.map { FilterOption(it.id, it.name) } }
    AppSheet(
        title = stringResource(R.string.stats2_filter_sheet_title),
        onDismiss = onDismiss,
        titleAction = {
            TextButton(onClick = onReset, enabled = filters.isActive) {
                Text(stringResource(R.string.stats2_reset))
            }
        },
        onDone = onDismiss,
        doneTestTag = StatisticsFilterTestTags.DONE,
    ) {
        GroupedSection(
            header = statFilterSectionLabel(StatFilterSection.BILLABLE),
            modifier = Modifier.testTag(StatisticsFilterTestTags.section(StatFilterSection.BILLABLE)),
        ) {
            SegmentedControl(
                options = listOf(BillableFilter.All, BillableFilter.Billable, BillableFilter.NonBillable),
                selected = filters.billable,
                onSelect = { onFiltersChange(filters.copy(billable = it)) },
                label = { billableLabel(it) },
                modifier = Modifier.padding(horizontal = Dimens.Space8, vertical = Dimens.Space4),
            )
        }

        GroupedSection {
            StatFilterRow(StatFilterSection.PROJECTS, Icons.Outlined.Folder, filters.projectIds, projects) { openSection = it }
            GroupedDivider(inset = Dimens.SettingsIconInset)
            StatFilterRow(StatFilterSection.CLIENTS, Icons.Outlined.Business, filters.clientIds, clients) { openSection = it }
            GroupedDivider(inset = Dimens.SettingsIconInset)
            StatFilterRow(StatFilterSection.TASKS, Icons.AutoMirrored.Outlined.List, filters.taskIds, tasks) { openSection = it }
            GroupedDivider(inset = Dimens.SettingsIconInset)
            StatFilterRow(StatFilterSection.TAGS, Icons.AutoMirrored.Outlined.Label, filters.tagIds, tags) { openSection = it }
        }
    }

    val section = openSection ?: return
    val title = statFilterSectionLabel(section)
    val close = { openSection = null }
    when (section) {
        StatFilterSection.PROJECTS -> MultiSelectFilterPicker(
            title = title,
            options = projects,
            selected = filters.projectIds,
            onChange = { onFiltersChange(filters.copy(projectIds = it)) },
            onDismiss = close,
            searchTestTag = StatisticsFilterTestTags.PROJECT_SEARCH,
            optionTestTag = StatisticsFilterTestTags::projectOption,
        )
        StatFilterSection.CLIENTS -> MultiSelectFilterPicker(
            title = title,
            options = clients,
            selected = filters.clientIds,
            onChange = { onFiltersChange(filters.copy(clientIds = it)) },
            onDismiss = close,
        )
        StatFilterSection.TASKS -> MultiSelectFilterPicker(
            title = title,
            options = tasks,
            selected = filters.taskIds,
            onChange = { onFiltersChange(filters.copy(taskIds = it)) },
            onDismiss = close,
        )
        StatFilterSection.TAGS -> MultiSelectFilterPicker(
            title = title,
            options = tags,
            selected = filters.tagIds,
            onChange = { onFiltersChange(filters.copy(tagIds = it)) },
            onDismiss = close,
        )
        StatFilterSection.BILLABLE -> Unit
    }
}

@Composable
private fun StatFilterRow(
    section: StatFilterSection,
    icon: ImageVector,
    selected: Set<String>,
    options: List<FilterOption>,
    onOpen: (StatFilterSection) -> Unit,
) {
    FilterRow(
        label = statFilterSectionLabel(section),
        icon = icon,
        value = multiSelectSummary(selected, options),
        onClick = { onOpen(section) },
        modifier = Modifier.testTag(StatisticsFilterTestTags.section(section)),
    )
}

@Composable
private fun statFilterSectionLabel(section: StatFilterSection): String = stringResource(
    when (section) {
        StatFilterSection.BILLABLE -> R.string.stats2_filter_billable
        StatFilterSection.TASKS -> R.string.stats2_filter_tasks
        StatFilterSection.TAGS -> R.string.stats2_filter_tags
        StatFilterSection.CLIENTS -> R.string.stats2_filter_clients
        StatFilterSection.PROJECTS -> R.string.stats2_filter_projects
    },
)

@Composable
private fun billableLabel(filter: BillableFilter): String = when (filter) {
    BillableFilter.All -> stringResource(R.string.stats2_billable_all)
    BillableFilter.Billable -> stringResource(R.string.stats2_billable_billable)
    BillableFilter.NonBillable -> stringResource(R.string.stats2_billable_nonbillable)
}
