/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.calendar.DeviceCalendarEvent
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Organization
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.data.repository.EntryTemplate
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.inbox.InboxIssue
import dev.tricked.solidverdant.domain.inbox.InboxIssueType
import dev.tricked.solidverdant.domain.inbox.MissingField
import dev.tricked.solidverdant.ui.calendar.CalendarUiState
import dev.tricked.solidverdant.ui.calendar.CalendarViewMode
import dev.tricked.solidverdant.ui.calendar.DayBucket
import dev.tricked.solidverdant.ui.calendar.MonthCalendarView
import dev.tricked.solidverdant.ui.calendar.WeekCalendarView
import dev.tricked.solidverdant.ui.components.EditTimeEntryDialog
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.navigation.MainMenuHeader
import dev.tricked.solidverdant.ui.navigation.MainMenuSheet
import dev.tricked.solidverdant.ui.navigation.MainTopBar
import dev.tricked.solidverdant.ui.review.InboxHeader
import dev.tricked.solidverdant.ui.review.InboxIssueCard
import dev.tricked.solidverdant.ui.review.InboxIssueCardActions
import dev.tricked.solidverdant.ui.review.ReviewContent
import dev.tricked.solidverdant.ui.review.ReviewDayUiState
import dev.tricked.solidverdant.ui.review.ReviewItem
import dev.tricked.solidverdant.ui.review.ReviewItemType
import dev.tricked.solidverdant.ui.review.ReviewProject
import dev.tricked.solidverdant.ui.settings.SettingsContent
import dev.tricked.solidverdant.ui.statistics.EstimateProgress
import dev.tricked.solidverdant.ui.statistics.MetricDelta
import dev.tricked.solidverdant.ui.statistics.PeriodComparison
import dev.tricked.solidverdant.ui.statistics.StatCatalog
import dev.tricked.solidverdant.ui.statistics.StatRange
import dev.tricked.solidverdant.ui.statistics.StatisticsAggregator
import dev.tricked.solidverdant.ui.statistics.StatisticsContent
import dev.tricked.solidverdant.ui.statistics.StatisticsUiState
import dev.tricked.solidverdant.ui.statistics.TrendGranularity
import dev.tricked.solidverdant.ui.templates.TemplateResolver
import dev.tricked.solidverdant.ui.templates.TemplateRow
import dev.tricked.solidverdant.ui.templates.templateDisplayLabel
import dev.tricked.solidverdant.ui.templates.templateProjectTaskSummary
import dev.tricked.solidverdant.ui.tracking.ActiveTimerBar
import dev.tricked.solidverdant.ui.tracking.StartTimerForm
import dev.tricked.solidverdant.ui.tracking.TimeTrackerTopBar
import dev.tricked.solidverdant.ui.tracking.TimerFab
import dev.tricked.solidverdant.ui.tracking.TrackingUiState
import dev.tricked.solidverdant.ui.tracking.trackingHistoryItems
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import dev.tricked.solidverdant.ui.navigation.Screen as NavScreen

/**
 * Generates the README screenshot set on the JVM — no emulator/device.
 *
 * Run with:  ./gradlew :app:recordRoborazziDebug
 *
 * Every screen is rendered across the [ScreenshotMatrix] (theme x device) into
 * .github/screenshots/generated/, and the Neo-dark + phone variant is additionally written to
 * .github/screenshots/readme/ as the cohesive hero set referenced by README.md.
 *
 * Every feature is rendered inside the production app scaffold and bottom navigation, using its
 * real top-level (or content) composable with deterministic state and no-op callbacks. Hilt and
 * ViewModels are intentionally not booted so captures remain deterministic and offline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class ReadmeScreenshotsTest {

    /** One README screen: the production shell pieces around it, then its content. */
    private class Screen(
        val name: String,
        val header: (@Composable () -> Unit)? = null,
        val pushedTitleRes: Int? = null,
        val bottomBar: @Composable () -> Unit = {},
        val fab: @Composable () -> Unit = {},
        val content: @Composable () -> Unit,
    )

    @Composable
    private fun Screen.Shell() {
        ScreenshotHost.AppShell(
            header = header,
            pushedTitleRes = pushedTitleRes,
            bottomBar = bottomBar,
            fab = fab,
            content = content,
        )
    }

    @Test
    fun captureReadmeAndMatrix() {
        val screens = buildScreens()
        for (locale in ScreenshotMatrix.locales) {
            for (device in ScreenshotMatrix.devices) {
                for (theme in ScreenshotMatrix.themes) {
                    for (screen in screens) {
                        val localeSuffix = if (locale == LocaleAxis.ENGLISH) "" else "-${locale.id}"
                        ScreenshotHost.capture(
                            theme = theme,
                            device = device,
                            locale = locale,
                            filePath = ScreenshotHost.outputPath(
                                ".github",
                                "screenshots",
                                "generated",
                                "${screen.name}-${theme.id}-${device.id}$localeSuffix.png",
                            ),
                            content = { screen.Shell() },
                        )
                        if (locale == LocaleAxis.ENGLISH &&
                            theme == ScreenshotMatrix.readmeTheme &&
                            device == ScreenshotMatrix.readmeDevice
                        ) {
                            ScreenshotHost.capture(
                                theme = theme,
                                device = device,
                                locale = locale,
                                filePath = ScreenshotHost.outputPath(
                                    ".github",
                                    "screenshots",
                                    "readme",
                                    "${screen.name}.png",
                                ),
                                content = { screen.Shell() },
                            )
                        }
                    }
                }
            }
        }
    }

    private val timeTrackerHeader: @Composable () -> Unit = {
        TimeTrackerTopBar(syncing = false, onRefresh = {}, onRequestNotifications = null, onSearch = {})
    }

    /** The Calendar header and new-entry button; the calendar views below are its body. */
    private val calendarHeader: @Composable () -> Unit = {
        MainTopBar(
            title = stringResource(R.string.nav_calendar),
            actions = {
                IconButton(onClick = {}) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.calendar_more_actions))
                }
            },
        )
    }
    private val addEntryFab: @Composable () -> Unit = {
        FloatingActionButton(
            onClick = {},
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_time_entry))
        }
    }
    private val idleTimerFab: @Composable () -> Unit = {
        TimerFab(timerActive = false, expanded = false, onExpandedChange = {}, onStartTimer = {}, onAddManual = {})
    }
    private val reviewHeader: @Composable () -> Unit = {
        MainTopBar(title = stringResource(R.string.review_title))
    }

    // ---------------------------------------------------------------------------------------------
    // Fabricated realistic sample data. Dates are pinned to June 2026 (never "today") so the
    // calendar's live current-time marker stays out of the golden images.
    // ---------------------------------------------------------------------------------------------

    private val zone = ZoneId.of("UTC")

    private val projects = listOf(
        Project(id = "p1", name = "Website Redesign", color = "#386A20", clientId = "c1"),
        Project(id = "p2", name = "Internal Tools", color = "#386666"),
        Project(id = "p3", name = "Client — Acme", color = "#8A5A00", isArchived = true),
    )
    private val tasks = listOf(
        Task(id = "t1", name = "Landing page", projectId = "p1", createdAt = "", updatedAt = ""),
        Task(id = "t2", name = "Design system", projectId = "p1", createdAt = "", updatedAt = ""),
    )
    private val tags = listOf(Tag("tag1", "focus"), Tag("tag2", "meeting"))
    private val clients = listOf(Client(id = "c1", name = "Acme Corp"))

    private fun entry(
        id: String,
        description: String?,
        startIso: String,
        endIso: String?,
        durationSeconds: Int,
        projectId: String? = "p1",
        taskId: String? = null,
        billable: Boolean = true,
        entryTags: List<Tag> = emptyList(),
    ) = TimeEntry(
        id = id,
        description = description,
        userId = "u1",
        start = startIso,
        end = endIso,
        duration = durationSeconds,
        taskId = taskId,
        projectId = projectId,
        tags = entryTags,
        billable = billable,
        organizationId = "org1",
    )

    private val historyEntries = listOf(
        entry("e1", "Design review", "2026-06-10T09:00:00Z", "2026-06-10T10:15:00Z", 4500, taskId = "t2", entryTags = listOf(tags[1])),
        entry("e2", "Landing page build", "2026-06-10T10:30:00Z", "2026-06-10T12:45:00Z", 8100, taskId = "t1", entryTags = listOf(tags[0])),
        entry("e3", "Standup", "2026-06-10T13:15:00Z", "2026-06-10T13:35:00Z", 1200, projectId = "p2", billable = false),
        entry("e4", "Bug triage", "2026-06-09T14:00:00Z", "2026-06-09T15:30:00Z", 5400, projectId = "p2", billable = false),
        entry(
            "e5",
            "Client call — Acme",
            "2026-06-09T16:00:00Z",
            "2026-06-09T16:45:00Z",
            2700,
            projectId = "p1",
            entryTags = listOf(tags[1]),
        ),
        entry("e6", "Landing page build", "2026-06-10T07:00:00Z", "2026-06-10T08:30:00Z", 5400, taskId = "t1", entryTags = listOf(tags[0])),
    )

    private val syncOperations = listOf(
        TimeEntryRepository.SyncOperation(
            entryId = "e3",
            type = OutboxOpType.CREATE,
            status = TimeEntryRepository.EntrySyncStatus.PENDING,
            attemptCount = 0,
            error = null,
        ),
        TimeEntryRepository.SyncOperation(
            entryId = "e4",
            type = OutboxOpType.UPDATE,
            status = TimeEntryRepository.EntrySyncStatus.FAILED,
            attemptCount = 3,
            error = "429 rate limited",
        ),
    )

    private val runningState = TrackingUiState(
        isTracking = true,
        elapsedSeconds = 5_112,
        currentTimeEntry = entry("running", "Landing page build", "2026-06-10T14:00:00Z", null, 0, taskId = "t1"),
        projects = projects,
        tasks = tasks,
        clients = clients,
        timeEntries = historyEntries,
        hasLoadedTimeEntries = true,
        editingDescription = "Landing page build",
        editingProjectId = "p1",
        editingTaskId = "t1",
        editingTags = listOf("tag1"),
        editingBillable = true,
        syncOperations = syncOperations,
    )

    private fun groupedHistory(): Map<LocalDate, List<TimeEntry>> = historyEntries.groupBy { LocalDate.parse(it.start.substring(0, 10)) }
        .toSortedMap(compareByDescending { it })

    @Suppress("LongMethod")
    private fun buildScreens(): List<Screen> = listOf(
        // 1. Time Tracker — history with the running timer docked at the bottom.
        Screen(
            name = "track",
            header = timeTrackerHeader,
            bottomBar = {
                ActiveTimerBar(
                    uiState = runningState,
                    elapsedSeconds = runningState.elapsedSeconds,
                    onStop = {},
                    onPause = {},
                    onResume = {},
                    onEditActiveEntry = {},
                )
            },
            fab = { TimerFab(timerActive = true, expanded = false, onExpandedChange = {}, onStartTimer = {}, onAddManual = {}) },
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                trackingHistoryItems(
                    uiState = runningState,
                    groupedEntries = groupedHistory(),
                    onEdit = {},
                    onDelete = {},
                    onDateClick = {},
                )
            }
        },
        // 1b. Time Tracker idle — the start-timer sheet for the next entry over the history.
        Screen(name = "track-idle", header = timeTrackerHeader) {
            val state = TrackingUiState(
                projects = projects,
                tasks = tasks,
                tags = tags,
                clients = clients,
                timeEntries = historyEntries,
                hasLoadedTimeEntries = true,
                editingDescription = "",
                editingProjectId = "p1",
                editingTaskId = "t2",
                editingBillable = true,
            )
            Box(Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    trackingHistoryItems(
                        uiState = state,
                        groupedEntries = groupedHistory(),
                        onEdit = {},
                        onDelete = {},
                        onDateClick = {},
                        onContinue = {},
                    )
                }
                // A modal sheet opens in its own window, which Roborazzi does not capture; draw
                // the same scrim and sheet in place.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
                        Text(
                            text = stringResource(R.string.start_timer_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        StartTimerForm(
                            uiState = state,
                            onDescriptionChange = {},
                            onProjectChange = {},
                            onTaskChange = {},
                            onTagsChange = {},
                            onBillableChange = {},
                            onStart = {},
                        )
                    }
                }
            }
        },
        // 2. History list — several entries grouped by day, with sync chips.
        Screen(name = "history", header = timeTrackerHeader, fab = idleTimerFab) {
            val state = TrackingUiState(
                projects = projects,
                tasks = tasks,
                tags = tags,
                timeEntries = historyEntries,
                hasLoadedTimeEntries = true,
                syncOperations = syncOperations,
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                trackingHistoryItems(
                    uiState = state,
                    groupedEntries = groupedHistory(),
                    onEdit = {},
                    onDelete = {},
                    onDateClick = {},
                    onContinue = {},
                )
            }
        },
        // 3. Calendar — month view with sample entries.
        Screen(name = "calendar-month", header = calendarHeader, fab = addEntryFab) {
            val d10 = LocalDate.of(2026, 6, 10)
            val d09 = LocalDate.of(2026, 6, 9)
            val state = CalendarUiState(
                viewMode = CalendarViewMode.MONTH,
                zone = ZoneOffset.UTC,
                visibleMonth = YearMonth.of(2026, 6),
                selectedDate = d10,
                isLoading = false,
                bucketsByDate = mapOf(
                    d10 to DayBucket(
                        d10,
                        historyEntries.filter { it.start.startsWith("2026-06-10") } +
                            listOf(
                                entry("m-short-1", "Setup", "2026-06-10T13:36:00Z", "2026-06-10T13:39:00Z", 180),
                                entry("m-short-2", "Run", "2026-06-10T13:41:00Z", "2026-06-10T13:42:00Z", 60),
                            ),
                        14_040,
                    ),
                    d09 to DayBucket(d09, historyEntries.filter { it.start.startsWith("2026-06-09") }, 8_100),
                ),
            )
            MonthCalendarView(
                state = state,
                onSelectDate = {},
                onPreviousMonth = {},
                onNextMonth = {},
                onEntryClick = {},
                projects = projects,
                tasks = tasks,
            )
        },
        // 3b. Calendar — day view: date and total, the week strip, grey entry cards.
        Screen(name = "calendar-day", header = calendarHeader, fab = addEntryFab) {
            val d10 = LocalDate.of(2026, 6, 10)
            val dayEntries = historyEntries.filter { it.start.startsWith("2026-06-10") }
            WeekCalendarView(
                state = CalendarUiState(
                    viewMode = CalendarViewMode.DAY,
                    zone = ZoneOffset.UTC,
                    selectedDate = d10,
                    weekAnchor = d10,
                    weekStart = DayOfWeek.MONDAY,
                    visibleDays = listOf(d10),
                    isLoading = false,
                    bucketsByDate = mapOf(d10 to DayBucket(d10, dayEntries, dayEntries.sumOf { it.duration ?: 0 }.toLong())),
                ),
                onSelectDate = {},
                onEntryClick = {},
                onPrevious = {},
                onNext = {},
                projects = projects,
                tasks = tasks,
                clients = clients,
            )
        },
        // 4. Calendar — week view with a couple of overlay calendar events.
        Screen(name = "calendar-week", header = calendarHeader, fab = addEntryFab) {
            val week = (8..14).map { LocalDate.of(2026, 6, it) } // Mon..Sun
            val mon = week[0]
            val tue = week[1]
            val state = CalendarUiState(
                viewMode = CalendarViewMode.WEEK,
                zone = ZoneOffset.UTC,
                selectedDate = mon,
                weekStart = DayOfWeek.MONDAY,
                dayCount = 7,
                visibleDays = week,
                isLoading = false,
                overlayEnabled = true,
                bucketsByDate = mapOf(
                    mon to DayBucket(
                        mon,
                        listOf(
                            entry("w1", "Deep work", "2026-06-08T09:00:00Z", "2026-06-08T12:00:00Z", 10_800, taskId = "t1"),
                            entry("w3", "Design review", "2026-06-08T10:00:00Z", "2026-06-08T11:30:00Z", 5_400, taskId = "t2"),
                            entry("w-short-1", "Setup", "2026-06-08T13:36:00Z", "2026-06-08T13:39:00Z", 180, taskId = "t1"),
                            entry("w-short-2", "Run", "2026-06-08T13:41:00Z", "2026-06-08T13:42:00Z", 60, taskId = "t1"),
                        ),
                        16_440,
                    ),
                    tue to
                        DayBucket(
                            tue,
                            listOf(entry("w2", "Design system", "2026-06-09T10:00:00Z", "2026-06-09T11:30:00Z", 5_400, taskId = "t2")),
                            5_400,
                        ),
                ),
                overlayEvents = listOf(
                    DeviceCalendarEvent(
                        instanceId = 1,
                        eventId = 1,
                        calendarId = "1",
                        title = "1:1 with Alex",
                        startUtcMs = Instant.parse("2026-06-08T13:00:00Z").toEpochMilli(),
                        endUtcMs = Instant.parse("2026-06-08T14:00:00Z").toEpochMilli(),
                        allDay = false,
                        colorArgb = 0xFF3F51B5.toInt(),
                    ),
                    DeviceCalendarEvent(
                        instanceId = 2,
                        eventId = 2,
                        calendarId = "1",
                        title = "Sprint planning",
                        startUtcMs = Instant.parse("2026-06-09T15:00:00Z").toEpochMilli(),
                        endUtcMs = Instant.parse("2026-06-09T16:00:00Z").toEpochMilli(),
                        allDay = false,
                        colorArgb = 0xFFE91E63.toInt(),
                    ),
                ),
            )
            WeekCalendarView(
                state = state,
                onSelectDate = {},
                onEntryClick = {},
                onPrevious = {},
                onNext = {},
                projects = projects,
            )
        },
        // 4b. Side menu — the account, organization and destinations.
        Screen(name = "menu", header = timeTrackerHeader) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))) {
                MainMenuSheet(
                    selectedRoute = NavScreen.Track.route,
                    onNavigate = {},
                    modifier = Modifier.fillMaxHeight(),
                    header = {
                        MainMenuHeader(
                            userName = "Alex Morgan",
                            userEmail = "alex@acme.studio",
                            organizationName = "Acme Studio",
                            memberships = emptyList(),
                            currentMembershipId = "m1",
                            canSwitchOrganization = true,
                            onMembershipChange = {},
                        )
                    },
                )
            }
        },
        // 5. Dashboard — a full week stacked by four projects.
        Screen(name = "statistics") {
            val dashboardProjects = listOf(
                Project(id = "d1", name = "Website Redesign", color = "#5E5CE6"),
                Project(id = "d2", name = "Mobile App", color = "#FF9F0A"),
                Project(id = "d3", name = "Client — Acme", color = "#30B0C7"),
                Project(id = "d4", name = "Internal Tools", color = "#34C759"),
            )
            // Minutes per project for Mon 8 – Sun 14 June 2026.
            val minutesByDay = listOf(
                listOf(210, 95, 60, 40),
                listOf(150, 140, 0, 55),
                listOf(240, 60, 90, 30),
                listOf(120, 170, 45, 50),
                listOf(180, 80, 75, 20),
                listOf(0, 90, 0, 0),
                listOf(0, 0, 0, 0),
            )
            val weekStart = LocalDate.of(2026, 6, 8)
            val weekEntries = minutesByDay.flatMapIndexed { day, minutes ->
                var cursor = weekStart.plusDays(day.toLong()).atTime(8, 30).toInstant(ZoneOffset.UTC)
                minutes.mapIndexedNotNull { p, mins ->
                    if (mins == 0) return@mapIndexedNotNull null
                    val end = cursor.plusSeconds(mins * 60L)
                    entry(
                        "w$day$p",
                        null,
                        cursor.toString(),
                        end.toString(),
                        mins * 60,
                        projectId = dashboardProjects[p].id,
                        billable =
                        p != 3,
                    )
                        .also { cursor = end.plusSeconds(15 * 60L) }
                }
            }
            val weekRange = weekStart..weekStart.plusDays(6)
            val summary = StatisticsAggregator.compute(
                entries = weekEntries,
                projects = dashboardProjects,
                rangeStart = weekRange.start,
                rangeEnd = weekRange.endInclusive,
                zone = zone,
                granularity = TrendGranularity.DAY,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
            StatisticsContent(
                state = StatisticsUiState(
                    isLoading = false,
                    range = StatRange.LastWeek,
                    catalog = StatCatalog(projects = dashboardProjects),
                    summary = summary,
                    comparison = PeriodComparison(
                        total = MetricDelta(summary.totalSeconds, 29L * 3600L + 40L * 60L),
                        previousStart = weekRange.start.minusWeeks(1),
                        previousEnd = weekRange.endInclusive.minusWeeks(1),
                    ),
                    estimateProgress = listOf(
                        EstimateProgress("d1", "Website Redesign", "#5E5CE6", estimatedSeconds = 60 * 3600, spentSeconds = 41 * 3600),
                        EstimateProgress("d3", "Client — Acme", "#30B0C7", estimatedSeconds = 12 * 3600, spentSeconds = 13 * 3600),
                    ),
                    rangeStart = weekRange.start,
                    rangeEnd = weekRange.endInclusive,
                    granularity = TrendGranularity.DAY,
                ),
                exporting = false,
                onRangeChange = {},
                onFiltersChange = {},
                onClearFilters = {},
                onRefresh = {},
                onExport = {},
                onProjectClick = {},
                onBucketClick = {},
            )
        },
        // 6. Time Inbox — a few review issue cards.
        Screen(name = "inbox", header = reviewHeader) {
            val projectsById = projects.associateBy { it.id }
            val issues = listOf(
                InboxIssue(
                    key = "missing:e3",
                    type = InboxIssueType.MISSING_METADATA,
                    startMs = Instant.parse("2026-06-10T13:15:00Z").toEpochMilli(),
                    endMs = Instant.parse("2026-06-10T13:35:00Z").toEpochMilli(),
                    primaryEntry = historyEntries[2],
                    missingFields = setOf(MissingField.PROJECT, MissingField.TAGS),
                ),
                InboxIssue(
                    key = "overlap:e1:e2",
                    type = InboxIssueType.OVERLAP,
                    startMs = Instant.parse("2026-06-10T10:00:00Z").toEpochMilli(),
                    endMs = Instant.parse("2026-06-10T10:30:00Z").toEpochMilli(),
                    primaryEntry = historyEntries[0],
                    secondaryEntry = historyEntries[1],
                ),
                InboxIssue(
                    key = "gap:1",
                    type = InboxIssueType.GAP,
                    startMs = Instant.parse("2026-06-10T12:45:00Z").toEpochMilli(),
                    endMs = Instant.parse("2026-06-10T13:15:00Z").toEpochMilli(),
                ),
                InboxIssue(
                    key = "long:e2",
                    type = InboxIssueType.LONG_DURATION,
                    startMs = Instant.parse("2026-06-09T08:00:00Z").toEpochMilli(),
                    endMs = Instant.parse("2026-06-09T17:00:00Z").toEpochMilli(),
                    primaryEntry = historyEntries[1],
                ),
            )
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InboxHeader(
                    issueCount = issues.size,
                    isRefreshing = false,
                    showHorizonChip = true,
                    horizonLabel = stringResource(dev.tricked.solidverdant.R.string.inbox_horizon_everything),
                    onHorizonChipClick = {},
                    onRefresh = {},
                    onOpenSettings = {},
                )
                issues.forEach { issue ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        InboxIssueCard(
                            issue = issue,
                            preventOverlap = true,
                            projectsById = projectsById,
                            zone = zone,
                            actions = InboxIssueCardActions(
                                onQuickFix = {},
                                onDismiss = {},
                            ),
                        )
                    }
                }
            }
        },
        // 7. End-of-day review — the guided pane.
        Screen(name = "review", header = reviewHeader) {
            val state = ReviewDayUiState(
                loading = false,
                hasOrganization = true,
                dateEpochDay = LocalDate.of(2026, 6, 10).toEpochDay(),
                totalTrackedSeconds = 6 * 3600 + 30 * 60,
                billableSeconds = 5 * 3600,
                entryCount = 7,
                largestGapSeconds = 45 * 60,
                uncategorizedCount = 1,
                failedSyncCount = 1,
                items = listOf(
                    ReviewItem(
                        "running:e1",
                        ReviewItemType.RUNNING_TIMER,
                        "e1",
                        description = "Landing page build",
                        startIso = "2026-06-10T14:00:00Z",
                    ),
                    ReviewItem("sync:e4", ReviewItemType.FAILED_SYNC, "e4", detail = "429 rate limited"),
                    ReviewItem(
                        "uncat:e3",
                        ReviewItemType.UNCATEGORIZED,
                        "e3",
                        description = "Standup",
                        startIso = "2026-06-10T13:15:00Z",
                        endIso = "2026-06-10T13:35:00Z",
                    ),
                ),
                handledIds = emptySet(),
                projects = listOf(
                    ReviewProject("p1", "Website Redesign", "#386A20"),
                    ReviewProject("p2", "Internal Tools", "#386666"),
                ),
            )
            ReviewContent(
                state = state,
                onStop = {},
                onKeepRunning = {},
                onAdjustEnd = {},
                onRetry = {},
                onKeepAsIs = {},
                onAssign = {},
                onReviewAgain = {},
            )
        },
        // 8. Edit/create entry sheet.
        Screen(name = "edit-entry", header = timeTrackerHeader) {
            val editing = entry(
                id = "e2",
                description = "Landing page build",
                startIso = "2026-06-10T10:30:00+00:00",
                endIso = "2026-06-10T12:45:00+00:00",
                durationSeconds = 8100,
                projectId = "p1",
                taskId = "t1",
                entryTags = listOf(tags[0]),
            )
            val backgroundState = TrackingUiState(
                projects = projects,
                tasks = tasks,
                tags = tags,
                timeEntries = historyEntries,
                hasLoadedTimeEntries = true,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    trackingHistoryItems(
                        uiState = backgroundState,
                        groupedEntries = groupedHistory(),
                        onEdit = {},
                        onDelete = {},
                        onDateClick = {},
                    )
                }
                EditTimeEntryDialog(
                    entry = editing,
                    zone = ZoneId.systemDefault(),
                    projects = projects,
                    tasks = tasks,
                    tags = tags,
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _ -> },
                    existingEntries = historyEntries,
                    preventOverlap = true,
                    inlinePresentation = true,
                )
            }
        },
        // 9. Settings tab.
        Screen(name = "settings") {
            SettingsContent(
                user = User(id = "u1", name = "Alex Morgan", email = "alex@acme.studio", timezone = "Europe/Amsterdam"),
                memberships = emptyList(),
                currentMembership = Membership("m1", "owner", Organization(id = "org1", name = "Acme Studio", currency = "EUR")),
                canSwitchOrganization = true,
                serverEndpoint = "https://time.acme.studio",
                clientId = "9f3c2a71-5d1e-4c9b-a0f2-1b7e6d4c8a90",
                appTheme = dev.tricked.solidverdant.data.local.AppThemeMode.SYSTEM,
                alwaysShowNotifications = true,
                optimisticRefresh = true,
                liveUpdateEnabled = false,
                autoClearEntryFieldsAfterStop = true,
                clearDescriptionAfterStop = false,
                longTimerHours = 4,
                onMembershipChange = {},
                onAppThemeChange = {},
                onAlwaysShowNotificationsChange = {},
                onOptimisticRefreshChange = {},
                onLiveUpdateEnabledChange = {},
                onAutoClearEntryFieldsAfterStopChange = {},
                onClearDescriptionAfterStopChange = {},
                onLongTimerHoursChange = {},
                onOpenReview = {},
                onOpenReminderSettings = {},
                onOpenManageTemplates = {},
                onOpenSyncCenter = {},
                onOpenPrivacy = {},
                onLogout = {},
                liveUpdatesSupported = true,
                systemLiveUpdatesEnabled = true,
                onRequestNotificationPermission = {},
            )
        },
        // 10. Templates / favorites.
        Screen(name = "templates", pushedTitleRes = R.string.review_menu_manage_templates) {
            val templates = listOf(
                EntryTemplate("tm1", "org1", "Deep work", "p1", "t1", "Focus block", listOf("tag1"), true, true, 0, 0L),
                EntryTemplate("tm2", "org1", null, "p1", null, "Daily standup", emptyList(), false, false, 1, 0L),
                EntryTemplate("tm3", "org1", "Client call", "p3", null, null, listOf("missing-tag"), true, false, 2, 0L),
            )
            // Production draws the rows as one grouped section of lazy items.
            GroupedSection(modifier = Modifier.padding(top = 16.dp)) {
                templates.forEachIndexed { index, template ->
                    if (index > 0) GroupedDivider()
                    val resolution = TemplateResolver.resolve(template, projects, tasks, tags)
                    TemplateRow(
                        template = template,
                        resolution = resolution,
                        projectTaskSummary = templateProjectTaskSummary(template, projects, tasks),
                        label = templateDisplayLabel(template, projects),
                        canMoveUp = index > 0,
                        canMoveDown = index < templates.lastIndex,
                        onToggleFavorite = {},
                        onMoveUp = {},
                        onMoveDown = {},
                        onEdit = {},
                        onDelete = {},
                    )
                }
            }
        },
    )
}
