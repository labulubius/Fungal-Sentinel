package org.fungalsentinel.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SettingsPanel(
    dngSaveMode: DngSaveMode,
    enforceCalibrationGate: Boolean,
    configurationEnabled: Boolean,
    onDngSaveModeChanged: (DngSaveMode) -> Unit,
    onEnforceCalibrationGateChanged: (Boolean) -> Unit,
    onSaveDiagnosticLog: () -> Unit,
    onClearDiagnosticLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClearLog by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = Color.White
    ) {
        Column(
            Modifier
                .padding(CompactLayout.panelPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CompactLayout.sectionSpacing)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))

            Text("RAW storage", style = MaterialTheme.typography.titleMedium)
            DngSaveMode.entries.forEach { mode ->
                TextButton(
                    onClick = { onDngSaveModeChanged(mode) },
                    enabled = configurationEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(if (mode == dngSaveMode) "✓ ${mode.displayName}" else mode.displayName)
                        Text(mode.description, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Applies to future captures. ZIP includes any DNG already saved.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CompactLayout.relatedSpacing)
                ) {
                    Text("Calibration protection", style = MaterialTheme.typography.titleMedium)
                    Text("Block Step 3 if Step 2 G error exceeds 10 nm.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = enforceCalibrationGate,
                    onCheckedChange = onEnforceCalibrationGateChanged,
                    enabled = configurationEnabled
                )
            }
            Text(
                if (enforceCalibrationGate) "ON · Failed calibration cannot continue."
                else "Override ON · Results remain FAILED.",
                color = if (enforceCalibrationGate) Color(0xffa5d6a7) else Color(0xffffd54f),
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
            Text("Diagnostic log", style = MaterialTheme.typography.titleMedium)
            Text(
                "Recorded automatically, stored locally (max 2 MB), never uploaded. Export includes device/Android info, camera and capture diagnostics; no RAW images or project names.",
                color = Color.LightGray,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onSaveDiagnosticLog, modifier = Modifier.fillMaxWidth()) {
                Text("Save diagnostic log")
            }
            if (confirmClearLog) {
                Text("Clear the local diagnostic log?", color = Color(0xffffd54f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { confirmClearLog = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        confirmClearLog = false
                        onClearDiagnosticLog()
                    }) { Text("Clear") }
                }
            } else {
                TextButton(onClick = { confirmClearLog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear diagnostic log")
                }
            }
        }
    }
}
