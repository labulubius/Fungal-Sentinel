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

data class PositioningWavelengths(
    val redNm: Double,
    val greenNm: Double,
    val blueNm: Double
) {
    init {
        require(redNm.isFinite() && greenNm.isFinite() && blueNm.isFinite()) {
            "R/G/B wavelengths must be finite numbers."
        }
        require(blueNm > 0.0 && blueNm < greenNm && greenNm < redNm) {
            "Wavelengths must be positive and ordered B < G < R."
        }
    }

    companion object {
        val DEFAULT = PositioningWavelengths(redNm = 622.5, greenNm = 522.5, blueNm = 462.5)

        fun parse(red: String, green: String, blue: String): Result<PositioningWavelengths> = runCatching {
            val values = listOf(red, green, blue).map {
                it.trim().toDoubleOrNull() ?: error("R/G/B wavelengths must be numbers.")
            }
            PositioningWavelengths(values[0], values[1], values[2])
        }
    }
}

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
    val redWavelengthInput: String = PositioningWavelengths.DEFAULT.redNm.toString(),
    val greenWavelengthInput: String = PositioningWavelengths.DEFAULT.greenNm.toString(),
    val blueWavelengthInput: String = PositioningWavelengths.DEFAULT.blueNm.toString(),
    val wavelengthCalibration: WavelengthCalibration? = null,
    val spectralResponse: SpectralResponse? = null,
    val sampleAnalysis: SampleAnalysis? = null,
    val standards: List<StandardMeasurement> = emptyList(),
    val concentrationResult: ConcentrationResult? = null,
    val selectedFluorophore: Fluorophore = Fluorophore.supported.first(),
    val lockedMetadata: CaptureMetadata? = null,
    val lastProfile: SpectralProfile? = null,
    val spdData: SpectralAlgorithms.SpdData? = null,
    val spdSource: String = SPD_NOT_LOADED_SOURCE,
    val standardConcentrationInput: String = "",
    val pendingCapture: AnalysisCapturePurpose? = null,
    val logs: List<String> = listOf("FSSA v1.1 ready. All analysis is performed offline.")
) {
    val positioningWavelengths: PositioningWavelengths?
        get() = PositioningWavelengths.parse(
            redWavelengthInput,
            greenWavelengthInput,
            blueWavelengthInput
        ).getOrNull()

    val wavelengthValidationMessage: String?
        get() = PositioningWavelengths.parse(
            redWavelengthInput,
            greenWavelengthInput,
            blueWavelengthInput
        ).exceptionOrNull()?.message

    val usesDefaultPositioningWavelengths: Boolean
        get() = positioningWavelengths == PositioningWavelengths.DEFAULT

    companion object {
        const val BUILT_IN_SPD_SOURCE = "Built-in true_spd.csv"
        const val SPD_NOT_LOADED_SOURCE = "No SPD loaded"
    }
}
