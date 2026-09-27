/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Organization
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SV-006 regression coverage for the organization line, now in the side-menu header
 * ([MainMenuHeader]). The org line previously had no dropdown affordance and no accessibility
 * semantics. These tests pin the fixed behaviours:
 *  - the header shows the signed-in account and names the organization once,
 *  - a switchable org line exposes a labelled [Role.Button] that switches organization,
 *  - an unswitchable org line is plain, inert text with none of that affordance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainMenuHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun membership(id: String, orgName: String) = Membership(
        id = id,
        role = "member",
        organization = Organization(id = "org-$id", name = orgName, currency = "EUR"),
    )

    private val switchDescription = "Switch organization"

    private val roleButtonMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun setHeader(memberships: List<Membership>, canSwitch: Boolean, onChange: (Membership) -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                MainMenuHeader(
                    userName = "Ada Lovelace",
                    userEmail = "ada@example.com",
                    organizationName = memberships.first().organization.name,
                    memberships = memberships,
                    currentMembershipId = memberships.first().id,
                    canSwitchOrganization = canSwitch,
                    onMembershipChange = onChange,
                )
            }
        }
    }

    @Test
    fun header_shows_the_account_and_the_organization_once() {
        setHeader(listOf(membership("m1", "Tricked")), canSwitch = false)

        composeRule.onNodeWithText("Ada Lovelace").assertExists()
        composeRule.onNodeWithText("ada@example.com").assertExists()
        composeRule.onNodeWithText("AL").assertExists()
        composeRule.onAllNodesWithText("Tricked").assertCountEquals(1)
    }

    @Test
    fun switchable_org_line_exposes_a_labelled_role_button_that_switches() {
        val globex = membership("m2", "Globex")
        var chosen: Membership? = null
        setHeader(listOf(membership("m1", "Acme"), globex), canSwitch = true) { chosen = it }

        composeRule.onNodeWithContentDescription(switchDescription)
            .assert(hasContentDescription(switchDescription).and(roleButtonMatcher))
            .performClick()
        composeRule.onNodeWithText("Globex").performClick()

        assertEquals(globex, chosen)
    }

    @Test
    fun single_membership_org_line_is_inert_plain_text() {
        setHeader(listOf(membership("m1", "Acme")), canSwitch = false)

        composeRule.onNodeWithContentDescription(switchDescription).assertDoesNotExist()
        composeRule.onNode(roleButtonMatcher).assertDoesNotExist()
        composeRule.onNodeWithText("Acme").assertExists()
    }

    @Test
    fun initials_take_the_first_letters_of_up_to_two_words() {
        assertEquals("AL", initialsOf("Ada Lovelace"))
        assertEquals("A", initialsOf("  ada "))
        assertEquals("GB", initialsOf("grace brewster hopper"))
        assertEquals("?", initialsOf(" "))
    }
}
