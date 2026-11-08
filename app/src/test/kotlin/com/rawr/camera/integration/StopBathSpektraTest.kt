package com.rawr.camera.integration

import com.rawr.camera.settings.model.FilmSimLook
import com.rawr.camera.settings.model.FilmStocks
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopBathSpektraTest {
    @Test
    fun blockNamesStocksAndKeepsTheSettings() {
        val look = FilmSimLook(film = 6, paper = 3, filmFormat = 4, filmExposureEv = 0.5f)
        val block = StopBathSpektra.block(look, JSONObject().put("filmExposureEv", 0.5))
        assertEquals("Kodak Gold 200", block.getString("film"))
        assertEquals("kodak_gold_200", block.getString("filmId"))
        assertEquals("kodak_supra_endura", block.getString("printId"))
        assertEquals("std35", block.getString("format"))
        assertEquals(0.5, block.getJSONObject("settings").getDouble("filmExposureEv"))
        assertTrue(block.getBoolean("enabled"))
        assertEquals(false, block.getJSONObject("settings").getBoolean("colorAdaptation"))
        assertEquals(true, block.getJSONObject("settings").getBoolean("colorAdaptationCurveSmoothing"))
    }

    @Test
    fun everyStockHasAnId() {
        FilmStocks.films.indices.forEach { assertTrue(StopBathSpektra.block(FilmSimLook(film = it), JSONObject()).has("filmId")) }
        FilmStocks.papers.indices.forEach { assertTrue(StopBathSpektra.block(FilmSimLook(paper = it), JSONObject()).has("printId")) }
    }
}
