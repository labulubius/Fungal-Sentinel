package org.fungalsentinel.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
            shape = MaterialTheme.shapes.large,
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

                DirectoryButton(
                    text = if (analyzeExpanded) "▾ Analyze" else "▸ Analyze",
                    selected = section == SidebarSection.ANALYZE,
                    onClick = onAnalyzeClicked
                )

                if (analyzeExpanded) {
                    AnalysisStep.entries.forEach { step ->
                        DirectoryButton(
                            text = "${step.number}. ${step.title}",
                            selected = section == SidebarSection.ANALYZE && state.step == step,
                            indent = 8.dp,
                            smallText = true,
                            onClick = { onStepChanged(step) }
                        )
                    }
                }

                DirectoryButton(
                    text = "Camera Parameters",
                    selected = section == SidebarSection.PARAMETERS,
                    smallText = true,
                    onClick = onParametersClicked
                )

                DirectoryButton(
                    text = when {
                        state.busy -> "Processing…"
                        !support.raw -> "RAW Unsupported"
                        else -> "Capture"
                    },
                    selected = false,
                    enabled = captureEnabled,
                    colors = captureButtonColors(),
                    onClick = onCapture
                )
            }
        }

        if (detailVisible) {
            Surface(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = 8.dp, top = 56.dp, end = 8.dp)
                    .widthIn(max = 560.dp)
                    .clickable { },
                shape = MaterialTheme.shapes.large,
                color = PanelBackground,
                contentColor = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp
            ) {
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

@Composable
private fun DirectoryButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    indent: Dp = 0.dp,
    smallText: Boolean = false,
    colors: ButtonColors = directoryButtonColors(selected)
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = colors
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
            style = if (smallText) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun directoryButtonColors(selected: Boolean) = ButtonDefaults.buttonColors(
    containerColor = if (selected) SelectedItemBackground else Color.Transparent,
    contentColor = Color.White,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = Color.White.copy(alpha = 0.38f)
)

@Composable
private fun captureButtonColors() = ButtonDefaults.buttonColors(
    containerColor = SelectedItemBackground,
    contentColor = Color.White,
    disabledContainerColor = Color.White.copy(alpha = 0.10f),
    disabledContentColor = Color.White.copy(alpha = 0.45f)
)
