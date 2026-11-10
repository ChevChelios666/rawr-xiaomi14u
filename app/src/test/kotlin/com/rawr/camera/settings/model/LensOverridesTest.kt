package com.rawr.camera.settings.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LensOverridesTest {
    private val base = SettingsCatalog.initialState().values
    private val lenses = listOf(
        LensProfile("1x", "2").copy(
            overrides = mapOf("ae.nightLook" to RawrNightLook.Off.name, "tone.contrast" to "20"),
            options = listOf(LensOption("50 mp", listOf(LensOptionChoice("Off"), LensOptionChoice("On", runAs = "50MP")),
                toggle = true))
        ),
        LensProfile("3.2x", "4"),
        LensProfile("50MP", "2").copy(overrides = mapOf("detail.sharpening" to "Sharpest"))
    )
    private val settings = base.copy(lensProfiles = lenses, lastLensId = "1x",
        rawrAe = base.rawrAe.copy(nightLook = RawrNightLook.Natural))

    @Test
    fun everySpecReadsBackWhatItWrites() {
        LensOverrides.all.forEach { spec ->
            val values = when (val kind = spec.kind) {
                is LensOverrideKind.Choice -> kind.values.map { it.first }
                is LensOverrideKind.Number -> listOf(kind.min, kind.max).map { v ->
                    if (kind.decimals == 0) Math.round(v).toString() else v.toString()
                }
                LensOverrideKind.Toggle -> listOf("true", "false")
            }
            values.forEach { value ->
                assertEquals(value.toFloatOrNull() ?: value, spec.read(spec.write(base, value)).let { it.toFloatOrNull() ?: it },
                    "${spec.key} = $value")
            }
        }
    }

    @Test
    fun activeLensOverridesApplyAndOthersFollowGlobal() {
        val effective = settings.withLensOverrides()
        assertEquals(RawrNightLook.Off, effective.rawrAe.nightLook)
        assertEquals(20f, effective.activeProfileTone().contrast)
        val tele = settings.copy(lastLensId = "3.2x").withLensOverrides()
        assertEquals(RawrNightLook.Natural, tele.rawrAe.nightLook)
        assertEquals(0f, tele.activeProfileTone().contrast)
    }

    @Test
    fun runAsLensUsesItsOwnOverridesInPhotoOnly() {
        val fifty = settings.copy(lensProfiles = lenses.map { if (it.name == "1x") it.selectOption(0, 1) else it })
        assertEquals("50MP", fifty.overrideLens()?.name)
        val effective = fifty.withLensOverrides()
        assertEquals(CaptureSharpeningLevel.Sharpest, effective.photoCaptureSharpeningLevel)
        assertEquals(RawrNightLook.Natural, effective.rawrAe.nightLook)
        assertEquals("1x", fifty.copy(captureModeId = "video").overrideLens()?.name)
    }

    @Test
    fun captureEditsGoToTheOverrideWhenTheLensHasOne() {
        // Night Look is overridden on 1x: the edit lands on 1x, the global value stays.
        val night = settings.editThroughLens { it.copy(rawrAe = it.rawrAe.copy(nightLook = RawrNightLook.Bright)) }
        assertEquals(RawrNightLook.Natural, night.rawrAe.nightLook)
        assertEquals(RawrNightLook.Bright.name, night.lensProfiles!!.first().overrides["ae.nightLook"])
        // Metering isn't: the global value changes.
        val metering = settings.editThroughLens { it.copy(rawrAe = it.rawrAe.copy(metering = RawrMetering.Highlight)) }
        assertEquals(RawrMetering.Highlight, metering.rawrAe.metering)
        assertEquals(lenses.first().overrides, metering.lensProfiles!!.first().overrides)
    }

    @Test
    fun hdrPlusTapIsRememberedPerLens() {
        val on = settings.editThroughLens(setOf(LensOverrides.HDR_PLUS)) { it.copy(experimentalMultiframeEnabled = true) }
        assertFalse(on.experimentalMultiframeEnabled)
        assertEquals("true", on.lensProfiles!!.first().overrides[LensOverrides.HDR_PLUS])
        assertTrue(on.withLensOverrides().experimentalMultiframeEnabled)
        assertFalse(on.copy(lastLensId = "3.2x").withLensOverrides().experimentalMultiframeEnabled)
    }
}
