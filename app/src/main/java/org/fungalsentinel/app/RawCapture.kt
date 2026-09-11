package org.fungalsentinel.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns a captured RAW image until storage and analysis have finished.
 * Closing the lease releases both the Image and the CameraController reader lease.
 */
class RawCapture internal constructor(
    val image: Image,
    val result: TotalCaptureResult,
    val cameraCharacteristics: CameraCharacteristics,
    val cameraId: String,
    val captureToken: Long,
    private val onClosed: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            image.close()
        } finally {
            onClosed()
        }
    }
}
