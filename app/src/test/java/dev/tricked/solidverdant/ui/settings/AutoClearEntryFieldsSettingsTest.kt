/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoClearEntryFieldsSettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun description_only_setting_is_shown_when_full_auto_clear_is_disabled() {
        var enabled = false
        composeRule.setContent {
            MaterialTheme {
                AutoClearEntryFieldsSettings(
                    autoClearEntryFieldsAfterStop = false,
                    clearDescriptionAfterStop = false,
                    onAutoClearEntryFieldsAfterStopChange = {},
                    onClearDescriptionAfterStopChange = { enabled = it },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.CLEAR_DESCRIPTION_AFTER_STOP_SWITCH)
            .assertIsDisplayed()
            .performClick()

        assertTrue(enabled)
    }

    @Test
    fun description_only_setting_is_hidden_when_full_auto_clear_is_enabled() {
        composeRule.setContent {
            MaterialTheme {
                AutoClearEntryFieldsSettings(
                    autoClearEntryFieldsAfterStop = true,
                    clearDescriptionAfterStop = true,
                    onAutoClearEntryFieldsAfterStopChange = {},
                    onClearDescriptionAfterStopChange = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.CLEAR_DESCRIPTION_AFTER_STOP_SWITCH).assertDoesNotExist()
    }
}
