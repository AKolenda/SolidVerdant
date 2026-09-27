/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.ConfirmDialog

/**
 * Logout confirmation. Logging out deletes this device's database and upload queue, so when
 * [unsyncedChanges] is above zero the dialog says how many changes would be lost for good and the
 * action reads "Log out anyway".
 */
@Composable
fun LogoutConfirmDialog(unsyncedChanges: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val atRisk = unsyncedChanges > 0
    ConfirmDialog(
        title = if (atRisk) {
            pluralStringResource(R.plurals.sweep_logout_unsynced_title, unsyncedChanges, unsyncedChanges)
        } else {
            stringResource(R.string.sweep_logout_title)
        },
        message = if (atRisk) {
            pluralStringResource(R.plurals.sweep_logout_unsynced_message, unsyncedChanges, unsyncedChanges)
        } else {
            stringResource(R.string.sweep_logout_synced_message)
        },
        confirmLabel = stringResource(if (atRisk) R.string.sweep_logout_confirm_anyway else R.string.sweep_logout_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        confirmTestTag = SettingsTestTags.LOGOUT_CONFIRM,
        dismissTestTag = SettingsTestTags.LOGOUT_CANCEL,
    )
}
