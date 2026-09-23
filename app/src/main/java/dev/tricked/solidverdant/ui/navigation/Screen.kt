/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import dev.tricked.solidverdant.R

/** The three bottom tabs. Route strings are stable: deep links and device tests depend on them. */
sealed class Screen(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Track : Screen("track", R.string.nav_timer, Icons.Outlined.Timer)
    data object Stats : Screen("stats", R.string.nav_dashboard, Icons.Outlined.BarChart)
    data object Settings : Screen("settings", R.string.settings_menu, Icons.Outlined.Settings)
}

val bottomNavScreens: List<Screen> = listOf(Screen.Track, Screen.Stats, Screen.Settings)

/** Test tag of a bottom tab, shared by production UI and device robots. */
fun mainNavTag(route: String): String = "main_nav_$route"

/**
 * Destinations pushed on top of the Timer tab. Calendar and Review used to be tabs; they keep their
 * route strings so calendar deep links and review notifications still resolve.
 */
object TimerRoutes {
    const val CALENDAR: String = "calendar"
    const val REVIEW: String = "review"
}

/**
 * Routes for review-loop destinations pushed on top of a tab. They are reached from Review's overflow
 * menu, Settings, or a reminder / end-of-day notification, and each renders full-screen with its own
 * back navigation.
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
 * Routes for the sync surface (#33). The dedicated Sync Center is pushed full-screen on top of the
 * tab graph with its own back navigation, reached from the Timer sync summary or Settings.
 */
object SyncRoutes {
    /** Dedicated Sync Center: freshness, pending changes, failures + retry/discard. */
    const val SYNC_CENTER: String = "sync/center"
}

/**
 * Routes for the settings surface. The privacy & data-management screen (#48) is pushed full-screen
 * on top of the tab graph with its own back navigation, reached from the Settings tab.
 */
object SettingsRoutes {
    /** Privacy & data-management: what is stored/sent, token protection, permissions, data controls. */
    const val PRIVACY: String = "settings/privacy"
}
