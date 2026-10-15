/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.local.UserCacheCleaner
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.data.remote.ConnectionTester
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TemplateRepository
import dev.tricked.solidverdant.sync.SyncScheduler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthViewModelOAuthTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<AuthRepository>(relaxed = true) {
        every { isLoggedIn } returns flowOf(false)
        every { endpoint } returns flowOf("https://issuer.invalid")
        every { clientId } returns flowOf("client")
        coEvery { initializeOAuthFlow() } returns Result.success("https://issuer.invalid/oauth/authorize")
        coEvery { getCurrentUser() } returns Result.success(User("user", "User", "user@example.invalid"))
        coEvery { getMyMemberships() } returns Result.success(emptyList())
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `duplicate callback failure cannot overwrite a successful login`() = runTest(dispatcher.scheduler) {
        val first = CompletableDeferred<Result<Unit>>()
        val duplicate = CompletableDeferred<Result<Unit>>()
        coEvery { repository.handleOAuthCallback("code", "state") } coAnswers { first.await() } coAndThen { duplicate.await() }
        val viewModel = viewModel()
        viewModel.startOAuthFlow()
        viewModel.handleOAuthCallback("code", "state")
        viewModel.handleOAuthCallback("code", "state")

        first.complete(Result.success(Unit))
        dispatcher.scheduler.advanceUntilIdle()
        duplicate.complete(Result.failure(IllegalStateException()))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("user", viewModel.uiState.value.user?.id)
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `late callback failure cannot clear a newer flows loading state`() = runTest(dispatcher.scheduler) {
        val oldCallback = CompletableDeferred<Result<Unit>>()
        coEvery { repository.handleOAuthCallback("old-code", "old-state") } coAnswers { oldCallback.await() }
        val viewModel = viewModel()
        viewModel.startOAuthFlow()
        viewModel.handleOAuthCallback("old-code", "old-state")
        val newAuthorization = CompletableDeferred<Result<String>>()
        coEvery { repository.initializeOAuthFlow() } coAnswers { newAuthorization.await() }
        viewModel.startOAuthFlow()

        oldCallback.complete(Result.failure(IllegalStateException()))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)

        newAuthorization.complete(Result.success("https://issuer.invalid/new-authorization"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("https://issuer.invalid/new-authorization", viewModel.uiState.value.authUrl)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    private fun viewModel() = AuthViewModel(
        authRepository = repository,
        userCacheCleaner = mockk<UserCacheCleaner>(relaxed = true),
        settingsDataStore = mockk<SettingsDataStore>(relaxed = true),
        templateRepository = mockk<TemplateRepository>(relaxed = true),
        connectionTester = mockk<ConnectionTester>(relaxed = true),
        syncScheduler = mockk<SyncScheduler>(relaxed = true),
        context = ApplicationProvider.getApplicationContext<Context>(),
    )
}
