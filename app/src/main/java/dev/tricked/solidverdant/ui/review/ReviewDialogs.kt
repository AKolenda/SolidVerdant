/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.AppTimePickerDialog
import dev.tricked.solidverdant.ui.components.PickerDialog
import dev.tricked.solidverdant.ui.components.PickerItem
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.util.NotificationPermissionHelper

/**
 * The time picker shared by the reminder settings and the "adjust end time" review action: the
 * app's [AppTimePickerDialog], so it follows the device's 12- or 24-hour clock like every other one.
 */
@Composable
internal fun ReviewTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    confirmTestTag: String = ReviewTestTags.REVIEW_TIME_CONFIRM,
) {
    AppTimePickerDialog(
        title = title,
        initialHour = initialHour,
        initialMinute = initialMinute,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmLabel = stringResource(R.string.review_dialog_confirm),
        confirmTestTag = confirmTestTag,
    )
}

/**
 * Project choice for the "assign project" review action, in the app's searchable picker: the same
 * lazily listed, full-screen-on-phones dialog as the filter and entry pickers. There is no "All" or
 * "No project" row because the step exists to give the entry a project.
 */
@Composable
internal fun ProjectPickerDialog(projects: List<ReviewProject>, onSelect: (projectId: String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(projects, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) projects else projects.filter { it.name.contains(normalized, ignoreCase = true) }
    }
    PickerDialog(
        title = stringResource(R.string.review_project_dialog_title),
        searchPlaceholder = stringResource(R.string.search_projects),
        searchQuery = query,
        onSearchQueryChange = { query = it },
        onClose = onDismiss,
        listTestTag = ReviewTestTags.REVIEW_PROJECT_LIST,
        searchTestTag = ReviewTestTags.REVIEW_PROJECT_SEARCH,
    ) {
        items(filtered, key = { it.id }) { project ->
            val color = projectColor(project.color)
            PickerItem(
                text = project.name,
                selected = false,
                onClick = { onSelect(project.id) },
                leadingContent = {
                    Box(Modifier.size(Dimens.ProjectDot).clip(CircleShape).background(color))
                },
                modifier = Modifier.testTag(ReviewTestTags.reviewProject(project.id)),
            )
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(if (query.isBlank()) R.string.review_project_dialog_empty else R.string.no_results_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.Space24, vertical = Dimens.Space16),
                )
            }
        }
    }
}

@Composable
private fun projectColor(hex: String): Color {
    val fallback = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(hex, fallback) {
        runCatching { Color(hex.toColorInt()) }.getOrDefault(fallback)
    }
}

/** Holder returned by [rememberNotificationPermissionState]. */
internal data class NotificationPermissionState(val hasPermission: Boolean, val request: () -> Unit)

/**
 * Tracks POST_NOTIFICATIONS permission and exposes a request launcher. The status is re-checked on
 * every ON_RESUME so it reflects a grant the user made from system settings after leaving the app.
 * On Android 12 and below notifications need no runtime permission, so this always reports granted.
 */
@Composable
internal fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(NotificationPermissionHelper.hasNotificationPermission(context))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = isGranted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = NotificationPermissionHelper.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return NotificationPermissionState(
        hasPermission = granted,
        request = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}
