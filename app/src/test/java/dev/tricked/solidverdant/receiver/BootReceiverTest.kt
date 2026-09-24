/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.receiver

import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.receiver.BootReceiver.BootSurface
import org.junit.Assert.assertEquals
import org.junit.Test

/** Boot never starts the tracking foreground service (Android 15+ forbids dataSync from boot). */
class BootReceiverTest {
    private val running = TimeEntry(id = "server-1", userId = "u", start = "2026-08-10T08:00:00Z", organizationId = "org")

    @Test fun running_timer_for_the_current_membership_shows_the_resume_prompt() {
        assertEquals(BootSurface.RESUME_PROMPT, BootReceiver.bootSurface(running, null, "org", alwaysShowNotification = false))
    }

    @Test fun offline_boot_uses_rooms_running_timer() {
        assertEquals(BootSurface.RESUME_PROMPT, BootReceiver.bootSurface(null, running, "org", alwaysShowNotification = false))
    }

    @Test fun a_timer_in_another_organization_is_not_resumed() {
        assertEquals(BootSurface.IDLE, BootReceiver.bootSurface(running, null, "other-org", alwaysShowNotification = true))
        assertEquals(BootSurface.NONE, BootReceiver.bootSurface(running, null, "other-org", alwaysShowNotification = false))
    }

    @Test fun no_timer_shows_the_idle_prompt_only_when_requested() {
        assertEquals(BootSurface.IDLE, BootReceiver.bootSurface(null, null, "org", alwaysShowNotification = true))
        assertEquals(BootSurface.NONE, BootReceiver.bootSurface(null, null, "org", alwaysShowNotification = false))
    }
}
