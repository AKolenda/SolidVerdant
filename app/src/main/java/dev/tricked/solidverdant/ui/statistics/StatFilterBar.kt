/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.theme.Dimens

internal object StatisticsFilterTestTags {
    const val OPEN = "stats_filter_open"
    const val CLEAR = "stats_filter_clear"
    const val PROJECT_SEARCH = "stats_project_filter_search"
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StatFilterSheet(
    filters: StatFilters,
    catalog: StatCatalog,
    onFiltersChange: (StatFilters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSection by rememberSaveable { mutableStateOf(StatFilterSection.BILLABLE) }
    var projectQuery by rememberSaveable { mutableStateOf("") }
    val filteredProjects = remember(catalog.projects, projectQuery) {
        val query = projectQuery.trim()
        if (query.isEmpty()) catalog.projects else catalog.projects.filter { it.name.contains(query, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.Space16)
                .padding(bottom = Dimens.Space24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.stats2_filter_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReset, enabled = filters.isActive) {
                    Text(stringResource(R.string.stats2_reset))
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
            ) {
                statFilterSectionOrder.forEach { section ->
                    FilterChip(
                        selected = selectedSection == section,
                        onClick = { selectedSection = section },
                        label = { Text(statFilterSectionLabel(section)) },
                        modifier = Modifier.testTag(StatisticsFilterTestTags.section(section)),
                    )
                }
            }

            when (selectedSection) {
                StatFilterSection.BILLABLE -> {
                    val billableOptions = listOf(
                        BillableFilter.All,
                        BillableFilter.Billable,
                        BillableFilter.NonBillable,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        billableOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = filters.billable == option,
                                onClick = { onFiltersChange(filters.copy(billable = option)) },
                                shape = SegmentedButtonDefaults.itemShape(index, billableOptions.size),
                            ) { Text(billableLabel(option)) }
                        }
                    }
                }

                StatFilterSection.TASKS -> FilterSection(
                    emptyText = stringResource(R.string.stats2_filter_empty_tasks),
                    options = catalog.tasks.map { it.id to it.name },
                    selected = filters.taskIds,
                    onToggle = { onFiltersChange(filters.toggleTask(it)) },
                )

                StatFilterSection.TAGS -> FilterSection(
                    emptyText = stringResource(R.string.stats2_filter_empty_tags),
                    options = catalog.tags.map { it.id to it.name },
                    selected = filters.tagIds,
                    onToggle = { onFiltersChange(filters.toggleTag(it)) },
                )

                StatFilterSection.CLIENTS -> FilterSection(
                    emptyText = stringResource(R.string.stats2_filter_empty_clients),
                    options = catalog.clients.map { it.id to it.name },
                    selected = filters.clientIds,
                    onToggle = { onFiltersChange(filters.toggleClient(it)) },
                )

                StatFilterSection.PROJECTS -> {
                    OutlinedTextField(
                        value = projectQuery,
                        onValueChange = { projectQuery = it },
                        modifier = Modifier.fillMaxWidth().testTag(StatisticsFilterTestTags.PROJECT_SEARCH),
                        label = { Text(stringResource(R.string.stats2_search_projects)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (projectQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { projectQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                    )
                    val emptyProjectsText = if (projectQuery.isBlank()) {
                        R.string.stats2_filter_empty_projects
                    } else {
                        R.string.no_results_found
                    }
                    FilterSection(
                        emptyText = stringResource(emptyProjectsText),
                        options = filteredProjects.map { it.id to it.name },
                        selected = filters.projectIds,
                        onToggle = { onFiltersChange(filters.toggleProject(it)) },
                        optionTestTag = StatisticsFilterTestTags::projectOption,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterSection(
    emptyText: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    optionTestTag: ((String) -> String)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
        if (options.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
                options.forEach { (id, name) ->
                    FilterChip(
                        selected = id in selected,
                        onClick = { onToggle(id) },
                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = optionTestTag?.let { Modifier.testTag(it(id)) } ?: Modifier,
                    )
                }
            }
        }
    }
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
