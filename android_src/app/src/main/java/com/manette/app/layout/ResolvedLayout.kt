package com.manette.app.layout

import com.manette.app.layout.model.Control
import com.manette.app.layout.model.ControllerDefinition
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Preset

/** A control placed on a real surface, in pixels. Every control is described by a centre and half-extents. */
class ResolvedElement(
    val control: Control,
    val cx: Float,
    val cy: Float,
    val halfW: Float,
    val halfH: Float,
    val opacity: Float,
) {
    val id: String get() = control.id
}

class ResolvedLayout(val width: Float, val height: Float, val elements: List<ResolvedElement>) {
    fun find(id: String): ResolvedElement? = elements.firstOrNull { it.id == id }
}

object LayoutResolver {
    /** Applies [preset] (if any) over the definition's defaults and converts to pixels for a [width] x [height] surface. */
    fun resolve(definition: ControllerDefinition, preset: Preset?, width: Float, height: Float): ResolvedLayout {
        require(width > 0f && height > 0f) { "surface must have a size" }
        require(preset == null || preset.controllerId == definition.id) { "preset is for a different controller" }

        val elements = definition.slots.map { slot ->
            val p: Placement = preset?.placements?.get(slot.control.id) ?: slot.placement
            val (w, h) = sizeUnits(slot.control)
            ResolvedElement(
                control = slot.control,
                cx = p.cx * width,
                cy = p.cy * height,
                halfW = w * p.scale * height / 2f,     // sizes are in surface-HEIGHT units, so circles stay circles
                halfH = h * p.scale * height / 2f,
                opacity = p.opacity,
            )
        }
        return ResolvedLayout(width, height, elements)
    }

    private fun sizeUnits(c: Control): Pair<Float, Float> = when (c) {
        is Control.Button -> c.width to c.height
        is Control.Dpad -> c.size to c.size
        is Control.Stick -> c.diameter to c.diameter
    }
}
