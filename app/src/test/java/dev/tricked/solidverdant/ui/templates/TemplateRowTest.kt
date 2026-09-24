/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.templates

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tricked.solidverdant.data.repository.EntryTemplate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemplateRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val template = EntryTemplate("tm1", "org1", "Deep work", null, null, "Focus block", emptyList(), false, false, 0, 0L)

    @Test
    fun tappingTheRowEditsAndTheStarTogglesFavorite() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                TemplateRow(
                    template = template,
                    resolution = TemplateResolver.resolve(template, emptyList(), emptyList(), emptyList()),
                    projectTaskSummary = null,
                    label = "Deep work",
                    canMoveUp = false,
                    canMoveDown = true,
                    onToggleFavorite = { events += "favorite" },
                    onMoveUp = { events += "up" },
                    onMoveDown = { events += "down" },
                    onEdit = { events += "edit" },
                    onDelete = { events += "delete" },
                )
            }
        }

        composeRule.onNodeWithTag(ManageTemplatesTestTags.row("tm1")).performClick()
        composeRule.onNodeWithTag(ManageTemplatesTestTags.favorite("tm1")).performClick()
        composeRule.onNodeWithTag(ManageTemplatesTestTags.menu("tm1")).performClick()
        composeRule.onNodeWithText("Move down").performClick()

        assertEquals(listOf("edit", "favorite", "down"), events)
    }
}
