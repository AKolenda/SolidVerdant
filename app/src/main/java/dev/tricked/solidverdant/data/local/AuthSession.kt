/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.local

/** An atomic credentials/configuration snapshot. Refresh keeps [id]; a new login replaces it. */
data class AuthSession(val id: String?, val endpoint: String, val clientId: String, val accessToken: String?, val refreshToken: String?) {
    fun sameSession(other: AuthSession): Boolean = id != null && id == other.id && endpoint == other.endpoint && clientId == other.clientId
}

/** The server and client that issued the authorization request travel with its PKCE secrets. */
data class OAuthTransaction(val state: String, val codeVerifier: String, val endpoint: String, val clientId: String)
