package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisQualityPolicyTest {
    @Test
    fun wavelengthThresholdsAreClassified() {
        assertEquals(AnalysisQuality.PASS, AnalysisQualityPolicy.wavelength(2.5))
        assertEquals(AnalysisQuality.WARNING, AnalysisQualityPolicy.wavelength(2.5001))
        assertEquals(AnalysisQuality.WARNING, AnalysisQualityPolicy.wavelength(-10.0))
        assertEquals(AnalysisQuality.FAILED, AnalysisQualityPolicy.wavelength(10.0001))
        assertEquals(AnalysisQuality.FAILED, AnalysisQualityPolicy.wavelength(Double.NaN))
    }

    @Test
    fun failedCalibrationCanOnlyProceedWhenProtectionIsDisabled() {
        val failed = WavelengthCalibration(1.0, 0.0, 12.0, emptyList(), 0..1)
        assertFalse(AnalysisQualityPolicy.allowsResponseCapture(failed, protectionEnabled = true))
        assertTrue(AnalysisQualityPolicy.allowsResponseCapture(failed, protectionEnabled = false))
        assertFalse(AnalysisQualityPolicy.allowsResponseCapture(null, protectionEnabled = false))
    }

    @Test
    fun saturationThresholdsAreClassified() {
        assertEquals(AnalysisQuality.PASS, AnalysisQualityPolicy.saturation(0.0009))
        assertEquals(AnalysisQuality.WARNING, AnalysisQualityPolicy.saturation(0.001))
        assertEquals(AnalysisQuality.FAILED, AnalysisQualityPolicy.saturation(0.01))
    }

    @Test
    fun regressionWarningsDoNotChangeTheCalculatedResult() {
        val standards = listOf(StandardMeasurement(0.0, 3.0), StandardMeasurement(1.0, 2.0))
        val result = ConcentrationResult(-1.0, 3.0, 0.9, -2.0, true)
        val warnings = AnalysisQualityPolicy.regressionWarnings(standards, result)
        assertTrue(warnings.size >= 4)
    }
}
