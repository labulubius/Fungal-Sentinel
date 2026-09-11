package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun analysisFlowStartsWithProjectNameAndNumbersAllFiveSteps() {
        val state = FssaUiState()
        assertEquals(AnalysisStep.PROJECT, state.step)
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            AnalysisStep.entries.map { it.number }
        )
        assertEquals("Project name", AnalysisStep.PROJECT.title)
    }

    @Test
    fun projectNamesRequireUsableFileNameContent() {
        assertTrue(isValidProjectName("EGFP 样品 A"))
        assertFalse(isValidProjectName(""))
        assertFalse(isValidProjectName("///"))
        assertFalse(isValidProjectName("a".repeat(101)))
        assertEquals("EGFP_样品_A", safeProjectFileComponent("EGFP 样品 A"))
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
    fun averageProfilesAcceptsNormalizedDifferentExposuresAndLimitsBatchSize() {
        fun profile(value: Double, exposure: Long = metadata.exposureTimeNs) = SpectralProfile(
            DoubleArray(4) { value }, DoubleArray(4) { value + 1.0 }, DoubleArray(4) { value + 2.0 },
            2..5, 0.1, metadata.copy(exposureTimeNs = exposure, height = 4)
        )
        val average = SpectralAlgorithms.averageProfiles(listOf(profile(2.0), profile(4.0, 40_000_000)))
        assertArrayEquals(DoubleArray(4) { 3.0 }, average.red, 0.0)
        assertArrayEquals(DoubleArray(4) { 4.0 }, average.green, 0.0)
        assertEquals(0.1, average.saturatedFraction, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            SpectralAlgorithms.averageProfiles(List(6) { profile(it.toDouble()) })
        }
    }

    @Test
    fun batchAnalysisAveragesBlankClipsEachSampleAndReportsSampleSd() {
        val size = 60
        val calibration = WavelengthCalibration(1.0, -500.0, 0.0, emptyList(), 2..5)
        fun profile(signal: Double, exposure: Long = metadata.exposureTimeNs) = SpectralProfile(
            DoubleArray(size) { signal }, DoubleArray(size) { signal }, DoubleArray(size) { signal },
            2..5, 0.0, metadata.copy(exposureTimeNs = exposure, height = size)
        )
        val wavelengths = DoubleArray(size) { 500.0 + it }
        val ones = DoubleArray(size) { 1.0 }
        val response = SpectralResponse(wavelengths, ones, ones, ones, 500.0..559.0)

        val result = SpectralAlgorithms.analyzeSamples(
            listOf(profile(8.0), profile(12.0, 30_000_000)),
            listOf(profile(12.0), profile(14.0)),
            calibration,
            response,
            Fluorophore.supported.first()
        )

        assertArrayEquals(doubleArrayOf(40.0, 80.0), result.replicateAreas, 1e-10)
        assertEquals(60.0, result.area, 1e-10)
        assertEquals(kotlin.math.sqrt(800.0), result.sampleStandardDeviation, 1e-10)
        assertEquals(4.0, result.peak, 1e-10)
        assertTrue((1 until result.wavelengthsNm.size).all { result.wavelengthsNm[it] > result.wavelengthsNm[it - 1] })
    }

    @Test
    fun batchAnalysisRejectsAnEmptyTargetResponseChannel() {
        val size = 60
        val calibration = WavelengthCalibration(1.0, -500.0, 0.0, emptyList(), 2..5)
        val profile = SpectralProfile(
            DoubleArray(size) { 1.0 }, DoubleArray(size) { 1.0 }, DoubleArray(size) { 1.0 },
            2..5, 0.0, metadata.copy(height = size)
        )
        val wavelengths = DoubleArray(size) { 500.0 + it }
        val response = SpectralResponse(
            wavelengths,
            DoubleArray(size) { 1.0 },
            DoubleArray(size),
            DoubleArray(size) { 1.0 },
            500.0..559.0
        )
        assertThrows(IllegalArgumentException::class.java) {
            SpectralAlgorithms.analyzeSamples(
                listOf(profile), listOf(profile), calibration, response, Fluorophore.supported.first()
            )
        }
    }

    @Test
    fun savitzkyGolayUsesQuadraticWindow21Coefficients() {
        val impulse = DoubleArray(61).also { it[30] = 1.0 }
        val filtered = SpectralAlgorithms.savitzkyGolay21QuadraticNearest(impulse)
        assertEquals(329.0 / 3059.0, filtered[30], 1e-12)
        assertEquals(324.0 / 3059.0, filtered[29], 1e-12)

        val quadratic = DoubleArray(61) { i -> 2.0 + 3.0 * i + 0.5 * i * i }
        val smooth = SpectralAlgorithms.savitzkyGolay21QuadraticNearest(quadratic)
        assertEquals(quadratic[30], smooth[30], 1e-9)
    }

    @Test
    fun modelReplicatesExposeMeanSampleSdAndBatchCaptureNames() {
        val standard = StandardMeasurement(2.0, doubleArrayOf(10.0, 14.0, 18.0))
        assertEquals(14.0, standard.area, 0.0)
        assertEquals(4.0, standard.sampleStandardDeviation, 0.0)
        assertTrue(AnalysisCapturePurpose.SAMPLE != AnalysisCapturePurpose.SAMPLE_BLANK)
        assertTrue(AnalysisCapturePurpose.STANDARD_BLANK != AnalysisCapturePurpose.STANDARD_SAMPLE)

        assertThrows(IllegalArgumentException::class.java) {
            FssaUiState(sampleProfiles = List(6) {
                SpectralProfile(DoubleArray(1), DoubleArray(1), DoubleArray(1), 0..0, 0.0, metadata)
            })
        }
    }

    @Test
    fun standardConcentrationMustBeFiniteAndNonNegative() {
        assertThrows(IllegalArgumentException::class.java) { StandardMeasurement(-1.0, 2.0) }
        assertThrows(IllegalArgumentException::class.java) { StandardMeasurement(Double.NaN, 2.0) }
        assertEquals(0.0, StandardMeasurement(0.0, 2.0).concentration, 0.0)
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
