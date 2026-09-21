package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformTest {
    @Test
    fun `rear camera rotation follows sensor minus display rotation`() {
        assertEquals(90, PreviewTransform.relativeRotationDegrees(90, 0, frontFacing = false))
        assertEquals(0, PreviewTransform.relativeRotationDegrees(90, 90, frontFacing = false))
        assertEquals(180, PreviewTransform.relativeRotationDegrees(90, 270, frontFacing = false))
    }

    @Test
    fun `front camera rotation follows sensor plus display rotation`() {
        assertEquals(270, PreviewTransform.relativeRotationDegrees(270, 0, frontFacing = true))
        assertEquals(0, PreviewTransform.relativeRotationDegrees(270, 90, frontFacing = true))
    }

    @Test
    fun `portrait transform uses producer sensor rotation and center crops`() {
        val values = PreviewTransform.matrixValues(
            1080, 2400, 1920, 1080,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 0,
            frontFacing = false
        )
        val bounds = transformedViewBounds(1080, 2400, values)

        assertTrue(bounds.width >= 1080.0 - TOLERANCE)
        assertEquals(2400.0, bounds.height, TOLERANCE)
        assertEquals(540.0, bounds.centerX, TOLERANCE)
        assertEquals(1200.0, bounds.centerY, TOLERANCE)
        assertEquals(1080.0 / 1920.0, bounds.width / bounds.height, TOLERANCE)
        assertEquals(0.0, values[1].toDouble(), TOLERANCE)
        assertEquals(0.0, values[3].toDouble(), TOLERANCE)
    }

    @Test
    fun `landscape transform applies residual display rotation without stretching`() {
        val values = PreviewTransform.matrixValues(
            2400, 1080, 1920, 1080,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 90,
            frontFacing = false
        )
        val bounds = transformedViewBounds(2400, 1080, values)

        assertEquals(2400.0, bounds.width, TOLERANCE)
        assertTrue(bounds.height > 1080.0)
        assertEquals(1920.0 / 1080.0, bounds.width / bounds.height, TOLERANCE)
        assertEquals(1200.0, bounds.centerX, TOLERANCE)
        assertEquals(540.0, bounds.centerY, TOLERANCE)
        assertTrue(values[1] > 0f)
        assertTrue(values[3] < 0f)
    }

    @Test
    fun `opposite landscape orientations differ by 180 degrees`() {
        val reverseValues = PreviewTransform.matrixValues(
            2400, 1080, 1920, 1080,
            sensorOrientationDegrees = 90,
            displayRotationDegrees = 270,
            frontFacing = false
        )
        val bounds = transformedViewBounds(2400, 1080, reverseValues)

        assertEquals(2400.0, bounds.width, TOLERANCE)
        assertEquals(1350.0, bounds.height, TOLERANCE)
        assertEquals(1200.0, bounds.centerX, TOLERANCE)
        assertEquals(540.0, bounds.centerY, TOLERANCE)
        assertTrue(reverseValues[1] < 0f)
        assertTrue(reverseValues[3] > 0f)
    }

    @Test
    fun `front preview mirrors around the view center`() {
        val values = PreviewTransform.matrixValues(
            1000, 1000, 1000, 1000,
            sensorOrientationDegrees = 0,
            displayRotationDegrees = 0,
            frontFacing = true
        )
        assertEquals(-1.0, values[0].toDouble(), TOLERANCE)
        assertEquals(1000.0, values[2].toDouble(), TOLERANCE)
    }

    private fun transformedViewBounds(width: Int, height: Int, matrix: FloatArray): Bounds {
        val corners = listOf(
            0.0 to 0.0,
            width.toDouble() to 0.0,
            0.0 to height.toDouble(),
            width.toDouble() to height.toDouble()
        ).map { (x, y) ->
            (matrix[0] * x + matrix[1] * y + matrix[2]) to
                (matrix[3] * x + matrix[4] * y + matrix[5])
        }
        return Bounds(
            left = corners.minOf { it.first },
            top = corners.minOf { it.second },
            right = corners.maxOf { it.first },
            bottom = corners.maxOf { it.second }
        )
    }

    private data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2.0
        val centerY get() = (top + bottom) / 2.0
    }

    private companion object {
        const val TOLERANCE = 0.001
    }
}
