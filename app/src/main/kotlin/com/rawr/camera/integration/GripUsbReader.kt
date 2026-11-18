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
 * Photography Kit over USB: the grip's dial reaches Android as the same key both ways and its record button not at
 * all, so Rawr reads the grip's own HID reports while it is on screen (Settings → Display & Control) and gives the
 * grip back to the system when it leaves. Reports (button test, Nov 2026), one HID interface, endpoint 0x81:
 *  - 01 dd 00 00 00 05 e8: the dial, dd = +1 (01) or -1 (ff) per click.
 *  - 02 aa aa bb bb cc cc xx: up to three key codes held (little endian): 418 zoom lever T (02 a2 01 …), 419 W
 *    (02 00 00 a3 01 …), 232 the red record button (02 00 00 00 00 e8 00 00); xx = the lever's last way.
 *  - 04 aa aa bb bb: up to two Linux key codes held (little endian): 528 half press, 212 full press.
 * While the button test is open the reports are only listed there.
 */
object GripUsbReader {
    /** Xiaomi (0x2717), "Xiaomi N1G Mouse" (0x5083): the Photography Kit grip. */
    private const val VENDOR = 10007
    private const val PRODUCT = 20611
    private const val ACTION = "com.rawr.camera.GRIP_USB_PERMISSION"

    private val runningFlow = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningFlow.asStateFlow()

    @Volatile private var connection: UsbDeviceConnection? = null
    private var heldKeys: Set<Int> = emptySet()
    private var heldKeys2: Set<Int> = emptySet()
    private var claimed: List<UsbInterface> = emptyList()
    private var threads: List<Thread> = emptyList()
    private var receiver: BroadcastReceiver? = null
    /** Permission refused this run: the screen doesn't ask again on every start. */
    @Volatile private var refused = false

    /** [quiet]: on screen start, say nothing (and don't ask again) when the grip isn't there or permission was refused. */
    fun start(context: Context, quiet: Boolean = false) {
        if (runningFlow.value) return
        val manager = context.getSystemService(UsbManager::class.java) ?: return ButtonTest.note("USB: no USB host service")
        val devices = manager.deviceList.values
        val device = devices.firstOrNull { it.vendorId == VENDOR && it.productId == PRODUCT }
            ?: devices.firstOrNull { it.vendorId == VENDOR }
        if (device == null && quiet) return
        if (!quiet) refused = false
        if (quiet && refused && device != null && !manager.hasPermission(device)) return
        if (device == null) {
            ButtonTest.note("USB: grip not found among ${devices.size} USB device(s): " +
                devices.joinToString { "${it.productName ?: it.deviceName} (${it.vendorId}:${it.productId})" }.ifEmpty { "none" })
            return
        }
        if (manager.hasPermission(device)) {
            open(context, manager, device)
            return
        }
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { app.unregisterReceiver(this) }
                receiver = null
                if (manager.hasPermission(device)) open(app, manager, device) else {
                    refused = true
                    ButtonTest.note("USB: permission denied")
                }
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
            // Button test: the HID report descriptor (how every report is laid out), for mapping what isn't known yet.
            if (ButtonTest.enabled.value) {
                val desc = ByteArray(1024)
                val n = conn.controlTransfer(0x81, 0x06, 0x2200, intf.id, desc, desc.size, 1000)
                if (n > 0) (0 until n).chunked(48).forEachIndexed { i, part ->
                    ButtonTest.note("USB descriptor #${intf.id} ${i + 1}: " + part.joinToString(" ") { "%02x".format(desc[it]) })
                } else ButtonTest.note("USB: no report descriptor ($n)")
            }
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction != UsbConstants.USB_DIR_IN) continue
                readers += Thread({
                    val buffer = ByteArray(ep.maxPacketSize.coerceAtLeast(8))
                    while (runningFlow.value) {
                        val n = conn.bulkTransfer(ep, buffer, buffer.size, 250)
                        if (n > 0) {
                            if (ButtonTest.enabled.value) {
                                ButtonTest.note("USB #${intf.id} ep0x${Integer.toHexString(ep.address)}: " +
                                    (0 until n).joinToString(" ") { "%02x".format(buffer[it]) })
                            }
                            handle(buffer, n)
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

    /** One report: acts on new presses and dial clicks (not while the button test is open). */
    @Synchronized
    private fun handle(report: ByteArray, n: Int) {
        val act = !ButtonTest.enabled.value
        when (report[0].toInt()) {
            1 -> if (n >= 2) {
                val clicks = report[1].toInt() // signed: +1 / -1
                if (clicks != 0 && act) GripEvents.dialSteps.tryEmit(clicks)
            }
            2 -> {
                // Up to three held key codes, 16-bit little endian at bytes 1, 3, 5 (byte 7 = the lever's last way,
                // ignored): 418 zoom lever T, 419 W, 232 (e8) the red record button.
                val keys = buildSet {
                    for (i in intArrayOf(1, 3, 5)) if (i + 1 < n) {
                        val code = (report[i].toInt() and 0xFF) or ((report[i + 1].toInt() and 0xFF) shl 8)
                        if (code != 0) add(code)
                    }
                }
                if (act && 232 in keys && 232 !in heldKeys2) GripEvents.actions.tryEmit(GripEvents.Action.Record)
                // The lever: a step on a new press, or smooth zoom while held (GripEvents.lever).
                if (act) GripEvents.lever(if (418 in keys) 1 else if (419 in keys) -1 else 0)
                if (act && 232 in heldKeys2 && 232 !in keys) GripEvents.actions.tryEmit(GripEvents.Action.RecordRelease)
                heldKeys2 = keys
            }
            4 -> {
                val keys = buildSet {
                    var i = 1
                    while (i + 1 < n) {
                        val code = (report[i].toInt() and 0xFF) or ((report[i + 1].toInt() and 0xFF) shl 8)
                        if (code != 0) add(code)
                        i += 2
                    }
                }
                if (act) for (code in keys - heldKeys) when (code) {
                    528 -> GripEvents.actions.tryEmit(GripEvents.Action.Focus)
                    212 -> GripEvents.actions.tryEmit(GripEvents.Action.Shutter)
                }
                if (act && 212 in heldKeys && 212 !in keys) GripEvents.actions.tryEmit(GripEvents.Action.ShutterRelease)
                heldKeys = keys
            }
        }
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
        heldKeys = emptySet()
        heldKeys2 = emptySet()
        GripEvents.zoomHeld.value = 0
        runCatching { conn?.close() }
        ButtonTest.note("USB: stopped, the grip is back to normal")
    }
}
