package org.fungalsentinel.app

import kotlin.math.max

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
        rotationDegrees: Int,
        mirrorHorizontally: Boolean
    ): FloatArray {
        require(viewWidth > 0 && viewHeight > 0 && bufferWidth > 0 && bufferHeight > 0)

        val dimensionsAreSwapped = rotationDegrees == 90 || rotationDegrees == 270
        val orientedWidth = if (dimensionsAreSwapped) bufferHeight.toDouble() else bufferWidth.toDouble()
        val orientedHeight = if (dimensionsAreSwapped) bufferWidth.toDouble() else bufferHeight.toDouble()
        val scale = max(viewWidth / orientedWidth, viewHeight / orientedHeight)
        val displayedWidth = orientedWidth * scale
        val displayedHeight = orientedHeight * scale
        val scaleX = displayedWidth / viewWidth * if (mirrorHorizontally) -1.0 else 1.0
        val scaleY = displayedHeight / viewHeight
        val translateX = (viewWidth - scaleX * viewWidth) / 2.0
        val translateY = (viewHeight - displayedHeight) / 2.0

        // SurfaceTexture already presents camera pixels in display orientation. This
        // matrix corrects its implicit stretch to the view bounds and center-crops.
        return floatArrayOf(
            scaleX.toFloat(), 0f, translateX.toFloat(),
            0f, scaleY.toFloat(), translateY.toFloat(),
            0f, 0f, 1f
        )
    }
}
