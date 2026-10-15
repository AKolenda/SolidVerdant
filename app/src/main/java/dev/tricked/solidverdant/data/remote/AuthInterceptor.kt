/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.remote

import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret ownership carried across OkHttp redirects and authentication retries. */
internal data class RequestAuthSession(val id: String, val endpoint: String, val clientId: String) {
    fun matches(session: AuthSession): Boolean = id == session.id && endpoint == session.endpoint && clientId == session.clientId
}

internal fun HttpUrl.hasAuthOrigin(endpoint: String): Boolean {
    val origin = endpoint.toHttpUrlOrNull() ?: return false
    return scheme == origin.scheme && host == origin.host && port == origin.port
}

/**
 * OkHttp Interceptor that adds Bearer token authorization to API requests
 *
 * Note: Uses runBlocking as a bridge between suspend functions and synchronous
 * OkHttp interceptor. This is a known pattern in Android networking.
 */
@Singleton
class AuthInterceptor @Inject constructor(private val authDataStore: AuthDataStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request().newBuilder()
            .removeHeader("Authorization")
            .tag(RequestAuthSession::class.java, null)
            .build()

        // Skip adding auth header for OAuth token requests
        if (originalRequest.url.encodedPath.contains("/oauth/token")) {
            return chain.proceed(originalRequest)
        }

        // Read credentials and their owner together so an account change cannot pair one server's
        // endpoint with another server's token. Unreadable credentials remain unauthenticated.
        val session = runBlocking {
            try {
                authDataStore.getSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Failed to read authentication session; proceeding without authorization")
                null
            }
        }

        val accessToken = session?.accessToken
        val sessionId = session?.id
        val newRequest = if (session != null &&
            sessionId != null &&
            !accessToken.isNullOrEmpty() &&
            originalRequest.url.hasAuthOrigin(session.endpoint)
        ) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .tag(RequestAuthSession::class.java, RequestAuthSession(sessionId, session.endpoint, session.clientId))
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(newRequest)
    }
}
