/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.repository.EntryTemplate
import dev.tricked.solidverdant.ui.components.EmptyState
import dev.tricked.solidverdant.ui.components.ErrorState
import dev.tricked.solidverdant.ui.components.LoadingState
import dev.tricked.solidverdant.ui.statistics.GroupedPosition
import dev.tricked.solidverdant.ui.statistics.LazyGroupedRow
import dev.tricked.solidverdant.ui.theme.Dimens

internal object ManageTemplatesTestTags {
    const val LIST = "templates_list"
    const val ADD = "templates_add"
    fun row(templateId: String) = "templates_row_$templateId"
    fun favorite(templateId: String) = "templates_favorite_$templateId"
    fun menu(templateId: String) = "templates_menu_$templateId"
}

/**
 * Manage favorites & templates (gap analysis #9). Create, edit, reorder, favorite and delete
 * reusable entry templates. Everything is Room-backed and works offline; archived/deleted catalogue
 * references are surfaced per row rather than silently substituted (gap analysis #81).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageTemplatesScreen(onBack: () -> Unit = {}, viewModel: ManageTemplatesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showCreate by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<EntryTemplate?>(null) }
    var deletingTemplate by remember { mutableStateOf<EntryTemplate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_templates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.review_navigate_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.isLoading && !state.error && state.organizationId != null) {
                FloatingActionButton(
                    onClick = { showCreate = true },
                    shape = CircleShape,
                    // The app's bright accent button, as on Track and Calendar.
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag(ManageTemplatesTestTags.ADD),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.templates_add))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> LoadingState(label = stringResource(R.string.templates_loading))
                state.error -> ErrorState(text = stringResource(R.string.templates_error_title), onRetry = viewModel::retry)
                state.templates.isEmpty() -> EmptyState(text = stringResource(R.string.templates_empty_title))
                else -> TemplateList(
                    state = state,
                    onToggleFavorite = { viewModel.setFavorite(it.id, !it.isFavorite) },
                    onMove = { template, up -> viewModel.moveTemplate(template.id, up = up) },
                    onEdit = { editingTemplate = it },
                    onDelete = { deletingTemplate = it },
                )
            }
        }
    }

    if (showCreate || editingTemplate != null) {
        val editing = editingTemplate
        TemplateEditorSheet(
            existing = editing,
            projects = state.projects,
            tasks = state.tasks,
            tags = state.tags,
            onDismiss = {
                showCreate = false
                editingTemplate = null
            },
            onSave = { draft ->
                viewModel.saveNewTemplate(draft)
                showCreate = false
            },
            onUpdate = { updated ->
                viewModel.updateTemplate(updated)
                editingTemplate = null
            },
            onDelete = editing?.let { template ->
                {
                    viewModel.deleteTemplate(template.id)
                    editingTemplate = null
                }
            },
        )
    }

    deletingTemplate?.let { template ->
        TemplateDeleteDialog(
            onConfirm = {
                viewModel.deleteTemplate(template.id)
                deletingTemplate = null
            },
            onDismiss = { deletingTemplate = null },
        )
    }
}

/** The templates as one grouped section, drawn per lazy item so a long list scrolls cheaply. */
@Composable
private fun TemplateList(
    state: ManageTemplatesUiState,
    onToggleFavorite: (EntryTemplate) -> Unit,
    onMove: (EntryTemplate, Boolean) -> Unit,
    onEdit: (EntryTemplate) -> Unit,
    onDelete: (EntryTemplate) -> Unit,
) {
    val ordered = state.templates
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(ManageTemplatesTestTags.LIST),
        contentPadding = PaddingValues(top = Dimens.Space16, bottom = Dimens.FabClearance),
    ) {
        itemsIndexed(ordered, key = { _, template -> template.id }) { index, template ->
            // Favorites and the rest reorder separately, so a row moves only within its group.
            val canMoveUp = index > 0 && ordered[index - 1].isFavorite == template.isFavorite
            val canMoveDown = index < ordered.lastIndex && ordered[index + 1].isFavorite == template.isFavorite
            val resolution = remember(template, state.projects, state.tasks, state.tags) {
                TemplateResolver.resolve(template, state.projects, state.tasks, state.tags)
            }
            val summary = remember(template, state.projects, state.tasks) {
                templateProjectTaskSummary(template, state.projects, state.tasks)
            }
            LazyGroupedRow(position = GroupedPosition.of(index, ordered.size)) {
                TemplateRow(
                    template = template,
                    resolution = resolution,
                    projectTaskSummary = summary,
                    label = templateDisplayLabel(template, state.projects),
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onToggleFavorite = { onToggleFavorite(template) },
                    onMoveUp = { onMove(template, true) },
                    onMoveDown = { onMove(template, false) },
                    onEdit = { onEdit(template) },
                    onDelete = { onDelete(template) },
                )
            }
        }
    }
}

/**
 * One template in the grouped list: tapping it edits the template; the star toggles favorite and
 * the overflow menu edits, reorders or deletes. Unavailable catalogue references are listed under
 * the details so the user sees what will not be applied.
 */
@Composable
@Suppress("LongMethod", "LongParameterList")
internal fun TemplateRow(
    template: EntryTemplate,
    resolution: TemplateResolution,
    projectTaskSummary: String?,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleFavorite: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit), onClick = onEdit)
            .testTag(ManageTemplatesTestTags.row(template.id))
            .padding(start = Dimens.Space16, end = Dimens.Space4, top = Dimens.Space8, bottom = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(vertical = Dimens.Space4),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space2),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = projectTaskSummary
                ?: stringResource(R.string.templates_details_none).takeIf {
                    template.description.isNullOrBlank() && template.projectId == null
                }
            secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val metaParts = buildList {
                if (template.tagIds.isNotEmpty()) {
                    add(pluralStringResource(R.plurals.templates_tag_count, template.tagIds.size, template.tagIds.size))
                }
                if (template.billable) add(stringResource(R.string.billable))
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (resolution.hasIssues) {
                Row(
                    modifier = Modifier.padding(top = Dimens.Space4),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = stringResource(R.string.templates_unavailable),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Dimens.IconSmall),
                    )
                    TemplateIssueList(resolution, modifier = Modifier.weight(1f))
                }
            }
        }

        IconButton(onClick = onToggleFavorite, modifier = Modifier.testTag(ManageTemplatesTestTags.favorite(template.id))) {
            Icon(
                imageVector = if (template.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = stringResource(
                    if (template.isFavorite) R.string.templates_favorite_remove else R.string.templates_favorite_add,
                ),
                tint = if (template.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag(ManageTemplatesTestTags.menu(template.id))) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.templates_row_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.templates_move_up)) },
                    enabled = canMoveUp,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onMoveUp()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.templates_move_down)) },
                    enabled = canMoveDown,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onMoveDown()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
