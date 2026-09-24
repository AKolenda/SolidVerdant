/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.sync

/** Stable tags for the dedicated sync status and recovery screen. */
object SyncCenterTestTags {
    const val SCREEN = "sync_status_screen"
    const val BACK_BUTTON = "sync_status_back"
    const val SYNC_NOW = "sync_now_row"
    const val RETRY_ALL = "sync_retry_all_row"
    const val FAILED_OUTSIDE_ORGANIZATION = "sync_failed_outside_organization"
    const val DISCARD_CONFIRM = "sync_discard_confirm"
    const val USE_SERVER_CONFIRM = "sync_use_server_confirm"

    fun conflictRetry(entryId: String) = "sync_conflict_retry_$entryId"

    fun conflictUseServer(entryId: String) = "sync_conflict_use_server_$entryId"

    fun failedRetry(entryId: String) = "sync_failed_retry_$entryId"

    fun failedDiscard(entryId: String) = "sync_failed_discard_$entryId"

    fun pendingRetry(entryId: String) = "sync_pending_retry_$entryId"
}
