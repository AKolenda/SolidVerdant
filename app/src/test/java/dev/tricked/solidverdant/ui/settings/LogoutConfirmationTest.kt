/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tricked.solidverdant.data.local.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B3: logout wipes the device's data, so it asks first and says how many changes would be lost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogoutConfirmationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var logouts = 0

    private fun showSettings(unsyncedChanges: Int) {
        composeRule.setContent {
            MaterialTheme {
                SettingsContent(
                    user = null,
                    memberships = emptyList(),
                    currentMembership = null,
                    canSwitchOrganization = false,
                    serverEndpoint = "https://time.example",
                    clientId = "client",
                    appTheme = AppThemeMode.SYSTEM,
                    alwaysShowNotifications = false,
                    optimisticRefresh = false,
                    liveUpdateEnabled = false,
                    autoClearEntryFieldsAfterStop = true,
                    clearDescriptionAfterStop = false,
                    longTimerHours = 8,
                    onMembershipChange = {},
                    onAppThemeChange = {},
                    onAlwaysShowNotificationsChange = {},
                    onOptimisticRefreshChange = {},
                    onLiveUpdateEnabledChange = {},
                    onAutoClearEntryFieldsAfterStopChange = {},
                    onClearDescriptionAfterStopChange = {},
                    onLongTimerHoursChange = {},
                    onOpenReview = {},
                    onOpenReminderSettings = {},
                    onOpenManageTemplates = {},
                    onOpenSyncCenter = {},
                    onOpenPrivacy = {},
                    onLogout = { logouts += 1 },
                    liveUpdatesSupported = false,
                    systemLiveUpdatesEnabled = false,
                    onRequestNotificationPermission = {},
                    unsyncedChanges = unsyncedChanges,
                )
            }
        }
        composeRule.onNodeWithTag(SettingsTestTags.LOGOUT_BUTTON).performScrollTo().performClick()
    }

    @Test
    fun one_tap_no_longer_logs_out_and_cancel_keeps_the_session() {
        showSettings(unsyncedChanges = 0)

        assertEquals(0, logouts)
        composeRule.onNodeWithText("Log out?").assertExists()
        composeRule.onNodeWithTag(SettingsTestTags.LOGOUT_CANCEL).performClick()

        composeRule.onNodeWithTag(SettingsTestTags.LOGOUT_CONFIRM).assertDoesNotExist()
        assertEquals(0, logouts)
    }

    @Test
    fun confirming_logs_out() {
        showSettings(unsyncedChanges = 0)

        composeRule.onNodeWithTag(SettingsTestTags.LOGOUT_CONFIRM).performClick()

        assertEquals(1, logouts)
    }

    @Test
    fun waiting_changes_are_counted_in_the_warning() {
        showSettings(unsyncedChanges = 3)

        composeRule.onNodeWithText("Log out and lose 3 changes?").assertExists()
        composeRule.onNodeWithText("Log out anyway").assertExists()
        assertEquals(0, logouts)
    }
}
