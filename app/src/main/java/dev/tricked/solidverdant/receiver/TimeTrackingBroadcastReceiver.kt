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
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimerCommands
import dev.tricked.solidverdant.service.TimeTrackingNotificationService
import dev.tricked.solidverdant.widget.TimeTrackingWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles the "Stop" action fired from the home-screen widget (and any other surface that needs
 * to stop tracking without a live Activity).
 *
 * This runs the same offline-capable stop path the app uses: an optimistic Room write plus an
 * outbox enqueue, then a sync request. Because it is a manifest-declared, Hilt-injected receiver
 * it works even when the app process was dead — the object graph is built on delivery, so the
 * stop happens reliably rather than merely opening the Activity. Once no timer is left running it
 * tears down the tracking notification and refreshes the widget.
 */
@AndroidEntryPoint
class TimeTrackingBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var timerCommands: TimerCommands

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_TRACKING_FROM_NOTIFICATION) {
            return
        }

        val pendingResult = goAsync()
        scope.launch {
            val nothingRunning = try {
                stopActiveTracking()
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop tracking from widget action")
                false
            }
            try {
                // Only tear the timer surfaces down when no timer is left running. If the running
                // timer could not be determined (offline and unknown to Room), hiding them would
                // leave a server timer running with nothing on screen to stop it.
                if (nothingRunning) {
                    if (settingsDataStore.alwaysShowNotification.first()) {
                        TimeTrackingNotificationService.showIdle(context)
                    } else {
                        TimeTrackingNotificationService.hide(context)
                    }
                    settingsDataStore.setWidgetTrackingState(isTracking = false)
                }
                TimeTrackingWidget.requestUpdate(context)
            } catch (e: Exception) {
                Timber.e(e, "Failed to reconcile UI after stop")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Stop the running timer through Room + the outbox ([TimerCommands]): the one Track shows,
     * even if its START has not synced, or else the server's timer Room did not know about.
     * Returns true when afterwards no timer is running.
     */
    private suspend fun stopActiveTracking(): Boolean {
        if (!authRepository.isLoggedIn.first()) {
            Timber.d("Stop action ignored: not logged in")
            return true
        }
        return when (val result = timerCommands.stop(organizationId = null, expectedStart = null).getOrThrow()) {
            is TimerCommands.StopResult.Stopped, TimerCommands.StopResult.NothingRunning -> true
            TimerCommands.StopResult.NotTheExpectedTimer -> {
                Timber.d("Stop action skipped: %s", result)
                false
            }
        }
    }

    companion object {
        const val ACTION_STOP_TRACKING_FROM_NOTIFICATION =
            "dev.tricked.solidverdant.ACTION_STOP_TRACKING_FROM_NOTIFICATION"
    }
}
