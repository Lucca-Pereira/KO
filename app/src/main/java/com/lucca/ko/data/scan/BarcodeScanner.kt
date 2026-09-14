package com.lucca.ko.data.scan

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Scanning a barcode off a packet.
 *
 * Uses Play Services' code scanner rather than ML Kit with CameraX. The scan runs in a
 * Google-provided activity, which means **no CAMERA permission** — no rationale dialog, no
 * denial handling, and about fifty lines instead of the three hundred a PreviewView plus
 * ImageAnalysis plus lifecycle binding would take. The trade is a dependency on Play Services,
 * which on a personal phone is not a trade at all.
 *
 * If a custom overlay or continuous scanning is ever wanted, the ML Kit path is a self-contained
 * swap behind this same interface.
 */
class BarcodeScanner(private val context: Context) {

    private val options = GmsBarcodeScannerOptions.Builder()
        // Food packaging is EAN or UPC; accepting QR codes as well would just let the scanner
        // lock on to the wrong thing.
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
        )
        .enableAutoZoom()
        .build()

    private val scanner: GmsBarcodeScanner
        get() = GmsBarcodeScanning.getClient(context, options)

    /** Result of one scan. Cancelling is not a failure — it is the back button. */
    sealed interface Outcome {
        data class Scanned(val barcode: String) : Outcome

        data object Cancelled : Outcome

        data class Failed(val message: String) : Outcome
    }

    suspend fun scan(): Outcome = suspendCancellableCoroutine { continuation ->
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                continuation.resume(
                    if (raw.isNullOrBlank()) {
                        Outcome.Failed("That barcode came back empty.")
                    } else {
                        Outcome.Scanned(raw)
                    },
                )
            }
            .addOnCanceledListener { continuation.resume(Outcome.Cancelled) }
            .addOnFailureListener { error ->
                continuation.resume(Outcome.Failed(describe(error)))
            }
    }

    /**
     * Asks Play Services to fetch the scanner module now.
     *
     * The manifest meta-data usually handles this, but a phone that installed the app offline
     * will not have it — and discovering that while stood in a supermarket is the worst possible
     * time. Best-effort and silent.
     */
    fun prefetchModule() {
        runCatching { ModuleInstall.getClient(context).areModulesAvailable(scanner) }
    }

    private fun describe(error: Throwable): String = when {
        error is MlKitException && error.errorCode == MlKitException.CODE_SCANNER_UNAVAILABLE ->
            "The scanner isn't available on this phone. Add the food by hand instead."
        error is MlKitException && error.errorCode == MlKitException.UNAVAILABLE ->
            "The scanner is still downloading. Try again in a moment, or add it by hand."
        else -> error.message ?: "The scanner failed. Add the food by hand instead."
    }
}
