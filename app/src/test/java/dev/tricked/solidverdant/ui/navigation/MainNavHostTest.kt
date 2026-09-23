/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The side menu: ☰ opens it, choosing an item switches destination and closes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainNavHostTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    @Composable
    private fun Destination(label: String) {
        Column {
            MainMenuButton()
            Text(label)
        }
    }

    @Composable
    private fun Host(reviewBadgeCount: Int = 0) {
        navController = rememberNavController()
        MainNavHost(
            navController = navController,
            trackContent = { Destination("TRACK_CONTENT") },
            calendarContent = { Destination("CALENDAR_CONTENT") },
            statsContent = { Destination("STATS_CONTENT") },
            settingsContent = { Destination("SETTINGS_CONTENT") },
            reviewContent = { Destination("REVIEW_CONTENT") },
            reviewBadgeCount = reviewBadgeCount,
            menuHeader = { Text("MENU_HEADER") },
            syncCenterContent = { Destination("SYNC_CENTER_CONTENT") },
        )
    }

    private fun choose(screen: Screen) {
        composeRule.onNodeWithTag(MAIN_MENU_BUTTON_TAG).performClick()
        composeRule.onNode(hasTestTag(mainNavTag(screen.route))).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun menuButtonOpensTheMenuWithItsHeader() {
        composeRule.setContent { Host() }
        composeRule.onNodeWithText("TRACK_CONTENT").assertIsDisplayed()

        composeRule.onNodeWithTag(MAIN_MENU_BUTTON_TAG).performClick()

        composeRule.onNodeWithText("MENU_HEADER").assertIsDisplayed()
        composeRule.onNode(hasTestTag(mainNavTag(Screen.Track.route))).assertIsSelected()
    }

    @Test
    fun choosingMenuItemsShowsEachDestination() {
        composeRule.setContent { Host() }

        choose(Screen.Calendar)
        composeRule.onNodeWithText("CALENDAR_CONTENT").assertIsDisplayed()
        choose(Screen.Stats)
        composeRule.onNodeWithText("STATS_CONTENT").assertIsDisplayed()
        choose(Screen.Review)
        composeRule.onNodeWithText("REVIEW_CONTENT").assertIsDisplayed()
        choose(Screen.Settings)
        composeRule.onNodeWithText("SETTINGS_CONTENT").assertIsDisplayed()
        choose(Screen.Track)
        composeRule.onNodeWithText("TRACK_CONTENT").assertIsDisplayed()
    }

    @Test
    fun syncCenterKeepsSettingsSelectedAndChoosingSettingsAgainReturnsToItsRoot() {
        composeRule.setContent { Host() }
        choose(Screen.Settings)
        composeRule.runOnIdle { navController.navigate(SyncRoutes.SYNC_CENTER) }
        composeRule.onNodeWithText("SYNC_CENTER_CONTENT").assertIsDisplayed()

        composeRule.onNodeWithTag(MAIN_MENU_BUTTON_TAG).performClick()
        composeRule.onNode(hasTestTag(mainNavTag(Screen.Settings.route))).assertIsSelected().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("SETTINGS_CONTENT").assertIsDisplayed()
        composeRule.onNodeWithText("SYNC_CENTER_CONTENT").assertDoesNotExist()
    }

    @Test
    fun deepLinkNavigationToCalendarSelectsCalendar() {
        composeRule.setContent { Host() }
        composeRule.runOnIdle { navController.navigateToMenuDestination(Screen.Calendar.route) }
        composeRule.onNodeWithText("CALENDAR_CONTENT").assertIsDisplayed()

        composeRule.onNodeWithTag(MAIN_MENU_BUTTON_TAG).performClick()
        composeRule.onNode(hasTestTag(mainNavTag(Screen.Calendar.route))).assertIsSelected()
    }

    @Test
    fun openReviewItemsShowTheirCountOnTheReviewItem() {
        composeRule.setContent { Host(reviewBadgeCount = 3) }

        // The ☰ dot is visual only, so the button's label carries the count for screen readers.
        composeRule.onNodeWithContentDescription("Open menu, Review, 3 items to review").assertExists()
        composeRule.onNodeWithTag(MAIN_MENU_BUTTON_TAG).performClick()

        composeRule.onNodeWithText("3").assertIsDisplayed()
    }
}
