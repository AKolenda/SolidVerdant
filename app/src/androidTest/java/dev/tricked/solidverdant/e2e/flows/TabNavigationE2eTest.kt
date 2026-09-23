/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.e2e.flows

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import dev.tricked.solidverdant.e2e.E2eRule
import dev.tricked.solidverdant.e2e.TestTags
import dev.tricked.solidverdant.e2e.robots.TrackRobot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every tab, plus Calendar and Review pushed from the Timer header, must compose against real
 * (stress-sized) data without crashing, and returning to Timer must restore the history. Guards the nav graph and each screen's
 * initial composition — the cheapest way to catch "screen X dies on launch" regressions.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TabNavigationE2eTest {

    @get:Rule
    val e2e = E2eRule(this)

    @Test
    fun allTabsRenderAgainstStressDataAndTrackRestores() {
        e2e.requireMockBackend().presetStressWorld(entryCount = 60)
        e2e.launchApp()
        TrackRobot(e2e.composeRule).waitForHistory()

        tap(TestTags.TRACK_OPEN_CALENDAR)
        // Renders either the month grid (day-cell-<date>) or the week view (week-day-*).
        waitForTagPrefix("day-cell-", "week-day-")

        tap(TestTags.NAV_DASHBOARD)
        waitForTag(TestTags.STATS_SCREEN)

        tap(TestTags.NAV_SETTINGS)
        waitForTag(TestTags.SETTINGS_SCREEN)

        // Timer restores its pushed Calendar; tapping Timer again pops back to its root.
        tap(TestTags.NAV_TIMER)
        waitForTagPrefix("day-cell-", "week-day-")
        tap(TestTags.NAV_TIMER)
        waitForTag(TestTags.TRACK_HISTORY_LIST)

        tap(TestTags.TRACK_OPEN_REVIEW)
        waitForTag("review_more_actions")

        tap(TestTags.NAV_TIMER)
        waitForTag(TestTags.TRACK_HISTORY_LIST)
    }

    private fun tap(tag: String) {
        e2e.composeRule.onAllNodes(hasTestTag(tag)).onFirst().performClick()
        e2e.composeRule.waitForIdle()
    }

    private fun waitForTag(tag: String) {
        e2e.composeRule.waitUntil(WAIT_MS) {
            e2e.composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTagPrefix(vararg prefixes: String) {
        val matcher = SemanticsMatcher("testTag starts with one of ${prefixes.toList()}") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            tag != null && prefixes.any(tag::startsWith)
        }
        e2e.composeRule.waitUntil(WAIT_MS) {
            e2e.composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    companion object {
        private const val WAIT_MS = 10_000L
    }
}
