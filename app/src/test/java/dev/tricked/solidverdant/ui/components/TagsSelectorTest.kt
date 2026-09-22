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
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.tricked.solidverdant.data.model.Tag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagsSelectorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clearRemovesSelectedTagMissingFromCatalogue() {
        var selected = listOf("archived")
        composeRule.setContent {
            var selectedTags by remember { mutableStateOf(listOf("archived")) }
            MaterialTheme {
                TagsSelector(
                    selectedTagIds = selectedTags,
                    availableTags = emptyList(),
                    onTagsChanged = {
                        selectedTags = it
                        selected = it
                    },
                    enabled = true,
                )
            }
        }

        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_SELECTOR).performClick()
        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_CLEAR).performClick()
        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun searchSelectAndRemoveTagsWithoutHorizontalScrolling() {
        var selected = emptyList<String>()
        composeRule.setContent {
            var selectedTags by remember { mutableStateOf(emptyList<String>()) }
            MaterialTheme {
                TagsSelector(
                    selectedTagIds = selectedTags,
                    availableTags = (1..100).map { Tag("tag-$it", "Tag $it") },
                    onTagsChanged = {
                        selectedTags = it
                        selected = it
                    },
                    enabled = true,
                )
            }
        }

        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_SELECTOR).performClick()
        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_SEARCH).performTextInput("tag 87")
        composeRule.onNodeWithTag(EditTimeEntryTestTags.tagChip("tag-87")).performClick()
        composeRule.onNodeWithTag(EditTimeEntryTestTags.tagChip("tag-87")).assertIsSelected()
        assertEquals(listOf("tag-87"), selected)

        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_SEARCH).performTextClearance()
        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_SEARCH).performTextInput("tag 2")
        composeRule.onNodeWithTag(EditTimeEntryTestTags.tagChip("tag-2")).performClick()
        assertEquals(listOf("tag-87", "tag-2"), selected)

        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_CLOSE).performClick()
        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_SELECTOR).performClick()
        composeRule.onNodeWithTag(EditTimeEntryTestTags.TAGS_SEARCH).performTextInput("tag 87")
        composeRule.onNodeWithTag(EditTimeEntryTestTags.tagChip("tag-87")).assertIsSelected().performClick()
        assertEquals(listOf("tag-2"), selected)
    }
}
