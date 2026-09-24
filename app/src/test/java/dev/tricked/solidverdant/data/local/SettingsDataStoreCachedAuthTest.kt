/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Organization
import dev.tricked.solidverdant.data.model.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsDataStoreCachedAuthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsDataStore(context)
    private val user = User("u1", "User", "user@example.invalid", timezone = "Europe/Amsterdam")
    private val memberships = listOf(Membership("m1", "member", Organization("org1", "Org", "EUR")))

    @Before fun clear() = runBlocking { settings.clearCachedData() }

    @Test fun subscribers_share_one_decode_per_change() = runTest {
        settings.cacheAuth(user, memberships, "m1")
        assertEquals(user, settings.observeCachedAuth().first()?.user)

        // Corrupt the stored JSON behind the store's back (no change notification): a direct read
        // decodes again and fails, new subscribers reuse the value decoded for this change.
        context.getSharedPreferences("immediate_ui_cache", Context.MODE_PRIVATE).edit().putString("user_json", "{").commit()

        assertNull(settings.getCachedAuth())
        assertEquals(user, settings.observeCachedAuth().first()?.user)
    }

    @Test fun rewriting_identical_auth_does_not_re_emit() = runTest {
        settings.cacheAuth(user, memberships, "m1")
        val seen = mutableListOf<SettingsDataStore.CachedAuth?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { settings.observeCachedAuth().collect { seen += it } }

        settings.cacheAuth(user, memberships, "m1")
        runCurrent()
        settings.cacheAuth(user.copy(timezone = "UTC"), memberships, "m1")
        runCurrent()

        assertEquals(listOf("Europe/Amsterdam", "UTC"), seen.map { it?.user?.timezone })
        job.cancel()
    }
}
