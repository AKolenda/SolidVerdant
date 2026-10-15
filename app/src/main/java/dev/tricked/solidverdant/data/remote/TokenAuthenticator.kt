/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.remote

import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.AuthSession
import dev.tricked.solidverdant.data.local.UserCacheCleaner
import dev.tricked.solidverdant.data.model.TokenResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal interface TokenStorage {
    suspend fun getSession(): AuthSession
    suspend fun saveRefreshedTokens(expected: AuthSession, accessToken: String, refreshToken: String): Boolean
    suspend fun invalidateSession(expected: AuthSession, clearAccountData: suspend () -> Unit): Boolean
}

private class DataStoreTokenStorage(private val store: AuthDataStore) : TokenStorage {
    override suspend fun getSession() = store.getSession()
    override suspend fun saveRefreshedTokens(expected: AuthSession, accessToken: String, refreshToken: String) =
        store.saveRefreshedTokens(expected, accessToken, refreshToken)
    override suspend fun invalidateSession(expected: AuthSession, clearAccountData: suspend () -> Unit) =
        store.invalidateSession(expected, clearAccountData)
}

/** Outcome of a refresh attempt, distinguishing dead credentials from a recoverable failure. */
internal sealed interface RefreshResult {
    data class Success(val tokens: TokenResponse) : RefreshResult

    /** The server definitively rejected the refresh token (401 / invalid_grant). Credentials are dead. */
    object Invalid : RefreshResult

    /** A transient failure (network error, timeout, 5xx). Stored credentials may still be valid. */
    object Transient : RefreshResult
}

internal fun interface TokenRefresher {
    fun refresh(endpoint: String, clientId: String, refreshToken: String): RefreshResult
}

private class HttpTokenRefresher(private val json: Json, private val client: OkHttpClient = OkHttpClient.Builder().build()) :
    TokenRefresher {
    override fun refresh(endpoint: String, clientId: String, refreshToken: String): RefreshResult {
        val request = Request.Builder()
            .url("${endpoint.removeSuffix("/")}/oauth/token")
            .post(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", clientId)
                    .add("refresh_token", refreshToken)
                    .build(),
            )
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.isSuccessful -> RefreshResult.Success(json.decodeFromString(body))
                    // 401, or 400 invalid_grant/invalid_client, means the refresh token is no longer
                    // usable: force logout. Other codes (e.g. 429, 5xx) are transient.
                    response.code == HTTP_UNAUTHORIZED || isDefinitiveRejection(response.code, body) -> {
                        Timber.w("Token refresh rejected with code: ${response.code}")
                        RefreshResult.Invalid
                    }
                    else -> {
                        Timber.w("Token refresh failed with code: ${response.code}")
                        RefreshResult.Transient
                    }
                }
            }
        } catch (e: IOException) {
            // Network/timeout failure: not evidence the credentials are invalid.
            Timber.w("Token refresh failed due to a network error")
            RefreshResult.Transient
        }
    }

    private fun isDefinitiveRejection(code: Int, body: String?): Boolean = code == HTTP_BAD_REQUEST &&
        body != null &&
        (body.contains("invalid_grant") || body.contains("invalid_client"))

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
    }
}

/** Refreshes an expired access token, allowing at most one refresh at a time. */
@Singleton
class TokenAuthenticator internal constructor(
    private val storage: TokenStorage,
    // Nullable with a default so existing unit tests can keep constructing this with just
    // (storage) { refresher }. Kept before [refresher] so the trailing-lambda test call sites bind
    // the lambda to [refresher] (the last param). Hilt always supplies a real instance.
    private val userCacheCleaner: UserCacheCleaner? = null,
    private val refresher: TokenRefresher,
) : Authenticator {
    @Inject
    constructor(authDataStore: AuthDataStore, json: Json, userCacheCleaner: UserCacheCleaner) : this(
        DataStoreTokenStorage(authDataStore),
        userCacheCleaner,
        HttpTokenRefresher(json),
    )

    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        val requestSession = response.request.tag(RequestAuthSession::class.java) ?: return null
        if (!response.request.url.hasAuthOrigin(requestSession.endpoint)) return null
        val authorization = response.request.header("Authorization") ?: return null
        if (!authorization.startsWith("Bearer ")) return null
        val failedToken = authorization.removePrefix("Bearer ").takeIf { it.isNotEmpty() } ?: return null

        return synchronized(refreshLock) {
            runBlocking {
                try {
                    val session = storage.getSession()
                    if (!requestSession.matches(session)) return@runBlocking null

                    // Concurrent requests may reuse a rotation only within their original session.
                    val currentToken = session.accessToken
                    if (!currentToken.isNullOrEmpty() && currentToken != failedToken) {
                        return@runBlocking retry(response, currentToken)
                    }

                    val refreshToken = session.refreshToken
                    if (refreshToken.isNullOrEmpty()) {
                        storage.invalidateSession(session) { userCacheCleaner?.clear() }
                        return@runBlocking null
                    }

                    when (val result = refresher.refresh(session.endpoint, session.clientId, refreshToken)) {
                        is RefreshResult.Success -> {
                            val saved = storage.saveRefreshedTokens(
                                expected = session,
                                accessToken = result.tokens.accessToken,
                                refreshToken = result.tokens.refreshToken,
                            )
                            // Both the saved result and a competing rotation must still belong to
                            // this request. A replacement login must never authenticate its retry.
                            val current = storage.getSession()
                            val token = current.accessToken
                            if (requestSession.matches(current) && !token.isNullOrEmpty() && (saved || token != failedToken)) {
                                retry(response, token)
                            } else {
                                null
                            }
                        }
                        RefreshResult.Invalid -> {
                            storage.invalidateSession(session) { userCacheCleaner?.clear() }
                            null
                        }
                        // Transient failure: preserve credentials so a later request can retry.
                        RefreshResult.Transient -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // An unexpected failure (e.g. malformed refresh payload) is not evidence that
                    // stored credentials are invalid; keep them and let a later request retry.
                    Timber.e("Token refresh failed")
                    null
                }
            }
        }
    }

    private fun retry(response: Response, token: String): Request = response.request.newBuilder()
        .header("Authorization", "Bearer $token")
        .build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
