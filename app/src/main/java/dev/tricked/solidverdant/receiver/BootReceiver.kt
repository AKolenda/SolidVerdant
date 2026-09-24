/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.data.repository.TimerCommands
import dev.tricked.solidverdant.service.TimeTrackingNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives boot completed broadcast and restores notification if tracking was active
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var timerCommands: TimerCommands

    @Inject
    lateinit var timeEntryRepository: TimeEntryRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Timber.d("Boot completed, checking for active tracking")

        // Use goAsync to allow coroutine to complete
        val pendingResult = goAsync()

        scope.launch {
            try {
                // Check if user is logged in
                val isLoggedIn = authRepository.isLoggedIn.first()
                if (!isLoggedIn) {
                    Timber.d("User not logged in, skipping notification restore")
                    return@launch
                }

                val account = timerCommands.currentAccount()
                // The network is often not up yet at boot; Room still knows a running timer. When
                // the server answered, only a timer whose START is still queued adds to it.
                val lookup = authRepository.getActiveTimeEntry()
                    .onFailure { Timber.w(it, "Could not check the active time entry after boot") }
                val localActive = account?.let { timeEntryRepository.localActiveEntry(it.organizationId, it.userId) }
                    ?.takeIf { lookup.isFailure || timeEntryRepository.hasPendingSync(it.id) }
                when (
                    bootSurface(
                        lookup.getOrNull(),
                        localActive,
                        account?.organizationId,
                        settingsDataStore.alwaysShowNotification.first(),
                    )
                ) {
                    BootSurface.RESUME_PROMPT -> {
                        // Android 15+ forbids starting a dataSync foreground service from
                        // BOOT_COMPLETED; the failure only surfaces later, uncaught, when the
                        // service calls startForeground(). Never start the tracking service from
                        // boot: post the plain "tap to resume" notification, and let the app
                        // restore the live tracking notification once it is opened.
                        Timber.d("Active tracking found after boot, showing resume prompt")
                        TimeTrackingNotificationService.showResumePrompt(context)
                    }
                    BootSurface.IDLE -> {
                        Timber.d("No active tracking for current membership, showing idle notification")
                        TimeTrackingNotificationService.showIdle(context)
                    }
                    BootSurface.NONE -> Unit
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in boot receiver")
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal enum class BootSurface { RESUME_PROMPT, IDLE, NONE }

    internal companion object {
        /**
         * What to show after boot: the resume prompt when a timer runs for the current membership
         * (the server's answer, or Room's when the server could not be reached), the idle prompt
         * when the user always wants one, otherwise nothing.
         */
        fun bootSurface(
            serverActive: TimeEntry?,
            localActive: TimeEntry?,
            currentOrganizationId: String?,
            alwaysShowNotification: Boolean,
        ): BootSurface {
            val running = (serverActive ?: localActive)?.takeIf { it.organizationId == currentOrganizationId }
            return when {
                running != null -> BootSurface.RESUME_PROMPT
                alwaysShowNotification -> BootSurface.IDLE
                else -> BootSurface.NONE
            }
        }
    }
}
