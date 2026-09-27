/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.local.db.RateLimitMarker
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.SyncOperation
import dev.tricked.solidverdant.ui.components.ConfirmDialog
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.util.RelativeTime
import java.text.DateFormat
import java.util.Date

/** What the Sync Center's rows can do; the ViewModel supplies them, tests record them. */
internal data class SyncCenterActions(
    val onSyncNow: () -> Unit,
    val onRetry: (String) -> Unit,
    val onRetryAll: () -> Unit,
    val onDiscard: (String) -> Unit,
    val onRetryUpload: (String) -> Unit,
    val onUseServerVersion: (String) -> Unit,
)

/**
 * Dedicated Sync Center screen (#33): shows sync freshness (last pull / last push), a plain-language
 * status summary, the changes waiting to sync, and any failures with per-entry Retry / Discard plus a
 * Retry All action. All state is communicated with text + icon (never colour alone) for a11y. It is a
 * read/display + existing-action surface; it does not implement any sync control flow itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncCenterScreen(onBack: () -> Unit, viewModel: SyncCenterViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.testTag(SyncCenterTestTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_center_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(SyncCenterTestTags.BACK_BUTTON)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.sync_center_navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        SyncCenterContent(
            state = state,
            nowMs = viewModel.nowMs(),
            actions = SyncCenterActions(
                onSyncNow = viewModel::syncNow,
                onRetry = viewModel::retry,
                onRetryAll = viewModel::retryAll,
                onDiscard = { viewModel.discard(it) },
                onRetryUpload = { viewModel.retryConflictWithLocal(it) },
                onUseServerVersion = { viewModel.useServerVersion(it) },
            ),
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * The Sync Center body: grouped sections for the status, freshness (with Sync now) and one row per
 * entry with pending, conflicting or failed changes. Discarding failed changes and using the server
 * version both throw away this device's work, so they confirm first; every entry action is disabled
 * while a recovery for that entry is still running.
 */
@Composable
@Suppress("LongMethod")
internal fun SyncCenterContent(state: SyncCenterUiState, nowMs: Long, actions: SyncCenterActions, modifier: Modifier = Modifier) {
    var confirmDiscard by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmUseServer by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Dimens.Space8, bottom = Dimens.Space24),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space24),
    ) {
        Text(
            text = stringResource(R.string.sync_center_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.Space32),
        )

        StatusSection(state)

        GroupedSection(header = stringResource(R.string.sync_freshness_title)) {
            FreshnessRow(
                label = stringResource(R.string.sync_last_pulled_label),
                icon = Icons.Outlined.CloudDownload,
                timeMs = state.lastFullSyncAtMs,
                neverRes = R.string.sync_never_pulled,
                nowMs = nowMs,
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            FreshnessRow(
                label = stringResource(R.string.sync_last_pushed_label),
                icon = Icons.Outlined.CloudUpload,
                timeMs = state.lastPushAtMs,
                neverRes = R.string.sync_never_pushed,
                nowMs = nowMs,
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(
                title = stringResource(R.string.sync_now),
                leadingIcon = Icons.Outlined.Sync,
                onClick = actions.onSyncNow,
                showChevron = false,
                modifier = Modifier.testTag(SyncCenterTestTags.SYNC_NOW),
            )
        }

        if (state.pendingEntries.isNotEmpty()) {
            GroupedSection(header = stringResource(R.string.sync_pending_section_title)) {
                state.pendingEntries.forEachIndexed { index, group ->
                    if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
                    val busy = group.entryId in state.activeRecoveryEntryIds
                    EntryRow(
                        group = group,
                        icon = Icons.Outlined.Schedule,
                        iconTint = MaterialTheme.colorScheme.primary,
                        reason = stringResource(pendingReasonRes(group)),
                    ) {
                        // Send a waiting or backing-off change now instead of after its retry delay.
                        TextButton(
                            onClick = { actions.onRetry(group.entryId) },
                            enabled = !busy,
                            modifier = Modifier.testTag(SyncCenterTestTags.pendingRetry(group.entryId)),
                        ) { Text(stringResource(R.string.sync_retry)) }
                    }
                }
            }
        }

        if (state.conflictEntries.isNotEmpty()) {
            GroupedSection(header = stringResource(R.string.sync_conflicts_section_title)) {
                state.conflictEntries.forEachIndexed { index, group ->
                    if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
                    val busy = group.entryId in state.activeRecoveryEntryIds
                    EntryRow(
                        group = group,
                        icon = Icons.Outlined.Warning,
                        iconTint = MaterialTheme.colorScheme.error,
                        reason = stringResource(R.string.sync_conflict_item),
                    ) {
                        DestructiveTextButton(
                            label = stringResource(R.string.sync_use_server_version),
                            enabled = !busy,
                            testTag = SyncCenterTestTags.conflictUseServer(group.entryId),
                            onClick = { confirmUseServer = group.entryId },
                        )
                        TextButton(
                            onClick = { actions.onRetryUpload(group.entryId) },
                            enabled = !busy,
                            modifier = Modifier.testTag(SyncCenterTestTags.conflictRetry(group.entryId)),
                        ) { Text(stringResource(R.string.sync_retry_upload)) }
                    }
                }
            }
        }

        if (state.failedEntries.isNotEmpty()) {
            GroupedSection(header = stringResource(R.string.sync_failures_section_title)) {
                state.failedEntries.forEachIndexed { index, group ->
                    if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
                    val busy = group.entryId in state.activeRecoveryEntryIds
                    EntryRow(
                        group = group,
                        icon = Icons.Outlined.SyncProblem,
                        iconTint = MaterialTheme.colorScheme.error,
                        reason = stringResource(failureReasonRes(group.error)),
                    ) {
                        DestructiveTextButton(
                            label = stringResource(R.string.sync_discard),
                            enabled = !busy,
                            testTag = SyncCenterTestTags.failedDiscard(group.entryId),
                            onClick = { confirmDiscard = group.entryId },
                        )
                        TextButton(
                            onClick = { actions.onRetry(group.entryId) },
                            enabled = !busy,
                            modifier = Modifier.testTag(SyncCenterTestTags.failedRetry(group.entryId)),
                        ) { Text(stringResource(R.string.sync_retry)) }
                    }
                }
                GroupedDivider()
                GroupedRow(
                    title = stringResource(R.string.sync_retry_all),
                    leadingIcon = Icons.Outlined.Refresh,
                    onClick = actions.onRetryAll,
                    showChevron = false,
                    modifier = Modifier.testTag(SyncCenterTestTags.RETRY_ALL),
                )
            }
        }
    }

    // A prompt whose entry was resolved or retried meanwhile has nothing left to confirm.
    val discardGroup = confirmDiscard?.let { id -> state.failedEntries.firstOrNull { it.entryId == id } }
    val useServerOpen = confirmUseServer?.let { id -> state.conflictEntries.any { it.entryId == id } } == true
    LaunchedEffect(discardGroup, useServerOpen) {
        if (discardGroup == null) confirmDiscard = null
        if (!useServerOpen) confirmUseServer = null
    }

    discardGroup?.let { group ->
        val failed = group.failedOperations
        ConfirmDialog(
            title = pluralStringResource(R.plurals.sweep_sync_discard_title, failed.size, failed.size),
            message = pluralStringResource(R.plurals.sweep_sync_discard_message, failed.size, failed.size, opSummary(failed)),
            confirmLabel = stringResource(R.string.sync_discard),
            onConfirm = {
                confirmDiscard = null
                actions.onDiscard(group.entryId)
            },
            onDismiss = { confirmDiscard = null },
            destructive = true,
            confirmTestTag = SyncCenterTestTags.DISCARD_CONFIRM,
        )
    }

    confirmUseServer?.takeIf { useServerOpen }?.let { entryId ->
        ConfirmDialog(
            title = stringResource(R.string.sweep_sync_use_server_title),
            message = stringResource(R.string.sweep_sync_use_server_message),
            confirmLabel = stringResource(R.string.sync_use_server_version),
            onConfirm = {
                confirmUseServer = null
                actions.onUseServerVersion(entryId)
            },
            onDismiss = { confirmUseServer = null },
            destructive = true,
            confirmTestTag = SyncCenterTestTags.USE_SERVER_CONFIRM,
        )
    }
}

@Composable
private fun StatusSection(state: SyncCenterUiState) {
    val (icon, text, iconDescRes) = when (state.topLine) {
        SyncCenterUiState.TopLine.SYNCED -> Triple(
            Icons.Filled.CheckCircle,
            stringResource(R.string.sync_status_synced),
            R.string.sync_status_icon_synced,
        )
        SyncCenterUiState.TopLine.PENDING -> Triple(
            Icons.Filled.CloudUpload,
            pluralStringResource(R.plurals.sync_status_pending, state.pendingCount, state.pendingCount),
            R.string.sync_status_icon_warning,
        )
        SyncCenterUiState.TopLine.FAILURES -> Triple(
            Icons.Filled.SyncProblem,
            pluralStringResource(R.plurals.sync_status_failures, state.failedCount, state.failedCount),
            R.string.sync_status_icon_warning,
        )
        SyncCenterUiState.TopLine.CONFLICTS -> Triple(
            Icons.Filled.SyncProblem,
            pluralStringResource(R.plurals.sync_status_conflicts, state.conflictCount, state.conflictCount),
            R.string.sync_status_icon_warning,
        )
    }
    val iconDesc = stringResource(iconDescRes)
    GroupedSection(
        footer = if (state.topLine == SyncCenterUiState.TopLine.SYNCED) stringResource(R.string.sync_all_caught_up) else null,
    ) {
        GroupedRow(
            title = text,
            leadingIcon = icon,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$iconDesc. $text" },
        )
        if (state.failedOutsideOrganizationCount > 0) {
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(
                title = pluralStringResource(
                    R.plurals.sync_failed_outside_organization,
                    state.failedOutsideOrganizationCount,
                    state.failedOutsideOrganizationCount,
                ),
                leadingIcon = Icons.Outlined.ErrorOutline,
                destructive = true,
                modifier = Modifier.testTag(SyncCenterTestTags.FAILED_OUTSIDE_ORGANIZATION),
            )
        }
    }
}

@Composable
private fun FreshnessRow(label: String, icon: ImageVector, timeMs: Long?, neverRes: Int, nowMs: Long) {
    GroupedRow(
        title = label,
        subtitle = timeMs?.let { rememberAbsoluteTime(it) },
        leadingIcon = icon,
        value = relativeTimeText(timeMs, neverRes, nowMs),
    )
}

/**
 * One entry with changes to sync: what was changed (every queued operation, in order), why it is
 * still here, and the entry's actions aligned to the end below.
 */
@Composable
private fun EntryRow(group: SyncEntryGroup, icon: ImageVector, iconTint: Color, reason: String, actions: @Composable RowScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.Space16, end = Dimens.Space8, top = Dimens.Space12, bottom = Dimens.Space4),
    ) {
        Row(
            modifier = Modifier.padding(end = Dimens.Space8),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Dimens.IconSmall))
            Column(Modifier.weight(1f)) {
                Text(opSummary(group.operations), style = MaterialTheme.typography.bodyLarge)
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space4, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
private fun DestructiveTextButton(label: String, enabled: Boolean, testTag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag(testTag),
    ) { Text(label) }
}

/** "Started timer, Stopped timer": each kind of change once, in the order it was made. */
@Composable
private fun opSummary(operations: List<SyncOperation>): String =
    operations.map { it.type }.distinct().map { opLabel(it) }.joinToString(", ")

@Composable
private fun opLabel(type: OutboxOpType): String = stringResource(
    when (type) {
        OutboxOpType.START -> R.string.sync_op_start
        OutboxOpType.CREATE -> R.string.sync_op_create
        OutboxOpType.STOP -> R.string.sync_op_stop
        OutboxOpType.UPDATE -> R.string.sync_op_update
        OutboxOpType.DELETE -> R.string.sync_op_delete
    },
)

/**
 * Map a raw outbox error to a plain-language reason. The stored [SyncOperation.error] holds
 * implementation strings (exception messages, HTTP status text); this classifies them into a few
 * friendly buckets and always falls back to a generic reassurance rather than leaking raw detail.
 */
internal fun failureReasonRes(error: String?): Int {
    val lower = error?.lowercase().orEmpty()
    return when {
        lower.isBlank() -> R.string.sync_reason_generic
        RateLimitMarker.matches(error) -> R.string.sync_reason_rate_limited
        listOf("offline", "timeout", "unable to resolve host", "connect", "unreachable", "network")
            .any { it in lower } -> R.string.sync_reason_offline
        // The worker's own dead-letter message says "rejected"; match it before the server
        // bucket, whose "server" keyword would otherwise claim it and imply a transient fault.
        listOf("400", "401", "403", "404", "409", "422", "unprocessable", "forbidden", "unauthorized", "bad request", "reject")
            .any { it in lower } -> R.string.sync_reason_rejected
        listOf("500", "502", "503", "504", "server", "gateway", "unavailable")
            .any { it in lower } -> R.string.sync_reason_server
        else -> R.string.sync_reason_generic
    }
}

/** A queued change only earns an explanation once the worker has tried it and written why it is still waiting. */
internal fun pendingReasonRes(op: SyncOperation): Int =
    if (op.status == EntrySyncStatus.RETRYING && !op.error.isNullOrBlank()) failureReasonRes(op.error) else R.string.sync_pending_item

/** The reason for an entry's waiting changes: the first that has been tried and is backing off. */
internal fun pendingReasonRes(group: SyncEntryGroup): Int =
    group.operations.firstOrNull { it.status == EntrySyncStatus.RETRYING && !it.error.isNullOrBlank() }
        ?.let(::pendingReasonRes)
        ?: R.string.sync_pending_item

@Composable
private fun relativeTimeText(timeMs: Long?, neverRes: Int, nowMs: Long): String = when (RelativeTime.bucketOf(timeMs, nowMs)) {
    RelativeTime.Bucket.Never -> stringResource(neverRes)
    RelativeTime.Bucket.JustNow -> stringResource(R.string.sync_time_just_now)
    RelativeTime.Bucket.Minutes -> stringResource(R.string.sync_time_minutes, RelativeTime.amount(timeMs, nowMs))
    RelativeTime.Bucket.Hours -> stringResource(R.string.sync_time_hours, RelativeTime.amount(timeMs, nowMs))
    RelativeTime.Bucket.Days -> {
        val days = RelativeTime.amount(timeMs, nowMs)
        pluralStringResource(R.plurals.sync_time_days, days, days)
    }
}

@Composable
private fun rememberAbsoluteTime(timeMs: Long): String =
    remember(timeMs) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMs)) }
