/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.remote

import dev.tricked.solidverdant.data.local.AuthSession
import dev.tricked.solidverdant.data.local.UserCacheCleaner
import dev.tricked.solidverdant.data.model.TokenResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest {
    @Test(expected = CancellationException::class)
    fun `cancelled refresh propagates cancellation`() {
        val storage = FakeTokenStorage("access", "refresh")
        val authenticator = TokenAuthenticator(storage) { _, _, _ -> throw CancellationException("cancelled") }

        authenticator.authenticate(null, unauthorized("access"))
    }

    @Test
    fun `concurrent 401s share one refresh and both retry with new token`() {
        repeat(20) {
            val storage = FakeTokenStorage("old-access", "old-refresh")
            val enteredRefresh = CountDownLatch(1)
            val releaseRefresh = CountDownLatch(1)
            val refreshCalls = AtomicInteger()
            val authenticator = TokenAuthenticator(storage) { _, _, _ ->
                refreshCalls.incrementAndGet()
                enteredRefresh.countDown()
                check(releaseRefresh.await(5, TimeUnit.SECONDS))
                RefreshResult.Success(TokenResponse("new-access", "new-refresh"))
            }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Request?> { authenticator.authenticate(null, unauthorized("old-access")) }
                check(enteredRefresh.await(5, TimeUnit.SECONDS))
                val second = pool.submit<Request?> { authenticator.authenticate(null, unauthorized("old-access")) }
                releaseRefresh.countDown()

                assertEquals("Bearer new-access", first.get(5, TimeUnit.SECONDS)?.header("Authorization"))
                assertEquals("Bearer new-access", second.get(5, TimeUnit.SECONDS)?.header("Authorization"))
                assertEquals(1, refreshCalls.get())
                assertEquals("new-access", storage.access)
                assertEquals("new-refresh", storage.refresh)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `transient refresh failure leaves credentials intact`() {
        val storage = FakeTokenStorage("access", "refresh")
        val authenticator = TokenAuthenticator(storage) { _, _, _ -> RefreshResult.Transient }

        assertNull(authenticator.authenticate(null, unauthorized("access")))
        assertEquals("access", storage.access)
        assertEquals("refresh", storage.refresh)
    }

    @Test
    fun `definitive refresh rejection clears the dead credentials`() {
        val storage = FakeTokenStorage("access", "refresh")
        val authenticator = TokenAuthenticator(storage) { _, _, _ -> RefreshResult.Invalid }

        assertNull(authenticator.authenticate(null, unauthorized("access")))
        assertNull(storage.access)
        assertNull(storage.refresh)
    }

    @Test
    fun `missing refresh token clears the dead access token`() {
        val storage = FakeTokenStorage("access", null)
        val refreshCalls = AtomicInteger()
        val authenticator = TokenAuthenticator(storage) { _, _, _ ->
            refreshCalls.incrementAndGet()
            RefreshResult.Invalid
        }

        assertNull(authenticator.authenticate(null, unauthorized("access")))
        assertNull(storage.access)
        assertNull(storage.refresh)
        assertEquals("refresh must not be attempted without a refresh token", 0, refreshCalls.get())
    }

    @Test
    fun `missing refresh token clears the account cache`() {
        val storage = FakeTokenStorage("access", null)
        val userCacheCleaner = mockk<UserCacheCleaner>()
        coEvery { userCacheCleaner.clear() } returns Unit
        val authenticator = TokenAuthenticator(storage, userCacheCleaner) { _, _, _ ->
            error("refresh must not be attempted without a refresh token")
        }

        assertNull(authenticator.authenticate(null, unauthorized("access")))

        coVerify(exactly = 1) { userCacheCleaner.clear() }
    }

    @Test
    fun `definitive refresh rejection clears the account cache`() {
        val storage = FakeTokenStorage("access", "refresh")
        val userCacheCleaner = mockk<UserCacheCleaner>()
        coEvery { userCacheCleaner.clear() } returns Unit
        val authenticator = TokenAuthenticator(storage, userCacheCleaner) { _, _, _ -> RefreshResult.Invalid }

        assertNull(authenticator.authenticate(null, unauthorized("access")))

        coVerify(exactly = 1) { userCacheCleaner.clear() }
    }

    @Test
    fun `successful refresh does not clear the account cache`() {
        val storage = FakeTokenStorage("access", "refresh")
        val userCacheCleaner = mockk<UserCacheCleaner>()
        coEvery { userCacheCleaner.clear() } returns Unit
        val authenticator = TokenAuthenticator(storage, userCacheCleaner) { _, _, _ ->
            RefreshResult.Success(TokenResponse("new-access", "new-refresh"))
        }

        val retried = authenticator.authenticate(null, unauthorized("access"))

        assertEquals("Bearer new-access", retried?.header("Authorization"))
        coVerify(exactly = 0) { userCacheCleaner.clear() }
    }

    @Test
    fun `401 for obsolete token reuses current token without refreshing`() {
        val storage = FakeTokenStorage("new-access", "new-refresh")
        val refreshCalls = AtomicInteger()
        val authenticator = TokenAuthenticator(storage) { _, _, _ ->
            refreshCalls.incrementAndGet()
            RefreshResult.Invalid
        }

        val retried = authenticator.authenticate(null, unauthorized("old-access"))

        assertEquals("Bearer new-access", retried?.header("Authorization"))
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `late refresh completion cannot resurrect credentials cleared by logout`() {
        val storage = FakeTokenStorage("old-access", "old-refresh")
        val enteredRefresh = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val authenticator = TokenAuthenticator(storage) { _, _, _ ->
            enteredRefresh.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS))
            RefreshResult.Success(TokenResponse("new-access", "new-refresh"))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Request?> {
                authenticator.authenticate(null, unauthorized("old-access"))
            }
            check(enteredRefresh.await(5, TimeUnit.SECONDS))

            runBlocking { storage.clearTokens() }
            releaseRefresh.countDown()

            assertNull(result.get(5, TimeUnit.SECONDS))
            assertNull(storage.access)
            assertNull(storage.refresh)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `late refresh rejection preserves replacement session and account cache`() {
        val storage = FakeTokenStorage("old-access", "old-refresh")
        val enteredRefresh = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val userCacheCleaner = mockk<UserCacheCleaner>()
        coEvery { userCacheCleaner.clear() } returns Unit
        val authenticator = TokenAuthenticator(storage, userCacheCleaner) { _, _, _ ->
            enteredRefresh.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS))
            RefreshResult.Invalid
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Request?> {
                authenticator.authenticate(null, unauthorized("old-access"))
            }
            check(enteredRefresh.await(5, TimeUnit.SECONDS))

            runBlocking { storage.saveTokens("replacement-access", "replacement-refresh") }
            releaseRefresh.countDown()

            assertNull(result.get(5, TimeUnit.SECONDS))
            assertEquals("replacement-access", storage.access)
            assertEquals("replacement-refresh", storage.refresh)
            coVerify(exactly = 0) { userCacheCleaner.clear() }
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `401 from replaced account never retries with its replacement credentials`() {
        listOf("https://example.test", "https://replacement.test").forEach { endpoint ->
            val storage = FakeTokenStorage("old-access", "old-refresh")
            val response = unauthorized("old-access")
            val authenticator = TokenAuthenticator(storage) { _, _, _ ->
                error("a replaced session must not refresh")
            }
            runBlocking { storage.saveTokens("replacement-access", "old-refresh", endpoint) }

            assertNull(authenticator.authenticate(null, response))
            assertEquals("replacement-access", storage.access)
            assertEquals("old-refresh", storage.refresh)
        }
    }

    @Test
    fun `late successful refresh never overwrites or retries with a replacement session`() {
        listOf("https://example.test", "https://replacement.test").forEach { endpoint ->
            val storage = FakeTokenStorage("old-access", "old-refresh")
            val authenticator = TokenAuthenticator(storage) { refreshEndpoint, clientId, refreshToken ->
                assertEquals("https://example.test", refreshEndpoint)
                assertEquals("client", clientId)
                assertEquals("old-refresh", refreshToken)
                runBlocking { storage.saveTokens("replacement-access", "old-refresh", endpoint) }
                RefreshResult.Success(TokenResponse("stale-access", "stale-refresh"))
            }

            assertNull(authenticator.authenticate(null, unauthorized("old-access")))
            assertEquals("replacement-access", storage.access)
            assertEquals("old-refresh", storage.refresh)
        }
    }

    @Test
    fun `late rejection from previous server cannot invalidate replacement server credentials`() {
        val storage = FakeTokenStorage("old-access", "old-refresh")
        val userCacheCleaner = mockk<UserCacheCleaner>()
        coEvery { userCacheCleaner.clear() } returns Unit
        val authenticator = TokenAuthenticator(storage, userCacheCleaner) { _, _, _ ->
            runBlocking { storage.saveTokens("replacement-access", "old-refresh", "https://replacement.test") }
            RefreshResult.Invalid
        }

        assertNull(authenticator.authenticate(null, unauthorized("old-access")))
        assertEquals("replacement-access", storage.access)
        coVerify(exactly = 0) { userCacheCleaner.clear() }
    }

    @Test
    fun `missing refresh invalidation cannot clear a concurrently replaced session`() {
        val storage = FakeTokenStorage("old-access", null)
        val userCacheCleaner = mockk<UserCacheCleaner>()
        coEvery { userCacheCleaner.clear() } returns Unit
        storage.beforeInvalidation = { storage.saveTokens("replacement-access", "replacement-refresh") }
        val authenticator = TokenAuthenticator(storage, userCacheCleaner) { _, _, _ ->
            error("missing refresh token must not refresh")
        }

        assertNull(authenticator.authenticate(null, unauthorized("old-access")))
        assertEquals("replacement-access", storage.access)
        assertEquals("replacement-refresh", storage.refresh)
        coVerify(exactly = 0) { userCacheCleaner.clear() }
    }

    @Test
    fun `rejected refresh cannot invalidate a newer rotation within the same session`() {
        val storage = FakeTokenStorage("old-access", "old-refresh")
        val userCacheCleaner = mockk<UserCacheCleaner>()
        coEvery { userCacheCleaner.clear() } returns Unit
        val authenticator = TokenAuthenticator(storage, userCacheCleaner) { _, _, _ ->
            runBlocking { storage.saveRefreshedTokens(storage.getSession(), "newer-access", "newer-refresh") }
            RefreshResult.Invalid
        }

        assertNull(authenticator.authenticate(null, unauthorized("old-access")))
        assertEquals("newer-access", storage.access)
        assertEquals("newer-refresh", storage.refresh)
        coVerify(exactly = 0) { userCacheCleaner.clear() }
    }

    @Test
    fun `competing refresh retries with the newest credentials from the same session`() {
        val storage = FakeTokenStorage("old-access", "old-refresh")
        val authenticator = TokenAuthenticator(storage) { _, _, _ ->
            runBlocking { storage.saveRefreshedTokens(storage.getSession(), "newer-access", "newer-refresh") }
            RefreshResult.Success(TokenResponse("stale-access", "stale-refresh"))
        }

        assertEquals("Bearer newer-access", authenticator.authenticate(null, unauthorized("old-access"))?.header("Authorization"))
        assertEquals("newer-access", storage.access)
        assertEquals("newer-refresh", storage.refresh)
    }

    @Test
    fun `untagged and foreign origin requests never receive credentials`() {
        val storage = FakeTokenStorage("access", "refresh")
        val authenticator = TokenAuthenticator(storage) { _, _, _ -> error("unowned requests must not refresh") }

        assertNull(authenticator.authenticate(null, unauthorized("access", session = null)))
        listOf("https://foreign.test/api", "http://example.test/api", "https://example.test:8443/api").forEach { url ->
            assertNull(authenticator.authenticate(null, unauthorized("access", url = url)))
        }
        assertEquals("access", storage.access)
        assertEquals("refresh", storage.refresh)
    }

    @Test
    fun `a retry that is rejected again does not refresh indefinitely`() {
        val storage = FakeTokenStorage("access", "refresh")
        val authenticator = TokenAuthenticator(storage) { _, _, _ -> error("a retry must not refresh again") }
        val response = unauthorized("access").newBuilder()
            .priorResponse(unauthorized("old-access"))
            .build()

        assertNull(authenticator.authenticate(null, response))
        assertEquals("access", storage.access)
    }

    private fun unauthorized(
        token: String,
        session: RequestAuthSession? = RequestAuthSession("session-1", "https://example.test", "client"),
        url: String = "https://example.test/api",
    ): Response {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .tag(RequestAuthSession::class.java, session)
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    private class FakeTokenStorage(access: String?, refresh: String?) : TokenStorage {
        private val mutex = Mutex()
        private var nextId = 1

        @Volatile private var session = AuthSession("session-1", "https://example.test", "client", access, refresh)

        val access: String? get() = session.accessToken
        val refresh: String? get() = session.refreshToken
        var beforeInvalidation: (suspend () -> Unit)? = null

        override suspend fun getSession(): AuthSession = mutex.withLock { session }

        suspend fun saveTokens(accessToken: String, refreshToken: String, endpoint: String = session.endpoint) {
            mutex.withLock {
                session =
                    session.copy(id = "session-${++nextId}", endpoint = endpoint, accessToken = accessToken, refreshToken = refreshToken)
            }
        }

        override suspend fun saveRefreshedTokens(expected: AuthSession, accessToken: String, refreshToken: String): Boolean =
            mutex.withLock {
                if (!expected.sameSession(session) || session != expected) return@withLock false
                session = session.copy(accessToken = accessToken, refreshToken = refreshToken)
                true
            }

        override suspend fun invalidateSession(expected: AuthSession, clearAccountData: suspend () -> Unit): Boolean {
            beforeInvalidation?.also { beforeInvalidation = null }?.invoke()
            return mutex.withLock {
                if (!expected.sameSession(session) || session != expected) return@withLock false
                clearAccountData()
                session = session.copy(id = null, accessToken = null, refreshToken = null)
                true
            }
        }

        suspend fun clearTokens() {
            mutex.withLock { session = session.copy(id = null, accessToken = null, refreshToken = null) }
        }
    }
}
