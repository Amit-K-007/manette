package com.manette.app.layout.model

/**
 * Where a control sits and how it looks. Everything is normalized so a preset survives a change of screen:
 * [cx]/[cy] are fractions (0..1) of the surface width/height, [scale] multiplies the control's default size.
 */
data class Placement(val cx: Float, val cy: Float, val scale: Float = 1f, val opacity: Float = 1f) {
    init {
        require(cx in 0f..1f && cy in 0f..1f) { "position must be within the surface: ($cx, $cy)" }
        require(scale in MIN_SCALE..MAX_SCALE) { "scale out of range: $scale" }
        require(opacity in MIN_OPACITY..1f) { "opacity out of range: $opacity" }
    }

    companion object {
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2.5f
        const val MIN_OPACITY = 0.1f
    }
}

data class ControlSlot(val control: Control, val placement: Placement)

/** A controller type (Xbox now; NES, PS1 later): its controls and their default arrangement. */
data class ControllerDefinition(val id: String, val name: String, val slots: List<ControlSlot>) {
    init {
        require(slots.map { it.control.id }.toSet().size == slots.size) { "control ids must be unique" }
    }
}

/**
 * A user's saved arrangement of one controller type. Only controls that differ from the default need an entry;
 * anything missing falls back to the definition's default placement.
 */
data class Preset(
    val id: String,
    val name: String,
    val controllerId: String,
    val placements: Map<String, Placement> = emptyMap(),
)
