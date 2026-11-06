package com.rawr.camera.settings.preferences

import com.rawr.camera.settings.model.CompanionMode
import com.rawr.camera.settings.model.LensOption
import com.rawr.camera.settings.model.LensOptionChoice
import com.rawr.camera.settings.model.LensProfile
import com.rawr.camera.settings.model.VendorKey
import com.rawr.camera.settings.model.VendorKeyScope
import com.rawr.camera.settings.model.VendorKeyType
import com.rawr.camera.settings.model.lockedRows
import com.rawr.camera.settings.model.optionSummary
import com.rawr.camera.settings.model.selectOption
import com.rawr.camera.settings.model.validateLensOptions
import com.rawr.camera.settings.model.validateLensProfile
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LensOptionsCodecTest {
    private fun aperture(f: Double) = LensOptionChoice(
        "f/$f",
        listOf(
            VendorKey("com.xiaomi.lens.aperture", VendorKeyScope.Request, VendorKeyType.Float, listOf(f)),
            VendorKey("com.xiaomi.lens.apertureMode", VendorKeyScope.Request, VendorKeyType.Int32, listOf(1.0))
        )
    )
    private val modeKey = "org.codeaurora.qcamera3.sensor_meta_data.current_mode"
    private val fiftyPatch = JSONObject("""{
        "camera": {"id": "2"},
        "streams": {"raw": {"format": "RAW16", "width": 8192, "height": 6144, "highResLive": true,
                            "buffers": {"usage": "0x100003"}},
                    "companion": {"mode": "fixed", "width": 640, "height": 480, "format": "private", "targeted": false}},
        "session": {"template": "still", "frameRate": 30, "startupDelayMs": 500}
    }""").toString()
    private val mode = LensOption(
        "Mode",
        listOf(
            LensOptionChoice("Normal"),
            LensOptionChoice("DCG", listOf(VendorKey(modeKey, VendorKeyScope.Request, VendorKeyType.Int32, listOf(7.0)))),
            LensOptionChoice(
                "50MP",
                listOf(VendorKey("xiaomi.remosaic.enabled", VendorKeyScope.SessionAndRequest, VendorKeyType.Byte, listOf(1.0))),
                fiftyPatch
            )
        )
    )
    private val main = LensProfile("1x", "0").copy(
        vendorKeys = listOf(VendorKey("xiaomi.app.module", VendorKeyScope.Request, VendorKeyType.Int32, listOf(167.0))),
        options = listOf(LensOption("Aperture", listOf(aperture(1.67), aperture(2.8), aperture(4.0))), mode)
    )

    @Test
    fun resolvedLensCarriesSelectedKeysAndKeepsItsId() {
        val lens = LensOptionsCodec.resolve(main.selectOption(0, 1).selectOption(1, 1))
        assertEquals("1x", lens.name)
        assertTrue(lens.options.isEmpty())
        assertEquals("0", lens.camera.id)
        val byTag = lens.vendorKeys.associateBy { it.tag }
        assertEquals(listOf(2.8), byTag.getValue("com.xiaomi.lens.aperture").values)
        assertEquals(listOf(7.0), byTag.getValue(modeKey).values)
        assertEquals(listOf(167.0), byTag.getValue("xiaomi.app.module").values)
        assertEquals("f/2.8 · DCG", main.selectOption(0, 1).selectOption(1, 1).optionSummary())
    }

    @Test
    fun choiceKeyReplacesLensKeyInTheSameSlot() {
        val lens = main.copy(vendorKeys = main.vendorKeys +
            VendorKey("com.xiaomi.lens.aperture", VendorKeyScope.Request, VendorKeyType.Float, listOf(9.9)))
        val keys = LensOptionsCodec.resolve(lens).vendorKeys.filter { it.tag == "com.xiaomi.lens.aperture" }
        assertEquals(listOf(listOf(1.67)), keys.map { it.values })
    }

    @Test
    fun patchSwitchesCameraAndStreams() {
        val lens = LensOptionsCodec.resolve(main.selectOption(1, 2))
        assertEquals("2", lens.camera.id)
        assertEquals(8192, lens.streams.raw.choice.width)
        assertEquals(6144, lens.streams.raw.choice.height)
        assertTrue(lens.streams.raw.choice.highResLive)
        assertEquals("0x100003", lens.streams.raw.buffers.usage)
        assertEquals(CompanionMode.Fixed, lens.streams.companion.mode)
        assertEquals("private", lens.streams.companion.format)
        assertFalse(lens.streams.companion.targeted)
        assertEquals(true, lens.session.stillTemplate)
        assertEquals(30, lens.session.frameRate)
        assertEquals(500, lens.session.startupDelayMs)
        assertTrue(lens.vendorKeys.any { it.tag == "xiaomi.remosaic.enabled" && it.scope == VendorKeyScope.SessionAndRequest })
        assertTrue(validateLensProfile(lens, emptyList()).isEmpty())
        // The stored lens is unchanged.
        assertEquals("0", main.camera.id)
    }

    @Test
    fun optionsRoundTripThroughSettingsAndBackups() {
        val encoded = LensProfileCodec.encode(listOf(main.selectOption(0, 2)))
        val decoded = LensProfileCodec.decode(encoded)!!.single()
        assertEquals(2, decoded.options[0].selected)
        assertEquals(main.options.map { o -> o.choices.map { it.label } }, decoded.options.map { o -> o.choices.map { it.label } })
        assertEquals(encoded, LensProfileCodec.encode(listOf(decoded)))
        val backup = LensProfileCodec.decodeBackup(encoded).single()
        assertEquals(LensOptionsCodec.resolve(decoded), LensOptionsCodec.resolve(backup))
    }

    @Test
    fun cameraFormHasNoOptions() {
        val native = JSONObject(LensProfileCodec.encode(listOf(LensOptionsCodec.resolve(main)), dcpDir = java.io.File("dcp")))
        assertFalse(native.getJSONArray("lenses").getJSONObject(0).has("options"))
    }

    @Test
    fun backupRejectsAChoiceThatBreaksTheLens() {
        val lens = JSONObject(LensProfileCodec.encode(listOf(main))).getJSONArray("lenses").getJSONObject(0)
        lens.getJSONArray("options").getJSONObject(1).getJSONArray("choices").getJSONObject(2)
            .getJSONObject("patch").getJSONObject("session").put("frameRate", 9999)
        assertFailsWith<IllegalArgumentException> {
            LensProfileCodec.decodeBackup("""{"schema":2,"lenses":[$lens]}""")
        }
    }

    @Test
    fun editorTextValidates() {
        assertTrue(LensOptionsCodec.fromEditorText(LensOptionsCodec.toEditorText(main.options)).isSuccess)
        assertTrue(LensOptionsCodec.fromEditorText("[]").getOrThrow().isEmpty())
        assertTrue(LensOptionsCodec.fromEditorText("""[{"name":"A","choices":[{"nolabel":1}]}]""").isFailure)
        assertTrue(LensOptionsCodec.fromEditorText("not json").isFailure)
        assertFalse(validateLensOptions(listOf(LensOption("A", listOf(LensOptionChoice("x"), LensOptionChoice("X"))))).isEmpty())
    }

    private val locking = main.copy(options = listOf(
        LensOption("Aperture", listOf(aperture(1.67), LensOptionChoice("f/2"), aperture(4.0)), selected = 2),
        mode.copy(choices = mode.choices.mapIndexed { i, c -> if (i == 2) c.copy(locks = mapOf("Aperture" to "f/2")) else c })
    ))

    @Test
    fun lockedRowRunsWithItsLockedChoiceAndKeepsItsSelection() {
        val fifty = locking.selectOption(1, 2)
        val resolved = LensOptionsCodec.resolve(fifty)
        assertFalse(resolved.vendorKeys.any { it.tag == "com.xiaomi.lens.aperture" })
        assertEquals(mapOf(0 to 1), fifty.options.lockedRows(fifty.options.map { it.selected }))
        assertEquals("f/2 · 50MP", fifty.optionSummary())
        // Picking a locked row switches the locking row back to its first choice.
        assertEquals("f/1.67 · Normal", fifty.selectOption(0, 0).optionSummary())
        // Its own selection (f/4) comes back with another mode.
        val normal = fifty.selectOption(1, 0)
        assertEquals("f/4.0 · Normal", normal.optionSummary())
        assertEquals(listOf(4.0), LensOptionsCodec.resolve(normal).vendorKeys.first { it.tag == "com.xiaomi.lens.aperture" }.values)
    }

    @Test
    fun toggleRowsRoundTripAndNeedTwoChoices() {
        val lens = main.copy(options = listOf(LensOption("DCG", listOf(LensOptionChoice("Off"), LensOptionChoice("On")), toggle = true)))
        assertTrue(LensProfileCodec.decodeBackup(LensProfileCodec.encode(listOf(lens))).single().options.single().toggle)
        assertFalse(validateLensOptions(listOf(LensOption("DCG", listOf(LensOptionChoice("On")), toggle = true))).isEmpty())
    }

    @Test
    fun locksRoundTripAndValidate() {
        val decoded = LensProfileCodec.decodeBackup(LensProfileCodec.encode(listOf(locking))).single()
        assertEquals(mapOf("Aperture" to "f/2"), decoded.options[1].choices[2].locks)
        assertTrue(validateLensOptions(locking.options).isEmpty())
        val bad = locking.options.mapIndexed { i, o ->
            if (i == 1) o.copy(choices = o.choices.map { it.copy(locks = mapOf("Aperture" to "f/9")) }) else o
        }
        assertFalse(validateLensOptions(bad).isEmpty())
    }

    private val video = LensProfile("1xV", "2").copy(
        streams = com.rawr.camera.settings.model.LensStreams().let { st ->
            st.copy(raw = st.raw.copy(choice = st.raw.choice.copy(width = 4096, height = 2304)))
        },
        options = listOf(LensOption("Aperture", listOf(aperture(1.67), LensOptionChoice("f/2"), aperture(2.8)), selected = 1))
    )

    @Test
    fun videoLensRunsInVideoModeUnderTheLensIdAndIsHidden() {
        val wide = main.copy(videoLens = "1xV").selectOption(0, 1)
        val lenses = listOf(LensProfile("0.5x", "3"), wide, video)
        val photo = LensOptionsCodec.forCamera(lenses, video = false)
        assertEquals(listOf("0.5x", "1x"), photo.map { it.name })
        assertEquals("0", photo[1].camera.id)
        val recording = LensOptionsCodec.forCamera(lenses, video = true)
        assertEquals(listOf("0.5x", "1x"), recording.map { it.name })
        assertEquals("2", recording[1].camera.id)
        assertEquals(2304, recording[1].streams.raw.choice.height)
        // f/2.8 on 1x carries over to the video lens's f/2.8; its Mode row has no counterpart there.
        assertEquals(listOf(2.8), recording[1].vendorKeys.first { it.tag == "com.xiaomi.lens.aperture" }.values)
        assertFalse(recording[1].vendorKeys.any { it.tag == modeKey })
    }

    @Test
    fun videoLensRoundTripsAndValidates() {
        val wide = main.copy(videoLens = "1xV")
        val decoded = LensProfileCodec.decodeBackup(LensProfileCodec.encode(listOf(wide, video)))
        assertEquals("1xV", decoded[0].videoLens)
        assertTrue(validateLensProfile(wide, listOf(video)).isEmpty())
        assertFalse(validateLensProfile(wide.copy(videoLens = "9x"), listOf(video)).isEmpty())
        assertFalse(validateLensProfile(wide.copy(videoLens = "1x"), listOf(video)).isEmpty())
        val camera = JSONObject(LensProfileCodec.encode(listOf(wide), dcpDir = java.io.File("dcp")))
        assertFalse(camera.getJSONArray("lenses").getJSONObject(0).has("videoLens"))
    }

    @Test
    fun mergePatchFollowsRfc7386() {
        val merged = LensOptionsCodec.mergePatch(
            JSONObject("""{"a":{"b":1,"c":2},"d":[1,2],"e":3}"""),
            JSONObject("""{"a":{"b":9},"d":[5],"e":null}""")
        ) as JSONObject
        assertEquals(9, merged.getJSONObject("a").getInt("b"))
        assertEquals(2, merged.getJSONObject("a").getInt("c"))
        assertEquals(1, merged.getJSONArray("d").length())
        assertFalse(merged.has("e"))
    }
}
