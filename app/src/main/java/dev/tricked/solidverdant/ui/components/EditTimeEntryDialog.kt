/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.domain.time.formatTimeEntryInstant
import dev.tricked.solidverdant.domain.time.isRunningTimeEntry
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.tracking.EntryTimeValidator
import dev.tricked.solidverdant.ui.tracking.EntryTrustRules
import dev.tricked.solidverdant.ui.tracking.EntryValidationBanner
import dev.tricked.solidverdant.ui.tracking.formatElapsedTime
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import dev.tricked.solidverdant.ui.tracking.ProjectTaskDropdown as TrackingProjectTaskDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun EditTimeEntryDialog(
    entry: TimeEntry?,
    zone: ZoneId,
    projects: List<Project>,
    clients: List<Client> = emptyList(),
    tasks: List<Task>,
    tags: List<Tag>,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?, List<String>, Boolean, String, String?) -> Unit,
    existingEntries: List<TimeEntry> = emptyList(),
    preventOverlap: Boolean = false,
    inlinePresentation: Boolean = false,
    suggestedStart: ZonedDateTime? = null,
    suggestedEnd: ZonedDateTime? = null,
    onDelete: (() -> Unit)? = null,
    isBreak: Boolean = false,
    onCreateProject: ((String, String?, (Result<Project>) -> Unit) -> Unit)? = null,
    onCreateClient: ((String, (Result<Client>) -> Unit) -> Unit)? = null,
    onCreateTask: ((String, String, (Result<Task>) -> Unit) -> Unit)? = null,
    onCreateTag: ((String, (Result<Tag>) -> Unit) -> Unit)? = null,
) {
    var description by remember(entry?.id) { mutableStateOf(entry?.description ?: "") }
    var projectId by remember(entry?.id) { mutableStateOf(entry?.projectId) }
    var taskId by remember(entry?.id) { mutableStateOf(entry?.taskId) }
    var selectedTags by remember(entry?.id) { mutableStateOf(entry?.tags?.map { it.id }.orEmpty()) }
    var billable by remember(entry?.id) { mutableStateOf(entry?.billable ?: false) }
    val isRunningEntry = remember(entry?.id, entry?.end, entry?.duration) {
        entry?.let(::isRunningTimeEntry) == true
    }
    val isBreakEntry = isBreak || entry?.type == TimeEntryType.BREAK
    val originalStart = remember(entry?.id, suggestedStart, zone) {
        entry?.let { ZonedDateTime.parse(it.start, DateTimeFormatter.ISO_DATE_TIME).withZoneSameInstant(zone) }
            ?: (suggestedStart ?: ZonedDateTime.now(zone).minusHours(1))
                .withSecond(0).withNano(0)
    }
    val originalEnd = remember(entry?.id, suggestedEnd, suggestedStart, zone) {
        when {
            suggestedEnd != null -> suggestedEnd.withZoneSameInstant(zone)
            entry?.end != null -> ZonedDateTime.parse(entry.end, DateTimeFormatter.ISO_DATE_TIME).withZoneSameInstant(zone)
            entry != null -> originalStart.plusSeconds((entry.duration ?: 0).toLong())
            else -> ZonedDateTime.now(originalStart.zone).withSecond(0).withNano(0)
                .let { if (it.isAfter(originalStart)) it else originalStart.plusMinutes(1) }
        }
    }
    var startTime by remember(entry?.id, suggestedStart, zone) { mutableStateOf(originalStart) }
    var endTime by remember(entry?.id, suggestedEnd, suggestedStart, zone) { mutableStateOf(originalEnd) }
    var durationMinutes by remember(entry?.id, suggestedStart, suggestedEnd, zone) {
        mutableStateOf(java.time.Duration.between(originalStart, originalEnd).toMinutes().coerceAtLeast(1).toString())
    }
    var editingTime by remember { mutableStateOf<TimeField?>(null) }
    var editingDate by remember { mutableStateOf<TimeField?>(null) }
    var catalogCreation by remember(entry?.id) { mutableStateOf<CatalogCreationRequest?>(null) }
    var catalogName by remember(entry?.id) { mutableStateOf("") }
    var catalogClientId by remember(entry?.id) { mutableStateOf<String?>(null) }
    var catalogError by remember(entry?.id) { mutableStateOf<String?>(null) }
    var catalogSaving by remember(entry?.id) { mutableStateOf(false) }
    var returnToProjectName by remember(entry?.id) { mutableStateOf("") }
    val durationIsValid = isRunningEntry || durationMinutes.toLongOrNull()?.let { it > 0 } == true
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val overlaps = remember(startTime, endTime, existingEntries, isRunningEntry) {
        if (isBreakEntry || isRunningEntry || existingEntries.isEmpty()) {
            false
        } else {
            val candidate = (
                entry ?: TimeEntry(
                    id = "",
                    userId = "",
                    start = formatTimeEntryInstant(startTime),
                    end = formatTimeEntryInstant(endTime),
                    organizationId = existingEntries.firstOrNull()?.organizationId.orEmpty(),
                )
                ).copy(
                start = formatTimeEntryInstant(startTime),
                end = formatTimeEntryInstant(endTime),
            )
            existingEntries.any { it.id != candidate.id && EntryTrustRules.overlaps(candidate, it) }
        }
    }
    val validation = remember(startTime, endTime, overlaps, preventOverlap, isRunningEntry) {
        if (isRunningEntry) {
            EntryTimeValidator.Result(error = null, warnings = emptyList())
        } else {
            EntryTimeValidator.evaluate(startTime, endTime, overlaps, preventOverlap)
        }
    }
    val durationHours = remember(startTime, endTime) {
        java.time.Duration.between(startTime, endTime).toHours().coerceAtLeast(0)
    }

    fun setDuration(minutes: Long) {
        val safeMinutes = minutes.coerceAtLeast(1)
        durationMinutes = safeMinutes.toString()
        endTime = startTime.plusMinutes(safeMinutes)
    }

    fun beginCatalogCreation(
        kind: CatalogCreationKind,
        suggestedName: String,
        projectId: String? = null,
        returnToProject: Boolean = false,
    ) {
        catalogCreation = CatalogCreationRequest(
            kind = kind,
            projectId = projectId,
            returnToProject = returnToProject,
        )
        catalogName = suggestedName
        catalogClientId = null
        catalogError = null
        catalogSaving = false
        if (!returnToProject) returnToProjectName = ""
    }

    val saveEntry = {
        onSave(
            description.ifEmpty { null },
            projectId.takeUnless { isBreakEntry },
            taskId.takeUnless { isBreakEntry },
            selectedTags.takeUnless { isBreakEntry }.orEmpty(),
            billable && !isBreakEntry,
            formatTimeEntryInstant(startTime),
            endTime.takeUnless { isRunningEntry }?.let(::formatTimeEntryInstant),
        )
    }
    val sheetContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = Dimens.Space24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
        ) {
            Box(Modifier.padding(horizontal = Dimens.Space8)) {
                EntrySheetHeader(
                    title = stringResource(
                        when {
                            isBreakEntry -> R.string.break_entry_title
                            entry == null -> R.string.add_time_entry
                            else -> R.string.edit_time_entry
                        },
                    ),
                    onCancel = onDismiss,
                    onSave = saveEntry,
                    saveEnabled = durationIsValid && validation.canSave,
                    cancelTag = EditTimeEntryTestTags.CANCEL_BUTTON,
                    saveTag = EditTimeEntryTestTags.SAVE_BUTTON,
                )
            }

            EntryDescriptionField(
                value = description,
                onValueChange = { description = it },
                testTag = EditTimeEntryTestTags.DESCRIPTION_FIELD,
            )

            if (!isBreakEntry) {
                GroupedSection {
                    TrackingProjectTaskDropdown(
                        selectedProjectId = projectId,
                        selectedTaskId = taskId,
                        projects = projects,
                        tasks = tasks,
                        onSelectionChanged = { newProjectId, newTaskId ->
                            projectId = newProjectId
                            taskId = newTaskId
                        },
                        enabled = true,
                        onCreateProject = onCreateProject?.let { { name -> beginCatalogCreation(CatalogCreationKind.PROJECT, name) } },
                        onCreateTask = onCreateTask?.let {
                            { name, selectedId ->
                                beginCatalogCreation(CatalogCreationKind.TASK, name, selectedId)
                            }
                        },
                        style = SelectorStyle.Grouped,
                    )
                    GroupedDivider(inset = Dimens.SettingsIconInset)
                    TagsSelector(
                        selectedTagIds = selectedTags,
                        availableTags = tags,
                        onTagsChanged = { selectedTags = it },
                        enabled = true,
                        onCreateTag = onCreateTag?.let { { name -> beginCatalogCreation(CatalogCreationKind.TAG, name) } },
                        style = SelectorStyle.Grouped,
                    )
                    GroupedDivider(inset = Dimens.SettingsIconInset)
                    GroupedSwitchRow(
                        title = stringResource(R.string.billable),
                        leadingIcon = Icons.Outlined.AttachMoney,
                        checked = billable,
                        onCheckedChange = { billable = it },
                        modifier = Modifier.testTag(EditTimeEntryTestTags.BILLABLE),
                    )
                }
            }

            GroupedSection(
                header = stringResource(R.string.time_section),
                footer = stringResource(R.string.running_entry_start_edit_hint).takeIf { isRunningEntry },
            ) {
                EntryTimeRow(
                    label = stringResource(R.string.start),
                    value = startTime,
                    onDateClick = { editingDate = TimeField.Start },
                    onTimeClick = { editingTime = TimeField.Start },
                    dateTag = EditTimeEntryTestTags.START_DATE,
                    timeTag = EditTimeEntryTestTags.START_TIME,
                    dateLabel = stringResource(R.string.start_date),
                    timeLabel = stringResource(R.string.start_time),
                )
                if (!isRunningEntry) {
                    GroupedDivider()
                    EntryTimeRow(
                        label = stringResource(R.string.end),
                        value = endTime,
                        onDateClick = { editingDate = TimeField.End },
                        onTimeClick = { editingTime = TimeField.End },
                        dateTag = EditTimeEntryTestTags.END_DATE,
                        timeTag = EditTimeEntryTestTags.END_TIME,
                        dateLabel = stringResource(R.string.end_date),
                        timeLabel = stringResource(R.string.end_time),
                    )
                    GroupedDivider()
                    EntryDurationRow(
                        minutesText = durationMinutes,
                        totalLabel = formatElapsedTime((durationMinutes.toLongOrNull() ?: 0) * SECONDS_PER_MINUTE),
                        isValid = durationIsValid,
                        onMinutesTextChange = { value ->
                            durationMinutes = value
                            value.toLongOrNull()
                                ?.takeIf { it >= MINIMUM_DURATION_MINUTES }
                                ?.let { endTime = startTime.plusMinutes(it) }
                        },
                        onDecrease = {
                            setDuration((durationMinutes.toLongOrNull() ?: MINIMUM_DURATION_MINUTES) - DURATION_STEP_MINUTES)
                        },
                        onIncrease = { setDuration((durationMinutes.toLongOrNull() ?: 0) + DURATION_STEP_MINUTES) },
                        fieldTag = EditTimeEntryTestTags.DURATION_FIELD,
                    )
                }
            }

            if (!isRunningEntry) {
                Box(Modifier.padding(horizontal = Dimens.Space16)) {
                    EntryValidationBanner(result = validation, durationHours = durationHours)
                }
            }

            if (onDelete != null) {
                DestructiveActionRow(
                    label = stringResource(R.string.delete_entry),
                    onClick = onDelete,
                    testTag = EditTimeEntryTestTags.DELETE_BUTTON,
                )
            }
        }
    }

    if (inlinePresentation) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT_FRACTION),
                shape = RoundedCornerShape(topStart = Dimens.RadiusXl, topEnd = Dimens.RadiusXl),
                color = MaterialTheme.colorScheme.background,
            ) {
                sheetContent()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = Dimens.RadiusXl, topEnd = Dimens.RadiusXl),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            sheetContent()
        }
    }

    editingTime?.let { field ->
        val current = if (field == TimeField.Start) startTime else endTime
        EntryTimePickerDialog(
            title = stringResource(if (field == TimeField.Start) R.string.start_time else R.string.end_time),
            initial = current,
            onDismiss = { editingTime = null },
            onConfirm = { hour, minute ->
                if (field == TimeField.Start) {
                    val newStart = startTime.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    if (!isRunningEntry) endTime = retimedEnd(startTime, newStart, endTime)
                    startTime = newStart
                } else {
                    val sameDayEnd = endTime.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                    // Do not silently roll an earlier clock-time into a ~24h entry: only a plausible
                    // overnight span becomes cross-midnight, otherwise keep it same-day so the
                    // validation banner surfaces the end-before-start error for the user to fix.
                    endTime = EntryTimeValidator.resolveEnd(startTime, sameDayEnd) ?: sameDayEnd
                    durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes().toString()
                }
                editingTime = null
            },
        )
    }

    editingDate?.let { field ->
        val current = if (field == TimeField.Start) startTime else endTime
        EntryDatePickerDialog(
            initialDate = current.toLocalDate(),
            onDismiss = { editingDate = null },
            onConfirm = { date ->
                if (field == TimeField.Start) {
                    val newStart = startTime.with(date)
                    if (!isRunningEntry) endTime = retimedEnd(startTime, newStart, endTime)
                    startTime = newStart
                } else {
                    endTime = endTime.with(date)
                    durationMinutes = java.time.Duration.between(startTime, endTime).toMinutes().toString()
                }
                editingDate = null
            },
        )
    }

    catalogCreation?.let { request ->
        val catalogueCreateFailed = stringResource(R.string.catalogue_create_failed)
        val title = when (request.kind) {
            CatalogCreationKind.CLIENT -> R.string.create_client
            CatalogCreationKind.PROJECT -> R.string.create_project
            CatalogCreationKind.TASK -> R.string.create_task
            CatalogCreationKind.TAG -> R.string.create_tag
        }
        val canSave = catalogName.trim().isNotEmpty() &&
            (request.kind != CatalogCreationKind.TASK || !request.projectId.isNullOrBlank()) &&
            !catalogSaving
        AlertDialog(
            onDismissRequest = {
                if (!catalogSaving) {
                    catalogCreation = null
                    catalogError = null
                }
            },
            title = { Text(stringResource(title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = catalogName,
                        onValueChange = {
                            catalogName = it
                            catalogError = null
                        },
                        label = { Text(stringResource(R.string.catalogue_name)) },
                        singleLine = true,
                        enabled = !catalogSaving,
                        modifier = Modifier.fillMaxWidth().testTag(EditTimeEntryTestTags.CATALOGUE_NAME),
                    )
                    if (request.kind == CatalogCreationKind.PROJECT) {
                        CatalogClientPicker(
                            clients = clients,
                            selectedClientId = catalogClientId,
                            enabled = !catalogSaving,
                            onSelected = { catalogClientId = it },
                            onCreateClient = onCreateClient?.let {
                                {
                                    returnToProjectName = catalogName
                                    beginCatalogCreation(CatalogCreationKind.CLIENT, "", returnToProject = true)
                                }
                            },
                        )
                    }
                    catalogError?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(EditTimeEntryTestTags.CATALOGUE_CREATE_ERROR),
                        )
                    }
                    if (catalogSaving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val safeName = catalogName.trim()
                        catalogSaving = true
                        when (request.kind) {
                            CatalogCreationKind.CLIENT -> onCreateClient?.invoke(safeName) { result ->
                                result.onSuccess { client ->
                                    catalogSaving = false
                                    if (request.returnToProject) {
                                        catalogClientId = client.id
                                        catalogName = returnToProjectName
                                        catalogCreation = CatalogCreationRequest(CatalogCreationKind.PROJECT)
                                    } else {
                                        catalogCreation = null
                                    }
                                }.onFailure { error ->
                                    catalogSaving = false
                                    catalogError = catalogueCreateFailed
                                }
                            }
                            CatalogCreationKind.PROJECT -> onCreateProject?.invoke(safeName, catalogClientId) { result ->
                                result.onSuccess { project ->
                                    projectId = project.id
                                    taskId = null
                                    catalogSaving = false
                                    catalogCreation = null
                                }.onFailure { error ->
                                    catalogSaving = false
                                    catalogError = catalogueCreateFailed
                                }
                            }
                            CatalogCreationKind.TASK -> onCreateTask?.invoke(safeName, requireNotNull(request.projectId)) { result ->
                                result.onSuccess { task ->
                                    projectId = task.projectId
                                    taskId = task.id
                                    catalogSaving = false
                                    catalogCreation = null
                                }.onFailure { error ->
                                    catalogSaving = false
                                    catalogError = catalogueCreateFailed
                                }
                            }
                            CatalogCreationKind.TAG -> onCreateTag?.invoke(safeName) { result ->
                                result.onSuccess { tag ->
                                    selectedTags = (selectedTags + tag.id).distinct()
                                    catalogSaving = false
                                    catalogCreation = null
                                }.onFailure { error ->
                                    catalogSaving = false
                                    catalogError = catalogueCreateFailed
                                }
                            }
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.testTag(EditTimeEntryTestTags.CATALOGUE_CREATE_CONFIRM),
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        catalogCreation = null
                        catalogError = null
                    },
                    enabled = !catalogSaving,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private enum class TimeField { Start, End }

private enum class CatalogCreationKind { CLIENT, PROJECT, TASK, TAG }

private data class CatalogCreationRequest(
    val kind: CatalogCreationKind,
    val projectId: String? = null,
    val returnToProject: Boolean = false,
)

@Composable
private fun CatalogClientPicker(
    clients: List<Client>,
    selectedClientId: String?,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
    onCreateClient: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = clients.firstOrNull { it.id == selectedClientId }?.name
        ?: stringResource(R.string.no_client)
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(EditTimeEntryTestTags.CLIENT_PICKER),
        ) {
            Text("${stringResource(R.string.client)}: $selectedName")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.no_client)) },
                onClick = {
                    onSelected(null)
                    expanded = false
                },
            )
            clients.filterNot { it.isArchived }.forEach { client ->
                DropdownMenuItem(
                    text = { Text(client.name) },
                    onClick = {
                        onSelected(client.id)
                        expanded = false
                    },
                )
            }
            onCreateClient?.let { createClient ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.create_client)) },
                    onClick = {
                        expanded = false
                        createClient()
                    },
                    modifier = Modifier.testTag(EditTimeEntryTestTags.CREATE_CLIENT),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryTimePickerDialog(title: String, initial: ZonedDateTime, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { Button(onClick = { onConfirm(state.hour, state.minute) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Moving the start keeps the entry's exact length; the minutes field alone rounds seconds away. */
internal fun retimedEnd(previousStart: ZonedDateTime, newStart: ZonedDateTime, end: ZonedDateTime): ZonedDateTime =
    end.plus(Duration.between(previousStart, newStart))

private const val DURATION_STEP_MINUTES = 15L
private const val MINIMUM_DURATION_MINUTES = 1L
private const val SECONDS_PER_MINUTE = 60L
private const val SHEET_HEIGHT_FRACTION = 0.9f
