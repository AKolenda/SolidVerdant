/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.isLight

/**
 * Space the floating tab bar covers at the bottom of the window. Tab screens scroll underneath the
 * bar and add this as trailing content padding; pushed screens are laid out above it.
 */
val LocalFloatingBarInset = compositionLocalOf<Dp> { 0.dp }

/**
 * The highlighted tab after navigating to [destinationRoute]: a tab route selects itself; a pushed
 * destination (calendar, review, sync center...) keeps the tab it was opened from.
 */
internal fun nextSelectedTab(current: String, destinationRoute: String?): String =
    destinationRoute?.takeIf { route -> bottomNavScreens.any { it.route == route } } ?: current

@Composable
fun MainNavHost(
    navController: NavHostController,
    trackContent: @Composable () -> Unit,
    calendarContent: @Composable () -> Unit,
    statsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    reviewContent: @Composable () -> Unit = {},
    onPrivacyLogout: () -> Unit = {},
    syncCenterContent: @Composable () -> Unit = {
        SyncCenterScreen(onBack = { navController.popBackStack() })
    },
) {
    var selectedRoute by rememberSaveable { mutableStateOf(Screen.Track.route) }
    val currentEntry by navController.currentBackStackEntryAsState()
    val destinationRoute = currentEntry?.destination?.route
    LaunchedEffect(destinationRoute) { selectedRoute = nextSelectedTab(selectedRoute, destinationRoute) }
    val barInset = Dimens.TabBarHeight + Dimens.TabBarBottomGap +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFloatingBarInset provides barInset) {
            NavHost(
                navController = navController,
                startDestination = Screen.Track.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Screen.Track.route) { trackContent() }
                composable(Screen.Stats.route) { statsContent() }
                composable(Screen.Settings.route) { settingsContent() }

                composable(TimerRoutes.CALENDAR) { AboveTabBar { calendarContent() } }
                composable(TimerRoutes.REVIEW) { AboveTabBar { reviewContent() } }
                composable(ReviewRoutes.END_OF_DAY) {
                    AboveTabBar { EndOfDayReviewHost(onBack = { navController.popBackStack() }) }
                }
                composable(ReviewRoutes.REMINDER_SETTINGS) {
                    AboveTabBar { ReminderSettingsScreen(onBack = { navController.popBackStack() }) }
                }
                composable(ReviewRoutes.MANAGE_TEMPLATES) {
                    AboveTabBar { ManageTemplatesScreen(onBack = { navController.popBackStack() }) }
                }
                composable(SyncRoutes.SYNC_CENTER) {
                    AboveTabBar { syncCenterContent() }
                }
                composable(SettingsRoutes.PRIVACY) {
                    AboveTabBar {
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
        MainNavigationBar(
            selectedRoute = selectedRoute,
            onNavigate = { screen ->
                val reselected = screen.route == selectedRoute
                selectedRoute = screen.route
                if (reselected) {
                    // Re-tapping the current tab returns to its root, like a UITabBar.
                    navController.popBackStack(screen.route, inclusive = false)
                } else {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Dimens.TabBarBottomGap),
        )
    }
}

@Composable
private fun AboveTabBar(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = LocalFloatingBarInset.current)) { content() }
}

/** Floating pill tab bar, shared by production and full-app screenshot rendering. */
@Composable
internal fun MainNavigationBar(selectedRoute: String?, onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .height(Dimens.TabBarHeight)
            .shadow(Dimens.TabBarShadow, CircleShape, clip = false),
        shape = CircleShape,
        // Light: a white pill over grey; dark: one step lighter than the rows it floats over.
        color = with(MaterialTheme.colorScheme) { if (isLight) surface else surfaceContainerHigh }.copy(alpha = TAB_BAR_ALPHA),
        border = BorderStroke(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(Dimens.Space4).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space2),
        ) {
            bottomNavScreens.forEach { screen ->
                val selected = screen.route == selectedRoute
                val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                Column(
                    modifier = Modifier
                        .testTag(mainNavTag(screen.route))
                        .width(Dimens.TabBarItemWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_PILL_ALPHA) else Color.Transparent,
                        )
                        .selectable(selected = selected, role = Role.Tab, onClick = { onNavigate(screen) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(screen.icon, contentDescription = null, tint = tint, modifier = Modifier.size(Dimens.IconSmall))
                    Text(
                        text = stringResource(screen.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val TAB_BAR_ALPHA = 0.94f
private const val SELECTED_PILL_ALPHA = 0.12f

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
