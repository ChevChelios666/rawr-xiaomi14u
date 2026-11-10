package com.rawr.camera.settings.preferences

import com.rawr.camera.settings.model.LensProfile
import com.rawr.camera.settings.model.selectOption
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
        copy(name = name, vendorKeys = vendorKeys.sortedWith(compareBy({ it.tag }, { it.scope.ordinal })), options = emptyList(),
            videoLens = null)

    // Aperture index: 0 f/1.67, 1 f/1.8, 2 f/2 (no keys, default), 3 f/2.4, 4 f/2.8, 5 f/3.2, 6 f/4.
    private val apertures = listOf(2 to "", 0 to " f1.67", 4 to " f2.8", 6 to " f4")

    @Test
    fun everyOldMainLensIsAnOptionCombination() {
        // 1x rows: Aperture, DCG (off/on), 50 mp (off/on).
        val main = new.getValue("1x")
        assertEquals(listOf("Aperture", "DCG", "50 mp"), main.options.map { it.name })
        assertEquals(2, main.options[0].selected, "f/2 is the default aperture")
        for ((aperture, suffix) in apertures) {
            for ((dcg, prefix) in listOf(0 to "1x", 1 to "DCG")) {
                val oldName = prefix + suffix
                assertEquals(
                    old.getValue(oldName).normalized(oldName),
                    LensOptionsCodec.resolve(main, listOf(aperture, dcg, 0)).normalized(oldName),
                    oldName
                )
            }
        }
    }

    @Test
    fun isZoomIsItsOwnLensWithTheSameApertures() {
        val isz = new.getValue("2x")
        assertEquals(listOf("Aperture"), isz.options.map { it.name })
        assertEquals(main7(), isz.options[0].choices.map { it.label })
        for ((aperture, suffix) in apertures) {
            val oldName = "2x$suffix"
            assertEquals(
                old.getValue(oldName).normalized(oldName),
                LensOptionsCodec.resolve(isz, listOf(aperture)).normalized(oldName),
                oldName
            )
        }
    }

    private fun main7() = listOf("f/1.67", "f/1.8", "f/2", "f/2.4", "f/2.8", "f/3.2", "f/4")

    @Test
    fun newAperturesCopyTheKeysWithTheirValue() {
        val main = new.getValue("1x")
        assertEquals(main7(), main.options[0].choices.map { it.label })
        val reference = LensOptionsCodec.resolve(main, listOf(4, 0, 0)).vendorKeys // f/2.8, tested
        for ((index, f) in listOf(1 to 1.8, 3 to 2.4, 5 to 3.2)) {
            val keys = LensOptionsCodec.resolve(main, listOf(index, 0, 0)).vendorKeys
            assertEquals(listOf(f), keys.first { it.tag == "com.xiaomi.lens.aperture" }.values)
            assertEquals(
                reference.filter { it.tag != "com.xiaomi.lens.aperture" },
                keys.filter { it.tag != "com.xiaomi.lens.aperture" }
            )
        }
    }

    @Test
    fun fiftyMegapixelsRunsAsTheHidden50MpLens() {
        // 50 mp On runs 1x as the hidden lens "50MP" (its own overrides); it locks Aperture to f/2 and DCG off.
        val main = new.getValue("1x")
        val fifty = old.getValue("1x 50MP").normalized("1x 50MP")
        assertEquals("50MP", main.options[2].choices[1].runAs)
        assertEquals(mapOf("Aperture" to "f/2", "DCG" to "Off"), main.options[2].choices[1].locks)
        val lenses = new.values.toList()
        for (aperture in 0..6) {
            val picked = main.copy(options = main.options.mapIndexed { i, o -> o.copy(selected = listOf(aperture, 0, 1)[i]) })
            val running = LensOptionsCodec.forCamera(lenses.map { if (it.name == "1x") picked else it }, video = false)
                .first { it.name == "1x" }
            assertEquals(fifty, running.copy(image = fifty.image).normalized("1x 50MP"), "aperture $aperture")
        }
        assertEquals(fifty, LensOptionsCodec.resolve(new.getValue("50MP")).copy(image = fifty.image).normalized("1x 50MP"))
    }

    @Test
    fun videoRunsAsTheHidden1xVLensWithItsDcgRow() {
        val lenses = new.values.toList()
        assertEquals("1xV", new.getValue("1x").videoLens)
        val video = LensOptionsCodec.forCamera(lenses, video = true)
        assertEquals(listOf("0.5x", "1x", "2x", "3.2x", "5x", "Front"), video.map { it.name })
        val main = video.first { it.name == "1x" }
        assertEquals("2", main.camera.id)
        assertEquals(4096 to 2304, main.streams.raw.choice.width to main.streams.raw.choice.height)
        val dcg = LensOptionsCodec.forCamera(lenses.map { if (it.name == "1x") it.selectOption(1, 1) else it }, video = true)
            .first { it.name == "1x" }
        assertEquals(4096 to 3072, dcg.streams.raw.choice.width to dcg.streams.raw.choice.height)
        assertEquals(listOf(7.0), dcg.vendorKeys.first { it.tag == "org.codeaurora.qcamera3.sensor_meta_data.current_mode" }.values)
    }

    @Test
    fun dcgAndFiftySwitchOver() {
        val main = new.getValue("1x")
        val dcg = main.selectOption(1, 1)
        val fifty = dcg.selectOption(2, 1)
        assertEquals(listOf(2, 0, 1), fifty.options.map { it.selected })
        assertEquals(listOf(2, 1, 0), fifty.selectOption(1, 1).options.map { it.selected })
    }

    @Test
    fun otherLensesAreUnchanged() {
        for (name in listOf("0.5x", "3.2x", "5x", "Front")) assertEquals(old.getValue(name), new.getValue(name), name)
        assertEquals(listOf("0.5x", "1x", "2x", "3.2x", "5x", "Front", "1xV", "50MP"), new.keys.toList())
        assertEquals(setOf("1xv", "50mp"), LensOptionsCodec.hiddenLensNames(new.values.toList()))
    }
}
