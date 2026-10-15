/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.remote

import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.AuthSession
import dev.tricked.solidverdant.data.local.UserCacheCleaner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AuthInterceptorTest {
    private val session = AuthSession("session-1", "https://example.test", "client", "access", "refresh")

    @Test
    fun `authenticated requests carry their originating identity without credential secrets`() {
        val store = mockk<AuthDataStore>()
        coEvery { store.getSession() } returns session

        val request = intercept(store, Request.Builder().url("https://EXAMPLE.test:443/api").build())

        assertEquals("Bearer access", request.header("Authorization"))
        assertEquals(
            RequestAuthSession("session-1", "https://example.test", "client"),
            request.tag(RequestAuthSession::class.java),
        )
        coVerify(exactly = 1) { store.getSession() }
    }

    @Test
    fun `foreign hosts ports and schemes receive neither bearer tokens nor session tags`() {
        val store = mockk<AuthDataStore>()
        coEvery { store.getSession() } returns session

        listOf("https://foreign.test/api", "http://example.test/api", "https://example.test:8443/api").forEach { url ->
            val request = intercept(store, previouslyAuthenticatedRequest(url))

            assertNull(request.header("Authorization"))
            assertNull(request.tag(RequestAuthSession::class.java))
        }
    }

    @Test
    fun `OAuth token requests remove inherited authorization and session tags`() {
        val store = mockk<AuthDataStore>()

        val request = intercept(store, previouslyAuthenticatedRequest("https://example.test/oauth/token"))

        assertNull(request.header("Authorization"))
        assertNull(request.tag(RequestAuthSession::class.java))
        coVerify(exactly = 0) { store.getSession() }
    }

    @Test
    fun `missing session identity or credentials leaves requests unauthenticated`() {
        val store = mockk<AuthDataStore>()
        listOf(session.copy(id = null), session.copy(accessToken = null), session.copy(accessToken = "")).forEach { incomplete ->
            coEvery { store.getSession() } returns incomplete

            val request = intercept(store, previouslyAuthenticatedRequest("https://example.test/api"))

            assertNull(request.header("Authorization"))
            assertNull(request.tag(RequestAuthSession::class.java))
        }
    }

    @Test
    fun `unreadable authentication storage leaves requests unauthenticated`() {
        val store = mockk<AuthDataStore>()
        coEvery { store.getSession() } throws IOException("Storage unavailable")

        val request = intercept(store, previouslyAuthenticatedRequest("https://example.test/api"))

        assertNull(request.header("Authorization"))
        assertNull(request.tag(RequestAuthSession::class.java))
    }

    @Test(expected = CancellationException::class)
    fun `cancelled credential read does not continue an unauthenticated request`() {
        val store = mockk<AuthDataStore>()
        coEvery { store.getSession() } throws CancellationException("cancelled")

        intercept(store, previouslyAuthenticatedRequest("https://example.test/api"))
    }

    @Test
    fun `redirected 401 cannot trigger a credential retry to another origin`() {
        val original = MockWebServer()
        val redirected = MockWebServer()
        original.start()
        redirected.start()
        try {
            val current = session.copy(endpoint = original.url("/").toString())
            val store = mockk<AuthDataStore>()
            coEvery { store.getSession() } returns current
            val cleaner = mockk<UserCacheCleaner>()
            val client = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(store))
                .authenticator(TokenAuthenticator(store, Json, cleaner))
                .build()
            original.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected.url("/api")))
            redirected.enqueue(MockResponse().setResponseCode(401))
            redirected.enqueue(MockResponse().setResponseCode(200))

            client.newCall(Request.Builder().url(original.url("/api")).build()).execute().use { response ->
                assertEquals(401, response.code)
            }

            assertEquals("Bearer access", original.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Authorization"))
            assertNull(redirected.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Authorization"))
            assertEquals(1, redirected.requestCount)
            coVerify(exactly = 1) { store.getSession() }
            coVerify(exactly = 0) { cleaner.clear() }
        } finally {
            original.shutdown()
            redirected.shutdown()
        }
    }

    @Test
    fun `delayed HTTP 401 never replays a request with replacement account credentials`() {
        listOf(false, true).forEach { changeEndpoint ->
            val original = MockWebServer()
            val replacement = MockWebServer()
            val releaseResponse = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            original.start()
            replacement.start()
            try {
                val current = AtomicReference(session.copy(endpoint = original.url("/").toString()))
                val store = mockk<AuthDataStore>()
                coEvery { store.getSession() } answers { current.get() }
                val cleaner = mockk<UserCacheCleaner>()
                val client = OkHttpClient.Builder()
                    .addInterceptor(AuthInterceptor(store))
                    .authenticator(TokenAuthenticator(store, Json, cleaner))
                    .build()
                original.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        check(releaseResponse.await(5, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(if (request.sequenceNumber == 0) 401 else 200)
                    }
                }
                val result = executor.submit<Int> {
                    client.newCall(Request.Builder().url(original.url("/api")).build()).execute().use { it.code }
                }
                val sent = requireNotNull(original.takeRequest(5, TimeUnit.SECONDS))
                current.set(
                    current.get().copy(
                        id = "session-2",
                        endpoint = if (changeEndpoint) replacement.url("/").toString() else current.get().endpoint,
                        accessToken = "replacement-access",
                        refreshToken = "replacement-refresh",
                    ),
                )
                releaseResponse.countDown()

                assertEquals(401, result.get(5, TimeUnit.SECONDS))
                assertEquals("Bearer access", sent.getHeader("Authorization"))
                assertEquals(1, original.requestCount)
                assertEquals(0, replacement.requestCount)
                coVerify(exactly = 0) { cleaner.clear() }
            } finally {
                releaseResponse.countDown()
                executor.shutdownNow()
                original.shutdown()
                replacement.shutdown()
            }
        }
    }

    @Test
    fun `HTTP refresh rotates credentials and authenticates the original session retry`() {
        val server = MockWebServer()
        server.start()
        try {
            val current = AtomicReference(session.copy(endpoint = server.url("/").toString()))
            val store = mockk<AuthDataStore>()
            coEvery { store.getSession() } answers { current.get() }
            coEvery { store.saveRefreshedTokens(any(), any(), any()) } coAnswers {
                val expected = firstArg<AuthSession>()
                current.compareAndSet(expected, expected.copy(accessToken = secondArg(), refreshToken = thirdArg()))
            }
            val cleaner = mockk<UserCacheCleaner>()
            val client = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(store))
                .authenticator(TokenAuthenticator(store, Json, cleaner))
                .build()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh"}"""))
            server.enqueue(MockResponse().setResponseCode(200))

            client.newCall(Request.Builder().url(server.url("/api")).build()).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals("Bearer access", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Authorization"))
            val refresh = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/oauth/token", refresh.path)
            assertEquals("grant_type=refresh_token&client_id=client&refresh_token=refresh", refresh.body.readUtf8())
            assertNull(refresh.getHeader("Authorization"))
            assertEquals("Bearer new-access", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Authorization"))
            assertEquals("new-refresh", current.get().refreshToken)
            coVerify(exactly = 0) { cleaner.clear() }
        } finally {
            server.shutdown()
        }
    }

    private fun previouslyAuthenticatedRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer obsolete")
        .tag(RequestAuthSession::class.java, RequestAuthSession("session-0", "https://example.test", "client"))
        .build()

    private fun intercept(store: AuthDataStore, request: Request): Request {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(firstArg())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        return AuthInterceptor(store).intercept(chain).request
    }
}
