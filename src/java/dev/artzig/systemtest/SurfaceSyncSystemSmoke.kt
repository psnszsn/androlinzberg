package dev.artzig.systemtest

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.window.ISurfaceSyncGroup
import android.window.SurfaceSyncGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stock sync groups coordinate readiness through the hosted WMS across Binder peers. */
internal object SurfaceSyncSystemSmoke {
    private const val NAME = "dev.artzig.test.b.surfacesync"

    fun publish() {
        ServiceManager.addService(NAME, object : Binder() {
            private val group = SurfaceSyncGroup("system-peer-b").apply { toggleTimeout(false) }
            private val consumed = CountDownLatch(1)
            private var consumedToken: IBinder? = null
            private var consumedAborted = false
            private val transition = object : android.window.IRemoteTransition.Stub() {
                override fun startAnimation(token: IBinder?, info: android.window.TransitionInfo?,
                    transaction: android.view.SurfaceControl.Transaction?,
                    callback: android.window.IRemoteTransitionFinishedCallback?) {
                    checkNotNull(callback).onTransitionFinished(null, null)
                }
                override fun mergeAnimation(token: IBinder?, info: android.window.TransitionInfo?,
                    transaction: android.view.SurfaceControl.Transaction?, target: IBinder?,
                    callback: android.window.IRemoteTransitionFinishedCallback?) = error("unexpected merge")
                override fun takeOverAnimation(token: IBinder?, info: android.window.TransitionInfo?,
                    transaction: android.view.SurfaceControl.Transaction?,
                    callback: android.window.IRemoteTransitionFinishedCallback?,
                    states: Array<android.window.WindowAnimationState>?) = error("unexpected takeover")
                override fun onTransitionConsumed(token: IBinder?, aborted: Boolean) {
                    consumedToken = token
                    consumedAborted = aborted
                    consumed.countDown()
                }
            }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(NAME)
                when (code) {
                    1 -> checkNotNull(reply).writeStrongBinder(group.mISurfaceSyncGroup.asBinder())
                    2 -> group.markSyncReady()
                    3 -> {
                        checkNotNull(reply).writeBoolean(
                            com.android.server.wm.StockSurfaceSyncSmoke.verifyRemoteOwner())
                    }
                    4 -> checkNotNull(reply).writeStrongBinder(transition.asBinder())
                    5 -> {
                        check(consumed.await(5, TimeUnit.SECONDS))
                        checkNotNull(reply).writeStrongBinder(consumedToken)
                        reply.writeBoolean(consumedAborted)
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        })
    }

    fun verify() {
        val service = checkNotNull(ServiceManager.waitForService(NAME))
        check(service !is Binder)
        fun <T> call(code: Int, read: (Parcel) -> T): T {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                request.writeInterfaceToken(NAME)
                check(service.transact(code, request, reply, 0))
                read(reply)
            } finally { request.recycle(); reply.recycle() }
        }
        val child = checkNotNull(ISurfaceSyncGroup.Stub.asInterface(call(1) { it.readStrongBinder() }))
        check(child.asBinder() !is Binder)
        val complete = CountDownLatch(1)
        val parent = SurfaceSyncGroup("system-peer-a").apply { toggleTimeout(false) }
        parent.addSyncCompleteCallback({ it.run() }) { complete.countDown() }
        check(parent.add(child, false, null))
        parent.markSyncReady()
        check(!complete.await(50, TimeUnit.MILLISECONDS)) { "remote child has not supplied its transaction" }
        call(2) {}
        check(complete.await(5, TimeUnit.SECONDS)) { "WMS did not report cross-process sync completion" }
        check(call(3) { it.readBoolean() }) { "sync token could be marked ready by a different UID" }
        val remote = checkNotNull(android.window.IRemoteTransition.Stub.asInterface(call(4) { it.readStrongBinder() }))
        check(remote.asBinder() !is Binder)
        val finished = CountDownLatch(1)
        val callback = object : android.window.IRemoteTransitionFinishedCallback.Stub() {
            override fun onTransitionFinished(wct: android.window.WindowContainerTransaction?,
                transaction: android.view.SurfaceControl.Transaction?) {
                check(wct == null && transaction == null)
                finished.countDown()
            }
        }
        val transitionToken = Binder()
        remote.startAnimation(transitionToken, null, null, callback)
        check(finished.await(5, TimeUnit.SECONDS))
        remote.onTransitionConsumed(transitionToken, true)
        call(5) { check(it.readStrongBinder() === transitionToken && it.readBoolean()) }
        System.err.println("system-test-client-a: ok: stock surface sync waits for remote readiness and enforces owner UID")
        System.err.println("system-test-client-a: ok: generated remote-transition Binder delivers finish callbacks and token identity")
    }
}
