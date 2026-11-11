package com.rawr.camera.settings.model

import com.rawr.camera.settings.preferences.ModeLookTestAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeLooksTest {
    private val base = SettingsCatalog.initialState().values.copy(x14uProUi = false)

    @Test
    fun aModeNotVisitedYetStartsAsACopy() {
        val pro = base.copy(x14uProUi = true).withModeLookSwitch(base)
        assertEquals(base.modeLook(), pro.modeLook())
        assertEquals(setOf(ModeLooks.PHOTO), pro.x14uModeLooks.keys)
    }

    @Test
    fun eachModeKeepsItsOwnLook() {
        val pro = base.copy(x14uProUi = true).withModeLookSwitch(base)
            .copy(filmSimEnabled = true, colorRenderProfile = ColorRenderProfile.AgX, rawrAe = RawrAeSettings(enabled = false))
        val photo = pro.copy(x14uProUi = false).withModeLookSwitch(pro)
        assertFalse(photo.filmSimEnabled)
        assertEquals(ColorRenderProfile.RawrBase, photo.colorRenderProfile)
        assertTrue(photo.rawrAe.enabled)
        val proAgain = photo.copy(x14uProUi = true).withModeLookSwitch(photo)
        assertTrue(proAgain.filmSimEnabled)
        assertEquals(ColorRenderProfile.AgX, proAgain.colorRenderProfile)
        assertFalse(proAgain.rawrAe.enabled)
    }

    @Test
    fun videoIsItsOwnMode() {
        val video = base.copy(captureModeId = "video").withModeLookSwitch(base)
        assertEquals(ModeLooks.VIDEO, video.lookMode())
        assertEquals(setOf(ModeLooks.PHOTO), video.x14uModeLooks.keys)
    }

    @Test
    fun codecRoundTrips() {
        val look = base.copy(
            filmSimEnabled = true,
            colorRenderProfile = ColorRenderProfile.SRgb,
            srgbTone = ProfileTone(contrast = 12f, saturation = 1.2f),
            photoHighlightCompression = 80f,
            rawrAe = RawrAeSettings(metering = RawrMetering.entries.last(), nightLook = RawrNightLook.entries.last())
        ).modeLook()
        val looks = mapOf(ModeLooks.PRO to look)
        assertEquals(looks, ModeLookTestAccess.roundTrip(looks))
    }
}
