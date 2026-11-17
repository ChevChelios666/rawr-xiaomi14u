package com.rawr.camera.integration

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Xiaomi 14 Ultra Photography Kit: what its controls ask the capture screen to do. */
object GripEvents {
    /** Zoom lever: the lens pill one step (+1 longer, -1 wider). */
    val lensSteps = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Dial: clicks (+ / -) for the control chosen in Settings. */
    val dialSteps = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 16)
    /** ShutterRelease: the full press let go (ends a burst). RecordRelease: the red button let go (hold-to-lock). */
    enum class Action { Focus, Shutter, ShutterRelease, Record, RecordRelease }
    val actions = kotlinx.coroutines.flow.MutableSharedFlow<Action>(extraBufferCapacity = 4)
}

/**
 * X14U button test (Tools → Button test): shows every key press and every dial / wheel event that reaches the app,
 * with its code, scan code and device, so the Photography Kit's controls can be mapped. While on, nothing else acts on
 * those events (Back still works). Each line also goes to the log ("RawrButtons").
 */
object ButtonTest {
    private const val MAX_LINES = 16
    private val enabledFlow = MutableStateFlow(false)
    private val linesFlow = MutableStateFlow<List<String>>(emptyList())
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()
    val lines: StateFlow<List<String>> = linesFlow.asStateFlow()

    fun setEnabled(on: Boolean) {
        enabledFlow.value = on
        if (on) linesFlow.value = emptyList() else GripUsbReader.stop()
    }

    /** A line from elsewhere (the raw USB reader); thread-safe. */
    fun note(line: String) = add(line)

    /** True when the event was recorded (and must not act). */
    fun onKey(event: KeyEvent): Boolean {
        if (!enabledFlow.value || event.keyCode == KeyEvent.KEYCODE_BACK) return false
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) "repeat ${event.repeatCount}" else "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "action ${event.action}"
        }
        // Repeats only once in a while, so a held key doesn't flood the list.
        if (event.repeatCount > 0 && event.repeatCount % 10 != 0) return true
        // Flags and meta state too: the grip's dial sends the same key both ways, so any difference counts.
        add("${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) $action · scan ${event.scanCode} · " +
            "flags 0x${Integer.toHexString(event.flags)} · meta 0x${Integer.toHexString(event.metaState)} · " +
            "source 0x${Integer.toHexString(event.source)} · ${device(event.device)}")
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!enabledFlow.value) return false
        // Touches on the screen are not buttons.
        if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
        val axes = mutableListOf<String>()
        event.device?.motionRanges?.forEach { range ->
            val v = event.getAxisValue(range.axis)
            if (v != 0f) axes += "${MotionEvent.axisToString(range.axis)}=${"%.2f".format(v)}"
        }
        for (axis in intArrayOf(MotionEvent.AXIS_SCROLL, MotionEvent.AXIS_VSCROLL, MotionEvent.AXIS_HSCROLL, MotionEvent.AXIS_WHEEL)) {
            val v = event.getAxisValue(axis)
            val name = MotionEvent.axisToString(axis)
            if (v != 0f && axes.none { it.startsWith("$name=") }) axes += "$name=${"%.2f".format(v)}"
        }
        add("${MotionEvent.actionToString(event.actionMasked)} ${axes.joinToString(" ").ifEmpty { "no axis" }} · " +
            "source 0x${Integer.toHexString(event.source)} · ${device(event.device)}")
        return true
    }

    private fun device(device: InputDevice?): String =
        if (device == null) "no device" else "${device.name} (id ${device.id}, vendor ${device.vendorId}, product ${device.productId})"

    private fun add(line: String) {
        Log.i("RawrButtons", line)
        linesFlow.update { (listOf(line) + it).take(MAX_LINES) }
    }
}
