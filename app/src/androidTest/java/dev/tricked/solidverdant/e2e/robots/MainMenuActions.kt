/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.tricked.solidverdant.e2e.robots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import dev.tricked.solidverdant.e2e.TestTags

/**
 * Side-menu and Calendar overflow actions shared by the flows. Every destination is reached through
 * the ☰ menu, and the Calendar's view modes, overlay and settings live in its ⋮ menu.
 */
private const val MENU_TIMEOUT_MS = 15_000L

private fun ComposeTestRule.tapFirstEnabled(tag: String, timeoutMs: Long) {
    val matcher = hasTestTag(tag) and isEnabled()
    waitUntilAtLeastOneExists(matcher, timeoutMs)
    onAllNodes(matcher, useUnmergedTree = true).onFirst().performClick()
}

/** Open the side menu and choose the destination tagged [navTag], e.g. [TestTags.NAV_CALENDAR]. */
fun ComposeTestRule.openMenuDestination(navTag: String, timeoutMs: Long = MENU_TIMEOUT_MS) {
    tapFirstEnabled(TestTags.MAIN_MENU_BUTTON, timeoutMs)
    tapFirstEnabled(navTag, timeoutMs)
    waitForIdle()
}

/**
 * Open Calendar from the side menu and switch it to [modeTag]. Calendar opens on the single
 * day; most flows address several days, so they ask for the week view. Pass null to keep the day.
 */
fun ComposeTestRule.openCalendarFromMenu(modeTag: String? = TestTags.CALENDAR_MODE_WEEK, timeoutMs: Long = MENU_TIMEOUT_MS) {
    openMenuDestination(TestTags.NAV_CALENDAR, timeoutMs)
    if (modeTag != null) chooseCalendarMenuItem(modeTag, timeoutMs)
}

/** Choose an item from the Calendar's ⋮ menu: a view mode, add break, overlay or settings. */
fun ComposeTestRule.chooseCalendarMenuItem(itemTag: String, timeoutMs: Long = MENU_TIMEOUT_MS) {
    tapFirstEnabled(TestTags.CALENDAR_MORE_ACTIONS, timeoutMs)
    tapFirstEnabled(itemTag, timeoutMs)
    waitForIdle()
}

/** Review left the side menu (its checks are on the history cards); Settings opens it. */
fun ComposeTestRule.openReviewFromSettings(timeoutMs: Long = MENU_TIMEOUT_MS) {
    openMenuDestination(TestTags.NAV_SETTINGS, timeoutMs)
    val row = hasTestTag(TestTags.SETTINGS_REVIEW_ROW)
    waitUntilAtLeastOneExists(row, timeoutMs)
    onAllNodes(row, useUnmergedTree = true).onFirst().performScrollTo().performClick()
    waitForIdle()
}
