/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.privacy

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.ConfirmDialog
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.settings.LogoutConfirmDialog
import dev.tricked.solidverdant.ui.theme.Dimens

/** Stable tags for the privacy and data screen. */
object PrivacyTestTags {
    const val EXPORT_ROW = "privacy_export_row"
    const val CLEAR_CACHE_ROW = "privacy_clear_cache_row"
    const val LOGOUT_ROW = "privacy_logout_row"
    const val CLEAR_CONFIRM = "privacy_clear_confirm"
    const val SYNC_FIRST_CONFIRM = "privacy_sync_first_confirm"
}

private enum class PrivacyDialog { CLEAR, SYNC_FIRST, LOGOUT }

/**
 * Privacy & data-management screen (roadmap #48). Explains what the app stores locally, what leaves
 * the device, how credentials are protected, and which optional permissions do what — then offers
 * the three data controls: export diagnostics (#49), clear the re-syncable cache, and log out /
 * revoke the local session. Destructive actions are red and confirm first; clearing the cache is
 * refused while changes are still waiting to sync. Status is communicated with text and icons,
 * never color alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun PrivacyScreen(onBack: () -> Unit = {}, onLogout: () -> Unit = {}) {
    val viewModel: PrivacyViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by rememberSaveable { mutableStateOf<PrivacyDialog?>(null) }

    LaunchedEffect(state.clearOutcome) {
        when (state.clearOutcome) {
            PrivacyViewModel.ClearOutcome.CLEARED ->
                snackbarHostState.showSnackbar(context.getString(R.string.sweep_privacy_cleared))
            PrivacyViewModel.ClearOutcome.FAILED ->
                snackbarHostState.showSnackbar(context.getString(R.string.sweep_privacy_clear_failed))
            // Changes arrived while the confirmation was open: explain instead of clearing.
            PrivacyViewModel.ClearOutcome.BLOCKED -> dialog = PrivacyDialog.SYNC_FIRST
            null -> return@LaunchedEffect
        }
        viewModel.consumeClearOutcome()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.privacy_navigate_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.Space8, bottom = Dimens.Space24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space24),
        ) {
            GroupedSection(
                header = stringResource(R.string.privacy_stored_title),
                footer = stringResource(R.string.privacy_source_legend),
            ) {
                InfoRow(Icons.Outlined.CloudDownload, stringResource(R.string.privacy_stored_solidtime))
                GroupedDivider(inset = Dimens.SettingsIconInset)
                InfoRow(Icons.Outlined.PhoneAndroid, stringResource(R.string.privacy_stored_local))
                GroupedDivider(inset = Dimens.SettingsIconInset)
                InfoRow(Icons.Outlined.Key, stringResource(R.string.privacy_stored_tokens))
            }

            GroupedSection(
                header = stringResource(R.string.privacy_sent_title),
                footer = stringResource(R.string.privacy_sent_body),
            ) {
                GroupedRow(
                    title = stringResource(R.string.privacy_sent_endpoint_label),
                    leadingIcon = Icons.Outlined.Dns,
                    value = state.serverHost.ifBlank { stringResource(R.string.privacy_sent_endpoint_unknown) },
                )
            }

            GroupedSection(header = stringResource(R.string.privacy_tokens_title)) {
                InfoRow(Icons.Outlined.Lock, stringResource(R.string.privacy_tokens_body))
            }

            GroupedSection(header = stringResource(R.string.privacy_permissions_title)) {
                InfoRow(Icons.Outlined.Notifications, stringResource(R.string.privacy_permissions_notifications))
                GroupedDivider(inset = Dimens.SettingsIconInset)
                InfoRow(Icons.Outlined.CalendarMonth, stringResource(R.string.privacy_permissions_calendar))
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(R.string.privacy_permissions_open_settings),
                    leadingIcon = Icons.Outlined.AdminPanelSettings,
                    onClick = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData("package:${context.packageName}".toUri()),
                                    )
                                }
                            }
                    },
                )
            }

            GroupedSection(
                header = stringResource(R.string.privacy_storage_title),
                footer = stringResource(R.string.privacy_storage_note),
            ) {
                val computing = stringResource(R.string.privacy_storage_computing)
                GroupedRow(
                    title = stringResource(R.string.privacy_storage_database),
                    leadingIcon = Icons.Outlined.Storage,
                    value = if (state.computingStorage) computing else ByteSizeFormatter.format(state.dbBytes),
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(R.string.privacy_storage_cache),
                    leadingIcon = Icons.Outlined.FolderZip,
                    value = if (state.computingStorage) computing else ByteSizeFormatter.format(state.cacheBytes),
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(R.string.privacy_storage_total),
                    leadingIcon = Icons.Outlined.DataUsage,
                    value = if (state.computingStorage) computing else ByteSizeFormatter.format(state.totalBytes),
                )
            }

            GroupedSection(header = stringResource(R.string.privacy_actions_title)) {
                GroupedRow(
                    title = stringResource(R.string.privacy_action_export),
                    subtitle = stringResource(R.string.privacy_action_export_note),
                    leadingIcon = Icons.Outlined.IosShare,
                    onClick = if (state.exporting) {
                        null
                    } else {
                        {
                            viewModel.exportDiagnostics { uri ->
                                runCatching { context.startActivity(viewModel.shareIntentFor(uri)) }
                            }
                        }
                    },
                    showChevron = false,
                    trailing = if (state.exporting) ({ RowProgress() }) else null,
                    modifier = Modifier.testTag(PrivacyTestTags.EXPORT_ROW),
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(R.string.privacy_action_clear_cache),
                    subtitle = stringResource(R.string.privacy_action_clear_cache_note),
                    leadingIcon = Icons.Outlined.DeleteSweep,
                    destructive = true,
                    onClick = if (state.clearingCache) {
                        null
                    } else {
                        { dialog = if (state.unsyncedChanges > 0) PrivacyDialog.SYNC_FIRST else PrivacyDialog.CLEAR }
                    },
                    trailing = if (state.clearingCache) ({ RowProgress() }) else null,
                    modifier = Modifier.testTag(PrivacyTestTags.CLEAR_CACHE_ROW),
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(R.string.privacy_action_logout),
                    subtitle = stringResource(R.string.privacy_action_logout_note),
                    leadingIcon = Icons.AutoMirrored.Outlined.Logout,
                    destructive = true,
                    onClick = { dialog = PrivacyDialog.LOGOUT },
                    modifier = Modifier.testTag(PrivacyTestTags.LOGOUT_ROW),
                )
            }
        }
    }

    when (dialog) {
        PrivacyDialog.CLEAR -> ConfirmDialog(
            title = stringResource(R.string.privacy_clear_dialog_title),
            message = stringResource(R.string.privacy_clear_dialog_message),
            confirmLabel = stringResource(R.string.privacy_clear_dialog_confirm),
            onConfirm = {
                dialog = null
                viewModel.clearCache()
            },
            onDismiss = { dialog = null },
            destructive = true,
            confirmTestTag = PrivacyTestTags.CLEAR_CONFIRM,
        )
        PrivacyDialog.SYNC_FIRST -> {
            val waiting = state.unsyncedChanges.coerceAtLeast(1)
            ConfirmDialog(
                title = pluralStringResource(R.plurals.sweep_privacy_clear_blocked_title, waiting, waiting),
                message = pluralStringResource(R.plurals.sweep_privacy_clear_blocked_message, waiting, waiting),
                confirmLabel = stringResource(R.string.sync_now),
                onConfirm = {
                    dialog = null
                    viewModel.syncNow()
                },
                onDismiss = { dialog = null },
                confirmTestTag = PrivacyTestTags.SYNC_FIRST_CONFIRM,
            )
        }
        PrivacyDialog.LOGOUT -> LogoutConfirmDialog(
            unsyncedChanges = state.unsyncedChanges,
            onConfirm = {
                dialog = null
                onLogout()
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/** An explanatory line in a grouped section: icon and body text, aligned with the other rows. */
@Composable
private fun InfoRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(horizontal = Dimens.Space16, vertical = Dimens.Space12),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.IconSmall))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RowProgress() {
    Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSmall), strokeWidth = Dimens.Space2)
    }
}
