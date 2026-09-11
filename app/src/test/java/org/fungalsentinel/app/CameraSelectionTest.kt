package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraSelectionTest {
    @Test
    fun rawManualBackCameraHasHighestPriority() {
        val selected = CameraSelection.choose(
            listOf(
                CameraCandidate("0", backFacing = true, rawOutput = false, manualSensor = true),
                CameraCandidate("1", backFacing = true, rawOutput = true, manualSensor = false),
                CameraCandidate("2", backFacing = true, rawOutput = true, manualSensor = true)
            )
        )
        assertEquals("2", selected)
    }

    @Test
    fun rawBackCameraBeatsManualBackCameraWithoutRaw() {
        val selected = CameraSelection.choose(
            listOf(
                CameraCandidate("0", backFacing = true, rawOutput = false, manualSensor = true),
                CameraCandidate("1", backFacing = true, rawOutput = true, manualSensor = false)
            )
        )
        assertEquals("1", selected)
    }

    @Test
    fun partialBackCameraIsRetainedAsFallback() {
        val selected = CameraSelection.choose(
            listOf(
                CameraCandidate("front", backFacing = false, rawOutput = true, manualSensor = true),
                CameraCandidate("back", backFacing = true, rawOutput = false, manualSensor = false)
            )
        )
        assertEquals("back", selected)
    }

    @Test
    fun emptyCameraListHasNoSelection() {
        assertNull(CameraSelection.choose(emptyList()))
    }
}
