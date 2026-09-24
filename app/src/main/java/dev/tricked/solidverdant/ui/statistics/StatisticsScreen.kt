/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.ui.components.ErrorState
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.components.LoadingState
import dev.tricked.solidverdant.ui.navigation.MainTopBar
import dev.tricked.solidverdant.ui.theme.Dimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Neutral fallback for a project whose stored hex is blank or unparseable. Statistics render sites
 * pass a theme token instead (see [hexToColor]); this literal only backs the default for non-Compose
 * callers (the calendar) that cannot resolve a theme colour.
 */
private val UnknownProjectColor = Color.Gray

/**
 * Parses a project colour hex into a [Color], falling back to [fallback] when the value is blank or
 * unparseable. Statistics call sites pass `MaterialTheme.colorScheme.outline` so swatches route
 * through a theme token and never bake a raw grey; the default keeps existing non-Compose callers
 * working unchanged.
 */
fun hexToColor(hex: String, fallback: Color = UnknownProjectColor): Color = try {
    Color(hex.toColorInt())
} catch (t: Throwable) {
    fallback
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "0m"
    val h = seconds / SECONDS_PER_HOUR
    val m = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return when {
        h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
        else -> "${m}m"
    }
}

/** Locale-ordered day and short month, e.g. "29 Jun", "Jun 29" or "6月29日". */
internal fun dayMonthFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)

/**
 * "29 Jun – 5 Jul" for a range, "Wed, 8 Jul" for a single day, adding years only when the range
 * spans two of them.
 */
internal fun formatDateRange(range: ClosedRange<LocalDate>, locale: Locale): String {
    val skeleton = if (range.start.year != range.endInclusive.year) "dMMMy" else "dMMM"
    if (range.start == range.endInclusive) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEE$skeleton")
        return range.start.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
    val formatter = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    return "${range.start.format(formatter)} – ${range.endInclusive.format(formatter)}"
}

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val drillDown by viewModel.drillDown.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val chooserTitle = stringResource(R.string.stats2_export_chooser_title)
    val emptyMsg = stringResource(R.string.stats2_export_empty)
    val errorMsg = stringResource(R.string.stats2_export_error)

    LaunchedEffect(exportState) {
        when (val es = exportState) {
            is ExportState.Ready -> {
                shareCsv(context, es.uri, chooserTitle)
                viewModel.onExportHandled()
            }
            ExportState.Empty -> {
                snackbarHostState.showSnackbar(emptyMsg)
                viewModel.onExportHandled()
            }
            ExportState.Error -> {
                snackbarHostState.showSnackbar(errorMsg)
                viewModel.onExportHandled()
            }
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().testTag("stats_screen")) {
        StatisticsContent(
            state = state,
            exporting = exportState is ExportState.Running,
            onRangeChange = viewModel::setRange,
            onFiltersChange = viewModel::setFilters,
            onClearFilters = viewModel::clearFilters,
            onRefresh = viewModel::refresh,
            onExport = viewModel::export,
            onProjectClick = { viewModel.openProjectDrillDown(it.projectId, it.projectName, it.colorHex) },
            onOtherProjectsClick = viewModel::openOtherProjectsDrillDown,
            onBucketClick = { bucket ->
                val end = when (state.granularity) {
                    TrendGranularity.DAY -> bucket.startDate
                    TrendGranularity.WEEK -> bucket.startDate.plusDays(WEEK_DAYS_MINUS_ONE)
                }
                viewModel.openTrendDrillDown(bucket.label, bucket.startDate, end)
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }

    drillDown?.let { dd ->
        StatDrillDownSheet(state = dd, onDismiss = viewModel::closeDrillDown)
    }
}

/**
 * Stateless Dashboard body: large title with export, period and offset controls, filter row, then
 * the total card, the stacked "By day" chart, the per-project breakdown and estimates. The cards
 * are lazy items, and pulling down refreshes the range from the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun StatisticsContent(
    state: StatisticsUiState,
    exporting: Boolean,
    onRangeChange: (StatRange) -> Unit,
    onFiltersChange: (StatFilters) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onProjectClick: (ProjectTotal) -> Unit,
    onBucketClick: (TrendBucket) -> Unit,
    modifier: Modifier = Modifier,
    onOtherProjectsClick: (Set<String?>) -> Unit = {},
) {
    val overview = remember(state.summary) { StatisticsAggregator.projectOverview(state.summary) }
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MainTopBar(
            title = stringResource(R.string.nav_reports),
            actions = { ExportAction(exporting = exporting, onExport = onExport) },
        )
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding().testTag(DashboardTestTags.LIST),
                contentPadding = PaddingValues(top = Dimens.Space8, bottom = Dimens.Space24),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
            ) {
                item(key = "range") { StatRangeControls(range = state.range, onSelect = onRangeChange) }
                item(key = "filters") {
                    StatFilterBar(
                        filters = state.filters,
                        catalog = state.catalog,
                        onFiltersChange = onFiltersChange,
                        onClearFilters = onClearFilters,
                    )
                }
                if (state.refreshFailed) {
                    item(key = "refresh_failed") {
                        GroupedSection {
                            ErrorState(text = stringResource(R.string.stats_cached_refresh_failed), onRetry = onRefresh)
                        }
                    }
                }
                if (state.isLoading) {
                    item(key = "loading") { LoadingState() }
                } else {
                    item(key = "summary") {
                        val range = state.rangeStart?.let { start -> state.rangeEnd?.let { end -> start..end } }
                        SummaryCard(
                            summary = state.summary,
                            range = range,
                            comparison = state.comparison,
                            emptyText = if (state.isEmpty) stringResource(R.string.stats_empty) else null,
                        )
                    }
                    if (!state.isEmpty) {
                        item(key = "trend") { TrendCard(overview, state.granularity, onBucketClick) }
                        item(key = "projects") {
                            ProjectBreakdownCard(state.summary, overview, onProjectClick, onOtherProjectsClick)
                        }
                        if (state.estimateProgress.isNotEmpty()) {
                            item(key = "estimates") { EstimatesCard(state.estimateProgress) }
                        }
                    }
                }
            }
        }
    }
}

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val WEEK_DAYS_MINUS_ONE = 6L

@Composable
private fun ExportAction(exporting: Boolean, onExport: () -> Unit) {
    val description = stringResource(R.string.stats2_export_content_description)
    IconButton(
        onClick = onExport,
        enabled = !exporting,
        modifier = Modifier.testTag(DashboardTestTags.EXPORT).semantics { contentDescription = description },
    ) {
        if (exporting) {
            CircularProgressIndicator(Modifier.size(Dimens.IconSmall), strokeWidth = Dimens.Space2)
        } else {
            Icon(
                Icons.Outlined.IosShare,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconMedium),
            )
        }
    }
}

/**
 * "Estimates & progress": server-authoritative spent vs estimated time per project, remaining or
 * overflow, and a consumed-fraction bar. Over-budget and near-estimate items are flagged with BOTH
 * a colour and a text label (never colour alone). Rendered only when [items] is non-empty.
 */
@Composable
private fun EstimatesCard(items: List<EstimateProgress>) {
    DashboardCard(title = stringResource(R.string.stats2_estimates_title)) {
        val barFallback = MaterialTheme.colorScheme.primary
        items.forEach { item ->
            EstimateRow(item, barFallback)
        }
    }
}

@Composable
private fun EstimateRow(item: EstimateProgress, defaultBarColor: Color) {
    val swatchFallback = MaterialTheme.colorScheme.outline
    val errorColor = MaterialTheme.colorScheme.error
    val spent = item.spentSeconds.toLong()
    val estimated = item.estimatedSeconds.toLong()
    val spentOfText = stringResource(R.string.stats2_estimates_spent_of, formatDuration(spent), formatDuration(estimated))
    val statusText = if (item.isOverBudget) {
        stringResource(R.string.stats2_estimates_over, formatDuration((-item.remainingSeconds).toLong()))
    } else {
        stringResource(R.string.stats2_estimates_remaining, formatDuration(item.remainingSeconds.toLong()))
    }
    val rowCd = if (item.isOverBudget) {
        stringResource(
            R.string.stats2_estimates_over_content_description,
            item.name,
            formatDuration((-item.remainingSeconds).toLong()),
        )
    } else {
        stringResource(
            R.string.stats2_estimates_progress_content_description,
            item.name,
            formatDuration(spent),
            formatDuration(estimated),
        )
    }
    val barColor = if (item.isOverBudget) errorColor else hexToColor(item.colorHex.orEmpty(), defaultBarColor)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = rowCd },
        verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            ProjectSwatch(hexToColor(item.colorHex.orEmpty(), swatchFallback))
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.isOverBudget) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProportionBar(item.fraction, barColor)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        ) {
            Text(
                spentOfText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (item.isNearEstimate) {
                Text(
                    stringResource(R.string.stats2_estimates_near),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * Shares the exported CSV through the Android system share sheet. Grants temporary read access to the
 * receiving app for the FileProvider content URI; the file itself only ever holds the user's own work
 * data, never tokens or credentials.
 */
private fun shareCsv(context: Context, uri: Uri, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(chooser) }
}
