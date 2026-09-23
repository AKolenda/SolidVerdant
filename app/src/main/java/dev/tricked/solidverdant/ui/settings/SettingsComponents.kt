/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.NotificationImportant
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.tricked.solidverdant.BuildConfig
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedRow
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.GroupedSwitchRow
import dev.tricked.solidverdant.ui.theme.Dimens

/** Stable tags for the Settings tab, shared by production UI and device robots. */
object SettingsTestTags {
    const val SCREEN = "settings_screen"
    const val LOGOUT_BUTTON = "settings_logout_button"
    const val THEME_ROW = "settings_theme_row"
    const val REMINDERS_ROW = "settings_reminders_row"
    const val TEMPLATES_ROW = "settings_templates_row"
    const val SYNC_CENTER_ROW = "settings_sync_center_row"
    const val LIVE_UPDATE_SWITCH = "settings_live_update_switch"
    const val AUTO_CLEAR_FIELDS_SWITCH = "settings_auto_clear_fields_switch"
    const val CLEAR_DESCRIPTION_AFTER_STOP_SWITCH = "settings_clear_description_after_stop_switch"

    fun themeOption(name: String): String = "settings_theme_option_$name"
}

/** Settings control for Android 16+ promoted ongoing timer notifications. */
@Composable
internal fun LiveUpdateSettingRow(
    enabled: Boolean,
    systemEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GroupedSwitchRow(
            title = stringResource(R.string.live_timer_updates),
            subtitle = stringResource(R.string.live_timer_updates_description),
            leadingIcon = Icons.Outlined.NotificationImportant,
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.testTag(SettingsTestTags.LIVE_UPDATE_SWITCH),
        )
        if (enabled && !systemEnabled) {
            Text(
                text = stringResource(R.string.live_timer_updates_android_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.SettingsIconInset, end = Dimens.Space16),
            )
            TextButton(
                onClick = onOpenSystemSettings,
                modifier = Modifier
                    .padding(start = Dimens.SettingsIconInset - Dimens.Space12)
                    .heightIn(min = Dimens.MinTouchTarget),
            ) {
                Text(stringResource(R.string.live_timer_updates_open_settings))
            }
        }
    }
}

/** Full field clearing plus the description-only fallback shown when full clearing is disabled. */
@Composable
internal fun AutoClearEntryFieldsSettings(
    autoClearEntryFieldsAfterStop: Boolean,
    clearDescriptionAfterStop: Boolean,
    onAutoClearEntryFieldsAfterStopChange: (Boolean) -> Unit,
    onClearDescriptionAfterStopChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        GroupedSwitchRow(
            title = stringResource(R.string.auto_clear_entry_fields_after_stop),
            subtitle = stringResource(R.string.auto_clear_entry_fields_after_stop_description),
            leadingIcon = Icons.Outlined.CleaningServices,
            checked = autoClearEntryFieldsAfterStop,
            onCheckedChange = onAutoClearEntryFieldsAfterStopChange,
            modifier = Modifier.testTag(SettingsTestTags.AUTO_CLEAR_FIELDS_SWITCH),
        )
        AnimatedVisibility(
            visible = !autoClearEntryFieldsAfterStop,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                GroupedDivider(inset = Dimens.SettingsIconInset)
                GroupedSwitchRow(
                    title = stringResource(R.string.clear_description_after_stop),
                    subtitle = stringResource(R.string.clear_description_after_stop_description),
                    leadingIcon = Icons.AutoMirrored.Outlined.ShortText,
                    checked = clearDescriptionAfterStop,
                    onCheckedChange = onClearDescriptionAfterStopChange,
                    modifier = Modifier.testTag(SettingsTestTags.CLEAR_DESCRIPTION_AFTER_STOP_SWITCH),
                )
            }
        }
    }
}

/** Version, verification details, and the Obtainium install/add action. */
@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var obtainiumInstalled by remember { mutableStateOf(isObtainiumInstalled(context)) }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                obtainiumInstalled = isObtainiumInstalled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val signingHash = remember { getSigningCertificateHash(context) }

    GroupedSection(header = stringResource(R.string.about)) {
        GroupedRow(
            title = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
            leadingIcon = Icons.Outlined.Info,
        )
        GroupedDivider(inset = Dimens.SettingsIconInset)
        GroupedRow(
            title = stringResource(R.string.package_id_label),
            subtitle = context.packageName,
            leadingIcon = Icons.Outlined.Inventory2,
            onClick = { copyToClipboard(context, context.packageName) },
            showChevron = false,
        )
        GroupedDivider(inset = Dimens.SettingsIconInset)
        GroupedRow(
            title = stringResource(R.string.signing_certificate_label),
            subtitle = signingHash,
            leadingIcon = Icons.Outlined.VerifiedUser,
            onClick = { copyToClipboard(context, signingHash) },
            showChevron = false,
        )
        GroupedDivider(inset = Dimens.SettingsIconInset)
        GroupedRow(
            title = stringResource(if (obtainiumInstalled) R.string.add_to_obtainium else R.string.install_obtainium),
            subtitle = stringResource(
                if (obtainiumInstalled) R.string.add_to_obtainium_description else R.string.install_obtainium_description,
            ),
            leadingIcon = Icons.Outlined.Download,
            onClick = {
                val obtainiumUrl = if (obtainiumInstalled) OBTAINIUM_ADD_APP_URL else OBTAINIUM_INSTALL_URL
                val intent = Intent(Intent.ACTION_VIEW, obtainiumUrl.toUri()).apply {
                    if (obtainiumInstalled) setPackage(OBTAINIUM_PACKAGE)
                }
                context.startActivity(intent)
            },
        )
    }
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("SolidVerdant", text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

private fun isObtainiumInstalled(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(OBTAINIUM_PACKAGE, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(OBTAINIUM_PACKAGE, 0)
    }
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

/** SHA-256 of the app's signing certificate, so users can verify the build they installed. */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun getSigningCertificateHash(context: Context): String = try {
    val packageInfo = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )
    val signature = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
    if (signature == null) {
        "Unknown"
    } else {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }
} catch (e: Exception) {
    "Unknown"
}
