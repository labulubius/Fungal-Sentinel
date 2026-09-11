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
    fun `portrait transform uses rotated buffer aspect and center crops`() {
        val values = PreviewTransform.matrixValues(1080, 2400, 1920, 1080, 90, false)
        val bounds = transformedViewBounds(1080, 2400, values)

        assertTrue(bounds.width >= 1080.0 - TOLERANCE)
        assertEquals(2400.0, bounds.height, TOLERANCE)
        assertEquals(540.0, bounds.centerX, TOLERANCE)
        assertEquals(1200.0, bounds.centerY, TOLERANCE)
        assertEquals(1080.0 / 1920.0, bounds.width / bounds.height, TOLERANCE)
    }

    @Test
    fun `landscape transform center crops without stretching`() {
        val values = PreviewTransform.matrixValues(2400, 1080, 1920, 1080, 0, false)
        val bounds = transformedViewBounds(2400, 1080, values)

        assertEquals(2400.0, bounds.width, TOLERANCE)
        assertTrue(bounds.height > 1080.0)
        assertEquals(1920.0 / 1080.0, bounds.width / bounds.height, TOLERANCE)
        assertEquals(1200.0, bounds.centerX, TOLERANCE)
        assertEquals(540.0, bounds.centerY, TOLERANCE)
    }

    @Test
    fun `front preview mirrors around the view center`() {
        val values = PreviewTransform.matrixValues(1000, 1000, 1000, 1000, 0, true)
        assertEquals(-1.0, values[0].toDouble(), TOLERANCE)
        assertEquals(1000.0, values[2].toDouble(), TOLERANCE)
    }

    private fun transformedViewBounds(width: Int, height: Int, matrix: FloatArray): Bounds {
        val left = matrix[2].toDouble()
        val top = matrix[5].toDouble()
        val right = (matrix[0] * width + matrix[2]).toDouble()
        val bottom = (matrix[4] * height + matrix[5]).toDouble()
        return Bounds(
            left = minOf(left, right),
            top = minOf(top, bottom),
            right = maxOf(left, right),
            bottom = maxOf(top, bottom)
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
