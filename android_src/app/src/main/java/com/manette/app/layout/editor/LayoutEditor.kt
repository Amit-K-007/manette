package com.manette.app.layout.editor

import com.manette.app.layout.LayoutResolver
import com.manette.app.layout.ResolvedElement
import com.manette.app.layout.ResolvedLayout
import com.manette.app.layout.model.ButtonShape
import com.manette.app.layout.model.Control
import com.manette.app.layout.model.ControllerDefinition
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Preset
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The editing rules for rearranging a controller, with no UI in it. The screen feeds it touches (pixels, in the same
 * space as the play surface so what you edit is exactly what you get) and reads [layout] to draw.
 *
 * Rules: dragging keeps the finger's grab point (no jump); a control can never be dragged or scaled off screen;
 * scale and opacity stay within [Placement]'s limits; saving stores only what differs from the defaults, so a
 * future change to the defaults still reaches users for controls they never touched.
 */
class LayoutEditor(
    private val definition: ControllerDefinition,
    initial: Preset?,
    val width: Float,
    val height: Float,
) {
    private val defaults: Map<String, Placement> = definition.slots.associate { it.control.id to it.placement }
    private val working = LinkedHashMap<String, Placement>()
    private val baseline: Map<String, Placement>

    var selectedId: String? = null
        private set
    var layout: ResolvedLayout = ResolvedLayout(width, height, emptyList())
        private set

    private var dragId: String? = null
    private var grabDx = 0f
    private var grabDy = 0f

    init {
        require(initial == null || initial.controllerId == definition.id) { "preset is for a different controller" }
        for (slot in definition.slots) working[slot.control.id] = initial?.placements?.get(slot.control.id) ?: slot.placement
        baseline = LinkedHashMap(working)
        relayout()
        for (id in working.keys) clampInside(id)            // a preset made on another screen shape may overflow this one
        relayout()
    }

    val dirty: Boolean get() = working != baseline

    fun placementOf(id: String): Placement = working.getValue(id)

    fun select(id: String?) {
        require(id == null || id in working) { "unknown control $id" }
        selectedId = id
    }

    // ------------------------------------------------------------------ dragging

    /** Finger down. Selects the top-most control under it and starts a drag; false if there is none. */
    fun beginDrag(x: Float, y: Float): Boolean {
        val hit = layout.elements.lastOrNull { contains(it, x, y) }
        if (hit == null) { selectedId = null; dragId = null; return false }
        selectedId = hit.id
        dragId = hit.id
        grabDx = x - hit.cx
        grabDy = y - hit.cy
        return true
    }

    fun dragTo(x: Float, y: Float) {
        val id = dragId ?: return
        move(id, x - grabDx, y - grabDy)
    }

    fun endDrag() { dragId = null }

    /** Puts the control's centre at pixel ([px], [py]), clamped so the whole control stays on the surface. */
    fun move(id: String, px: Float, py: Float) {
        val e = layout.find(id) ?: return
        val cx = if (e.halfW * 2f >= width) width / 2f else px.coerceIn(e.halfW, width - e.halfW)
        val cy = if (e.halfH * 2f >= height) height / 2f else py.coerceIn(e.halfH, height - e.halfH)
        update(id) { it.copy(cx = (cx / width).coerceIn(0f, 1f), cy = (cy / height).coerceIn(0f, 1f)) }
    }

    // -------------------------------------------------------------- size, opacity

    fun setScale(id: String, scale: Float) {
        update(id) { it.copy(scale = scale.coerceIn(Placement.MIN_SCALE, Placement.MAX_SCALE)) }
        clampInside(id)                                     // growing must not push an edge off screen
        relayout()
    }

    fun setOpacity(id: String, opacity: Float) =
        update(id) { it.copy(opacity = opacity.coerceIn(Placement.MIN_OPACITY, 1f)) }

    fun resetControl(id: String) {
        working[id] = defaults.getValue(id)
        relayout()                                          // clamp must see the restored position, not the old one
        clampInside(id)
        relayout()
    }

    fun resetAll() {
        for ((id, p) in defaults) working[id] = p
        relayout()
        for (id in working.keys) clampInside(id)
        relayout()
    }

    // ------------------------------------------------------------------- result

    /** A preset holding only the controls that differ from the defaults. */
    fun toPreset(id: String, name: String): Preset =
        Preset(id, name, definition.id, working.filter { (cid, p) -> p != defaults[cid] })

    // ------------------------------------------------------------------ helpers

    private fun update(id: String, change: (Placement) -> Placement) {
        working[id] = change(working.getValue(id))
        relayout()
    }

    private fun relayout() {
        layout = LayoutResolver.resolve(definition, Preset("editing", "editing", definition.id, working), width, height)
    }

    private fun clampInside(id: String) {
        val e = layout.find(id) ?: return
        val cx = if (e.halfW * 2f >= width) width / 2f else e.cx.coerceIn(e.halfW, width - e.halfW)
        val cy = if (e.halfH * 2f >= height) height / 2f else e.cy.coerceIn(e.halfH, height - e.halfH)
        val p = working.getValue(id)
        working[id] = p.copy(cx = (cx / width).coerceIn(0f, 1f), cy = (cy / height).coerceIn(0f, 1f))
    }

    /** Hit test on the control's real shape, with a little slop because fingertips are imprecise. */
    private fun contains(e: ResolvedElement, x: Float, y: Float): Boolean {
        val dx = abs(x - e.cx)
        val dy = abs(y - e.cy)
        val slop = 1.1f
        return when (val c = e.control) {
            is Control.Stick -> hypot(dx, dy) <= e.halfW * slop
            is Control.Button -> if (c.shape == ButtonShape.CIRCLE) hypot(dx, dy) <= e.halfW * slop else dx <= e.halfW * slop && dy <= e.halfH * slop
            is Control.Dpad -> dx <= e.halfW * slop && dy <= e.halfH * slop
        }
    }
}
