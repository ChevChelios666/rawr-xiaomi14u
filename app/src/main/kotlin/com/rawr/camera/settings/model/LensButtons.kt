package com.rawr.camera.settings.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * X14U lens pill (Settings → Lens → Lens buttons): label style, and per mode which lenses show and which digital
 * focal lengths are added. A digital stop is a crop of the next wider lens in the zoom chain.
 */
data class DigitalStop(val mm: Int, val on: Boolean = true)

data class LensButtonSet(
    /** Lens names left out of the pill in this mode. */
    val hidden: Set<String> = emptySet(),
    val digital: List<DigitalStop> = emptyList()
)

data class LensButtons(
    /** Labels as focal lengths ("23" over "mm") instead of zoom factors ("1x"). */
    val mmLabels: Boolean = false,
    val photo: LensButtonSet = LensButtonSet(),
    /** Null: the same as photo (until video is edited). */
    val video: LensButtonSet? = null
) {
    fun forMode(video: Boolean): LensButtonSet = if (video) this.video ?: photo else photo

    fun withSet(video: Boolean, set: LensButtonSet): LensButtons = if (video) copy(video = set) else copy(photo = set)

    fun encode(): String = JSONObject()
        .put("mm", mmLabels)
        .put("photo", encodeSet(photo))
        .apply { video?.let { put("video", encodeSet(it)) } }
        .toString()

    companion object {
        const val MIN_MM = 13
        const val MAX_MM = 240
        /** The main lens's 35 mm-equivalent focal length (1x). */
        const val MAIN_MM = 23f

        fun decode(text: String?): LensButtons {
            if (text.isNullOrBlank()) return LensButtons()
            return runCatching {
                val o = JSONObject(text)
                LensButtons(
                    mmLabels = o.optBoolean("mm", false),
                    photo = decodeSet(o.optJSONObject("photo")),
                    video = o.optJSONObject("video")?.let(::decodeSet)
                )
            }.getOrDefault(LensButtons())
        }

        /** 35 mm-equivalent focal length of a lens with this zoom factor (the X14U's own numbers where it has one). */
        fun mmOf(factor: Float): Int = when {
            abs(factor - .5f) < .02f -> 12
            abs(factor - 1f) < .02f -> 23
            abs(factor - 3.2f) < .05f -> 75
            abs(factor - 5f) < .15f -> 120
            else -> (factor * MAIN_MM).roundToInt()
        }

        private fun encodeSet(set: LensButtonSet): JSONObject = JSONObject()
            .put("hidden", JSONArray(set.hidden.sorted()))
            .put("digital", JSONArray(set.digital.map { JSONObject().put("mm", it.mm).put("on", it.on) }))

        private fun decodeSet(o: JSONObject?): LensButtonSet {
            if (o == null) return LensButtonSet()
            val hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val digital = o.optJSONArray("digital")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { d -> DigitalStop(d.optInt("mm").coerceIn(MIN_MM, MAX_MM), d.optBoolean("on", true)) }
                }
            } ?: emptyList()
            return LensButtonSet(hidden - "", digital.distinctBy { it.mm }.sortedBy { it.mm })
        }
    }
}

/** A digital stop on the pill: its focal length, the chain lens it crops and the total zoom (chain units). */
data class ResolvedStop(val mm: Int, val lens: String, val lensMm: Int, val crop: Float, val zoom: Float)

/** Resolves [mm] against the zoom chain (lens name to factor): the longest lens not above it. */
fun resolveDigitalStop(mm: Int, chain: List<Pair<String, Float>>): ResolvedStop? {
    if (chain.isEmpty()) return null
    val withMm = chain.map { (name, factor) -> Triple(name, factor, LensButtons.mmOf(factor)) }
    val source = withMm.lastOrNull { it.third <= mm } ?: return null
    if (source.third == mm) return null
    val crop = mm.toFloat() / source.third
    return ResolvedStop(mm, source.first, source.third, crop, source.second * crop)
}
