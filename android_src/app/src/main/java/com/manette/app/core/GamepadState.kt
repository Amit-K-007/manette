package com.manette.app.core

/** Button bit values. They equal the Windows XInput wButtons layout (docs/protocol.md section 5.1). */
object Buttons {
    const val NONE = 0x0000
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val GUIDE = 0x0400
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000

    /** Every defined bit. Bit 0x0800 is reserved and must be sent as 0. */
    const val ALL_DEFINED = 0xF7FF
}

/**
 * The canonical Xbox-style controller state. Every controller layout (Xbox now; NES, PS1 later) is just
 * a different way of producing one of these, so the wire format never changes when layouts are added.
 *
 * Positive X is right and positive Y is UP (XInput convention). Android screen Y grows downward:
 * convert with [screenYToWire] when building the state from touch coordinates.
 *
 * Plain Ints keep the code simple; ranges are checked on construction so a bug fails loudly in tests
 * instead of sending a garbage packet.
 */
data class GamepadState(
    val buttons: Int = 0,          // bitmask, 0..0xFFFF, see [Buttons]
    val leftTrigger: Int = 0,      // 0..255
    val rightTrigger: Int = 0,     // 0..255
    val leftX: Int = 0,            // -32768..32767
    val leftY: Int = 0,
    val rightX: Int = 0,
    val rightY: Int = 0,
) {
    init {
        require(buttons in 0..0xFFFF) { "buttons out of range: $buttons" }
        require(buttons and RESERVED_BITS == 0) { "reserved button bit set: 0x${buttons.toString(16)}" }
        require(leftTrigger in 0..255) { "leftTrigger out of range: $leftTrigger" }
        require(rightTrigger in 0..255) { "rightTrigger out of range: $rightTrigger" }
        require(leftX in AXIS_MIN..AXIS_MAX) { "leftX out of range: $leftX" }
        require(leftY in AXIS_MIN..AXIS_MAX) { "leftY out of range: $leftY" }
        require(rightX in AXIS_MIN..AXIS_MAX) { "rightX out of range: $rightX" }
        require(rightY in AXIS_MIN..AXIS_MAX) { "rightY out of range: $rightY" }
    }

    fun isPressed(mask: Int): Boolean = buttons and mask == mask

    companion object {
        const val AXIS_MIN = -32768
        const val AXIS_MAX = 32767
        private const val RESERVED_BITS = 0x0800

        val NEUTRAL = GamepadState()

        /**
         * Screen Y grows downward, wire Y grows upward: wire = clamp(-screen, -32768, 32767).
         * The clamp matters because -(-32768) does not fit in an i16.
         */
        fun screenYToWire(screenY: Int): Int = (-screenY.toLong()).coerceIn(AXIS_MIN.toLong(), AXIS_MAX.toLong()).toInt()
    }
}
