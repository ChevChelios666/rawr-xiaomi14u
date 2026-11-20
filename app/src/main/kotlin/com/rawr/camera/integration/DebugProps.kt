package com.rawr.camera.integration

import android.annotation.SuppressLint
import java.lang.reflect.Method

/** Test switches set with `adb shell setprop debug.rawr.<name> <value>` (debug.* properties are readable by apps). */
internal object DebugProps {
    private val get: Method? by lazy { lookup() }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun lookup(): Method? =
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()

    /** The property as a number, or [default] when unset or not a number. */
    fun int(name: String, default: Int = -1): Int =
        runCatching { (get?.invoke(null, name) as? String)?.trim()?.toIntOrNull() }.getOrNull() ?: default
}
