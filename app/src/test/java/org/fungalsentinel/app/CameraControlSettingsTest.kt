package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlSettingsTest {

    @Test
    fun defaultManualSettingsMatchCurrentCaptureDefaults() {
        val settings = CameraControlSettings.manualDefaults()

        assertTrue(settings.manualControlsEnabled)
        assertEquals(10_000_000L, settings.exposureTimeNs)
        assertEquals(400, settings.iso)
        assertEquals(2.0f, settings.focusDistanceDiopters)
        assertFalse(settings.autoWhiteBalanceEnabled)
        assertFalse(settings.noiseReductionEnabled)
        assertFalse(settings.edgeEnhancementEnabled)
        assertFalse(settings.hotPixelCorrectionEnabled)
    }

    @Test
    fun automaticModeDisablesManualProcessingControls() {
        val settings = CameraControlSettings.automatic()

        assertFalse(settings.manualControlsEnabled)
        assertTrue(settings.autoWhiteBalanceEnabled)
        assertTrue(settings.noiseReductionEnabled)
        assertTrue(settings.edgeEnhancementEnabled)
        assertTrue(settings.hotPixelCorrectionEnabled)
    }

    @Test
    fun clampedToDeviceRangesKeepsValuesInsideSupportedBounds() {
        val settings = CameraControlSettings(
            manualControlsEnabled = true,
            exposureTimeNs = 1_000L,
            iso = 20_000,
            focusDistanceDiopters = 99.0f,
            autoWhiteBalanceEnabled = false,
            noiseReductionEnabled = false,
            edgeEnhancementEnabled = false,
            hotPixelCorrectionEnabled = false
        )

        val clamped = settings.clampedTo(
            CameraControlRanges(
                exposureTimeNs = 5_000_000L..100_000_000L,
                iso = 50..3_200,
                focusDistanceDiopters = 0.0f..8.0f
            )
        )

        assertEquals(5_000_000L, clamped.exposureTimeNs)
        assertEquals(3_200, clamped.iso)
        assertEquals(8.0f, clamped.focusDistanceDiopters)
    }
}
