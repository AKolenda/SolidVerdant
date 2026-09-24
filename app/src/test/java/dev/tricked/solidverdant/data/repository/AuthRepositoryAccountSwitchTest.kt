/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.AccountDataOwnerGuard
import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.remote.ApiClientFactory
import dev.tricked.solidverdant.data.remote.AuthInterceptor
import dev.tricked.solidverdant.di.NetworkModule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A new sign-in identifies its account *before* the tokens become the active session, so another
 * account's cached outbox is cleared before any request of the new session can upload it.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryAccountSwitchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsDataStore
    private val identifyAuthorization = mutableListOf<String?>()

    @Volatile private var storedAccessToken: String? = null
    private var tokenWhenWiped: String? = "not-wiped"

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.path).substringBefore("?")
                val body = when {
                    path.endsWith("/oauth/token") -> """{"access_token":"new-access","refresh_token":"new-refresh"}"""
                    path.endsWith("/api/v1/users/me") -> {
                        identifyAuthorization += request.getHeader("Authorization")
                        """{"data":{"id":"user-b","name":"B","email":"b@example.invalid"}}"""
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        settings = SettingsDataStore(context)
        runBlocking { settings.clearCachedData() }
        settings.setDataOwner(SettingsDataStore.DataOwner(endpoint(), "user-a"))
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun endpoint() = server.url("/").toString().removeSuffix("/")

    private fun authDataStore(): AuthDataStore = mockk(relaxed = true) {
        coEvery { getState() } returns "state"
        coEvery { getCodeVerifier() } returns "verifier"
        coEvery { getEndpoint() } returns endpoint()
        coEvery { getClientId() } returns "test-client"
        coEvery { getAccessToken() } answers { storedAccessToken }
        coEvery { saveTokens(any(), any()) } answers { storedAccessToken = firstArg() }
    }

    @Test fun sign_in_as_another_account_wipes_before_tokens_are_saved() = runTest {
        val authDataStore = authDataStore()
        val guard = AccountDataOwnerGuard(settings) { tokenWhenWiped = storedAccessToken }
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(authDataStore)).build()
        val repository = AuthRepository(authDataStore, ApiClientFactory(client, NetworkModule.provideJson()), guard)

        assertTrue(repository.handleOAuthCallback(code = "code", state = "state").isSuccess)

        assertEquals(listOf<String?>("Bearer new-access"), identifyAuthorization)
        assertNull("The previous account's data must be cleared before the new tokens are active", tokenWhenWiped)
        assertEquals("new-access", storedAccessToken)
        assertEquals("user-b", settings.getDataOwner()?.userId)
    }

    @Test fun later_profile_fetch_also_claims_the_account() = runTest {
        storedAccessToken = "session"
        val authDataStore = authDataStore()
        var wipes = 0
        val guard = AccountDataOwnerGuard(settings) { wipes += 1 }
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(authDataStore)).build()
        val repository = AuthRepository(authDataStore, ApiClientFactory(client, NetworkModule.provideJson()), guard)

        assertEquals("user-b", repository.getCurrentUser().getOrThrow().id)

        assertEquals(1, wipes)
        assertEquals(listOf<String?>("Bearer session"), identifyAuthorization)
    }
}
