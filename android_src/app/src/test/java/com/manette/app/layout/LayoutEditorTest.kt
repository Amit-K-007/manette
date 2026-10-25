package com.manette.app.layout

import com.manette.app.layout.definitions.XboxDefinition
import com.manette.app.layout.definitions.XboxDefinition.Ids
import com.manette.app.layout.editor.LayoutEditor
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Preset
import com.manette.app.layout.storage.PresetJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutEditorTest {
    private val def = XboxDefinition.definition
    private fun editor(initial: Preset? = null) = LayoutEditor(def, initial, 2000f, 1000f)
    private fun LayoutEditor.el(id: String) = layout.find(id)!!

    @Test
    fun startsCleanAndSelectsNothing() {
        val e = editor()
        assertFalse(e.dirty)
        assertNull(e.selectedId)
        assertEquals(def.slots.size, e.layout.elements.size)
    }

    @Test
    fun draggingMovesTheControlByTheFingerDeltaWithoutAJump() {
        val e = editor()
        val a = e.el(Ids.A)
        assertTrue(e.beginDrag(a.cx + 10f, a.cy + 5f))                 // grab off-centre
        assertEquals(Ids.A, e.selectedId)
        e.dragTo(a.cx + 110f, a.cy + 105f)                              // move the finger by (100, 100)
        assertEquals(a.cx + 100f, e.el(Ids.A).cx, 0.01f)
        assertEquals(a.cy + 100f, e.el(Ids.A).cy, 0.01f)
        e.endDrag()
        e.dragTo(0f, 0f)                                                // no drag in progress: ignored
        assertEquals(a.cx + 100f, e.el(Ids.A).cx, 0.01f)
        assertTrue(e.dirty)
    }

    @Test
    fun aControlCanNeverBeDraggedOffTheSurface() {
        val e = editor()
        val a = e.el(Ids.A)
        e.beginDrag(a.cx, a.cy)
        e.dragTo(99999f, 99999f)
        assertEquals(2000f - a.halfW, e.el(Ids.A).cx, 0.01f)
        assertEquals(1000f - a.halfH, e.el(Ids.A).cy, 0.01f)
        e.dragTo(-99999f, -99999f)
        assertEquals(a.halfW, e.el(Ids.A).cx, 0.01f)
        assertEquals(a.halfH, e.el(Ids.A).cy, 0.01f)
    }

    @Test
    fun theTopMostControlWinsWhenTwoOverlapAndEmptySpaceDeselects() {
        val e = editor()
        val a = e.el(Ids.A)
        e.move(Ids.B, a.cx, a.cy)                                       // put B exactly on A; A is later in the list = drawn on top
        assertTrue(e.beginDrag(a.cx, a.cy))
        assertEquals(Ids.A, e.selectedId)

        assertFalse(e.beginDrag(1000f, 900f))                           // empty space
        assertNull(e.selectedId)
    }

    @Test
    fun scaleIsClampedAndGrowingNeverPushesAnEdgeOffScreen() {
        val e = editor()
        e.setScale(Ids.A, 99f); assertEquals(Placement.MAX_SCALE, e.placementOf(Ids.A).scale, 0.0001f)
        e.setScale(Ids.A, 0.01f); assertEquals(Placement.MIN_SCALE, e.placementOf(Ids.A).scale, 0.0001f)

        e.setScale(Ids.RT, Placement.MAX_SCALE)                         // RT hugs the top-right corner
        val rt = e.el(Ids.RT)
        assertTrue(rt.cx + rt.halfW <= 2000.01f)
        assertTrue(rt.cy - rt.halfH >= -0.01f)
    }

    @Test
    fun opacityIsClamped() {
        val e = editor()
        e.setOpacity(Ids.A, 0f); assertEquals(Placement.MIN_OPACITY, e.placementOf(Ids.A).opacity, 0.0001f)
        e.setOpacity(Ids.A, 5f); assertEquals(1f, e.placementOf(Ids.A).opacity, 0.0001f)
    }

    @Test
    fun resetControlAndResetAllRestoreTheDefaults() {
        val e = editor()
        e.move(Ids.A, 100f, 100f); e.setScale(Ids.B, 2f); e.setOpacity(Ids.X, 0.3f)
        assertTrue(e.dirty)
        e.resetControl(Ids.A)
        assertEquals(def.slots.first { it.control.id == Ids.A }.placement, e.placementOf(Ids.A))
        assertTrue("B and X still changed", e.dirty)
        e.resetAll()
        assertFalse(e.dirty)
    }

    @Test
    fun toPresetStoresOnlyWhatDiffersFromTheDefaults() {
        val e = editor()
        assertTrue(e.toPreset("p", "n").placements.isEmpty())
        e.move(Ids.A, 300f, 300f)
        e.setOpacity(Ids.LB, 0.5f)
        assertEquals(setOf(Ids.A, Ids.LB), e.toPreset("p", "n").placements.keys)
        e.resetControl(Ids.A)
        assertEquals(setOf(Ids.LB), e.toPreset("p", "n").placements.keys)
    }

    @Test
    fun whatYouEditIsWhatYouGetAfterSavingAndLoading() {
        val e = editor()
        e.beginDrag(e.el(Ids.RIGHT_STICK).cx, e.el(Ids.RIGHT_STICK).cy); e.dragTo(1300f, 600f); e.endDrag()
        e.setScale(Ids.Y, 1.6f); e.setOpacity(Ids.START, 0.4f); e.move(Ids.DPAD, 400f, 450f)

        val loaded = PresetJson.fromJson(PresetJson.toJson(e.toPreset("p", "Mine")))
        val play = LayoutResolver.resolve(def, loaded, 2000f, 1000f)
        for (el in e.layout.elements) {
            val o = play.find(el.id)!!
            assertEquals(el.id, el.cx, o.cx, 0.05f); assertEquals(el.id, el.cy, o.cy, 0.05f)
            assertEquals(el.id, el.halfW, o.halfW, 0.05f); assertEquals(el.id, el.opacity, o.opacity, 0.001f)
        }
    }

    @Test
    fun aPresetMadeForAnotherScreenShapeIsPulledBackIntoView() {
        val p = Preset("p", "n", "xbox", mapOf(Ids.B to Placement(0.99f, 0.99f, scale = Placement.MAX_SCALE)))
        val b = editor(p).el(Ids.B)
        assertTrue(b.cx + b.halfW <= 2000.01f && b.cy + b.halfH <= 1000.01f)
        assertTrue(b.cx - b.halfW >= -0.01f && b.cy - b.halfH >= -0.01f)
    }

    @Test
    fun wrongControllerOrUnknownControlIsRefused() {
        try { editor(Preset("p", "n", "nes")); fail() } catch (_: IllegalArgumentException) { }
        try { editor().select("nope"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun editingAnExistingPresetStartsFromItsValuesAndIsCleanUntilChanged() {
        val p = Preset("p", "n", "xbox", mapOf(Ids.A to Placement(0.5f, 0.5f, 1.5f, 0.6f)))
        val e = editor(p)
        assertEquals(0.6f, e.placementOf(Ids.A).opacity, 0.0001f)
        assertFalse(e.dirty)
        e.setOpacity(Ids.A, 0.9f)
        assertTrue(e.dirty)
    }
}
