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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class SidebarSection { ANALYZE, PARAMETERS, HISTORY, SETTINGS }

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
    dngSaveMode: DngSaveMode,
    historyEntries: List<ExperimentHistoryEntity>,
    selectedHistoryId: String?,
    historyBusy: Boolean,
    historyName: String,
    projectNameInput: String,
    projectActive: Boolean,
    historyDirty: Boolean,
    enforceCalibrationGate: Boolean,
    onClose: () -> Unit,
    onAnalyzeClicked: () -> Unit,
    onStepChanged: (AnalysisStep) -> Unit,
    onParametersClicked: () -> Unit,
    onHistoryClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    onHistorySelected: (String?) -> Unit,
    onHistoryExport: (ExperimentHistoryEntity) -> Unit,
    onHistoryDelete: (ExperimentHistoryEntity) -> Unit,
    onProjectNameChanged: (String) -> Unit,
    onCreateProject: () -> Unit,
    onSaveHistory: () -> Unit,
    onResetExperiment: (deleteDng: Boolean) -> Unit,
    onCapture: () -> Unit,
    onSettingsChanged: (CameraControlSettings) -> Unit,
    onDngSaveModeChanged: (DngSaveMode) -> Unit,
    onEnforceCalibrationGateChanged: (Boolean) -> Unit,
    onSaveDiagnosticLog: () -> Unit,
    onClearDiagnosticLog: () -> Unit,
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
    modifier: Modifier = Modifier
) {
    var showResetConfirmation by remember { mutableStateOf(false) }
    val panelVisible = detailVisible || showResetConfirmation
    // Keep the navigation as an icon/initial rail whenever a detail panel is open.
    // This preserves camera-preview width in both portrait and landscape.
    val compactDetail = panelVisible
    val sidebarModifier = if (compactDetail) {
        Modifier.width(CompactLayout.compactSidebarWidth)
    } else {
        Modifier.width(IntrinsicSize.Max).widthIn(min = 144.dp, max = 180.dp)
    }
    Row(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Surface(
            modifier = sidebarModifier.clickable { },
            shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            color = PanelBackground,
            contentColor = Color.White,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = if (compactDetail) 2.dp else 6.dp, vertical = 3.dp)
            ) {
                IconButton(
                    onClick = {
                        showResetConfirmation = false
                        onClose()
                    },
                    modifier = Modifier
                        .padding(start = if (compactDetail) 0.dp else 2.dp, top = 4.dp)
                        .size(if (compactDetail) 48.dp else 56.dp)
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
                        text = if (compactDetail) "A" else if (analyzeExpanded) "▾ Analyze" else "▸ Analyze",
                        selected = section == SidebarSection.ANALYZE,
                        accessibilityLabel = "Analyze",
                        onClick = {
                            showResetConfirmation = false
                            onAnalyzeClicked()
                        }
                    )

                    if (analyzeExpanded) {
                        AnalysisStep.entries.forEach { step ->
                            DirectoryButton(
                                text = if (compactDetail) step.number.toString() else "${step.number}. ${step.title}",
                                selected = section == SidebarSection.ANALYZE && state.step == step,
                                indent = if (compactDetail) 0.dp else 8.dp,
                                smallText = true,
                                accessibilityLabel = "Step ${step.number}: ${step.title}",
                                onClick = {
                                    showResetConfirmation = false
                                    onStepChanged(step)
                                }
                            )
                        }
                    }

                    DirectoryButton(
                        text = if (compactDetail) "⚙" else "Parameters",
                        selected = section == SidebarSection.PARAMETERS,
                        accessibilityLabel = "Parameters",
                        onClick = {
                            showResetConfirmation = false
                            onParametersClicked()
                        }
                    )

                    DirectoryButton(
                        text = when {
                            compactDetail && state.busy -> "…"
                            compactDetail && !support.raw -> "×"
                            compactDetail && support.autoExposureLock && !settings.manualControlsEnabled && exposureStatus != ExposureStatus.AUTO_LOCKED -> "L"
                            compactDetail -> "●"
                            state.busy -> "Processing…"
                            !support.raw -> "RAW Unsupported"
                            support.autoExposureLock && !settings.manualControlsEnabled && !settings.meterThenLockEnabled -> "Enable Meter & lock"
                            support.autoExposureLock && !settings.manualControlsEnabled && exposureStatus != ExposureStatus.AUTO_LOCKED -> "Locking exposure…"
                            else -> "Capture"
                        },
                        selected = false,
                        enabled = captureEnabled,
                        emphasized = true,
                        accessibilityLabel = "Capture",
                        onClick = {
                            showResetConfirmation = false
                            onCapture()
                        }
                    )

                    DirectoryButton(
                        text = if (compactDetail) "R" else "Reset",
                        selected = false,
                        enabled = historyName.isNotBlank() && !state.busy && !historyBusy,
                        accessibilityLabel = "Reset experiment",
                        onClick = { showResetConfirmation = true }
                    )

                    DirectoryButton(
                        text = if (compactDetail) "H" else "History",
                        selected = section == SidebarSection.HISTORY,
                        accessibilityLabel = "History",
                        onClick = {
                            showResetConfirmation = false
                            onHistoryClicked()
                        }
                    )

                    DirectoryButton(
                        text = if (compactDetail) "S" else "Settings",
                        selected = section == SidebarSection.SETTINGS,
                        accessibilityLabel = "Settings",
                        onClick = {
                            showResetConfirmation = false
                            onSettingsClicked()
                        }
                    )
                }
            }
        }

        if (panelVisible) {
            val detailModifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 4.dp, top = 6.dp, end = 4.dp)
                .widthIn(max = CompactLayout.detailPanelMaxWidth)
            Surface(
                modifier = detailModifier.clickable { },
                shape = MaterialTheme.shapes.large,
                color = PanelBackground,
                contentColor = Color.White,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp
            ) {
                if (showResetConfirmation) {
                    ResetConfirmationPanel(
                        onCancel = { showResetConfirmation = false },
                        onResetKeepDng = {
                            showResetConfirmation = false
                            onResetExperiment(false)
                        },
                        onResetDeleteDng = {
                            showResetConfirmation = false
                            onResetExperiment(true)
                        }
                    )
                } else when (section) {
                    SidebarSection.ANALYZE -> FssaPanel(
                        state = state,
                        onWavelengthChanged = onWavelengthChanged,
                        onRestoreDefaultWavelengths = onRestoreDefaultWavelengths,
                        onImportSpd = onImportSpd,
                        onRestoreBuiltInSpd = onRestoreBuiltInSpd,
                        onFluorophoreChanged = onFluorophoreChanged,
                        onStandardConcentrationChanged = onStandardConcentrationChanged,
                        onCapturePurposeChanged = onCapturePurposeChanged,
                        onClearCaptureBatch = onClearCaptureBatch,
                        onCommitStandard = onCommitStandard,
                        onRemoveStandard = onRemoveStandard,
                        onCalculateConcentration = onCalculateConcentration,
                        historyName = historyName,
                        projectNameInput = projectNameInput,
                        projectActive = projectActive,
                        dngSaveMode = dngSaveMode,
                        onProjectNameChanged = onProjectNameChanged,
                        onCreateProject = onCreateProject,
                        historySaving = historyBusy,
                        historyDirty = historyDirty,
                        enforceCalibrationGate = enforceCalibrationGate,
                        onSaveHistory = onSaveHistory
                    )
                    SidebarSection.PARAMETERS -> CameraSettingsPanel(
                        settings = settings,
                        ranges = ranges,
                        support = support,
                        exposureStatus = exposureStatus,
                        onSettingsChanged = onSettingsChanged
                    )
                    SidebarSection.HISTORY -> HistoryPanel(
                        entries = historyEntries,
                        selectedId = selectedHistoryId,
                        busy = historyBusy,
                        onSelect = onHistorySelected,
                        onExport = onHistoryExport,
                        onDelete = onHistoryDelete
                    )
                    SidebarSection.SETTINGS -> SettingsPanel(
                        dngSaveMode = dngSaveMode,
                        enforceCalibrationGate = enforceCalibrationGate,
                        configurationEnabled = !state.busy && !historyBusy,
                        onDngSaveModeChanged = onDngSaveModeChanged,
                        onEnforceCalibrationGateChanged = onEnforceCalibrationGateChanged,
                        onSaveDiagnosticLog = onSaveDiagnosticLog,
                        onClearDiagnosticLog = onClearDiagnosticLog
                    )
                }
            }
        }
    }
}

@Composable
private fun ResetConfirmationPanel(
    onCancel: () -> Unit,
    onResetKeepDng: () -> Unit,
    onResetDeleteDng: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(CompactLayout.panelPadding),
        verticalArrangement = Arrangement.spacedBy(CompactLayout.sectionSpacing)
    ) {
        Text("Reset experiment", style = MaterialTheme.typography.titleLarge)
        PanelDivider()
        Text("Clear captures and results? Settings, SPD and History stay saved.")
        Text("DNG files", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        DirectoryButton(
            text = "Keep DNG & reset",
            selected = false,
            emphasized = true,
            onClick = onResetKeepDng
        )
        DirectoryButton(
            text = "Delete DNG & reset",
            selected = false,
            onClick = onResetDeleteDng
        )
        DirectoryButton(
            text = "Cancel",
            selected = false,
            onClick = onCancel
        )
    }
}

@Composable
private fun PanelDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.22f))
            .heightIn(min = 1.dp, max = 1.dp)
    )
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
    emphasized: Boolean = false,
    accessibilityLabel: String? = null
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
            .heightIn(min = CompactLayout.sidebarItemMinHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .semantics {
                accessibilityLabel?.let { contentDescription = it }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
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
