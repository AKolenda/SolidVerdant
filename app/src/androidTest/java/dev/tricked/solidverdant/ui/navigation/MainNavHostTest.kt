/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test

class MainNavHostTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    @Composable
    private fun Host() {
        navController = rememberNavController()
        MainNavHost(
            navController = navController,
            trackContent = { Text("TRACK_CONTENT") },
            calendarContent = { Text("CALENDAR_CONTENT") },
            statsContent = { Text("STATS_CONTENT") },
            settingsContent = { Text("SETTINGS_CONTENT") },
            syncCenterContent = { Text("SYNC_CENTER_CONTENT") },
        )
    }

    @Test
    fun tappingDashboardAndSettingsTabsShowsTheirContent() {
        composeRule.setContent { Host() }
        composeRule.onNodeWithText("TRACK_CONTENT").assertIsDisplayed()

        composeRule.onNode(hasTestTag(mainNavTag(Screen.Stats.route))).performClick()
        composeRule.onNodeWithText("STATS_CONTENT").assertIsDisplayed()

        composeRule.onNode(hasTestTag(mainNavTag(Screen.Settings.route))).performClick()
        composeRule.onNodeWithText("SETTINGS_CONTENT").assertIsDisplayed()
    }

    @Test
    fun calendarPushedFromTimerKeepsTimerSelectedAndSurvivesTabSwitches() {
        composeRule.setContent { Host() }
        composeRule.runOnIdle { navController.navigate(TimerRoutes.CALENDAR) }
        composeRule.onNodeWithText("CALENDAR_CONTENT").assertIsDisplayed()
        composeRule.onNode(hasTestTag(mainNavTag(Screen.Track.route))).assertIsSelected()

        composeRule.onNode(hasTestTag(mainNavTag(Screen.Settings.route))).performClick()
        composeRule.onNodeWithText("SETTINGS_CONTENT").assertIsDisplayed()

        composeRule.onNode(hasTestTag(mainNavTag(Screen.Track.route))).performClick()
        composeRule.onNodeWithText("CALENDAR_CONTENT").assertIsDisplayed()

        composeRule.onNode(hasTestTag(mainNavTag(Screen.Track.route))).performClick()
        composeRule.onNodeWithText("TRACK_CONTENT").assertIsDisplayed()
        composeRule.onNodeWithText("CALENDAR_CONTENT").assertDoesNotExist()
    }

    @Test
    fun syncCenterOpenedFromSettingsKeepsSettingsSelected() {
        composeRule.setContent { Host() }
        composeRule.onNode(hasTestTag(mainNavTag(Screen.Settings.route))).performClick()
        composeRule.runOnIdle { navController.navigate(SyncRoutes.SYNC_CENTER) }
        composeRule.onNodeWithText("SYNC_CENTER_CONTENT").assertIsDisplayed()
        composeRule.onNode(hasTestTag(mainNavTag(Screen.Settings.route))).assertIsSelected()

        composeRule.onNode(hasTestTag(mainNavTag(Screen.Track.route))).performClick()
        composeRule.onNodeWithText("TRACK_CONTENT").assertIsDisplayed()
    }
}
