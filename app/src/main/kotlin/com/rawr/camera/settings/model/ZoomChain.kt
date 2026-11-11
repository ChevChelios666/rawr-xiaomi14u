package com.rawr.camera.settings.model

/**
 * X14U digital zoom: the lenses pinch and the zoom ruler move between (a separate sensor each), by zoom factor.
 * A lens on the same sensor as a wider one (in-sensor zoom like the 2x, or 50 MP) is not part of the chain: it stays
 * a button of its own when enabled, and zooming crops the wider lens instead.
 */
object ZoomChain {
    const val MAX_ZOOM = 10f
    private val factorPattern = Regex("""^(\d+(?:\.\d+)?)x$""", RegexOption.IGNORE_CASE)

    /** The zoom factor a lens name states ("3.2x" -> 3.2); null for other names (Front, 1xV, 50MP). */
    fun factorOf(name: String): Float? = factorPattern.matchEntire(name.trim())?.groupValues?.get(1)?.toFloatOrNull()

    private fun sensorOf(lens: LensProfile): String = lens.camera.physical.ifEmpty { lens.camera.id }

    /** Enabled rear lenses with a factor, one per sensor (the widest), sorted by factor. */
    fun of(lenses: List<LensProfile>): List<Pair<String, Float>> =
        lenses.filter { it.enabled }
            .mapNotNull { lens -> factorOf(lens.name)?.let { Triple(lens.name, it, sensorOf(lens)) } }
            .groupBy { it.third }
            .map { (_, onSensor) -> onSensor.minBy { it.second } }
            .map { it.first to it.second }
            .sortedBy { it.second }

    /** Lenses that crop a wider lens's sensor (left out of the chain). */
    fun inSensorCrops(lenses: List<LensProfile>): Set<String> {
        val withFactor = lenses.mapNotNull { lens -> factorOf(lens.name)?.let { lens to it } }
        return withFactor.filter { (lens, f) ->
            withFactor.any { (other, g) -> other !== lens && sensorOf(other) == sensorOf(lens) && g < f }
        }.map { it.first.name }.toSet()
    }

    /** For a total zoom: the chain lens to use (the longest not above it) and the crop on top of it. */
    fun resolve(chain: List<Pair<String, Float>>, zoom: Float): Pair<String, Float>? {
        if (chain.isEmpty()) return null
        val lens = chain.lastOrNull { it.second <= zoom + 1e-3f } ?: chain.first()
        return lens.first to (zoom / lens.second).coerceAtLeast(1f)
    }
}
