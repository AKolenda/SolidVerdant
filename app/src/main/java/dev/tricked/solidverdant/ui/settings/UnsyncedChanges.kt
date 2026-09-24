/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Changes on this device the server has not received, across every organization of the account:
 * queued, retrying and failed outbox operations, plus entries held in an unresolved sync conflict
 * (whose device copy lives only on the Room row). Logging out or clearing the cache deletes all of
 * them, so both ask with this number first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeUnsyncedChanges(repository: TimeEntryRepository, settingsDataStore: SettingsDataStore): Flow<Int> {
    val conflicts = settingsDataStore.observeCachedAuth()
        .map { auth -> auth?.memberships?.map { it.organizationId }?.distinct().orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { organizationIds ->
            if (organizationIds.isEmpty()) {
                flowOf(0)
            } else {
                combine(organizationIds.map { id -> repository.observeConflicts(id).map { it.size } }) { it.sum() }
            }
        }
    return combine(repository.observeOutboxCount(), conflicts) { queued, conflicted -> queued + conflicted }
        .distinctUntilChanged()
}

/** Supplies the Settings logout confirmation with the number of changes a logout would delete. */
@HiltViewModel
class UnsyncedChangesViewModel @Inject constructor(repository: TimeEntryRepository, settingsDataStore: SettingsDataStore) :
    ViewModel() {
    val unsyncedChanges: StateFlow<Int> = observeUnsyncedChanges(repository, settingsDataStore)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
