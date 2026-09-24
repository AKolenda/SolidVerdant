/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import dev.tricked.solidverdant.ui.review.ReviewDayViewModel.Companion.resolveEndAfterStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** B4: a picked end time lands on the first such time after the start, in the account zone. */
class ReviewEndTimeTest {

    private val zone = ZoneId.of("Europe/Amsterdam")
    private val start = Instant.parse("2025-07-10T18:00:00Z") // 20:00 in Amsterdam
    private val now = Instant.parse("2025-07-11T06:00:00Z") // 08:00 the next morning

    @Test
    fun `an evening time ends the timer on the day it started`() {
        assertEquals(Instant.parse("2025-07-10T21:00:00Z"), resolveEndAfterStart(start, LocalTime.of(23, 0), zone, now))
    }

    @Test
    fun `a morning time ends it the next day`() {
        assertEquals(Instant.parse("2025-07-11T05:00:00Z"), resolveEndAfterStart(start, LocalTime.of(7, 0), zone, now))
    }

    @Test
    fun `a time that has not happened yet is refused`() {
        assertNull(resolveEndAfterStart(start, LocalTime.of(9, 0), zone, now))
    }

    @Test
    fun `the time is read in the account zone, not the device zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 20:00 Amsterdam is 03:00 in Tokyo; 04:00 Tokyo is one hour later.
        assertEquals(Instant.parse("2025-07-10T19:00:00Z"), resolveEndAfterStart(start, LocalTime.of(4, 0), tokyo, now))
    }
}
