package org.fungalsentinel.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun FssaPanel(
    state: FssaUiState,
    onWavelengthChanged: (SpectralChannel, String) -> Unit,
    onRestoreDefaultWavelengths: () -> Unit,
    onImportSpd: () -> Unit,
    onRestoreBuiltInSpd: () -> Unit,
    onFluorophoreChanged: (Fluorophore) -> Unit,
    onStandardConcentrationChanged: (String) -> Unit,
    onCapturePurposeChanged: (AnalysisCapturePurpose) -> Unit,
    onClearCaptureBatch: (AnalysisCapturePurpose) -> Unit,
    onCommitStandard: () -> Unit,
    onRemoveStandard: (Int) -> Unit,
    onCalculateConcentration: () -> Unit,
    historyName: String,
    projectNameInput: String,
    projectActive: Boolean,
    dngSaveMode: DngSaveMode,
    onProjectNameChanged: (String) -> Unit,
    onCreateProject: () -> Unit,
    historySaving: Boolean,
    historyDirty: Boolean,
    enforceCalibrationGate: Boolean,
    onSaveHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(CompactLayout.panelPadding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CompactLayout.sectionSpacing)
        ) {
            Text("${state.step.number}. ${state.step.title}", style = MaterialTheme.typography.titleLarge)
            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
            Text(state.status, color = if (state.busy) Color(0xffffb74d) else Color.White)
            if (state.concentrationResult != null) {
                Button(
                    onClick = onSaveHistory,
                    enabled = !state.busy && !historySaving && historyDirty
                ) {
                    Text(if (historySaving) "Saving ZIP…" else "Finish project & save to History")
                }
            }
            state.lastProfile?.let {
                val quality = AnalysisQualityPolicy.saturation(it.saturatedFraction)
                Text(
                    "Saturation ${percent(it.saturatedFraction)} · ${quality.name}",
                    color = when (quality) {
                        AnalysisQuality.PASS -> Color(0xffa5d6a7)
                        AnalysisQuality.WARNING -> Color(0xffffd54f)
                        AnalysisQuality.FAILED -> Color(0xffff8a80)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            when (state.step) {
                AnalysisStep.PROJECT -> {
                    if (projectActive) {
                        Metric("Project name", historyName)
                        Text(
                            "Locked · Reset to rename.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        ActionButton("Continue to Step 2", !state.busy) { onCreateProject() }
                    } else {
                        OutlinedTextField(
                            value = projectNameInput,
                            onValueChange = onProjectNameChanged,
                            label = { Text("Project name") },
                            supportingText = {
                                Text(
                                    if (projectNameInput.isNotEmpty() && !isValidProjectName(projectNameInput)) {
                                        "1–100 characters; avoid / \\ : * ? \" < > |"
                                    } else {
                                        "Used for DNG files and History."
                                    }
                                )
                            },
                            singleLine = true,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.45f),
                                focusedLabelColor = Color.White,
                                unfocusedLabelColor = Color.White.copy(alpha = 0.70f),
                                focusedSupportingTextColor = Color.LightGray,
                                unfocusedSupportingTextColor = Color.LightGray
                            )
                        )
                        Text("DNG mode: ${dngSaveMode.displayName}", style = MaterialTheme.typography.bodySmall)
                        ActionButton("Create & continue", !state.busy && isValidProjectName(projectNameInput)) {
                            onCreateProject()
                        }
                    }
                }
                AnalysisStep.POSITIONING -> {
                    WavelengthField("R wavelength (nm)", state.redWavelengthInput, state.busy) {
                        onWavelengthChanged(SpectralChannel.RED, it)
                    }
                    WavelengthField("G wavelength (nm)", state.greenWavelengthInput, state.busy) {
                        onWavelengthChanged(SpectralChannel.GREEN, it)
                    }
                    WavelengthField("B wavelength (nm)", state.blueWavelengthInput, state.busy) {
                        onWavelengthChanged(SpectralChannel.BLUE, it)
                    }
                    state.wavelengthValidationMessage?.let {
                        Text(it, color = Color(0xffff8a80), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = onRestoreDefaultWavelengths,
                        enabled = !state.busy && !state.usesDefaultPositioningWavelengths,
                        colors = analysisOutlinedButtonColors()
                    ) { Text("Restore default wavelengths") }
                    BatchSelector(
                        label = "Positioning captures",
                        purpose = AnalysisCapturePurpose.POSITIONING,
                        count = state.positioningProfiles.size,
                        selected = state.selectedCapturePurpose == AnalysisCapturePurpose.POSITIONING,
                        busy = state.busy,
                        onSelect = onCapturePurposeChanged,
                        onClear = onClearCaptureBatch
                    )
                    Text("First capture locks the ROI.", style = MaterialTheme.typography.bodySmall)
                    state.wavelengthCalibration?.let {
                        Metric("Mapping", "p = ${f(it.slopePixelsPerNm)}λ + ${f(it.interceptPixels)}")
                        Metric("G validation error", "${f(it.validationErrorNm)} nm")
                        Metric("Calibration quality", it.qualityMessage)
                        when (it.quality) {
                            AnalysisQuality.WARNING -> Text(
                                "Usable with caution; improve alignment.",
                                color = Color(0xffffd54f)
                            )
                            AnalysisQuality.FAILED -> Text(
                                if (enforceCalibrationGate) {
                                    "FAILED · Adjust alignment before Step 3."
                                } else {
                                    "FAILED · Step 3 override is active."
                                },
                                color = Color(0xffff8a80)
                            )
                            AnalysisQuality.PASS -> Unit
                        }
                        ProfileChart(state.positioningAverageProfile)
                    }
                }
                AnalysisStep.RESPONSE -> {
                    Text("SPD: ${state.spdSource}", style = MaterialTheme.typography.bodySmall)
                    Text("Use the light source matching this SPD.", color = Color(0xffffd54f), style = MaterialTheme.typography.bodySmall)
                    BatchSelector(
                        label = "SPD captures",
                        purpose = AnalysisCapturePurpose.RESPONSE,
                        count = state.responseProfiles.size,
                        selected = state.selectedCapturePurpose == AnalysisCapturePurpose.RESPONSE,
                        busy = state.busy,
                        onSelect = onCapturePurposeChanged,
                        onClear = onClearCaptureBatch
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(CompactLayout.actionSpacing)) {
                        OutlinedButton(
                            onClick = onImportSpd,
                            enabled = !state.busy,
                            colors = analysisOutlinedButtonColors()
                        ) { Text("Import custom CSV") }
                        OutlinedButton(
                            onClick = onRestoreBuiltInSpd,
                            enabled = !state.busy &&
                                (state.spdData == null || state.spdSource != FssaUiState.BUILT_IN_SPD_SOURCE),
                            colors = analysisOutlinedButtonColors()
                        ) { Text("Restore built-in") }
                    }
                    state.spectralResponse?.let { ResponseChart(it) }
                }
                AnalysisStep.SAMPLE -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(CompactLayout.actionSpacing)
                    ) {
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
                    BatchSelector(
                        label = "Blank captures",
                        purpose = AnalysisCapturePurpose.SAMPLE_BLANK,
                        count = state.sampleBlankProfiles.size,
                        selected = state.selectedCapturePurpose == AnalysisCapturePurpose.SAMPLE_BLANK,
                        busy = state.busy,
                        onSelect = onCapturePurposeChanged,
                        onClear = onClearCaptureBatch
                    )
                    BatchSelector(
                        label = "Sample captures",
                        purpose = AnalysisCapturePurpose.SAMPLE,
                        count = state.sampleProfiles.size,
                        selected = state.selectedCapturePurpose == AnalysisCapturePurpose.SAMPLE,
                        busy = state.busy,
                        onSelect = onCapturePurposeChanged,
                        onClear = onClearCaptureBatch
                    )
                    state.sampleAnalysis?.let {
                        Metric("Integrated area (mean)", f(it.area))
                        Metric("Sample SD", f(it.sd))
                        Metric("Replicates", it.replicateAreas.size.toString())
                        Metric("Peak intensity (last replicate)", f(it.peak))
                        SpectrumChart(it)
                        Text("Chart: last shot · Area/SD: all shots", style = MaterialTheme.typography.bodySmall)
                    }
                }
                AnalysisStep.CONCENTRATION -> {
                    Text("Standards: 2–10 groups · 3+ recommended")
                    OutlinedTextField(
                        value = state.standardConcentrationInput,
                        onValueChange = onStandardConcentrationChanged,
                        label = { Text("Standard concentration") },
                        singleLine = true,
                        enabled = !state.busy && state.standardDraftConcentration == null &&
                            state.standardBlankProfiles.isEmpty() && state.standardSampleProfiles.isEmpty(),
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
                    state.standardDraftConcentration?.let {
                        Text("Concentration locked: ${f(it)}", color = Color(0xffffd54f))
                    }
                    BatchSelector(
                        label = "Draft standard Blank",
                        purpose = AnalysisCapturePurpose.STANDARD_BLANK,
                        count = state.standardBlankProfiles.size,
                        selected = state.selectedCapturePurpose == AnalysisCapturePurpose.STANDARD_BLANK,
                        busy = state.busy,
                        onSelect = onCapturePurposeChanged,
                        onClear = onClearCaptureBatch
                    )
                    BatchSelector(
                        label = "Draft standard Sample",
                        purpose = AnalysisCapturePurpose.STANDARD_SAMPLE,
                        count = state.standardSampleProfiles.size,
                        selected = state.selectedCapturePurpose == AnalysisCapturePurpose.STANDARD_SAMPLE,
                        busy = state.busy,
                        onSelect = onCapturePurposeChanged,
                        onClear = onClearCaptureBatch
                    )
                    ActionButton(
                        "Add standard group",
                        !state.busy && state.standards.size < FssaUiState.MAX_STANDARDS &&
                            state.standardBlankProfiles.isNotEmpty() && state.standardSampleProfiles.isNotEmpty() &&
                            state.standardConcentrationInput.toDoubleOrNull()?.isFinite() == true
                    ) { onCommitStandard() }
                    state.standards.forEachIndexed { index, standard ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#${index + 1}  C=${f(standard.concentration)} · " +
                                    "Mean=${f(standard.area)} · SD=${f(standard.sd)} · n=${standard.replicateAreas.size}",
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { onRemoveStandard(index) },
                                enabled = !state.busy,
                                colors = analysisOutlinedButtonColors()
                            ) { Text("Remove") }
                        }
                    }
                    if (state.standards.isNotEmpty()) {
                        ConcentrationChart(state.standards, state.concentrationResult, state.sampleAnalysis?.area)
                    }
                    val curveReadinessError = when {
                        state.sampleAnalysis == null -> "Complete Step 4 Sample analysis first."
                        state.standards.size < 2 -> "Add at least two different standard groups."
                        else -> runCatching {
                            SpectralAlgorithms.calculateConcentration(state.standards, state.sampleAnalysis.area)
                        }.exceptionOrNull()?.message
                    }
                    ActionButton(
                        "Build curve",
                        !state.busy && curveReadinessError == null
                    ) { onCalculateConcentration() }
                    curveReadinessError?.let {
                        Text("Not ready: $it", color = Color(0xffff8a80), style = MaterialTheme.typography.bodySmall)
                    }
                    state.concentrationResult?.let {
                        Metric("Curve", "I = ${f(it.slope)}C + ${f(it.intercept)}")
                        Metric("R²", f(it.rSquared))
                        Metric("Predicted concentration", f(it.sampleConcentration))
                        val warnings = AnalysisQualityPolicy.regressionWarnings(state.standards, it)
                        warnings.forEach { warning ->
                            Text("⚠ $warning", color = Color(0xffffd54f), style = MaterialTheme.typography.bodySmall)
                        }
                        if (it.outsideCalibrationRange) {
                            Text(
                                "⚠ Predicted value is outside the standard range.",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xffb71c1c))
                                    .padding(CompactLayout.cardPadding)
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun WavelengthField(
    label: String,
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
}

@Composable
private fun BatchSelector(
    label: String,
    purpose: AnalysisCapturePurpose,
    count: Int,
    selected: Boolean,
    busy: Boolean,
    onSelect: (AnalysisCapturePurpose) -> Unit,
    onClear: (AnalysisCapturePurpose) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(CompactLayout.relatedSpacing)) {
        Text("$label: $count/5", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(CompactLayout.actionSpacing)) {
            OutlinedButton(
                onClick = { onSelect(purpose) },
                enabled = !busy && count < FssaUiState.MAX_BATCH_PROFILES,
                colors = analysisOutlinedButtonColors()
            ) { Text(if (selected) "✓ Selected" else "Select") }
            OutlinedButton(
                onClick = { onClear(purpose) },
                enabled = !busy && count > 0,
                colors = analysisOutlinedButtonColors()
            ) {
                Text(
                    when (purpose) {
                        AnalysisCapturePurpose.POSITIONING -> "Clear all"
                        AnalysisCapturePurpose.RESPONSE -> "Clear downstream"
                        else -> "Clear"
                    }
                )
            }
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

private data class ChartSeries(
    val points: List<ChartPoint>,
    val color: Color,
    val showPoints: Boolean = false,
    val dashed: Boolean = false,
    val connectLine: Boolean = true
)

@Composable
private fun ProfileChart(profile: SpectralProfile?) {
    profile ?: return
    val x = DoubleArray(profile.size) { it.toDouble() }
    val all = listOf(
        ChartGeometry.pairedFinitePoints(x, profile.red) to Color.Red,
        ChartGeometry.pairedFinitePoints(x, profile.green) to Color.Green,
        ChartGeometry.pairedFinitePoints(x, profile.blue) to Color.Blue
    )
    val points = all.flatMap { it.first }
    XyChart(
        series = all.map { ChartSeries(it.first, it.second) },
        xRange = ChartGeometry.paddedRange(points.map { it.x }),
        yRange = ChartGeometry.paddedRange(points.map { it.y } + 0.0),
        xLabel = "Sensor position (px)",
        yLabel = "Signal (a.u.)",
        emptyMessage = "No finite profile data"
    )
}

@Composable
private fun ResponseChart(response: SpectralResponse) {
    val channels = listOf(response.red to Color.Red, response.green to Color.Green, response.blue to Color.Blue)
    val chartSeries = channels.flatMap { (values, color) ->
        ChartGeometry.pairedFiniteSegments(response.wavelengthsNm, values, response.validRangeNm)
            .map { ChartSeries(it, color) }
    }
    val allPoints = chartSeries.flatMap { it.points }
    val xRange = validChartRange(response.validRangeNm)
    XyChart(
        series = chartSeries,
        xRange = xRange,
        yRange = ChartGeometry.paddedRange(allPoints.map { it.y } + 0.0),
        xLabel = "Wavelength (nm)",
        yLabel = "Relative response",
        emptyMessage = "No response data in valid wavelength range"
    )
}

@Composable
private fun SpectrumChart(analysis: SampleAnalysis) {
    val geometry = ChartGeometry.spectrum(
        analysis.wavelengthsNm,
        analysis.correctedIntensity,
        analysis.validRangeNm,
        analysis.fluorophore.peakWavelengthNm,
        analysis.fluorophore.integrationWidthNm
    )
    XyChart(
        series = geometry.segments.map { ChartSeries(it, Color(0xff66d9ef)) },
        xRange = geometry.xRange,
        yRange = geometry.yRange,
        xLabel = "Wavelength (nm)",
        yLabel = "Intensity (a.u.)",
        targetX = geometry.targetPeakNm,
        interval = geometry.integrationRangeNm,
        emptyMessage = "No finite sample data in valid wavelength range"
    )
    Text(
        "Band · target ${f(analysis.fluorophore.peakWavelengthNm)} nm",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ConcentrationChart(
    standards: List<StandardMeasurement>,
    result: ConcentrationResult?,
    sampleArea: Double?
) {
    val geometry = ChartGeometry.concentration(standards, result, sampleArea)
    val series = buildList {
        add(ChartSeries(geometry.standards, Color(0xff66d9ef), showPoints = true, connectLine = false))
        if (geometry.regressionLine.size == 2) add(ChartSeries(geometry.regressionLine, Color(0xffff8a80), dashed = true))
        geometry.unknown?.let { add(ChartSeries(listOf(it), Color(0xffffd54f), showPoints = true, connectLine = false)) }
    }
    XyChart(
        series = series,
        xRange = geometry.xRange,
        yRange = geometry.yRange,
        xLabel = "Concentration",
        yLabel = "Integrated area",
        emptyMessage = "Capture standards to build the concentration chart",
        errorBars = geometry.errorBars
    )
    result?.let {
        Text(
            "I=${f(it.slope)}C+${f(it.intercept)} · R²=${f(it.rSquared)} · ● unknown",
            style = MaterialTheme.typography.bodySmall,
            color = if (it.outsideCalibrationRange) Color(0xffff8a80) else Color.White
        )
    }
}

@Composable
private fun XyChart(
    series: List<ChartSeries>,
    xRange: ChartRange,
    yRange: ChartRange,
    xLabel: String,
    yLabel: String,
    emptyMessage: String,
    targetX: Double? = null,
    interval: ClosedFloatingPointRange<Double>? = null,
    errorBars: List<ChartErrorBar> = emptyList()
) {
    val hasData = series.any { it.points.isNotEmpty() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CompactLayout.chartHeight)
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CompactLayout.chartHeight)
                .semantics { contentDescription = "$yLabel by $xLabel chart" }
        ) {
            val left = 66.dp.toPx()
            val right = size.width - 12.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 48.dp.toPx()
            if (right <= left || bottom <= top) return@Canvas
            fun px(x: Double) = (left + xRange.fraction(x).toFloat() * (right - left))
            fun py(y: Double) = (bottom - yRange.fraction(y).toFloat() * (bottom - top))
            val axisColor = Color.White.copy(alpha = 0.65f)
            val gridColor = Color.White.copy(alpha = 0.13f)
            val labelPaint = android.graphics.Paint().apply {
                color = Color.White.copy(alpha = 0.82f).toArgb()
                textSize = 10.dp.toPx()
                isAntiAlias = true
            }

            interval?.let {
                val low = it.start.coerceIn(xRange.min, xRange.max)
                val high = it.endInclusive.coerceIn(xRange.min, xRange.max)
                if (high >= low) drawRect(Color(0xff66d9ef).copy(alpha = 0.14f), Offset(px(low), top), androidx.compose.ui.geometry.Size(px(high) - px(low), bottom - top))
            }
            for (index in 0..4) {
                val fraction = index / 4.0
                val xValue = xRange.min + fraction * (xRange.max - xRange.min)
                val yValue = yRange.min + fraction * (yRange.max - yRange.min)
                val x = left + fraction.toFloat() * (right - left)
                val y = bottom - fraction.toFloat() * (bottom - top)
                drawLine(gridColor, Offset(x, top), Offset(x, bottom))
                drawLine(gridColor, Offset(left, y), Offset(right, y))
                drawContext.canvas.nativeCanvas.apply {
                    labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                    drawText(tick(xValue), x, bottom + 15.dp.toPx(), labelPaint)
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    drawText(tick(yValue), left - 5.dp.toPx(), y + 3.dp.toPx(), labelPaint)
                }
            }
            drawLine(axisColor, Offset(left, top), Offset(left, bottom), 1.5f)
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), 1.5f)
            targetX?.takeIf { it.isFinite() && it in xRange.min..xRange.max }?.let {
                drawLine(Color(0xffffd54f), Offset(px(it), top), Offset(px(it), bottom), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
            errorBars.forEach { bar ->
                if (bar.x.isFinite() && bar.low.isFinite() && bar.high.isFinite()) {
                    val x = px(bar.x)
                    val lowY = py(bar.low)
                    val highY = py(bar.high)
                    val cap = 5.dp.toPx()
                    drawLine(Color(0xff90caf9), Offset(x, lowY), Offset(x, highY), 2f)
                    drawLine(Color(0xff90caf9), Offset(x - cap, lowY), Offset(x + cap, lowY), 2f)
                    drawLine(Color(0xff90caf9), Offset(x - cap, highY), Offset(x + cap, highY), 2f)
                }
            }
            series.forEach { item ->
                if (item.connectLine && item.points.size >= 2) {
                    val path = Path()
                    item.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(px(point.x), py(point.y)) else path.lineTo(px(point.x), py(point.y))
                    }
                    drawPath(
                        path,
                        item.color,
                        style = Stroke(
                            width = 2.5f,
                            pathEffect = if (item.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null
                        )
                    )
                }
                if (item.showPoints) item.points.forEach { drawCircle(item.color, 5.dp.toPx(), Offset(px(it.x), py(it.y))) }
            }
            drawContext.canvas.nativeCanvas.apply {
                labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                labelPaint.textSize = 11.dp.toPx()
                drawText(xLabel, (left + right) / 2f, size.height - 5.dp.toPx(), labelPaint)
                save()
                rotate(-90f, 12.dp.toPx(), (top + bottom) / 2f)
                drawText(yLabel, 12.dp.toPx(), (top + bottom) / 2f, labelPaint)
                restore()
            }
        }
        if (!hasData) Text(emptyMessage, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun validChartRange(range: ClosedFloatingPointRange<Double>): ChartRange =
    if (range.start.isFinite() && range.endInclusive.isFinite() && range.endInclusive > range.start) {
        ChartRange(range.start, range.endInclusive)
    } else ChartRange(0.0, 1.0)

private fun tick(value: Double): String = when {
    !value.isFinite() -> "—"
    kotlin.math.abs(value) >= 10_000.0 || (value != 0.0 && kotlin.math.abs(value) < 0.01) -> String.format(Locale.US, "%.1e", value)
    kotlin.math.abs(value) >= 100.0 -> String.format(Locale.US, "%.0f", value)
    kotlin.math.abs(value) >= 10.0 -> String.format(Locale.US, "%.1f", value)
    else -> String.format(Locale.US, "%.2f", value)
}

private fun f(value: Double): String = String.format(Locale.US, "%.4g", value)
private fun percent(fraction: Double): String = String.format(Locale.US, "%.3f%%", fraction * 100.0)
