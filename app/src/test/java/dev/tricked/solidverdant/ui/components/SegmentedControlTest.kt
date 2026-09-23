/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import dev.tricked.solidverdant.ui.theme.Dimens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegmentedControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = listOf("a", "b", "c")
    private val taps = mutableListOf<String>()

    private fun setControl(initial: String) {
        composeRule.setContent {
            var selected by remember { mutableStateOf(initial) }
            MaterialTheme {
                SegmentedControl(
                    options = options,
                    selected = selected,
                    onSelect = {
                        taps += it
                        selected = it
                    },
                    label = { it.uppercase() },
                    optionTestTag = { "segment_$it" },
                )
            }
        }
    }

    @Test
    fun tappingASegmentSelectsItAndDeselectsTheOthers() {
        setControl("a")
        composeRule.onNodeWithTag("segment_a").assertIsSelected()

        composeRule.onNodeWithTag("segment_c").performClick()

        composeRule.onNodeWithTag("segment_c").assertIsSelected()
        composeRule.onNodeWithTag("segment_a").assertIsNotSelected()
        composeRule.onNodeWithTag("segment_b").assertIsNotSelected()
        assertEquals(listOf("c"), taps)
    }

    @Test
    fun tappingTheSelectedSegmentReportsItAgain() {
        setControl("b")
        composeRule.onNodeWithTag("segment_b").performClick()
        assertEquals(listOf("b"), taps)
    }

    @Test
    fun segmentsAreTabsInASelectableGroupWithFullTouchHeight() {
        setControl("a")
        composeRule.onNodeWithTag("segment_b")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertHeightIsAtLeast(Dimens.MinTouchTarget)
        composeRule.onNodeWithTag("segment_b").onParent()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
    }
}
