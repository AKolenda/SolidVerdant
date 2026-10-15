/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.repository

import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.AuthDataStoreFixture
import dev.tricked.solidverdant.data.local.OAuthTransaction
import dev.tricked.solidverdant.data.model.TokenResponse
import dev.tricked.solidverdant.data.remote.ApiClientFactory
import dev.tricked.solidverdant.data.remote.SolidtimeApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryOAuthTest {
    @get:Rule internal val fixture = AuthDataStoreFixture()
    private val api = mockk<SolidtimeApi>()
    private val factory = mockk<ApiClientFactory> { every { createApi(any()) } returns api }
    private val store get() = fixture.store
    private val repository by lazy { AuthRepository(store, factory) }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `duplicate callback rejection cannot discard the original successful exchange`() = runTest {
        // Keep storage synchronous here so runCurrent reaches a duplicate rejection and its
        // cleanup before releasing the original response. Other tests use real file-backed storage.
        val controlledStore = mockk<AuthDataStore>(relaxed = true)
        var pending: OAuthTransaction? = OAuthTransaction("state", "verifier", "https://issuer.invalid", "client")
        var installedAccess: String? = null
        coEvery { controlledStore.getOAuthTransaction(any()) } answers { pending?.takeIf { it.state == firstArg<String>() } }
        coEvery { controlledStore.clearPKCEData(any()) } answers {
            if (pending == firstArg<OAuthTransaction>()) pending = null
        }
        coEvery { controlledStore.completeOAuthLogin(any(), any(), any()) } answers {
            if (pending == firstArg<OAuthTransaction>()) {
                installedAccess = secondArg()
                pending = null
                true
            } else {
                false
            }
        }
        val subject = AuthRepository(controlledStore, factory)
        val originalResponse = CompletableDeferred<TokenResponse>()
        var exchanges = 0
        coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } coAnswers {
            exchanges++
            if (exchanges > 1) error("Authorization code already redeemed")
            originalResponse.await()
        }

        val original = async { subject.handleOAuthCallback("code", "state") }
        runCurrent()
        val duplicate = async { subject.handleOAuthCallback("code", "state") }
        runCurrent()
        originalResponse.complete(TokenResponse("access", "refresh"))

        assertTrue(original.await().isSuccess)
        assertTrue(duplicate.await().isFailure)
        assertEquals("access", installedAccess)
        coVerify(exactly = 1) { api.exchangeCodeForToken(any(), any(), any(), any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent duplicate callbacks exchange once and persist the original login`() = runTest {
        store.savePKCEData("verifier", "state")
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<TokenResponse>()
        coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            response.await()
        }
        val original = async { repository.handleOAuthCallback("code", "state") }
        started.await()
        val duplicate = async { repository.handleOAuthCallback("code", "state") }
        runCurrent()
        assertFalse(duplicate.isCompleted)
        response.complete(TokenResponse("access", "refresh"))

        assertTrue(original.await().isSuccess)
        assertTrue(duplicate.await().isFailure)
        assertEquals("access", store.getAccessToken())
        assertNotNull(store.getSession().id)
        coVerify(exactly = 1) { api.exchangeCodeForToken(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `newer flow completes while obsolete exchange is blocked and survives its late result`() = runTest {
        for (rejectOld in listOf(false, true)) {
            store.saveOAuthConfig("https://original.invalid", "original-client")
            store.savePKCEData("old-verifier", "old-state")
            val oldStarted = CompletableDeferred<Unit>()
            val finishOld = CompletableDeferred<Unit>()
            coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), "old-code") } coAnswers {
                oldStarted.complete(Unit)
                finishOld.await()
                if (rejectOld) error("Old exchange rejected")
                TokenResponse("old-access", "old-refresh")
            }
            coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), "new-code") } returns TokenResponse("new-access", "new-refresh")
            val oldCallback = async { repository.handleOAuthCallback("old-code", "old-state") }
            oldStarted.await()
            store.saveOAuthConfig("https://replacement.invalid", "replacement-client")
            store.savePKCEData("new-verifier", "new-state")

            assertTrue(repository.handleOAuthCallback("new-code", "new-state").isSuccess)
            assertFalse(oldCallback.isCompleted)
            val replacementSession = store.getSession()
            assertEquals("new-access", replacementSession.accessToken)
            finishOld.complete(Unit)

            assertTrue(oldCallback.await().isFailure)
            assertEquals(replacementSession, store.getSession())
        }
        verify(exactly = 2) { factory.createApi("https://replacement.invalid") }
        coVerify(exactly = 2) {
            api.exchangeCodeForToken("authorization_code", "replacement-client", "solidtime://oauth/callback", "new-verifier", "new-code")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling an exchange releases a queued callback for that transaction`() = runTest {
        store.savePKCEData("verifier", "state")
        val started = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<TokenResponse>()
        var exchanges = 0
        coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } coAnswers {
            exchanges++
            if (exchanges == 1) {
                started.complete(Unit)
                neverCompletes.await()
            } else {
                TokenResponse("access", "refresh")
            }
        }
        val original = async { repository.handleOAuthCallback("code", "state") }
        started.await()
        val queued = async { repository.handleOAuthCallback("code", "state") }
        runCurrent()
        assertFalse(queued.isCompleted)

        original.cancelAndJoin()

        assertTrue(queued.await().isSuccess)
        assertEquals("access", store.getAccessToken())
        coVerify(exactly = 2) { api.exchangeCodeForToken(any(), any(), any(), any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a queued duplicate leaves the original exchange and pending transaction intact`() = runTest {
        val transaction = store.savePKCEData("verifier", "state")
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<TokenResponse>()
        coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            response.await()
        }
        val original = async { repository.handleOAuthCallback("code", "state") }
        started.await()
        val duplicate = async { repository.handleOAuthCallback("code", "state") }
        runCurrent()

        duplicate.cancelAndJoin()
        assertEquals(transaction, store.getOAuthTransaction("state"))
        response.complete(TokenResponse("access", "refresh"))

        assertTrue(original.await().isSuccess)
        assertEquals("access", store.getAccessToken())
        coVerify(exactly = 1) { api.exchangeCodeForToken(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `authorization captures issuer and client with persistent encrypted PKCE`() = runTest {
        store.saveOAuthConfig("https://issuer.invalid", "original-client")
        val authorization = repository.initializeOAuthFlow().getOrThrow().toHttpUrl()
        val state = requireNotNull(authorization.queryParameter("state"))
        val pending = store.getOAuthTransaction(state)

        assertNotNull(pending)
        assertEquals("https://issuer.invalid", pending!!.endpoint)
        assertEquals("original-client", pending.clientId)
        assertEquals("original-client", authorization.queryParameter("client_id"))
        val restored = AuthDataStore(fixture.preferences, fixture.cipher)
        assertEquals(pending, restored.getOAuthTransaction(state))
    }

    @Test
    fun `configuration save or reset invalidates the pending flow before any exchange`() = runTest {
        suspend fun rejectAfter(changeConfig: suspend () -> Unit) {
            store.saveOAuthConfig("https://issuer.invalid", "original-client")
            store.savePKCEData("verifier", "state")
            changeConfig()
            assertTrue(repository.handleOAuthCallback("code", "state").isFailure)
            assertNull(store.getAccessToken())
        }

        rejectAfter { store.saveOAuthConfig("https://replacement.invalid", "replacement-client") }
        rejectAfter { store.saveOAuthConfig("https://issuer.invalid", "replacement-client") }
        rejectAfter { store.resetOAuthConfig() }
        verify(exactly = 0) { factory.createApi(any()) }
    }

    @Test
    fun `successful callback uses recorded issuer and consumes its transaction once`() = runTest {
        store.saveOAuthConfig("https://issuer.invalid", "original-client")
        store.savePKCEData("verifier", "state")
        coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } returns TokenResponse("access", "refresh")

        assertTrue(repository.handleOAuthCallback("code", "state").isSuccess)
        assertEquals("access", store.getAccessToken())
        assertNotNull(store.getSession().id)
        assertNull(store.getOAuthTransaction("state"))
        assertTrue(repository.handleOAuthCallback("code", "state").isFailure)
        verify(exactly = 1) { factory.createApi("https://issuer.invalid") }
        coVerify(exactly = 1) {
            api.exchangeCodeForToken("authorization_code", "original-client", "solidtime://oauth/callback", "verifier", "code")
        }
    }

    @Test
    fun `late successful callback cannot install tokens after config change logout or replacement login`() = runTest {
        suspend fun rejectAfter(replace: suspend () -> Unit, expectedAccess: String?) {
            store.clearAll()
            store.savePKCEData("old-verifier", "old-state")
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<TokenResponse>()
            coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                response.await()
            }
            val callback = async { repository.handleOAuthCallback("old-code", "old-state") }
            started.await()
            replace()
            response.complete(TokenResponse("late-access", "late-refresh"))

            assertTrue(callback.await().isFailure)
            assertEquals(expectedAccess, store.getAccessToken())
        }

        rejectAfter({ store.saveOAuthConfig("https://replacement.invalid", "replacement-client") }, null)
        rejectAfter({ store.clearAll() }, null)
        rejectAfter({ store.saveTokens("replacement-access", "replacement-refresh") }, "replacement-access")
    }

    @Test
    fun `late successful or failed callback preserves a newer pending flow`() = runTest {
        for (fail in listOf(false, true)) {
            store.savePKCEData("old-verifier", "old-state")
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
                if (fail) error("Synthetic exchange failure")
                TokenResponse("late-access", "late-refresh")
            }
            val callback = async { repository.handleOAuthCallback("old-code", "old-state") }
            started.await()
            val replacement = store.savePKCEData("new-verifier", "new-state")
            finish.complete(Unit)

            assertTrue(callback.await().isFailure)
            assertNull(store.getAccessToken())
            assertEquals(replacement, store.getOAuthTransaction("new-state"))
        }
    }

    @Test
    fun `exchange cancellation propagates without clearing a newer flow`() = runTest {
        store.savePKCEData("verifier", "state")
        coEvery { api.exchangeCodeForToken(any(), any(), any(), any(), any()) } coAnswers {
            store.savePKCEData("new-verifier", "new-state")
            throw CancellationException("cancelled")
        }

        val result = runCatching { repository.handleOAuthCallback("code", "state") }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertNotNull(store.getOAuthTransaction("new-state"))
        assertFalse(store.isLoggedIn.first())
    }
}
