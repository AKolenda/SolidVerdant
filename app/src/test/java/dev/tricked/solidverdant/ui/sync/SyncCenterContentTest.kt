/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.SyncOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B2: one row per entry; Discard and "Use server version" confirm and wait for running recoveries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCenterContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val discarded = mutableListOf<String>()
    private val usedServer = mutableListOf<String>()

    private val failedTimer = listOf(
        SyncOperation("local-timer", OutboxOpType.START, EntrySyncStatus.FAILED, 5, "Server rejected this change"),
        SyncOperation("local-timer", OutboxOpType.STOP, EntrySyncStatus.FAILED, 0, "Server rejected this change"),
    )
    private val conflict = listOf(SyncOperation("e-conflict", OutboxOpType.UPDATE, EntrySyncStatus.CONFLICT, 0, null))

    private fun show(active: Set<String> = emptySet()) {
        val operations = failedTimer + conflict
        val groups = groupByEntry(operations)
        val state = SyncCenterUiState(
            isLoading = false,
            organizationId = "org1",
            failed = failedTimer,
            conflicts = conflict,
            failedEntries = groups.filter { it.status == EntrySyncStatus.FAILED },
            conflictEntries = groups.filter { it.status == EntrySyncStatus.CONFLICT },
            activeRecoveryEntryIds = active,
            topLine = SyncCenterUiState.TopLine.FAILURES,
        )
        composeRule.setContent {
            MaterialTheme {
                SyncCenterContent(
                    state = state,
                    nowMs = 0L,
                    actions = SyncCenterActions(
                        onSyncNow = {},
                        onRetry = {},
                        onRetryAll = {},
                        onDiscard = { discarded += it },
                        onRetryUpload = {},
                        onUseServerVersion = { usedServer += it },
                    ),
                )
            }
        }
    }

    @Test
    fun a_failed_timer_is_one_row_naming_both_changes() {
        show()

        composeRule.onNodeWithText("Started timer, Stopped timer").performScrollTo().assertExists()
        composeRule.onNodeWithText("Discard 2 failed changes?").assertDoesNotExist()
    }

    @Test
    fun discard_asks_first_and_names_what_goes() {
        show()

        composeRule.onNodeWithTag(SyncCenterTestTags.failedDiscard("local-timer")).performScrollTo().performClick()
        assertTrue("nothing is discarded before confirming", discarded.isEmpty())
        composeRule.onNodeWithText("Discard 2 failed changes?").assertExists()

        composeRule.onNodeWithTag(SyncCenterTestTags.DISCARD_CONFIRM).performClick()
        assertEquals(listOf("local-timer"), discarded)
    }

    @Test
    fun use_server_version_asks_first() {
        show()

        composeRule.onNodeWithTag(SyncCenterTestTags.conflictUseServer("e-conflict")).performScrollTo().performClick()
        assertTrue(usedServer.isEmpty())

        composeRule.onNodeWithTag(SyncCenterTestTags.USE_SERVER_CONFIRM).performClick()
        assertEquals(listOf("e-conflict"), usedServer)
    }

    @Test
    fun entry_actions_wait_while_a_recovery_for_it_runs() {
        show(active = setOf("local-timer", "e-conflict"))

        composeRule.onNodeWithTag(SyncCenterTestTags.failedDiscard("local-timer")).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(SyncCenterTestTags.failedRetry("local-timer")).assertIsNotEnabled()
        composeRule.onNodeWithTag(SyncCenterTestTags.conflictUseServer("e-conflict")).performScrollTo().assertIsNotEnabled()
    }
}
