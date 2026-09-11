package org.fungalsentinel.app

/** The four analysis stages exposed by the on-device FSSA workflow. */
enum class AnalysisStep(val number: Int, val title: String) {
    POSITIONING(1, "Wavelength calibration"),
    RESPONSE(2, "Spectral response"),
    SAMPLE(3, "Sample analysis"),
    CONCENTRATION(4, "Concentration")
}

data class CaptureMetadata(
    val cameraId: String,
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistanceDiopters: Float,
    val blackLevel: Double,
    val whiteLevel: Int,
    val cfaArrangement: Int,
    val width: Int,
    val height: Int
)

data class SpectralProfile(
    val red: DoubleArray,
    val green: DoubleArray,
    val blue: DoubleArray,
    val xRoi: IntRange,
    val saturatedFraction: Double,
    val metadata: CaptureMetadata
) {
    val size: Int get() = green.size
}

data class SpectralPeak(
    val name: String,
    val wavelengthNm: Double,
    val channelName: String,
    val centroidPixel: Double,
    val maximumPixel: Int,
    val peakHeight: Double
)

data class WavelengthCalibration(
    val slopePixelsPerNm: Double,
    val interceptPixels: Double,
    val validationErrorNm: Double,
    val peaks: List<SpectralPeak>,
    val xRoi: IntRange
) {
    fun wavelengthToPixel(wavelengthNm: Double): Double =
        slopePixelsPerNm * wavelengthNm + interceptPixels

    fun pixelToWavelength(pixel: Double): Double =
        (pixel - interceptPixels) / slopePixelsPerNm

    val qualityMessage: String
        get() = when {
            validationErrorNm.isNaN() -> "FAILED"
            kotlin.math.abs(validationErrorNm) <= 2.5 -> "PASS"
            kotlin.math.abs(validationErrorNm) <= 10.0 -> "WARNING"
            else -> "FAILED"
        }
}

data class SpectralResponse(
    /** Always strictly increasing. */
    val wavelengthsNm: DoubleArray,
    val red: DoubleArray,
    val green: DoubleArray,
    val blue: DoubleArray,
    val validRangeNm: ClosedFloatingPointRange<Double> = 420.0..680.0
)

data class Fluorophore(
    val displayName: String,
    val peakWavelengthNm: Double,
    val channel: SpectralChannel,
    val integrationWidthNm: Double
) {
    companion object {
        val supported = listOf(
            Fluorophore("Ypet (Venus variant)", 527.0, SpectralChannel.GREEN, 20.0),
            Fluorophore("EGFP", 509.0, SpectralChannel.GREEN, 20.0),
            Fluorophore("mCherry", 610.0, SpectralChannel.RED, 25.0),
            Fluorophore("CFP", 475.0, SpectralChannel.BLUE, 20.0),
            Fluorophore("mTurquoise2", 482.0, SpectralChannel.BLUE, 20.0)
        )
    }
}

enum class SpectralChannel { RED, GREEN, BLUE }

enum class AnalysisCapturePurpose { POSITIONING, RESPONSE, SAMPLE, STANDARD }

data class SampleAnalysis(
    /** Always strictly increasing and safe for interpolation/integration. */
    val wavelengthsNm: DoubleArray,
    val correctedIntensity: DoubleArray,
    val area: Double,
    val peak: Double,
    val fluorophore: Fluorophore,
    /** Range where response correction is calibrated and chart values are meaningful. */
    val validRangeNm: ClosedFloatingPointRange<Double> = 420.0..680.0
)

data class StandardMeasurement(val concentration: Double, val area: Double)

data class ConcentrationResult(
    val slope: Double,
    val intercept: Double,
    val rSquared: Double,
    val sampleConcentration: Double,
    val outsideCalibrationRange: Boolean
)

data class FssaUiState(
    val visible: Boolean = false,
    val step: AnalysisStep = AnalysisStep.POSITIONING,
    val status: String = "Ready to capture the RGB positioning source.",
    val busy: Boolean = false,
    val wavelengthCalibration: WavelengthCalibration? = null,
    val spectralResponse: SpectralResponse? = null,
    val sampleAnalysis: SampleAnalysis? = null,
    val standards: List<StandardMeasurement> = emptyList(),
    val concentrationResult: ConcentrationResult? = null,
    val selectedFluorophore: Fluorophore = Fluorophore.supported.first(),
    val lockedMetadata: CaptureMetadata? = null,
    val lastProfile: SpectralProfile? = null,
    val spdData: SpectralAlgorithms.SpdData? = null,
    val spdFileName: String? = null,
    val standardConcentrationInput: String = "",
    val pendingCapture: AnalysisCapturePurpose? = null,
    val logs: List<String> = listOf("FSSA v1.1 ready. All analysis is performed offline.")
)
