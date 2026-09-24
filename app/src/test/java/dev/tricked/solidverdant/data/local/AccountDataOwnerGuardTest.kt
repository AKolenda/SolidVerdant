/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.OutboxEntity
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Organization
import dev.tricked.solidverdant.data.model.User
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An expired session keeps unsynced work; only a *different* account signing in clears it.
 */
@RunWith(RobolectricTestRunner::class)
class AccountDataOwnerGuardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsDataStore
    private lateinit var guard: AccountDataOwnerGuard
    private var wipes = 0

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsDataStore(context)
        val cleaner = UserCacheCleaner(context, settings, db)
        kotlinx.coroutines.runBlocking { settings.clearCachedData() }
        wipes = 0
        guard = AccountDataOwnerGuard(settings) {
            wipes += 1
            cleaner.clear()
        }
    }

    @After fun teardown() = db.close()

    private suspend fun queueUnsyncedStart() {
        db.outboxDao().insert(
            OutboxEntity(
                opType = OutboxOpType.START,
                organizationId = "org",
                timeEntryId = "local-1",
                payloadJson = "{}",
                createdAtMs = 1L,
            ),
        )
    }

    @Test fun same_account_signing_in_again_keeps_the_outbox() = runTest {
        assertFalse(guard.claim("https://a.example/", "user-a"))
        queueUnsyncedStart()

        // Session expired and the same account signed in again.
        assertFalse(guard.claim("https://a.example", "user-a"))

        assertEquals(0, wipes)
        assertEquals(1, db.outboxDao().peekAll().size)
    }

    @Test fun different_account_clears_the_previous_accounts_data() = runTest {
        guard.claim("https://a.example", "user-a")
        queueUnsyncedStart()

        assertTrue(guard.claim("https://a.example", "user-b"))

        assertEquals(1, wipes)
        assertTrue(db.outboxDao().peekAll().isEmpty())
        assertEquals(SettingsDataStore.DataOwner("https://a.example", "user-b"), settings.getDataOwner())
    }

    @Test fun same_user_id_on_another_server_is_a_different_account() = runTest {
        guard.claim("https://a.example", "user-a")
        queueUnsyncedStart()

        assertTrue(guard.claim("https://b.example", "user-a"))
        assertTrue(db.outboxDao().peekAll().isEmpty())
    }

    @Test fun legacy_install_falls_back_to_the_cached_profile_owner() = runTest {
        settings.cacheAuth(
            User(id = "user-a", name = "A", email = "a@example.invalid"),
            listOf(Membership("m", "member", Organization("org", "Org", "EUR"))),
            "m",
        )
        queueUnsyncedStart()

        assertFalse(guard.claim("https://a.example", "user-a"))
        assertEquals(1, db.outboxDao().peekAll().size)

        assertTrue(guard.claim("https://a.example", "user-b"))
        assertTrue(db.outboxDao().peekAll().isEmpty())
    }

    @Test fun explicit_logout_forgets_the_owner_so_the_next_sign_in_does_not_wipe_again() = runTest {
        guard.claim("https://a.example", "user-a")
        UserCacheCleaner(context, settings, db).clear()
        queueUnsyncedStart()

        assertFalse(guard.claim("https://a.example", "user-b"))
        assertEquals(0, wipes)
    }
}
