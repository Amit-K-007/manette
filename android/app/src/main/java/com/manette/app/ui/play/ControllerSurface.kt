package com.manette.app.ui.play

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.manette.app.core.Buttons
import com.manette.app.core.GamepadState
import com.manette.app.layout.LayoutResolver
import com.manette.app.layout.ResolvedElement
import com.manette.app.layout.ResolvedLayout
import com.manette.app.layout.mapping.TouchMapper
import com.manette.app.layout.model.ButtonShape
import com.manette.app.layout.model.Control
import com.manette.app.layout.model.ControllerDefinition
import com.manette.app.layout.model.Preset
import com.manette.app.layout.definitions.XboxDefinition.Ids

/**
 * The on-screen controller. It is deliberately thin: all the behaviour (which finger controls what, stick math,
 * D-pad sectors) is in [TouchMapper] and [LayoutResolver], which are plain Kotlin with unit tests. This composable
 * only (1) turns Compose pointer events into mapper calls, (2) reports the resulting state, and (3) draws.
 *
 * [onState] is called only when the state actually changes, on the main thread. Hand it straight to
 * `Transport.send`, which never blocks.
 *
 * NOTE: this file was written without an Android/Compose toolchain and has NOT been compiled. Expect to fix small
 * API-name differences for your Compose version when you first build.
 */
@Composable
fun ControllerSurface(
    definition: ControllerDefinition,
    preset: Preset?,
    onState: (GamepadState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(definition, preset, size) {
        if (size.width > 0 && size.height > 0) {
            LayoutResolver.resolve(definition, preset, size.width.toFloat(), size.height.toFloat())
        } else null
    }
    val mapper = remember(layout) { layout?.let { TouchMapper(it) } }
    var frame by remember { mutableIntStateOf(0) }                 // bumped after each touch event to trigger a redraw
    val currentOnState by rememberUpdatedState(onState)
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    // If the surface goes away (rotation, leaving the screen) while a finger is down, release everything.
    DisposableEffect(mapper) {
        onDispose { currentOnState(GamepadState.NEUTRAL) }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(mapper) {
                val m = mapper ?: return@pointerInput
                awaitPointerEventScope {
                    var last = GamepadState.NEUTRAL
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        for (change in event.changes) {
                            val id = change.id.value.toInt()
                            when {
                                change.pressed && !change.previousPressed -> m.pointerDown(id, change.position.x, change.position.y)
                                change.pressed -> m.pointerMove(id, change.position.x, change.position.y)
                                change.previousPressed -> m.pointerUp(id)
                            }
                            change.consume()
                        }
                        val s = m.state
                        if (s != last) {
                            last = s
                            currentOnState(s)
                        }
                        frame++
                    }
                }
            },
    ) {
        @Suppress("UNUSED_VARIABLE")
        val redrawTrigger = frame                                   // reading it here makes the canvas redraw on change
        if (layout != null && mapper != null) drawController(layout, mapper, paint)
    }
}

private val FILL = Color(0xFFFFFFFF)
private val RING = Color(0xFFFFFFFF)

private fun faceColor(id: String): Color? = when (id) {
    Ids.A -> Color(0xFF5DBB63)
    Ids.B -> Color(0xFFE5484D)
    Ids.X -> Color(0xFF4C8DFF)
    Ids.Y -> Color(0xFFF5C542)
    else -> null
}

private fun DrawScope.drawController(layout: ResolvedLayout, mapper: TouchMapper, paint: Paint) {
    val pressed = mapper.pressedIds()
    val dpadBits = mapper.dpadBits()
    for (e in layout.elements) {
        when (val c = e.control) {
            is Control.Button -> drawButton(e, c, e.id in pressed, paint)
            is Control.Dpad -> drawDpad(e, dpadBits)
            is Control.Stick -> drawStick(e, mapper.knob(e.id), paint, c.label)
        }
    }
}

private fun DrawScope.drawButton(e: ResolvedElement, c: Control.Button, isPressed: Boolean, paint: Paint) {
    val fillAlpha = e.opacity * if (isPressed) 0.55f else 0.18f
    val ringAlpha = e.opacity * 0.7f
    val center = Offset(e.cx, e.cy)
    if (c.shape == ButtonShape.CIRCLE) {
        drawCircle(FILL, radius = e.halfW, center = center, alpha = fillAlpha)
        drawCircle(RING, radius = e.halfW, center = center, alpha = ringAlpha, style = Stroke(width = e.halfW * 0.06f))
    } else {
        val topLeft = Offset(e.cx - e.halfW, e.cy - e.halfH)
        val size = Size(e.halfW * 2f, e.halfH * 2f)
        val corner = CornerRadius(e.halfH, e.halfH)
        drawRoundRect(FILL, topLeft = topLeft, size = size, cornerRadius = corner, alpha = fillAlpha)
        drawRoundRect(RING, topLeft = topLeft, size = size, cornerRadius = corner, alpha = ringAlpha, style = Stroke(width = e.halfH * 0.08f))
    }
    drawLabel(c.label, e.cx, e.cy, e.halfH * if (c.shape == ButtonShape.CIRCLE) 1.0f else 1.1f, faceColor(e.id) ?: Color.White, e.opacity, paint)
}

private fun DrawScope.drawDpad(e: ResolvedElement, bits: Int) {
    val arm = e.halfW * 0.62f                                       // half-width of the plus shape's arm
    val len = e.halfW
    fun armRect(dx: Float, dy: Float, mask: Int) {
        val active = bits and mask != 0
        val horizontal = dx != 0f
        val w = if (horizontal) len else arm
        val h = if (horizontal) arm else len
        val cx = e.cx + dx * len / 2f
        val cy = e.cy + dy * len / 2f
        drawRoundRect(
            FILL, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h),
            cornerRadius = CornerRadius(arm * 0.25f, arm * 0.25f), alpha = e.opacity * if (active) 0.55f else 0.18f,
        )
        drawRoundRect(
            RING, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h),
            cornerRadius = CornerRadius(arm * 0.25f, arm * 0.25f), alpha = e.opacity * 0.7f, style = Stroke(width = arm * 0.05f),
        )
    }
    armRect(0f, -1f, Buttons.DPAD_UP)
    armRect(0f, 1f, Buttons.DPAD_DOWN)
    armRect(-1f, 0f, Buttons.DPAD_LEFT)
    armRect(1f, 0f, Buttons.DPAD_RIGHT)
}

private fun DrawScope.drawStick(e: ResolvedElement, knob: Pair<Float, Float>, paint: Paint, label: String) {
    val base = Offset(e.cx, e.cy)
    drawCircle(FILL, radius = e.halfW, center = base, alpha = e.opacity * 0.10f)
    drawCircle(RING, radius = e.halfW, center = base, alpha = e.opacity * 0.6f, style = Stroke(width = e.halfW * 0.04f))
    val knobR = e.halfW * 0.42f
    val k = Offset(e.cx + knob.first * (e.halfW - knobR), e.cy + knob.second * (e.halfW - knobR))
    drawCircle(FILL, radius = knobR, center = k, alpha = e.opacity * 0.45f)
    drawCircle(RING, radius = knobR, center = k, alpha = e.opacity * 0.8f, style = Stroke(width = knobR * 0.08f))
    drawLabel(label, k.x, k.y, knobR, Color.White, e.opacity, paint)
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float, radius: Float, color: Color, opacity: Float, paint: Paint) {
    if (text.isEmpty()) return
    paint.color = color.copy(alpha = (opacity * 0.95f).coerceIn(0f, 1f)).toArgb()
    paint.textSize = radius * if (text.length > 2) 0.62f else 0.95f
    drawContext.canvas.nativeCanvas.drawText(text, x, y + paint.textSize * 0.35f, paint)
}
