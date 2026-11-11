package com.rawr.camera.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

internal object CaptureFileNames {
    /**
     * X14U Pano: the next still's name tag ("_PANO2231_03"). Set just before a pano frame's shutter and used by that
     * capture only (taken once; stale after 20 s, which covers the self-timer), so no later photo can pick it up.
     */
    private val pendingSuffix = java.util.concurrent.atomic.AtomicReference<Pair<String, Long>?>(null)

    fun setNextStillSuffix(suffix: String) {
        pendingSuffix.set(if (suffix.isEmpty()) null else suffix to System.nanoTime())
    }

    fun takeStillSuffix(): String {
        val (suffix, at) = pendingSuffix.getAndSet(null) ?: return ""
        return if (System.nanoTime() - at < 20_000_000_000L) suffix else ""
    }

    fun baseName(wallClockMillis: Long): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(wallClockMillis))

    fun baseName(captureTime: CaptureTime): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = SimpleTimeZone(captureTime.utcOffsetMinutes * 60_000, "CaptureTime")
        }.format(Date(captureTime.wallClockUnixMillis))
}
