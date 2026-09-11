package org.fungalsentinel.app

import org.junit.Assert.assertFalse
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
    fun quantitativeCaptureParameterChangesAreRejected() {
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(exposureTimeNs = 399_000_000L)))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(iso = 401)))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(focusDistanceDiopters = 0.02f)))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(cameraId = "other")))
        assertFalse(RawProfileExtractor.metadataMatches(reference, reference.copy(width = 2000)))
    }
}
