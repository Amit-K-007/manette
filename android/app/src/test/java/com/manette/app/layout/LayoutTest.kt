package com.manette.app.layout

import com.manette.app.core.Buttons
import com.manette.app.layout.definitions.XboxDefinition
import com.manette.app.layout.definitions.XboxDefinition.Ids
import com.manette.app.layout.model.ButtonAction
import com.manette.app.layout.model.Control
import com.manette.app.layout.model.ControlSlot
import com.manette.app.layout.model.ControllerDefinition
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class LayoutTest {
    private val def = XboxDefinition.definition

    private fun fails(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        fail("expected IllegalArgumentException")
    }

    @Test
    fun defaultPlacementsResolveToPixelsAndSizesFollowTheSurfaceHeight() {
        val l = LayoutResolver.resolve(def, null, 2000f, 1000f)
        val a = l.find(Ids.A)!!
        assertEquals(0.86f * 2000f, a.cx, 0.01f)
        assertEquals(0.58f * 1000f, a.cy, 0.01f)
        assertEquals("a circle stays a circle", a.halfW, a.halfH, 0.001f)
        assertEquals(0.17f * 1000f / 2f, a.halfW, 0.01f)

        val tall = LayoutResolver.resolve(def, null, 1000f, 500f)
        assertEquals("sizes scale with height", a.halfW / 2f, tall.find(Ids.A)!!.halfW, 0.01f)
    }

    @Test
    fun aPresetOverridesOnlyWhatItNames() {
        val preset = Preset("p1", "Mine", XboxDefinition.ID, mapOf(Ids.A to Placement(0.5f, 0.5f, scale = 2f, opacity = 0.4f)))
        val l = LayoutResolver.resolve(def, preset, 2000f, 1000f)
        val a = l.find(Ids.A)!!
        assertEquals(1000f, a.cx, 0.01f)
        assertEquals(500f, a.cy, 0.01f)
        assertEquals(0.17f * 2f * 1000f / 2f, a.halfW, 0.01f)
        assertEquals(0.4f, a.opacity, 0.0001f)
        // B was not mentioned, so it keeps its default
        assertEquals(0.95f * 2000f, l.find(Ids.B)!!.cx, 0.01f)
    }

    @Test
    fun aPresetForAnotherControllerOrBadPlacementsAreRejected() {
        fails { LayoutResolver.resolve(def, Preset("p", "x", "nes"), 100f, 100f) }
        fails { LayoutResolver.resolve(def, null, 0f, 100f) }
        fails { Placement(1.1f, 0.5f) }
        fails { Placement(0.5f, -0.1f) }
        fails { Placement(0.5f, 0.5f, scale = 0.1f) }
        fails { Placement(0.5f, 0.5f, scale = 3f) }
        fails { Placement(0.5f, 0.5f, opacity = 0f) }
        fails { ControllerDefinition("x", "x", listOf(def.slots[0], def.slots[0])) }   // duplicate ids
    }

    @Test
    fun theXboxDefinitionCoversEveryControllerInput() {
        val ids = def.slots.map { it.control.id }
        assertEquals(16, ids.size)

        var buttons = 0
        val masks = ArrayList<Int>()
        var triggers = 0
        var sticks = 0
        var dpad = 0
        for (s in def.slots) when (val c = s.control) {
            is Control.Button -> when (val a = c.action) {
                is ButtonAction.Mask -> { buttons = buttons or a.mask; masks.add(a.mask) }
                is ButtonAction.Trigger -> triggers++
            }
            is Control.Stick -> sticks++
            is Control.Dpad -> dpad++
        }
        assertEquals("no button bit used twice", masks.size, masks.toSet().size)
        // The d-pad supplies the four direction bits; every other defined bit must have its own button.
        val dpadBits = Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT
        assertEquals(Buttons.ALL_DEFINED, buttons or dpadBits)
        assertEquals(2, triggers)
        assertEquals(2, sticks)
        assertEquals(1, dpad)
        assertNotNull(def.slots.firstOrNull { it.control.id == Ids.GUIDE })
    }

    @Test
    fun defaultControlsDoNotOverlapOnCommonPhoneShapes() {
        // 2.0 is the design ratio; 2.17 is a modern tall phone in landscape; 1.78 is 16:9.
        for (ratio in listOf(1.78f, 2.0f, 2.17f)) {
            val l = LayoutResolver.resolve(def, null, ratio * 1000f, 1000f)
            val els = l.elements
            for (i in els.indices) for (j in i + 1 until els.size) {
                val a = els[i]
                val b = els[j]
                assertFalse("${a.id} overlaps ${b.id} at ratio $ratio", overlap(a, b))
            }
        }
    }

    @Test
    fun everyDefaultControlFitsInsideTheSurface() {
        for (ratio in listOf(1.78f, 2.0f, 2.17f)) {
            val l = LayoutResolver.resolve(def, null, ratio * 1000f, 1000f)
            for (e in l.elements) {
                assertTrue("${e.id} left edge at ratio $ratio", e.cx - e.halfW >= 0f)
                assertTrue("${e.id} right edge at ratio $ratio", e.cx + e.halfW <= l.width)
                assertTrue("${e.id} top edge", e.cy - e.halfH >= 0f)
                assertTrue("${e.id} bottom edge", e.cy + e.halfH <= l.height)
            }
        }
    }

    /** True if the two controls' real shapes intersect (circles as circles, the rest as rectangles). */
    private fun overlap(a: ResolvedElement, b: ResolvedElement): Boolean {
        val dx = abs(a.cx - b.cx)
        val dy = abs(a.cy - b.cy)
        val aRound = isRound(a)
        val bRound = isRound(b)
        return when {
            aRound && bRound -> hypot(dx, dy) < a.halfW + b.halfW
            !aRound && !bRound -> dx < a.halfW + b.halfW && dy < a.halfH + b.halfH
            else -> {
                val (c, r) = if (aRound) a to b else b to a
                val nx = (c.cx).coerceIn(r.cx - r.halfW, r.cx + r.halfW)
                val ny = (c.cy).coerceIn(r.cy - r.halfH, r.cy + r.halfH)
                hypot(c.cx - nx, c.cy - ny) < c.halfW
            }
        }
    }

    private fun isRound(e: ResolvedElement): Boolean = when (val c = e.control) {
        is Control.Stick -> true
        is Control.Button -> c.shape == com.manette.app.layout.model.ButtonShape.CIRCLE
        is Control.Dpad -> false
    }
}
