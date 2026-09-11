package org.fungalsentinel.app

import java.io.Serializable

internal fun isValidProjectName(name: String): Boolean =
    name.trim().length in 1..100 && safeProjectFileComponent(name).isNotBlank()

internal fun safeProjectFileComponent(name: String): String = name.trim()
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
    .replace(Regex("\\s+"), "_")
    .trim('.', '_')
    .take(60)

/** The project setup step followed by the four scientific FSSA analysis stages. */
enum class AnalysisStep(val number: Int, val title: String) {
    PROJECT(1, "Project name"),
    POSITIONING(2, "Wavelength calibration"),
    RESPONSE(3, "Spectral response"),
    SAMPLE(4, "Sample analysis"),
    CONCENTRATION(5, "Concentration")
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
) : Serializable

data class SpectralProfile(
    val red: DoubleArray,
    val green: DoubleArray,
    val blue: DoubleArray,
    val xRoi: IntRange,
    val saturatedFraction: Double,
    val metadata: CaptureMetadata
) : Serializable {
    init {
        require(red.size == green.size && green.size == blue.size) {
            "R/G/B profile channels must have equal lengths."
        }
    }

    val size: Int get() = green.size
}

data class SpectralPeak(
    val name: String,
    val wavelengthNm: Double,
    val channelName: String,
    val centroidPixel: Double,
    val maximumPixel: Int,
    val peakHeight: Double
) : Serializable

data class WavelengthCalibration(
    val slopePixelsPerNm: Double,
    val interceptPixels: Double,
    val validationErrorNm: Double,
    val peaks: List<SpectralPeak>,
    val xRoi: IntRange
) : Serializable {
    fun wavelengthToPixel(wavelengthNm: Double): Double =
        slopePixelsPerNm * wavelengthNm + interceptPixels

    fun pixelToWavelength(pixel: Double): Double =
        (pixel - interceptPixels) / slopePixelsPerNm

    val quality: AnalysisQuality get() = AnalysisQualityPolicy.wavelength(validationErrorNm)
    val qualityMessage: String get() = quality.name
}

data class SpectralResponse(
    /** Always strictly increasing. */
    val wavelengthsNm: DoubleArray,
    val red: DoubleArray,
    val green: DoubleArray,
    val blue: DoubleArray,
    val validRangeNm: ClosedFloatingPointRange<Double> = 420.0..680.0
) : Serializable

data class Fluorophore(
    val displayName: String,
    val peakWavelengthNm: Double,
    val channel: SpectralChannel,
    val integrationWidthNm: Double
) : Serializable {
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
) : Serializable {
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

enum class AnalysisCapturePurpose(val displayName: String) {
    POSITIONING("positioning"),
    RESPONSE("SPD source"),
    SAMPLE_BLANK("sample blank"),
    SAMPLE("sample"),
    STANDARD_BLANK("standard blank"),
    STANDARD_SAMPLE("standard sample")
}

data class SampleAnalysis(
    /** Always strictly increasing and safe for interpolation/integration. */
    val wavelengthsNm: DoubleArray,
    val correctedIntensity: DoubleArray,
    val area: Double,
    val peak: Double,
    val fluorophore: Fluorophore,
    /** Range where response correction is calibrated and chart values are meaningful. */
    val validRangeNm: ClosedFloatingPointRange<Double> = 420.0..680.0,
    /** Independently integrated sample captures. */
    val replicateAreas: DoubleArray = doubleArrayOf(area)
) : Serializable {
    val sampleStandardDeviation: Double
        get() = replicateAreas.sampleStandardDeviation()
    val standardDeviation: Double get() = sampleStandardDeviation
    val areaStandardDeviation: Double get() = sampleStandardDeviation

    /** Short alias used by result tables. */
    val sd: Double get() = sampleStandardDeviation
}

data class StandardMeasurement(
    val concentration: Double,
    val replicateAreas: DoubleArray
) : Serializable {
    constructor(concentration: Double, area: Double) : this(concentration, doubleArrayOf(area))

    init {
        require(concentration.isFinite() && concentration >= 0.0) {
            "Standard concentration must be finite and non-negative."
        }
        require(replicateAreas.isNotEmpty()) { "A standard requires at least one replicate area." }
        require(replicateAreas.all { it.isFinite() }) { "Standard replicate areas must be finite." }
    }

    val area: Double get() = replicateAreas.average()
    val sampleStandardDeviation: Double get() = replicateAreas.sampleStandardDeviation()
    val standardDeviation: Double get() = sampleStandardDeviation
    val areaStandardDeviation: Double get() = sampleStandardDeviation
    val sd: Double get() = sampleStandardDeviation
}

internal fun DoubleArray.sampleStandardDeviation(): Double {
    if (size < 2) return 0.0
    val mean = average()
    return kotlin.math.sqrt(sumOf { (it - mean) * (it - mean) } / (size - 1))
}

data class ConcentrationResult(
    val slope: Double,
    val intercept: Double,
    val rSquared: Double,
    val sampleConcentration: Double,
    val outsideCalibrationRange: Boolean
) : Serializable

data class FssaUiState(
    val visible: Boolean = false,
    val step: AnalysisStep = AnalysisStep.PROJECT,
    val status: String = "Enter a project name to begin.",
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
    /** X ROI selected by positioning and reused by every later capture. */
    val lockedXRoi: IntRange? = null,
    val positioningProfiles: List<SpectralProfile> = emptyList(),
    val positioningAverageProfile: SpectralProfile? = null,
    val responseProfiles: List<SpectralProfile> = emptyList(),
    val sampleBlankProfiles: List<SpectralProfile> = emptyList(),
    val sampleProfiles: List<SpectralProfile> = emptyList(),
    /** Blank/sample batches for the standard currently being drafted. */
    val standardBlankProfiles: List<SpectralProfile> = emptyList(),
    val standardSampleProfiles: List<SpectralProfile> = emptyList(),
    val lastProfile: SpectralProfile? = null,
    val spdData: SpectralAlgorithms.SpdData? = null,
    val spdSource: String = SPD_NOT_LOADED_SOURCE,
    val standardConcentrationInput: String = "",
    /** Concentration frozen when the first RAW enters the current standard draft. */
    val standardDraftConcentration: Double? = null,
    val selectedCapturePurpose: AnalysisCapturePurpose = AnalysisCapturePurpose.POSITIONING,
    val pendingCapture: AnalysisCapturePurpose? = null,
    val logs: List<String> = listOf("FSSA algorithm v1.3.4 ready. All analysis is performed offline.")
) : Serializable {
    init {
        val batches = listOf(
            positioningProfiles,
            responseProfiles,
            sampleBlankProfiles,
            sampleProfiles,
            standardBlankProfiles,
            standardSampleProfiles
        )
        require(batches.all { it.size <= MAX_BATCH_PROFILES }) { "Each capture batch is limited to 5 profiles." }
    }

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
        const val MAX_BATCH_PROFILES = 5
        const val MAX_STANDARDS = 10
        const val BUILT_IN_SPD_SOURCE = "Built-in true_spd.csv"
        const val SPD_NOT_LOADED_SOURCE = "No SPD loaded"
    }
}
