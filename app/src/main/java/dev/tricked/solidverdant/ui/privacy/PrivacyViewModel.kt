/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.privacy

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tricked.solidverdant.data.export.DiagnosticExporter
import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.local.UserCacheCleaner
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.sync.SyncTrigger
import dev.tricked.solidverdant.ui.settings.observeUnsyncedChanges
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Backs [PrivacyScreen] (roadmap #48). This is a *surfacing* view model: it explains what is stored
 * and reuses the existing safe primitives for the destructive actions rather than reimplementing
 * them — [UserCacheCleaner.clear] for the re-syncable cache wipe, [DiagnosticExporter.export] for the
 * privacy-reviewed bundle (#49), and the host's existing logout path (routed via a callback in the
 * screen) for full session revocation.
 *
 * The cache wipe also deletes the upload queue and conflict copies, so it is refused while any
 * change is still waiting to reach the server; the screen offers Sync now instead.
 *
 * Storage usage is approximate and cheap: it sums the Room DB file(s) and the cache directory off
 * the main thread. It is refreshed after a cache clear so the numbers reflect the wipe.
 */
@HiltViewModel
class PrivacyViewModel internal constructor(
    context: Context,
    private val readEndpoint: suspend () -> String,
    private val readSessionPresent: suspend () -> Boolean,
    private val clearUserCache: suspend () -> Unit,
    private val exportDiagnosticBundle: suspend () -> Uri,
    private val buildShareIntent: (Uri) -> Intent,
    private val storageDispatcher: CoroutineDispatcher,
    private val unsyncedChanges: Flow<Int> = flowOf(0),
    private val requestSync: () -> Unit = {},
) : ViewModel() {

    private val applicationContext = context.applicationContext

    /** Production wiring; the internal constructor keeps unit tests off process-wide IO/DataStore. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsDataStore: SettingsDataStore,
        authDataStore: AuthDataStore,
        userCacheCleaner: UserCacheCleaner,
        diagnosticExporter: DiagnosticExporter,
        timeEntryRepository: TimeEntryRepository,
        syncTrigger: SyncTrigger,
    ) : this(
        context = context,
        readEndpoint = { authDataStore.endpoint.first() },
        readSessionPresent = { !authDataStore.accessToken.first().isNullOrEmpty() },
        clearUserCache = { clearKeepingSignIn(settingsDataStore, userCacheCleaner, timeEntryRepository) },
        exportDiagnosticBundle = { diagnosticExporter.export() },
        buildShareIntent = diagnosticExporter::shareIntent,
        storageDispatcher = Dispatchers.IO,
        unsyncedChanges = observeUnsyncedChanges(timeEntryRepository, settingsDataStore),
        requestSync = syncTrigger::requestSync,
    )

    /** How the last "clear cached data" ended, for a one-shot message on the screen. */
    enum class ClearOutcome { CLEARED, BLOCKED, FAILED }

    @Stable
    data class State(
        val serverHost: String = "",
        val sessionPresent: Boolean = false,
        val dbBytes: Long = 0L,
        val cacheBytes: Long = 0L,
        val computingStorage: Boolean = true,
        val clearingCache: Boolean = false,
        val exporting: Boolean = false,
        /** Changes the server has not received; clearing the cache or logging out would delete them. */
        val unsyncedChanges: Int = 0,
        val clearOutcome: ClearOutcome? = null,
    ) {
        val totalBytes: Long get() = dbBytes + cacheBytes
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val endpoint = readEndpoint()
            _state.update { it.copy(serverHost = hostOf(endpoint)) }
        }
        viewModelScope.launch {
            val present = readSessionPresent()
            _state.update { it.copy(sessionPresent = present) }
        }
        viewModelScope.launch {
            unsyncedChanges.collect { count -> _state.update { it.copy(unsyncedChanges = count) } }
        }
        refreshStorage()
    }

    /** Recompute the Room DB + cache directory sizes off the main thread. */
    fun refreshStorage() {
        viewModelScope.launch {
            refreshStorageNow()
        }
    }

    private suspend fun refreshStorageNow() {
        _state.update { it.copy(computingStorage = true) }
        val (dbBytes, cacheBytes) = withContext(storageDispatcher) {
            databaseBytes() to directoryBytes(applicationContext.cacheDir)
        }
        _state.update { it.copy(dbBytes = dbBytes, cacheBytes = cacheBytes, computingStorage = false) }
    }

    /**
     * Clears the re-syncable account cache via the existing [UserCacheCleaner] (preserves templates
     * per SV-011, keeps the user logged in), asks for a sync, then re-reads storage. Does NOT touch
     * auth. The wipe includes the upload queue, so it is refused — and reported as
     * [ClearOutcome.BLOCKED] — while any change is still waiting to reach the server; the count is
     * read again here because it can grow while the confirmation is open.
     */
    fun clearCache() {
        if (_state.value.clearingCache) return
        viewModelScope.launch {
            val waiting = unsyncedChanges.first()
            if (waiting > 0) {
                _state.update { it.copy(unsyncedChanges = waiting, clearOutcome = ClearOutcome.BLOCKED) }
                return@launch
            }
            _state.update { it.copy(clearingCache = true) }
            val cleared = runCatching { clearUserCache() }
                .onFailure { Timber.e(it, "Failed to clear cached data") }
                .isSuccess
            if (cleared) requestSync()
            refreshStorageNow()
            _state.update {
                it.copy(clearingCache = false, clearOutcome = if (cleared) ClearOutcome.CLEARED else ClearOutcome.FAILED)
            }
        }
    }

    /** Send the waiting changes now, so the cache can be cleared once they are on the server. */
    fun syncNow() = requestSync()

    fun consumeClearOutcome() {
        _state.update { it.copy(clearOutcome = null) }
    }

    /**
     * Builds the diagnostic bundle (#49) and hands the shareable [Uri] back to the screen, which
     * launches the system share sheet. The bundle contains no tokens or work content.
     */
    fun exportDiagnostics(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            runCatching { exportDiagnosticBundle() }
                .onSuccess { onReady(it) }
                .onFailure { Timber.e(it, "Failed to export diagnostics") }
            _state.update { it.copy(exporting = false) }
        }
    }

    /** Share-sheet intent for a diagnostic bundle [uri], delegating to the exporter (#49). */
    fun shareIntentFor(uri: Uri): Intent = buildShareIntent(uri)

    /** Sum of the main Room DB file plus its `-wal`/`-shm`/`-journal` sidecars, if present. */
    private fun databaseBytes(): Long {
        val main = applicationContext.getDatabasePath(DB_NAME)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm"), File(main.path + "-journal"))
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    /** Shallow-recursive sum of file lengths under [dir]; symlinks are followed by File.length only. */
    private fun directoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun hostOf(endpoint: String): String {
        val host = runCatching { endpoint.toUri().host }.getOrNull()
        if (!host.isNullOrBlank()) return host
        return endpoint.substringAfter("://").substringBefore("/").ifBlank { endpoint }
    }

    /**
     * Cancels [viewModelScope] for unit tests that install a test Main dispatcher; mirrors the sync
     * VM's teardown so no Main-bound continuation straggles past `Dispatchers.resetMain()`.
     */
    @VisibleForTesting
    internal fun cancelScopeForTest() {
        viewModelScope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        /** Must match the name used in [dev.tricked.solidverdant.di.DatabaseModule]. */
        const val DB_NAME = "solidverdant.db"
    }
}

/**
 * The cache wipe without signing the user out. [UserCacheCleaner] also drops the cached account
 * snapshot that Review, Sync & recovery and the widgets read the account from, so it is put back;
 * then the current organization is downloaded again so Track and Calendar are not left empty.
 */
private suspend fun clearKeepingSignIn(settings: SettingsDataStore, cleaner: UserCacheCleaner, repository: TimeEntryRepository) {
    val account = settings.getCachedAuth()
    cleaner.clear()
    account ?: return
    settings.cacheAuth(account.user, account.memberships, account.currentMembershipId)
    val membership = account.memberships.firstOrNull { it.id == account.currentMembershipId }
        ?: account.memberships.firstOrNull()
        ?: return
    repository.refreshAll(membership.organizationId, membership.id)
        .onFailure { Timber.w(it, "Re-download after clearing the cache failed; it will load on the next refresh") }
}
