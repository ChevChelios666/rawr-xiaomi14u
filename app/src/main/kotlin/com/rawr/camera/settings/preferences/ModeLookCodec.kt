package com.rawr.camera.settings.preferences

import com.rawr.camera.settings.model.AgxPreset
import com.rawr.camera.settings.model.AgxValues
import com.rawr.camera.settings.model.ColorRenderProfile
import com.rawr.camera.settings.model.ModeLook
import com.rawr.camera.settings.model.ProfileTone
import com.rawr.camera.settings.model.RawrAeSettings
import org.json.JSONObject

/** X14U: the per-mode Look / Exposure sets as one JSON preference (lenient: an unreadable mode is dropped). */
internal object ModeLookCodec {
    private inline fun <reified T : Enum<T>> enumOf(raw: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default

    private fun tone(t: ProfileTone) = JSONObject()
        .put("renderExposure", t.renderExposure.toDouble()).put("blacks", t.blacks.toDouble())
        .put("shadows", t.shadows.toDouble()).put("contrast", t.contrast.toDouble())
        .put("midtones", t.midtones.toDouble()).put("highlights", t.highlights.toDouble())
        .put("whites", t.whites.toDouble()).put("saturation", t.saturation.toDouble())
        .put("vibrance", t.vibrance.toDouble())

    private fun tone(o: JSONObject?): ProfileTone {
        val d = ProfileTone.Neutral
        if (o == null) return d
        fun f(k: String, v: Float) = o.optDouble(k, v.toDouble()).toFloat().takeIf { it.isFinite() } ?: v
        return ProfileTone(
            f("renderExposure", d.renderExposure), f("blacks", d.blacks), f("shadows", d.shadows),
            f("contrast", d.contrast), f("midtones", d.midtones), f("highlights", d.highlights),
            f("whites", d.whites), f("saturation", d.saturation), f("vibrance", d.vibrance)
        )
    }

    fun encode(looks: Map<String, ModeLook>, agx: (Map<AgxPreset, AgxValues>) -> String): String =
        JSONObject().apply {
            looks.toSortedMap().forEach { (mode, l) ->
                put(mode, JSONObject()
                    .put("profile", l.colorRenderProfile.name)
                    .put("lut", l.selectedUserLutProfileId ?: JSONObject.NULL)
                    .put("agxPreset", l.agxPreset.name)
                    .put("agxLook", l.selectedAgxLookId ?: JSONObject.NULL)
                    .put("rawrTone", tone(l.rawrBaseTone)).put("srgbTone", tone(l.srgbTone))
                    .put("rec709Tone", tone(l.rec709Tone)).put("agxTone", tone(l.agxTone))
                    .put("agxValues", agx(l.agxValues))
                    .put("hlEnabled", l.photoHighlightEnabled).put("hlMethod", l.photoHighlightMethod)
                    .put("hlThreshold", l.photoHighlightThreshold.toDouble())
                    .put("hlCompression", l.photoHighlightCompression.toDouble())
                    .put("film", l.filmSimEnabled).put("filmLook", FilmSimCodec.encodeLook(l.filmSimLook))
                    .put("filmPreset", l.selectedFilmPresetId ?: JSONObject.NULL)
                    .put("ae", JSONObject()
                        .put("enabled", l.rawrAe.enabled).put("nightLook", l.rawrAe.nightLook.name)
                        .put("maxShutter", l.rawrAe.maxShutter.name).put("balance", l.rawrAe.balance.name)
                        .put("digitalGain", l.rawrAe.digitalGain.name).put("speed", l.rawrAe.speed.name)
                        .put("metering", l.rawrAe.metering.name)))
            }
        }.toString()

    fun decode(raw: String?, agx: (String?) -> Map<AgxPreset, AgxValues>): Map<String, ModeLook> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val d = RawrAeSettings()
        return root.keys().asSequence().mapNotNull { mode ->
            runCatching {
                val o = root.getJSONObject(mode)
                val ae = o.optJSONObject("ae") ?: JSONObject()
                mode to ModeLook(
                    colorRenderProfile = enumOf(o.optString("profile"), ColorRenderProfile.RawrBase),
                    selectedUserLutProfileId = o.optString("lut").takeIf { !o.isNull("lut") && it.isNotEmpty() },
                    agxPreset = enumOf(o.optString("agxPreset"), AgxPreset.Rawr),
                    selectedAgxLookId = o.optString("agxLook").takeIf { !o.isNull("agxLook") && it.isNotEmpty() },
                    rawrBaseTone = tone(o.optJSONObject("rawrTone")),
                    srgbTone = tone(o.optJSONObject("srgbTone")),
                    rec709Tone = tone(o.optJSONObject("rec709Tone")),
                    agxTone = tone(o.optJSONObject("agxTone")),
                    agxValues = agx(o.optString("agxValues")),
                    photoHighlightEnabled = o.optBoolean("hlEnabled", true),
                    photoHighlightMethod = o.optInt("hlMethod", 1).coerceIn(0, 1),
                    photoHighlightThreshold = o.optDouble("hlThreshold", 1.0).toFloat().coerceIn(.5f, 2f),
                    photoHighlightCompression = o.optDouble("hlCompression", 45.0).toFloat().coerceIn(0f, 300f),
                    filmSimEnabled = o.optBoolean("film", false),
                    filmSimLook = FilmSimCodec.decodeLook(o.optString("filmLook")) ?: return@runCatching null,
                    selectedFilmPresetId = o.optString("filmPreset").takeIf { !o.isNull("filmPreset") && it.isNotEmpty() },
                    rawrAe = RawrAeSettings(
                        enabled = ae.optBoolean("enabled", d.enabled),
                        nightLook = enumOf(ae.optString("nightLook"), d.nightLook),
                        maxShutter = enumOf(ae.optString("maxShutter"), d.maxShutter),
                        balance = enumOf(ae.optString("balance"), d.balance),
                        digitalGain = enumOf(ae.optString("digitalGain"), d.digitalGain),
                        speed = enumOf(ae.optString("speed"), d.speed),
                        metering = enumOf(ae.optString("metering"), d.metering)
                    )
                )
            }.getOrNull()
        }.toMap()
    }
}
