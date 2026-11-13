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
        // X14U Burst: each frame the burst queued takes the next number ("_BURST2313_007"), in capture order.
        burstTag?.let { tag ->
            if (burstPending.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                return tag + "_%03d".format(java.util.Locale.US, burstIndex.incrementAndGet())
            }
        }
        val (suffix, at) = pendingSuffix.getAndSet(null) ?: return ""
        return if (System.nanoTime() - at < 20_000_000_000L) suffix else ""
    }

    @Volatile private var burstTag: String? = null
    private val burstIndex = java.util.concurrent.atomic.AtomicInteger(0)
    private val burstPending = java.util.concurrent.atomic.AtomicInteger(0)

    /** A burst starts: its frames are named [tag]_001, _002, … */
    fun startBurst(tag: String) {
        burstIndex.set(0)
        burstPending.set(0)
        burstTag = tag
    }

    /** One more burst frame is on its way to the capture worker (takes the next number). */
    fun burstFrameQueued() {
        burstPending.incrementAndGet()
    }

    /** A queued burst frame never reached the naming step (refused before it). */
    fun burstFrameDropped() {
        burstPending.getAndUpdate { if (it > 0) it - 1 else 0 }
    }

    /** Leaving Burst: later photos get plain names. */
    fun endBurst() {
        burstTag = null
        burstPending.set(0)
    }

    fun baseName(wallClockMillis: Long): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(wallClockMillis))

    fun baseName(captureTime: CaptureTime): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = SimpleTimeZone(captureTime.utcOffsetMinutes * 60_000, "CaptureTime")
        }.format(Date(captureTime.wallClockUnixMillis))
}
