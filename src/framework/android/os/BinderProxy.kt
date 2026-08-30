package android.os

import android.util.Log

/**
 * Remote IBinder backed by the hosted Binder broker (the counterpart of AOSP's
 * BinderProxy). One instance exists per global object id and runtime, so
 * identity is stable for repeated receipt of the same object.
 */
class BinderProxy internal constructor(
    internal val runtime: HostedBinderRuntime,
    val globalId: Long,
    internal val state: HostedBinderRuntime.ProxyState,
) : IBinder {
    private val recipients = ArrayList<IBinder.DeathRecipient>()
    @Volatile private var descriptor: String? = null

    @Throws(RemoteException::class)
    override fun getInterfaceDescriptor(): String? {
        descriptor?.let { return it }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0)
            val result = reply.readString()
            descriptor = result
            return result
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun pingBinder(): Boolean {
        if (!isBinderAlive) return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            transact(IBinder.PING_TRANSACTION, data, reply, 0)
        } catch (e: RemoteException) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun isBinderAlive(): Boolean = !state.dead && !runtime.isDisconnected()

    override fun queryLocalInterface(descriptor: String?): IInterface? = null

    @Throws(RemoteException::class)
    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        runtime.transact(this, code, data, reply, flags)

    @Throws(RemoteException::class)
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        val first: Boolean
        synchronized(recipients) {
            if (state.dead || runtime.isDisconnected()) throw DeadObjectException()
            first = recipients.isEmpty()
            recipients.add(recipient)
        }
        if (first) runtime.linkDeath(globalId)
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
        val removed: Boolean
        val last: Boolean
        synchronized(recipients) {
            removed = recipients.remove(recipient)
            last = removed && recipients.isEmpty()
        }
        if (last && !state.dead) runtime.unlinkDeath(globalId)
        return removed
    }

    /** Invoked once by the runtime when the owner disappears. */
    internal fun notifyDeath() {
        val snapshot: List<IBinder.DeathRecipient>
        synchronized(recipients) {
            snapshot = ArrayList(recipients)
            recipients.clear()
        }
        for (r in snapshot) {
            try {
                r.binderDied()
            } catch (t: Throwable) {
                Log.w(TAG, "death recipient threw", t)
            }
        }
    }

    override fun toString(): String = "BinderProxy{global=$globalId}"

    private companion object {
        const val TAG = "HostedBinder"
    }
}
