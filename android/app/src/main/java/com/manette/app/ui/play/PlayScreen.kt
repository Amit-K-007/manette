package com.manette.app.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.manette.app.layout.definitions.XboxDefinition
import com.manette.app.platform.PlayEnvironment
import com.manette.app.session.ConnectionController
import com.manette.app.transport.ConnectionState

/** The controller plus a tiny status line (bottom centre, the one empty spot) and an Exit button. NOTE: not compiled. */
@Composable
fun PlayScreen(c: ConnectionController) {
    PlayEnvironment()
    Box(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        ControllerSurface(
            definition = XboxDefinition.definition,
            preset = null,
            onState = { c.send(it) },
        )
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val status = when {
                c.state == ConnectionState.RECONNECTING -> "Reconnecting..."
                c.rttMs >= 0 -> "${c.rttMs} ms"
                else -> "Connected"
            }
            Text(status, color = Color(0xCCFFFFFF))
            TextButton(onClick = { c.disconnect() }) { Text("Exit", color = Color(0xCCFFFFFF)) }
        }
    }
}
