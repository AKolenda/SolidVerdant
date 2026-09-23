/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics.charts

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val TARGET_INTERVALS = 4

/** Candidate gridline steps in minutes: 15m and 30m for short ranges, then round hour counts. */
private val NICE_STEP_MINUTES = longArrayOf(15, 30, 60, 120, 240, 300, 600, 1200, 1500, 3000, 6000)

/** At most this many x labels once a chart has more bars than [ALL_LABELS_MAX_BARS]. */
private const val SPARSE_LABEL_COUNT = 5

/** Up to a week of bars, every bar gets its own label. */
private const val ALL_LABELS_MAX_BARS = 7

/** A duration y axis: gridlines every [stepSeconds] from 0 up to and including [maxSeconds]. */
data class DurationAxis(val stepSeconds: Long, val maxSeconds: Long) {
    val ticks: List<Long> get() = (0L..maxSeconds step stepSeconds).toList()

    /** "0h", "2h" for hour steps; "0m", "15m" when the step is under an hour. */
    fun label(seconds: Long): String = if (stepSeconds < SECONDS_PER_HOUR) {
        "${seconds / SECONDS_PER_MINUTE}m"
    } else {
        "${seconds / SECONDS_PER_HOUR}h"
    }
}

/**
 * The smallest "nice" axis whose top is at or above [maxBucketSeconds], aiming for about four
 * intervals. 7h12m gives 0–8h in 2h steps; 45m gives 0–45m in 15m steps; an empty chart gets a
 * single 15m interval so gridlines still render.
 */
fun durationAxis(maxBucketSeconds: Long): DurationAxis {
    val max = maxBucketSeconds.coerceAtLeast(1L)
    val rawStep = max / TARGET_INTERVALS
    val niceMinutes = NICE_STEP_MINUTES.firstOrNull { it * SECONDS_PER_MINUTE >= rawStep }
    val largest = NICE_STEP_MINUTES.last() * SECONDS_PER_MINUTE
    val step = niceMinutes?.times(SECONDS_PER_MINUTE) ?: (((rawStep + largest - 1) / largest) * largest)
    val intervals = ((max + step - 1) / step).coerceAtLeast(1L)
    return DurationAxis(stepSeconds = step, maxSeconds = step * intervals)
}

/**
 * Which of [count] bars get an x label. Up to a week every bar is labelled; beyond that about
 * [SPARSE_LABEL_COUNT] evenly spaced bars including the first and last. The evenly spaced label
 * before the last is dropped when it sits closer than one step, so no two labels crowd.
 */
fun sparseLabelIndices(count: Int): Set<Int> {
    if (count <= ALL_LABELS_MAX_BARS) return (0 until count).toSet()
    val step = (count - 1 + SPARSE_LABEL_COUNT - 2) / (SPARSE_LABEL_COUNT - 1)
    val picked = (0 until count step step).toMutableList()
    val last = count - 1
    if (picked.last() != last) {
        if (last - picked.last() < step) picked.removeAt(picked.lastIndex)
        picked += last
    }
    return picked.toSet()
}
