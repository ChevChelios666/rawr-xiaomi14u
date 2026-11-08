package com.rawr.camera.integration

import com.rawr.camera.settings.model.FilmSimLook
import org.json.JSONObject

/**
 * X14U build: the film recipe in the layout StopBath reads from a DNG's ImageDescription (the layout of
 * Unspektrawesome's recipe: `unspektrawesomeRecipe.capture.metadata.spektra`). Rawr and StopBath both run
 * spektrafilm, so the settings are Rawr's own spektrafilm field names (as in SpektraParameters.h); the stocks and the
 * format also carry spektrafilm's ids. The native DNG writer wraps this block (see DngExif.cpp).
 */
internal object StopBathSpektra {
    /** spektrafilm profile ids and names, in FilmStocks.films order (native profile table order). */
    private val films = listOf(
        "kodak_ektar_100" to "Kodak Ektar 100", "kodak_portra_160" to "Kodak Portra 160",
        "kodak_portra_400" to "Kodak Portra 400", "kodak_portra_800" to "Kodak Portra 800",
        "kodak_portra_800_push1" to "Kodak Portra 800 (Push 1)", "kodak_portra_800_push2" to "Kodak Portra 800 (Push 2)",
        "kodak_gold_200" to "Kodak Gold 200", "kodak_ultramax_400" to "Kodak Ultramax 400",
        "kodak_vision3_50d" to "Kodak Vision3 50D", "kodak_vision3_250d" to "Kodak Vision3 250D",
        "kodak_verita_200d" to "Kodak Verita 200D", "kodak_vision3_200t" to "Kodak Vision3 200T",
        "kodak_vision3_500t" to "Kodak Vision3 500T", "fujifilm_pro_400h" to "Fujifilm Pro 400H",
        "fujifilm_c200" to "Fujifilm C200", "fujifilm_xtra_400" to "Fujifilm X-Tra 400",
        "kodak_ektachrome_100" to "Kodak Ektachrome 100", "kodak_kodachrome_64" to "Kodak Kodachrome 64",
        "fujifilm_velvia_100" to "Fujifilm Velvia 100", "fujifilm_provia_100f" to "Fujifilm Provia 100F"
    )

    /** In FilmStocks.papers order. */
    private val papers = listOf(
        "kodak_endura_premier" to "Kodak Professional Endura Premier",
        "kodak_ultra_endura" to "Kodak Professional Ultra Endura",
        "kodak_ektacolor_edge" to "Kodak Ektacolor Edge",
        "kodak_supra_endura" to "Kodak Professional Supra Endura",
        "kodak_portra_endura" to "Kodak Professional Portra Endura",
        "fujifilm_crystal_archive_typeii" to "Fujifilm Crystal Archive Type II",
        "kodak_2383" to "Kodak Vision 2383",
        "kodak_2393" to "Kodak Vision Premier 2393"
    )

    /** spektrafilm FilmFormat order (SpektraParameters.h). */
    private val formats = listOf("std8", "super8", "std16", "super16", "std35", "super35", "std65", "imax70")

    /** The `spektra` block: enabled, stocks, format and every film setting under its spektrafilm name. */
    fun block(look: FilmSimLook, settings: JSONObject): JSONObject = JSONObject().apply {
        put("enabled", true)
        put("source", "rawr")
        films.getOrNull(look.film)?.let { (id, name) -> put("film", name); put("filmId", id) }
        papers.getOrNull(look.paper)?.let { (id, name) -> put("print", name); put("printId", id) }
        formats.getOrNull(look.filmFormat)?.let { put("format", it) }
        put("settings", JSONObject(settings.toString()).apply {
            // Film-engine switches Rawr never changes: the native FilmLook defaults (spektrafilm RenderParams),
            // written so StopBath doesn't have to assume them.
            put("colorAdaptation", false)
            put("colorAdaptationInputCompression", true)
            put("colorAdaptationCurveSmoothing", true)
            put("colorAdaptationOutputLightnessCompression", true)
            put("colorAdaptationOutputChromaCompression", true)
            put("autoExposure", false)
        })
    }
}
