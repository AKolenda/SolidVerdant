/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.local.AppThemeMode
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.service.TimeTrackingNotificationService
import dev.tricked.solidverdant.ui.components.ErrorState
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.LoadingState
import dev.tricked.solidverdant.ui.components.ProjectTaskDropdown
import dev.tricked.solidverdant.ui.components.SelectorStyle
import dev.tricked.solidverdant.ui.components.SheetTitleRow
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.SolidVerdantTheme

/**
 * Activity for selecting a project when starting time tracking from Quick Settings.
 *
 * This activity closes immediately after selection; the foreground notification service owns the
 * quick-start request so it survives the activity lifecycle.
 */
@AndroidEntryPoint
class ProjectSelectionActivity : ComponentActivity() {

    private val viewModel: ProjectSelectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appTheme by viewModel.appTheme.collectAsState(initial = AppThemeMode.SYSTEM)
            SolidVerdantTheme(themeMode = appTheme) {
                ProjectSelectionContent(
                    viewModel = viewModel,
                    onStartTracking = { projectId, taskId, description, projectName, taskName ->
                        TimeTrackingNotificationService.quickStart(
                            context = this,
                            projectId = projectId,
                            taskId = taskId,
                            description = description,
                            projectName = projectName,
                            taskName = taskName,
                        )

                        // Close immediately - TileService handles the rest
                        finish()
                    },
                    onCancel = {
                        finish()
                    },
                )
            }
        }
    }
}

object ProjectSelectionTestTags {
    const val SCREEN = "tile_project_selection_screen"
    const val START_BUTTON = "tile_project_selection_start_button"
    const val CANCEL_BUTTON = "tile_project_selection_cancel_button"
    const val DESCRIPTION_FIELD = "tile_project_selection_description"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSelectionContent(
    viewModel: ProjectSelectionViewModel,
    onStartTracking: (projectId: String?, taskId: String?, description: String, projectName: String?, taskName: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadProjects()
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.loadProjects(forceRefresh = true) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.Space16, bottom = Dimens.Space16)
                .testTag(ProjectSelectionTestTags.SCREEN),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
        ) {
            SheetTitleRow(title = stringResource(R.string.start_time_tracking))
            when {
                uiState.isLoading && uiState.projects.isEmpty() -> {
                    LoadingState(label = stringResource(R.string.loading_projects))
                }

                (uiState.error != null || uiState.errorRes != null) && uiState.projects.isEmpty() -> {
                    ErrorState(
                        text = stringResource(
                            R.string.error_format,
                            uiState.error ?: uiState.errorRes?.let { stringResource(it) }.orEmpty(),
                        ),
                        onRetry = { viewModel.loadProjects(forceRefresh = true) },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Space16),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onCancel, modifier = Modifier.testTag(ProjectSelectionTestTags.CANCEL_BUTTON)) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }

                else -> {
                    StartTrackingForm(
                        projects = uiState.projects.filter { !it.isArchived },
                        tasks = uiState.tasks.filter { !it.isDone },
                        onStartTracking = onStartTracking,
                        onCancel = onCancel,
                    )
                }
            }
        }
    }
}

/**
 * The next timer's fields, laid out like the Time Tracker's start form: what you are working on
 * first, then the grouped project and task rows, then Cancel and a clear primary Start. The tile's
 * quick start carries no tags or billable flag, so those rows are not offered here.
 */
@Composable
fun StartTrackingForm(
    projects: List<Project>,
    tasks: List<Task>,
    onStartTracking: (projectId: String?, taskId: String?, description: String, projectName: String?, taskName: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    val descriptionLabel = stringResource(R.string.description_optional)

    val start = {
        val project = projects.firstOrNull { it.id == projectId }
        val task = project?.let { tasks.firstOrNull { it.id == taskId && it.projectId == project.id } }
        onStartTracking(project?.id, task?.id, description, project?.name, task?.name)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space16)) {
        GroupedSection {
            TextField(
                value = description,
                onValueChange = { description = it },
                placeholder = { Text(descriptionLabel) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProjectSelectionTestTags.DESCRIPTION_FIELD)
                    .semantics { contentDescription = descriptionLabel },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            GroupedDivider()
            ProjectTaskDropdown(
                projects = projects,
                tasks = tasks,
                selectedProjectId = projectId,
                selectedTaskId = taskId,
                onSelectionChanged = { newProjectId, newTaskId ->
                    projectId = newProjectId
                    taskId = newTaskId
                },
                style = SelectorStyle.Grouped,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Space16),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(ProjectSelectionTestTags.CANCEL_BUTTON)) {
                Text(stringResource(R.string.cancel))
            }
            Button(onClick = start, modifier = Modifier.testTag(ProjectSelectionTestTags.START_BUTTON)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(Dimens.IconSmall))
                Spacer(Modifier.size(Dimens.Space8))
                Text(stringResource(R.string.start))
            }
        }
    }
}
