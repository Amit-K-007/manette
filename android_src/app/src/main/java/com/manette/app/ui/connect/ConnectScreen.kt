package com.manette.app.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manette.app.session.ConnectionController
import com.manette.app.platform.scanQr
import com.manette.app.session.DiscoveryState
import com.manette.app.session.PresetsState
import com.manette.app.transport.ConnectionState

/** Minimal "connect by IP + code" screen. QR scanning and discovery come later. NOTE: not compiled. */
@Composable
fun ConnectScreen(c: ConnectionController, presets: PresetsState, discovery: DiscoveryState, onEditLayout: () -> Unit) {
    val context = LocalContext.current
    var host by rememberSaveable { mutableStateOf(c.savedPc?.host ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    val busy = c.state == ConnectionState.CONNECTING
    LaunchedEffect(discovery.pcs) { c.followSavedPc(discovery.pcs) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
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
        Button(
            onClick = { scanQr(context, onResult = { raw -> if (raw != null) c.pair("", raw, discovery.pcs) }, onError = { c.showMessage(it) }) },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan QR code from the PC") }
        Text("PCs on this network", style = MaterialTheme.typography.titleSmall)
        if (discovery.pcs.isEmpty()) Text("Looking... (if none appear, scan the QR code or type the address below)")
        discovery.pcs.forEach { pc ->
            Row(Modifier.fillMaxWidth().clickable { host = if (pc.port == 26760) pc.host else "${pc.host}:${pc.port}" }, verticalAlignment = Alignment.CenterVertically) {
                Text("${pc.name}  ${pc.host}" + (if (pc.pairingOpen == true) "  (ready to pair)" else "") + (if (pc.installId != null && pc.installId == c.savedPc?.installId) "  (saved)" else ""))
            }
        }
        Text("Or enter it by hand. On the PC, note its address and the typed code.")
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

        Spacer(Modifier.height(16.dp))
        Text("Controller layout", style = MaterialTheme.typography.titleMedium)
        @Composable
        fun choice(label: String, id: String?) = Row(
            Modifier.fillMaxWidth().clickable { presets.select(id) }, verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = presets.activeId == id, onClick = { presets.select(id) })
            Text(label)
        }
        choice("Default", null)
        presets.presets.forEach { choice(it.name, it.id) }
        Row {
            OutlinedButton(onClick = onEditLayout) { Text(if (presets.active == null) "Create a layout" else "Edit this layout") }
            presets.active?.let { TextButton(onClick = { presets.delete(it.id) }) { Text("Delete") } }
        }
    }
}
