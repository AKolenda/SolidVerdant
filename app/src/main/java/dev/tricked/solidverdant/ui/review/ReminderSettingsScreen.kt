/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.GroupedSwitchRow
import dev.tricked.solidverdant.ui.components.LoadingState
import dev.tricked.solidverdant.ui.theme.Dimens

/**
 * Configures the tracking reminder and the end-of-day review (gap analysis #4, #18, #78).
 *
 * Edits the foundation DataStore keys via [ReminderSettingsViewModel] (enabled toggles + reminder
 * time) and re-evaluates the WorkManager schedule after each change. Surfaces the loading state,
 * a denied-notification-permission recovery path, and explains why the time control is unavailable
 * when no reminder is enabled. The reminder time persists across logout like other preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(onBack: () -> Unit = {}) {
    val viewModel: ReminderSettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberNotificationPermissionState()
    val context = LocalContext.current
    val timeFormatter = rememberTimeOfDayFormatter()
    var showTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reminder_settings_title)) },
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
    ) { innerPadding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(innerPadding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(top = Dimens.Space8, bottom = Dimens.Space24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space24),
        ) {
            if (state.anyEnabled && !permission.hasPermission) {
                // Without the permission nothing below can be delivered, so the fix comes first.
                GroupedSection(
                    header = stringResource(R.string.reminder_permission_warning_title),
                    footer = stringResource(R.string.reminder_permission_warning_body),
                ) {
                    GroupedRow(
                        title = stringResource(R.string.reminder_permission_allow),
                        leadingIcon = Icons.Outlined.NotificationsOff,
                        onClick = permission.request,
                        showChevron = false,
                        modifier = Modifier.testTag(ReviewTestTags.REMINDER_ALLOW_NOTIFICATIONS),
                    )
                    GroupedDivider(inset = Dimens.SettingsIconInset)
                    GroupedRow(
                        title = stringResource(R.string.reminder_permission_open_settings),
                        leadingIcon = Icons.Outlined.Settings,
                        onClick = {
                            val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }
            }

            GroupedSection(
                header = stringResource(R.string.reminder_section_title),
                footer = stringResource(R.string.reminder_best_effort_note),
            ) {
                GroupedSwitchRow(
                    title = stringResource(R.string.reminder_daily_title),
                    subtitle = stringResource(R.string.reminder_daily_subtitle),
                    leadingIcon = Icons.Outlined.NotificationsActive,
                    checked = state.reminderEnabled,
                    onCheckedChange = { viewModel.setReminderEnabled(it) },
                    modifier = Modifier.testTag(ReviewTestTags.REMINDER_DAILY_SWITCH),
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedSwitchRow(
                    title = stringResource(R.string.reminder_eod_title),
                    subtitle = stringResource(R.string.reminder_eod_subtitle),
                    leadingIcon = Icons.Outlined.EventAvailable,
                    checked = state.endOfDayReviewEnabled,
                    onCheckedChange = { viewModel.setEndOfDayReviewEnabled(it) },
                    modifier = Modifier.testTag(ReviewTestTags.REMINDER_EOD_SWITCH),
                )
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedRow(
                    title = stringResource(R.string.reminder_time_title),
                    subtitle = if (state.anyEnabled) null else stringResource(R.string.reminder_time_disabled_hint),
                    leadingIcon = Icons.Outlined.Schedule,
                    value = if (state.anyEnabled) formatMinuteOfDay(state.minuteOfDay, timeFormatter) else null,
                    onClick = if (state.anyEnabled) ({ showTimePicker = true }) else null,
                    modifier = Modifier.testTag(ReviewTestTags.REMINDER_TIME_ROW),
                )
            }
        }
    }

    if (showTimePicker) {
        ReviewTimePickerDialog(
            title = stringResource(R.string.reminder_time_dialog_title),
            initialHour = state.minuteOfDay / MINUTES_PER_HOUR,
            initialMinute = state.minuteOfDay % MINUTES_PER_HOUR,
            onConfirm = { hour, minute ->
                showTimePicker = false
                viewModel.setReminderTime(hour, minute)
            },
            onDismiss = { showTimePicker = false },
            confirmTestTag = ReviewTestTags.REMINDER_TIME_CONFIRM,
        )
    }
}

private const val MINUTES_PER_HOUR = 60
