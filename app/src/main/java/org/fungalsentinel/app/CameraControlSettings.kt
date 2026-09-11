package org.fungalsentinel.app

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

data class CameraControlSettings(
    val manualControlsEnabled: Boolean,
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistanceDiopters: Float,
    val autoWhiteBalanceEnabled: Boolean,
    val noiseReductionEnabled: Boolean,
    val edgeEnhancementEnabled: Boolean,
    val hotPixelCorrectionEnabled: Boolean,
    val meterThenLockEnabled: Boolean = false
) {
    fun clampedTo(ranges: CameraControlRanges): CameraControlSettings {
        return copy(
            exposureTimeNs = exposureTimeNs.coerceIn(ranges.exposureTimeNs),
            iso = iso.coerceIn(ranges.iso),
            focusDistanceDiopters = focusDistanceDiopters.coerceIn(ranges.focusDistanceDiopters)
        )
    }

    companion object {
        const val DEFAULT_EXPOSURE_TIME_NS = 400_000_000L

        fun manualDefaults(): CameraControlSettings = CameraControlSettings(
            manualControlsEnabled = true,
            exposureTimeNs = DEFAULT_EXPOSURE_TIME_NS,
            iso = 400,
            focusDistanceDiopters = 0.0f,
            autoWhiteBalanceEnabled = false,
            noiseReductionEnabled = false,
            edgeEnhancementEnabled = false,
            hotPixelCorrectionEnabled = false
        )

        fun automatic(): CameraControlSettings = CameraControlSettings(
            manualControlsEnabled = false,
            exposureTimeNs = DEFAULT_EXPOSURE_TIME_NS,
            iso = 400,
            focusDistanceDiopters = 0.0f,
            autoWhiteBalanceEnabled = true,
            noiseReductionEnabled = true,
            edgeEnhancementEnabled = true,
            hotPixelCorrectionEnabled = true
        )
    }
}

data class CameraControlRanges(
    val exposureTimeNs: LongRange,
    val iso: IntRange,
    val focusDistanceDiopters: ClosedFloatingPointRange<Float>
) {
    companion object {
        val fallback = CameraControlRanges(
            exposureTimeNs = 1_000_000L..100_000_000L,
            iso = 50..3_200,
            focusDistanceDiopters = 0.0f..10.0f
        )
    }
}

data class CameraControlSupport(
    val manualSensor: Boolean,
    val raw: Boolean,
    val manualFocus: Boolean,
    val noiseReduction: Boolean,
    val edgeEnhancement: Boolean,
    val hotPixelCorrection: Boolean,
    val autoExposureLock: Boolean = false
) {
    val canUseManualControls: Boolean
        get() = manualSensor
}

enum class ExposureStatus(val displayName: String) {
    MANUAL("Manual"),
    AUTO("Auto"),
    AUTO_METERING("Auto — metering for lock"),
    AUTO_LOCKED("Auto — locked")
}

/** Runtime AE-lock state kept separate from the user's persistent camera settings. */
data class AutoExposureState(
    val lockRequested: Boolean = false,
    val locked: Boolean = false
) {
    fun reset(): AutoExposureState = if (lockRequested || locked) AutoExposureState() else this

    fun onAeStable(settings: CameraControlSettings, support: CameraControlSupport): AutoExposureState {
        return if (!settings.manualControlsEnabled && settings.meterThenLockEnabled &&
            support.autoExposureLock && !lockRequested
        ) copy(lockRequested = true) else this
    }

    fun onLockConfirmed(settings: CameraControlSettings, support: CameraControlSupport): AutoExposureState {
        return if (!settings.manualControlsEnabled && settings.meterThenLockEnabled &&
            support.autoExposureLock && lockRequested
        ) copy(locked = true) else this
    }

    fun status(settings: CameraControlSettings, support: CameraControlSupport): ExposureStatus = when {
        settings.manualControlsEnabled && support.manualSensor -> ExposureStatus.MANUAL
        !settings.meterThenLockEnabled || !support.autoExposureLock -> ExposureStatus.AUTO
        locked -> ExposureStatus.AUTO_LOCKED
        else -> ExposureStatus.AUTO_METERING
    }
}

/** Selects a stable preview range near 30 fps so auto exposure cannot make preview motion choppy. */
object PreviewFrameRateSelector {
    fun choose(ranges: List<IntRange>, targetFps: Int = 30): IntRange? {
        val valid = ranges.filter { it.first > 0 && it.last >= it.first }
        if (valid.isEmpty()) return null
        val atOrBelowTarget = valid.filter { it.last <= targetFps }
        if (atOrBelowTarget.isNotEmpty()) {
            return atOrBelowTarget.maxWithOrNull(compareBy<IntRange> { it.first }.thenBy { it.last })
        }
        return valid.minByOrNull { it.last }
    }
}

/** Logarithmic mapping gives short and long shutter times useful portions of the slider. */
object ExposureSliderScale {
    fun positionFor(exposureTimeNs: Long, range: LongRange): Float {
        val low = range.first.coerceAtLeast(1L).toDouble()
        val high = range.last.coerceAtLeast(range.first.coerceAtLeast(1L)).toDouble()
        if (low == high) return 0f
        val value = exposureTimeNs.coerceIn(range).toDouble()
        return ((ln(value) - ln(low)) / (ln(high) - ln(low))).toFloat().coerceIn(0f, 1f)
    }

    fun exposureTimeAt(position: Float, range: LongRange): Long {
        val low = range.first.coerceAtLeast(1L).toDouble()
        val high = range.last.coerceAtLeast(range.first.coerceAtLeast(1L)).toDouble()
        if (low == high) return low.roundToLong()
        val value = exp(ln(low) + position.coerceIn(0f, 1f) * (ln(high) - ln(low)))
        return value.roundToLong().coerceIn(range)
    }
}

fun formatFocusDistance(diopters: Float): String =
    if (diopters <= 0.0001f) "∞ (0 D)" else "${formatCameraValue(diopters)} D"

internal fun formatCameraValue(value: Float): String = when {
    value >= 100f -> value.roundToLong().toString()
    value >= 10f -> String.format(java.util.Locale.US, "%.1f", value)
    else -> String.format(java.util.Locale.US, "%.2f", value)
}
