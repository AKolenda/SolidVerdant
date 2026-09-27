/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.tricked.solidverdant.sync.SyncFollowUpScheduler
import dev.tricked.solidverdant.sync.SyncScheduler
import javax.inject.Singleton

/**
 * Worker-side scheduling seam. Kept out of [RemoteModule] so test graphs that replace the remote
 * wiring still get the production scheduler.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds @Singleton
    abstract fun bindSyncFollowUpScheduler(impl: SyncScheduler): SyncFollowUpScheduler
}
