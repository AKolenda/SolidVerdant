/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.tricked.solidverdant.data.local.AppThemeMode
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.reminder.ReminderWorker
import dev.tricked.solidverdant.sync.SyncStatusReporter
import dev.tricked.solidverdant.ui.auth.AuthState
import dev.tricked.solidverdant.ui.auth.AuthViewModel
import dev.tricked.solidverdant.ui.calendar.CalendarScreen
import dev.tricked.solidverdant.ui.components.AppStatusOverlay
import dev.tricked.solidverdant.ui.login.LoginScreen
import dev.tricked.solidverdant.ui.navigation.MainMenuHeader
import dev.tricked.solidverdant.ui.navigation.MainNavHost
import dev.tricked.solidverdant.ui.navigation.ReviewRoutes
import dev.tricked.solidverdant.ui.navigation.Screen
import dev.tricked.solidverdant.ui.navigation.SettingsRoutes
import dev.tricked.solidverdant.ui.navigation.SyncRoutes
import dev.tricked.solidverdant.ui.navigation.calendarDateFromUri
import dev.tricked.solidverdant.ui.navigation.navigateToMenuDestination
import dev.tricked.solidverdant.ui.review.ReviewScreen
import dev.tricked.solidverdant.ui.settings.SettingsScreen
import dev.tricked.solidverdant.ui.statistics.StatisticsScreen
import dev.tricked.solidverdant.ui.theme.SolidVerdantTheme
import dev.tricked.solidverdant.ui.tracking.TrackingScreen
import dev.tricked.solidverdant.ui.tracking.TrackingViewModel
import dev.tricked.solidverdant.ui.tracking.entryDraft
import dev.tricked.solidverdant.ui.tracking.withoutIdleDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

/**
 * Main activity for SolidVerdant app
 */
@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsDataStore: SettingsDataStore

    @Inject lateinit var syncStatusReporter: SyncStatusReporter

    protected val authViewModel: AuthViewModel by viewModels()
    private val trackingViewModel: TrackingViewModel by viewModels()
    private var stoppedAtElapsedRealtime: Long? = null
    private var handoffOrganizationId by mutableStateOf<String?>(null)
    private var editActiveEntryRequested by mutableStateOf(false)
    private var pendingReviewRoute by mutableStateOf<String?>(null)
    private var pendingCalendarDate by mutableStateOf<LocalDate?>(null)
    private val startupTheme = MutableStateFlow(AppThemeMode.SYSTEM)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        startupTheme.value = settingsDataStore.getCachedAppTheme()
        lifecycleScope.launch { startupTheme.value = settingsDataStore.getAppTheme() }
        splash.setKeepOnScreenCondition {
            authViewModel.authState.value == dev.tricked.solidverdant.ui.auth.AuthState.Unknown
        }
        enableEdgeToEdge()

        // Handle the launch intent once. A recreated activity (rotation, process restore) gets the
        // same intent again: handling it again would switch organization past the running-timer
        // guard, jump the calendar again and resubmit a used OAuth code. Requests not yet acted on
        // come back from the saved state instead.
        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            restorePendingRequests(savedInstanceState)
        }

        setContent {
            val initialTheme by startupTheme.collectAsState()
            val appTheme by trackingViewModel.appTheme.collectAsState(initial = initialTheme)
            val syncStatus by syncStatusReporter.status.collectAsState()
            val resolvedTheme = appTheme
            SolidVerdantTheme(themeMode = resolvedTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppStatusOverlay(
                        syncStatus = syncStatus,
                        onRetrySync = {
                            val organizationId = authViewModel.uiState.value.currentMembership?.organizationId
                            if (organizationId != null) {
                                trackingViewModel.retryAllSync(organizationId)
                            } else {
                                trackingViewModel.retrySync()
                            }
                        },
                    ) {
                        SolidVerdantApp(
                            authViewModel = authViewModel,
                            trackingViewModel = trackingViewModel,
                            handoffOrganizationId = handoffOrganizationId,
                            onHandoffConsumed = { handoffOrganizationId = null },
                            editActiveEntryRequested = editActiveEntryRequested,
                            onEditActiveEntryConsumed = { editActiveEntryRequested = false },
                            pendingReviewRoute = pendingReviewRoute,
                            onPendingReviewRouteConsumed = { pendingReviewRoute = null },
                            calendarInitialDate = pendingCalendarDate,
                            onCalendarInitialDateConsumed = { pendingCalendarDate = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        handoffOrganizationId?.let { outState.putString(STATE_HANDOFF_ORGANIZATION_ID, it) }
        outState.putBoolean(STATE_EDIT_ACTIVE_ENTRY, editActiveEntryRequested)
        pendingReviewRoute?.let { outState.putString(STATE_REVIEW_ROUTE, it) }
        pendingCalendarDate?.let { outState.putString(STATE_CALENDAR_DATE, it.toString()) }
    }

    private fun restorePendingRequests(state: Bundle) {
        handoffOrganizationId = state.getString(STATE_HANDOFF_ORGANIZATION_ID)
        editActiveEntryRequested = state.getBoolean(STATE_EDIT_ACTIVE_ENTRY, false)
        pendingReviewRoute = state.getString(STATE_REVIEW_ROUTE)
        pendingCalendarDate = state.getString(STATE_CALENDAR_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    override fun onStart() {
        super.onStart()

        val backgroundDuration = stoppedAtElapsedRealtime?.let {
            SystemClock.elapsedRealtime() - it
        }
        val membership = authViewModel.uiState.value.currentMembership
        if (membership != null) {
            trackingViewModel.onAppForegrounded(
                organizationId = membership.organizationId,
                memberId = membership.id,
                refreshAll = backgroundDuration != null &&
                    backgroundDuration >= RESUME_REFRESH_THRESHOLD_MS,
            )
        }
        stoppedAtElapsedRealtime = null
    }

    override fun onStop() {
        stoppedAtElapsedRealtime = SystemClock.elapsedRealtime()
        trackingViewModel.onAppBackgrounded()
        super.onStop()
    }

    /** Handle incoming intents (including deep links). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val requests = takeLaunchRequests(intent)
        handoffOrganizationId = requests.handoffOrganizationId
        if (requests.editActiveEntry) editActiveEntryRequested = true
        requests.reviewRoute?.let { pendingReviewRoute = it }
        requests.deepLink?.let(::handleDeepLink)
    }

    /** Handle incoming app deep links without logging URI contents. */
    private fun handleDeepLink(uri: Uri) {
        Timber.d("Handling app deep link")

        if (uri.scheme == "solidtime" && uri.host == "oauth" && uri.path == "/callback") {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")

            authViewModel.handleOAuthCallback(code, state)
            return
        }
        calendarDateFromUri(uri)?.let { pendingCalendarDate = it }
    }

    companion object {
        const val RESUME_REFRESH_THRESHOLD_MS = 30_000L
        const val EXTRA_HANDOFF_ORGANIZATION_ID = "handoff_organization_id"
        const val EXTRA_EDIT_ACTIVE_ENTRY = "edit_active_entry"
        private const val STATE_HANDOFF_ORGANIZATION_ID = "state_handoff_organization_id"
        private const val STATE_EDIT_ACTIVE_ENTRY = "state_edit_active_entry"
        private const val STATE_REVIEW_ROUTE = "state_review_route"
        private const val STATE_CALENDAR_DATE = "state_calendar_date"
    }
}

/** The one-off requests an intent to [MainActivity] carries. */
internal data class LaunchRequests(
    val handoffOrganizationId: String?,
    val editActiveEntry: Boolean,
    val reviewRoute: String?,
    val deepLink: Uri?,
)

/**
 * Read [intent]'s requests and remove them from it, so the activity's intent never carries a
 * request that was already acted on: an OAuth code is single-use, a calendar link a one-off jump,
 * and a handoff must not switch organization again later.
 */
internal fun takeLaunchRequests(intent: Intent): LaunchRequests {
    val handoffOrganizationId = intent.getStringExtra(MainActivity.EXTRA_HANDOFF_ORGANIZATION_ID)
    intent.removeExtra(MainActivity.EXTRA_HANDOFF_ORGANIZATION_ID)
    val editActiveEntry = intent.getBooleanExtra(MainActivity.EXTRA_EDIT_ACTIVE_ENTRY, false)
    intent.removeExtra(MainActivity.EXTRA_EDIT_ACTIVE_ENTRY)
    val reviewRoute = intent.getStringExtra(ReminderWorker.EXTRA_OPEN_REVIEW_ROUTE)
        ?.takeIf { it in setOf(ReviewRoutes.END_OF_DAY, ReviewRoutes.REMINDER_SETTINGS, ReviewRoutes.MANAGE_TEMPLATES) }
    intent.removeExtra(ReminderWorker.EXTRA_OPEN_REVIEW_ROUTE)
    val deepLink = intent.data
    intent.data = null
    return LaunchRequests(handoffOrganizationId, editActiveEntry, reviewRoute, deepLink)
}

/**
 * Root composable for the app
 */
@Composable
@Suppress("LongMethod")
fun SolidVerdantApp(
    authViewModel: AuthViewModel,
    trackingViewModel: TrackingViewModel,
    handoffOrganizationId: String? = null,
    onHandoffConsumed: () -> Unit = {},
    editActiveEntryRequested: Boolean = false,
    onEditActiveEntryConsumed: () -> Unit = {},
    pendingReviewRoute: String? = null,
    onPendingReviewRouteConsumed: () -> Unit = {},
    calendarInitialDate: LocalDate? = null,
    onCalendarInitialDateConsumed: () -> Unit = {},
) {
    val authUiState by authViewModel.uiState.collectAsState()
    val configState by authViewModel.configState.collectAsState()
    val authState by authViewModel.authState.collectAsState()
    // Read lower down, in the destinations. Typing into the start-timer sheet only changes its
    // draft: the state everything else sees stays equal, so the shell, the navigation host and the
    // Time Tracker skip recomposition, and only the form reads [entryDraft].
    val trackingUiStateSource = trackingViewModel.uiState.collectAsState()
    val trackingUiState by remember(trackingUiStateSource) {
        derivedStateOf(structuralEqualityPolicy()) { trackingUiStateSource.value.withoutIdleDraft() }
    }
    val entryDraft = remember(trackingUiStateSource) {
        derivedStateOf(structuralEqualityPolicy()) { trackingUiStateSource.value.entryDraft() }
    }
    val alwaysShowNotifications by trackingViewModel.alwaysShowNotifications.collectAsState(initial = false)
    val appTheme by trackingViewModel.appTheme.collectAsState(initial = AppThemeMode.SYSTEM)
    val optimisticRefresh by trackingViewModel.optimisticRefresh.collectAsState(initial = true)
    val liveUpdateEnabled by trackingViewModel.liveUpdateEnabled.collectAsState(initial = false)
    val autoClearEntryFieldsAfterStop by trackingViewModel.autoClearEntryFieldsAfterStop.collectAsState(initial = true)
    val clearDescriptionAfterStop by trackingViewModel.clearDescriptionAfterStop.collectAsState(initial = false)
    val longTimerHours by trackingViewModel.longTimerHours.collectAsState(initial = 4)
    val hasSnapshot by trackingViewModel.hasSnapshot.collectAsState()
    val snapshotHydrated by trackingViewModel.snapshotHydrated.collectAsState()

    // Load user data when logged in
    LaunchedEffect(authState) {
        if (authState == AuthState.LoggedIn) {
            authViewModel.loadUserData()
        }
    }

    LaunchedEffect(handoffOrganizationId, authUiState.memberships) {
        val organizationId = handoffOrganizationId ?: return@LaunchedEffect
        val membership = authUiState.memberships.firstOrNull { it.organizationId == organizationId } ?: return@LaunchedEffect
        val tracking = trackingViewModel.uiState.value
        // Like the menu and Settings: switching organization while a timer runs or is paused would
        // orphan it, so a handoff then keeps the current organization.
        if (!tracking.isTracking && !tracking.isPaused) authViewModel.selectMembership(membership)
        onHandoffConsumed()
    }

    // Show the organization's local history and full catalogue from Room right away; the network
    // refresh below may wait for account revalidation.
    LaunchedEffect(authUiState.currentMembership?.id, snapshotHydrated) {
        val membership = authUiState.currentMembership ?: return@LaunchedEffect
        if (snapshotHydrated) trackingViewModel.observeLocalData(membership.organizationId, membership.id)
    }

    // Load all tracking data when user and membership are available
    LaunchedEffect(
        authUiState.currentMembership?.id,
        authUiState.hasRevalidated,
        optimisticRefresh,
        hasSnapshot,
        snapshotHydrated,
    ) {
        val membership = authUiState.currentMembership
        val mayRefresh = snapshotHydrated &&
            membership != null &&
            (!hasSnapshot || optimisticRefresh || authUiState.hasRevalidated)
        if (membership != null && mayRefresh) {
            trackingViewModel.loadAllData(
                organizationId = membership.organizationId,
                memberId = membership.id,
            )
        }
    }

    when {
        authState == AuthState.LoggedIn -> {
            val navController = rememberNavController()
            val currentMembership = authUiState.currentMembership
            LaunchedEffect(pendingReviewRoute, currentMembership?.organizationId) {
                val route = pendingReviewRoute
                if (route != null && currentMembership != null) {
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                    onPendingReviewRouteConsumed()
                }
            }
            LaunchedEffect(calendarInitialDate, currentMembership?.organizationId) {
                if (calendarInitialDate != null && currentMembership != null) {
                    navController.navigateToMenuDestination(Screen.Calendar.route)
                }
            }
            // "Adjust end time" from the notification opens the running entry in Time Tracker, so
            // go there now instead of leaving the request for whenever Time Tracker is next shown.
            LaunchedEffect(editActiveEntryRequested) {
                if (editActiveEntryRequested) navController.navigateToMenuDestination(Screen.Track.route)
            }
            MainNavHost(
                navController = navController,
                onPrivacyLogout = { authViewModel.logout() },
                menuHeader = {
                    MainMenuHeader(
                        userName = authUiState.user?.name,
                        userEmail = authUiState.user?.email,
                        organizationName = currentMembership?.organization?.name,
                        memberships = authUiState.memberships,
                        currentMembershipId = currentMembership?.id,
                        // Switching organization while a timer runs would orphan the running entry.
                        canSwitchOrganization = authUiState.memberships.size > 1 &&
                            !trackingUiState.isTracking &&
                            !trackingUiState.isPaused,
                        onMembershipChange = authViewModel::selectMembership,
                    )
                },
                reviewContent = {
                    // Opened from Settings or a review notification, so it goes back to its opener.
                    ReviewScreen(
                        onBack = { navController.popBackStack() },
                        onOpenReminderSettings = {
                            navController.navigate(ReviewRoutes.REMINDER_SETTINGS)
                        },
                        onOpenManageTemplates = {
                            navController.navigate(ReviewRoutes.MANAGE_TEMPLATES)
                        },
                        onOpenEndOfDayReview = {
                            navController.navigate(ReviewRoutes.END_OF_DAY)
                        },
                    )
                },
                trackContent = {
                    TrackingScreen(
                        user = authUiState.user,
                        currentMembership = authUiState.currentMembership,
                        uiState = trackingUiState,
                        elapsedSeconds = trackingViewModel.elapsedSeconds,
                        autoClearEntryFieldsAfterStop = autoClearEntryFieldsAfterStop,
                        longTimerHours = longTimerHours,
                        editActiveEntryRequested = editActiveEntryRequested,
                        onEditActiveEntryConsumed = onEditActiveEntryConsumed,
                        onRefresh = {
                            authUiState.currentMembership?.let { membership ->
                                trackingViewModel.loadAllData(
                                    organizationId = membership.organizationId,
                                    memberId = membership.id,
                                    userInitiated = true,
                                )
                            }
                        },
                        onStartTracking = {
                            authUiState.currentMembership?.let { membership ->
                                authUiState.user?.let { user ->
                                    trackingViewModel.startTimeEntry(
                                        organizationId = membership.organizationId,
                                        memberId = membership.id,
                                        userId = user.id,
                                    )
                                }
                            }
                        },
                        onStopTracking = { trackingViewModel.stopTimeEntry() },
                        onPauseTracking = { trackingViewModel.pauseTimeEntry() },
                        onStopTrackingWithEdits = { edits -> trackingViewModel.stopTimeEntry(edits) },
                        onPauseTrackingWithEdits = { edits -> trackingViewModel.pauseTimeEntry(edits) },
                        entryDraft = { entryDraft.value },
                        onResumeTracking = {
                            authUiState.currentMembership?.let { membership ->
                                authUiState.user?.let { user ->
                                    trackingViewModel.resumeTimeEntry(
                                        organizationId = membership.organizationId,
                                        memberId = membership.id,
                                        userId = user.id,
                                    )
                                }
                            }
                        },
                        onDescriptionChange = { description ->
                            trackingViewModel.updateDescription(description)
                        },
                        onProjectChange = { projectId ->
                            trackingViewModel.updateProject(projectId)
                        },
                        onTaskChange = { taskId ->
                            trackingViewModel.updateTask(taskId)
                        },
                        onResetEntryFields = trackingViewModel::resetEntryFields,
                        onTagsChange = { tags ->
                            trackingViewModel.updateTags(tags)
                        },
                        onBillableChange = { billable ->
                            trackingViewModel.updateBillable(billable)
                        },
                        onUpdatePastEntry = {
                                entry: TimeEntry,
                                description: String?,
                                projectId: String?,
                                taskId: String?,
                                tags: List<String>,
                                billable: Boolean,
                                start: String,
                                end: String?,
                            ->
                            trackingViewModel.updatePastTimeEntry(
                                timeEntry = entry,
                                description = description,
                                projectId = projectId,
                                taskId = taskId,
                                tags = tags,
                                billable = billable,
                                start = start,
                                end = end,
                            )
                        },
                        onCreateEntry = {
                                description: String?,
                                projectId: String?,
                                taskId: String?,
                                tags: List<String>,
                                billable: Boolean,
                                start: String,
                                end: String,
                            ->
                            authUiState.currentMembership?.let { membership ->
                                authUiState.user?.let { user ->
                                    trackingViewModel.createManualTimeEntry(
                                        organizationId = membership.organizationId,
                                        memberId = membership.id,
                                        userId = user.id,
                                        description = description,
                                        projectId = projectId,
                                        taskId = taskId,
                                        tags = tags,
                                        billable = billable,
                                        start = start,
                                        end = end,
                                    )
                                }
                            }
                        },
                        onDeleteEntry = { timeEntryId ->
                            trackingViewModel.deleteTimeEntry(timeEntryId = timeEntryId)
                        },
                        onDuplicateEntry = trackingViewModel::duplicateTimeEntry,
                        onSplitEntry = trackingViewModel::splitTimeEntry,
                        onEntryToEditConsumed = trackingViewModel::consumeEntryToEdit,
                        onUndoDelete = trackingViewModel::undoDelete,
                        onRetrySync = {
                            val organizationId = authUiState.currentMembership?.organizationId
                            if (organizationId != null) {
                                trackingViewModel.retryAllSync(organizationId)
                            } else {
                                trackingViewModel.retrySync()
                            }
                        },
                        onRetrySyncEntry = trackingViewModel::retrySync,
                        onOpenSyncCenter = {
                            navController.navigate(SyncRoutes.SYNC_CENTER)
                        },
                        onContinueEntry = { entry ->
                            authUiState.currentMembership?.let { membership ->
                                authUiState.user?.let { user ->
                                    trackingViewModel.continueEntry(
                                        entry = entry,
                                        organizationId = membership.organizationId,
                                        memberId = membership.id,
                                        userId = user.id,
                                    )
                                }
                            }
                        },
                        onLoadMoreEntries = trackingViewModel::loadMoreTimeEntries,
                        onLoadNewerEntries = trackingViewModel::loadNewerTimeEntries,
                        onJumpToDate = trackingViewModel::jumpToHistoryDate,
                        onHistoryJumpConsumed = trackingViewModel::consumeHistoryJump,
                        onClearError = trackingViewModel::clearError,
                    )
                },
                settingsContent = {
                    SettingsScreen(
                        user = authUiState.user,
                        memberships = authUiState.memberships,
                        currentMembership = authUiState.currentMembership,
                        canSwitchOrganization = authUiState.memberships.size > 1 &&
                            !trackingUiState.isTracking &&
                            !trackingUiState.isPaused,
                        serverEndpoint = configState.endpoint,
                        clientId = configState.clientId,
                        appTheme = appTheme,
                        alwaysShowNotifications = alwaysShowNotifications,
                        optimisticRefresh = optimisticRefresh,
                        liveUpdateEnabled = liveUpdateEnabled,
                        autoClearEntryFieldsAfterStop = autoClearEntryFieldsAfterStop,
                        clearDescriptionAfterStop = clearDescriptionAfterStop,
                        longTimerHours = longTimerHours,
                        onMembershipChange = authViewModel::selectMembership,
                        onAppThemeChange = trackingViewModel::setAppTheme,
                        onAlwaysShowNotificationsChange = trackingViewModel::setAlwaysShowNotifications,
                        onOptimisticRefreshChange = trackingViewModel::setOptimisticRefresh,
                        onLiveUpdateEnabledChange = trackingViewModel::setLiveUpdateEnabled,
                        onAutoClearEntryFieldsAfterStopChange = trackingViewModel::setAutoClearEntryFieldsAfterStop,
                        onClearDescriptionAfterStopChange = trackingViewModel::setClearDescriptionAfterStop,
                        onLongTimerHoursChange = trackingViewModel::setLongTimerHours,
                        onOpenReview = { navController.navigate(Screen.Review.route) },
                        onOpenReminderSettings = { navController.navigate(ReviewRoutes.REMINDER_SETTINGS) },
                        onOpenManageTemplates = { navController.navigate(ReviewRoutes.MANAGE_TEMPLATES) },
                        onOpenSyncCenter = { navController.navigate(SyncRoutes.SYNC_CENTER) },
                        onOpenPrivacy = { navController.navigate(SettingsRoutes.PRIVACY) },
                        onLogout = authViewModel::logout,
                    )
                },
                calendarContent = {
                    if (currentMembership != null) {
                        CalendarScreen(
                            organizationId = currentMembership.organizationId,
                            memberId = currentMembership.id,
                            initialDate = calendarInitialDate,
                            onInitialDateConsumed = onCalendarInitialDateConsumed,
                            runningEntry = trackingUiState.currentTimeEntry,
                            elapsedSeconds = trackingViewModel.elapsedSeconds,
                            projects = trackingUiState.projects,
                            clients = trackingUiState.clients,
                            tasks = trackingUiState.tasks,
                            tags = trackingUiState.tags,
                            breaksEnabled = currentMembership.organization.breaksEnabled,
                            onSaveEntry = { entry, description, projectId, taskId, entryTags, billable, start, end ->
                                trackingViewModel.updatePastTimeEntry(
                                    timeEntry = entry,
                                    description = description,
                                    projectId = projectId,
                                    taskId = taskId,
                                    tags = entryTags,
                                    billable = billable,
                                    start = start,
                                    end = end,
                                )
                            },
                            onCreateBreakEntry = { description, start, end ->
                                authUiState.user?.let { user ->
                                    trackingViewModel.createManualTimeEntry(
                                        organizationId = currentMembership.organizationId,
                                        memberId = currentMembership.id,
                                        userId = user.id,
                                        description = description,
                                        projectId = null,
                                        taskId = null,
                                        tags = emptyList(),
                                        billable = false,
                                        start = start,
                                        end = end,
                                        type = TimeEntryType.BREAK,
                                    )
                                }
                            },
                            onMoveEntry = { entry, start, end ->
                                trackingViewModel.updatePastTimeEntry(
                                    timeEntry = entry,
                                    description = entry.description,
                                    projectId = entry.projectId,
                                    taskId = entry.taskId,
                                    tags = entry.tags.map { it.id },
                                    billable = entry.billable,
                                    start = start,
                                    end = end,
                                )
                            },
                            onCreateEntry = { description, projectId, taskId, entryTags, billable, start, end ->
                                authUiState.user?.let { user ->
                                    trackingViewModel.createManualTimeEntry(
                                        organizationId = currentMembership.organizationId,
                                        memberId = currentMembership.id,
                                        userId = user.id,
                                        description = description,
                                        projectId = projectId,
                                        taskId = taskId,
                                        tags = entryTags,
                                        billable = billable,
                                        start = start,
                                        end = end,
                                    )
                                }
                            },
                            onCreateProject = { name, clientId, onResult ->
                                trackingViewModel.createCalendarProject(
                                    currentMembership.organizationId,
                                    name,
                                    clientId,
                                    onResult,
                                )
                            },
                            onCreateClient = { name, onResult ->
                                trackingViewModel.createCalendarClient(
                                    currentMembership.organizationId,
                                    name,
                                    onResult,
                                )
                            },
                            onCreateTask = { name, projectId, onResult ->
                                trackingViewModel.createCalendarTask(
                                    currentMembership.organizationId,
                                    name,
                                    projectId,
                                    onResult,
                                )
                            },
                            onCreateTag = { name, onResult ->
                                trackingViewModel.createCalendarTag(
                                    currentMembership.organizationId,
                                    name,
                                    onResult,
                                )
                            },
                            onDeleteEntry = trackingViewModel::deleteTimeEntry,
                            // The Calendar opens its own editor; do not leave the Time Tracker a
                            // request to open the copy the next time it is shown.
                            onDuplicateEntry = { entryId -> trackingViewModel.duplicateTimeEntry(entryId, openEditor = false) },
                            onSplitEntry = { entryId, atIso -> trackingViewModel.splitTimeEntry(entryId, atIso, openEditor = false) },
                            onStopEntry = { trackingViewModel.stopTimeEntry() },
                            // Stops a running or ends a paused timer first, as the history play button does.
                            onContinueEntry = { entry ->
                                authUiState.user?.let { user ->
                                    trackingViewModel.continueEntry(
                                        entry = entry,
                                        organizationId = currentMembership.organizationId,
                                        memberId = currentMembership.id,
                                        userId = user.id,
                                    )
                                }
                            },
                            onUndoDelete = trackingViewModel::undoDelete,
                            onRetrySyncEntry = trackingViewModel::retrySync,
                            onDiscardFailedSync = trackingViewModel::discardFailedSync,
                            onOpenSyncCenter = {
                                navController.navigate(SyncRoutes.SYNC_CENTER)
                            },
                            preventOverlap = currentMembership.organization.preventOverlappingTimeEntries,
                        )
                    }
                },
                statsContent = {
                    StatisticsScreen()
                },
            )
        }

        authState == AuthState.LoggedOut -> {
            LoginScreen(
                uiState = authUiState,
                configState = configState,
                onLoginClick = {
                    authViewModel.startOAuthFlow()
                },
                onConfigSave = { endpoint, clientId ->
                    authViewModel.saveOAuthConfig(endpoint, clientId)
                },
                onConfigReset = {
                    authViewModel.resetOAuthConfig()
                },
                onTestConnection = authViewModel::testConnection,
                onClearAuthUrl = {
                    authViewModel.clearAuthUrl()
                },
            )
        }

        else -> Unit
    }
}
