/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.calendar.DeviceCalendarEvent
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import dev.tricked.solidverdant.domain.time.isRunningTimeEntry
import dev.tricked.solidverdant.ui.components.EntryBlock
import dev.tricked.solidverdant.ui.components.LoadingState
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.tabular
import dev.tricked.solidverdant.ui.tracking.formatClockDuration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

private const val NARROW_CALENDAR_DAYS = 3
private const val MAX_ALL_DAY_EVENTS = 3

/**
 * Google-Calendar-style multi-day time grid. Renders [CalendarUiState.visibleDays] as columns with
 * device-calendar events drawn as faded, read-only background blocks behind the tracked time
 * entries. Overlap packing, midnight-spanning clipping, and all-day handling come from
 * [WeekCalendarLayout]; this file is presentation only.
 *
 * The hour gutter, gridlines, hour height and the tracked-entry blocks come from the shared grid
 * primitives ([HourGridlines], [CalendarHourHeight], [EntryBlock]) so the week grid and the month
 * day-timeline read as one product.
 */
@Composable
fun WeekCalendarView(
    state: CalendarUiState,
    onSelectDate: (LocalDate) -> Unit,
    onEntryClick: (TimeEntry) -> Unit,
    onMoveEntry: (TimeEntry, String, String) -> Unit = { _, _, _ -> },
    onCreateRange: (CalendarTimeRange) -> Unit = {},
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    projects: List<Project>,
    tasks: List<Task> = emptyList(),
    clients: List<Client> = emptyList(),
    syncStatusByEntryId: Map<String, EntrySyncStatus> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Never expose the unusable seven-column layout on a phone, including the first frame
        // before CalendarScreen's width effect updates the ViewModel after recreation.
        val days = if (state.viewMode == CalendarViewMode.WEEK && maxWidth < 600.dp) {
            state.visibleDays.take(NARROW_CALENDAR_DAYS)
        } else {
            state.visibleDays
        }
        WeekCalendarContent(
            state = state,
            days = days,
            onSelectDate = onSelectDate,
            onEntryClick = onEntryClick,
            onMoveEntry = onMoveEntry,
            onCreateRange = onCreateRange,
            onPrevious = onPrevious,
            onNext = onNext,
            projects = projects,
            tasks = tasks,
            clients = clients,
            syncStatusByEntryId = syncStatusByEntryId,
        )
    }
}

@Composable
private fun WeekCalendarContent(
    state: CalendarUiState,
    days: List<LocalDate>,
    onSelectDate: (LocalDate) -> Unit,
    onEntryClick: (TimeEntry) -> Unit,
    onMoveEntry: (TimeEntry, String, String) -> Unit,
    onCreateRange: (CalendarTimeRange) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    projects: List<Project>,
    tasks: List<Task>,
    clients: List<Client>,
    syncStatusByEntryId: Map<String, EntrySyncStatus>,
) {
    val zone = state.zone
    val settings = state.calendarSettings
    val hasRunningEntries = remember(state.bucketsByDate, days) {
        days.any { day -> state.bucketsByDate[day]?.entries.orEmpty().any(::isRunningTimeEntry) }
    }
    // Ticks every second only while a timer is visible, and only running blocks and the now line
    // read it. Everything else here follows the minute, so a tick does not recompose the grid.
    val clock = rememberCalendarClock(secondPrecision = hasRunningEntries)
    val minuteNow by rememberCalendarMinute(clock)
    val today = minuteNow.atZone(zone).toLocalDate()
    val locale = appLocale()
    val title = remember(days, state.viewMode, locale) { periodTitle(days, state.viewMode, locale) }

    // Precompute the per-day layouts once per data change rather than inside the render loop.
    val timedByDay = remember(state.overlayEvents, days, settings) {
        days.associateWith { layoutTimedEvents(state.overlayEvents, it, zone, settings) }
    }
    val allDayByDay = remember(state.overlayEvents, days) {
        days.associateWith { allDayEventsForDay(state.overlayEvents, it) }
    }
    val hasAnyAllDay = allDayByDay.values.any { it.isNotEmpty() }
    val hasTrackedEntries = remember(state.bucketsByDate, days) {
        days.any { state.bucketsByDate[it]?.entries?.isNotEmpty() == true }
    }
    val hasContent = hasTrackedEntries || state.overlayEvents.isNotEmpty()
    val projectsById = remember(projects) { projects.associateBy { it.id } }
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    val clientsById = remember(clients) { clients.associateBy { it.id } }
    val totalSeconds = remember(state.bucketsByDate, days) { days.sumOf { state.bucketsByDate[it]?.totalSeconds ?: 0L } }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            CalendarPeriodHeader(
                title = title,
                totalSeconds = totalSeconds,
                onPrevious = onPrevious,
                onNext = onNext,
            )
            if (state.viewMode == CalendarViewMode.DAY) {
                // Day view: the selected day's week as a strip, the day circled.
                CalendarWeekStrip(
                    days = remember(state.selectedDate, state.weekStart) { weekOf(state.selectedDate, state.weekStart) },
                    selectedDate = state.selectedDate,
                    today = today,
                    locale = locale,
                    onSelectDate = onSelectDate,
                )
            } else {
                // Day-of-week / date header aligned with the grid gutter.
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(CalendarGutterWidth))
                    days.forEach { day ->
                        DayHeaderCell(
                            day = day,
                            selected = day == state.selectedDate,
                            isToday = day == today,
                            locale = locale,
                            onSelect = { onSelectDate(day) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Subtle top-line refresh only when content is already on screen; a first, empty load uses
        // the full-content LoadingState below instead.
        if (state.isLoading && hasContent) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        HairLine()

        if (hasAnyAllDay) {
            AllDayRow(days = days, allDayByDay = allDayByDay)
            HairLine()
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading && !hasContent ->
                    LoadingState(
                        modifier = Modifier.align(Alignment.Center),
                        label = stringResource(R.string.calendar_loading_entries),
                    )

                else -> WeekGrid(
                    onPrevious = onPrevious,
                    onNext = onNext,
                    days = days,
                    today = today,
                    minuteNow = minuteNow,
                    clock = clock,
                    zone = zone,
                    settings = settings,
                    timedByDay = timedByDay,
                    state = state,
                    projectsById = projectsById,
                    tasksById = tasksById,
                    clientsById = clientsById,
                    onEntryClick = onEntryClick,
                    onMoveEntry = onMoveEntry,
                    onCreateRange = onCreateRange,
                    syncStatusByEntryId = syncStatusByEntryId,
                )
            }
        }
    }
}

@Composable
private fun WeekGrid(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    days: List<LocalDate>,
    today: LocalDate,
    minuteNow: Instant,
    clock: State<Instant>,
    zone: ZoneId,
    settings: CalendarGridSettings,
    timedByDay: Map<LocalDate, List<EventBlock>>,
    state: CalendarUiState,
    projectsById: Map<String, Project>,
    tasksById: Map<String, Task>,
    clientsById: Map<String, Client>,
    onEntryClick: (TimeEntry) -> Unit,
    onMoveEntry: (TimeEntry, String, String) -> Unit,
    onCreateRange: (CalendarTimeRange) -> Unit,
    syncStatusByEntryId: Map<String, EntrySyncStatus>,
) {
    val initialScrollHours = calendarInitialScrollHours(minuteNow, zone, settings).toFloat()
    val initialScroll = with(LocalDensity.current) { (calendarHourHeight(settings) * initialScrollHours).roundToPx() }
    val scrollState = rememberScrollState(initial = initialScroll)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(CalendarTestTags.WEEK_GRID)
            .calendarSwipePaging(onPrevious = onPrevious, onNext = onNext)
            .verticalScroll(scrollState),
    ) {
        val totalHeight = calendarTotalHeight(settings)
        Box(modifier = Modifier.fillMaxWidth().height(totalHeight)) {
            // Shared hour gutter + gridlines.
            HourGridlines(settings = settings)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .padding(start = CalendarGutterWidth),
            ) {
                days.forEachIndexed { index, day ->
                    val entries = state.bucketsByDate[day]?.entries.orEmpty()
                    // Laid out once per data change; a day with a running entry relays out each
                    // minute, while its block's live height comes from the second clock.
                    val layoutKey = calendarLayoutClockKey(entries, minuteNow)
                    val blocks = remember(entries, day, zone, settings, layoutKey) {
                        layoutTrackedEntries(entries, day, layoutKey ?: minuteNow, zone, settings)
                    }
                    DayColumn(
                        day = day,
                        isToday = day == today,
                        clock = clock,
                        zone = zone,
                        settings = settings,
                        eventBlocks = timedByDay[day].orEmpty(),
                        blocks = blocks,
                        projectsById = projectsById,
                        tasksById = tasksById,
                        clientsById = clientsById,
                        onEntryClick = onEntryClick,
                        onMoveEntry = onMoveEntry,
                        onCreateRange = onCreateRange,
                        syncStatusByEntryId = syncStatusByEntryId,
                        dayIndex = index,
                        dayCount = days.size,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** "Wed, Sep 23" over "Total: 00:00:33", with previous/next arrows on the right. */
@Composable
private fun CalendarPeriodHeader(title: String, totalSeconds: Long, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Dimens.Space16, end = Dimens.Space4, top = Dimens.Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.calendar_total, formatClockDuration(totalSeconds)),
                style = MaterialTheme.typography.titleMedium.tabular(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.calendar_show_previous),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.calendar_show_next),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One week as weekday initials over dates; the selected date sits in a filled accent circle. */
@Composable
private fun CalendarWeekStrip(
    days: List<LocalDate>,
    selectedDate: LocalDate,
    today: LocalDate,
    locale: Locale,
    onSelectDate: (LocalDate) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.Space4, vertical = Dimens.Space4)) {
        days.forEach { day ->
            DayHeaderCell(
                day = day,
                selected = day == selectedDate,
                isToday = day == today,
                locale = locale,
                onSelect = { onSelectDate(day) },
                weekdayStyle = TextStyle.NARROW,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayHeaderCell(
    day: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    locale: Locale,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    weekdayStyle: TextStyle = TextStyle.SHORT,
) {
    val colors = MaterialTheme.colorScheme
    val dateColor = when {
        selected -> colors.onPrimary
        isToday -> colors.primary
        else -> colors.onSurface
    }
    Column(
        modifier = modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable(onClick = onSelect)
            .padding(vertical = Dimens.Space4)
            .testTag("week-day-header-$day"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Space2),
    ) {
        Text(
            text = day.dayOfWeek.getDisplayName(weekdayStyle, locale),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday && !selected) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(Dimens.CalendarStripDay)
                .clip(CircleShape)
                .then(if (selected) Modifier.background(colors.primary) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isToday || selected) FontWeight.SemiBold else FontWeight.Normal,
                color = dateColor,
            )
        }
    }
}

@Composable
private fun AllDayRow(days: List<LocalDate>, allDayByDay: Map<LocalDate, List<DeviceCalendarEvent>>) {
    val untitled = stringResource(R.string.calendar_overlay_event_untitled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .padding(vertical = Dimens.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.calendar_all_day),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(CalendarGutterWidth).padding(end = Dimens.Space4),
            textAlign = TextAlign.End,
        )
        days.forEach { day ->
            Column(modifier = Modifier.weight(1f).padding(horizontal = Dimens.Space1)) {
                allDayByDay[day].orEmpty().take(MAX_ALL_DAY_EVENTS).forEach { event ->
                    val label = event.title?.ifBlank { null } ?: untitled
                    val base = event.eventColor()
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(base.copy(alpha = 0.16f))
                            .padding(horizontal = Dimens.Space4, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    isToday: Boolean,
    clock: State<Instant>,
    zone: ZoneId,
    eventBlocks: List<EventBlock>,
    blocks: List<TrackedEntryBlock>,
    settings: CalendarGridSettings,
    projectsById: Map<String, Project>,
    tasksById: Map<String, Task>,
    clientsById: Map<String, Client>,
    onEntryClick: (TimeEntry) -> Unit,
    onMoveEntry: (TimeEntry, String, String) -> Unit,
    onCreateRange: (CalendarTimeRange) -> Unit,
    syncStatusByEntryId: Map<String, EntrySyncStatus>,
    dayIndex: Int,
    dayCount: Int,
    modifier: Modifier = Modifier,
) {
    val untitled = stringResource(R.string.calendar_overlay_event_untitled)
    // Later columns draw over earlier ones, so the column holding a dragged entry rises above them.
    var draggingEntries by remember(day) { mutableStateOf(0) }
    BoxWithConstraints(
        modifier = modifier
            .zIndex(if (draggingEntries > 0) 1f else 0f)
            .padding(horizontal = COLUMN_PADDING)
            .testTag("week-day-column-$day"),
    ) {
        val colWidth = maxWidth
        val density = LocalDensity.current
        // One day to the next: the column plus its padding on both sides.
        val columnPitchPx = with(density) { (colWidth + COLUMN_PADDING * 2).toPx() }
        val totalHeight = calendarTotalHeight(settings)
        val gridHeightPx = with(density) { totalHeight.toPx() }

        // Faded, read-only device-calendar events behind the tracked entries.
        eventBlocks.forEach { block ->
            val slotWidth = colWidth / block.columnCount.coerceAtLeast(1)
            val base = block.event.eventColor()
            val label = block.event.title?.ifBlank { null } ?: untitled
            val a11y = stringResource(R.string.calendar_overlay_event_a11y, label)
            Box(
                modifier = Modifier
                    .offset(
                        x = slotWidth * block.column,
                        y = totalHeight * block.startFraction,
                    )
                    .width(slotWidth)
                    .height((totalHeight * block.heightFraction).coerceAtLeast(14.dp))
                    .padding(0.5.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(base.copy(alpha = 0.14f))
                    .border(1.dp, base.copy(alpha = 0.5f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 3.dp, vertical = 1.dp)
                    .semantics { contentDescription = a11y },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        CalendarTimeSelectionLayer(
            day = day,
            zone = zone,
            settings = settings,
            onSelectionComplete = onCreateRange,
            modifier = Modifier.fillMaxSize(),
        )

        // Tracked time entries drawn on top with the shared EntryBlock treatment. Each block is
        // keyed and skippable, so one entry changing does not recompose its neighbours.
        blocks.forEach { block ->
            val entry = block.entry
            val slotWidth = colWidth / block.columnCount.coerceAtLeast(1)
            val project = projectsById[entry.projectId]
            key(entry.id) {
                CalendarEntryBlockItem(
                    block = block,
                    day = day,
                    zone = zone,
                    settings = settings,
                    clock = clock,
                    totalHeight = totalHeight,
                    gridHeightPx = gridHeightPx,
                    columnWidthPx = columnPitchPx,
                    dayIndex = dayIndex,
                    dayCount = dayCount,
                    project = project,
                    task = tasksById[entry.taskId],
                    client = project?.clientId?.let(clientsById::get),
                    syncStatus = syncStatusByEntryId[entry.id],
                    testTag = "week-entry-${entry.id}",
                    showBreakSubtitle = true,
                    formatOpenDuration = ::formatRunningDuration,
                    onEntryClick = onEntryClick,
                    onMoveEntry = onMoveEntry,
                    modifier = Modifier
                        .offset(x = slotWidth * block.column, y = totalHeight * block.startFraction)
                        .width(slotWidth)
                        .padding(horizontal = Dimens.Hairline),
                    onDragActiveChange = { active -> draggingEntries = (draggingEntries + if (active) 1 else -1).coerceAtLeast(0) },
                )
            }
        }

        // Current-time indicator on today's column; it moves without recomposing the column.
        if (isToday) {
            CurrentTimeMarker(clock = clock, day = day, zone = zone, settings = settings)
        }
    }
}

/**
 * Shared hour gutter + horizontal gridlines for the day/week time grid. Draws 24 right-aligned
 * hour labels in a [CalendarGutterWidth] gutter and a per-hour hairline (via `outlineVariant`)
 * across the full width. Used by both the week grid and the month day-timeline.
 */
@Composable
internal fun HourGridlines(settings: CalendarGridSettings = CalendarGridSettings(), modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = GRIDLINE_ALPHA)
    val hourHeight = calendarHourHeight(settings)
    val context = LocalContext.current
    val locale = appLocale()
    val hourFormatter = remember(locale) {
        // "11 AM" keeps 12-hour labels on one line in the hour gutter.
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h a", locale)
    }
    val labelStyle = MaterialTheme.typography.labelSmall
    // Each label sits centred on its hour line rather than hanging below it.
    val labelHalfHeight = with(LocalDensity.current) { labelStyle.lineHeight.toDp() } / 2
    val totalHeight = calendarTotalHeight(settings)
    Box(modifier = modifier.fillMaxWidth().height(totalHeight)) {
        for (hour in settings.startHour until settings.endHour) {
            val lineY = hourHeight * (hour - settings.startHour)
            // The first line is the grid's top edge; a centred label there would be clipped.
            if (hour > settings.startHour) {
                Text(
                    text = LocalTime.of(hour, 0).format(hourFormatter),
                    style = labelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(y = lineY - labelHalfHeight)
                        .width(CalendarGutterWidth)
                        .padding(end = Dimens.Space8),
                    textAlign = TextAlign.End,
                )
            }
            Spacer(
                modifier = Modifier
                    .offset(x = CalendarGutterWidth, y = lineY)
                    .fillMaxWidth()
                    .height(Dimens.Space1)
                    .background(lineColor),
            )
        }
        // The gutter divider separates the time column from the day.
        Spacer(
            modifier = Modifier
                .offset(x = CalendarGutterWidth)
                .width(Dimens.Space1)
                .height(totalHeight)
                .background(lineColor),
        )
    }
}

/**
 * The red "now" line for [day], positioned by the current instant within the 24h column. Renders
 * nothing when [now] falls outside [day]. Pass a start padding via [modifier] to align it past the
 * hour gutter in single-column layouts.
 */
@Composable
internal fun CurrentTimeMarker(
    now: Instant,
    day: LocalDate,
    zone: ZoneId,
    settings: CalendarGridSettings = CalendarGridSettings(),
    modifier: Modifier = Modifier,
) {
    val clock = rememberUpdatedState(now)
    CurrentTimeMarker(clock = clock, day = day, zone = zone, settings = settings, modifier = modifier)
}

/**
 * The now line driven by [clock]. Only its placement reads the clock, so a tick moves the line
 * without recomposing it or the column it sits in; it recomposes only to appear or disappear.
 */
@Composable
internal fun CurrentTimeMarker(
    clock: State<Instant>,
    day: LocalDate,
    zone: ZoneId,
    settings: CalendarGridSettings = CalendarGridSettings(),
    modifier: Modifier = Modifier,
) {
    val grid = remember(day, zone, settings) { calendarGridBounds(day, zone, settings) }
    val visible by remember(clock, grid) { derivedStateOf { gridFraction(clock.value, grid) in 0f..1f } }
    if (!visible) return
    val totalHeight = calendarTotalHeight(settings)
    Box(
        modifier = modifier
            .offset { IntOffset(0, (totalHeight.toPx() * gridFraction(clock.value, grid)).roundToInt()) }
            .fillMaxWidth()
            .height(Dimens.Space2)
            .background(MaterialTheme.colorScheme.error)
            .testTag(CalendarTestTags.CURRENT_TIME_MARKER),
    )
}

private fun gridFraction(now: Instant, grid: CalendarGridBounds): Float =
    (now.epochSecond - grid.start.epochSecond).toFloat() / grid.seconds.coerceAtLeast(1L)

@Composable
private fun HairLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/** Compose color for a device event, falling back to the theme secondary when none is supplied. */
@Composable
private fun DeviceCalendarEvent.eventColor(): Color = colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.secondary

private fun periodTitle(days: List<LocalDate>, viewMode: CalendarViewMode, locale: Locale): String {
    if (days.isEmpty()) return ""
    val first = days.first()
    val last = days.last()
    if (viewMode == CalendarViewMode.DAY || first == last) {
        return first.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale))
    }
    val rangeFormat = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    return "${first.format(rangeFormat)} – ${last.format(rangeFormat)}"
}

/** The seven days of [date]'s week, starting on the account's [weekStart]. */
internal fun weekOf(date: LocalDate, weekStart: DayOfWeek): List<LocalDate> {
    val first = date.with(TemporalAdjusters.previousOrSame(weekStart))
    return (0L until DAYS_PER_WEEK).map(first::plusDays)
}

private const val DAYS_PER_WEEK = 7L
private const val GRIDLINE_ALPHA = 0.7f

private val COLUMN_PADDING = 1.dp
