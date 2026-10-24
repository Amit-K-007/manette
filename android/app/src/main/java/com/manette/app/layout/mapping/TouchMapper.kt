package com.manette.app.layout.mapping

import com.manette.app.core.Buttons
import com.manette.app.core.GamepadState
import com.manette.app.layout.ResolvedElement
import com.manette.app.layout.ResolvedLayout
import com.manette.app.layout.model.ButtonAction
import com.manette.app.layout.model.ButtonShape
import com.manette.app.layout.model.Control
import com.manette.app.layout.model.Side
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns finger positions into a [GamepadState] for one [ResolvedLayout]. Pure Kotlin: the Compose layer feeds it
 * pointer events (positions in the same pixels as the layout) and reads [state]. Call it from one thread only
 * (the UI thread).
 *
 * Multi-touch rules, which are what make an on-screen pad feel right:
 *  - Every finger is tracked by its own pointer id, so thumbs on a stick and a button never interfere.
 *  - A finger that lands on a STICK owns that stick until it lifts, even if it wanders far outside the stick's
 *    area. Only one finger can hold a given stick; a second finger landing on it is ignored.
 *  - A finger that lands on a BUTTON or the D-PAD is re-evaluated as it moves, so you can slide from A to B.
 *    It never grabs a stick it slides over, and sliding onto empty space releases it.
 *  - A finger that lands on empty space does nothing for its whole life, however far it slides.
 */
class TouchMapper(private val layout: ResolvedLayout) {
    private enum class Kind { STICK, GROUP, IGNORED }

    private class Pointer(var kind: Kind, var element: ResolvedElement?, var x: Float, var y: Float)

    private val pointers = HashMap<Int, Pointer>()

    // ------------------------------------------------------------------ input

    fun pointerDown(id: Int, x: Float, y: Float) {
        pointers.remove(id)                                    // a re-used id starts fresh
        val best = pickBest(x, y, includeSticks = true)
        pointers[id] = when {
            best == null -> Pointer(Kind.IGNORED, null, x, y)
            best.control is Control.Stick ->
                if (stickHeldByAnotherPointer(best)) Pointer(Kind.IGNORED, null, x, y)
                else Pointer(Kind.STICK, best, x, y)
            else -> Pointer(Kind.GROUP, best, x, y)
        }
    }

    fun pointerMove(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        p.x = x
        p.y = y
        if (p.kind != Kind.GROUP) return

        // The nearest button/d-pad under the finger wins. Only when the finger is in a gap does the current
        // control hold on a little longer (hysteresis), so a finger resting on an edge does not flicker.
        val best = pickBest(x, y, includeSticks = false)
        val current = p.element
        p.element = when {
            best != null -> best
            current != null && score(current, x, y, STAY_SLOP) != null -> current
            else -> null
        }
    }

    fun pointerUp(id: Int) {
        pointers.remove(id)
    }

    /** The system cancelled the gesture (palm rejection, app switch): let go of everything. */
    fun cancelAll() {
        pointers.clear()
    }

    // ------------------------------------------------------------------ output

    /** The controller state right now. Cheap enough to call on every touch event. */
    val state: GamepadState
        get() {
            var buttons = 0
            var lt = 0
            var rt = 0
            var lx = 0
            var ly = 0
            var rx = 0
            var ry = 0
            for (p in pointers.values) {
                val e = p.element ?: continue
                when (p.kind) {
                    Kind.IGNORED -> Unit
                    Kind.GROUP -> when (val c = e.control) {
                        is Control.Button -> when (val a = c.action) {
                            is ButtonAction.Mask -> buttons = buttons or a.mask
                            is ButtonAction.Trigger -> if (a.side == Side.LEFT) lt = 255 else rt = 255
                        }
                        is Control.Dpad -> buttons = buttons or dpadBits(e, p.x, p.y)
                        is Control.Stick -> Unit
                    }
                    Kind.STICK -> {
                        val (vx, vy) = stickVector(e, p.x, p.y)
                        val x = roundSymmetric(vx * AXIS_MAX)
                        val y = GamepadState.screenYToWire(roundSymmetric(vy * AXIS_MAX))   // screen Y points down
                        if ((e.control as Control.Stick).side == Side.LEFT) { lx = x; ly = y } else { rx = x; ry = y }
                    }
                }
            }
            return GamepadState(buttons, lt, rt, lx, ly, rx, ry)
        }

    /** Ids of the buttons and d-pad currently held, for drawing them as pressed. */
    fun pressedIds(): Set<String> {
        val out = HashSet<String>()
        for (p in pointers.values) if (p.kind == Kind.GROUP) p.element?.let { out.add(it.id) }
        return out
    }

    /** The D-pad direction bits currently held (for highlighting the right arm). 0 if none. */
    fun dpadBits(): Int {
        var bits = 0
        for (p in pointers.values) {
            val e = p.element ?: continue
            if (p.kind == Kind.GROUP && e.control is Control.Dpad) bits = bits or dpadBits(e, p.x, p.y)
        }
        return bits
    }

    /** Stick knob position for drawing: x right, y DOWN (screen orientation), each -1..1 within the unit disc. */
    fun knob(stickId: String): Pair<Float, Float> {
        for (p in pointers.values) {
            val e = p.element ?: continue
            if (p.kind == Kind.STICK && e.id == stickId) return stickVector(e, p.x, p.y)
        }
        return 0f to 0f
    }

    // ----------------------------------------------------------------- helpers

    /** Rounds halves away from zero, so left and right (up and down) deflect by exactly the same amount. */
    private fun roundSymmetric(v: Float): Int = if (v >= 0f) floor(v + 0.5f).toInt() else -floor(-v + 0.5f).toInt()

    private fun stickHeldByAnotherPointer(stick: ResolvedElement) =
        pointers.values.any { it.kind == Kind.STICK && it.element === stick }

    /** Offset from the stick centre in units of its radius, with the length limited to 1 (a circular, not square, range). */
    private fun stickVector(e: ResolvedElement, x: Float, y: Float): Pair<Float, Float> {
        var vx = (x - e.cx) / e.halfW
        var vy = (y - e.cy) / e.halfW
        val m = hypot(vx, vy)
        if (m > 1f) { vx /= m; vy /= m }
        return vx to vy
    }

    /** Eight-way D-pad: a direction is pressed when the finger is within 67.5 degrees of it, so diagonals press two. */
    private fun dpadBits(e: ResolvedElement, x: Float, y: Float): Int {
        val dx = x - e.cx
        val dy = y - e.cy
        val dist = hypot(dx, dy)
        if (dist < DPAD_DEADZONE * e.halfW) return 0
        val ux = dx / dist
        val uy = dy / dist
        var bits = 0
        if (ux > DPAD_AXIS) bits = bits or Buttons.DPAD_RIGHT
        if (ux < -DPAD_AXIS) bits = bits or Buttons.DPAD_LEFT
        if (uy > DPAD_AXIS) bits = bits or Buttons.DPAD_DOWN     // screen Y grows downward
        if (uy < -DPAD_AXIS) bits = bits or Buttons.DPAD_UP
        return bits
    }

    private fun pickBest(x: Float, y: Float, includeSticks: Boolean): ResolvedElement? {
        var best: ResolvedElement? = null
        var bestScore = Float.MAX_VALUE
        for (e in layout.elements) {
            val slop = when (e.control) {
                is Control.Stick -> if (includeSticks) STICK_CAPTURE_SLOP else continue
                is Control.Dpad -> DPAD_SLOP
                is Control.Button -> BUTTON_SLOP
            }
            val s = score(e, x, y, slop) ?: continue
            if (s < bestScore) { best = e; bestScore = s }
        }
        return best
    }

    /** 0 at the centre, 1 at the element's edge; null if the point is outside the edge scaled by [slop]. */
    private fun score(e: ResolvedElement, x: Float, y: Float, slop: Float): Float? {
        val dx = abs(x - e.cx)
        val dy = abs(y - e.cy)
        val s = when (val c = e.control) {
            is Control.Stick -> hypot(dx, dy) / e.halfW
            is Control.Button -> if (c.shape == ButtonShape.CIRCLE) hypot(dx, dy) / e.halfW else max(dx / e.halfW, dy / e.halfH)
            is Control.Dpad -> max(dx / e.halfW, dy / e.halfH)
        }
        return if (s <= slop) s else null
    }

    companion object {
        private const val AXIS_MAX = 32767f
        private const val BUTTON_SLOP = 1.12f       // forgiving: thumbs are bigger than they feel
        private const val DPAD_SLOP = 1.05f
        private const val STICK_CAPTURE_SLOP = 1.35f
        private const val STAY_SLOP = 1.5f          // a held button lets go only well outside its edge
        private const val DPAD_DEADZONE = 0.18f     // fraction of the half-size where no direction registers
        private const val DPAD_AXIS = 0.3827f       // sin(22.5 degrees): the 8-way sector boundary
    }
}
