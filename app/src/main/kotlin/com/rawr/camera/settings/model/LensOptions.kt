package com.rawr.camera.settings.model

/**
 * Lens options (X14U build): rows of choices on one lens, e.g. Aperture (f/1.67, f/2.8, f/4) and Mode (Normal,
 * DCG, ISZ, 50 MP). The capture screen shows one lens; long press (or the options button) picks a choice per row.
 *
 * Each choice carries only what differs from the lens: vendor keys to add, and an optional [LensOptionChoice.patch],
 * a JSON merge patch (RFC 7386) over the lens's schema-2 profile JSON, for anything else (streams, session, camera).
 * The camera receives the lens with every selected choice applied in row order; later rows win. Changing a choice
 * restarts the camera with the new lens (the native camera restarts whenever the lens it runs changes).
 */
data class LensOption(
    val name: String,
    val choices: List<LensOptionChoice>,
    val selected: Int = 0
) {
    val selectedChoice: LensOptionChoice? get() = choices.getOrNull(selected) ?: choices.firstOrNull()
}

data class LensOptionChoice(
    val label: String,
    /** Added to the lens's keys; a lens key with the same tag and scope is replaced. */
    val vendorKeys: List<VendorKey> = emptyList(),
    /** JSON merge patch over the lens profile (objects merge, values replace, null removes); null for none. */
    val patch: String? = null,
    /**
     * Other rows this choice forces, by row name to choice label (e.g. 50MP locks "Aperture" to "f/2": the
     * full-size mode ignores aperture). A locked row's own selection is kept for when the lock goes away.
     */
    val locks: Map<String, String> = emptyMap()
)

/** Most rows / choices per row the capture-screen panel lays out. */
const val LENS_OPTION_MAX_ROWS = 4
const val LENS_OPTION_MAX_CHOICES = 8

fun LensProfile.selectOption(row: Int, choice: Int): LensProfile {
    if (row !in options.indices || choice !in options[row].choices.indices) return this
    if (row in options.lockedRows(options.map { it.selected })) return this
    return copy(options = options.mapIndexed { i, o -> if (i == row) o.copy(selected = choice) else o })
}

/**
 * Rows forced by a chosen choice in another row, as row index to choice index, for [picks] (one choice index per
 * row). Locks from a locked row's own choice don't count; the first lock on a row wins.
 */
fun List<LensOption>.lockedRows(picks: List<Int>): Map<Int, Int> {
    val locked = linkedMapOf<Int, Int>()
    forEachIndexed { row, option ->
        val choice = option.choices.getOrNull(picks.getOrElse(row) { 0 }) ?: option.choices.firstOrNull() ?: return@forEachIndexed
        choice.locks.forEach { (rowName, label) ->
            val target = indexOfFirst { it.name.trim().equals(rowName.trim(), ignoreCase = true) }
            if (target < 0 || target == row || target in locked) return@forEach
            val index = this[target].choices.indexOfFirst { it.label.trim().equals(label.trim(), ignoreCase = true) }
            if (index >= 0) locked[target] = index
        }
    }
    return locked
}

/** The choice each row runs with: its selection, or the choice another row locks it to. */
fun List<LensOption>.effectivePicks(picks: List<Int> = map { it.selected }): List<Int> {
    val locked = lockedRows(picks)
    return indices.map { row -> locked[row] ?: picks.getOrElse(row) { 0 } }
}

/** Running choice labels in row order ("f/2.8 · DCG"); empty without options. */
fun LensProfile.optionSummary(): String =
    options.effectivePicks().mapIndexedNotNull { row, pick -> options[row].choices.getOrNull(pick)?.label }.joinToString(" · ")

/** Structural problems with a lens's options; the merged lens is checked separately (LensOptionResolver). */
fun validateLensOptions(options: List<LensOption>): List<String> = buildList {
    if (options.size > LENS_OPTION_MAX_ROWS) add("At most $LENS_OPTION_MAX_ROWS option rows")
    val names = mutableSetOf<String>()
    options.forEachIndexed { index, option ->
        val name = option.name.trim()
        val label = name.ifEmpty { "Option ${index + 1}" }
        if (name.isEmpty()) add("$label: name is required")
        else if (!names.add(name.lowercase())) add("Two option rows are named $name")
        if (option.choices.isEmpty()) add("$label: add at least one choice")
        if (option.choices.size > LENS_OPTION_MAX_CHOICES) add("$label: at most $LENS_OPTION_MAX_CHOICES choices")
        if (option.choices.isNotEmpty() && option.selected !in option.choices.indices) add("$label: selected choice is out of range")
        val choiceNames = mutableSetOf<String>()
        option.choices.forEach { choice ->
            val choiceLabel = choice.label.trim()
            if (choiceLabel.isEmpty()) add("$label: every choice needs a label")
            else if (!choiceNames.add(choiceLabel.lowercase())) add("$label: two choices are labelled $choiceLabel")
            choice.vendorKeys.forEach { key -> validateVendorKey(key)?.let { add("$label · $choiceLabel: $it") } }
            choice.locks.forEach { (rowName, lockedLabel) ->
                val target = options.firstOrNull { it.name.trim().equals(rowName.trim(), ignoreCase = true) }
                when {
                    target == null -> add("$label · $choiceLabel: no option row named $rowName to lock")
                    target === option -> add("$label · $choiceLabel: a choice can't lock its own row")
                    target.choices.none { it.label.trim().equals(lockedLabel.trim(), ignoreCase = true) } ->
                        add("$label · $choiceLabel: ${target.name} has no choice $lockedLabel")
                }
            }
        }
    }
}
