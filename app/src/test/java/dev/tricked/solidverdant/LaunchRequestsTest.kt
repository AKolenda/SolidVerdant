/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant

import android.content.Intent
import android.net.Uri
import dev.tricked.solidverdant.reminder.ReminderWorker
import dev.tricked.solidverdant.ui.navigation.ReviewRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A request an intent carries is taken once: a recreated activity sees the same intent object, and
 * must not switch organization, jump the calendar or submit an OAuth code again.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchRequestsTest {

    @Test
    fun requests_are_taken_once_and_removed_from_the_intent() {
        val callback = Uri.parse("solidtime://oauth/callback?code=one-time&state=abc")
        val intent = Intent(Intent.ACTION_VIEW, callback)
            .putExtra(MainActivity.EXTRA_HANDOFF_ORGANIZATION_ID, "org-2")
            .putExtra(MainActivity.EXTRA_EDIT_ACTIVE_ENTRY, true)
            .putExtra(ReminderWorker.EXTRA_OPEN_REVIEW_ROUTE, ReviewRoutes.END_OF_DAY)

        val first = takeLaunchRequests(intent)

        assertEquals("org-2", first.handoffOrganizationId)
        assertTrue(first.editActiveEntry)
        assertEquals(ReviewRoutes.END_OF_DAY, first.reviewRoute)
        assertEquals(callback, first.deepLink)
        assertNull(intent.data)
        assertFalse(intent.hasExtra(MainActivity.EXTRA_HANDOFF_ORGANIZATION_ID))

        val again = takeLaunchRequests(intent)
        assertEquals(LaunchRequests(handoffOrganizationId = null, editActiveEntry = false, reviewRoute = null, deepLink = null), again)
    }

    @Test
    fun an_unknown_review_route_is_ignored() {
        val intent = Intent().putExtra(ReminderWorker.EXTRA_OPEN_REVIEW_ROUTE, "somewhere/else")

        assertNull(takeLaunchRequests(intent).reviewRoute)
        assertFalse(intent.hasExtra(ReminderWorker.EXTRA_OPEN_REVIEW_ROUTE))
    }
}
