/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.util.Clock
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = object : Clock {
        override fun nowMs() = System.currentTimeMillis()
    }

    /** Stands in for ReminderWorker so a period can complete without its Hilt dependencies. */
    private class DoneWorker(context: Context, params: WorkerParameters) : androidx.work.Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    @Before fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = DoneWorker(appContext, workerParameters)
                })
                .build(),
        )
    }

    private fun info(): WorkInfo = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(ReminderScheduler.UNIQUE_NAME).get()
        .single { !it.state.isFinished }

    private fun minuteOfDayIn(minutes: Long): Int {
        val target = Instant.ofEpochMilli(clock.nowMs()).plusSeconds(minutes * 60).atZone(ZoneOffset.UTC)
        return target.hour * 60 + target.minute
    }

    @Test fun re_anchoring_after_a_completed_period_uses_the_new_reminder_time() {
        val scheduler = ReminderScheduler(context, mockk<SettingsDataStore>(relaxed = true), clock)
        scheduler.schedule(minuteOfDayIn(180), ZoneOffset.UTC)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        // Let the first period run, as the daily worker does before it re-anchors.
        driver.setInitialDelayMet(info().id)
        driver.setPeriodDelayMet(info().id)
        assertTrue("One period completed", info().runAttemptCount == 0 && info().state == WorkInfo.State.ENQUEUED)

        // The user moves the reminder to about an hour from now.
        val before = clock.nowMs()
        scheduler.schedule(minuteOfDayIn(60), ZoneOffset.UTC)

        val next = info().nextScheduleTimeMillis
        val expectedWindow = (before + TimeUnit.MINUTES.toMillis(58))..(clock.nowMs() + TimeUnit.MINUTES.toMillis(61))
        assertTrue(
            "Next run should follow the new time (~1 h), not the old 24 h period: in ${next - before} ms",
            next in expectedWindow,
        )
        assertEquals(
            1,
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReminderScheduler.UNIQUE_NAME).get().count {
                !it.state.isFinished
            },
        )
    }
}
