package com.manette.app.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.net.wifi.WifiManager
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * While the play screen is showing: keep the screen on, go immersive, hold a low-latency Wi-Fi lock, and stop the
 * system back-gesture from stealing touches near the bottom corners where the thumbs rest.
 * NOTE: not compiled (no Android toolchain where it was written).
 */
@Composable
fun PlayEnvironment() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        view.keepScreenOn = true

        val window = context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "manette").apply {
            setReferenceCounted(false)
            acquire()
        }

        // Android allows excluding at most 200dp of height per screen edge.
        val density = view.resources.displayMetrics.density
        val edge = (32 * density).toInt()
        val tall = (200 * density).toInt()
        val applyExclusion = View.OnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            v.systemGestureExclusionRects = listOf(Rect(0, h - tall, edge, h), Rect(w - edge, h - tall, w, h))
        }
        view.addOnLayoutChangeListener(applyExclusion)
        if (view.width > 0) applyExclusion.onLayoutChange(view, 0, 0, view.width, view.height, 0, 0, 0, 0)

        onDispose {
            view.removeOnLayoutChangeListener(applyExclusion)
            view.systemGestureExclusionRects = emptyList()
            view.keepScreenOn = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
            if (lock.isHeld) lock.release()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Immersive + keep-awake only. The layout editor uses this so its surface is exactly the size of the play surface
 * (sizes are relative to the screen height, so bars would make the editor differ from what you then play).
 */
@Composable
fun ImmersiveMode() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
