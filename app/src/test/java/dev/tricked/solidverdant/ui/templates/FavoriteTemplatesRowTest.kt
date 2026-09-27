/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.templates

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.repository.EntryTemplate
import dev.tricked.solidverdant.ui.components.GroupedSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Favourite templates as start-timer shortcut rows: label, "Project · Task", one tap to start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteTemplatesRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val projects = listOf(Project("p1", "Website", "#336699"))
    private val tasks = listOf(Task(id = "t1", name = "Design", projectId = "p1", createdAt = "", updatedAt = ""))

    private fun template(id: String, name: String?, description: String?, taskId: String? = "t1") = EntryTemplate(
        id = id,
        organizationId = "org",
        name = name,
        projectId = "p1",
        taskId = taskId,
        description = description,
        tagIds = emptyList(),
        billable = true,
        isFavorite = true,
        sortOrder = 0,
        createdAtMs = 0L,
    )

    @Test
    fun a_row_shows_the_template_and_starts_it_in_one_tap() {
        var started: TemplateStart? = null
        composeRule.setContent {
            MaterialTheme {
                GroupedSection {
                    FavoriteTemplatesRow(
                        templates = listOf(template("a", name = "Deep work", description = "Focus block")),
                        projects = projects,
                        tasks = tasks,
                        tags = emptyList(),
                        onStart = { started = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Website · Design").assertIsDisplayed()
        composeRule.onNodeWithText("Deep work").performClick()

        assertEquals("Focus block", started?.description)
        assertEquals("p1", started?.projectId)
        assertEquals("t1", started?.taskId)
    }

    @Test
    fun a_template_with_a_placeholder_asks_before_starting() {
        var started: TemplateStart? = null
        composeRule.setContent {
            MaterialTheme {
                GroupedSection {
                    FavoriteTemplatesRow(
                        templates = listOf(template("b", name = null, description = "Call {client}", taskId = null)),
                        projects = projects,
                        tasks = tasks,
                        tags = emptyList(),
                        onStart = { started = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Website").assertIsDisplayed()
        composeRule.onNodeWithText("Call {client}").performClick()
        assertNull(started)

        composeRule.onNodeWithText("Start").performClick()
        assertEquals("p1", started?.projectId)
    }
}
