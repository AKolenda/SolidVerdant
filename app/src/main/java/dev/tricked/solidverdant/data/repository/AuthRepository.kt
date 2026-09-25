/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.repository

import dev.tricked.solidverdant.data.local.AccountDataOwnerGuard
import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.OrganizationMember
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntriesMeta
import dev.tricked.solidverdant.data.model.TimeEntriesResponse
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.data.model.UpdateTimeEntryRequest
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.data.remote.ApiClientFactory
import dev.tricked.solidverdant.data.remote.SolidtimeTimestamps
import dev.tricked.solidverdant.util.PKCEUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for authentication and API operations
 * Handles OAuth2 flow, token management, and API calls
 */
@Singleton
class AuthRepository @Inject constructor(
    private val authDataStore: AuthDataStore,
    private val apiClientFactory: ApiClientFactory,
    // Optional so API-contract tests can build the repository without the cache stack.
    private val accountDataOwnerGuard: AccountDataOwnerGuard? = null,
) {
    companion object {
        private const val REDIRECT_URI = "solidtime://oauth/callback"
        private const val HTTP_NOT_FOUND = 404
        private const val MAX_CATALOG_PAGES = 10_000

        /** The server's largest time-entry page, and a stop far beyond any one project's history. */
        private const val PROJECT_ENTRY_PAGE_SIZE = 500
        private const val MAX_PROJECT_ENTRY_PAGES = 200
    }

    val isLoggedIn: Flow<Boolean> = authDataStore.isLoggedIn
    val endpoint: Flow<String> = authDataStore.endpoint
    val clientId: Flow<String> = authDataStore.clientId

    suspend fun getCurrentMembershipId(): String? = authDataStore.currentMembershipId.first()

    suspend fun getCurrentMembership(): Membership? {
        val memberships = getMyMemberships().getOrNull() ?: return null
        val selectedId = getCurrentMembershipId()
        return memberships.firstOrNull { it.id == selectedId } ?: memberships.firstOrNull()
    }

    /**
     * Initialize the OAuth2 authorization flow
     * @return Result containing the authorization URL to open in browser
     */
    suspend fun initializeOAuthFlow(): Result<String> = try {
        // Generate PKCE data
        val pkceData = PKCEUtil.generatePKCEData()

        // Store PKCE data for later verification
        authDataStore.savePKCEData(
            codeVerifier = pkceData.codeVerifier,
            state = pkceData.state,
        )

        // Get current config
        val currentEndpoint = authDataStore.getEndpoint()
        val currentClientId = authDataStore.getClientId()

        // Build authorization URL
        val authUrl = PKCEUtil.buildAuthorizationUrl(
            endpoint = currentEndpoint,
            clientId = currentClientId,
            codeChallenge = pkceData.codeChallenge,
            state = pkceData.state,
        )

        Timber.d("OAuth flow initialized")
        Result.success(authUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to initialize OAuth flow")
        Result.failure(e)
    }

    /**
     * Handle OAuth callback and exchange authorization code for tokens
     * @param code The authorization code from the callback
     * @param state The state parameter from the callback
     * @return Result indicating success or failure
     */
    suspend fun handleOAuthCallback(code: String, state: String): Result<Unit> {
        return try {
            // Verify state parameter (CSRF protection)
            val storedState = authDataStore.getState()
            if (state != storedState || state.isEmpty()) {
                Timber.w("Invalid state parameter in OAuth callback")
                return Result.failure(Exception("Invalid state parameter"))
            }

            // Get stored PKCE data
            val codeVerifier = authDataStore.getCodeVerifier()
            if (codeVerifier.isNullOrEmpty()) {
                Timber.w("No code verifier found")
                return Result.failure(Exception("No code verifier found"))
            }

            // Get current config
            val currentEndpoint = authDataStore.getEndpoint()
            val currentClientId = authDataStore.getClientId()

            // Create API instance
            val api = apiClientFactory.createApi(currentEndpoint)

            // Exchange code for tokens
            val tokenResponse = api.exchangeCodeForToken(
                clientId = currentClientId,
                redirectUri = REDIRECT_URI,
                codeVerifier = codeVerifier,
                code = code,
            )

            // Identify the account before the tokens become the active session. Saving them flips
            // the app to signed-in and lets queued sync run, so a different account's cached data
            // and outbox must be cleared first. Best effort: a failed lookup here is repeated by
            // the next successful profile fetch (getCurrentUser) instead of blocking sign-in.
            runCatching {
                api.getCurrentUserWithToken("Bearer ${tokenResponse.accessToken}").data
            }.onSuccess { user ->
                accountDataOwnerGuard?.claim(currentEndpoint, user.id)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.w("Could not identify the signed-in account before saving tokens")
            }

            // Save tokens
            authDataStore.saveTokens(
                accessToken = tokenResponse.accessToken,
                refreshToken = tokenResponse.refreshToken,
            )

            // Clear PKCE data
            authDataStore.clearPKCEData()

            Timber.d("OAuth callback handled successfully")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle OAuth callback")
            authDataStore.clearPKCEData()
            Result.failure(e)
        }
    }

    /**
     * Get the current authenticated user
     */
    suspend fun getCurrentUser(): Result<User> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        val response = api.getCurrentUser()
        // Second line of defence for account isolation (see handleOAuthCallback).
        accountDataOwnerGuard?.claim(endpoint, response.data.id)
        Result.success(response.data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to get current user")
        Result.failure(e)
    }

    /**
     * Get all memberships (organizations) for the current user
     */
    suspend fun getMyMemberships(): Result<List<Membership>> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        val response = api.getMyMemberships()
        val memberships = response.data.map { membership ->
            // Solidtime's personal-memberships resource only embeds id/name/currency. Fetch the
            // authoritative organization resource so policy flags are not silently defaulted.
            membership.copy(organization = api.getOrganization(membership.organizationId).data)
        }
        Result.success(memberships)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to get memberships")
        Result.failure(e)
    }

    /**
     * Get the active time entry for the current user
     */
    suspend fun getActiveTimeEntry(): Result<TimeEntry?> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        val response = api.getActiveTimeEntry()
        Result.success(response.data)
    } catch (e: retrofit2.HttpException) {
        if (e.code() == HTTP_NOT_FOUND) {
            Timber.d("No active time entry")
            Result.success(null)
        } else {
            Timber.e(e, "Failed to get active time entry")
            Result.failure(e)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to get active time entry")
        Result.failure(e)
    }

    /**
     * Save OAuth configuration (endpoint and client ID)
     */
    suspend fun saveOAuthConfig(endpoint: String, clientId: String): Result<Unit> = try {
        authDataStore.saveOAuthConfig(endpoint, clientId)
        Timber.d("OAuth config saved")
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to save OAuth config")
        Result.failure(e)
    }

    /**
     * Save current membership ID
     */
    suspend fun saveCurrentMembershipId(membershipId: String): Result<Unit> = try {
        authDataStore.saveCurrentMembershipId(membershipId)
        Timber.d("Current membership ID saved")
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to save current membership ID")
        Result.failure(e)
    }

    /**
     * Start a new time entry
     */
    @Suppress("UnusedParameter")
    suspend fun startTimeEntry(
        organizationId: String,
        memberId: String,
        userId: String,
        projectId: String? = null,
        taskId: String? = null,
        description: String = "",
        // The actual capture-time start timestamp (ISO-8601). Pass the value captured when tracking
        // really began so an offline-captured start is not stamped with the reconnect/sync time.
        // Null/blank falls back to now() for callers that do not thread a captured value.
        startIso: String? = null,
        // Chosen when the timer started; omitting them made the tags vanish on the next pull.
        tags: List<String> = emptyList(),
        billable: Boolean = false,
    ): Result<TimeEntry> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)

        // Use current time in format: Y-m-d\TH:i:s\Z (e.g., 2025-12-01T21:32:10Z)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        val start = startIso?.takeIf { it.isNotBlank() }?.let(SolidtimeTimestamps::utc)
            ?: java.time.ZonedDateTime.now().withZoneSameInstant(java.time.ZoneOffset.UTC).format(formatter)

        val request = dev.tricked.solidverdant.data.model.StartTimeEntryRequest(
            memberId = memberId,
            start = start,
            description = description,
            projectId = projectId,
            taskId = taskId,
            billable = billable,
            tags = tags,
        )

        val response = api.startTimeEntry(organizationId, request)
        Timber.d("Time entry started")
        Result.success(response.data!!)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to start time entry")
        Result.failure(e)
    }

    /**
     * Create a completed time entry with an explicit start and end (manual entry).
     * Does not affect any currently running entry.
     */
    @Suppress("UnusedParameter")
    suspend fun createTimeEntry(
        organizationId: String,
        memberId: String,
        userId: String,
        start: String,
        end: String,
        description: String = "",
        projectId: String? = null,
        taskId: String? = null,
        tags: List<String> = emptyList(),
        billable: Boolean = false,
        type: TimeEntryType = TimeEntryType.WORK,
    ): Result<TimeEntry> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)

        val request = dev.tricked.solidverdant.data.model.StartTimeEntryRequest(
            memberId = memberId,
            start = SolidtimeTimestamps.utc(start),
            end = SolidtimeTimestamps.utc(end),
            description = description,
            projectId = projectId,
            taskId = taskId,
            billable = billable,
            tags = tags,
            type = type,
        )

        val created = api.startTimeEntry(organizationId, request).data!!

        Timber.d("Manual time entry created")
        Result.success(created)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to create manual time entry")
        Result.failure(e)
    }

    /**
     * Stop the active time entry
     */
    @Suppress("UnusedParameter")
    suspend fun stopTimeEntry(
        organizationId: String,
        timeEntryId: String,
        userId: String,
        startTime: String,
        // The actual capture-time end timestamp (ISO-8601). Pass the value captured when tracking
        // really ended so an offline-captured stop is not stamped with the reconnect/sync time.
        // Null/blank falls back to now() for callers that do not thread a captured value.
        endIso: String? = null,
    ): Result<TimeEntry> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)

        // Use current time in format: Y-m-d\TH:i:s\Z (e.g., 2025-12-01T21:32:10Z)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        val end = endIso?.takeIf { it.isNotBlank() }?.let(SolidtimeTimestamps::utc)
            ?: java.time.ZonedDateTime.now().withZoneSameInstant(java.time.ZoneOffset.UTC).format(formatter)

        // Stopping is a narrow command: only set the end timestamp. Re-sending the cached start
        // could overwrite a correction made by another client while this device was offline.
        val request = dev.tricked.solidverdant.data.model.StopTimeEntryRequest(end = end)

        val response = api.stopTimeEntry(organizationId, timeEntryId, request)
        Timber.d("Time entry stopped")
        Result.success(response.data!!)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to stop time entry")
        Result.failure(e)
    }

    /**
     * Logout and clear all stored data (preserves OAuth config)
     */
    suspend fun logout() {
        authDataStore.clearAll()
        Timber.d("User logged out")
    }

    /**
     * Reset OAuth configuration to defaults
     */
    suspend fun resetOAuthConfig(): Result<Unit> = try {
        authDataStore.resetOAuthConfig()
        Timber.d("OAuth config reset to defaults")
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to reset OAuth config")
        Result.failure(e)
    }

    /**
     * Get time entries for an organization
     */
    suspend fun getTimeEntries(
        organizationId: String,
        memberId: String,
        limit: Int = 50,
        offset: Int = 0,
        onlyFullDates: Boolean = false,
        start: String? = null,
        end: String? = null,
    ): Result<TimeEntriesResponse> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        val response = api.getTimeEntries(
            organizationId,
            memberId,
            onlyFullDates = onlyFullDates,
            limit = limit,
            offset = offset,
            start = start,
            end = end,
        )
        Result.success(response)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to get time entries")
        Result.failure(e)
    }

    /**
     * Every time entry on [projectId], whatever its date, paged until the server has no more. A null
     * [memberId] asks for every member's entries. A failure keeps its HTTP status, so a caller can
     * tell a refused all-members request (403) from an outage; no partial list is returned.
     */
    suspend fun getAllProjectTimeEntries(organizationId: String, projectId: String, memberId: String?): Result<List<TimeEntry>> = try {
        val api = apiClientFactory.createApi(authDataStore.getEndpoint())
        val entries = LinkedHashMap<String, TimeEntry>()
        var offset = 0
        for (page in 0 until MAX_PROJECT_ENTRY_PAGES) {
            val response = api.getProjectTimeEntries(organizationId, projectId, memberId, PROJECT_ENTRY_PAGE_SIZE, offset)
            val added = response.data.count { entries.putIfAbsent(it.id, it) == null }
            offset += response.data.size
            val total = response.meta?.total
            // A short page, the reported total or a page of only repeats ends the list.
            if (response.data.size < PROJECT_ENTRY_PAGE_SIZE || (total != null && offset >= total) || added == 0) break
        }
        Result.success(entries.values.toList())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("Failed to get the project's time entries: %s", e.javaClass.simpleName)
        Result.failure(e)
    }

    /** The organization's members, for naming who logged an entry. */
    suspend fun getMembers(organizationId: String): Result<List<OrganizationMember>> = try {
        val api = apiClientFactory.createApi(authDataStore.getEndpoint())
        Result.success(
            collectAllPages { page ->
                val response = api.getMembers(organizationId, page)
                response.data to response.meta
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("Failed to get members: %s", e.javaClass.simpleName)
        Result.failure(e)
    }

    /**
     * Get all tags for an organization
     */
    suspend fun getTags(organizationId: String): Result<List<Tag>> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        Result.success(
            collectAllPages { page ->
                val response = api.getTags(organizationId, page)
                response.data to response.meta
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to get tags")
        Result.failure(e)
    }

    /**
     * Get all projects for an organization
     */
    suspend fun getProjects(organizationId: String): Result<List<Project>> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        Result.success(
            collectAllPages { page ->
                val response = api.getProjects(organizationId, page)
                response.data to response.meta
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to get projects")
        Result.failure(e)
    }

    suspend fun getClients(organizationId: String): Result<List<Client>> = try {
        val api = apiClientFactory.createApi(authDataStore.getEndpoint())
        Result.success(
            collectAllPages { page ->
                val response = api.getClients(organizationId, page)
                response.data to response.meta
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun createClient(organizationId: String, name: String): Result<Client> = try {
        val safeName = name.trim().takeIf { it.isNotEmpty() } ?: error("Client name cannot be blank")
        val api = apiClientFactory.createApi(authDataStore.getEndpoint())
        Result.success(api.createClient(organizationId, dev.tricked.solidverdant.data.model.CreateClientRequest(safeName)).data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to create client")
        Result.failure(e)
    }

    suspend fun createProject(organizationId: String, name: String, clientId: String? = null): Result<Project> = try {
        val safeName = name.trim().takeIf { it.isNotEmpty() } ?: error("Project name cannot be blank")
        val api = apiClientFactory.createApi(authDataStore.getEndpoint())
        Result.success(
            api.createProject(
                organizationId,
                dev.tricked.solidverdant.data.model.CreateProjectRequest(name = safeName, clientId = clientId),
            ).data,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to create project")
        Result.failure(e)
    }

    suspend fun createTask(organizationId: String, name: String, projectId: String): Result<Task> = try {
        val safeName = name.trim().takeIf { it.isNotEmpty() } ?: error("Task name cannot be blank")
        require(projectId.isNotBlank()) { "Task project cannot be blank" }
        val api = apiClientFactory.createApi(authDataStore.getEndpoint())
        Result.success(api.createTask(organizationId, dev.tricked.solidverdant.data.model.CreateTaskRequest(safeName, projectId)).data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to create task")
        Result.failure(e)
    }

    suspend fun createTag(organizationId: String, name: String): Result<Tag> = try {
        val safeName = name.trim().takeIf { it.isNotEmpty() } ?: error("Tag name cannot be blank")
        val api = apiClientFactory.createApi(authDataStore.getEndpoint())
        Result.success(api.createTag(organizationId, dev.tricked.solidverdant.data.model.CreateTagRequest(safeName)).data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to create tag")
        Result.failure(e)
    }

    /**
     * Get all tasks for an organization
     */
    suspend fun getTasks(organizationId: String): Result<List<Task>> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        Result.success(
            collectAllPages { page ->
                val response = api.getTasks(organizationId, page)
                response.data to response.meta
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to get tasks")
        Result.failure(e)
    }

    /**
     * Update an existing time entry
     */
    suspend fun updateTimeEntry(organizationId: String, timeEntry: TimeEntry, tags: List<String> = emptyList()): Result<TimeEntry> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)

        val request = UpdateTimeEntryRequest(
            userId = timeEntry.userId,
            start = SolidtimeTimestamps.utc(timeEntry.start),
            end = timeEntry.end?.let(SolidtimeTimestamps::utc),
            description = timeEntry.description,
            projectId = timeEntry.projectId,
            taskId = timeEntry.taskId,
            billable = timeEntry.billable,
            tags = tags,
            type = timeEntry.type,
        )

        val response = api.updateTimeEntry(organizationId, timeEntry.id, request)
        Timber.d("Time entry updated")
        Result.success(response.data!!)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to update time entry")
        Result.failure(e)
    }

    /**
     * Delete a time entry
     */
    suspend fun deleteTimeEntry(organizationId: String, timeEntryId: String): Result<Unit> = try {
        val endpoint = authDataStore.getEndpoint()
        val api = apiClientFactory.createApi(endpoint)
        val response = api.deleteTimeEntry(organizationId, timeEntryId)
        if (!response.isSuccessful) throw HttpException(response)
        Timber.d("Time entry deleted")
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to delete time entry")
        Result.failure(e)
    }

    private suspend fun <T> collectAllPages(fetch: suspend (Int) -> Pair<List<T>, TimeEntriesMeta?>): List<T> {
        val collected = mutableListOf<T>()
        var requestedPage = 1
        repeat(MAX_CATALOG_PAGES) {
            val (items, meta) = fetch(requestedPage)
            val currentPage = meta?.currentPage ?: requestedPage
            val lastPage = meta?.lastPage ?: currentPage
            check(currentPage == requestedPage) { "Catalogue pagination returned an unexpected page" }
            check(lastPage >= currentPage) { "Catalogue pagination returned an invalid last page" }
            collected += items
            if (items.isEmpty() || currentPage >= lastPage) return collected
            requestedPage = currentPage + 1
        }
        error("Catalogue pagination exceeded the safety limit")
    }
}
