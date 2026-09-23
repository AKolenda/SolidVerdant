/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryDayLabelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.now(zone)

    @Test
    fun `recent days read as today and yesterday`() {
        assertEquals(context.getString(R.string.today), formatHistoryDayLabel(today, context, zone, Locale.US))
        assertEquals(context.getString(R.string.yesterday), formatHistoryDayLabel(today.minusDays(1), context, zone, Locale.US))
    }

    @Test
    fun `older days in this year show the weekday without the year`() {
        val date = today.minusDays(3).takeIf { it.year == today.year } ?: today.plusDays(3)
        val label = formatHistoryDayLabel(date, context, zone, Locale.US)
        val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)
        assertEquals("$weekday, ${date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)} ${date.dayOfMonth}", label)
    }

    @Test
    fun `days in another year include the year`() {
        val date = LocalDate.of(today.year - 1, 6, 10)
        val label = formatHistoryDayLabel(date, context, zone, Locale.US)
        assertEquals(true, label.contains(date.year.toString()))
        assertEquals(true, label.startsWith(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)))
    }
}
