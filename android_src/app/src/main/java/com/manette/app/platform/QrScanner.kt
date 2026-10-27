package com.manette.app.platform

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scans a QR code with Google's code scanner (part of Google Play services): no camera permission and no CameraX
 * needed. On phones without Play services it fails with a message, and pasting the code or link still works.
 * [onResult] gets the QR text, or null if the user backed out.
 * NOTE: not compiled.
 */
fun scanQr(context: Context, onResult: (String?) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { onResult(it.rawValue) }
        .addOnCanceledListener { onResult(null) }
        .addOnFailureListener { onError("QR scanning is not available on this phone (${it.message}). Paste the code or link instead.") }
}
