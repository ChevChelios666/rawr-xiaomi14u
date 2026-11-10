package com.rawr.camera.settings.preferences

import com.rawr.camera.settings.model.ModeLook

/** Test access to the internal per-mode Look codec (AgX values left empty). */
object ModeLookTestAccess {
    fun roundTrip(looks: Map<String, ModeLook>): Map<String, ModeLook> =
        ModeLookCodec.decode(ModeLookCodec.encode(looks) { "" }) { emptyMap() }
}
