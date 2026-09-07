package dev.artzig.system

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.ICompanionDeviceManager
import android.companion.IOnAssociationsChangedListener
import android.content.Context
import android.content.ContextWrapper
import android.os.UserHandle
import java.util.function.Consumer

/** Exercise stock CDM callback dispatch into the virtual-device local-service contract. */
internal object StockVirtualDeviceAssociationsSmoke {
    fun run(context: Context, drain: () -> Unit) {
        val eligible = AssociationInfo.Builder(4242, context.userId, context.packageName)
            .setSelfManaged(true).setDisplayName("Virtual fixture")
            .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_APP_STREAMING).build()
        val ordinary = AssociationInfo.Builder(4243, context.userId, context.packageName)
            .setSelfManaged(true).setDisplayName("Ordinary companion").build()
        var associations = listOf(eligible, ordinary)
        var callback: IOnAssociationsChangedListener? = null
        val binder = object : ICompanionDeviceManager.Stub(),
            ICompanionDeviceManager by unsupported<ICompanionDeviceManager>() {
            override fun getAllAssociationsForUser(userId: Int): MutableList<AssociationInfo> {
                check(userId == UserHandle.USER_ALL)
                return associations.toMutableList()
            }
            override fun addOnAssociationsChangedListener(listener: IOnAssociationsChangedListener, userId: Int) {
                check(userId == UserHandle.USER_ALL)
                callback = listener
            }
        }
        lateinit var companion: CompanionDeviceManager
        val wrapper = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.COMPANION_DEVICE_SERVICE) companion else super.getSystemService(name)
        }
        companion = CompanionDeviceManager(binder, wrapper)
        val local = HostedVirtualDeviceManagerInternal()
        val removed = ArrayList<String>()
        local.registerPersistentDeviceIdRemovedListener(Consumer { removed.add(it) })
        val service = HostedVirtualDeviceService(wrapper, local)
        check(service.getAllPersistentDeviceIds() == listOf("companion:4242"))
        check(service.getDisplayNameForPersistentDeviceId("companion:4242") == "Virtual fixture")
        check(service.getDisplayNameForPersistentDeviceId("companion:4243") == null)
        associations = listOf(AssociationInfo.Builder(eligible).setDisplayName("Renamed").build(), ordinary)
        checkNotNull(callback).onAssociationsChanged(associations)
        drain()
        check(removed.isEmpty())
        check(service.getDisplayNameForPersistentDeviceId("companion:4242") == "Renamed")
        associations = listOf(AssociationInfo.Builder(eligible).setRevoked(true).build(), ordinary)
        checkNotNull(callback).onAssociationsChanged(associations)
        check(removed.isEmpty())
        drain()
        check(removed == listOf("companion:4242"))
        check(service.getAllPersistentDeviceIds().isEmpty())
        checkNotNull(callback).onAssociationsChanged(associations)
        drain()
        check(removed == listOf("companion:4242"))
    }
}
