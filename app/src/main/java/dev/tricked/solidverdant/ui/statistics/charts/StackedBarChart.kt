/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import dev.tricked.solidverdant.ui.statistics.TrendBucket
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.tabular

/** Share of each bar's slot that the bar fills; the rest is the gap between bars. */
private const val BAR_FILL = 0.56f

object StackedBarChartTestTags {
    const val CHART = "stats_trend_chart"
    fun bar(index: Int) = "stats_trend_bar_$index"
}

/**
 * One bar per [bars] bucket, each stacked bottom-up by its project [TrendBucket.segments] in
 * [segmentColors] (parallel to the segments), over hairline gridlines with a duration axis on the
 * right. [xLabels] (parallel to [bars], null to skip) sit under their bars. Every bar is a
 * full-height tappable column announced with its [barDescriptions] entry, so taps land on the nearest bar
 * even when a month of bars leaves each one narrow.
 */
@Composable
fun StackedBarChart(
    bars: List<TrendBucket>,
    segmentColors: List<List<Color>>,
    xLabels: List<String?>,
    barDescriptions: List<String>,
    onBarClick: (TrendBucket) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) return
    val axis = remember(bars) { durationAxis(bars.maxOf { it.seconds }) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.tabular().copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val axisLabels = remember(axis, labelStyle, textMeasurer) {
        axis.ticks.map { it to textMeasurer.measure(axis.label(it), labelStyle) }
    }
    val xLabelLayouts = remember(xLabels, labelStyle, textMeasurer) {
        xLabels.map { label -> label?.let { textMeasurer.measure(it, labelStyle) } }
    }
    val gutterPx = axisLabels.maxOf { it.second.size.width } + with(density) { Dimens.Space8.toPx() }
    val labelBandPx = axisLabels.first().second.size.height + with(density) { Dimens.Space8.toPx() }
    val gutter = with(density) { gutterPx.toDp() }
    val labelBand = with(density) { labelBandPx.toDp() }

    Box(modifier.fillMaxWidth().height(Dimens.ChartHeight).testTag(StackedBarChartTestTags.CHART)) {
        Canvas(Modifier.fillMaxSize().semantics { hideFromAccessibility() }) {
            val plotRight = size.width - gutterPx
            val plotBottom = size.height - labelBandPx
            val halfLabel = axisLabels.first().second.size.height / 2f
            val plotTop = halfLabel
            val plotHeight = plotBottom - plotTop
            val hairline = Dimens.Hairline.toPx()

            axisLabels.forEach { (tick, layout) ->
                val y = plotBottom - plotHeight * tick / axis.maxSeconds
                drawLine(gridColor, Offset(0f, y), Offset(plotRight, y), strokeWidth = hairline)
                drawText(layout, topLeft = Offset(plotRight + Dimens.Space8.toPx(), y - layout.size.height / 2f))
            }

            val slot = plotRight / bars.size
            val barWidth = (slot * BAR_FILL).coerceAtLeast(1f)
            val corner = Dimens.ChartBarCorner.toPx().coerceAtMost(barWidth / 2f)
            bars.forEachIndexed { i, bucket ->
                val left = slot * i + (slot - barWidth) / 2f
                var bottom = plotBottom
                val colors = segmentColors.getOrNull(i).orEmpty()
                bucket.segments.forEachIndexed { s, segment ->
                    val h = plotHeight * segment.seconds / axis.maxSeconds
                    val top = bottom - h
                    val isTop = s == bucket.segments.lastIndex
                    val color = colors.getOrElse(s) { gridColor }
                    if (isTop) {
                        val r = CornerRadius(corner.coerceAtMost(h))
                        val path = Path().apply {
                            addRoundRect(RoundRect(left, top, left + barWidth, bottom, topLeftCornerRadius = r, topRightCornerRadius = r))
                        }
                        drawPath(path, color)
                    } else {
                        drawRect(color, Offset(left, top), Size(barWidth, h))
                    }
                    bottom = top
                }
                xLabelLayouts.getOrNull(i)?.let { layout ->
                    val x = (left + barWidth / 2f - layout.size.width / 2f).coerceIn(0f, (plotRight - layout.size.width).coerceAtLeast(0f))
                    drawText(layout, topLeft = Offset(x, plotBottom + Dimens.Space4.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxSize().padding(end = gutter, bottom = labelBand)) {
            bars.forEachIndexed { i, bucket ->
                val description = barDescriptions.getOrElse(i) { "" }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Button) { onBarClick(bucket) }
                        .semantics { contentDescription = description }
                        .testTag(StackedBarChartTestTags.bar(i)),
                )
            }
        }
    }
}
