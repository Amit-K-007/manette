package com.manette.app.layout.editor

/** Where the floating editor panel and its minimized pill may sit. Pure math, so it is unit tested. */
object PanelPlacement {
    /** Keeps an item fully inside the box. An item bigger than the box is pinned to the top-left corner. */
    fun clamp(x: Float, y: Float, boxW: Float, boxH: Float, itemW: Float, itemH: Float): Pair<Float, Float> =
        x.coerceIn(0f, maxOf(0f, boxW - itemW)) to y.coerceIn(0f, maxOf(0f, boxH - itemH))

    fun centeredTop(boxW: Float, itemW: Float, margin: Float): Pair<Float, Float> =
        maxOf(0f, (boxW - itemW) / 2f) to margin

    fun centeredBottom(boxW: Float, boxH: Float, itemW: Float, itemH: Float, margin: Float): Pair<Float, Float> =
        maxOf(0f, (boxW - itemW) / 2f) to maxOf(0f, boxH - itemH - margin)
}
