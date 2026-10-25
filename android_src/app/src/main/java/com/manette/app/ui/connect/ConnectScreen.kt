package com.manette.app.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manette.app.session.ConnectionController
import com.manette.app.transport.ConnectionState

/** Minimal "connect by IP + code" screen. QR scanning and discovery come later. NOTE: not compiled. */
@Composable
fun ConnectScreen(c: ConnectionController) {
    var host by rememberSaveable { mutableStateOf(c.savedPc?.host ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    val busy = c.state == ConnectionState.CONNECTING

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Manette", style = MaterialTheme.typography.headlineMedium)
        c.savedPc?.let { pc ->
            Button(onClick = { c.reconnect() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Reconnect to ${pc.host}")
            }
            OutlinedButton(onClick = { c.forgetPc() }, enabled = !busy) { Text("Forget this PC") }
            Spacer(Modifier.height(8.dp))
        }
        Text("Pair with a PC", style = MaterialTheme.typography.titleMedium)
        Text("On the PC, run the companion and note its IP address and the typed pairing code (or the whole manette:// link).")
        OutlinedTextField(
            value = host, onValueChange = { host = it }, label = { Text("PC address, e.g. 192.168.1.20") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code, onValueChange = { code = it }, label = { Text("Pairing code or link") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { c.pair(host, code) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Connecting..." else "Pair and connect")
        }
        c.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
