package org.fungalsentinel.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max

@Composable
fun FssaPanel(
    state: FssaUiState,
    captureReady: Boolean,
    onClose: () -> Unit,
    onStepChanged: (AnalysisStep) -> Unit,
    onCapture: (AnalysisCapturePurpose) -> Unit,
    onImportSpd: () -> Unit,
    onFluorophoreChanged: (Fluorophore) -> Unit,
    onStandardConcentrationChanged: (String) -> Unit,
    onCalculateConcentration: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxHeight().fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("FSSA on-device analysis", style = MaterialTheme.typography.titleLarge)
                    Text("v1.1 · Offline RAW workflow", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnalysisStep.entries.forEach { step ->
                    val selected = state.step == step
                    Button(
                        onClick = { onStepChanged(step) },
                        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
                    ) { Text("${step.number}. ${step.title}") }
                }
            }
            HorizontalDivider()
            Text(state.status, color = if (state.busy) Color(0xffff9800) else MaterialTheme.colorScheme.onSurface)
            when (state.step) {
                AnalysisStep.POSITIONING -> {
                    Text("Capture the combined R/G/B positioning source. B and R fit the wavelength mapping; G validates it.")
                    ActionButton("Capture positioning RAW", captureReady && !state.busy) { onCapture(AnalysisCapturePurpose.POSITIONING) }
                    state.wavelengthCalibration?.let {
                        Metric("Mapping", "p = ${f(it.slopePixelsPerNm)}λ + ${f(it.interceptPixels)}")
                        Metric("G validation error", "${f(it.validationErrorNm)} nm")
                        ProfileChart(state.lastProfile)
                    }
                }
                AnalysisStep.RESPONSE -> {
                    Text("Optionally import a true-SPD CSV, then capture the standard light source. Without a CSV, FSSA uses its simulated default SPD.")
                    OutlinedButton(onClick = onImportSpd, enabled = !state.busy) {
                        Text(state.spdFileName ?: "Import true SPD CSV (optional)")
                    }
                    ActionButton(
                        "Capture SPD calibration RAW",
                        captureReady && !state.busy && state.wavelengthCalibration != null
                    ) { onCapture(AnalysisCapturePurpose.RESPONSE) }
                    state.spectralResponse?.let { ResponseChart(it) }
                }
                AnalysisStep.SAMPLE -> {
                    Text("Select the fluorophore, then capture the unknown sample using the locked settings.")
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Fluorophore.supported.forEach { fluor ->
                            OutlinedButton(onClick = { onFluorophoreChanged(fluor) }) {
                                Text(if (fluor == state.selectedFluorophore) "✓ ${fluor.displayName}" else fluor.displayName)
                            }
                        }
                    }
                    Text("Target ${state.selectedFluorophore.peakWavelengthNm.toInt()} nm · ${state.selectedFluorophore.channel}")
                    ActionButton(
                        "Capture and analyze sample",
                        captureReady && !state.busy && state.spectralResponse != null
                    ) { onCapture(AnalysisCapturePurpose.SAMPLE) }
                    state.sampleAnalysis?.let {
                        Metric("Integrated area", f(it.area))
                        Metric("Peak intensity", f(it.peak))
                        SpectrumChart(it)
                    }
                }
                AnalysisStep.CONCENTRATION -> {
                    Text("Capture 2–5 standards. Three or more are recommended for a meaningful R².")
                    OutlinedTextField(
                        value = state.standardConcentrationInput,
                        onValueChange = onStandardConcentrationChanged,
                        label = { Text("Standard concentration") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ActionButton(
                        "Capture standard",
                        captureReady && !state.busy && state.spectralResponse != null &&
                            state.standardConcentrationInput.toDoubleOrNull() != null && state.standards.size < 5
                    ) { onCapture(AnalysisCapturePurpose.STANDARD) }
                    state.standards.forEachIndexed { index, standard ->
                        Text("Standard ${index + 1}: C=${f(standard.concentration)}, area=${f(standard.area)}")
                    }
                    ActionButton(
                        "Build curve and calculate sample",
                        !state.busy && state.standards.size >= 2 && state.sampleAnalysis != null
                    ) { onCalculateConcentration() }
                    state.concentrationResult?.let {
                        Metric("Curve", "I = ${f(it.slope)}C + ${f(it.intercept)}")
                        Metric("R²", f(it.rSquared))
                        Metric("Predicted concentration", f(it.sampleConcentration))
                        if (it.outsideCalibrationRange) Text("Warning: result is outside the calibrated range.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            HorizontalDivider()
            Text("Analysis results & logs", style = MaterialTheme.typography.titleMedium)
            Text(state.logs.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProfileChart(profile: SpectralProfile?) {
    profile ?: return
    LineChart(listOf(profile.red to Color.Red, profile.green to Color.Green, profile.blue to Color.Blue))
}

@Composable
private fun ResponseChart(response: SpectralResponse) {
    LineChart(listOf(response.red to Color.Red, response.green to Color.Green, response.blue to Color.Blue))
}

@Composable
private fun SpectrumChart(analysis: SampleAnalysis) {
    LineChart(listOf(analysis.correctedIntensity to Color(0xff6a1b9a)))
}

@Composable
private fun LineChart(series: List<Pair<DoubleArray, Color>>) {
    val globalMax = max(1e-12, series.maxOfOrNull { it.first.maxOrNull() ?: 0.0 } ?: 1.0)
    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp).background(Color(0xfff5f5f5))) {
        drawLine(Color.Gray, Offset(30f, 8f), Offset(30f, size.height - 22f))
        drawLine(Color.Gray, Offset(30f, size.height - 22f), Offset(size.width - 8f, size.height - 22f))
        series.forEach { (values, color) ->
            if (values.size < 2) return@forEach
            val path = Path()
            values.indices.forEach { index ->
                val x = 30f + index.toFloat() / (values.size - 1) * (size.width - 38f)
                val y = (size.height - 22f) - (values[index] / globalMax).toFloat() * (size.height - 32f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color)
        }
    }
}

private fun f(value: Double): String = String.format(Locale.US, "%.4g", value)
