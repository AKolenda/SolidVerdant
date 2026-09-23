/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics.charts

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAxisTest {

    @Test
    fun `a working day rounds up to an even hour axis`() {
        val axis = durationAxis(7 * 3600L + 12 * 60L)
        assertEquals(DurationAxis(stepSeconds = 2 * 3600L, maxSeconds = 8 * 3600L), axis)
        assertEquals(listOf("0h", "2h", "4h", "6h", "8h"), axis.ticks.map(axis::label))
    }

    @Test
    fun `short totals use minute steps`() {
        val axis = durationAxis(45 * 60L)
        assertEquals(listOf("0m", "15m", "30m", "45m"), axis.ticks.map(axis::label))
    }

    @Test
    fun `an exact multiple is not padded with an extra interval`() {
        assertEquals(8 * 3600L, durationAxis(8 * 3600L).maxSeconds)
    }

    @Test
    fun `an empty chart still gets one interval`() {
        assertEquals(DurationAxis(stepSeconds = 15 * 60L, maxSeconds = 15 * 60L), durationAxis(0L))
    }

    @Test
    fun `weekly totals beyond the nicest step stay whole multiples`() {
        val axis = durationAxis(700 * 3600L)
        assertEquals(0L, axis.maxSeconds % axis.stepSeconds)
        assertEquals(true, axis.maxSeconds >= 700 * 3600L)
        assertEquals(true, axis.ticks.size in 2..6)
    }

    @Test
    fun `up to a week every bar is labelled`() {
        assertEquals((0 until 7).toSet(), sparseLabelIndices(7))
        assertEquals(setOf(0), sparseLabelIndices(1))
    }

    @Test
    fun `longer ranges label a few spaced bars including both ends`() {
        assertEquals(setOf(0, 8, 16, 30), sparseLabelIndices(31))
        assertEquals(setOf(0, 4, 8, 14), sparseLabelIndices(15))
        assertEquals(setOf(0, 2, 4, 7), sparseLabelIndices(8))
    }
}
