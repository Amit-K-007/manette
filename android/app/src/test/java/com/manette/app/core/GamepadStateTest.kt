package com.manette.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GamepadStateTest {
    private fun rejects(build: () -> GamepadState) {
        try {
            build()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }

    @Test
    fun neutralIsAllZeros() {
        assertEquals(GamepadState(0, 0, 0, 0, 0, 0, 0), GamepadState.NEUTRAL)
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        rejects { GamepadState(buttons = 0x10000) }
        rejects { GamepadState(buttons = -1) }
        rejects { GamepadState(buttons = 0x0800) }       // reserved bit
        rejects { GamepadState(leftTrigger = 256) }
        rejects { GamepadState(rightTrigger = -1) }
        rejects { GamepadState(leftX = 32768) }
        rejects { GamepadState(leftY = -32769) }
        rejects { GamepadState(rightX = 40000) }
        rejects { GamepadState(rightY = -40000) }
    }

    @Test
    fun extremesAreAccepted() {
        GamepadState(Buttons.ALL_DEFINED, 255, 255, -32768, 32767, -32768, 32767)
    }

    @Test
    fun buttonConstantsMatchTheXInputLayout() {
        assertEquals(0x1000, Buttons.A)
        assertEquals(0x2000, Buttons.B)
        assertEquals(0x4000, Buttons.X)
        assertEquals(0x8000, Buttons.Y)
        assertEquals(0x0400, Buttons.GUIDE)
        assertEquals(0xF7FF, Buttons.ALL_DEFINED)
        val all = listOf(
            Buttons.DPAD_UP, Buttons.DPAD_DOWN, Buttons.DPAD_LEFT, Buttons.DPAD_RIGHT, Buttons.START, Buttons.BACK,
            Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB, Buttons.LEFT_SHOULDER, Buttons.RIGHT_SHOULDER, Buttons.GUIDE,
            Buttons.A, Buttons.B, Buttons.X, Buttons.Y,
        )
        assertEquals("all button bits are distinct", all.size, all.toSet().size)
        assertEquals(Buttons.ALL_DEFINED, all.fold(0) { acc, b -> acc or b })
    }

    @Test
    fun isPressedChecksEveryRequestedBit() {
        val s = GamepadState(buttons = Buttons.A or Buttons.LEFT_SHOULDER)
        assertTrue(s.isPressed(Buttons.A))
        assertTrue(s.isPressed(Buttons.A or Buttons.LEFT_SHOULDER))
        assertFalse(s.isPressed(Buttons.A or Buttons.B))
        assertFalse(s.isPressed(Buttons.X))
    }
}
