package org.fungalsentinel.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
    exposureStatus: ExposureStatus,
    onClose: () -> Unit,
    onAnalyzeClicked: () -> Unit,
    onStepChanged: (AnalysisStep) -> Unit,
    onParametersClicked: () -> Unit,
    onCapture: () -> Unit,
    onSettingsChanged: (CameraControlSettings) -> Unit,
    onWavelengthChanged: (SpectralChannel, String) -> Unit,
    onRestoreDefaultWavelengths: () -> Unit,
    onImportSpd: () -> Unit,
    onRestoreBuiltInSpd: () -> Unit,
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
                .width(IntrinsicSize.Max)
                .widthIn(min = 160.dp, max = 220.dp)
                .clickable { },
            shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            color = PanelBackground,
            contentColor = Color.White,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .padding(start = 4.dp, top = 8.dp)
                        .size(64.dp)
                ) {
                    Text("☰", style = MaterialTheme.typography.headlineSmall)
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
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
                        emphasized = true,
                        onClick = onCapture
                    )
                }
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
                        onWavelengthChanged = onWavelengthChanged,
                        onRestoreDefaultWavelengths = onRestoreDefaultWavelengths,
                        onImportSpd = onImportSpd,
                        onRestoreBuiltInSpd = onRestoreBuiltInSpd,
                        onFluorophoreChanged = onFluorophoreChanged,
                        onStandardConcentrationChanged = onStandardConcentrationChanged,
                        onCalculateConcentration = onCalculateConcentration
                    )
                    SidebarSection.PARAMETERS -> CameraSettingsPanel(
                        settings = settings,
                        ranges = ranges,
                        support = support,
                        exposureStatus = exposureStatus,
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
    emphasized: Boolean = false
) {
    val background = when {
        emphasized && !enabled -> Color.White.copy(alpha = 0.10f)
        selected || emphasized -> SelectedItemBackground
        else -> Color.Transparent
    }
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent)
            .heightIn(min = 40.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = contentColor,
            textAlign = TextAlign.Start,
            style = if (smallText) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
        )
    }
}
