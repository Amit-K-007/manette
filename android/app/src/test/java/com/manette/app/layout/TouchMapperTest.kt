package com.manette.app.layout

import com.manette.app.core.Buttons
import com.manette.app.core.GamepadState
import com.manette.app.layout.definitions.XboxDefinition
import com.manette.app.layout.definitions.XboxDefinition.Ids
import com.manette.app.layout.mapping.TouchMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** A 2000 x 1000 surface (2:1), so a placement of (0.5, 0.5) is the pixel (1000, 500). */
class TouchMapperTest {
    private val layout = LayoutResolver.resolve(XboxDefinition.definition, null, 2000f, 1000f)
    private fun m() = TouchMapper(layout)
    private fun el(id: String) = layout.find(id)!!

    private fun TouchMapper.downOn(pointer: Int, id: String, dx: Float = 0f, dy: Float = 0f) {
        val e = el(id); pointerDown(pointer, e.cx + dx, e.cy + dy)
    }

    private fun TouchMapper.moveTo(pointer: Int, id: String, dx: Float = 0f, dy: Float = 0f) {
        val e = el(id); pointerMove(pointer, e.cx + dx, e.cy + dy)
    }

    // ---------------------------------------------------------------- buttons

    @Test
    fun tappingAndLiftingAButton() {
        val t = m()
        t.downOn(1, Ids.A)
        assertEquals(Buttons.A, t.state.buttons)
        assertEquals(setOf(Ids.A), t.pressedIds())
        t.pointerUp(1)
        assertEquals(GamepadState.NEUTRAL, t.state)
        assertTrue(t.pressedIds().isEmpty())
    }

    @Test
    fun everyFaceAndCentreButtonProducesItsOwnBit() {
        val expected = mapOf(
            Ids.A to Buttons.A, Ids.B to Buttons.B, Ids.X to Buttons.X, Ids.Y to Buttons.Y,
            Ids.LB to Buttons.LEFT_SHOULDER, Ids.RB to Buttons.RIGHT_SHOULDER,
            Ids.START to Buttons.START, Ids.BACK to Buttons.BACK, Ids.GUIDE to Buttons.GUIDE,
            Ids.L3 to Buttons.LEFT_THUMB, Ids.R3 to Buttons.RIGHT_THUMB,
        )
        for ((id, mask) in expected) {
            val t = m()
            t.downOn(1, id)
            assertEquals("tapping $id", mask, t.state.buttons)
        }
    }

    @Test
    fun triggersAreFullyPressedAndIndependentOfButtons() {
        val t = m()
        t.downOn(1, Ids.LT)
        t.downOn(2, Ids.RT)
        t.downOn(3, Ids.LB)
        val s = t.state
        assertEquals(255, s.leftTrigger)
        assertEquals(255, s.rightTrigger)
        assertEquals(Buttons.LEFT_SHOULDER, s.buttons)
    }

    @Test
    fun severalFingersPressSeveralButtonsAtOnce() {
        val t = m()
        t.downOn(1, Ids.A)
        t.downOn(2, Ids.B)
        t.downOn(3, Ids.RB)
        assertEquals(Buttons.A or Buttons.B or Buttons.RIGHT_SHOULDER, t.state.buttons)
        t.pointerUp(2)
        assertEquals(Buttons.A or Buttons.RIGHT_SHOULDER, t.state.buttons)
    }

    @Test
    fun twoFingersOnTheSameButtonKeepItHeldUntilBothLift() {
        val t = m()
        t.downOn(1, Ids.A)
        t.downOn(2, Ids.A, dx = 5f)
        t.pointerUp(1)
        assertEquals(Buttons.A, t.state.buttons)
        t.pointerUp(2)
        assertEquals(0, t.state.buttons)
    }

    @Test
    fun slidingAcrossFaceButtonsSwitchesBetweenThem() {
        val t = m()
        t.downOn(1, Ids.A)
        assertEquals(Buttons.A, t.state.buttons)
        t.moveTo(1, Ids.B)
        assertEquals("only B now", Buttons.B, t.state.buttons)
        t.moveTo(1, Ids.X)
        assertEquals(Buttons.X, t.state.buttons)
    }

    @Test
    fun slidingOffAButtonOntoEmptySpaceReleasesIt() {
        val t = m()
        t.downOn(1, Ids.A)
        t.pointerMove(1, 1000f, 900f)                       // empty space at the bottom centre
        assertEquals(0, t.state.buttons)
    }

    @Test
    fun aHeldButtonSurvivesSmallDriftPastItsEdgeButNotALargeOne() {
        val t = m()
        val r = el(Ids.A).halfW
        t.downOn(1, Ids.A)
        t.moveTo(1, Ids.A, dy = r * 1.3f)                   // just outside the edge, in a gap
        assertEquals("hysteresis holds it", Buttons.A, t.state.buttons)
        t.moveTo(1, Ids.A, dy = r * 1.7f)
        assertEquals("well outside lets go", 0, t.state.buttons)
    }

    @Test
    fun aFingerThatStartsOnEmptySpaceNeverPressesAnything() {
        val t = m()
        t.pointerDown(1, 1000f, 900f)
        t.moveTo(1, Ids.A)                                  // slides right over a button
        assertEquals(GamepadState.NEUTRAL, t.state)
        t.moveTo(1, Ids.LEFT_STICK)                         // and over a stick
        assertEquals(GamepadState.NEUTRAL, t.state)
    }

    @Test
    fun aFingerThatStartsOnAButtonDoesNotGrabAStick() {
        val t = m()
        t.downOn(1, Ids.L3)
        t.moveTo(1, Ids.LEFT_STICK, dx = 40f)
        assertEquals(0, t.state.leftX)
        assertEquals(0, t.state.buttons)                    // it left L3 for the stick area: nothing pressed
    }

    // ----------------------------------------------------------------- sticks

    @Test
    fun draggingTheStickRightToItsEdgeGivesFullRight() {
        val t = m()
        val r = el(Ids.LEFT_STICK).halfW
        t.downOn(1, Ids.LEFT_STICK)
        t.moveTo(1, Ids.LEFT_STICK, dx = r)
        assertEquals(32767, t.state.leftX)
        assertEquals(0, t.state.leftY)
        assertEquals(Pair(1f, 0f), t.knob(Ids.LEFT_STICK))
    }

    @Test
    fun draggingUpOnScreenIsPositiveYOnTheWire() {
        val t = m()
        val r = el(Ids.LEFT_STICK).halfW
        t.downOn(1, Ids.LEFT_STICK)
        t.moveTo(1, Ids.LEFT_STICK, dy = -r / 2)            // screen up
        assertEquals("wire Y is up-positive", 16384, t.state.leftY)
        t.moveTo(1, Ids.LEFT_STICK, dy = r)                 // screen down
        assertEquals(-32767, t.state.leftY)
    }

    @Test
    fun stickHasACircularRangeAndNeverExceedsTheLimit() {
        val t = m()
        val r = el(Ids.RIGHT_STICK).halfW
        t.downOn(1, Ids.RIGHT_STICK)
        t.moveTo(1, Ids.RIGHT_STICK, dx = r * 3f, dy = -r * 3f)   // far past the edge on the diagonal
        val s = t.state
        val mag = hypot(s.rightX.toDouble(), s.rightY.toDouble())
        assertTrue("magnitude $mag", mag in 32760.0..32775.0)
        assertTrue(s.rightX > 0 && s.rightY > 0)
        assertEquals(0, s.leftX)
    }

    @Test
    fun theStickKeepsTrackingAfterTheFingerLeavesItsArea() {
        val t = m()
        t.downOn(1, Ids.LEFT_STICK)
        t.pointerMove(1, el(Ids.LEFT_STICK).cx + 900f, el(Ids.LEFT_STICK).cy)   // far outside
        assertEquals(32767, t.state.leftX)
    }

    @Test
    fun liftingTheStickRecentres() {
        val t = m()
        t.downOn(1, Ids.LEFT_STICK, dx = 60f)
        assertTrue(t.state.leftX > 0)
        t.pointerUp(1)
        assertEquals(0, t.state.leftX)
        assertEquals(Pair(0f, 0f), t.knob(Ids.LEFT_STICK))
    }

    @Test
    fun aSecondFingerOnAHeldStickIsIgnored() {
        val t = m()
        val r = el(Ids.LEFT_STICK).halfW
        t.downOn(1, Ids.LEFT_STICK, dx = r / 2)
        t.downOn(2, Ids.LEFT_STICK, dx = -r / 2)
        assertEquals(16384, t.state.leftX)                  // still the first finger's value
        t.pointerUp(2)
        assertEquals("ignored finger lifting changes nothing", 16384, t.state.leftX)
        t.pointerUp(1)
        t.downOn(3, Ids.LEFT_STICK, dx = -r / 2)            // free again, so a new finger can take it
        assertEquals(-16384, t.state.leftX)
    }

    @Test
    fun bothSticksAndButtonsWorkTogether() {
        val t = m()
        val r = el(Ids.LEFT_STICK).halfW
        t.downOn(1, Ids.LEFT_STICK)
        t.moveTo(1, Ids.LEFT_STICK, dx = -r)
        t.downOn(2, Ids.RIGHT_STICK)
        t.moveTo(2, Ids.RIGHT_STICK, dy = -r)
        t.downOn(3, Ids.A)
        t.downOn(4, Ids.RT)
        val s = t.state
        assertEquals(-32767, s.leftX)
        assertEquals(32767, s.rightY)
        assertEquals(Buttons.A, s.buttons)
        assertEquals(255, s.rightTrigger)
    }

    // ------------------------------------------------------------------ d-pad

    @Test
    fun dpadGivesEightDirections() {
        val e = el(Ids.DPAD)
        val d = e.halfW * 0.7f
        val cases = mapOf(
            (0f to -d) to Buttons.DPAD_UP, (0f to d) to Buttons.DPAD_DOWN,
            (-d to 0f) to Buttons.DPAD_LEFT, (d to 0f) to Buttons.DPAD_RIGHT,
            (d to -d) to (Buttons.DPAD_UP or Buttons.DPAD_RIGHT), (-d to -d) to (Buttons.DPAD_UP or Buttons.DPAD_LEFT),
            (d to d) to (Buttons.DPAD_DOWN or Buttons.DPAD_RIGHT), (-d to d) to (Buttons.DPAD_DOWN or Buttons.DPAD_LEFT),
        )
        for ((off, bits) in cases) {
            val t = m()
            t.pointerDown(1, e.cx + off.first, e.cy + off.second)
            assertEquals("offset $off", bits, t.state.buttons)
            assertEquals(bits, t.dpadBits())
        }
    }

    @Test
    fun dpadCentreIsADeadzoneAndSlidingChangesDirection() {
        val e = el(Ids.DPAD)
        val t = m()
        t.pointerDown(1, e.cx + 2f, e.cy - 2f)
        assertEquals("centre", 0, t.state.buttons)
        t.pointerMove(1, e.cx, e.cy - e.halfW * 0.8f)
        assertEquals(Buttons.DPAD_UP, t.state.buttons)
        t.pointerMove(1, e.cx + e.halfW * 0.8f, e.cy)
        assertEquals(Buttons.DPAD_RIGHT, t.state.buttons)
        t.pointerUp(1)
        assertEquals(0, t.state.buttons)
    }

    // ------------------------------------------------------------- housekeeping

    @Test
    fun cancelAllReleasesEverything() {
        val t = m()
        t.downOn(1, Ids.A)
        t.downOn(2, Ids.LEFT_STICK, dx = 100f)
        t.downOn(3, Ids.LT)
        t.cancelAll()
        assertEquals(GamepadState.NEUTRAL, t.state)
    }

    @Test
    fun unknownPointersAreIgnoredAndIdsCanBeReused() {
        val t = m()
        t.pointerMove(99, 10f, 10f)
        t.pointerUp(99)
        assertEquals(GamepadState.NEUTRAL, t.state)

        t.downOn(1, Ids.A); t.pointerUp(1)
        t.downOn(1, Ids.B)
        assertEquals(Buttons.B, t.state.buttons)
        t.downOn(1, Ids.X)                                   // a duplicate DOWN for the same id restarts it
        assertEquals(Buttons.X, t.state.buttons)
    }

    @Test
    fun theStateAlwaysSatisfiesTheProtocolRanges() {
        // Hammer the mapper with random multi-touch input; GamepadState validates its own ranges on construction,
        // so any out-of-range value would throw here.
        val rnd = java.util.Random(42)
        val t = m()
        repeat(20000) {
            val id = rnd.nextInt(5)
            val x = rnd.nextFloat() * 2400f - 200f           // includes points outside the surface
            val y = rnd.nextFloat() * 1400f - 200f
            when (rnd.nextInt(4)) {
                0 -> t.pointerDown(id, x, y)
                1, 2 -> t.pointerMove(id, x, y)
                else -> t.pointerUp(id)
            }
            val s = t.state
            assertTrue(s.buttons and 0x0800 == 0)
        }
    }

    @Test
    fun gapsBetweenControlsDoNotFlickerPressed() {
        val t = m()
        t.pointerDown(1, 1000f, 900f)                        // empty
        assertFalse(t.pressedIds().isNotEmpty())
    }
}
