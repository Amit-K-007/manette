package com.manette.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.manette.app.layout.definitions.XboxDefinition
import com.manette.app.layout.storage.FilePresetRepository
import com.manette.app.session.ConnectionController
import com.manette.app.session.PresetsState
import com.manette.app.ui.editor.LayoutEditorScreen
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.manette.app.transport.ConnectionState
import com.manette.app.ui.connect.ConnectScreen
import com.manette.app.ui.play.PlayScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: ConnectionController
    private lateinit var presets: PresetsState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ConnectionController(applicationContext)
        presets = PresetsState(FilePresetRepository(File(filesDir, "presets")), XboxDefinition.ID)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root(controller, presets) }
            }
        }
    }

    /** Leaving the app releases the controller on the PC (it goes neutral). Reconnect is one tap. */
    override fun onStop() {
        super.onStop()
        controller.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) controller.close()
    }
}

@Composable
private fun Root(c: ConnectionController, presets: PresetsState) {
    var editing by remember { mutableStateOf(false) }
    when {
        c.state == ConnectionState.CONNECTED || c.state == ConnectionState.RECONNECTING -> PlayScreen(c, presets.active)
        editing -> LayoutEditorScreen(
            definition = XboxDefinition.definition,
            initial = presets.active,
            onSave = { presets.save(it); editing = false },
            onCancel = { editing = false },
        )
        else -> ConnectScreen(c, presets, onEditLayout = { editing = true })
    }
}
