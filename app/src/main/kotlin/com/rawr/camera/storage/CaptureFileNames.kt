package com.rawr.camera.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

internal object CaptureFileNames {
    /** X14U Pano: appended to still names while a set runs ("_PANO2231_03"); empty otherwise. */
    @Volatile var stillSuffix: String = ""

    fun baseName(wallClockMillis: Long): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(wallClockMillis))

    fun baseName(captureTime: CaptureTime): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = SimpleTimeZone(captureTime.utcOffsetMinutes * 60_000, "CaptureTime")
        }.format(Date(captureTime.wallClockUnixMillis))
}
