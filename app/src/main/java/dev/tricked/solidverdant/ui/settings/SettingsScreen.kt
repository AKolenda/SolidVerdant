/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.local.AppThemeMode
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.service.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS
import dev.tricked.solidverdant.service.LIVE_UPDATES_API_LEVEL
import dev.tricked.solidverdant.service.canPostPromotedNotifications
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.GroupedSwitchRow
import dev.tricked.solidverdant.ui.components.OptionPickerDialog
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.navigation.LocalFloatingBarInset
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.labelRes
import dev.tricked.solidverdant.ui.theme.selectableThemeModes
import dev.tricked.solidverdant.util.NotificationPermissionHelper
import java.time.DayOfWeek
import java.time.format.TextStyle

private enum class SettingsPicker { THEME, LONG_TIMER, ORGANIZATION }

/** Settings tab: account, appearance, timer behaviour, review tools, sync, server and about. */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun SettingsScreen(
    user: User?,
    memberships: List<Membership>,
    currentMembership: Membership?,
    canSwitchOrganization: Boolean,
    serverEndpoint: String,
    clientId: String,
    appTheme: AppThemeMode,
    alwaysShowNotifications: Boolean,
    optimisticRefresh: Boolean,
    liveUpdateEnabled: Boolean,
    autoClearEntryFieldsAfterStop: Boolean,
    clearDescriptionAfterStop: Boolean,
    longTimerHours: Int,
    onMembershipChange: (Membership) -> Unit,
    onAppThemeChange: (AppThemeMode) -> Unit,
    onAlwaysShowNotificationsChange: (Boolean) -> Unit,
    onOptimisticRefreshChange: (Boolean) -> Unit,
    onLiveUpdateEnabledChange: (Boolean) -> Unit,
    onAutoClearEntryFieldsAfterStopChange: (Boolean) -> Unit,
    onClearDescriptionAfterStopChange: (Boolean) -> Unit,
    onLongTimerHoursChange: (Int) -> Unit,
    onOpenReminderSettings: () -> Unit,
    onOpenManageTemplates: () -> Unit,
    onOpenSyncCenter: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val liveUpdatesSupported = Build.VERSION.SDK_INT >= LIVE_UPDATES_API_LEVEL
    var systemLiveUpdatesEnabled by remember(context, liveUpdatesSupported) {
        mutableStateOf(canPostPromotedNotifications(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner, liveUpdatesSupported) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemLiveUpdatesEnabled = canPostPromotedNotifications(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // SV-014: POST_NOTIFICATIONS is only requested from an explicit opt-in on this screen.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val requestNotificationsIfNeeded = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationPermissionHelper.hasNotificationPermission(context)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SettingsContent(
        user = user,
        memberships = memberships,
        currentMembership = currentMembership,
        canSwitchOrganization = canSwitchOrganization,
        serverEndpoint = serverEndpoint,
        clientId = clientId,
        appTheme = appTheme,
        alwaysShowNotifications = alwaysShowNotifications,
        optimisticRefresh = optimisticRefresh,
        liveUpdateEnabled = liveUpdateEnabled,
        autoClearEntryFieldsAfterStop = autoClearEntryFieldsAfterStop,
        clearDescriptionAfterStop = clearDescriptionAfterStop,
        longTimerHours = longTimerHours,
        onMembershipChange = onMembershipChange,
        onAppThemeChange = onAppThemeChange,
        onAlwaysShowNotificationsChange = onAlwaysShowNotificationsChange,
        onOptimisticRefreshChange = onOptimisticRefreshChange,
        onLiveUpdateEnabledChange = onLiveUpdateEnabledChange,
        onAutoClearEntryFieldsAfterStopChange = onAutoClearEntryFieldsAfterStopChange,
        onClearDescriptionAfterStopChange = onClearDescriptionAfterStopChange,
        onLongTimerHoursChange = onLongTimerHoursChange,
        onOpenReminderSettings = onOpenReminderSettings,
        onOpenManageTemplates = onOpenManageTemplates,
        onOpenSyncCenter = onOpenSyncCenter,
        onOpenPrivacy = onOpenPrivacy,
        onLogout = onLogout,
        liveUpdatesSupported = liveUpdatesSupported,
        systemLiveUpdatesEnabled = systemLiveUpdatesEnabled,
        onRequestNotificationPermission = requestNotificationsIfNeeded,
    )
}

/** Stateless Settings body; [SettingsScreen] supplies the platform permission/lifecycle state. */
@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun SettingsContent(
    user: User?,
    memberships: List<Membership>,
    currentMembership: Membership?,
    canSwitchOrganization: Boolean,
    serverEndpoint: String,
    clientId: String,
    appTheme: AppThemeMode,
    alwaysShowNotifications: Boolean,
    optimisticRefresh: Boolean,
    liveUpdateEnabled: Boolean,
    autoClearEntryFieldsAfterStop: Boolean,
    clearDescriptionAfterStop: Boolean,
    longTimerHours: Int,
    onMembershipChange: (Membership) -> Unit,
    onAppThemeChange: (AppThemeMode) -> Unit,
    onAlwaysShowNotificationsChange: (Boolean) -> Unit,
    onOptimisticRefreshChange: (Boolean) -> Unit,
    onLiveUpdateEnabledChange: (Boolean) -> Unit,
    onAutoClearEntryFieldsAfterStopChange: (Boolean) -> Unit,
    onClearDescriptionAfterStopChange: (Boolean) -> Unit,
    onLongTimerHoursChange: (Int) -> Unit,
    onOpenReminderSettings: () -> Unit,
    onOpenManageTemplates: () -> Unit,
    onOpenSyncCenter: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onLogout: () -> Unit,
    liveUpdatesSupported: Boolean,
    systemLiveUpdatesEnabled: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    var picker by rememberSaveable { mutableStateOf<SettingsPicker?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = LocalFloatingBarInset.current + Dimens.Space24)
            .testTag(SettingsTestTags.SCREEN),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space24),
    ) {
        Text(
            text = stringResource(R.string.settings_menu),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = Dimens.Space16 + Dimens.Space4, top = Dimens.Space16),
        )

        GroupedSection {
            if (user != null) {
                AccountRow(user)
                GroupedDivider()
            }
            val organizationName = currentMembership?.organization?.name.orEmpty()
            GroupedRow(
                title = stringResource(R.string.settings_organization),
                leadingIcon = Icons.Outlined.Business,
                value = organizationName,
                onClick = if (canSwitchOrganization) ({ picker = SettingsPicker.ORGANIZATION }) else null,
            )
        }

        GroupedSection(header = stringResource(R.string.settings_section_appearance)) {
            GroupedRow(
                title = stringResource(R.string.theme),
                leadingIcon = Icons.Outlined.Palette,
                value = stringResource(appTheme.labelRes),
                onClick = { picker = SettingsPicker.THEME },
                modifier = Modifier.testTag(SettingsTestTags.THEME_ROW),
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(
                title = stringResource(R.string.choose_language),
                leadingIcon = Icons.Outlined.Language,
                onClick = { openLanguageSettings(context) },
            )
        }

        GroupedSection(header = stringResource(R.string.nav_timer)) {
            GroupedRow(
                title = stringResource(R.string.long_timer_warning),
                leadingIcon = Icons.Outlined.Schedule,
                value = pluralStringResource(R.plurals.hours_short, longTimerHours, longTimerHours),
                onClick = { picker = SettingsPicker.LONG_TIMER },
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            AutoClearEntryFieldsSettings(
                autoClearEntryFieldsAfterStop = autoClearEntryFieldsAfterStop,
                clearDescriptionAfterStop = clearDescriptionAfterStop,
                onAutoClearEntryFieldsAfterStopChange = onAutoClearEntryFieldsAfterStopChange,
                onClearDescriptionAfterStopChange = onClearDescriptionAfterStopChange,
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedSwitchRow(
                title = stringResource(R.string.always_show_notifications),
                subtitle = stringResource(R.string.always_show_notifications_description),
                leadingIcon = Icons.Outlined.NotificationsActive,
                checked = alwaysShowNotifications,
                onCheckedChange = { enabled ->
                    if (enabled) onRequestNotificationPermission()
                    onAlwaysShowNotificationsChange(enabled)
                },
            )
            if (liveUpdatesSupported) {
                GroupedDivider(inset = Dimens.SettingsIconInset)
                LiveUpdateSettingRow(
                    enabled = liveUpdateEnabled,
                    systemEnabled = systemLiveUpdatesEnabled,
                    onEnabledChange = { enabled ->
                        if (enabled) onRequestNotificationPermission()
                        onLiveUpdateEnabledChange(enabled)
                    },
                    onOpenSystemSettings = { openLiveUpdateSettings(context) },
                )
            }
        }

        GroupedSection(header = stringResource(R.string.nav_review)) {
            GroupedRow(
                title = stringResource(R.string.review_menu_reminder_settings),
                leadingIcon = Icons.Outlined.NotificationsActive,
                onClick = onOpenReminderSettings,
                modifier = Modifier.testTag(SettingsTestTags.REMINDERS_ROW),
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(
                title = stringResource(R.string.review_menu_manage_templates),
                leadingIcon = Icons.Outlined.Star,
                onClick = onOpenManageTemplates,
                modifier = Modifier.testTag(SettingsTestTags.TEMPLATES_ROW),
            )
        }

        GroupedSection(header = stringResource(R.string.settings_section_data)) {
            GroupedRow(
                title = stringResource(R.string.sync_center_title),
                leadingIcon = Icons.Outlined.CloudSync,
                onClick = onOpenSyncCenter,
                modifier = Modifier.testTag(SettingsTestTags.SYNC_CENTER_ROW),
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedSwitchRow(
                title = stringResource(R.string.optimistic_refresh),
                subtitle = stringResource(R.string.optimistic_refresh_description),
                leadingIcon = Icons.Outlined.Refresh,
                checked = optimisticRefresh,
                onCheckedChange = onOptimisticRefreshChange,
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(
                title = stringResource(R.string.privacy_menu_entry),
                leadingIcon = Icons.Outlined.Lock,
                onClick = onOpenPrivacy,
            )
        }

        GroupedSection(header = stringResource(R.string.server_information)) {
            GroupedRow(
                title = stringResource(R.string.server_endpoint),
                subtitle = serverEndpoint,
                leadingIcon = Icons.Outlined.Dns,
                onClick = { copyToClipboard(context, serverEndpoint) },
                showChevron = false,
            )
            GroupedDivider(inset = Dimens.SettingsIconInset)
            GroupedRow(
                title = stringResource(R.string.client_id),
                subtitle = clientId,
                leadingIcon = Icons.Outlined.Key,
                onClick = { copyToClipboard(context, clientId) },
                showChevron = false,
            )
        }

        AboutSection()

        GroupedSection {
            GroupedRow(
                title = stringResource(R.string.logout),
                leadingIcon = Icons.AutoMirrored.Filled.ExitToApp,
                destructive = true,
                onClick = onLogout,
                modifier = Modifier.testTag(SettingsTestTags.LOGOUT_BUTTON),
            )
        }
    }

    when (picker) {
        SettingsPicker.THEME -> OptionPickerDialog(
            title = stringResource(R.string.theme),
            options = selectableThemeModes(),
            selected = appTheme,
            label = { stringResource(it.labelRes) },
            onSelect = onAppThemeChange,
            onDismiss = { picker = null },
            optionTag = { SettingsTestTags.themeOption(it.name) },
        )
        SettingsPicker.LONG_TIMER -> OptionPickerDialog(
            title = stringResource(R.string.long_timer_warning),
            options = LONG_TIMER_OPTIONS,
            selected = longTimerHours,
            label = { pluralStringResource(R.plurals.hours_short, it, it) },
            onSelect = onLongTimerHoursChange,
            onDismiss = { picker = null },
        )
        SettingsPicker.ORGANIZATION -> OptionPickerDialog(
            title = stringResource(R.string.settings_organization),
            options = memberships,
            selected = currentMembership,
            label = { it.organization.name },
            onSelect = onMembershipChange,
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

@Composable
private fun AccountRow(user: User) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Dimens.Space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var profileImageFailed by remember(user.profilePhotoUrl) { mutableStateOf(false) }
        val profileDescription = stringResource(R.string.profile_picture)
        if (user.profilePhotoUrl.isNotBlank() && !profileImageFailed) {
            AsyncImage(
                model = user.profilePhotoUrl,
                contentDescription = profileDescription,
                onError = { profileImageFailed = true },
                modifier = Modifier.size(Dimens.AvatarSize).clip(CircleShape),
            )
        } else {
            Surface(
                modifier = Modifier.size(Dimens.AvatarSize).semantics { contentDescription = profileDescription },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = user.name.split(Regex("\\s+")).mapNotNull { it.firstOrNull() }
                            .take(2).joinToString("").uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Column(Modifier.padding(start = Dimens.Space12)) {
            Text(user.name, style = MaterialTheme.typography.titleMedium)
            Text(
                user.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val locale = appLocale()
            val weekStart = remember(user.weekStart, locale) {
                runCatching { DayOfWeek.valueOf(user.weekStart.uppercase()) }
                    .getOrNull()?.getDisplayName(TextStyle.FULL, locale)
            }
            val details = listOfNotNull(user.timezone.takeIf { it.isNotBlank() }, weekStart).joinToString(" · ")
            if (details.isNotBlank()) {
                Spacer(Modifier.height(Dimens.Space2))
                Text(
                    details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun openLanguageSettings(context: android.content.Context) {
    val appUri = "package:${context.packageName}".toUri()
    val languageIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, appUri)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
    }
    runCatching { context.startActivity(languageIntent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)) }
}

private fun openLiveUpdateSettings(context: android.content.Context) {
    val intent = Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}

private val LONG_TIMER_OPTIONS = listOf(2, 4, 6, 8, 12)
