package dev.artzig

import android.app.sdksandbox.AppOwnedSdkSandboxInterface
import android.app.sdksandbox.LoadSdkException
import android.app.sdksandbox.SdkSandboxManager
import android.media.ApplicationMediaCapabilities
import android.media.MediaFormat
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import android.se.omapi.ISecureElementListener

/** Public data and transport contracts for ActivityThread's mainline dependencies. */
internal object StockMainlineTypesSmoke {
    fun run() {
        val uri = NdefRecord.createUri("https://example.org/tag")
        val text = NdefRecord.createTextRecord("en", "Hello")
        val message = NdefMessage(arrayOf(uri, text))
        val restored = NdefMessage(message.toByteArray())
        check(restored == message && restored.records.size == 2)
        check(restored.records[0].toUri().toString() == "https://example.org/tag")
        check(copy(message, NdefMessage.CREATOR) == message)
        check(NdefRecord.createExternal("Example.ORG", "TYPE", byteArrayOf(1)).toUri().toString() ==
            "vnd.android.nfc://ext/example.org:type")
        check(runCatching { NdefMessage(byteArrayOf(0)) }.exceptionOrNull() is FormatException)
        check(runCatching { NdefMessage(emptyArray<NdefRecord>()) }.exceptionOrNull() is IllegalArgumentException)

        val trigger = ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR)
            .setRateLimitingPeriodHours(8).build()
        check(trigger.triggerType == ProfilingTrigger.TRIGGER_TYPE_ANR)
        check(trigger.toValueParcel().rateLimitingPeriodHours == 8)
        check(runCatching { ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_NONE) }
            .exceptionOrNull() is IllegalArgumentException)
        check(runCatching { ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR)
            .setRateLimitingPeriodHours(-1) }.exceptionOrNull() is IllegalArgumentException)
        val result = ProfilingResult(ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS,
            null, "test", "rate limited", trigger.triggerType)
        check(copy(result, ProfilingResult.CREATOR) == result)

        val binder = Binder()
        val sdk = AppOwnedSdkSandboxInterface("service", 42L, binder)
        val sdkCopy = copy(sdk, AppOwnedSdkSandboxInterface.CREATOR)
        check(sdkCopy.name == "service" && sdkCopy.version == 42L && sdkCopy.`interface` === binder)
        val failure = LoadSdkException(SdkSandboxManager.LOAD_SDK_NOT_FOUND, "missing", null,
            Bundle().apply { putString("detail", "absent") })
        val failureCopy = copy(failure, LoadSdkException.CREATOR)
        check(failureCopy.loadSdkErrorCode == SdkSandboxManager.LOAD_SDK_NOT_FOUND)
        check(failureCopy.message == "missing" && failureCopy.extraInformation.getString("detail") == "absent")

        val capabilities = ApplicationMediaCapabilities.Builder()
            .addSupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            .addUnsupportedVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AV1).build()
        val capabilitiesCopy = copy(capabilities, ApplicationMediaCapabilities.CREATOR)
        check(capabilitiesCopy.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_HEVC))
        check(!capabilitiesCopy.isVideoMimeTypeSupported(MediaFormat.MIMETYPE_VIDEO_AV1))

        // Exercise generated stable-AIDL metadata through the proxy's Parcel path.
        val listener = object : ISecureElementListener.Stub() {
            override fun getInterfaceVersion(): Int = ISecureElementListener.VERSION
            override fun getInterfaceHash(): String = ISecureElementListener.HASH
        }
        val remote = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                listener.transact(code, data, reply, flags)
        }
        val proxy = ISecureElementListener.Stub.asInterface(remote)
        check(proxy !== listener && proxy.interfaceVersion == 1)
        check(proxy.interfaceHash == "894069bcfe4f35ceb2088278ddf87c83adee8014")
        println("stock-mainline-types: NDEF parsing, profiling validation, SDK results, media capabilities and stable AIDL passed")
    }

    private fun <T : Parcelable> copy(value: T, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            creator.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }
}
