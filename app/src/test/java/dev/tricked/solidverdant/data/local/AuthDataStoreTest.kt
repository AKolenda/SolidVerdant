/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthDataStoreTest {
    @get:Rule internal val fixture = AuthDataStoreFixture()
    private val store get() = fixture.store

    @Test
    fun `legacy credentials are encrypted and receive a persistent session identity`() = runTest {
        fixture.preferences.edit {
            it[stringPreferencesKey("access_token")] = "legacy-access"
            it[stringPreferencesKey("refresh_token")] = "legacy-refresh"
            it[stringPreferencesKey("code_verifier")] = "legacy-pending-verifier"
            it[stringPreferencesKey("oauth_state")] = "legacy-pending-state"
        }

        val migrated = store.getSession()

        assertNotNull(migrated.id)
        assertEquals("legacy-access", migrated.accessToken)
        assertEquals("legacy-refresh", migrated.refreshToken)
        assertTrue(store.isLoggedIn.first())
        assertTrue(fixture.cipher.isEncrypted(fixture.preferences.data.first()[stringPreferencesKey("access_token")]!!))
        assertNull(store.getOAuthTransaction("legacy-pending-state"))
        assertEquals(migrated, AuthDataStore(fixture.preferences, fixture.cipher).getSession())
    }

    @Test
    fun `lost encryption key fails closed without retaining a session identity`() = runTest {
        fixture.preferences.edit {
            it[stringPreferencesKey("access_token")] = fixture.cipher.encrypt("access")
            it[stringPreferencesKey("refresh_token")] = fixture.cipher.encrypt("refresh")
            it[stringPreferencesKey("session_id")] = "old-session"
        }
        val restored = AuthDataStore(fixture.preferences, AuthDataStoreFixture.newCipher())

        assertNull(restored.getSession().id)
        assertNull(restored.getAccessToken())
        assertNull(restored.getRefreshToken())
        assertFalse(restored.isLoggedIn.first())
    }

    @Test
    fun `refresh retains identity but login and configuration replacement invalidate it`() = runTest {
        store.saveTokens("access", "refresh")
        val original = store.getSession()

        assertTrue(store.saveRefreshedTokens(original, "rotated-access", "rotated-refresh"))
        assertEquals(original.id, store.getSession().id)
        assertFalse(store.saveRefreshedTokens(original, "late-access", "late-refresh"))

        store.saveTokens("rotated-access", "rotated-refresh")
        assertNotEquals(original.id, store.getSession().id)
        store.saveOAuthConfig("https://other.invalid", "other-client")
        assertNull(store.getSession().id)
        assertNull(store.getAccessToken())
        assertFalse(store.saveRefreshedTokens(original, "late-access", "late-refresh"))
    }

    @Test
    fun `logout cannot be overtaken by cache publication after a durable token write`() = runTest {
        store.getSession()
        val pause = PausingPreferencesStore.Pause()
        fixture.preferences.nextWritePause = pause
        val save = async { store.saveTokens("outgoing-access", "outgoing-refresh") }
        pause.committed.await()
        val logout = async { store.clearAll() }
        runCurrent()
        assertFalse(logout.isCompleted)

        pause.release.complete(Unit)
        save.await()
        logout.await()

        assertNull(store.getAccessToken())
        assertNull(store.getSession().id)
        assertNull(fixture.preferences.data.first()[stringPreferencesKey("access_token")])
    }

    @Test
    fun `cold credential initialization cannot publish a stale cache after logout`() = runTest {
        fixture.preferences.edit {
            it[stringPreferencesKey("access_token")] = "old-access"
            it[stringPreferencesKey("refresh_token")] = "old-refresh"
        }
        val pause = PausingPreferencesStore.Pause()
        fixture.preferences.nextWritePause = pause
        val read = async { store.getAccessToken() }
        pause.committed.await()
        val logout = async { store.clearTokens() }
        runCurrent()
        assertFalse(logout.isCompleted)

        pause.release.complete(Unit)
        read.await()
        logout.await()

        assertNull(store.getAccessToken())
        assertFalse(store.isLoggedIn.first())
    }

    @Test
    fun `account cleanup completes before a replacement login can publish credentials and work`() = runTest {
        store.saveTokens("old-access", "old-refresh")
        val outgoing = store.getSession()
        val entries = mutableListOf("old offline work")
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val invalidation = async {
            store.invalidateSession(outgoing) {
                cleanupStarted.complete(Unit)
                finishCleanup.await()
                entries.clear()
            }
        }
        cleanupStarted.await()
        val login = async {
            store.saveTokens("replacement-access", "replacement-refresh")
            entries += "replacement offline work"
        }
        runCurrent()
        assertFalse(login.isCompleted)

        finishCleanup.complete(Unit)
        assertTrue(invalidation.await())
        login.await()

        assertEquals("replacement-access", store.getAccessToken())
        assertEquals(listOf("replacement offline work"), entries)
        assertFalse(store.invalidateSession(outgoing) { error("Stale cleanup must not run") })
        assertEquals(listOf("replacement offline work"), entries)
    }

    @Test
    fun `cancellation during logout finishes cleanup and invalidation before releasing a queued login`() = runTest {
        store.saveTokens("old-access", "old-refresh")
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        var returnedToCaller = false
        val logout = launch {
            store.clearAll {
                cleanupStarted.complete(Unit)
                finishCleanup.await()
                cleaned = true
            }
            returnedToCaller = true
        }
        cleanupStarted.await()
        logout.cancel()
        val login = async { store.saveTokens("replacement-access", "replacement-refresh") }
        runCurrent()
        assertFalse(login.isCompleted)
        finishCleanup.complete(Unit)
        logout.join()
        login.await()

        assertTrue(cleaned)
        assertTrue(logout.isCancelled)
        assertFalse(returnedToCaller)
        assertEquals("replacement-access", store.getAccessToken())
    }

    @Test
    fun `cleanup failure preserves credentials for recovery and releases the transition lock`() = runTest {
        store.saveTokens("access", "refresh")
        val original = store.getSession()
        val result = runCatching { store.invalidateSession(original) { error("Cleanup unavailable") } }

        assertTrue(result.isFailure)
        assertEquals(original, store.getSession())
        assertTrue(store.invalidateSession(original) { })
        assertNull(store.getAccessToken())
    }
}
