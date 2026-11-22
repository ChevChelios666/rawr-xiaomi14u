package com.rawr.camera.integration

import com.rawr.camera.settings.model.LensProfile
import com.rawr.camera.settings.model.LensOption
import com.rawr.camera.settings.model.LensOptionChoice
import com.rawr.camera.settings.preferences.LensProfileCodec
import com.rawr.camera.settings.preferences.BundledDcpProfiles
import org.json.JSONObject

data class DeviceCameraProfile(
    val id: String,
    val name: String,
    val description: String,
    val isDefault: Boolean,
    val lenses: List<LensProfile>
)

/** X14U 5x Lab: keep a stable 12 MP finder; FullRes runs through the proven run-as shot handover. */
private fun lab5xOptions(profileId: String, lenses: List<LensProfile>): List<LensProfile> {
    if (profileId != "x14u_5x_lab") return lenses
    return lenses.map { lens ->
        if (lens.name != "5x12") lens
        else lens.copy(
            name = "5x",
            options = listOf(
                LensOption(
                    name = "FullRes",
                    selected = 0,
                    choices = listOf(
                        LensOptionChoice(label = "12 MP"),
                        LensOptionChoice(label = "900D", runAs = "5x_900D"),
                        LensOptionChoice(label = "900E", runAs = "5x_900E")
                    )
                )
            )
        )
    }
}

/** This device's built-in lenses (native built-in camera profile); the Lens settings start from these. */
object DeviceLensDefaults {
    val profiles: List<DeviceCameraProfile> by lazy {
        NativePreviewEngine().deviceCameraProfiles().toList().chunked(5).map { record ->
            DeviceCameraProfile(record[0], record[1], record[2], record[3].toBoolean(),
                lab5xOptions(record[0], BundledDcpProfiles.defaults(record[0], LensProfileCodec.decode(record[4]).orEmpty())))
        }
    }

    val lenses: List<LensProfile> by lazy {
        val raw = NativePreviewEngine().builtInCameraProfile()
        lab5xOptions(JSONObject(raw).optString("id"), BundledDcpProfiles.defaults(JSONObject(raw).optString("id"), LensProfileCodec.decode(raw).orEmpty()))
    }
}
