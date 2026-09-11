package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class SpectralAlgorithmsTest {
    private val metadata = CaptureMetadata("0", 20_000_000, 160, 1f, 64.0, 1023, 3, 200, 3000)

    @Test
    fun wavelengthCalibrationUsesBlueAndRedAndValidatesGreen() {
        val expectedSlope = -3.5
        val expectedIntercept = 3900.0
        fun peak(wavelength: Double): DoubleArray {
            val center = expectedSlope * wavelength + expectedIntercept
            return DoubleArray(3000) { pixel -> 3.0 + 500.0 * exp(-0.5 * (pixel - center) * (pixel - center) / 36.0) }
        }
        val profile = SpectralProfile(peak(622.5), peak(522.5), peak(462.5), 50..150, 0.0, metadata)
        val result = SpectralAlgorithms.calibrateWavelength(profile, PositioningWavelengths.DEFAULT)
        assertEquals(expectedSlope, result.slopePixelsPerNm, 0.02)
        assertEquals(expectedIntercept, result.interceptPixels, 3.0)
        assertEquals(0.0, result.validationErrorNm, 0.1)
        assertEquals("PASS", result.qualityMessage)
    }

    @Test
    fun wavelengthCalibrationUsesExplicitCustomWavelengths() {
        val wavelengths = PositioningWavelengths(redNm = 650.0, greenNm = 540.0, blueNm = 450.0)
        val expectedSlope = -3.0
        val expectedIntercept = 3600.0
        fun peak(wavelength: Double): DoubleArray {
            val center = expectedSlope * wavelength + expectedIntercept
            return DoubleArray(3000) { pixel ->
                2.0 + 400.0 * exp(-0.5 * (pixel - center) * (pixel - center) / 36.0)
            }
        }
        val profile = SpectralProfile(
            peak(wavelengths.redNm),
            peak(wavelengths.greenNm),
            peak(wavelengths.blueNm),
            50..150,
            0.0,
            metadata
        )

        val result = SpectralAlgorithms.calibrateWavelength(profile, wavelengths)

        assertEquals(expectedSlope, result.slopePixelsPerNm, 0.02)
        assertEquals(wavelengths.redNm, result.peaks.first { it.name == "R" }.wavelengthNm, 0.0)
        assertEquals(wavelengths.greenNm, result.peaks.first { it.name == "G" }.wavelengthNm, 0.0)
        assertEquals(wavelengths.blueNm, result.peaks.first { it.name == "B" }.wavelengthNm, 0.0)
    }

    @Test
    fun positioningWavelengthValidationRequiresFinitePositiveOrderedValues() {
        assertTrue(PositioningWavelengths.parse("622.5", "522.5", "462.5").isSuccess)
        assertTrue(PositioningWavelengths.parse("Infinity", "522.5", "462.5").isFailure)
        assertTrue(PositioningWavelengths.parse("622.5", "400", "462.5").isFailure)
        assertTrue(PositioningWavelengths.parse("622.5", "522.5", "0").isFailure)
        assertTrue(PositioningWavelengths.parse("", "522.5", "462.5").isFailure)
    }

    @Test
    fun wavelengthInputsDefaultToRequestedValuesAndExposeCaptureValidity() {
        val state = FssaUiState()
        assertEquals("622.5", state.redWavelengthInput)
        assertEquals("522.5", state.greenWavelengthInput)
        assertEquals("462.5", state.blueWavelengthInput)
        assertEquals(PositioningWavelengths.DEFAULT, state.positioningWavelengths)
        assertTrue(state.usesDefaultPositioningWavelengths)
        assertFalse(state.copy(redWavelengthInput = "620").usesDefaultPositioningWavelengths)
        assertEquals(null, state.copy(blueWavelengthInput = "700").positioningWavelengths)
    }

    @Test
    fun responseAndSampleRemainAscendingAndProducePositiveAreaForNegativeMapping() {
        val size = 3000
        val calibration = WavelengthCalibration(-3.5, 3900.0, 0.0, emptyList(), 0..10)
        val broad = DoubleArray(size) { 100.0 }
        val responseProfile = SpectralProfile(broad, broad, broad, 0..10, 0.0, metadata)
        val spd = SpectralAlgorithms.SpdData(
            doubleArrayOf(380.0, 420.0, 550.0, 680.0, 750.0),
            doubleArrayOf(20.0, 80.0, 100.0, 80.0, 20.0)
        )
        val response = SpectralAlgorithms.calibrateResponse(responseProfile, calibration, spd)
        assertTrue((1 until response.wavelengthsNm.size).all { response.wavelengthsNm[it] > response.wavelengthsNm[it - 1] })

        val sampleGreen = DoubleArray(size) { pixel ->
            val wavelength = calibration.pixelToWavelength(pixel.toDouble())
            4.0 + 200.0 * exp(-0.5 * (wavelength - 527.0) * (wavelength - 527.0) / 25.0)
        }
        val sample = SpectralProfile(broad, sampleGreen, broad, 0..10, 0.0, metadata)
        val analysis = SpectralAlgorithms.analyzeSample(sample, calibration, response, Fluorophore.supported.first())
        assertTrue(analysis.area > 0.0)
        assertTrue(analysis.peak > 0.0)
        assertEquals(response.validRangeNm, analysis.validRangeNm)
        assertTrue((1 until analysis.wavelengthsNm.size).all { analysis.wavelengthsNm[it] > analysis.wavelengthsNm[it - 1] })
    }

    @Test
    fun concentrationRegressionReportsInterpolationAndExtrapolation() {
        val standards = listOf(
            StandardMeasurement(0.0, 2.0),
            StandardMeasurement(1.0, 5.0),
            StandardMeasurement(2.0, 8.0)
        )
        val inside = SpectralAlgorithms.calculateConcentration(standards, 6.5)
        assertEquals(3.0, inside.slope, 1e-10)
        assertEquals(2.0, inside.intercept, 1e-10)
        assertEquals(1.0, inside.rSquared, 1e-10)
        assertEquals(1.5, inside.sampleConcentration, 1e-10)
        assertFalse(inside.outsideCalibrationRange)
        assertTrue(SpectralAlgorithms.calculateConcentration(standards, 11.0).outsideCalibrationRange)
    }

    @Test
    fun defaultSpdMatchesReferenceFallbackRange() {
        val spd = SpectralAlgorithms.defaultSpd()
        assertEquals(380.0, spd.wavelengthsNm.first(), 0.0)
        assertEquals(750.0, spd.wavelengthsNm.last(), 0.0)
        assertEquals(120.0, spd.intensity[170], 1e-10)
    }

    @Test
    fun csvParserSkipsHeaderSortsAndDeduplicates() {
        val parsed = SpectralAlgorithms.parseSpdCsv("Wavelength_nm,Intensity\n550,1\n420,0.5\n550,2\n680,0.4")
        assertTrue(parsed.wavelengthsNm.contentEquals(doubleArrayOf(420.0, 550.0, 680.0)))
        assertTrue(parsed.intensity.contentEquals(doubleArrayOf(0.5, 1.0, 0.4)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun csvParserRejectsNegativeIntensity() {
        SpectralAlgorithms.parseSpdCsv("420,0.5\n550,-0.1\n680,0.4")
    }
}
