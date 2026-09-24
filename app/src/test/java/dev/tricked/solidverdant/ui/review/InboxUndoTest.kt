/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B5: the Inbox undo prompt times out, and undoing a swiped card does not dismiss it again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InboxUndoTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var expired = 0
    private var undone: String? = null

    /** Offers the undo for "gap:1" as the pane does; the test plays the snackbar host. */
    private fun TestScope.offer(host: SnackbarHostState) {
        backgroundScope.launch {
            offerUndo(host, "Item dismissed", "Undo", "gap:1", onUndo = { undone = it }, onExpired = { expired += 1 })
        }
        runCurrent()
    }

    @Test
    fun undo_snackbar_times_out_instead_of_staying_forever() = runTest {
        val host = SnackbarHostState()
        offer(host)
        val data = requireNotNull(host.currentSnackbarData)

        // With an action label and no duration the snackbar would default to Indefinite.
        assertEquals(SnackbarDuration.Long, data.visuals.duration)
        assertEquals("Undo", data.visuals.actionLabel)

        // The host's timer runs out: the undo window closes without undoing.
        data.dismiss()
        runCurrent()
        assertEquals(1, expired)
        assertNull(undone)
    }

    @Test
    fun undo_action_restores_the_dismissed_issue() = runTest {
        val host = SnackbarHostState()
        offer(host)

        requireNotNull(host.currentSnackbarData).performAction()
        runCurrent()

        assertEquals("gap:1", undone)
        assertEquals(0, expired)
    }

    @Test
    fun undone_card_stays_in_the_list_instead_of_dismissing_itself_again() {
        val keys = mutableStateListOf("a", "b")
        val dismissed = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    items(keys, key = { it }) { key ->
                        DismissibleIssue(
                            onDismiss = {
                                dismissed += key
                                keys.remove(key)
                            },
                        ) {
                            Text(key, modifier = Modifier.fillMaxWidth().height(72.dp).testTag("card_$key"))
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("card_a").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf("a"), dismissed)
        composeRule.onNodeWithTag("card_a").assertDoesNotExist()

        // Undo: the same issue key comes back into the list.
        composeRule.runOnIdle { keys.add(0, "a") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("card_a").assertIsDisplayed()
        assertEquals("the restored card must not be dismissed a second time", listOf("a"), dismissed)
    }
}
