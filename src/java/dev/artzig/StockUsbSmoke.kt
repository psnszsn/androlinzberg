@file:Suppress("DEPRECATION")
package dev.artzig

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.hardware.usb.*
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import dev.artzig.system.unsupported
import java.io.File
import java.nio.ByteBuffer

internal object StockUsbSmoke {
    private class UsbContext : ContextWrapper(null) {
        override fun getPackageName() = "dev.artzig.usb-test"
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = ApplicationInfo().apply { targetSdkVersion = 35 }
    }

    fun run() {
        val context = UsbContext()
        val absent = UsbManager(context, null)
        check(absent.deviceList.isEmpty() && absent.accessoryList == null)
        val functions = UsbManager.FUNCTION_MTP or UsbManager.FUNCTION_ADB
        check(UsbManager.usbFunctionsFromString(UsbManager.usbFunctionsToString(functions)) == functions)
        check(UsbManager.usbFunctionsFromString("none") == 0L)
        check(runCatching { UsbManager.usbFunctionsFromString("invalid") }.exceptionOrNull() is IllegalArgumentException)
        val role = UsbPort.combineRolesAsBit(UsbPortStatus.POWER_ROLE_SOURCE, UsbPortStatus.DATA_ROLE_HOST)
        val status = UsbPortStatus(UsbPortStatus.MODE_DFP, UsbPortStatus.POWER_ROLE_SOURCE,
            UsbPortStatus.DATA_ROLE_HOST, role, 0, 0)
        check(status.isConnected && status.isRoleCombinationSupported(
            UsbPortStatus.POWER_ROLE_SOURCE, UsbPortStatus.DATA_ROLE_HOST))
        check(!status.isRoleCombinationSupported(UsbPortStatus.POWER_ROLE_SINK, UsbPortStatus.DATA_ROLE_DEVICE))
        val parcel = Parcel.obtain()
        try {
            status.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = UsbPortStatus.CREATOR.createFromParcel(parcel)
            check(restored.currentMode == status.currentMode && restored.supportedRoleCombinations == role)
        } finally { parcel.recycle() }

        val device = UsbDevice.Builder("/dev/bus/usb/002/017", 0x1234, 0x5678, 0, 0, 0,
            "Fixture", "Descriptor transport", "1.0", emptyArray(), null,
            false, false, false, false, false).build(object : IUsbSerialReader.Stub() {
                override fun getSerial(packageName: String?) = "fixture"
            })
        val descriptors = byteArrayOf(18, 1, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 0, 0, 0, 0)
        val file = File.createTempFile("usb-descriptors-", ".bin")
        try {
            file.writeBytes(descriptors)
            val service = object : IUsbManager.Stub(), IUsbManager by unsupported<IUsbManager>() {
                override fun asBinder(): IBinder = this
                override fun getDeviceList(devices: Bundle) { devices.putParcelable(device.deviceName, device) }
                override fun getCurrentAccessory(): UsbAccessory? = null
                override fun openDevice(name: String?, packageName: String?): ParcelFileDescriptor {
                    check(name == device.deviceName && packageName == context.packageName)
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
                }
            }
            // Force the generated proxy/Stub Parcel path, including descriptor duplication.
            val transport = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                    service.transact(code, data, reply, flags)
            }
            val manager = UsbManager(context, IUsbManager.Stub.asInterface(transport))
            check(manager.deviceList[device.deviceName] == device && manager.accessoryList == null)
            val connection = checkNotNull(manager.openDevice(device))
            try {
                check(connection.fileDescriptor >= 0 && connection.rawDescriptors.contentEquals(descriptors))
                // A regular descriptor fixture can be read, but cannot perform USB ioctls.
                check(connection.controlTransfer(0, 0, 0, 0, null, 0, 1) == -1)
                val request = UsbRequest()
                try {
                    val endpoint = UsbEndpoint(0x81, UsbConstants.USB_ENDPOINT_XFER_BULK, 64, 1)
                    check(request.initialize(connection, endpoint))
                    request.clientData = device
                    check(request.clientData === device && request.endpoint === endpoint)
                    check(!request.queue(ByteBuffer.allocateDirect(16)))
                } finally { request.close() }
            } finally { connection.close() }
            check(connection.fileDescriptor == -1)
            check(connection.controlTransfer(0, 0, 0, 0, null, 0, 1) == -1)
            val closedRequest = UsbRequest()
            try {
                check(!closedRequest.initialize(connection,
                    UsbEndpoint(0x81, UsbConstants.USB_ENDPOINT_XFER_BULK, 64, 1)))
            } finally { closedRequest.close() }
        } finally { check(file.delete()) }
        check(android.app.SystemServiceRegistry.getSystemServiceName(UsbManager::class.java) == Context.USB_SERVICE)
    }
}
