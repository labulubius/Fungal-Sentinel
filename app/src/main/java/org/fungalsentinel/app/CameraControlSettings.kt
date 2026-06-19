package org.fungalsentinel.app

data class CameraControlSettings(
    val manualControlsEnabled: Boolean,
    val exposureTimeNs: Long,
    val iso: Int,
    val focusDistanceDiopters: Float,
    val autoWhiteBalanceEnabled: Boolean,
    val noiseReductionEnabled: Boolean,
    val edgeEnhancementEnabled: Boolean,
    val hotPixelCorrectionEnabled: Boolean
) {
    fun clampedTo(ranges: CameraControlRanges): CameraControlSettings {
        return copy(
            exposureTimeNs = exposureTimeNs.coerceIn(ranges.exposureTimeNs),
            iso = iso.coerceIn(ranges.iso),
            focusDistanceDiopters = focusDistanceDiopters.coerceIn(ranges.focusDistanceDiopters)
        )
    }

    companion object {
        fun manualDefaults(): CameraControlSettings = CameraControlSettings(
            manualControlsEnabled = true,
            exposureTimeNs = 10_000_000L,
            iso = 400,
            focusDistanceDiopters = 2.0f,
            autoWhiteBalanceEnabled = false,
            noiseReductionEnabled = false,
            edgeEnhancementEnabled = false,
            hotPixelCorrectionEnabled = false
        )

        fun automatic(): CameraControlSettings = CameraControlSettings(
            manualControlsEnabled = false,
            exposureTimeNs = 10_000_000L,
            iso = 400,
            focusDistanceDiopters = 2.0f,
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
    val hotPixelCorrection: Boolean
) {
    val canUseManualControls: Boolean
        get() = manualSensor
}
