/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build(),
        )
    }

    @Test fun requestSync_enqueues_unique_outbox_sync_work() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SyncScheduler(context).requestSync()
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("outbox-sync").get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test fun follow_up_waits_for_the_requested_delay() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SyncScheduler(context).scheduleFollowUp(delayMs = 30_000L)

        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(30_000L, info.initialDelayMillis)
    }

    @Test fun user_sync_replaces_a_waiting_follow_up_instead_of_queueing_behind_it() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scheduler = SyncScheduler(context)
        // e.g. parked on a rate limit or a long WorkManager backoff
        scheduler.scheduleFollowUp(delayMs = 60 * 60 * 1_000L)

        scheduler.requestSync()

        val waiting = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_NAME).get()
            .filterNot { it.state.isFinished }
        assertEquals("The delayed run is replaced, not waited behind", 1, waiting.size)
        assertEquals(WorkInfo.State.ENQUEUED, waiting.single().state)
        assertEquals(0L, waiting.single().initialDelayMillis)
    }
}
