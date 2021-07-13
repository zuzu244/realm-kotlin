package io.realm.internal

import io.realm.BaseRealm
import io.realm.VersionId
import io.realm.interop.NativePointer
import io.realm.interop.RealmInterop

/**
 * A _Realm Reference_ that links a specific Kotlin BaseRealm instance with an underlying C++
 * SharedRealm.
 *
 * This is needed as each Results, List or Object need to know, both which public Realm they belong
 * to, but also what underlying SharedRealm they are part of. Each object linked to a Realm needs
 * to keep it's own C++ SharedInstance, as the owning [Realm]'s C++ SharedRealm instance is updated
 * on writes/notifications.
 *
 * For frozen Realms, the `dbPointer` will point to a specific version of a read transaction that
 * is guaranteed to not change.
 *
 * For live Realms, the `dbPointer` will point to a live SharedRealm that can advance its internal
 * version.
 */
data class RealmReference(
    val owner: BaseRealm,
    val dbPointer: NativePointer
    // FIXME Should we keep a debug flag to assert that we have the right liveness state
) : RealmLifeCycle {

    override fun version(): VersionId {
        checkClosed()
        return VersionId(RealmInterop.realm_get_version_id(dbPointer))
    }

    override fun isFrozen(): Boolean {
        checkClosed()
        return RealmInterop.realm_is_frozen(dbPointer)
    }

    override fun isClosed(): Boolean {
        return RealmInterop.realm_is_closed(dbPointer)
    }

    inline fun checkClosed() {
        if (isClosed()) {
            throw IllegalStateException("Realm has been closed and is no longer accessible: ${owner.configuration.path}")
        }
    }
}
