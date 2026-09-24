/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.service

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Organization
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.data.remote.ApiClientFactory
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.data.repository.TimerCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TimeTrackingTileServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val activeEntry = TimeEntry(
        id = "external-entry-id",
        description = "External work",
        userId = "user-id",
        start = "2026-08-10T08:00:00Z",
        organizationId = "organization-id",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.getSharedPreferences(TILE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun no_server_timer_renders_an_inactive_start_tile() {
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } returns Result.success(null)
        }
        val service = createService(authRepository)

        service.onStartListening()

        awaitTile(service) { state == Tile.STATE_INACTIVE && subtitle == "Tap to start" }
        assertEquals("Time Tracking", service.qsTile.label)
    }

    @Test
    fun externally_started_timer_refreshes_the_tile_to_active() {
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } returns Result.success(activeEntry)
        }
        val service = createService(authRepository)

        service.onStartListening()

        awaitTile(service) { state == Tile.STATE_ACTIVE && subtitle == activeEntry.description }
        assertEquals("Tracking", service.qsTile.label)
    }

    @Test
    fun externally_stopped_timer_clears_the_cached_active_tile() {
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } returnsMany listOf(
                Result.success(activeEntry),
                Result.success(null),
            )
        }
        val service = createService(authRepository)
        service.onStartListening()
        awaitTile(service) { state == Tile.STATE_ACTIVE }

        requestRefresh()

        awaitTile(service) { state == Tile.STATE_INACTIVE && subtitle == "Tap to start" }
        coVerify(timeout = TIMEOUT_MS, exactly = 2) { authRepository.getActiveTimeEntry() }
        assertEquals(null, context.getSharedPreferences(TILE_PREFS, Context.MODE_PRIVATE).getString(LAST_ENTRY_ID, null))
    }

    @Test
    fun network_failure_keeps_the_last_confirmed_active_tile() {
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } returnsMany listOf(
                Result.success(activeEntry),
                Result.failure(IllegalStateException("offline")),
            )
        }
        val service = createService(authRepository)
        service.onStartListening()
        awaitTile(service) { state == Tile.STATE_ACTIVE }

        requestRefresh()

        awaitTile(service) { state == Tile.STATE_ACTIVE && subtitle == "Tap to stop" }
        coVerify(timeout = TIMEOUT_MS, exactly = 2) { authRepository.getActiveTimeEntry() }
    }

    @Test
    fun late_refresh_result_cannot_overwrite_a_newer_tile_state() {
        val firstLookupStarted = CompletableDeferred<Unit>()
        val secondLookupStarted = CompletableDeferred<Unit>()
        val releaseFirstLookup = CompletableDeferred<Unit>()
        val releaseSecondLookup = CompletableDeferred<Unit>()
        val lookupCount = AtomicInteger()
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } coAnswers {
                if (lookupCount.incrementAndGet() == 1) {
                    firstLookupStarted.complete(Unit)
                    releaseFirstLookup.await()
                    Result.success(activeEntry)
                } else {
                    secondLookupStarted.complete(Unit)
                    releaseSecondLookup.await()
                    Result.success<TimeEntry?>(null)
                }
            }
        }
        val service = createService(authRepository)

        service.onStartListening()
        runBlocking { withTimeout(TIMEOUT_MS) { firstLookupStarted.await() } }
        requestRefresh()
        runBlocking { withTimeout(TIMEOUT_MS) { secondLookupStarted.await() } }

        releaseSecondLookup.complete(Unit)
        awaitTile(service) { state == Tile.STATE_INACTIVE && subtitle == "Tap to start" }
        releaseFirstLookup.complete(Unit)
        awaitCondition {
            service.qsTile.state == Tile.STATE_INACTIVE && service.qsTile.subtitle == "Tap to start"
        }
    }

    @Test
    fun clicking_an_active_tile_stops_the_server_entry_through_the_outbox() {
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } returnsMany listOf(
                Result.success(activeEntry),
                Result.success(null),
            )
        }
        val service = createService(authRepository)

        service.onClick()

        coVerify(timeout = TIMEOUT_MS, exactly = 1) { timeEntryRepository.adoptServerEntry(activeEntry) }
        coVerify(timeout = TIMEOUT_MS, exactly = 1) { timeEntryRepository.stopEntry(activeEntry, activeEntry.userId) }
        coVerify(exactly = 0) { authRepository.stopTimeEntry(any(), any(), any(), any(), any()) }
    }

    @Test
    fun clicking_the_tile_stops_a_timer_whose_start_is_still_queued() {
        val queued = activeEntry.copy(id = "local-queued")
        coEvery { timeEntryRepository.localActiveEntry(activeEntry.organizationId, activeEntry.userId) } returns queued
        coEvery { timeEntryRepository.hasPendingSync(queued.id) } returns true
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } returns Result.failure(IllegalStateException("offline"))
        }
        val service = createService(authRepository)

        service.onClick()

        coVerify(timeout = TIMEOUT_MS, exactly = 1) { timeEntryRepository.stopEntry(queued, activeEntry.userId) }
    }

    @Test
    fun refresh_keeps_a_locally_started_timer_active_until_its_start_syncs() {
        val queued = activeEntry.copy(id = "local-queued")
        coEvery { timeEntryRepository.localActiveEntry(activeEntry.organizationId, activeEntry.userId) } returns queued
        coEvery { timeEntryRepository.hasPendingSync(queued.id) } returns true
        context.getSharedPreferences(TILE_PREFS, Context.MODE_PRIVATE).edit().putString(LAST_ENTRY_ID, queued.id).commit()
        val authRepository = loggedInRepository().also {
            // The server has not received the START yet.
            coEvery { it.getActiveTimeEntry() } returns Result.success(null)
        }
        val service = createService(authRepository)

        service.onStartListening()
        // Force a network refresh too: a debounced first refresh may race the tile binding.
        requestRefresh()

        awaitTile(service) { state == Tile.STATE_ACTIVE && subtitle == queued.description }
        assertEquals(queued.id, context.getSharedPreferences(TILE_PREFS, Context.MODE_PRIVATE).getString(LAST_ENTRY_ID, null))
    }

    @Test
    fun repeated_click_is_ignored_while_the_first_lookup_is_in_flight() {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } coAnswers {
                lookupStarted.complete(Unit)
                releaseLookup.await()
                Result.success(null)
            }
        }
        val service = createService(authRepository)

        service.onClick()
        runBlocking { withTimeout(TIMEOUT_MS) { lookupStarted.await() } }
        service.onClick()

        coVerify(exactly = 1) { authRepository.getActiveTimeEntry() }
        releaseLookup.complete(Unit)
    }

    @Test
    fun a_slow_stop_ignores_repeated_clicks_and_the_tile_can_stop_again_afterwards() {
        val firstStopEntered = CompletableDeferred<Unit>()
        val releaseFirstStop = CompletableDeferred<Unit>()
        val secondStopFinished = CompletableDeferred<Unit>()
        val stopAttempts = AtomicInteger()
        val authRepository = loggedInRepository().also {
            coEvery { it.getActiveTimeEntry() } returns Result.success(activeEntry)
        }
        coEvery { timeEntryRepository.stopEntry(any(), any()) } coAnswers {
            if (stopAttempts.incrementAndGet() == 1) {
                firstStopEntered.complete(Unit)
                releaseFirstStop.await()
            } else {
                secondStopFinished.complete(Unit)
            }
        }
        val service = createService(authRepository)

        service.onClick()
        runBlocking { withTimeout(TIMEOUT_MS) { firstStopEntered.await() } }

        service.onClick()
        coVerify(exactly = 1) { timeEntryRepository.stopEntry(any(), any()) }

        releaseFirstStop.complete(Unit)
        awaitCondition { !service.isProcessingForTest() }

        service.onClick()

        runBlocking { withTimeout(TIMEOUT_MS) { secondStopFinished.await() } }
        coVerify(timeout = TIMEOUT_MS, exactly = 2) { timeEntryRepository.stopEntry(any(), any()) }
    }

    private fun loggedInRepository() = mockk<AuthRepository>(relaxed = true) {
        every { isLoggedIn } returns flowOf(true)
    }

    /** Room + outbox seam; Room knows no timer unless a test says so. */
    private val timeEntryRepository = mockk<TimeEntryRepository>(relaxed = true) {
        every { observeProjects(any()) } returns flowOf(emptyList())
        every { observeTasks(any()) } returns flowOf(emptyList())
        coEvery { localActiveEntry(any(), any()) } returns null
        coEvery { hasPendingSync(any()) } returns false
        coEvery { isStoppingLocally(any()) } returns false
        coEvery { adoptServerEntry(any()) } answers { firstArg() }
    }

    private fun createService(authRepository: AuthRepository): TimeTrackingTileService {
        val controller = Robolectric.buildService(TimeTrackingTileService::class.java).create()
        return controller.get().also {
            it.authRepository = authRepository
            it.apiClientFactory = mockk<ApiClientFactory>(relaxed = true)
            it.timeEntryRepository = timeEntryRepository
            it.settingsDataStore = mockk<SettingsDataStore>(relaxed = true) {
                every { alwaysShowNotification } returns flowOf(false)
            }
            val cachedAccount = mockk<SettingsDataStore> {
                every { getCachedAuth() } returns SettingsDataStore.CachedAuth(
                    User(id = activeEntry.userId, name = "User", email = "user@example.invalid"),
                    listOf(Membership("member-id", "member", Organization(activeEntry.organizationId, "Org", "EUR"))),
                    "member-id",
                )
            }
            it.timerCommands = TimerCommands(authRepository, timeEntryRepository, cachedAccount) { }
        }
    }

    private fun awaitTile(service: TimeTrackingTileService, predicate: Tile.() -> Boolean) {
        awaitCondition { service.qsTile.predicate() }
    }

    private fun requestRefresh() {
        context.sendBroadcast(
            Intent(TimeTrackingTileService.ACTION_REFRESH_TILE).setPackage(context.packageName),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(predicate: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (true) {
                shadowOf(Looper.getMainLooper()).idle()
                if (predicate()) return@withTimeout
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        const val TILE_PREFS = "tile_state"
        const val LAST_ENTRY_ID = "last_entry_id"

        // Generous: the first test in a JVM also pays for mock and coroutine warm-up.
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
