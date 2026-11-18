package com.rawr.camera.settings.model

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * X14U Settings → Display → Control Style → Accent (Nov 18): the capture screen's accent colour (selected values, the
 * lit mode, active buttons). "Style default" keeps each style's own (Minimal coral, Classic orange, Bronze amber).
 * Kept across launches; read as Compose state, so the capture screen changes at once.
 */
object AccentSettings {
    enum class Choice(val label: String, val argb: Long?) {
        StyleDefault("Style default", null),
        Orange("Orange", 0xFFE8763E),
        Amber("Amber", 0xFFFFC846),
        Verdigris("Verdigris", 0xFF54D6BE),
        IceBlue("Ice blue", 0xFF78C8FF),
        Coral("Coral", 0xFFE06A61),
        Red("Red", 0xFFE24638),
        Ivory("Ivory", 0xFFF2EBDD)
    }

    private var prefs: SharedPreferences? = null

    var choice by mutableStateOf(Choice.StyleDefault)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_accent", Context.MODE_PRIVATE)
        prefs = p
        choice = Choice.entries.firstOrNull { it.name == p.getString("accent", null) } ?: Choice.StyleDefault
    }

    fun select(next: Choice) {
        choice = next
        prefs?.edit()?.putString("accent", next.name)?.apply()
    }
}
