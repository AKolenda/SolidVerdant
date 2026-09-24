/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.calendar.DeviceCalendar
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.GroupedSwitchRow
import dev.tricked.solidverdant.ui.theme.Dimens

/**
 * Opt-in device-calendar overlay controls as grouped sections: the on/off switch with the privacy
 * note, the runtime-permission education and recovery states, and the per-calendar toggles with
 * the overlay's status beneath them. All privacy-sensitive language lives here so the user
 * understands access is local and read-only (FEATURE_GAP_ANALYSIS.md #22/#77).
 */
@Composable
fun CalendarOverlayControls(
    state: CalendarUiState,
    showRationale: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onToggleCalendar: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Space16)) {
        GroupedSection(footer = stringResource(R.string.calendar_overlay_privacy)) {
            GroupedSwitchRow(
                title = stringResource(R.string.calendar_overlay_show),
                checked = state.overlayEnabled,
                onCheckedChange = onToggleOverlay,
                leadingIcon = Icons.Outlined.Event,
                modifier = Modifier.testTag(CalendarTestTags.OVERLAY_TOGGLE),
            )
        }

        if (state.overlayEnabled) {
            when {
                !state.hasCalendarPermission -> PermissionSection(
                    permanentlyDenied = state.permissionRequested && !showRationale,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                )

                state.calendarListLoading -> GroupedSection(modifier = Modifier.testTag(CalendarTestTags.OVERLAY_CALENDAR_LOADING)) {
                    GroupedRow(
                        title = stringResource(R.string.calendar_overlay_calendars_loading),
                        trailing = { SmallProgress() },
                    )
                }

                state.calendarListError -> GroupedSection(
                    footer = stringResource(R.string.calendar_overlay_calendars_error),
                    modifier = Modifier.testTag(CalendarTestTags.OVERLAY_CALENDAR_ERROR),
                ) {
                    RetryRow(onRetry = onRetry, modifier = Modifier.testTag(CalendarTestTags.OVERLAY_RETRY))
                }

                state.availableCalendars.isEmpty() -> OverlayNote(stringResource(R.string.calendar_overlay_no_calendars))

                else -> CalendarPickerSection(state = state, onToggleCalendar = onToggleCalendar, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun PermissionSection(permanentlyDenied: Boolean, onRequestPermission: () -> Unit, onOpenAppSettings: () -> Unit) {
    GroupedSection(
        footer = stringResource(
            if (permanentlyDenied) R.string.calendar_overlay_denied else R.string.calendar_overlay_permission_rationale,
        ),
    ) {
        if (permanentlyDenied) {
            // Leaves the app for system settings, so it keeps the chevron.
            GroupedRow(
                title = stringResource(R.string.calendar_overlay_open_settings),
                leadingIcon = Icons.Outlined.Settings,
                onClick = onOpenAppSettings,
            )
        } else {
            GroupedRow(
                title = stringResource(R.string.calendar_overlay_grant),
                leadingIcon = Icons.Outlined.LockOpen,
                showChevron = false,
                onClick = onRequestPermission,
            )
        }
    }
}

/** Every device calendar as a toggle row, with the overlay's current state as the footer. */
@Composable
private fun CalendarPickerSection(state: CalendarUiState, onToggleCalendar: (String) -> Unit, onRetry: () -> Unit) {
    val selectedCount = state.selectedCalendarIds.size
    val status = when {
        state.selectedCalendarIds.isEmpty() -> R.string.calendar_overlay_none_selected
        state.overlayLoading -> R.string.calendar_overlay_loading
        state.overlayError -> R.string.calendar_overlay_error
        state.overlayEvents.isEmpty() -> R.string.calendar_overlay_empty
        else -> R.string.calendar_overlay_showing
    }
    GroupedSection(
        header = pluralStringResource(R.plurals.calendar_overlay_choose_count, selectedCount, selectedCount),
        footer = stringResource(status),
    ) {
        state.availableCalendars.forEachIndexed { index, calendar ->
            if (index > 0) GroupedDivider(inset = Dimens.SettingsIconInset)
            OverlayCalendarRow(
                calendar = calendar,
                checked = calendar.id in state.selectedCalendarIds,
                onToggle = { onToggleCalendar(calendar.id) },
            )
        }
        if (status == R.string.calendar_overlay_error) {
            GroupedDivider(inset = Dimens.SettingsIconInset)
            RetryRow(onRetry = onRetry)
        }
    }
}

/**
 * One device calendar: its colour where grouped rows have their icon, the name and account, and a
 * switch. The whole row toggles, like [GroupedSwitchRow].
 */
@Composable
private fun OverlayCalendarRow(calendar: DeviceCalendar, checked: Boolean, onToggle: () -> Unit) {
    val description = stringResource(R.string.calendar_overlay_calendar_desc, calendar.displayName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            .padding(horizontal = Dimens.Space16, vertical = Dimens.Space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        Box(modifier = Modifier.size(Dimens.IconSmall), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(Dimens.ProjectDot)
                    .clip(CircleShape)
                    .background(calendar.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = calendar.displayName.ifBlank { calendar.accountName },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (calendar.accountName.isNotBlank()) {
                Text(
                    text = calendar.accountName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            // The same colours as GroupedSwitchRow, so every switch in the sheet matches.
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun RetryRow(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GroupedRow(
        title = stringResource(R.string.calendar_overlay_retry),
        leadingIcon = Icons.Default.Refresh,
        showChevron = false,
        onClick = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun SmallProgress() {
    CircularProgressIndicator(modifier = Modifier.size(Dimens.IconSmall), strokeWidth = Dimens.Space2)
}

/** A status line on its own, styled and inset like a grouped section's footer. */
@Composable
private fun OverlayNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Space16 + Dimens.Space16),
    )
}
