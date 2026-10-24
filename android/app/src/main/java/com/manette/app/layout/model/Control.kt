package com.manette.app.layout.model

enum class Side { LEFT, RIGHT }

enum class ButtonShape { CIRCLE, PILL }

/** What a held-down on-screen button does to the controller state. */
sealed interface ButtonAction {
    /** Presses these [com.manette.app.core.Buttons] bits. */
    data class Mask(val mask: Int) : ButtonAction

    /** Presses an analog trigger fully (255). Phones have no pressure, so triggers are digital. */
    data class Trigger(val side: Side) : ButtonAction
}

/**
 * One on-screen control, independent of where it sits. Sizes are in units of the surface HEIGHT, so circles stay
 * circles on any screen shape; positions live in [Placement].
 */
sealed class Control {
    abstract val id: String
    abstract val label: String

    data class Button(
        override val id: String,
        override val label: String,
        val action: ButtonAction,
        val shape: ButtonShape,
        /** Width in surface-height units (the diameter for a CIRCLE). */
        val width: Float,
        val height: Float,
    ) : Control()

    /** Eight-way directional pad: a square of [size] height-units that presses one or two direction bits. */
    data class Dpad(override val id: String, override val label: String, val size: Float) : Control()

    /** Analog stick; [diameter] is the size of its base in height-units. */
    data class Stick(override val id: String, override val label: String, val side: Side, val diameter: Float) : Control()
}
