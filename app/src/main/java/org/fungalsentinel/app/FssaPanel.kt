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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
    onImportSpd: () -> Unit,
    onFluorophoreChanged: (Fluorophore) -> Unit,
    onStandardConcentrationChanged: (String) -> Unit,
    onCalculateConcentration: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxHeight().fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text("${state.step.number}. ${state.step.title}", style = MaterialTheme.typography.titleLarge)
                Text("FSSA on-device analysis · Offline RAW workflow", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
            Text(state.status, color = if (state.busy) Color(0xffffb74d) else Color.White)
            when (state.step) {
                AnalysisStep.POSITIONING -> {
                    Text("Capture the combined R/G/B positioning source. B and R fit the wavelength mapping; G validates it.")
                    state.wavelengthCalibration?.let {
                        Metric("Mapping", "p = ${f(it.slopePixelsPerNm)}λ + ${f(it.interceptPixels)}")
                        Metric("G validation error", "${f(it.validationErrorNm)} nm")
                        ProfileChart(state.lastProfile)
                    }
                }
                AnalysisStep.RESPONSE -> {
                    Text("Optionally import a true-SPD CSV, then capture the standard light source. Without a CSV, FSSA uses its simulated default SPD.")
                    OutlinedButton(
                        onClick = onImportSpd,
                        enabled = !state.busy,
                        colors = analysisOutlinedButtonColors()
                    ) {
                        Text(state.spdFileName ?: "Import true SPD CSV (optional)")
                    }
                    state.spectralResponse?.let { ResponseChart(it) }
                }
                AnalysisStep.SAMPLE -> {
                    Text("Select the fluorophore, then capture the unknown sample using the locked settings.")
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Fluorophore.supported.forEach { fluor ->
                            OutlinedButton(
                                onClick = { onFluorophoreChanged(fluor) },
                                enabled = !state.busy,
                                colors = analysisOutlinedButtonColors()
                            ) {
                                Text(if (fluor == state.selectedFluorophore) "✓ ${fluor.displayName}" else fluor.displayName)
                            }
                        }
                    }
                    Text("Target ${state.selectedFluorophore.peakWavelengthNm.toInt()} nm · ${state.selectedFluorophore.channel}")
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
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.45f),
                            focusedLabelColor = Color.White,
                            unfocusedLabelColor = Color.White.copy(alpha = 0.70f)
                        )
                    )
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
                        if (it.outsideCalibrationRange) Text("Warning: result is outside the calibrated range.", color = Color(0xffff8a80))
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
            Text("Analysis results & logs", style = MaterialTheme.typography.titleMedium)
            Text(state.logs.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, action: () -> Unit) {
    Button(
        onClick = action,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.20f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.08f),
            disabledContentColor = Color.White.copy(alpha = 0.35f)
        )
    ) { Text(text) }
}

@Composable
private fun analysisOutlinedButtonColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = Color.White,
    disabledContentColor = Color.White.copy(alpha = 0.35f)
)

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
    LineChart(listOf(analysis.correctedIntensity to Color(0xff66d9ef)))
}

@Composable
private fun LineChart(series: List<Pair<DoubleArray, Color>>) {
    val globalMax = max(1e-12, series.maxOfOrNull { it.first.maxOrNull() ?: 0.0 } ?: 1.0)
    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp).background(Color.White.copy(alpha = 0.08f))) {
        val axisColor = Color.White.copy(alpha = 0.60f)
        drawLine(axisColor, Offset(30f, 8f), Offset(30f, size.height - 22f))
        drawLine(axisColor, Offset(30f, size.height - 22f), Offset(size.width - 8f, size.height - 22f))
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
