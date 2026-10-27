package com.manette.app.layout

import com.manette.app.layout.editor.PanelPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelPlacementTest {
    @Test
    fun anItemInsideTheBoxIsUntouched() {
        assertEquals(100f to 50f, PanelPlacement.clamp(100f, 50f, 2000f, 1000f, 300f, 200f))
    }

    @Test
    fun draggingPastAnyEdgeStopsAtThatEdge() {
        assertEquals(0f to 0f, PanelPlacement.clamp(-500f, -9f, 2000f, 1000f, 300f, 200f))
        assertEquals(1700f to 800f, PanelPlacement.clamp(5000f, 5000f, 2000f, 1000f, 300f, 200f))
    }

    @Test
    fun anItemBiggerThanTheBoxIsPinnedTopLeftNotCrashing() {
        assertEquals(0f to 0f, PanelPlacement.clamp(40f, 40f, 200f, 100f, 300f, 300f))
    }

    @Test
    fun defaultsAreCenteredAndInsideTheBox() {
        assertEquals(850f to 16f, PanelPlacement.centeredTop(2000f, 300f, 16f))
        assertEquals(940f to 940f, PanelPlacement.centeredBottom(2000f, 1000f, 120f, 44f, 16f))
        assertEquals(0f to 16f, PanelPlacement.centeredTop(200f, 300f, 16f))   // wider than the box
    }
}
