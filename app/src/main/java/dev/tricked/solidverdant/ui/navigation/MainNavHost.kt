/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.privacy.PrivacyScreen
import dev.tricked.solidverdant.ui.review.ReminderSettingsScreen
import dev.tricked.solidverdant.ui.review.ReviewDayPane
import dev.tricked.solidverdant.ui.sync.SyncCenterScreen
import dev.tricked.solidverdant.ui.templates.ManageTemplatesScreen
import kotlinx.coroutines.launch

/**
 * The highlighted menu item after navigating to [destinationRoute]: a menu destination selects
 * itself; a pushed destination (sync center, reminders...) keeps the item it was opened from.
 */
internal fun nextSelectedDestination(current: String, destinationRoute: String?): String =
    destinationRoute?.takeIf { route -> menuScreens.any { it.route == route } } ?: current

/** Switch to a menu destination, keeping one copy of each and restoring its saved state. */
fun NavHostController.navigateToMenuDestination(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MainNavHost(
    navController: NavHostController,
    trackContent: @Composable () -> Unit,
    calendarContent: @Composable () -> Unit,
    statsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    reviewContent: @Composable () -> Unit = {},
    menuHeader: @Composable ColumnScope.() -> Unit = {},
    onPrivacyLogout: () -> Unit = {},
    syncCenterContent: @Composable () -> Unit = {
        SyncCenterScreen(onBack = { navController.popBackStack() })
    },
) {
    var selectedRoute by rememberSaveable { mutableStateOf(Screen.Track.route) }
    val currentEntry by navController.currentBackStackEntryAsState()
    val destinationRoute = currentEntry?.destination?.route
    LaunchedEffect(destinationRoute) { selectedRoute = nextSelectedDestination(selectedRoute, destinationRoute) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val menu = remember(drawerState, scope) { MainMenuController(open = { scope.launch { drawerState.open() } }) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Edge swipes stay with the content (history rows swipe right to resume); the ☰ button
        // opens the menu and a swipe or scrim tap closes it.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            MainMenuSheet(
                selectedRoute = selectedRoute,
                header = menuHeader,
                onNavigate = { screen ->
                    scope.launch { drawerState.close() }
                    if (screen.route == selectedRoute) {
                        // Choosing the current item returns to its root, e.g. from the Sync Center.
                        navController.popBackStack(screen.route, inclusive = false)
                    } else {
                        navController.navigateToMenuDestination(screen.route)
                    }
                },
            )
        },
    ) {
        CompositionLocalProvider(LocalMainMenu provides menu) {
            NavHost(
                navController = navController,
                startDestination = Screen.Track.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                page(Screen.Track.route) { trackContent() }
                page(Screen.Calendar.route) { calendarContent() }
                page(Screen.Stats.route) { statsContent() }
                page(Screen.Review.route) { reviewContent() }
                page(Screen.Settings.route) { settingsContent() }

                page(ReviewRoutes.END_OF_DAY) { EndOfDayReviewHost(onBack = { navController.popBackStack() }) }
                page(ReviewRoutes.REMINDER_SETTINGS) { ReminderSettingsScreen(onBack = { navController.popBackStack() }) }
                page(ReviewRoutes.MANAGE_TEMPLATES) { ManageTemplatesScreen(onBack = { navController.popBackStack() }) }
                page(SyncRoutes.SYNC_CENTER) { syncCenterContent() }
                page(SettingsRoutes.PRIVACY) {
                    PrivacyScreen(
                        onBack = { navController.popBackStack() },
                        onLogout = {
                            navController.popBackStack()
                            onPrivacyLogout()
                        },
                    )
                }
            }
        }
    }
}

/**
 * A destination on an opaque page. The predictive back swipe draws the destination below while the
 * current one slides away, so a screen without its own background would show both at once.
 */
private fun NavGraphBuilder.page(route: String, content: @Composable () -> Unit) {
    composable(route) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/**
 * Full-screen host for the end-of-day review flow (opened from the end-of-day notification). Wraps
 * the review/reminders agent's [ReviewDayPane] with a top bar and back navigation. Foundation shell
 * only; the pane itself is fleshed out by that agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndOfDayReviewHost(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_end_of_day_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.review_navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ReviewDayPane()
        }
    }
}
