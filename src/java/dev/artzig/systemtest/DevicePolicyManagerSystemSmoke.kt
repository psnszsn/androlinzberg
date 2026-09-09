package dev.artzig.systemtest

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.ServiceManager
import java.util.function.Supplier

/** Focused cross-process assertions for the stock DPM client and hosted policy transport. */
internal object DevicePolicyManagerSystemSmoke {
    fun run(context: Context) {
        check(ServiceManager.getService(Context.DEVICE_POLICY_SERVICE) != null) {
            "device_policy service is published"
        }
        val manager = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
        check(!DevicePolicyManager.isMtePolicyEnforced()) { "x86_64 has no ARM MTE" }
        check(manager.createProvisioningIntentFromNfcIntent(android.content.Intent("unrelated")) == null)
        val properties = "${DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}=${context.packageName}/.Admin\n" +
            "${DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE}=true\n"
        val message = android.nfc.NdefMessage(arrayOf(android.nfc.NdefRecord.createMime(
            DevicePolicyManager.MIME_TYPE_PROVISIONING_NFC, properties.toByteArray(Charsets.UTF_8))))
        val nfc = android.content.Intent(android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED)
            .putExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf<android.os.Parcelable>(message))
        val provision = checkNotNull(manager.createProvisioningIntentFromNfcIntent(nfc))
        check(provision.action == DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE)
        check(provision.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
            ComponentName::class.java) == ComponentName(context.packageName, "${context.packageName}.Admin"))
        check(provision.getBooleanExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, false))
        check(provision.getIntExtra(DevicePolicyManager.EXTRA_PROVISIONING_TRIGGER, -1) == DevicePolicyManager.PROVISIONING_TRIGGER_NFC)

        val strings = listOf("", "policy", "ümlaut")
        val nano = android.stats.devicepolicy.nano.StringList().apply { stringValue = strings.toTypedArray() }
        val bytes = com.android.framework.protobuf.nano.MessageNano.toByteArray(nano)
        check(android.stats.devicepolicy.nano.StringList.parseFrom(bytes).stringValue.toList() == strings)
        android.app.admin.DevicePolicyEventLogger.createEvent(1).setStrings(*strings.toTypedArray()).write()
        val parcel = android.os.Parcel.obtain()
        try {
            val slice = android.content.pm.StringParceledListSlice(strings)
            slice.setInlineCountLimit(1) // Exercise the original Binder continuation, too.
            slice.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            check(android.content.pm.StringParceledListSlice.CREATOR.createFromParcel(parcel).list == strings)
        } finally { parcel.recycle() }
        val absentAdmin = ComponentName(context.packageName, "MissingDeviceAdminReceiver")

        check(!manager.isDeviceManaged) { "the hosted desktop starts unmanaged" }
        check(!manager.isAdminActive(absentAdmin)) { "an unprovisioned receiver is not active" }
        check(manager.activeAdmins == null) { "an unmanaged user has no active admins" }
        check(!manager.isProfileOwnerApp(context.packageName)) { "the app is not a profile owner" }
        check(!manager.isDeviceOwnerApp(context.packageName)) { "the app is not a device owner" }
        check(!manager.isSecurityLoggingEnabled(null)) { "security collection is disabled without an owner" }
        check(
            manager.resources.getString("dev.artzig.missing", Supplier { "host-default" }) ==
                "host-default",
        ) { "missing policy resources preserve the stock fallback contract" }
    }
}
