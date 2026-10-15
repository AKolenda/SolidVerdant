/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Successive Hilt test components must share the same DataStore delegate for this file.
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_prefs")

/** Encrypted credentials and OAuth transactions, serialized with account cleanup. */
@Singleton
class AuthDataStore internal constructor(private val dataStore: DataStore<Preferences>, private val secretCipher: AuthSecretCipher) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.authDataStore, AuthSecretCipher())

    private object PreferencesKeys {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val SESSION_ID = stringPreferencesKey("session_id")
        val ENDPOINT = stringPreferencesKey("instance_endpoint")
        val CLIENT_ID = stringPreferencesKey("instance_client_id")
        val CODE_VERIFIER = stringPreferencesKey("code_verifier")
        val STATE = stringPreferencesKey("oauth_state")
        val OAUTH_ENDPOINT = stringPreferencesKey("oauth_endpoint")
        val OAUTH_CLIENT_ID = stringPreferencesKey("oauth_client_id")
        val CURRENT_MEMBERSHIP_ID = stringPreferencesKey("current_membership_id")
    }

    // Cache reads take this mutex too, so none observe credentials halfway through cleanup.
    // Publication happens after the durable edit, while still holding the same mutex.
    private val transitionMutex = Mutex()
    private var migrationComplete = false
    private var cachedSession: AuthSession? = null

    private val secretKeys = listOf(
        PreferencesKeys.ACCESS_TOKEN,
        PreferencesKeys.REFRESH_TOKEN,
        PreferencesKeys.CODE_VERIFIER,
        PreferencesKeys.STATE,
    )

    private fun secretFlow(key: Preferences.Key<String>): Flow<String?> = dataStore.data
        .onStart { transition { } }
        .map { preferences -> preferences[key]?.let(secretCipher::decryptOrNull) }

    /** Finish an acquired transition even if navigation cancels its caller during cleanup. */
    private suspend fun <T> transition(block: suspend () -> T): T = transitionMutex.withLock {
        val result = withContext(NonCancellable) {
            migrateSecretsLocked()
            block()
        }
        currentCoroutineContext().ensureActive()
        result
    }

    private suspend fun migrateSecretsLocked() {
        if (migrationComplete) return
        editLocked { preferences ->
            secretKeys.forEach { key ->
                val storedValue = preferences[key] ?: return@forEach
                when (val sanitized = sanitizeSecret(storedValue)) {
                    null -> preferences.remove(key)
                    else -> preferences[key] = sanitized
                }
            }
            if (preferences[PreferencesKeys.ACCESS_TOKEN].isNullOrEmpty()) {
                preferences.remove(PreferencesKeys.SESSION_ID)
                preferences.remove(PreferencesKeys.REFRESH_TOKEN)
            } else if (preferences[PreferencesKeys.SESSION_ID] == null) {
                // Existing signed-in installs get one identity persisted across restarts.
                preferences[PreferencesKeys.SESSION_ID] = UUID.randomUUID().toString()
            }
            // Pre-upgrade pending flows have no recorded issuer and must be started again.
            if (pendingTransaction(preferences) == null) clearPendingTransaction(preferences)
        }
        migrationComplete = true
    }

    private fun sanitizeSecret(storedValue: String): String? = if (secretCipher.isEncrypted(storedValue)) {
        if (secretCipher.decryptOrNull(storedValue) != null) {
            storedValue
        } else {
            Timber.w("Discarding an undecryptable authentication secret; treating session as logged out")
            null
        }
    } else {
        try {
            secretCipher.encrypt(storedValue)
        } catch (e: Exception) {
            Timber.w("Discarding an authentication secret that could not be encrypted")
            null
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://app.solidtime.io"
        const val DEFAULT_CLIENT_ID = "9c994748-c593-4a6d-951b-6849c829bc4e"
    }

    val accessToken: Flow<String?> = secretFlow(PreferencesKeys.ACCESS_TOKEN)
    val refreshToken: Flow<String?> = secretFlow(PreferencesKeys.REFRESH_TOKEN)

    // Keep the decrypt-validating first emission used by the splash screen and cached first frame.
    val isLoggedIn: Flow<Boolean> = accessToken.map { !it.isNullOrEmpty() }

    val endpoint: Flow<String> = dataStore.data.map { it[PreferencesKeys.ENDPOINT] ?: DEFAULT_ENDPOINT }
    val clientId: Flow<String> = dataStore.data.map { it[PreferencesKeys.CLIENT_ID] ?: DEFAULT_CLIENT_ID }
    val codeVerifier: Flow<String?> = secretFlow(PreferencesKeys.CODE_VERIFIER)
    val state: Flow<String?> = secretFlow(PreferencesKeys.STATE)
    val currentMembershipId: Flow<String?> = dataStore.data.map { it[PreferencesKeys.CURRENT_MEMBERSHIP_ID] }

    /** A new login always creates a new identity, even when the server reuses token strings. */
    suspend fun saveTokens(accessToken: String, refreshToken: String) = transition {
        editLocked { preferences ->
            putTokens(preferences, accessToken, refreshToken)
            preferences[PreferencesKeys.SESSION_ID] = UUID.randomUUID().toString()
            clearPendingTransaction(preferences)
        }
    }

    suspend fun saveRefreshedTokens(expected: AuthSession, accessToken: String, refreshToken: String): Boolean = transition {
        if (expected.id == null || sessionLocked() != expected) return@transition false
        editLocked { putTokens(it, accessToken, refreshToken) }
        true
    }

    /**
     * Check ownership, clear account data, and invalidate credentials as one transition.
     * [clearAccountData] must not call back into AuthDataStore. New logins wait until it finishes.
     */
    suspend fun invalidateSession(expected: AuthSession, clearAccountData: suspend () -> Unit): Boolean = transition {
        if (expected.id == null || sessionLocked() != expected) return@transition false
        clearAccountData()
        clearAllLocked()
        true
    }

    suspend fun clearTokens() = transition {
        editLocked { preferences ->
            clearCredentials(preferences)
            clearPendingTransaction(preferences)
        }
    }

    suspend fun saveOAuthConfig(endpoint: String, clientId: String) = transition {
        editLocked { preferences ->
            val normalizedEndpoint = endpoint.removeSuffix("/")
            if (normalizedEndpoint != (preferences[PreferencesKeys.ENDPOINT] ?: DEFAULT_ENDPOINT) ||
                clientId != (preferences[PreferencesKeys.CLIENT_ID] ?: DEFAULT_CLIENT_ID)
            ) {
                clearCredentials(preferences)
            }
            preferences[PreferencesKeys.ENDPOINT] = normalizedEndpoint
            preferences[PreferencesKeys.CLIENT_ID] = clientId
            clearPendingTransaction(preferences)
        }
    }

    /** Capture PKCE and its issuer/client in the same persistent edit. */
    suspend fun savePKCEData(codeVerifier: String, state: String): OAuthTransaction = transition {
        var transaction: OAuthTransaction? = null
        editLocked { preferences ->
            val endpoint = preferences[PreferencesKeys.ENDPOINT] ?: DEFAULT_ENDPOINT
            val clientId = preferences[PreferencesKeys.CLIENT_ID] ?: DEFAULT_CLIENT_ID
            preferences[PreferencesKeys.CODE_VERIFIER] = secretCipher.encrypt(codeVerifier)
            preferences[PreferencesKeys.STATE] = secretCipher.encrypt(state)
            preferences[PreferencesKeys.OAUTH_ENDPOINT] = endpoint
            preferences[PreferencesKeys.OAUTH_CLIENT_ID] = clientId
            transaction = OAuthTransaction(state, codeVerifier, endpoint, clientId)
        }
        requireNotNull(transaction)
    }

    suspend fun getOAuthTransaction(state: String): OAuthTransaction? = transition {
        pendingTransaction(dataStore.data.first())?.takeIf { state.isNotEmpty() && it.state == state }
    }

    /** A late callback may neither install tokens nor consume a newer authorization flow. */
    suspend fun completeOAuthLogin(expected: OAuthTransaction, accessToken: String, refreshToken: String): Boolean = transition {
        if (pendingTransaction(dataStore.data.first()) != expected) return@transition false
        editLocked { preferences ->
            putTokens(preferences, accessToken, refreshToken)
            preferences[PreferencesKeys.SESSION_ID] = UUID.randomUUID().toString()
            clearPendingTransaction(preferences)
        }
        true
    }

    suspend fun clearPKCEData(expected: OAuthTransaction) = transition {
        if (pendingTransaction(dataStore.data.first()) == expected) editLocked(::clearPendingTransaction)
    }

    suspend fun saveCurrentMembershipId(membershipId: String) = transition {
        editLocked { it[PreferencesKeys.CURRENT_MEMBERSHIP_ID] = membershipId }
    }

    /** Preserve configuration and serialize all logout cleanup against the next login. */
    suspend fun clearAll(clearAccountData: suspend () -> Unit = {}) = transition {
        clearAccountData()
        clearAllLocked()
    }

    suspend fun resetOAuthConfig() = saveOAuthConfig(DEFAULT_ENDPOINT, DEFAULT_CLIENT_ID)

    /** Cached after the first read, without per-request disk reads or Keystore decrypt calls. */
    suspend fun getSession(): AuthSession = transition { sessionLocked() }
    suspend fun getAccessToken(): String? = getSession().accessToken
    suspend fun getRefreshToken(): String? = getSession().refreshToken
    suspend fun getEndpoint(): String = getSession().endpoint
    suspend fun getClientId(): String = getSession().clientId
    suspend fun getCodeVerifier(): String? = codeVerifier.first()
    suspend fun getState(): String? = state.first()

    private suspend fun sessionLocked(): AuthSession = cachedSession ?: readSession(dataStore.data.first()).also { cachedSession = it }

    private suspend fun editLocked(block: (MutablePreferences) -> Unit) {
        val committed = dataStore.edit { block(it) }
        cachedSession = readSession(committed)
    }

    private fun readSession(preferences: Preferences): AuthSession {
        val accessToken = preferences[PreferencesKeys.ACCESS_TOKEN]?.let(secretCipher::decryptOrNull)
        return AuthSession(
            id = preferences[PreferencesKeys.SESSION_ID]?.takeIf { !accessToken.isNullOrEmpty() },
            endpoint = preferences[PreferencesKeys.ENDPOINT] ?: DEFAULT_ENDPOINT,
            clientId = preferences[PreferencesKeys.CLIENT_ID] ?: DEFAULT_CLIENT_ID,
            accessToken = accessToken,
            refreshToken = preferences[PreferencesKeys.REFRESH_TOKEN]?.let(secretCipher::decryptOrNull),
        )
    }

    private fun pendingTransaction(preferences: Preferences): OAuthTransaction? {
        val state = preferences[PreferencesKeys.STATE]?.let(secretCipher::decryptOrNull)?.takeIf { it.isNotEmpty() } ?: return null
        val verifier =
            preferences[PreferencesKeys.CODE_VERIFIER]?.let(secretCipher::decryptOrNull)?.takeIf { it.isNotEmpty() } ?: return null
        val endpoint = preferences[PreferencesKeys.OAUTH_ENDPOINT] ?: return null
        val clientId = preferences[PreferencesKeys.OAUTH_CLIENT_ID] ?: return null
        if (endpoint != (preferences[PreferencesKeys.ENDPOINT] ?: DEFAULT_ENDPOINT) ||
            clientId != (preferences[PreferencesKeys.CLIENT_ID] ?: DEFAULT_CLIENT_ID)
        ) {
            return null
        }
        return OAuthTransaction(state, verifier, endpoint, clientId)
    }

    private fun putTokens(preferences: MutablePreferences, accessToken: String, refreshToken: String) {
        preferences[PreferencesKeys.ACCESS_TOKEN] = secretCipher.encrypt(accessToken)
        preferences[PreferencesKeys.REFRESH_TOKEN] = secretCipher.encrypt(refreshToken)
    }

    private fun clearCredentials(preferences: MutablePreferences) {
        preferences.remove(PreferencesKeys.ACCESS_TOKEN)
        preferences.remove(PreferencesKeys.REFRESH_TOKEN)
        preferences.remove(PreferencesKeys.SESSION_ID)
        preferences.remove(PreferencesKeys.CURRENT_MEMBERSHIP_ID)
    }

    private fun clearPendingTransaction(preferences: MutablePreferences) {
        preferences.remove(PreferencesKeys.CODE_VERIFIER)
        preferences.remove(PreferencesKeys.STATE)
        preferences.remove(PreferencesKeys.OAUTH_ENDPOINT)
        preferences.remove(PreferencesKeys.OAUTH_CLIENT_ID)
    }

    private suspend fun clearAllLocked() {
        editLocked { preferences ->
            val endpoint = preferences[PreferencesKeys.ENDPOINT]
            val clientId = preferences[PreferencesKeys.CLIENT_ID]
            preferences.clear()
            endpoint?.let { preferences[PreferencesKeys.ENDPOINT] = it }
            clientId?.let { preferences[PreferencesKeys.CLIENT_ID] = it }
        }
    }
}
