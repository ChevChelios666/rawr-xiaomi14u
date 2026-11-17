package com.rawr.camera.settings.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * X14U Settings → Capture: "Focus on faces" (continuous AF follows the main face, eye when seen) and "Expose for
 * faces" (exposure set for it). A tapped focus or exposure point takes priority. Kept across launches.
 */
object FaceSettings {
    data class Values(val focus: Boolean = true, val exposure: Boolean = true)

    private var prefs: SharedPreferences? = null
    private val flow = MutableStateFlow(Values())
    val values: StateFlow<Values> = flow.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_faces", Context.MODE_PRIVATE)
        prefs = p
        flow.value = Values(p.getBoolean("focus", true), p.getBoolean("exposure", true))
    }

    fun update(next: Values) {
        flow.value = next
        prefs?.edit()?.putBoolean("focus", next.focus)?.putBoolean("exposure", next.exposure)?.apply()
    }
}
