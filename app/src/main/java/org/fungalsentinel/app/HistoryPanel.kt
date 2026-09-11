package org.fungalsentinel.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryPanel(
    entries: List<ExperimentHistoryEntity>,
    selectedId: String?,
    busy: Boolean,
    onSelect: (String?) -> Unit,
    onExport: (ExperimentHistoryEntity) -> Unit,
    onDelete: (ExperimentHistoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = entries.firstOrNull { it.id == selectedId }
    var pendingDelete by remember { mutableStateOf<ExperimentHistoryEntity?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(CompactLayout.panelPadding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CompactLayout.sectionSpacing)
    ) {
        Text("History", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("Completed analyses are stored as ordinary ZIP archives.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        HorizontalDivider(color = Color.White.copy(alpha = 0.22f))
        if (selected != null) {
            OutlinedButton(onClick = { onSelect(null) }) { Text("Back to history") }
            Text(selected.name, style = MaterialTheme.typography.titleMedium)
            HistoryMetric("Created", formatHistoryDate(selected.createdAtEpochMs))
            HistoryMetric("Fluorophore", selected.fluorophoreName)
            HistoryMetric("Area", historyNumber(selected.sampleArea))
            HistoryMetric("Sample SD", historyNumber(selected.sampleSd))
            HistoryMetric("Replicates", selected.replicateCount.toString())
            selected.predictedConcentration?.let { HistoryMetric("Predicted concentration", historyNumber(it)) }
            selected.rSquared?.let { HistoryMetric("R²", historyNumber(it)) }
            HistoryMetric("Calibration", selected.calibrationQuality)
            HistoryMetric("Standards", selected.standardCount.toString())
            HistoryMetric("DNG mode", selected.dngSaveMode)
            HistoryMetric("RAW files in ZIP", selected.rawFileCount.toString())
            HistoryMetric("ZIP size", formatBytes(selected.archiveSizeBytes))
            Row(horizontalArrangement = Arrangement.spacedBy(CompactLayout.actionSpacing)) {
                Button(onClick = { onExport(selected) }, enabled = !busy) { Text("Export ZIP") }
                OutlinedButton(onClick = { pendingDelete = selected }, enabled = !busy) { Text("Delete") }
            }
        } else if (entries.isEmpty()) {
            Text("No saved experiments yet.", color = Color.LightGray)
            Text("Create a project, complete the concentration curve, then finish it to save History.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        } else {
            entries.forEach { item ->
                Card(
                    onClick = { onSelect(item.id) },
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.10f), contentColor = Color.White)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(CompactLayout.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(CompactLayout.relatedSpacing)
                    ) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatHistoryDate(item.createdAtEpochMs), style = MaterialTheme.typography.bodySmall)
                        Text("${item.fluorophoreName} · area ${historyNumber(item.sampleArea)} · n=${item.replicateCount}")
                        Text("${item.dngSaveMode} · ${item.rawFileCount} RAW", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    }
                }
            }
        }
    }
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete history entry?") },
            text = { Text("The internal ZIP archive will be deleted. DNG files already saved in Pictures/FungalSentinel are not deleted.") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(item) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HistoryMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray)
        Text(value, color = Color.White)
    }
}

private fun formatHistoryDate(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
private fun historyNumber(value: Double) = String.format(Locale.US, "%.6g", value)
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
