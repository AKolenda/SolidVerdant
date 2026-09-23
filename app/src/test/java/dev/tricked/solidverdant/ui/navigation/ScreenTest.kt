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
    fun pushedDestinations_keepTheTabThatOpenedThem() {
        assertEquals("track", nextSelectedTab("track", TimerRoutes.CALENDAR))
        assertEquals("track", nextSelectedTab("track", ReviewRoutes.END_OF_DAY))
        assertEquals("settings", nextSelectedTab("settings", SyncRoutes.SYNC_CENTER))
    }

    @Test
    fun tabDestinationsSelectThemselves() {
        assertEquals("stats", nextSelectedTab("track", "stats"))
        assertEquals("track", nextSelectedTab("settings", "track"))
        assertEquals("settings", nextSelectedTab("settings", null))
    }
}
