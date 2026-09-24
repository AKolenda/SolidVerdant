/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Small seam so ViewModels can request a sync without depending on WorkManager directly. */
fun interface SyncTrigger {
    fun requestSync()
}

/** Lets the worker ask for its own next run at a specific time (e.g. after a server Retry-After). */
fun interface SyncFollowUpScheduler {
    fun scheduleFollowUp(delayMs: Long)
}

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    SyncTrigger,
    SyncFollowUpScheduler {
    /**
     * Run the outbox now. Every caller is a user action (an edit, Sync now, Retry) or the app
     * returning to the foreground, so it must not queue behind a worker that is merely waiting:
     * a backed-off retry (WorkManager doubles the delay up to 5 h) or a follow-up parked on a
     * rate limit is replaced and runs as soon as the network allows. A drain that is actually in
     * progress is never cancelled mid-request; a follow-up run is appended so changes made while
     * it runs are flushed right after it.
     */
    override fun requestSync() {
        val policy = if (SyncWorker.isDraining()) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, policy, request(delayMs = 0))
    }

    /** Appended after the running worker, so it starts [delayMs] after that worker finishes. */
    override fun scheduleFollowUp(delayMs: Long) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delayMs))
    }

    /** Cancels any queued or in-flight sync work, e.g. before clearing the account cache on logout. */
    fun cancelSync() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private fun request(delayMs: Long): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
        .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
        .build()

    companion object {
        const val UNIQUE_NAME = "outbox-sync"
    }
}
