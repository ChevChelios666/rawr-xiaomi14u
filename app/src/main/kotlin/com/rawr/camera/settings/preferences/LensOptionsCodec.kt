package com.rawr.camera.settings.preferences

import com.rawr.camera.settings.model.LensOption
import com.rawr.camera.settings.model.LensOptionChoice
import com.rawr.camera.settings.model.LensProfile
import com.rawr.camera.settings.model.VendorKey
import com.rawr.camera.settings.model.effectivePicks
import com.rawr.camera.settings.model.validateLensOptions
import com.rawr.camera.settings.model.validateLensProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lens options (X14U build) in the lens JSON, and the lens the camera receives with the selected choices applied.
 *
 * Wire form, a lens's "options" array:
 * ```
 * "options": [
 *   { "name": "Aperture", "selected": 0, "choices": [
 *       { "label": "f/1.67", "vendorKeys": [ { "tag": "com.xiaomi.lens.aperture", "type": "float",
 *                                              "scope": "request", "values": [1.67] } ] },
 *       ... ] },
 *   { "name": "Mode", "selected": 0, "choices": [
 *       { "label": "Normal" },
 *       { "label": "50MP", "patch": { "camera": { "id": "2" }, "streams": { "raw": { ... } } } } ] }
 * ]
 * ```
 * The native camera never sees "options": [resolve] runs before the profile is sent.
 */
internal object LensOptionsCodec {

    fun encode(options: List<LensOption>): JSONArray = JSONArray().apply {
        options.forEach { option ->
            put(JSONObject()
                .put("name", option.name.trim())
                .put("selected", option.selected)
                .put("choices", JSONArray().apply {
                    option.choices.forEach { choice ->
                        put(JSONObject().put("label", choice.label.trim()).apply {
                            if (choice.vendorKeys.isNotEmpty()) put("vendorKeys", LensProfileCodec.encodeKeys(choice.vendorKeys))
                            choice.patch?.let { put("patch", JSONObject(it)) }
                            if (choice.locks.isNotEmpty()) put("locks", JSONObject().apply { choice.locks.forEach { (k, v) -> put(k, v) } })
                        })
                    }
                }))
        }
    }

    /** Lenient (settings): unreadable rows or choices are dropped, never the lens. */
    fun decode(array: JSONArray?): List<LensOption> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val choicesJson = o.getJSONArray("choices")
                val choices = (0 until choicesJson.length()).mapNotNull { j ->
                    runCatching { decodeChoice(choicesJson.getJSONObject(j)) }.getOrNull()
                }
                if (choices.isEmpty()) return@runCatching null
                LensOption(
                    name = o.getString("name").trim(),
                    choices = choices,
                    selected = o.optInt("selected", 0).takeIf { it in choices.indices } ?: 0
                )
            }.getOrNull()
        }
    }

    private fun decodeChoice(o: JSONObject) = LensOptionChoice(
        label = o.getString("label").trim(),
        vendorKeys = LensProfileCodec.decodeKeys(o.optJSONArray("vendorKeys")),
        patch = o.optJSONObject("patch")?.toString(),
        locks = o.optJSONObject("locks")?.let { l ->
            l.keys().asSequence().associateWith { l.getString(it) }
        }.orEmpty()
    )

    /**
     * Strict (backup import): every row and choice must be well formed, and the lens with each choice applied must
     * pass [validateLens] (the backup check for a plain lens).
     */
    fun validateBackup(lens: JSONObject, validateLens: (JSONObject) -> Unit) {
        if (!lens.has("options")) return
        val rows = lens.getJSONArray("options")
        val base = JSONObject(lens.toString()).apply { remove("options") }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.get("name") is String && row.getString("name").isNotBlank()) { "Invalid option name" }
            if (row.has("selected")) require(row.get("selected") is Number) { "Invalid option selection" }
            val choices = row.getJSONArray("choices")
            require(choices.length() > 0) { "An option row needs a choice" }
            for (j in 0 until choices.length()) {
                val choice = choices.getJSONObject(j)
                require(choice.get("label") is String && choice.getString("label").isNotBlank()) { "Invalid choice label" }
                choice.optJSONObject("locks")?.let { locks ->
                    locks.keys().forEach { name -> require(locks.get(name) is String) { "Invalid lock on $name" } }
                } ?: require(!choice.has("locks")) { "locks must be an object" }
                val merged = choice.optJSONObject("patch")?.let { mergePatch(base, it) as JSONObject } ?: JSONObject(base.toString())
                choice.optJSONArray("vendorKeys")?.let { keys ->
                    val all = merged.optJSONArray("vendorKeys") ?: JSONArray()
                    for (k in 0 until keys.length()) all.put(keys.get(k))
                    merged.put("vendorKeys", all)
                }
                merged.put("id", base.get("id"))
                validateLens(merged)
            }
        }
        val decoded = decode(rows)
        require(decoded.size == rows.length()) { "Malformed lens options" }
        require(validateLensOptions(decoded).isEmpty()) { validateLensOptions(decoded).first() }
    }

    /** The lens the camera runs: [lens] with every selected choice applied, without options. */
    fun resolve(lens: LensProfile): LensProfile = resolve(lens, lens.options.map { it.selected })

    /**
     * [lens] with choice [picks]`[row]` of each row applied (missing or out of range: the row's first choice), after
     * locks: a row another chosen choice locks runs with the locked choice.
     */
    fun resolve(lens: LensProfile, picks: List<Int>): LensProfile {
        if (lens.options.isEmpty()) return lens
        val base = lens.copy(options = emptyList())
        val effective = lens.options.effectivePicks(picks)
        val chosen = lens.options.mapIndexedNotNull { row, option ->
            option.choices.getOrNull(effective[row]) ?: option.choices.firstOrNull()
        }
        var json = LensProfileCodec.encodeLens(base, null)
        chosen.forEach { choice -> choice.patch?.let { json = mergePatch(json, JSONObject(it)) as JSONObject } }
        json.put("id", base.name.trim()).put("enabled", base.enabled)
        val patched = if (chosen.any { it.patch != null }) LensProfileCodec.decodeLens(json) else base
        val keys = patched.vendorKeys.toMutableList()
        chosen.forEach { choice ->
            choice.vendorKeys.forEach { key ->
                keys.removeAll { it.sameSlot(key) }
                keys += key
            }
        }
        return patched.copy(name = base.name, enabled = base.enabled, vendorKeys = keys, options = emptyList())
    }

    /** Problems of the lens with each choice applied (the other rows at their selection), deduplicated. */
    fun resolvedProblems(lens: LensProfile, others: List<LensProfile>): List<String> {
        if (lens.options.isEmpty()) return emptyList()
        val selection = lens.options.map { it.selected }
        val problems = linkedSetOf<String>()
        lens.options.forEachIndexed { row, option ->
            option.choices.forEachIndexed { index, choice ->
                val picks = selection.toMutableList().also { it[row] = index }
                val resolved = runCatching { resolve(lens, picks) }
                resolved.exceptionOrNull()?.let { problems += "${option.name} · ${choice.label}: ${it.message ?: "invalid patch"}" }
                resolved.getOrNull()?.let { merged ->
                    validateLensProfile(merged, others).forEach { problems += "${option.name} · ${choice.label}: $it" }
                }
            }
        }
        return problems.toList()
    }

    /** RFC 7386 JSON merge patch: objects merge, other values replace, null removes. */
    fun mergePatch(target: Any?, patch: Any?): Any? {
        if (patch !is JSONObject) return patch
        val result = if (target is JSONObject) JSONObject(target.toString()) else JSONObject()
        val names = patch.keys()
        while (names.hasNext()) {
            val name = names.next()
            val value = patch.get(name)
            if (value == JSONObject.NULL) result.remove(name) else result.put(name, mergePatch(result.opt(name), value))
        }
        return result
    }

    private fun VendorKey.sameSlot(other: VendorKey) = tag.trim() == other.tag.trim() && scope == other.scope

    /** The options of [lens] as editable JSON text (the editor's Options dialog). */
    fun toEditorText(options: List<LensOption>): String = if (options.isEmpty()) "[]" else encode(options).toString(2)

    /** Parsed editor text, or the reason it can't be read. */
    fun fromEditorText(text: String): Result<List<LensOption>> = runCatching {
        val array = JSONArray(text.trim().ifEmpty { "[]" })
        val decoded = decode(array)
        require(decoded.size == array.length()) { "A row is malformed: each needs a name and choices with labels" }
        for (i in 0 until array.length()) {
            val choices = array.getJSONObject(i).getJSONArray("choices")
            require(decoded[i].choices.size == choices.length()) { "${decoded[i].name}: a choice is malformed" }
            for (j in 0 until choices.length()) {
                val choice = choices.getJSONObject(j)
                if (choice.has("patch")) require(choice.get("patch") is JSONObject) { "${decoded[i].name}: patch must be an object" }
                if (choice.has("locks")) require(choice.get("locks") is JSONObject) { "${decoded[i].name}: locks must be an object" }
                val keys = choice.optJSONArray("vendorKeys")
                require((keys?.length() ?: 0) == decoded[i].choices[j].vendorKeys.size) {
                    "${decoded[i].name} · ${decoded[i].choices[j].label}: a vendor key is malformed"
                }
            }
        }
        decoded
    }
}
