package org.fungalsentinel.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CameraSettingsPanel(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    support: CameraControlSupport,
    exposureStatus: ExposureStatus,
    onSettingsChanged: (CameraControlSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = Color.White,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Exposure", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        exposureStatus.displayName,
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (support.canUseManualControls) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Auto", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = !settings.manualControlsEnabled,
                            onCheckedChange = { autoEnabled ->
                                onSettingsChanged(settings.copy(manualControlsEnabled = !autoEnabled))
                            },
                            colors = cameraSwitchColors()
                        )
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))

            if (support.autoExposureLock) {
                SettingSwitch(
                    label = "Meter & lock",
                    checked = settings.meterThenLockEnabled,
                    enabled = !settings.manualControlsEnabled,
                    onCheckedChange = { onSettingsChanged(settings.copy(meterThenLockEnabled = it)) }
                )
            }

            val manualExposureEnabled = settings.manualControlsEnabled && support.canUseManualControls

            if (support.canUseManualControls) {
                ExposureSlider(
                    settings = settings,
                    ranges = ranges,
                    enabled = manualExposureEnabled,
                    onSettingsChanged = onSettingsChanged
                )
                IsoSlider(
                    settings = settings,
                    ranges = ranges,
                    enabled = manualExposureEnabled,
                    onSettingsChanged = onSettingsChanged
                )
            }
            if (support.manualFocus) {
                FocusSlider(
                    settings = settings,
                    ranges = ranges,
                    enabled = true,
                    onSettingsChanged = onSettingsChanged
                )
            }
            SettingSwitch(
                label = "Auto WB",
                checked = settings.autoWhiteBalanceEnabled,
                enabled = true,
                onCheckedChange = { onSettingsChanged(settings.copy(autoWhiteBalanceEnabled = it)) }
            )
            if (support.noiseReduction) {
                SettingSwitch(
                    label = "Denoise",
                    checked = settings.noiseReductionEnabled,
                    enabled = true,
                    onCheckedChange = { onSettingsChanged(settings.copy(noiseReductionEnabled = it)) }
                )
            }
            if (support.edgeEnhancement) {
                SettingSwitch(
                    label = "Sharpen",
                    checked = settings.edgeEnhancementEnabled,
                    enabled = true,
                    onCheckedChange = { onSettingsChanged(settings.copy(edgeEnhancementEnabled = it)) }
                )
            }
            if (support.hotPixelCorrection) {
                SettingSwitch(
                    label = "Hot pixels",
                    checked = settings.hotPixelCorrectionEnabled,
                    enabled = true,
                    onCheckedChange = { onSettingsChanged(settings.copy(hotPixelCorrectionEnabled = it)) }
                )
            }
        }
    }
}

@Composable
private fun ExposureSlider(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    enabled: Boolean,
    onSettingsChanged: (CameraControlSettings) -> Unit
) {
    val effectiveExposure = settings.exposureTimeNs.coerceIn(ranges.exposureTimeNs)

    LabeledSlider(
        label = "Exposure time",
        valueText = formatExposureTime(effectiveExposure),
        value = ExposureSliderScale.positionFor(effectiveExposure, ranges.exposureTimeNs),
        valueRange = 0f..1f,
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(
                settings.copy(exposureTimeNs = ExposureSliderScale.exposureTimeAt(it, ranges.exposureTimeNs))
            )
        }
    )
    val presetEffective = CameraControlSettings.DEFAULT_EXPOSURE_TIME_NS.coerceIn(ranges.exposureTimeNs)
    TextButton(
        onClick = {
            onSettingsChanged(settings.copy(exposureTimeNs = presetEffective))
        },
        enabled = enabled
    ) {
        Text(
            if (presetEffective == CameraControlSettings.DEFAULT_EXPOSURE_TIME_NS) "Set 400 ms"
            else "Set ${formatExposureTime(presetEffective)} (device max)"
        )
    }
}

@Composable
private fun IsoSlider(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    enabled: Boolean,
    onSettingsChanged: (CameraControlSettings) -> Unit
) {
    LabeledSlider(
        label = "ISO",
        valueText = settings.iso.toString(),
        value = settings.iso.toFloat(),
        valueRange = ranges.iso.first.toFloat()..ranges.iso.last.toFloat(),
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(settings.copy(iso = it.roundToInt()))
        }
    )
}

@Composable
private fun FocusSlider(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    enabled: Boolean,
    onSettingsChanged: (CameraControlSettings) -> Unit
) {
    LabeledSlider(
        label = "Focus",
        valueText = formatFocusDistance(settings.focusDistanceDiopters),
        value = settings.focusDistanceDiopters,
        valueRange = ranges.focusDistanceDiopters.start..ranges.focusDistanceDiopters.endInclusive,
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(settings.copy(focusDistanceDiopters = it))
        }
    )
    TextButton(
        onClick = { onSettingsChanged(settings.copy(focusDistanceDiopters = 0f)) },
        enabled = enabled
    ) {
        Text("Set ∞")
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f))
            Text(valueText, color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f))
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = cameraSliderColors()
        )
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            maxLines = 2
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = cameraSwitchColors()
        )
    }
}

@Composable
private fun cameraSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White.copy(alpha = 0.82f),
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
    disabledThumbColor = Color.White.copy(alpha = 0.38f),
    disabledActiveTrackColor = Color.White.copy(alpha = 0.28f),
    disabledInactiveTrackColor = Color.White.copy(alpha = 0.12f)
)

@Composable
private fun cameraSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.Black,
    checkedTrackColor = Color.White,
    uncheckedThumbColor = Color.White.copy(alpha = 0.72f),
    uncheckedTrackColor = Color.White.copy(alpha = 0.18f),
    disabledCheckedThumbColor = Color.White.copy(alpha = 0.45f),
    disabledCheckedTrackColor = Color.White.copy(alpha = 0.18f),
    disabledUncheckedThumbColor = Color.White.copy(alpha = 0.35f),
    disabledUncheckedTrackColor = Color.White.copy(alpha = 0.10f)
)

private fun formatExposureTime(exposureTimeNs: Long): String {
    val milliseconds = exposureTimeNs / 1_000_000.0
    return when {
        milliseconds >= 100.0 -> String.format(Locale.US, "%.0f ms", milliseconds)
        milliseconds >= 1.0 -> String.format(Locale.US, "%.2f ms", milliseconds)
        else -> String.format(Locale.US, "%.0f µs", exposureTimeNs / 1_000.0)
    }
}
