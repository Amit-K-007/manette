package com.manette.app.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.manette.app.layout.editor.LayoutEditor
import com.manette.app.layout.model.ControllerDefinition
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Preset
import com.manette.app.layout.storage.PresetIds
import com.manette.app.layout.storage.PresetJson
import com.manette.app.platform.ImmersiveMode
import com.manette.app.ui.play.drawController

/**
 * Drag controls to move them; tap one to change its size and opacity. All the rules (staying on screen, limits,
 * what gets saved) live in [LayoutEditor], which is unit tested; this screen only draws and forwards touches.
 * NOTE: not compiled (no Android toolchain where it was written).
 */
@Composable
fun LayoutEditorScreen(
    definition: ControllerDefinition,
    initial: Preset?,
    onSave: (Preset) -> Unit,
    onCancel: () -> Unit,
) {
    ImmersiveMode()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val editor = remember(size) {
        if (size.width > 0 && size.height > 0) LayoutEditor(definition, initial, size.width.toFloat(), size.height.toFloat()) else null
    }
    var version by remember { mutableIntStateOf(0) }            // bumped after every change: redraw and refresh the panel
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "My layout") }
    var panelAtTop by remember { mutableStateOf(true) }
    val paint = remember {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    }
    val refresh = version                                         // reading it makes this composable update on change

    Box(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(editor) {
                val ed = editor ?: return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.first()   // one finger edits
                        when {
                            change.pressed && !change.previousPressed -> ed.beginDrag(change.position.x, change.position.y)
                            change.pressed -> ed.dragTo(change.position.x, change.position.y)
                            change.previousPressed -> ed.endDrag()
                        }
                        change.consume()
                        version++
                    }
                }
            },
        ) {
            @Suppress("UNUSED_VARIABLE") val redraw = version
            if (editor != null) drawController(editor.layout, emptySet(), 0, { 0f to 0f }, paint, editor.selectedId)
        }

        Surface(
            modifier = Modifier.align(if (panelAtTop) Alignment.TopCenter else Alignment.BottomCenter).padding(8.dp).width(380.dp),
            color = Color(0xEE1E1F24), shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(PresetJson.MAX_NAME) }, label = { Text("Layout name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                val sel = editor?.selectedId
                if (editor != null && sel != null) {
                    val control = definition.slots.first { it.control.id == sel }.control
                    val p = editor.placementOf(sel)
                    Text("${control.label.ifEmpty { control.id }}: size ${"%.1f".format(p.scale)}x, opacity ${(p.opacity * 100).toInt()}%", color = Color.White)
                    Slider(value = p.scale, onValueChange = { editor.setScale(sel, it); version++ }, valueRange = Placement.MIN_SCALE..Placement.MAX_SCALE)
                    Slider(value = p.opacity, onValueChange = { editor.setOpacity(sel, it); version++ }, valueRange = Placement.MIN_OPACITY..1f)
                    TextButton(onClick = { editor.resetControl(sel); version++ }) { Text("Reset this control") }
                } else {
                    Text("Drag a control to move it. Tap one to change its size and opacity.", color = Color.White)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editor?.resetAll(); version++ }) { Text("Reset all") }
                    TextButton(onClick = { panelAtTop = !panelAtTop }) { Text("Move panel") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun save(id: String) = editor?.let { onSave(it.toPreset(id, name.trim().ifBlank { "My layout" })) }
                    Button(onClick = { save(initial?.id ?: PresetIds.newId()) }) { Text("Save") }
                    if (initial != null) Button(onClick = { save(PresetIds.newId()) }) { Text("Save as new") }
                }
            }
        }
    }
}
