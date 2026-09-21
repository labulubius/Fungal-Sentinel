package org.fungalsentinel.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Camera sensor-buffer to center-cropped TextureView transform calculations. */
object PreviewTransform {
    fun relativeRotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean
    ): Int {
        val rotation = if (frontFacing) {
            sensorOrientationDegrees + displayRotationDegrees
        } else {
            sensorOrientationDegrees - displayRotationDegrees
        }
        return ((rotation % 360) + 360) % 360
    }

    fun matrixValues(
        viewWidth: Int,
        viewHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean
    ): FloatArray {
        require(viewWidth > 0 && viewHeight > 0 && bufferWidth > 0 && bufferHeight > 0)

        val sensorRotation = normalizeDegrees(sensorOrientationDegrees)
        val displayRotation = normalizeDegrees(displayRotationDegrees)
        val relativeRotation = relativeRotationDegrees(sensorRotation, displayRotation, frontFacing)
        val finalDimensionsAreSwapped = relativeRotation == 90 || relativeRotation == 270
        val finalWidth = if (finalDimensionsAreSwapped) bufferHeight.toDouble() else bufferWidth.toDouble()
        val finalHeight = if (finalDimensionsAreSwapped) bufferWidth.toDouble() else bufferHeight.toDouble()
        val scale = max(viewWidth / finalWidth, viewHeight / finalHeight)

        // TextureView consumes SurfaceTexture's producer matrix, so sensor orientation is
        // already applied. Apply only the remaining display rotation here.
        val producerDimensionsAreSwapped = sensorRotation == 90 || sensorRotation == 270
        val producerWidth = if (producerDimensionsAreSwapped) bufferHeight.toDouble() else bufferWidth.toDouble()
        val producerHeight = if (producerDimensionsAreSwapped) bufferWidth.toDouble() else bufferHeight.toDouble()
        val correctionDegrees = normalizeDegrees(if (frontFacing) displayRotation else -displayRotation)
        val radians = Math.toRadians(correctionDegrees.toDouble())
        val cosine = snapRightAngle(cos(radians))
        val sine = snapRightAngle(sin(radians))
        val producerScaleX = scale * producerWidth / viewWidth
        val producerScaleY = scale * producerHeight / viewHeight
        var a = cosine * producerScaleX
        var c = -sine * producerScaleY
        val b = sine * producerScaleX
        val d = cosine * producerScaleY
        if (frontFacing) {
            // Mirror in display coordinates, after rotation.
            a = -a
            c = -c
        }

        val centerX = viewWidth / 2.0
        val centerY = viewHeight / 2.0
        val translateX = centerX - a * centerX - c * centerY
        val translateY = centerY - b * centerX - d * centerY
        return floatArrayOf(
            a.toFloat(), c.toFloat(), translateX.toFloat(),
            b.toFloat(), d.toFloat(), translateY.toFloat(),
            0f, 0f, 1f
        )
    }

    private fun normalizeDegrees(value: Int): Int = ((value % 360) + 360) % 360

    private fun snapRightAngle(value: Double): Double = if (abs(value) < 1e-10) 0.0 else value
}
