package org.fungalsentinel.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawProfileExtractorTest {
    private val reference = CaptureMetadata(
        cameraId = "rear",
        exposureTimeNs = 400_000_000L,
        iso = 400,
        focusDistanceDiopters = 0f,
        blackLevel = 64.0,
        whiteLevel = 1023,
        cfaArrangement = 0,
        width = 4000,
        height = 3000
    )

    @Test
    fun matchingCaptureParametersAreAccepted() {
        assertTrue(RawProfileExtractor.metadataMatches(reference, reference.copy(blackLevel = 65.0)))
        assertTrue(RawProfileExtractor.metadataMatches(reference, reference.copy(focusDistanceDiopters = 0.005f)))
    }

    @Test
    fun exposureMayDifferAfterNormalizationButOtherCaptureParametersMayNot() {
        assertTrue(RawProfileExtractor.metadataMatches(reference, reference.copy(exposureTimeNs = 399_000_000L)))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(iso = 401)))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(focusDistanceDiopters = 0.02f)))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(cameraId = "other")))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(width = 2000)))
    }

    @Test
    fun profilesAreNormalizedToExposureSeconds() {
        val values = doubleArrayOf(2.0, 4.0)
        RawProfileExtractor.normalizeForExposure(values, 500_000_000L)
        assertArrayEquals(doubleArrayOf(4.0, 8.0), values, 0.0)

        assertThrows(IllegalArgumentException::class.java) {
            RawProfileExtractor.normalizeForExposure(doubleArrayOf(3.0), 0L)
        }
    }

    @Test
    fun fixedRoiMustBeInsideImageAndAtLeastFourColumns() {
        RawProfileExtractor.validateFixedXRoi(2..5, 10)
        assertThrows(IllegalArgumentException::class.java) { RawProfileExtractor.validateFixedXRoi(-1..5, 10) }
        assertThrows(IllegalArgumentException::class.java) { RawProfileExtractor.validateFixedXRoi(7..10, 10) }
        assertThrows(IllegalArgumentException::class.java) { RawProfileExtractor.validateFixedXRoi(2..4, 10) }
    }

    @Test
    fun measuredZeroIsNotTreatedAsMissing() {
        val values = doubleArrayOf(8.0, 0.0, 4.0)
        RawProfileExtractor.fillMissingRows(values, booleanArrayOf(true, true, true))
        assertArrayEquals(doubleArrayOf(8.0, 0.0, 4.0), values, 0.0)
    }

    @Test
    fun missingRowsUseAdjacentValidSamples() {
        val values = doubleArrayOf(0.0, 2.0, 0.0, 6.0, 0.0)
        RawProfileExtractor.fillMissingRows(values, booleanArrayOf(false, true, false, true, false))
        assertArrayEquals(doubleArrayOf(2.0, 2.0, 4.0, 6.0, 6.0), values, 0.0)
    }

    @Test
    fun channelWithoutSamplesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RawProfileExtractor.fillMissingRows(doubleArrayOf(0.0, 0.0), booleanArrayOf(false, false))
        }
    }
}
