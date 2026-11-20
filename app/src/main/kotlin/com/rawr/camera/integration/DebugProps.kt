package com.rawr.camera.integration

import android.annotation.SuppressLint

/** Test switches set with `adb shell setprop debug.rawr.<name> <value>` (debug.* properties are readable by apps). */
internal object DebugProps {
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private val get by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }

    /** The property as a number, or [default] when unset or not a number. */
    fun int(name: String, default: Int = -1): Int =
        runCatching { (get?.invoke(null, name) as? String)?.trim()?.toIntOrNull() }.getOrNull() ?: default
}
