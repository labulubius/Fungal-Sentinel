package org.fungalsentinel.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class SidebarSection { ANALYZE, PARAMETERS }

private val PanelBackground = Color.Black.copy(alpha = 0.62f)
private val SelectedItemBackground = Color.White.copy(alpha = 0.20f)

@Composable
fun AppSidebar(
    state: FssaUiState,
    section: SidebarSection,
    analyzeExpanded: Boolean,
    detailVisible: Boolean,
    captureEnabled: Boolean,
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    support: CameraControlSupport,
    onClose: () -> Unit,
    onCloseDetail: () -> Unit,
    onAnalyzeClicked: () -> Unit,
    onStepChanged: (AnalysisStep) -> Unit,
    onParametersClicked: () -> Unit,
    onCapture: () -> Unit,
    onSettingsChanged: (CameraControlSettings) -> Unit,
    onImportSpd: () -> Unit,
    onFluorophoreChanged: (Fluorophore) -> Unit,
    onStandardConcentrationChanged: (String) -> Unit,
    onCalculateConcentration: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f / 3f)
                .clickable { },
            color = PanelBackground,
            contentColor = Color.White,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("FSSA", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onClose) { Text("×") }
                }

                Button(
                    onClick = onAnalyzeClicked,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = directoryButtonColors(section == SidebarSection.ANALYZE)
                ) {
                    Text(
                        if (analyzeExpanded) "▾ Analyze" else "▸ Analyze",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (analyzeExpanded) {
                    AnalysisStep.entries.forEach { step ->
                        val selected = section == SidebarSection.ANALYZE && state.step == step
                        Button(
                            onClick = { onStepChanged(step) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp),
                            colors = directoryButtonColors(selected)
                        ) {
                            Text(
                                "${step.number}. ${step.title}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Button(
                    onClick = onParametersClicked,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = directoryButtonColors(section == SidebarSection.PARAMETERS)
                ) {
                    Text("Camera Parameters", style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = onCapture,
                    enabled = captureEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SelectedItemBackground,
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.10f),
                        disabledContentColor = Color.White.copy(alpha = 0.45f)
                    )
                ) {
                    Text(
                        when {
                            state.busy -> "Processing…"
                            !support.raw -> "RAW Unsupported"
                            else -> "Capture"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (detailVisible) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 8.dp, top = 56.dp, end = 8.dp, bottom = 12.dp)
                    .widthIn(max = 560.dp)
                    .clickable { },
                color = PanelBackground,
                contentColor = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(onClick = onCloseDetail) { Text("×") }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        when (section) {
                            SidebarSection.ANALYZE -> FssaPanel(
                                state = state,
                                onImportSpd = onImportSpd,
                                onFluorophoreChanged = onFluorophoreChanged,
                                onStandardConcentrationChanged = onStandardConcentrationChanged,
                                onCalculateConcentration = onCalculateConcentration
                            )
                            SidebarSection.PARAMETERS -> CameraSettingsPanel(
                                settings = settings,
                                ranges = ranges,
                                support = support,
                                onSettingsChanged = onSettingsChanged
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun directoryButtonColors(selected: Boolean) = ButtonDefaults.buttonColors(
    containerColor = if (selected) SelectedItemBackground else Color.Transparent,
    contentColor = Color.White,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = Color.White.copy(alpha = 0.38f)
)
