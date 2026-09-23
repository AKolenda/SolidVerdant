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
    fun menuScreens_haveUniqueStableRoutesInMenuOrder() {
        val routes = menuScreens.map { it.route }
        // Calendar deep links and review notifications resolve these strings.
        assertEquals(listOf("track", "calendar", "stats", "review", "settings"), routes)
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun pushedDestinations_keepTheMenuItemThatOpenedThem() {
        assertEquals("review", nextSelectedDestination("review", ReviewRoutes.END_OF_DAY))
        assertEquals("settings", nextSelectedDestination("settings", SyncRoutes.SYNC_CENTER))
        assertEquals("track", nextSelectedDestination("track", SyncRoutes.SYNC_CENTER))
    }

    @Test
    fun menuDestinationsSelectThemselves() {
        assertEquals("calendar", nextSelectedDestination("track", "calendar"))
        assertEquals("stats", nextSelectedDestination("track", "stats"))
        assertEquals("track", nextSelectedDestination("settings", "track"))
        assertEquals("settings", nextSelectedDestination("settings", null))
    }
}
