/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.DeltaBadge
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.localization.appLocale
import dev.tricked.solidverdant.ui.statistics.charts.StackedBarChart
import dev.tricked.solidverdant.ui.statistics.charts.sparseLabelIndices
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.tabular
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

internal object DashboardTestTags {
    const val EXPORT = "stats_export"
    const val TOTAL = "stats_total"
    fun projectRow(projectId: String?) = "stats_project_row_${projectId ?: "none"}"
}

private const val TRAILING_WEEK_DAYS = 6L
private const val WEEKDAY_LABEL_MAX_BARS = 7

/** A white rounded Dashboard card on the grouped background, with an optional [title]. */
@Composable
internal fun DashboardCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    GroupedSection(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dimens.Space16),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            content()
        }
    }
}

/**
 * Range dates, the big total, one secondary line (billable, average per day, entry count) and the
 * change against the previous equal-length window. An empty range shows [emptyText] in place of
 * the secondary line.
 */
@Composable
internal fun SummaryCard(summary: StatisticsSummary, range: ClosedRange<LocalDate>?, comparison: PeriodComparison?, emptyText: String?) {
    val locale = appLocale()
    DashboardCard {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space2)) {
            if (range != null) {
                Text(
                    formatDateRange(range, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatDuration(summary.totalSeconds),
                style = MaterialTheme.typography.headlineLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(DashboardTestTags.TOTAL),
            )
            Text(
                text = emptyText ?: listOf(
                    stringResource(R.string.stats_summary_billable, formatDuration(summary.billableSeconds)),
                    stringResource(R.string.stats_summary_per_day, formatDuration(summary.avgSecondsPerDay)),
                    pluralStringResource(R.plurals.stats_summary_entries, summary.entryCount, summary.entryCount),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (comparison != null && (comparison.total.current > 0 || comparison.total.previous > 0)) {
            ComparisonLine(comparison, locale)
        }
    }
}

@Composable
private fun ComparisonLine(comparison: PeriodComparison, locale: Locale) {
    val delta = comparison.total
    val sign = delta.absoluteDelta
    val percent = delta.percentChange()?.roundToLong()
    val percentText = when (percent) {
        null -> stringResource(R.string.stats2_delta_new)
        else -> stringResource(R.string.stats2_delta_percent, if (percent > 0) "+$percent" else percent.toString())
    }
    val signedDuration = when {
        sign > 0 -> "+" + formatDuration(sign)
        sign < 0 -> "-" + formatDuration(abs(sign))
        else -> formatDuration(0)
    }
    val deltaDescription = when {
        sign > 0 -> stringResource(R.string.stats2_delta_increase_content_description, formatDuration(sign))
        sign < 0 -> stringResource(R.string.stats2_delta_decrease_content_description, formatDuration(abs(sign)))
        else -> stringResource(R.string.stats2_delta_flat_content_description)
    }
    val previousRange = stringResource(
        R.string.stats_vs_range,
        formatDateRange(comparison.previousStart..comparison.previousEnd, locale),
    )
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        DeltaBadge(
            sign = sign.compareTo(0L),
            text = "$signedDuration · $percentText",
            modifier = Modifier.clearAndSetSemantics { contentDescription = deltaDescription },
        )
        Text(
            previousRange,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "By day" (or "By week") stacked bars with a project legend. Up to a week of day bars is
 * labelled by weekday; longer ranges label a few evenly spaced dates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrendCard(summary: StatisticsSummary, granularity: TrendGranularity, onBucketClick: (TrendBucket) -> Unit) {
    val locale = appLocale()
    val fallback = MaterialTheme.colorScheme.outline
    val trend = summary.trend
    val segmentColors = remember(trend, fallback) { trend.map { b -> b.segments.map { hexToColor(it.colorHex, fallback) } } }
    val xLabels = remember(trend, granularity, locale) {
        val shown = sparseLabelIndices(trend.size)
        val weekdays = granularity == TrendGranularity.DAY && trend.size <= WEEKDAY_LABEL_MAX_BARS
        val dayMonth = dayMonthFormatter(locale)
        trend.mapIndexed { i, bucket ->
            when {
                i !in shown -> null
                weekdays -> bucket.startDate.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                else -> bucket.startDate.format(dayMonth)
            }
        }
    }
    val title = stringResource(if (granularity == TrendGranularity.DAY) R.string.stats_by_day else R.string.stats_by_week)
    val barSummaries = remember(trend, granularity, locale) {
        trend.map { bucket ->
            val end = if (granularity == TrendGranularity.WEEK) bucket.startDate.plusDays(TRAILING_WEEK_DAYS) else bucket.startDate
            "${formatDateRange(bucket.startDate..end, locale)}: ${formatDuration(bucket.seconds)}"
        }
    }
    val barDescriptions = barSummaries.map { stringResource(R.string.stats2_drilldown_bucket_content_description, it) }
    DashboardCard(title = title) {
        StackedBarChart(
            bars = trend,
            segmentColors = segmentColors,
            xLabels = xLabels,
            barDescriptions = barDescriptions,
            onBarClick = onBucketClick,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space4),
        ) {
            summary.perProject.forEach { project ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                    ProjectSwatch(hexToColor(project.colorHex, fallback))
                    Text(
                        projectDisplayName(project),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One row per project: name and duration over a thin bar in the project colour. Each bar's length
 * is the project's share of the range total, so the bars read as a proportion of all tracked time.
 */
@Composable
internal fun ProjectBreakdownCard(summary: StatisticsSummary, onProjectClick: (ProjectTotal) -> Unit) {
    val fallback = MaterialTheme.colorScheme.outline
    DashboardCard(title = stringResource(R.string.stats_by_project)) {
        Column {
            summary.perProject.forEach { project ->
                val name = projectDisplayName(project)
                val duration = formatDuration(project.seconds)
                val description = stringResource(R.string.stats2_drilldown_project_content_description, name) + ", " + duration
                val fraction = if (summary.totalSeconds > 0) project.seconds.toFloat() / summary.totalSeconds else 0f
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.MinTouchTarget)
                        .testTag(DashboardTestTags.projectRow(project.projectId))
                        .clickable(role = Role.Button) { onProjectClick(project) }
                        .clearAndSetSemantics { contentDescription = description }
                        .padding(vertical = Dimens.Space8),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
                        ProjectSwatch(hexToColor(project.colorHex, fallback))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            duration,
                            style = MaterialTheme.typography.bodyMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProportionBar(fraction, hexToColor(project.colorHex, fallback))
                }
            }
        }
    }
}

/** A thin rounded bar filled to [fraction] (clamped to 0..1) in [color] over a neutral track. */
@Composable
internal fun ProportionBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimens.ProgressBarHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(Dimens.ProgressBarHeight)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** The single project colour dot used across statistics rows (legend, breakdown, drill-down). */
@Composable
internal fun ProjectSwatch(color: Color) {
    Box(
        Modifier
            .size(Dimens.ProjectDot)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun projectDisplayName(project: ProjectTotal): String = when {
    project.projectId == null -> stringResource(R.string.stats2_no_project)
    else -> project.projectName ?: stringResource(R.string.stats2_unknown_project)
}
