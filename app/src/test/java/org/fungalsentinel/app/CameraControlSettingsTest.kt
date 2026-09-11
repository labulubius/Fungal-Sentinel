package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlSettingsTest {

    @Test
    fun defaultManualSettingsUse400MillisecondsAndInfinityFocus() {
        val settings = CameraControlSettings.manualDefaults()

        assertTrue(settings.manualControlsEnabled)
        assertEquals(400_000_000L, settings.exposureTimeNs)
        assertEquals(400, settings.iso)
        assertEquals(0.0f, settings.focusDistanceDiopters)
        assertEquals("∞ (0 D)", formatFocusDistance(settings.focusDistanceDiopters))
        assertFalse(settings.autoWhiteBalanceEnabled)
        assertFalse(settings.meterThenLockEnabled)
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
    fun defaultExposureIsClampedToDeviceRange() {
        val ranges = CameraControlRanges(
            exposureTimeNs = 1_000_000L..100_000_000L,
            iso = 50..3_200,
            focusDistanceDiopters = 0.0f..8.0f
        )

        assertEquals(100_000_000L, CameraControlSettings.manualDefaults().clampedTo(ranges).exposureTimeNs)
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

    @Test
    fun previewFrameRatePrefersStable30Fps() {
        val selected = PreviewFrameRateSelector.choose(
            listOf(10..10, 5..15, 15..15, 5..24, 24..24, 5..30, 7..30, 30..30)
        )

        assertEquals(30..30, selected)
        assertEquals(15..30, PreviewFrameRateSelector.choose(listOf(5..30, 15..30)))
        assertEquals(24..24, PreviewFrameRateSelector.choose(listOf(5..30, 24..24)))
        assertEquals(24..24, PreviewFrameRateSelector.choose(listOf(15..15, 24..24)))
        assertEquals(null, PreviewFrameRateSelector.choose(emptyList()))
    }

    @Test
    fun logarithmicExposureScaleRoundTripsAndKeeps400msExactPreset() {
        val range = 100_000L..2_000_000_000L
        val target = CameraControlSettings.DEFAULT_EXPOSURE_TIME_NS
        val position = ExposureSliderScale.positionFor(target, range)
        val roundTrip = ExposureSliderScale.exposureTimeAt(position, range)

        assertTrue(position in 0f..1f)
        assertTrue(kotlin.math.abs(roundTrip - target) < 100L)
        assertEquals(target, CameraControlSettings.manualDefaults().clampedTo(
            CameraControlRanges(range, 50..3200, 0f..10f)
        ).exposureTimeNs)
        assertEquals(range.first, ExposureSliderScale.exposureTimeAt(0f, range))
        assertEquals(range.last, ExposureSliderScale.exposureTimeAt(1f, range))
    }

    @Test
    fun meterThenLockRequiresAutoExposureSupportAndConvergence() {
        val autoLock = CameraControlSettings.automatic().copy(meterThenLockEnabled = true)
        val supported = CameraControlSupport(true, false, false, false, false, false, autoExposureLock = true)
        val unsupported = supported.copy(autoExposureLock = false)

        val metering = AutoExposureState()
        assertEquals(ExposureStatus.AUTO_METERING, metering.status(autoLock, supported))
        assertFalse(metering.onAeStable(autoLock, unsupported).lockRequested)

        val requested = metering.onAeStable(autoLock, supported)
        assertTrue(requested.lockRequested)
        assertFalse(requested.locked)
        assertEquals(ExposureStatus.AUTO_METERING, requested.status(autoLock, supported))

        val locked = requested.onLockConfirmed(autoLock, supported)
        assertTrue(locked.locked)
        assertEquals(ExposureStatus.AUTO_LOCKED, locked.status(autoLock, supported))
        assertEquals(ExposureStatus.AUTO_METERING, locked.reset().status(autoLock, supported))
        assertEquals(ExposureStatus.AUTO, AutoExposureState().status(CameraControlSettings.automatic(), supported))
        assertEquals(
            ExposureStatus.MANUAL,
            locked.status(CameraControlSettings.manualDefaults(), supported)
        )
    }
}
