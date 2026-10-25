package com.manette.app.layout.definitions

import com.manette.app.core.Buttons
import com.manette.app.layout.model.ButtonAction
import com.manette.app.layout.model.ButtonShape
import com.manette.app.layout.model.Control
import com.manette.app.layout.model.ControlSlot
import com.manette.app.layout.model.ControllerDefinition
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Side

/**
 * The Xbox layout. Control sizes are in surface-height units; positions are fractions of the surface and are tuned
 * for a landscape phone around 2:1 (the face-button diamond is spaced for that ratio).
 * NES, PS1 and others will be more definitions like this one, mapping onto the same GamepadState.
 */
object XboxDefinition {
    const val ID = "xbox"

    // Control ids are part of the saved-preset format. Never rename one.
    object Ids {
        const val LEFT_STICK = "left_stick"
        const val RIGHT_STICK = "right_stick"
        const val DPAD = "dpad"
        const val A = "a"
        const val B = "b"
        const val X = "x"
        const val Y = "y"
        const val LB = "lb"
        const val RB = "rb"
        const val LT = "lt"
        const val RT = "rt"
        const val START = "start"
        const val BACK = "back"
        const val GUIDE = "guide"
        const val L3 = "l3"
        const val R3 = "r3"
    }

    private fun face(id: String, label: String, mask: Int, cx: Float, cy: Float) =
        ControlSlot(Control.Button(id, label, ButtonAction.Mask(mask), ButtonShape.CIRCLE, 0.17f, 0.17f), Placement(cx, cy))

    private fun pill(id: String, label: String, action: ButtonAction, cx: Float, cy: Float) =
        ControlSlot(Control.Button(id, label, action, ButtonShape.PILL, 0.22f, 0.11f), Placement(cx, cy))

    private fun small(id: String, label: String, mask: Int, cx: Float, cy: Float, shape: ButtonShape = ButtonShape.PILL) =
        ControlSlot(Control.Button(id, label, ButtonAction.Mask(mask), shape, 0.10f, 0.08f), Placement(cx, cy))

    val definition = ControllerDefinition(
        id = ID,
        name = "Xbox",
        slots = listOf(
            // sticks and d-pad
            ControlSlot(Control.Stick(Ids.LEFT_STICK, "L", Side.LEFT, 0.42f), Placement(0.31f, 0.70f)),
            ControlSlot(Control.Stick(Ids.RIGHT_STICK, "R", Side.RIGHT, 0.42f), Placement(0.69f, 0.70f)),
            ControlSlot(Control.Dpad(Ids.DPAD, "", 0.30f), Placement(0.13f, 0.38f)),
            // face buttons: a diamond centred at (0.86, 0.40)
            face(Ids.Y, "Y", Buttons.Y, 0.86f, 0.22f),
            face(Ids.X, "X", Buttons.X, 0.77f, 0.40f),
            face(Ids.B, "B", Buttons.B, 0.95f, 0.40f),
            face(Ids.A, "A", Buttons.A, 0.86f, 0.58f),
            // shoulders and triggers along the top
            pill(Ids.LT, "LT", ButtonAction.Trigger(Side.LEFT), 0.07f, 0.07f),
            pill(Ids.LB, "LB", ButtonAction.Mask(Buttons.LEFT_SHOULDER), 0.20f, 0.07f),
            pill(Ids.RB, "RB", ButtonAction.Mask(Buttons.RIGHT_SHOULDER), 0.80f, 0.07f),
            pill(Ids.RT, "RT", ButtonAction.Trigger(Side.RIGHT), 0.93f, 0.07f),
            // centre cluster
            small(Ids.BACK, "BACK", Buttons.BACK, 0.43f, 0.10f),
            small(Ids.GUIDE, "X", Buttons.GUIDE, 0.50f, 0.10f, ButtonShape.CIRCLE),
            small(Ids.START, "START", Buttons.START, 0.57f, 0.10f),
            small(Ids.L3, "L3", Buttons.LEFT_THUMB, 0.45f, 0.50f, ButtonShape.CIRCLE),
            small(Ids.R3, "R3", Buttons.RIGHT_THUMB, 0.55f, 0.50f, ButtonShape.CIRCLE),
        ),
    )
}
