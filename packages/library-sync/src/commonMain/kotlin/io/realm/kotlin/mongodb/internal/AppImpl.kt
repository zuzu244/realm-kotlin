/*
 * Copyright 2021 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.kotlin.mongodb.internal

import io.realm.kotlin.internal.interop.RealmAppPointer
import io.realm.kotlin.internal.interop.RealmInterop
import io.realm.kotlin.internal.interop.RealmUserPointer
import io.realm.kotlin.internal.interop.sync.NetworkTransport
import io.realm.kotlin.internal.util.DispatcherHolder
import io.realm.kotlin.internal.util.Validation
import io.realm.kotlin.internal.util.use
import io.realm.kotlin.mongodb.App
import io.realm.kotlin.mongodb.AppConfiguration
import io.realm.kotlin.mongodb.AuthenticationChange
import io.realm.kotlin.mongodb.Credentials
import io.realm.kotlin.mongodb.User
import io.realm.kotlin.mongodb.auth.EmailPasswordAuth
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal typealias AppResources = Triple<DispatcherHolder, NetworkTransport, RealmAppPointer>

// TODO Public due to being a transitive dependency to UserImpl
public class AppImpl(
    override val configuration: AppConfigurationImpl,
) : App {

    internal val nativePointer: RealmAppPointer
    internal val appNetworkDispatcher: DispatcherHolder
    private val networkTransport: NetworkTransport

    // Allow some delay between events being reported and them being consumed.
    // When the (somewhat arbitrary) limit is hit, we will throw an exception, since we assume the
    // consumer is doing something wrong. This is also needed because we don't
    // want to block user events like logout, delete and remove.
    @Suppress("MagicNumber")
    private val authenticationChangeFlow = MutableSharedFlow<AuthenticationChange>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    init {
        val appResources: AppResources = configuration.createNativeApp()
        appNetworkDispatcher = appResources.first
        networkTransport = appResources.second
        nativePointer = appResources.third
    }

    override val emailPasswordAuth: EmailPasswordAuth by lazy { EmailPasswordAuthImpl(nativePointer) }

    override val currentUser: User?
        get() = RealmInterop.realm_app_get_current_user(nativePointer)
            ?.let { UserImpl(it, this) }

    override fun allUsers(): Map<String, User> {
        val nativeUsers: List<RealmUserPointer> =
            RealmInterop.realm_app_get_all_users(nativePointer)
        val map = mutableMapOf<String, User>()
        nativeUsers.map { ptr: RealmUserPointer ->
            val user = UserImpl(ptr, this)
            map[user.identity] = user
        }
        return map
    }

    override suspend fun login(credentials: Credentials): User {
        // suspendCoroutine doesn't allow freezing callback capturing continuation
        // ... and cannot be resumed on another thread (we probably also want to guarantee that we
        // are resuming on the same dispatcher), so run our own implementation using a channel
        Channel<Result<User>>(1).use { channel ->
            RealmInterop.realm_app_log_in_with_credentials(
                nativePointer,
                when (credentials) {
                    is CredentialsImpl -> credentials.nativePointer
                    is CustomEJsonCredentialsImpl -> credentials.nativePointer(this)
                    else -> throw IllegalArgumentException("Argument 'credentials' is of an invalid type ${credentials::class.simpleName}")
                },
                channelResultCallback<RealmUserPointer, User>(channel) { userPointer ->
                    UserImpl(userPointer, this)
                }
            )
            return channel.receive()
                .getOrThrow().also { user: User ->
                    reportAuthenticationChange(user, User.State.LOGGED_IN)
                }
        }
    }

    internal fun reportAuthenticationChange(user: User, change: User.State) {
        val event: AuthenticationChange = when (change) {
            User.State.LOGGED_OUT -> LoggedOutImpl(user)
            User.State.LOGGED_IN -> LoggedInImpl(user)
            User.State.REMOVED -> RemovedImpl(user)
        }
        if (!authenticationChangeFlow.tryEmit(event)) {
            throw IllegalStateException(
                "It wasn't possible to emit authentication changes " +
                    "because a consuming flow was blocked. Increase dispatcher processing resources " +
                    "or buffer `App.authenticationChangeAsFlow()` with buffer(...)."
            )
        }
    }

    override fun authenticationChangeAsFlow(): Flow<AuthenticationChange> {
        return authenticationChangeFlow
    }

    override fun close() {
        // The native App instance is what keeps the underlying SyncClient thread alive. So closing
        // it will close the Sync thread and close any network dispatchers.
        //
        // This is not required as the pointers will otherwise be released by the GC, but it can
        // be beneficial in order to reason about the lifecycle of the Sync thread and dispatchers.
        networkTransport.close()
        nativePointer.release()
    }

    internal companion object {
        // This method is used to inject bundleId to the sync configuration. The
        // SyncLoweringExtension is replacing calls to App.create(appId) with calls to this method.
        internal fun create(appId: String, bundleId: String): App {
            Validation.checkEmpty(appId, "appId")
            return App.create(AppConfiguration.Builder(appId).build(bundleId))
        }
    }
}
