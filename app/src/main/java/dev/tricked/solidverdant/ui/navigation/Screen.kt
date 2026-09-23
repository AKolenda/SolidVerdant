/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import dev.tricked.solidverdant.R

/**
 * The side-menu destinations, in menu order. Route strings are stable: calendar deep links, review
 * notifications and device tests depend on them.
 */
sealed class Screen(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Track : Screen("track", R.string.nav_time_tracker, Icons.Outlined.Timer)
    data object Calendar : Screen("calendar", R.string.nav_calendar, Icons.Outlined.CalendarMonth)
    data object Stats : Screen("stats", R.string.nav_reports, Icons.Outlined.BarChart)
    data object Review : Screen("review", R.string.nav_review, Icons.Outlined.Inbox)
    data object Settings : Screen("settings", R.string.settings_menu, Icons.Outlined.Settings)
}

val menuScreens: List<Screen> = listOf(Screen.Track, Screen.Calendar, Screen.Stats, Screen.Review, Screen.Settings)

/** Test tag of a side-menu item, shared by production UI and device robots. */
fun mainNavTag(route: String): String = "main_nav_$route"

/** Test tag of the ☰ button that opens the side menu. */
const val MAIN_MENU_BUTTON_TAG: String = "main_menu_button"

/**
 * Routes for review-loop destinations pushed on top of a menu destination. They are reached from
 * Review's overflow menu, Settings, or a reminder / end-of-day notification, and each renders
 * full-screen with its own back navigation.
 */
object ReviewRoutes {
    /** Compact end-of-day review flow (opened from the end-of-day notification). */
    const val END_OF_DAY: String = "review/end_of_day"

    /** Tracking-reminder + end-of-day reminder configuration. */
    const val REMINDER_SETTINGS: String = "review/reminders"

    /** Manage reusable entry templates / favorites. */
    const val MANAGE_TEMPLATES: String = "templates/manage"
}

/**
 * Routes for the sync surface (#33). The dedicated Sync Center is pushed full-screen with its own
 * back navigation, reached from the Time Tracker sync summary or Settings.
 */
object SyncRoutes {
    /** Dedicated Sync Center: freshness, pending changes, failures + retry/discard. */
    const val SYNC_CENTER: String = "sync/center"
}

/**
 * Routes for the settings surface. The privacy & data-management screen (#48) is pushed full-screen
 * on top of the menu destinations with its own back navigation, reached from Settings.
 */
object SettingsRoutes {
    /** Privacy & data-management: what is stored/sent, token protection, permissions, data controls. */
    const val PRIVACY: String = "settings/privacy"
}
