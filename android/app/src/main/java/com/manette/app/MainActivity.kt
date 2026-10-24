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
import com.manette.app.session.ConnectionController
import com.manette.app.transport.ConnectionState
import com.manette.app.ui.connect.ConnectScreen
import com.manette.app.ui.play.PlayScreen

class MainActivity : ComponentActivity() {
    private lateinit var controller: ConnectionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ConnectionController(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root(controller) }
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
private fun Root(c: ConnectionController) {
    if (c.state == ConnectionState.CONNECTED || c.state == ConnectionState.RECONNECTING) PlayScreen(c) else ConnectScreen(c)
}
