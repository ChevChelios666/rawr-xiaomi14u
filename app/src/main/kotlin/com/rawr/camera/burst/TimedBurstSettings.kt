package com.rawr.camera.burst

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * X14U timed burst (intervalometer, Nov 17): with it on, the shutter in Photo / Photo+ starts a run of [photos]
 * shots (0 = until stopped), one every [intervalS] seconds, focus and white balance locked at the first, exposure
 * too when [lockExposure]. Kept across launches; the Timed burst tile in the tools panel changes it.
 */
object TimedBurstSettings {
    data class Values(
        val enabled: Boolean = false,
        val photos: Int = 300,
        val intervalS: Int = 2,
        val lockExposure: Boolean = true
    ) {
        /** About how long a run takes (null: until stopped). */
        val durationS: Int? get() = if (photos > 0) (photos - 1) * intervalS else null
    }

    /** 0 = until stopped (∞). */
    val PHOTOS = listOf(10, 30, 60, 120, 300, 600, 0)
    val INTERVALS = listOf(1, 2, 5, 10, 30, 60)

    private var prefs: SharedPreferences? = null
    private val flow = MutableStateFlow(Values())
    val values: StateFlow<Values> = flow.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_timed_burst", Context.MODE_PRIVATE)
        prefs = p
        flow.value = Values(
            enabled = p.getBoolean("enabled", false),
            photos = p.getInt("photos", 300).takeIf { it in PHOTOS } ?: 300,
            intervalS = p.getInt("intervalS", 2).takeIf { it in INTERVALS } ?: 2,
            lockExposure = p.getBoolean("lockExposure", true)
        )
    }

    fun update(next: Values) {
        flow.value = next
        prefs?.edit()
            ?.putBoolean("enabled", next.enabled)
            ?.putInt("photos", next.photos)
            ?.putInt("intervalS", next.intervalS)
            ?.putBoolean("lockExposure", next.lockExposure)
            ?.apply()
    }
}
