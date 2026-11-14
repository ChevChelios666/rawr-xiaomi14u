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

    fun takeStillSuffix(): String = takeStill().suffix

    /** The next still's name: a tag after Rawr's own name, or (a burst frame) a whole name and its folder. */
    data class StillName(val suffix: String = "", val folder: String? = null, val wholeName: String? = null)

    /**
     * X14U Burst: each queued frame takes its reserved number, named the way Pixel phones name burst frames so
     * Google Photos stacks them: "00040IMG_00040_BURST20261114032628123_COVER" (index from 0, one shared burst id,
     * the press frame is the cover).
     */
    fun takeStill(): StillName {
        burstTag?.let { tag ->
            val number = burstNumbers.pollFirst()
            if (number != null) {
                val index = number - 1
                val cover = if (number == burstCover) "_COVER" else ""
                return StillName(folder = burstFolder,
                    wholeName = "%05dIMG_%05d%s%s".format(Locale.US, index, index, tag, cover))
            }
        }
        val (suffix, at) = pendingSuffix.getAndSet(null) ?: return StillName()
        return StillName(suffix = if (System.nanoTime() - at < 20_000_000_000L) suffix else "")
    }

    @Volatile private var burstTag: String? = null
    @Volatile var burstFolder: String? = null
        private set
    @Volatile private var burstCover = 0
    private val burstNumbers = java.util.concurrent.LinkedBlockingDeque<Int>()
    private val nextLive = java.util.concurrent.atomic.AtomicInteger(1)
    /** Burst tags whose RAW job files are kept privately (JPEG-only bursts) for full-quality develop later. */
    private val retainedTags = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * A burst starts ([tag] "_BURST004945", folder "BURST_20261114_004945"): live frames are numbered from
     * [firstLive] (frames from before the press take 1…firstLive-1 later), and the first live frame is the cover.
     */
    fun startBurst(tag: String, folder: String?, firstLive: Int = 1, retainRaw: Boolean = false) {
        burstNumbers.clear()
        nextLive.set(firstLive)
        burstCover = firstLive
        burstFolder = folder
        burstTag = tag
        if (retainRaw) retainedTags[tag] = folder ?: ("BURST" + tag.removePrefix("_BURST"))
    }

    /** One more live burst frame is on its way to the capture worker (takes the next live number). */
    fun burstFrameQueued() {
        burstNumbers.addLast(nextLive.getAndIncrement())
    }

    /** A pre-capture frame (taken before the press) is on its way: it takes [number]. */
    fun burstPreFrameQueued(number: Int) {
        burstNumbers.addLast(number)
    }

    /** A queued burst frame never reached the naming step (refused before it). */
    fun burstFrameDropped() {
        burstNumbers.pollLast()
    }

    /** True when this job's RAW stays (privately) after its photo is saved; the burst folder it goes to. */
    fun retainedRawFolder(jobName: String): String? {
        return retainedTags.entries.firstOrNull { jobName.contains(it.key) }?.value
    }

    /** Leaving Burst: later photos get plain names. */
    fun endBurst() {
        burstTag = null
        burstFolder = null
        burstNumbers.clear()
    }

    fun baseName(wallClockMillis: Long): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(wallClockMillis))

    fun baseName(captureTime: CaptureTime): String =
        "RAWR_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = SimpleTimeZone(captureTime.utcOffsetMinutes * 60_000, "CaptureTime")
        }.format(Date(captureTime.wallClockUnixMillis))
}
