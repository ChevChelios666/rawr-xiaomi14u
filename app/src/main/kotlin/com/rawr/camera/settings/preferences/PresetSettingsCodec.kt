package com.rawr.camera.settings.preferences

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rawr.camera.settings.model.SettingsValues
import org.json.JSONObject

/**
 * X14U presets: a preset's settings are kept the way a settings backup keeps them (each persisted key with its
 * type), so an older preset decodes through the same migrations as stored settings. Libraries (lenses, LUTs, film
 * and AgX looks), the presets themselves and phone-only keys are left out; they always come from the live settings.
 */
class PresetSettingsCodec(context: Context, defaults: SettingsValues) {
    private val repository = SettingsPreferencesRepository(context.applicationContext, defaults)

    fun encode(values: SettingsValues): String {
        val json = JSONObject()
        repository.encodeForBackup(values).asMap().forEach { (key, value) ->
            if (key.name in LIVE_KEYS) return@forEach
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Float -> "float"
                is String -> "string"
                else -> return@forEach
            }
            json.put(key.name, JSONObject().put("type", type).put("value", value))
        }
        return json.toString()
    }

    /** [saved] over [current]'s libraries; null when it can't be read (or is from a newer version). */
    fun decode(saved: String, current: SettingsValues): SettingsValues? = runCatching {
        val json = JSONObject(saved)
        val p: MutablePreferences = mutablePreferencesOf()
        repository.encodeForBackup(current).asMap().forEach { (key, value) ->
            if (key.name !in LIVE_KEYS) return@forEach
            when (value) {
                is Boolean -> p[booleanPreferencesKey(key.name)] = value
                is Int -> p[intPreferencesKey(key.name)] = value
                is Float -> p[floatPreferencesKey(key.name)] = value
                is String -> p[stringPreferencesKey(key.name)] = value
            }
        }
        json.keys().forEach { name ->
            if (name in LIVE_KEYS) return@forEach
            val entry = json.optJSONObject(name) ?: return@forEach
            when (entry.optString("type")) {
                "boolean" -> p[booleanPreferencesKey(name)] = entry.getBoolean("value")
                "int" -> p[intPreferencesKey(name)] = entry.getInt("value")
                "float" -> p[floatPreferencesKey(name)] = entry.getDouble("value").toFloat()
                "string" -> p[stringPreferencesKey(name)] = entry.getString("value")
            }
        }
        repository.decodeBackup(p)
    }.getOrNull()

    private companion object {
        val LIVE_KEYS = setOf(
            "save_location_id", "last_lens_id", "debug_custom_gpu_driver_enabled", "debug_custom_gpu_driver_name",
            "lens_profiles", "bundled_dcp_version", "user_lut_profiles", "film_presets", "agx_looks",
            "x14u_presets", "x14u_active_preset"
        )
    }
}
