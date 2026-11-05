package com.rawr.camera.settings.preferences

import com.rawr.camera.settings.model.LensProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The X14U backup converted to lens options (resources/x14u/new.json) must open every old separate lens
 * (resources/x14u/old.json, the working 0.5.1/Debug lenses) with the same camera, streams, session, levels and keys.
 */
class X14uBackupConversionTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/x14u/$name")).readText()
    private val old = LensProfileCodec.decodeBackup(resource("old.json")).associateBy { it.name }
    private val new = LensProfileCodec.decodeBackup(resource("new.json")).associateBy { it.name }

    private fun LensProfile.normalized(name: String) =
        copy(name = name, vendorKeys = vendorKeys.sortedWith(compareBy({ it.tag }, { it.scope.ordinal })), options = emptyList())

    @Test
    fun everyOldMainLensIsAnOptionCombination() {
        // Aperture: f/1.67, f/2 (no keys, the default), f/2.8, f/4. Mode: Normal, DCG, ISZ, 50MP.
        val picks = mapOf(
            "1x" to listOf(1, 0), "1x f1.67" to listOf(0, 0), "1x f2.8" to listOf(2, 0), "1x f4" to listOf(3, 0),
            "DCG" to listOf(1, 1), "DCG f1.67" to listOf(0, 1), "DCG f2.8" to listOf(2, 1), "DCG f4" to listOf(3, 1),
            "2x" to listOf(1, 2), "2x f1.67" to listOf(0, 2), "2x f2.8" to listOf(2, 2), "2x f4" to listOf(3, 2),
            "1x 50MP" to listOf(1, 3)
        )
        val main = new.getValue("1x")
        assertEquals(1, main.options[0].selected, "f/2 is the default aperture")
        for ((oldName, pick) in picks) {
            assertEquals(
                old.getValue(oldName).normalized(oldName),
                LensOptionsCodec.resolve(main, pick).normalized(oldName),
                oldName
            )
        }
    }

    @Test
    fun fiftyMegapixelsLocksApertureToF2() {
        // Tested on the phone: aperture keys do nothing at 50 MP, so 50MP runs at f/2 whatever Aperture says.
        val main = new.getValue("1x")
        val fifty = old.getValue("1x 50MP").normalized("1x 50MP")
        for (aperture in 0..3) {
            assertEquals(fifty, LensOptionsCodec.resolve(main, listOf(aperture, 3)).normalized("1x 50MP"), "aperture $aperture")
        }
    }

    @Test
    fun otherLensesAreUnchanged() {
        for (name in listOf("0.5x", "3.2x", "5x", "Front")) assertEquals(old.getValue(name), new.getValue(name), name)
    }
}
