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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun CameraSettingsPanel(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    support: CameraControlSupport,
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
                Column {
                    Text("Manual controls", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        support.summaryText(),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = settings.manualControlsEnabled && support.canUseManualControls,
                    enabled = support.canUseManualControls,
                    onCheckedChange = { checked ->
                        onSettingsChanged(settings.copy(manualControlsEnabled = checked))
                    },
                    colors = cameraSwitchColors()
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))

            val controlsEnabled = settings.manualControlsEnabled && support.canUseManualControls

            ExposureSlider(
                settings = settings,
                ranges = ranges,
                enabled = controlsEnabled,
                onSettingsChanged = onSettingsChanged
            )
            IsoSlider(
                settings = settings,
                ranges = ranges,
                enabled = controlsEnabled,
                onSettingsChanged = onSettingsChanged
            )
            FocusSlider(
                settings = settings,
                ranges = ranges,
                enabled = controlsEnabled && support.manualFocus,
                onSettingsChanged = onSettingsChanged
            )
            SettingSwitch(
                label = "Auto white balance",
                checked = settings.autoWhiteBalanceEnabled,
                enabled = controlsEnabled,
                onCheckedChange = { onSettingsChanged(settings.copy(autoWhiteBalanceEnabled = it)) }
            )
            SettingSwitch(
                label = "Noise reduction",
                checked = settings.noiseReductionEnabled,
                enabled = controlsEnabled && support.noiseReduction,
                onCheckedChange = { onSettingsChanged(settings.copy(noiseReductionEnabled = it)) }
            )
            SettingSwitch(
                label = "Edge enhancement",
                checked = settings.edgeEnhancementEnabled,
                enabled = controlsEnabled && support.edgeEnhancement,
                onCheckedChange = { onSettingsChanged(settings.copy(edgeEnhancementEnabled = it)) }
            )
            SettingSwitch(
                label = "Hot pixel correction",
                checked = settings.hotPixelCorrectionEnabled,
                enabled = controlsEnabled && support.hotPixelCorrection,
                onCheckedChange = { onSettingsChanged(settings.copy(hotPixelCorrectionEnabled = it)) }
            )
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
    val minMs = max(0.1f, ranges.exposureTimeNs.first / 1_000_000f)
    val maxMs = max(minMs, min(30_000f, ranges.exposureTimeNs.last / 1_000_000f))
    val valueMs = (settings.exposureTimeNs / 1_000_000f).coerceIn(minMs, maxMs)

    LabeledSlider(
        label = "Exposure",
        valueText = "${formatFloat(valueMs)} ms",
        value = valueMs,
        valueRange = minMs..maxMs,
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(settings.copy(exposureTimeNs = (it * 1_000_000L).roundToLong()))
        }
    )
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
        valueText = "${formatFloat(settings.focusDistanceDiopters)} D",
        value = settings.focusDistanceDiopters,
        valueRange = ranges.focusDistanceDiopters.start..ranges.focusDistanceDiopters.endInclusive,
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(settings.copy(focusDistanceDiopters = it))
        }
    )
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
        Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f))
        Spacer(modifier = Modifier.width(16.dp))
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

private fun CameraControlSupport.summaryText(): String {
    return "Manual: ${manualSensor.status()}  RAW: ${raw.status()}  Focus: ${manualFocus.status()}"
}

private fun Boolean.status(): String = if (this) "supported" else "unsupported"

private fun formatFloat(value: Float): String {
    return if (value >= 10f) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
