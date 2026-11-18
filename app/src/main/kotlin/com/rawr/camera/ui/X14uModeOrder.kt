package com.rawr.camera.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * X14U (Nov 19): the order of the modes on the mode strip. Long-press a mode and drag it to move it (as the preset
 * cards). Kept across launches; read as Compose state, so the strip follows at once.
 */
internal object X14uModeOrder {
    val DEFAULT = listOf(X14uMode.Photo, X14uMode.Pro, X14uMode.Video, X14uMode.Burst, X14uMode.Pano)

    private var prefs: SharedPreferences? = null

    var modes by mutableStateOf(DEFAULT)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_mode_order", Context.MODE_PRIVATE)
        prefs = p
        modes = decode(p.getString("order", null))
    }

    /** Stored names in order; unknown names dropped, missing modes added at the end in the default order. */
    private fun decode(raw: String?): List<X14uMode> {
        val stored = raw.orEmpty().split(',').mapNotNull { name -> X14uMode.entries.firstOrNull { it.name == name } }.distinct()
        return stored + DEFAULT.filter { it !in stored }
    }

    fun set(next: List<X14uMode>) {
        val fixed = decode(next.joinToString(",") { it.name })
        modes = fixed
        prefs?.edit()?.putString("order", fixed.joinToString(",") { it.name })?.apply()
    }
}
