package org.fungalsentinel.app

import kotlin.math.abs

enum class AnalysisQuality { PASS, WARNING, FAILED }

/** Quality gates wrap the v1.3.4 math without changing its calibration or regression formulas. */
object AnalysisQualityPolicy {
    const val SATURATION_WARNING_FRACTION = 0.001
    const val SATURATION_REJECT_FRACTION = 0.01
    const val REGRESSION_WARNING_R_SQUARED = 0.95
    const val REGRESSION_GOOD_R_SQUARED = 0.99

    fun wavelength(errorNm: Double): AnalysisQuality = when {
        !errorNm.isFinite() || abs(errorNm) > 10.0 -> AnalysisQuality.FAILED
        abs(errorNm) > 2.5 -> AnalysisQuality.WARNING
        else -> AnalysisQuality.PASS
    }

    fun allowsResponseCapture(calibration: WavelengthCalibration?, protectionEnabled: Boolean): Boolean =
        calibration != null && (!protectionEnabled || calibration.quality != AnalysisQuality.FAILED)

    fun saturation(fraction: Double): AnalysisQuality = when {
        !fraction.isFinite() || fraction >= SATURATION_REJECT_FRACTION -> AnalysisQuality.FAILED
        fraction >= SATURATION_WARNING_FRACTION -> AnalysisQuality.WARNING
        else -> AnalysisQuality.PASS
    }

    fun regressionWarnings(
        standards: List<StandardMeasurement>,
        result: ConcentrationResult
    ): List<String> = buildList {
        if (standards.map { it.concentration }.distinct().size < 3) {
            add("Only two concentration levels were used; R² is not a meaningful linearity check.")
        }
        if (result.slope <= 0.0) add("The fitted slope is not positive.")
        if (result.rSquared < REGRESSION_WARNING_R_SQUARED) {
            add("R² is below ${REGRESSION_WARNING_R_SQUARED}.")
        } else if (result.rSquared < REGRESSION_GOOD_R_SQUARED) {
            add("R² is below the recommended ${REGRESSION_GOOD_R_SQUARED}.")
        }
        if (result.sampleConcentration < 0.0) add("The predicted concentration is negative.")
        if (result.outsideCalibrationRange) add("The prediction is outside the calibrated range.")
    }
}
