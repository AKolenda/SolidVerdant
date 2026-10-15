/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Real file-backed preferences and encryption, with a controllable durable-write return boundary. */
internal class AuthDataStoreFixture : ExternalResource() {
    private val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    lateinit var preferences: PausingPreferencesStore
        private set
    lateinit var store: AuthDataStore
        private set
    val cipher = newCipher()

    override fun before() {
        folder.create()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PausingPreferencesStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "auth.preferences_pb") },
        )
        store = AuthDataStore(preferences, cipher)
    }

    override fun after() {
        runBlocking {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
        folder.delete()
    }

    companion object {
        fun newCipher(): AuthSecretCipher {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            return AuthSecretCipher(object : SecretKeyProvider {
                override fun getOrCreate(): SecretKey = key
            })
        }
    }
}

internal class PausingPreferencesStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
    override val data = delegate.data

    class Pause {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    @Volatile var nextWritePause: Pause? = null

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val committed = delegate.updateData(transform)
        nextWritePause?.let { pause ->
            nextWritePause = null
            pause.committed.complete(Unit)
            pause.release.await()
        }
        return committed
    }
}
