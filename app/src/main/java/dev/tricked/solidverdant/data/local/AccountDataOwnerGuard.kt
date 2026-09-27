/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tricked.solidverdant.service.TimeTrackingNotificationService
import dev.tricked.solidverdant.sync.SyncScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps cached account data (Room, outbox, first-frame caches) isolated per account now that an
 * expired or revoked session no longer wipes it.
 *
 * The owner is recorded the first time an authenticated user is identified. When a later sign-in
 * identifies a *different* account, the previous account's cache and its unsyncable outbox are
 * cleared before the new session can read or upload them. The same account signing in again keeps
 * everything, so work queued before the session expired still syncs.
 */
@Singleton
class AccountDataOwnerGuard internal constructor(
    private val settingsDataStore: SettingsDataStore,
    private val wipeAccountData: suspend () -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsDataStore: SettingsDataStore,
        userCacheCleaner: UserCacheCleaner,
        syncScheduler: SyncScheduler,
    ) : this(
        settingsDataStore,
        {
            // Mirror explicit logout: no queued sync may upload the outgoing account's rows with
            // the incoming account's credentials, and no timer surface may keep showing them.
            syncScheduler.cancelSync()
            TimeTrackingNotificationService.clearForLogout(context)
            userCacheCleaner.clear()
        },
    )

    private val mutex = Mutex()

    /**
     * Record [userId] on [endpoint] as the owner of the cached data, first wiping data owned by a
     * different account. Returns true when a wipe happened.
     */
    suspend fun claim(endpoint: String, userId: String): Boolean = mutex.withLock {
        val current = SettingsDataStore.DataOwner(normalize(endpoint), userId)
        // Installs that predate owner tracking still know the cached profile of the last user.
        val previous = settingsDataStore.getDataOwner()
            ?: settingsDataStore.getCachedAuth()?.user?.id?.let { SettingsDataStore.DataOwner(endpoint = null, userId = it) }
        val switched = previous != null && !previous.isSameAccountAs(current)
        if (switched) {
            Timber.i("A different account signed in; clearing the previous account's cached data")
            wipeAccountData()
        }
        settingsDataStore.setDataOwner(current)
        switched
    }

    private fun SettingsDataStore.DataOwner.isSameAccountAs(other: SettingsDataStore.DataOwner): Boolean =
        userId == other.userId && (endpoint == null || other.endpoint == null || endpoint == other.endpoint)

    private fun normalize(endpoint: String): String = endpoint.trim().removeSuffix("/")
}
