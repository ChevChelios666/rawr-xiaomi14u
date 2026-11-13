package com.rawr.camera.integration

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Photography Kit raw USB test (Button test → Read USB): the grip's dial reaches Android as the same key both ways, so
 * this reads the grip's own HID reports, where the direction should be. It takes the grip's HID interfaces from the
 * system while it runs (the buttons then only show here) and gives them back when stopped. Lines go to the button
 * test list and the log ("RawrButtons").
 */
object GripUsbReader {
    /** Xiaomi (0x2717), "Xiaomi N1G Mouse" (0x5083): the Photography Kit grip. */
    private const val VENDOR = 10007
    private const val PRODUCT = 20611
    private const val ACTION = "com.rawr.camera.GRIP_USB_PERMISSION"

    private val runningFlow = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningFlow.asStateFlow()

    @Volatile private var connection: UsbDeviceConnection? = null
    private var claimed: List<UsbInterface> = emptyList()
    private var threads: List<Thread> = emptyList()
    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        if (runningFlow.value) return
        val manager = context.getSystemService(UsbManager::class.java) ?: return ButtonTest.note("USB: no USB host service")
        val devices = manager.deviceList.values
        val device = devices.firstOrNull { it.vendorId == VENDOR && it.productId == PRODUCT }
            ?: devices.firstOrNull { it.vendorId == VENDOR }
        if (device == null) {
            ButtonTest.note("USB: grip not found among ${devices.size} USB device(s): " +
                devices.joinToString { "${it.productName ?: it.deviceName} (${it.vendorId}:${it.productId})" }.ifEmpty { "none" })
            return
        }
        if (manager.hasPermission(device)) {
            open(context, manager, device)
            return
        }
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { app.unregisterReceiver(this) }
                receiver = null
                if (manager.hasPermission(device)) open(app, manager, device) else ButtonTest.note("USB: permission denied")
            }
        }
        receiver = r
        ContextCompat.registerReceiver(app, r, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(app, 0, Intent(ACTION).setPackage(app.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ButtonTest.note("USB: asking for permission to read ${device.productName ?: "the grip"}…")
        manager.requestPermission(device, pending)
    }

    private fun open(context: Context, manager: UsbManager, device: UsbDevice) {
        val conn = manager.openDevice(device) ?: return ButtonTest.note("USB: couldn't open the grip")
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        ButtonTest.note("USB: ${device.productName} · ${interfaces.size} interface(s): " + interfaces.joinToString {
            "#${it.id} class ${it.interfaceClass}/${it.interfaceSubclass}/${it.interfaceProtocol} ${it.endpointCount} ep"
        })
        val hid = interfaces.filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        val readers = mutableListOf<Thread>()
        val taken = mutableListOf<UsbInterface>()
        for (intf in hid) {
            if (!conn.claimInterface(intf, true)) {
                ButtonTest.note("USB: interface #${intf.id} couldn't be claimed")
                continue
            }
            taken += intf
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_IN) continue
                readers += Thread({
                    val buffer = ByteArray(ep.maxPacketSize.coerceAtLeast(8))
                    while (runningFlow.value) {
                        val n = conn.bulkTransfer(ep, buffer, buffer.size, 250)
                        if (n > 0) {
                            ButtonTest.note("USB #${intf.id} ep0x${Integer.toHexString(ep.address)}: " +
                                (0 until n).joinToString(" ") { "%02x".format(buffer[it]) })
                        }
                    }
                }, "GripUsb-${intf.id}-${ep.address}")
            }
        }
        if (taken.isEmpty()) {
            conn.close()
            ButtonTest.note("USB: no HID interface to read")
            return
        }
        connection = conn
        claimed = taken
        runningFlow.value = true
        threads = readers
        readers.forEach { it.isDaemon = true; it.start() }
        ButtonTest.note("USB: reading ${readers.size} endpoint(s). Turn the dial each way, press each button.")
    }

    fun stop() {
        receiver = null
        if (!runningFlow.value) return
        runningFlow.value = false
        threads.forEach { runCatching { it.join(400) } }
        threads = emptyList()
        val conn = connection
        connection = null
        // Released interfaces go back to the system's driver (keys reach apps again).
        claimed.forEach { runCatching { conn?.releaseInterface(it) } }
        claimed = emptyList()
        runCatching { conn?.close() }
        ButtonTest.note("USB: stopped, the grip is back to normal")
    }
}
