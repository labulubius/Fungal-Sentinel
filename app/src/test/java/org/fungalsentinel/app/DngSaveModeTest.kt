package org.fungalsentinel.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DngSaveModeTest {
    @Test
    fun allSavesEveryCapture() {
        AnalysisCapturePurpose.entries.forEach { assertTrue(DngSaveMode.ALL.shouldSave(it)) }
        assertTrue(DngSaveMode.ALL.shouldSave(null))
    }

    @Test
    fun keyCapturesSavesOnlyUnknownAndStandardSamples() {
        assertTrue(DngSaveMode.KEY_CAPTURES.shouldSave(AnalysisCapturePurpose.SAMPLE))
        assertTrue(DngSaveMode.KEY_CAPTURES.shouldSave(AnalysisCapturePurpose.STANDARD_SAMPLE))
        assertFalse(DngSaveMode.KEY_CAPTURES.shouldSave(AnalysisCapturePurpose.SAMPLE_BLANK))
        assertFalse(DngSaveMode.KEY_CAPTURES.shouldSave(AnalysisCapturePurpose.POSITIONING))
        assertFalse(DngSaveMode.KEY_CAPTURES.shouldSave(null))
    }

    @Test
    fun noneNeverSavesDng() {
        AnalysisCapturePurpose.entries.forEach { assertFalse(DngSaveMode.NONE.shouldSave(it)) }
        assertFalse(DngSaveMode.NONE.shouldSave(null))
    }
}
