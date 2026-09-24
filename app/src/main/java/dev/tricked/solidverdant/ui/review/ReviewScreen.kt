/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.SegmentedControl
import dev.tricked.solidverdant.ui.navigation.MainMenuButton
import dev.tricked.solidverdant.ui.navigation.MainTopBar
import dev.tricked.solidverdant.ui.theme.Dimens

/**
 * Container for the review-loop home, a side-menu destination. Hosts a segmented control that
 * switches between [InboxPane] and [ReviewDayPane], and an overflow menu with entry points to the
 * reminder settings and template management screens.
 *
 * The header shows the side-menu button, or a back arrow when pushed with [onBack]. This is shared
 * scaffolding only. The Inbox agent fills in [InboxPane]; the review/reminders agent
 * fills in [ReviewDayPane] and [ReminderSettingsScreen]; the templates agent fills in the manage
 * templates screen. Navigation callbacks default to no-ops so the container renders standalone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: (() -> Unit)? = null,
    onOpenReminderSettings: () -> Unit = {},
    onOpenManageTemplates: () -> Unit = {},
    onOpenEndOfDayReview: () -> Unit = {},
) {
    var segment by rememberSaveable { mutableStateOf(ReviewSegment.Inbox) }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        MainTopBar(
            title = stringResource(R.string.review_title),
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("review_back")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.review_navigate_back),
                        )
                    }
                } else {
                    MainMenuButton()
                }
            },
            actions = {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("review_more_actions"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.review_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.review_menu_end_of_day)) },
                        onClick = {
                            menuExpanded = false
                            onOpenEndOfDayReview()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.review_menu_reminder_settings)) },
                        onClick = {
                            menuExpanded = false
                            onOpenReminderSettings()
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.testTag("review_manage_templates"),
                        text = { Text(stringResource(R.string.review_menu_manage_templates)) },
                        onClick = {
                            menuExpanded = false
                            onOpenManageTemplates()
                        },
                    )
                }
            },
        )

        SegmentedControl(
            options = ReviewSegment.entries,
            selected = segment,
            onSelect = { segment = it },
            label = { option ->
                stringResource(
                    when (option) {
                        ReviewSegment.Inbox -> R.string.review_segment_inbox
                        ReviewSegment.ReviewDay -> R.string.review_segment_review_day
                    },
                )
            },
            modifier = Modifier.padding(horizontal = Dimens.Space16, vertical = Dimens.Space8),
            optionTestTag = ReviewTestTags::segment,
        )

        when (segment) {
            ReviewSegment.Inbox -> InboxPane()
            ReviewSegment.ReviewDay -> ReviewDayPane()
        }
    }
}

/** Shared placeholder body used by the stub panes/screens until a feature agent replaces it. */
@Composable
internal fun ReviewPlaceholder(textRes: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.Space24),
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
