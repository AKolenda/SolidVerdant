/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTest {
    @Test
    fun bottomNavScreens_haveUniqueStableRoutes() {
        val routes = bottomNavScreens.map { it.route }
        assertEquals(listOf("track", "stats", "settings"), routes)
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun pushedDestinations_belongToTheTabThatOpenedThem() {
        assertEquals("track", selectedTabRoute(listOf(null, "track", TimerRoutes.CALENDAR)))
        assertEquals("track", selectedTabRoute(listOf(null, "track", TimerRoutes.REVIEW, ReviewRoutes.END_OF_DAY)))
        assertEquals(
            "settings",
            selectedTabRoute(listOf(null, "track", "settings", SyncRoutes.SYNC_CENTER)),
        )
        assertEquals("stats", selectedTabRoute(listOf(null, "track", "stats")))
    }

    @Test
    fun deepLinkWithoutATabFallsBackToTimer() {
        assertEquals("track", selectedTabRoute(listOf(null, ReviewRoutes.REMINDER_SETTINGS)))
        assertEquals("track", selectedTabRoute(emptyList()))
    }
}
