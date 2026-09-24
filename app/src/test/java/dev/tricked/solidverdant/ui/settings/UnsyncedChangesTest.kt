/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.OutboxEntity
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.local.db.SyncState
import dev.tricked.solidverdant.data.local.db.toEntity
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Organization
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.data.remote.FakeRemoteDataSource
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.util.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What logout and "clear cached data" would delete: every queued op plus unresolved conflicts. */
@RunWith(RobolectricTestRunner::class)
class UnsyncedChangesTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsDataStore
    private lateinit var repository: TimeEntryRepository
    private val json = Json { encodeDefaults = true }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsDataStore(context)
        repository = TimeEntryRepository(
            db.timeEntryDao(),
            db.catalogDao(),
            db.outboxDao(),
            db.syncMetaDao(),
            FakeRemoteDataSource(),
            object : Clock {
                override fun nowMs() = NOW_MS
            },
            json,
            db,
        )
        val user = User(id = "u1", name = "Ada", email = "ada@example.com")
        val memberships = listOf("org1", "org2").map { org ->
            Membership(id = "m-$org", role = "member", organization = Organization(id = org, name = org, currency = "EUR"))
        }
        settings.cacheAuth(user, memberships, "m-org1")
    }

    @After
    fun teardown() = db.close()

    private suspend fun queue(org: String, entryId: String, deadLettered: Boolean) {
        db.outboxDao().insert(
            OutboxEntity(
                opType = OutboxOpType.UPDATE,
                organizationId = org,
                timeEntryId = entryId,
                payloadJson = "{}",
                createdAtMs = 1L,
                deadLettered = deadLettered,
            ),
        )
    }

    @Test
    fun counts_queued_and_failed_ops_and_conflicts_in_every_organization() = runBlocking {
        queue("org1", "e1", deadLettered = false)
        queue("org2", "e2", deadLettered = true)
        val server =
            TimeEntry(id = "e3", userId = "u1", organizationId = "org2", start = "2026-08-31T08:00:00Z", end = "2026-08-31T09:00:00Z")
        db.timeEntryDao().upsert(
            server.copy(description = "device copy").toEntity(NOW_MS, SyncState.CONFLICT)
                .copy(conflictServerJson = json.encodeToString(TimeEntry.serializer(), server)),
        )

        assertEquals(3, observeUnsyncedChanges(repository, settings).first { it == 3 })
    }

    @Test
    fun nothing_waiting_reads_zero() = runBlocking {
        assertEquals(0, observeUnsyncedChanges(repository, settings).first())
    }

    private companion object {
        const val NOW_MS = 1_000_000_000_000L
    }
}
