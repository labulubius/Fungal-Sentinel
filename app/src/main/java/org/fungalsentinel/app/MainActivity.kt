package org.fungalsentinel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private data class PendingCaptureContext(
    val state: FssaUiState,
    val projectId: String,
    val projectName: String,
    val dngSaveMode: DngSaveMode
)

class MainActivity : ComponentActivity() {

    private val logTag = "FungalSentinel"
    private val experimentViewModel: FssaViewModel by viewModels()

    private lateinit var cameraController: CameraController
    private val rawProcessingExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RawProcessing").apply { isDaemon = true }
    }
    private var textureView: TextureView? = null

    private var sidebarVisible by mutableStateOf(false)
    private var sidebarDetailVisible by mutableStateOf(false)
    private var sidebarSection by mutableStateOf(SidebarSection.ANALYZE)
    private var analyzeExpanded by mutableStateOf(true)
    private var cameraSupport by mutableStateOf(CameraControlSupport(false, false, false, false, false, false))
    private var controlRanges by mutableStateOf(CameraControlRanges.fallback)
    private var cameraSettings by mutableStateOf(CameraControlSettings.manualDefaults())
    private var exposureStatus by mutableStateOf(ExposureStatus.MANUAL)
    private var captureReady by mutableStateOf(false)
    private var permissionScreenVisible = false
    @Volatile private var captureGeneration = 0L
    @Volatile private var pendingCaptureSnapshot: PendingCaptureContext? = null
    private var fssaState: FssaUiState
        get() = experimentViewModel.fssaState
        set(value) { experimentViewModel.fssaState = value }
    private var dngSaveMode: DngSaveMode
        get() = experimentViewModel.dngSaveMode
        set(value) { experimentViewModel.dngSaveMode = value }
    private var historyName: String
        get() = experimentViewModel.historyName
        set(value) { experimentViewModel.historyName = value }
    private var projectNameInput: String
        get() = experimentViewModel.projectNameInput
        set(value) { experimentViewModel.projectNameInput = value }
    private var historyDirty: Boolean
        get() = experimentViewModel.historyDirty
        set(value) { experimentViewModel.historyDirty = value }
    private var historyRevision: Long
        get() = experimentViewModel.historyRevision
        set(value) { experimentViewModel.historyRevision = value }
    private val captureArtifacts get() = experimentViewModel.captureArtifacts
    private val capturedProfiles get() = experimentViewModel.capturedProfiles

    private var historyEntries by mutableStateOf<List<ExperimentHistoryEntity>>(emptyList())
    private var selectedHistoryId by mutableStateOf<String?>(null)
    private var historyBusy by mutableStateOf(false)
    private var enforceCalibrationGate by mutableStateOf(true)
    private var pendingExport: ExperimentHistoryEntity? = null

    private val historyExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val item = pendingExport
        pendingExport = null
        if (uri == null || item == null) return@registerForActivityResult
        historyBusy = true
        rawProcessingExecutor.execute {
            val result = runCatching {
                val source = File(filesDir, item.archiveRelativePath)
                val root = File(filesDir, "experiments").canonicalFile
                require(source.canonicalFile.parentFile == root) { "Invalid History archive path." }
                require(source.isFile) { "The internal ZIP archive is missing." }
                contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Could not open the selected destination." }
                    source.inputStream().use { it.copyTo(output) }
                }
            }
            runOnUiThread {
                historyBusy = false
                result.fold(
                    onSuccess = { showMessage("History ZIP exported.") },
                    onFailure = { updateFssaError("ZIP export failed: ${it.message}") }
                )
            }
        }
    }

    private val diagnosticLogExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        rawProcessingExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Could not open the selected destination." }
                    DiagnosticLogger.export(this, output)
                }
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { showMessage("Diagnostic log saved.") },
                    onFailure = {
                        DiagnosticLogger.error(this, "Diagnostic log export failed", it)
                        showMessage("Could not save diagnostic log: ${it.message}")
                    }
                )
            }
        }
    }

    private val spdPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read the selected file.")
            val data = SpectralAlgorithms.parseSpdCsv(text)
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "true_spd.csv"
            setSpdSource(data, "Custom: $name")
        } catch (error: Exception) {
            updateFssaError("SPD import failed: ${error.message}")
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showCameraPreview()
            } else {
                showPermissionRequired()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLogger.info(this, "App started · activeProject=${experimentViewModel.hasActiveProject}")

        try {
            cameraController = CameraController(
                context = this,
                onCaptureReadyChanged = ::updateCaptureReady,
                onRawCaptured = ::handleRawCapture,
                onError = ::handleCameraError,
                onExposureStatusChanged = ::updateExposureStatus
            )
        } catch (error: Exception) {
            DiagnosticLogger.error(this, "Camera initialization failed", error)
            showCameraUnavailable(error.message ?: "No compatible camera could be initialized.")
            return
        }
        cameraSupport = cameraController.support
        DiagnosticLogger.info(
            this,
            "Camera ready · RAW=${cameraSupport.raw} · manual=${cameraSupport.canUseManualControls} · AE-lock=${cameraSupport.autoExposureLock}"
        )
        controlRanges = cameraController.ranges.forSpectralControls()
        cameraSettings = experimentViewModel.cameraSettings
            ?.clampedTo(controlRanges)
            ?.let(cameraController::updateSettings)
            ?: cameraController.settings.clampedTo(controlRanges).let(cameraController::updateSettings)
        experimentViewModel.cameraSettings = cameraSettings
        exposureStatus = AutoExposureState().status(cameraSettings, cameraSupport)
        val settingsPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        enforceCalibrationGate = settingsPreferences.getBoolean("enforceCalibrationGate", true)
        val savedDngMode = settingsPreferences.getString("dngSaveMode", null)
        if (savedDngMode != null) {
            dngSaveMode = runCatching { DngSaveMode.valueOf(savedDngMode) }.getOrDefault(DngSaveMode.ALL)
        } else if (!experimentViewModel.initialized) {
            dngSaveMode = DngSaveMode.ALL
        }
        if (!experimentViewModel.initialized) {
            restoreBuiltInSpd(initialLoad = true)
            experimentViewModel.initialized = true
        }
        refreshHistory()
        cameraController.start()

        if (hasCameraPermission()) {
            showCameraPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::cameraController.isInitialized) return
        cameraController.start()

        val view = textureView
        if (hasCameraPermission() && permissionScreenVisible) {
            showCameraPreview()
        } else if (hasCameraPermission() && view != null && view.isAvailable) {
            cameraController.open(view)
        }
    }

    override fun onPause() {
        if (!::cameraController.isInitialized) {
            super.onPause()
            return
        }
        if (fssaState.pendingCapture != null) {
            DiagnosticLogger.warning(this, "Capture interrupted by app pause · purpose=${fssaState.pendingCapture}")
            captureGeneration++
            pendingCaptureSnapshot = null
            fssaState = fssaState.copy(
                busy = false,
                pendingCapture = null,
                status = "Capture interrupted because the app was paused.",
                logs = fssaState.logs + "ERROR: Capture interrupted because the app was paused."
            )
        }
        cameraController.close()
        cameraController.stop()
        super.onPause()
    }

    override fun onStop() {
        if (experimentViewModel.initialized && !experimentViewModel.flushDraft()) {
            Log.w(logTag, "Experiment draft checkpoint did not complete.")
            DiagnosticLogger.warning(this, "Experiment draft checkpoint failed")
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Let accepted tasks reach their finally blocks so every RAW lease is released.
        rawProcessingExecutor.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionRequired() {
        permissionScreenVisible = true
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Camera permission is required to capture and save RAW images.")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant camera permission")
                        }
                        Button(onClick = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                            )
                        }) { Text("Open app settings") }
                    }
                }
            }
        }
    }

    private fun showCameraUnavailable(message: String) {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Camera unavailable\n$message",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }

    private fun showCameraPreview() {
        permissionScreenVisible = false
        if (!experimentViewModel.hasActiveProject) {
            sidebarVisible = true
            sidebarDetailVisible = true
            sidebarSection = SidebarSection.ANALYZE
            analyzeExpanded = true
        }
        if (!experimentViewModel.hasActiveProject && fssaState.step != AnalysisStep.PROJECT
        ) {
            fssaState = fssaState.copy(
                step = AnalysisStep.PROJECT,
                status = stepGuidance(AnalysisStep.PROJECT, fssaState, enforceCalibrationGate)
            )
        }
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(
                        onViewCreated = { textureView = it },
                        onSurfaceAvailable = cameraController::open,
                        onSurfaceSizeChanged = cameraController::updatePreviewTransform,
                        onSurfaceDestroyed = cameraController::close
                    )

                    if (!sidebarVisible) {
                        IconButton(
                            onClick = {
                                sidebarVisible = true
                                sidebarDetailVisible = false
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .safeDrawingPadding()
                                .padding(12.dp)
                                .size(64.dp)
                                .clip(MaterialTheme.shapes.large)
                                .background(Color.Black.copy(alpha = 0.62f))
                        ) {
                            Text("☰", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        }
                    }

                    if (sidebarVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (sidebarDetailVisible) {
                                        sidebarDetailVisible = false
                                    } else {
                                        sidebarVisible = false
                                    }
                                }
                        )
                        val exposureReady = cameraSettings.manualControlsEnabled ||
                            !cameraSupport.autoExposureLock ||
                            (cameraSettings.meterThenLockEnabled && exposureStatus == ExposureStatus.AUTO_LOCKED)
                        val captureEnabled = experimentViewModel.hasActiveProject && cameraSupport.raw && captureReady && exposureReady &&
                            !fssaState.busy && !historyBusy && canCaptureSelectedBatch(fssaState, enforceCalibrationGate)
                        AppSidebar(
                            state = fssaState,
                            section = sidebarSection,
                            analyzeExpanded = analyzeExpanded,
                            detailVisible = sidebarDetailVisible,
                            captureEnabled = captureEnabled,
                            settings = cameraSettings,
                            ranges = controlRanges,
                            support = cameraSupport,
                            exposureStatus = exposureStatus,
                            dngSaveMode = dngSaveMode,
                            historyEntries = historyEntries,
                            selectedHistoryId = selectedHistoryId,
                            historyBusy = historyBusy,
                            historyName = historyName,
                            projectNameInput = projectNameInput,
                            projectActive = experimentViewModel.hasActiveProject,
                            historyDirty = historyDirty,
                            enforceCalibrationGate = enforceCalibrationGate,
                            onClose = {
                                sidebarDetailVisible = false
                                sidebarVisible = false
                            },
                            onAnalyzeClicked = {
                                if (sidebarSection == SidebarSection.ANALYZE) {
                                    analyzeExpanded = !analyzeExpanded
                                } else {
                                    sidebarSection = SidebarSection.ANALYZE
                                    analyzeExpanded = true
                                }
                            },
                            onStepChanged = {
                                if (fssaState.busy) return@AppSidebar
                                val requestedStep = if (
                                    !experimentViewModel.hasActiveProject && it != AnalysisStep.PROJECT
                                ) AnalysisStep.PROJECT else it
                                val selectedAgain = requestedStep == it &&
                                    sidebarSection == SidebarSection.ANALYZE &&
                                    fssaState.step == requestedStep && sidebarDetailVisible
                                sidebarSection = SidebarSection.ANALYZE
                                sidebarDetailVisible = !selectedAgain
                                fssaState = fssaState.copy(
                                    step = requestedStep,
                                    selectedCapturePurpose = defaultPurposeForStep(requestedStep, fssaState),
                                    status = if (requestedStep != it) {
                                        "Complete Step 1 by creating a project first."
                                    } else {
                                        stepGuidance(requestedStep, fssaState, enforceCalibrationGate)
                                    }
                                )
                            },
                            onParametersClicked = {
                                if (fssaState.busy) return@AppSidebar
                                val selectedAgain = sidebarSection == SidebarSection.PARAMETERS && sidebarDetailVisible
                                sidebarSection = SidebarSection.PARAMETERS
                                sidebarDetailVisible = !selectedAgain
                            },
                            onHistoryClicked = {
                                if (fssaState.busy) return@AppSidebar
                                val selectedAgain = sidebarSection == SidebarSection.HISTORY && sidebarDetailVisible
                                sidebarSection = SidebarSection.HISTORY
                                sidebarDetailVisible = !selectedAgain
                                if (!selectedAgain) refreshHistory()
                            },
                            onSettingsClicked = {
                                if (fssaState.busy) return@AppSidebar
                                val selectedAgain = sidebarSection == SidebarSection.SETTINGS && sidebarDetailVisible
                                sidebarSection = SidebarSection.SETTINGS
                                sidebarDetailVisible = !selectedAgain
                            },
                            onHistorySelected = { selectedHistoryId = it },
                            onHistoryExport = ::exportHistory,
                            onHistoryDelete = ::deleteHistory,
                            onProjectNameChanged = { projectNameInput = it },
                            onCreateProject = {
                                if (experimentViewModel.hasActiveProject) {
                                    fssaState = fssaState.copy(
                                        step = AnalysisStep.POSITIONING,
                                        selectedCapturePurpose = AnalysisCapturePurpose.POSITIONING,
                                        status = stepGuidance(AnalysisStep.POSITIONING, fssaState, enforceCalibrationGate)
                                    )
                                } else if (isValidProjectName(projectNameInput)) {
                                    experimentViewModel.createProject(projectNameInput)
                                    projectNameInput = ""
                                }
                            },
                            onCapture = { startAnalysisCapture(fssaState.selectedCapturePurpose) },
                            onSettingsChanged = ::updateCameraSettings,
                            onDngSaveModeChanged = ::updateDngSaveMode,
                            onEnforceCalibrationGateChanged = ::updateCalibrationGate,
                            onSaveDiagnosticLog = {
                                DiagnosticLogger.info(this@MainActivity, "Diagnostic log export requested")
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                diagnosticLogExporter.launch("Fungal-Sentinel-diagnostic-$stamp.txt")
                            },
                            onClearDiagnosticLog = {
                                if (DiagnosticLogger.clear(this@MainActivity)) {
                                    showMessage("Diagnostic log cleared.")
                                } else {
                                    showMessage("Could not clear diagnostic log.")
                                }
                            },
                            onWavelengthChanged = ::updateWavelength,
                            onRestoreDefaultWavelengths = ::restoreDefaultWavelengths,
                            onImportSpd = { spdPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                            onRestoreBuiltInSpd = { restoreBuiltInSpd(initialLoad = false) },
                            onFluorophoreChanged = ::changeFluorophore,
                            onStandardConcentrationChanged = {
                                if (fssaState.standardDraftConcentration == null &&
                                    fssaState.standardBlankProfiles.isEmpty() &&
                                    fssaState.standardSampleProfiles.isEmpty()
                                ) {
                                    fssaState = fssaState.copy(standardConcentrationInput = it)
                                }
                            },
                            onCapturePurposeChanged = ::selectCapturePurpose,
                            onClearCaptureBatch = ::clearCaptureBatch,
                            onCommitStandard = ::commitStandard,
                            onRemoveStandard = ::removeStandard,
                            onCalculateConcentration = ::calculateConcentration,
                            onSaveHistory = ::saveToHistory,
                            onResetExperiment = ::resetExperiment,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    }
                }
            }
        }
    }

    private fun resetExperiment(deleteDng: Boolean) {
        if (fssaState.busy || historyBusy) return
        DiagnosticLogger.info(this, "Experiment reset requested · deleteDng=$deleteDng")
        captureGeneration++
        pendingCaptureSnapshot = null
        if (!deleteDng) {
            finishProjectReset()
            return
        }
        historyBusy = true
        // This executor is also used by RAW processing, so all in-flight DNG writes finish
        // before the final project file list is captured for deletion.
        rawProcessingExecutor.execute {
            val files = synchronized(experimentViewModel.projectDngFiles) {
                experimentViewModel.projectDngFiles.distinctBy { it.sourceUri }
            }
            val failed = files.filterNot { runCatching { DngStorage.delete(this, it) }.getOrDefault(false) }
            runOnUiThread {
                historyBusy = false
                if (failed.isEmpty()) {
                    finishProjectReset()
                    showMessage("Deleted ${files.size} project DNG file(s).")
                } else {
                    synchronized(experimentViewModel.projectDngFiles) {
                        experimentViewModel.projectDngFiles.clear()
                        experimentViewModel.projectDngFiles += failed
                    }
                    fssaState = fssaState.copy(
                        status = "Could not delete ${failed.size} DNG file(s). Retry Reset or keep them."
                    )
                    showMessage("DNG deletion incomplete; the project was not reset.")
                }
            }
        }
    }

    private fun finishProjectReset() {
        experimentViewModel.resetExperiment()
        projectNameInput = ""
        selectedHistoryId = null
        sidebarVisible = true
        sidebarDetailVisible = true
        sidebarSection = SidebarSection.ANALYZE
        analyzeExpanded = true
    }

    private fun updateCalibrationGate(enabled: Boolean) {
        if (fssaState.busy || historyBusy || enabled == enforceCalibrationGate) return
        enforceCalibrationGate = enabled
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putBoolean("enforceCalibrationGate", enabled).apply()
        fssaState = fssaState.copy(
            status = if (enabled) "Calibration protection enabled."
            else "Calibration override ON · Failed results stay flagged.",
            logs = fssaState.logs + "Calibration protection ${if (enabled) "enabled" else "disabled"}."
        )
        markHistoryDirty()
    }

    private fun updateDngSaveMode(mode: DngSaveMode) {
        if (fssaState.busy || historyBusy || mode == dngSaveMode) return
        dngSaveMode = mode
        DiagnosticLogger.info(this, "DNG mode changed · ${mode.name}")
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("dngSaveMode", mode.name).apply()
        fssaState = fssaState.copy(
            status = "RAW storage mode: ${mode.displayName}.",
            logs = fssaState.logs + "RAW storage mode changed to ${mode.name}."
        )
        markHistoryDirty()
    }

    private fun markHistoryDirty() = experimentViewModel.markHistoryDirty()

    private fun refreshHistory() {
        if (historyBusy) return
        rawProcessingExecutor.execute {
            val result = runCatching { ExperimentHistoryDatabase.get(this).historyDao().getAll() }
            runOnUiThread {
                result.onSuccess { historyEntries = it }
                    .onFailure { showMessage("Could not load History: ${it.message}") }
            }
        }
    }

    private fun saveToHistory() {
        val sample = fssaState.sampleAnalysis ?: return updateFssaError("Analyze a sample before saving History.")
        if (fssaState.concentrationResult == null) {
            return updateFssaError("Build the concentration curve before finishing the project.")
        }
        if (historyBusy || !historyDirty) return
        val snapshot = fssaState
        val savedRevision = historyRevision
        val savedMode = dngSaveMode
        val savedCalibrationGate = enforceCalibrationGate
        val id = experimentViewModel.projectId
        val createdAt = experimentViewModel.projectCreatedAtEpochMs
        val resolvedName = historyName
        if (id.isBlank() || createdAt <= 0L || resolvedName.isBlank()) {
            return updateFssaError("No active project.")
        }
        val profiles = synchronized(capturedProfiles) { capturedProfiles.toList() }
        // Artifacts exist only for captures whose DNG was saved at capture time. A later
        // Settings change affects future captures, not already-created project evidence.
        val artifacts = synchronized(captureArtifacts) { captureArtifacts.toList() }
        historyBusy = true
        fssaState = fssaState.copy(busy = true, status = "Finishing project and creating History ZIP…")
        rawProcessingExecutor.execute {
            val result = runCatching {
                val dao = ExperimentHistoryDatabase.get(this).historyDao()
                val archive = ExperimentArchiveWriter.write(
                    this,
                    ExperimentArchiveWriter.Request(
                        id = id,
                        name = resolvedName,
                        createdAtEpochMs = createdAt,
                        appVersion = BuildConfig.VERSION_NAME,
                        dngSaveMode = savedMode,
                        enforceCalibrationGate = savedCalibrationGate,
                        state = snapshot,
                        profiles = profiles,
                        rawArtifacts = artifacts
                    )
                )
                val entity = ExperimentHistoryEntity(
                    id = id,
                    name = resolvedName,
                    createdAtEpochMs = createdAt,
                    fluorophoreName = sample.fluorophore.displayName,
                    sampleArea = sample.area,
                    sampleSd = sample.sd,
                    replicateCount = sample.replicateAreas.size,
                    predictedConcentration = snapshot.concentrationResult?.sampleConcentration,
                    rSquared = snapshot.concentrationResult?.rSquared,
                    calibrationQuality = snapshot.wavelengthCalibration?.quality?.name ?: "UNKNOWN",
                    standardCount = snapshot.standards.size,
                    dngSaveMode = savedMode.name,
                    rawFileCount = archive.includedRawCount,
                    archiveRelativePath = "experiments/${archive.file.name}",
                    archiveSizeBytes = archive.file.length()
                )
                dao.insert(entity)
                entity to archive.missingRawFiles
            }
            runOnUiThread {
                historyBusy = false
                result.fold(
                    onSuccess = { (entity, missing) ->
                        historyEntries = listOf(entity) + historyEntries.filterNot { it.id == entity.id }
                        if (historyRevision == savedRevision) historyDirty = false
                        experimentViewModel.resetExperiment()
                        selectedHistoryId = entity.id
                        sidebarSection = SidebarSection.HISTORY
                        sidebarDetailVisible = true
                        showMessage(
                            if (missing.isEmpty()) "Project finished and saved to History."
                            else "Project saved; ${missing.size} unavailable DNG file(s) were omitted."
                        )
                    },
                    onFailure = { updateFssaError("Could not save History ZIP: ${it.message}") }
                )
            }
        }
    }

    private fun exportHistory(item: ExperimentHistoryEntity) {
        if (historyBusy) return
        pendingExport = item
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(item.createdAtEpochMs))
        historyExporter.launch("FungalSentinel_${stamp}_${item.id.take(8)}.zip")
    }

    private fun deleteHistory(item: ExperimentHistoryEntity) {
        if (historyBusy) return
        historyBusy = true
        rawProcessingExecutor.execute {
            val result = runCatching {
                val archive = File(filesDir, item.archiveRelativePath)
                val root = File(filesDir, "experiments").canonicalFile
                require(archive.canonicalFile.parentFile == root) { "Invalid History archive path." }
                val tombstone = File(archive.parentFile, archive.name + ".deleting")
                require(!archive.exists() || archive.renameTo(tombstone)) { "Could not prepare the ZIP archive for deletion." }
                try {
                    ExperimentHistoryDatabase.get(this).historyDao().deleteById(item.id)
                    tombstone.delete()
                } catch (error: Exception) {
                    if (tombstone.exists()) tombstone.renameTo(archive)
                    throw error
                }
            }
            runOnUiThread {
                historyBusy = false
                result.fold(
                    onSuccess = {
                        historyEntries = historyEntries.filterNot { it.id == item.id }
                        if (selectedHistoryId == item.id) selectedHistoryId = null
                    },
                    onFailure = { showMessage("Could not delete History: ${it.message}") }
                )
            }
        }
    }

    private fun updateCameraSettings(next: CameraControlSettings) {
        if (fssaState.busy || historyBusy) return
        val previous = cameraSettings
        cameraSettings = cameraController.updateSettings(next)
        experimentViewModel.cameraSettings = cameraSettings
        val hasCameraDependentResults = fssaState.positioningProfiles.isNotEmpty() ||
            fssaState.wavelengthCalibration != null || fssaState.responseProfiles.isNotEmpty() ||
            fssaState.spectralResponse != null || fssaState.sampleBlankProfiles.isNotEmpty() ||
            fssaState.sampleProfiles.isNotEmpty() || fssaState.sampleAnalysis != null ||
            fssaState.standardBlankProfiles.isNotEmpty() || fssaState.standardSampleProfiles.isNotEmpty() ||
            fssaState.standards.isNotEmpty() || fssaState.concentrationResult != null
        val onlyExposureChanged = previous.copy(exposureTimeNs = cameraSettings.exposureTimeNs) == cameraSettings
        if (cameraSettings != previous && !onlyExposureChanged && hasCameraDependentResults) {
            fssaState = clearWavelengthDependentResults(
                fssaState,
                "Camera changed · Analysis cleared."
            )
        }
    }

    private fun updateWavelength(channel: SpectralChannel, value: String) {
        val currentValue = when (channel) {
            SpectralChannel.RED -> fssaState.redWavelengthInput
            SpectralChannel.GREEN -> fssaState.greenWavelengthInput
            SpectralChannel.BLUE -> fssaState.blueWavelengthInput
        }
        if (value == currentValue) return
        fssaState = clearWavelengthDependentResults(
            when (channel) {
                SpectralChannel.RED -> fssaState.copy(redWavelengthInput = value)
                SpectralChannel.GREEN -> fssaState.copy(greenWavelengthInput = value)
                SpectralChannel.BLUE -> fssaState.copy(blueWavelengthInput = value)
            },
            "Wavelengths changed · Analysis cleared."
        )
    }

    private fun restoreDefaultWavelengths() {
        if (fssaState.usesDefaultPositioningWavelengths) return
        val defaults = PositioningWavelengths.DEFAULT
        fssaState = clearWavelengthDependentResults(
            fssaState.copy(
                redWavelengthInput = defaults.redNm.toString(),
                greenWavelengthInput = defaults.greenNm.toString(),
                blueWavelengthInput = defaults.blueNm.toString()
            ),
            "Default wavelengths restored · Analysis cleared."
        )
    }

    private fun clearWavelengthDependentResults(state: FssaUiState, message: String): FssaUiState {
        purgeArchivedCaptures { true }
        markHistoryDirty()
        return state.copy(
        wavelengthCalibration = null,
        spectralResponse = null,
        sampleAnalysis = null,
        standards = emptyList(),
        concentrationResult = null,
        lockedMetadata = null,
        lockedXRoi = null,
        positioningProfiles = emptyList(),
        positioningAverageProfile = null,
        responseProfiles = emptyList(),
        sampleBlankProfiles = emptyList(),
        sampleProfiles = emptyList(),
        standardBlankProfiles = emptyList(),
        standardSampleProfiles = emptyList(),
        standardDraftConcentration = null,
        selectedCapturePurpose = AnalysisCapturePurpose.POSITIONING,
        lastProfile = null,
        status = message,
        logs = state.logs + message
        )
    }

    private fun purgeArchivedCaptures(predicate: (AnalysisCapturePurpose) -> Boolean) {
        synchronized(capturedProfiles) { capturedProfiles.removeAll { predicate(it.purpose) } }
        synchronized(captureArtifacts) { captureArtifacts.removeAll { it.purpose?.let(predicate) == true } }
    }

    private fun restoreBuiltInSpd(initialLoad: Boolean) {
        try {
            val data = resources.openRawResource(R.raw.true_spd).bufferedReader().use {
                SpectralAlgorithms.parseSpdCsv(it.readText())
            }
            if (initialLoad) {
                fssaState = fssaState.copy(
                    spdData = data,
                    spdSource = FssaUiState.BUILT_IN_SPD_SOURCE,
                    logs = fssaState.logs + "SPD: loaded built-in true_spd.csv (${data.wavelengthsNm.size} points)."
                )
            } else {
                setSpdSource(data, FssaUiState.BUILT_IN_SPD_SOURCE)
            }
        } catch (error: Exception) {
            updateFssaError("Built-in SPD load failed: ${error.message}")
        }
    }

    private fun setSpdSource(data: SpectralAlgorithms.SpdData, source: String) {
        purgeArchivedCaptures { it != AnalysisCapturePurpose.POSITIONING }
        markHistoryDirty()
        fssaState = fssaState.copy(
            spdData = data,
            spdSource = source,
            spectralResponse = null,
            responseProfiles = emptyList(),
            sampleBlankProfiles = emptyList(),
            sampleProfiles = emptyList(),
            standardBlankProfiles = emptyList(),
            standardSampleProfiles = emptyList(),
            standardDraftConcentration = null,
            sampleAnalysis = null,
            standards = emptyList(),
            concentrationResult = null,
            status = "SPD loaded (${data.wavelengthsNm.size} points) · Analysis cleared.",
            logs = fssaState.logs + "SPD: loaded $source (${data.wavelengthsNm.size} points); downstream results cleared."
        )
    }

    private fun startAnalysisCapture(purpose: AnalysisCapturePurpose) {
        if (!experimentViewModel.hasActiveProject) {
            updateFssaError("Create a project before capturing RAW data.")
            return
        }
        if (historyBusy || !canCaptureSelectedBatch(fssaState, enforceCalibrationGate) || purpose != fssaState.selectedCapturePurpose) {
            updateFssaError("The selected capture batch is not ready or is already full.")
            return
        }
        val captureState = if (
            purpose == AnalysisCapturePurpose.STANDARD_BLANK ||
            purpose == AnalysisCapturePurpose.STANDARD_SAMPLE
        ) {
            val concentration = fssaState.standardDraftConcentration
                ?: requireNotNull(fssaState.standardConcentrationInput.toDoubleOrNull())
            fssaState.copy(standardDraftConcentration = concentration)
        } else {
            fssaState
        }
        DiagnosticLogger.info(this, "RAW capture started · purpose=${purpose.name} · dng=${dngSaveMode.name}")
        captureGeneration++
        pendingCaptureSnapshot = PendingCaptureContext(
            captureState,
            experimentViewModel.projectId,
            historyName,
            dngSaveMode
        )
        fssaState = captureState.copy(
            busy = true,
            pendingCapture = purpose,
            status = "Capturing ${purpose.displayName} RAW…"
        )
        captureRawDng(captureGeneration)
    }

    private fun selectCapturePurpose(purpose: AnalysisCapturePurpose) {
        if (!fssaState.busy && !historyBusy && purposeMatchesStep(purpose, fssaState.step)) {
            fssaState = fssaState.copy(selectedCapturePurpose = purpose)
        }
    }

    private fun clearCaptureBatch(purpose: AnalysisCapturePurpose) {
        if (fssaState.busy || historyBusy) return
        val draftConcentration = fssaState.standardDraftConcentration
        when (purpose) {
            AnalysisCapturePurpose.POSITIONING -> purgeArchivedCaptures { true }
            AnalysisCapturePurpose.RESPONSE -> purgeArchivedCaptures { it != AnalysisCapturePurpose.POSITIONING }
            AnalysisCapturePurpose.SAMPLE_BLANK -> purgeArchivedCaptures { it == AnalysisCapturePurpose.SAMPLE_BLANK }
            AnalysisCapturePurpose.SAMPLE -> purgeArchivedCaptures { it == AnalysisCapturePurpose.SAMPLE }
            AnalysisCapturePurpose.STANDARD_BLANK, AnalysisCapturePurpose.STANDARD_SAMPLE -> {
                synchronized(capturedProfiles) {
                    capturedProfiles.removeAll { it.purpose == purpose && it.standardConcentration == draftConcentration }
                }
                synchronized(captureArtifacts) {
                    captureArtifacts.removeAll { it.purpose == purpose && it.standardConcentration == draftConcentration }
                }
            }
        }
        markHistoryDirty()
        fssaState = when (purpose) {
            AnalysisCapturePurpose.POSITIONING -> clearWavelengthDependentResults(
                fssaState,
                "Positioning captures and all dependent results were cleared."
            )
            AnalysisCapturePurpose.RESPONSE -> fssaState.copy(
                responseProfiles = emptyList(), spectralResponse = null,
                sampleBlankProfiles = emptyList(), sampleProfiles = emptyList(), sampleAnalysis = null,
                standardBlankProfiles = emptyList(), standardSampleProfiles = emptyList(),
                standardDraftConcentration = null,
                standards = emptyList(), concentrationResult = null,
                status = "Response captures and dependent results were cleared."
            )
            AnalysisCapturePurpose.SAMPLE_BLANK -> fssaState.copy(
                sampleBlankProfiles = emptyList(), sampleAnalysis = null, concentrationResult = null,
                status = "Sample blank captures were cleared."
            )
            AnalysisCapturePurpose.SAMPLE -> fssaState.copy(
                sampleProfiles = emptyList(), sampleAnalysis = null, concentrationResult = null,
                status = "Sample captures were cleared."
            )
            AnalysisCapturePurpose.STANDARD_BLANK -> fssaState.copy(
                standardBlankProfiles = emptyList(),
                standardDraftConcentration = if (fssaState.standardSampleProfiles.isEmpty()) null
                else fssaState.standardDraftConcentration,
                status = "Draft standard blank captures were cleared."
            )
            AnalysisCapturePurpose.STANDARD_SAMPLE -> fssaState.copy(
                standardSampleProfiles = emptyList(),
                standardDraftConcentration = if (fssaState.standardBlankProfiles.isEmpty()) null
                else fssaState.standardDraftConcentration,
                status = "Draft standard sample captures were cleared."
            )
        }
    }

    private fun changeFluorophore(fluorophore: Fluorophore) {
        if (fluorophore == fssaState.selectedFluorophore || fssaState.busy || historyBusy) return
        purgeArchivedCaptures {
            it == AnalysisCapturePurpose.STANDARD_BLANK || it == AnalysisCapturePurpose.STANDARD_SAMPLE
        }
        markHistoryDirty()
        val base = fssaState.copy(
            selectedFluorophore = fluorophore,
            standards = emptyList(),
            standardBlankProfiles = emptyList(),
            standardSampleProfiles = emptyList(),
            standardDraftConcentration = null,
            concentrationResult = null
        )
        val analysis = runCatching { analyzeIfComplete(base) }.getOrNull()
        fssaState = base.copy(
            sampleAnalysis = analysis,
            status = if (analysis == null) {
                "Fluorophore changed · Capture Blank and Sample."
            } else {
                "Fluorophore changed · Sample reanalyzed."
            }
        )
    }

    private fun commitStandard() {
        if (historyBusy) return
        markHistoryDirty()
        val concentration = fssaState.standardDraftConcentration
            ?: fssaState.standardConcentrationInput.toDoubleOrNull()
        if (concentration == null || !concentration.isFinite() || concentration < 0.0) {
            updateFssaError("Enter a finite, non-negative standard concentration.")
            return
        }
        if (fssaState.standards.any { it.concentration == concentration }) {
            updateFssaError("That standard concentration is already present.")
            return
        }
        if (fssaState.standards.size >= FssaUiState.MAX_STANDARDS) {
            updateFssaError("A maximum of 10 standards is supported.")
            return
        }
        try {
            val analysis = SpectralAlgorithms.analyzeSamples(
                fssaState.standardBlankProfiles,
                fssaState.standardSampleProfiles,
                requireNotNull(fssaState.wavelengthCalibration),
                requireNotNull(fssaState.spectralResponse),
                fssaState.selectedFluorophore
            )
            val standard = StandardMeasurement(concentration, analysis.replicateAreas)
            fssaState = fssaState.copy(
                standards = fssaState.standards + standard,
                standardBlankProfiles = emptyList(),
                standardSampleProfiles = emptyList(),
                standardConcentrationInput = "",
                standardDraftConcentration = null,
                selectedCapturePurpose = AnalysisCapturePurpose.STANDARD_BLANK,
                concentrationResult = null,
                status = "Standard ${fssaState.standards.size + 1} recorded: mean ${formatDouble(standard.area)}, SD ${formatDouble(standard.sd)}.",
                logs = fssaState.logs + "Step 5 standard: C=${formatDouble(concentration)}, mean=${formatDouble(standard.area)}, SD=${formatDouble(standard.sd)}, n=${standard.replicateAreas.size}."
            )
        } catch (error: Exception) {
            updateFssaError(error.message ?: "Could not add the standard.")
        }
    }

    private fun removeStandard(index: Int) {
        if (fssaState.busy || historyBusy || index !in fssaState.standards.indices) return
        val concentration = fssaState.standards[index].concentration
        synchronized(capturedProfiles) {
            capturedProfiles.removeAll { it.standardConcentration == concentration }
        }
        synchronized(captureArtifacts) {
            captureArtifacts.removeAll { it.standardConcentration == concentration }
        }
        markHistoryDirty()
        fssaState = fssaState.copy(
            standards = fssaState.standards.filterIndexed { itemIndex, _ -> itemIndex != index },
            concentrationResult = null,
            status = "Standard ${index + 1} removed; rebuild the concentration curve."
        )
    }

    private fun calculateConcentration() {
        if (historyBusy) return
        markHistoryDirty()
        val sample = fssaState.sampleAnalysis ?: return updateFssaError("Analyze a sample first.")
        try {
            val result = SpectralAlgorithms.calculateConcentration(fssaState.standards, sample.area)
            val warnings = AnalysisQualityPolicy.regressionWarnings(fssaState.standards, result)
            fssaState = fssaState.copy(
                concentrationResult = result,
                status = if (warnings.isEmpty()) "Concentration calculated."
                else "Concentration calculated with quality warnings.",
                logs = fssaState.logs +
                    "Step 5: I=${formatDouble(result.slope)}C+${formatDouble(result.intercept)}, " +
                    "R²=${formatDouble(result.rSquared)}, sample C=${formatDouble(result.sampleConcentration)}." +
                    if (warnings.isEmpty()) "" else " WARNINGS: ${warnings.joinToString(" ")}"
            )
        } catch (error: Exception) {
            updateFssaError(error.message ?: "Concentration calculation failed.")
        }
    }

    private fun captureRawDng(captureToken: Long) {
        if (!captureReady) {
            capturePreconditionFailed("Camera is still preparing.")
            return
        }
        cameraController.captureRaw(captureToken)?.let(::capturePreconditionFailed)
    }

    private fun capturePreconditionFailed(message: String) {
        showMessage(message)
        if (fssaState.pendingCapture != null) updateFssaError(message)
    }

    private fun handleRawCapture(capture: RawCapture) {
        try {
            rawProcessingExecutor.execute { processRawCapture(capture) }
        } catch (error: RejectedExecutionException) {
            capture.close()
            handleCameraError("RAW processing is no longer available.")
        }
    }

    private fun processRawCapture(capture: RawCapture) {
        val captureToken = capture.captureToken
        val captureContext = pendingCaptureSnapshot?.takeIf { captureToken == captureGeneration }

        try {
            if (captureContext == null) return
            val snapshot = captureContext.state
            val purpose = snapshot.selectedCapturePurpose
            val replicate = captureBatchSize(snapshot, purpose) + 1
            val purposePrefix = when (purpose) {
                AnalysisCapturePurpose.POSITIONING -> "positioning_$replicate"
                AnalysisCapturePurpose.RESPONSE -> "spd_$replicate"
                AnalysisCapturePurpose.SAMPLE_BLANK -> "sample_blank_$replicate"
                AnalysisCapturePurpose.SAMPLE -> "sample_$replicate"
                AnalysisCapturePurpose.STANDARD_BLANK -> "standard_${snapshot.standardDraftConcentration}_blank_$replicate"
                AnalysisCapturePurpose.STANDARD_SAMPLE -> "standard_${snapshot.standardDraftConcentration}_sample_$replicate"
            }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val fileName = "${safeProjectFileComponent(captureContext.projectName)}__${purposePrefix}_${System.currentTimeMillis()}.dng"
            val shouldSaveDng = captureContext.dngSaveMode.shouldSave(purpose)
            val dngSaveResult = if (shouldSaveDng) runCatching {
                DngStorage.save(this, capture.cameraCharacteristics, fileName, capture.image, capture.result)
            } else null
            val storedDng = dngSaveResult?.getOrNull()
            if (storedDng != null) {
                experimentViewModel.recordProjectDng(captureContext.projectId, storedDng)
            }

            run {
                val captureState = snapshot
                val profile = RawProfileExtractor.extract(
                    capture.image,
                    capture.result,
                    capture.cameraCharacteristics,
                    capture.cameraId,
                    fixedXRoi = captureState.lockedXRoi
                )
                DiagnosticLogger.info(
                    this,
                    "RAW received · purpose=${purpose.name} · ${profile.metadata.width}x${profile.metadata.height} · " +
                        "ISO=${profile.metadata.iso} · exposureNs=${profile.metadata.exposureTimeNs} · saturation=${formatPercent(profile.saturatedFraction)}"
                )
                val next = processAnalysisCapture(purpose, profile, captureToken, captureState)
                if (next != null) {
                    val standardConcentration = captureState.standardDraftConcentration.takeIf {
                        purpose == AnalysisCapturePurpose.STANDARD_BLANK ||
                            purpose == AnalysisCapturePurpose.STANDARD_SAMPLE
                    }
                    runOnUiThread {
                        if (captureToken == captureGeneration && fssaState.pendingCapture == purpose) {
                            if (purpose == AnalysisCapturePurpose.POSITIONING) {
                                purgeArchivedCaptures { it != AnalysisCapturePurpose.POSITIONING }
                            } else if (purpose == AnalysisCapturePurpose.RESPONSE) {
                                purgeArchivedCaptures {
                                    it != AnalysisCapturePurpose.POSITIONING && it != AnalysisCapturePurpose.RESPONSE
                                }
                            }
                            synchronized(capturedProfiles) {
                                capturedProfiles += CapturedProfileRecord(purpose, replicate, standardConcentration, profile)
                            }
                            if (storedDng != null) synchronized(captureArtifacts) {
                                captureArtifacts += CaptureArchiveArtifact(
                                    purpose, replicate, System.currentTimeMillis(), standardConcentration, storedDng
                                )
                            }
                            pendingCaptureSnapshot = null
                            fssaState = next
                            markHistoryDirty()
                        }
                    }
                }
            }

            when {
                !shouldSaveDng -> {
                    Log.i(logTag, "DNG skipped by ${captureContext.dngSaveMode.name} policy")
                    DiagnosticLogger.info(this, "DNG skipped · mode=${captureContext.dngSaveMode.name}")
                }
                storedDng != null -> {
                    Log.i(logTag, "Saved DNG: $fileName")
                    DiagnosticLogger.info(this, "DNG saved · bytes=${storedDng.sizeBytes}")
                    showMessage("Saved: $fileName")
                }
                else -> {
                    Log.w(logTag, "Analysis completed but DNG saving is unavailable", dngSaveResult?.exceptionOrNull())
                    DiagnosticLogger.warning(this, "DNG save failed", dngSaveResult?.exceptionOrNull())
                    showMessage("Analysis completed; this camera could not create a DNG file.")
                }
            }
        } catch (error: Exception) {
            Log.e(logTag, "RAW capture processing failed", error)
            DiagnosticLogger.error(this, "RAW capture processing failed", error)
            runOnUiThread {
                if (captureToken == captureGeneration) {
                    pendingCaptureSnapshot = null
                    updateFssaError("Capture processing failed: ${error.message}")
                }
                showMessage("Capture processing failed: ${error.message}")
            }
        } finally {
            capture.close()
        }
    }

    private fun processAnalysisCapture(
        purpose: AnalysisCapturePurpose,
        profile: SpectralProfile,
        captureToken: Long,
        current: FssaUiState
    ): FssaUiState? {
        try {
            val saturationQuality = AnalysisQualityPolicy.saturation(profile.saturatedFraction)
            require(saturationQuality != AnalysisQuality.FAILED) {
                "RAW rejected: ${formatPercent(profile.saturatedFraction)} of ROI samples are saturated; reduce exposure or ISO."
            }
            if (purpose != AnalysisCapturePurpose.POSITIONING || current.positioningProfiles.isNotEmpty()) {
                val reference = requireNotNull(current.lockedMetadata) {
                    "Camera reference metadata is missing; repeat wavelength calibration."
                }
                require(RawProfileExtractor.metadataMatches(reference, profile.metadata)) {
                    "Camera, ISO, focus, sensor, or RAW size changed; repeat calibration with consistent settings."
                }
            }
            val computedNext = when (purpose) {
                AnalysisCapturePurpose.POSITIONING -> {
                    val batch = current.positioningProfiles + profile
                    val averaged = SpectralAlgorithms.averageProfiles(batch)
                    val calibrationResult = runCatching {
                        SpectralAlgorithms.calibrateWavelength(
                            averaged,
                            requireNotNull(current.positioningWavelengths) { "Enter valid positioning wavelengths." }
                        )
                    }
                    val calibration = calibrationResult.getOrNull()
                    current.copy(
                        busy = false, pendingCapture = null,
                        positioningProfiles = batch,
                        wavelengthCalibration = calibration,
                        lockedMetadata = current.lockedMetadata ?: profile.metadata,
                        lockedXRoi = current.lockedXRoi ?: profile.xRoi,
                        responseProfiles = emptyList(), spectralResponse = null,
                        sampleBlankProfiles = emptyList(), sampleProfiles = emptyList(), sampleAnalysis = null,
                        standardBlankProfiles = emptyList(), standardSampleProfiles = emptyList(),
                        standardDraftConcentration = null,
                        standards = emptyList(), concentrationResult = null,
                        lastProfile = averaged,
                        positioningAverageProfile = averaged,
                        status = when {
                            calibration == null -> "Positioning ${batch.size}/5 · ${calibrationResult.exceptionOrNull()?.message}"
                            calibration.quality == AnalysisQuality.FAILED ->
                                "FAILED · G error ${formatDouble(calibration.validationErrorNm)} nm · Realign."
                            else -> "Positioning ${batch.size}/5 · G error ${formatDouble(calibration.validationErrorNm)} nm."
                        },
                        logs = current.logs + when {
                            calibration == null -> "Step 2 (${batch.size} shot): capture retained; ${calibrationResult.exceptionOrNull()?.message}."
                            calibration.quality == AnalysisQuality.FAILED ->
                                "Step 2 (${batch.size} shot): calibration FAILED, G error=${formatDouble(calibration.validationErrorNm)} nm."
                            else -> "Step 2 (${batch.size} shot): p=${formatDouble(calibration.slopePixelsPerNm)}λ+${formatDouble(calibration.interceptPixels)}, G error=${formatDouble(calibration.validationErrorNm)} nm, ROI=${profile.xRoi.first}..${profile.xRoi.last}."
                        }
                    )
                }
                AnalysisCapturePurpose.RESPONSE -> {
                    val batch = current.responseProfiles + profile
                    val averaged = SpectralAlgorithms.averageProfiles(batch)
                    val responseResult = runCatching {
                        SpectralAlgorithms.calibrateResponse(
                            averaged,
                            requireNotNull(current.wavelengthCalibration),
                            requireNotNull(current.spdData) { "No SPD source is loaded." }
                        )
                    }
                    val response = responseResult.getOrNull()
                    current.copy(
                        busy = false, pendingCapture = null,
                        responseProfiles = batch, spectralResponse = response,
                        sampleBlankProfiles = emptyList(), sampleProfiles = emptyList(), sampleAnalysis = null,
                        standardBlankProfiles = emptyList(), standardSampleProfiles = emptyList(),
                        standardDraftConcentration = null,
                        standards = emptyList(), concentrationResult = null,
                        lastProfile = averaged,
                        status = if (response == null) {
                            "SPD ${batch.size}/5 · ${responseResult.exceptionOrNull()?.message}"
                        } else {
                            "SPD ${batch.size}/5 · Response ready."
                        },
                        logs = current.logs + if (response == null) {
                            "Step 3 (${batch.size} shot): capture retained; ${responseResult.exceptionOrNull()?.message}."
                        } else {
                            "Step 3 (${batch.size} shot): SG(21,2) response over 420–680 nm using ${current.spdSource}."
                        }
                    )
                }
                AnalysisCapturePurpose.SAMPLE_BLANK -> {
                    val blanks = current.sampleBlankProfiles + profile
                    val analysisResult = runCatching { analyzeIfComplete(current.copy(sampleBlankProfiles = blanks)) }
                    val analysis = analysisResult.getOrNull()
                    current.copy(
                        busy = false, pendingCapture = null,
                        sampleBlankProfiles = blanks, sampleAnalysis = analysis,
                        concentrationResult = null, lastProfile = profile,
                        status = when {
                            analysis != null -> sampleStatus(analysis)
                            current.sampleProfiles.isEmpty() -> "Blank ${blanks.size}/5 · Capture Sample."
                            else -> "Blank ${blanks.size}/5 · ${analysisResult.exceptionOrNull()?.message}"
                        },
                        logs = current.logs + "Step 4 blank: ${blanks.size} capture(s)."
                    )
                }
                AnalysisCapturePurpose.SAMPLE -> {
                    val samples = current.sampleProfiles + profile
                    val analysisResult = runCatching { analyzeIfComplete(current.copy(sampleProfiles = samples)) }
                    val analysis = analysisResult.getOrNull()
                    current.copy(
                        busy = false, pendingCapture = null,
                        sampleProfiles = samples, sampleAnalysis = analysis,
                        concentrationResult = null, lastProfile = profile,
                        status = when {
                            analysis != null -> sampleStatus(analysis)
                            current.sampleBlankProfiles.isEmpty() -> "Sample ${samples.size}/5 · Capture Blank."
                            else -> "Sample ${samples.size}/5 · ${analysisResult.exceptionOrNull()?.message}"
                        },
                        logs = current.logs + "Step 4 sample: ${samples.size} capture(s)."
                    )
                }
                AnalysisCapturePurpose.STANDARD_BLANK -> {
                    val batch = current.standardBlankProfiles + profile
                    current.copy(
                        busy = false, pendingCapture = null, standardBlankProfiles = batch,
                        lastProfile = profile, concentrationResult = null,
                        status = "Draft standard blank ${batch.size}/5 captured."
                    )
                }
                AnalysisCapturePurpose.STANDARD_SAMPLE -> {
                    val batch = current.standardSampleProfiles + profile
                    current.copy(
                        busy = false, pendingCapture = null, standardSampleProfiles = batch,
                        lastProfile = profile, concentrationResult = null,
                        status = "Draft standard sample ${batch.size}/5 captured."
                    )
                }
            }
            val next = if (saturationQuality == AnalysisQuality.WARNING) {
                computedNext.copy(
                    status = computedNext.status + " Warning: ${formatPercent(profile.saturatedFraction)} saturated.",
                    logs = computedNext.logs + "WARNING: ${formatPercent(profile.saturatedFraction)} of ROI samples saturated."
                )
            } else computedNext
            return next
        } catch (error: Exception) {
            runOnUiThread {
                if (captureToken == captureGeneration && fssaState.pendingCapture == purpose) {
                    pendingCaptureSnapshot = null
                    updateFssaError("Analysis failed: ${error.message}")
                }
            }
            return null
        }
    }

    private fun analyzeIfComplete(state: FssaUiState): SampleAnalysis? {
        if (state.sampleBlankProfiles.isEmpty() || state.sampleProfiles.isEmpty()) return null
        return SpectralAlgorithms.analyzeSamples(
            state.sampleBlankProfiles,
            state.sampleProfiles,
            requireNotNull(state.wavelengthCalibration),
            requireNotNull(state.spectralResponse),
            state.selectedFluorophore
        )
    }

    private fun sampleStatus(analysis: SampleAnalysis): String =
        "Ready · Mean ${formatDouble(analysis.area)} · SD ${formatDouble(analysis.sd)} · n=${analysis.replicateAreas.size}"

    private fun updateFssaError(message: String) {
        runOnUiThread {
            pendingCaptureSnapshot = null
            fssaState = fssaState.copy(
                busy = false,
                pendingCapture = null,
                standardDraftConcentration = if (
                    fssaState.standardBlankProfiles.isEmpty() && fssaState.standardSampleProfiles.isEmpty()
                ) null else fssaState.standardDraftConcentration,
                status = message,
                logs = fssaState.logs + "ERROR: $message"
            )
        }
    }

    private fun handleCameraError(message: String) {
        DiagnosticLogger.error(this, "Camera error · $message")
        showMessage(message)
        if (fssaState.pendingCapture != null) {
            updateFssaError(message)
        } else {
            runOnUiThread {
                fssaState = fssaState.copy(
                    status = message,
                    logs = fssaState.logs + "CAMERA: $message"
                )
            }
        }
    }

    private fun showMessage(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCaptureReady(ready: Boolean) {
        runOnUiThread {
            captureReady = ready
        }
    }

    private fun updateExposureStatus(status: ExposureStatus) {
        runOnUiThread {
            exposureStatus = status
        }
    }
}

private fun stepGuidance(step: AnalysisStep, state: FssaUiState, enforceCalibrationGate: Boolean): String = when (step) {
    AnalysisStep.PROJECT -> "Enter a project name to begin."
    AnalysisStep.POSITIONING -> "Capture 1–5 positioning RAW frames."
    AnalysisStep.RESPONSE -> if (state.wavelengthCalibration == null) {
        "Complete wavelength calibration first."
    } else if (enforceCalibrationGate && state.wavelengthCalibration.quality == AnalysisQuality.FAILED) {
        "Step 2 calibration failed."
    } else if (state.wavelengthCalibration.quality == AnalysisQuality.FAILED) {
        "Override ON · Calibration remains FAILED."
    } else "Capture 1–5 matching SPD-source frames."
    AnalysisStep.SAMPLE -> if (state.spectralResponse == null) "Complete spectral response calibration first."
    else "Capture paired Blank and Sample frames."
    AnalysisStep.CONCENTRATION -> if (state.sampleAnalysis == null) "Complete Step 4 first."
    else "Capture Blank + Sample for each standard."
}

private fun purposeMatchesStep(purpose: AnalysisCapturePurpose, step: AnalysisStep): Boolean = when (step) {
    AnalysisStep.PROJECT -> false
    AnalysisStep.POSITIONING -> purpose == AnalysisCapturePurpose.POSITIONING
    AnalysisStep.RESPONSE -> purpose == AnalysisCapturePurpose.RESPONSE
    AnalysisStep.SAMPLE -> purpose == AnalysisCapturePurpose.SAMPLE_BLANK || purpose == AnalysisCapturePurpose.SAMPLE
    AnalysisStep.CONCENTRATION -> purpose == AnalysisCapturePurpose.STANDARD_BLANK ||
        purpose == AnalysisCapturePurpose.STANDARD_SAMPLE
}

private fun defaultPurposeForStep(step: AnalysisStep, state: FssaUiState): AnalysisCapturePurpose = when (step) {
    AnalysisStep.PROJECT -> AnalysisCapturePurpose.POSITIONING
    AnalysisStep.POSITIONING -> AnalysisCapturePurpose.POSITIONING
    AnalysisStep.RESPONSE -> AnalysisCapturePurpose.RESPONSE
    AnalysisStep.SAMPLE -> if (state.sampleBlankProfiles.isEmpty()) AnalysisCapturePurpose.SAMPLE_BLANK else AnalysisCapturePurpose.SAMPLE
    AnalysisStep.CONCENTRATION -> if (state.standardBlankProfiles.isEmpty()) {
        AnalysisCapturePurpose.STANDARD_BLANK
    } else {
        AnalysisCapturePurpose.STANDARD_SAMPLE
    }
}

private fun captureBatchSize(state: FssaUiState, purpose: AnalysisCapturePurpose): Int = when (purpose) {
    AnalysisCapturePurpose.POSITIONING -> state.positioningProfiles.size
    AnalysisCapturePurpose.RESPONSE -> state.responseProfiles.size
    AnalysisCapturePurpose.SAMPLE_BLANK -> state.sampleBlankProfiles.size
    AnalysisCapturePurpose.SAMPLE -> state.sampleProfiles.size
    AnalysisCapturePurpose.STANDARD_BLANK -> state.standardBlankProfiles.size
    AnalysisCapturePurpose.STANDARD_SAMPLE -> state.standardSampleProfiles.size
}

private fun canCaptureSelectedBatch(state: FssaUiState, enforceCalibrationGate: Boolean): Boolean {
    val purpose = state.selectedCapturePurpose
    if (!purposeMatchesStep(purpose, state.step) || captureBatchSize(state, purpose) >= FssaUiState.MAX_BATCH_PROFILES) return false
    return when (purpose) {
        AnalysisCapturePurpose.POSITIONING -> state.positioningWavelengths != null
        AnalysisCapturePurpose.RESPONSE -> state.spdData != null &&
            AnalysisQualityPolicy.allowsResponseCapture(state.wavelengthCalibration, enforceCalibrationGate)
        AnalysisCapturePurpose.SAMPLE_BLANK, AnalysisCapturePurpose.SAMPLE -> state.spectralResponse != null
        AnalysisCapturePurpose.STANDARD_BLANK, AnalysisCapturePurpose.STANDARD_SAMPLE -> {
            val concentration = state.standardDraftConcentration
                ?: state.standardConcentrationInput.toDoubleOrNull()
            state.spectralResponse != null && state.sampleAnalysis != null &&
                concentration?.isFinite() == true && concentration >= 0.0 &&
                state.standards.size < FssaUiState.MAX_STANDARDS
        }
    }
}

private fun formatDouble(value: Double): String = String.format(Locale.US, "%.5g", value)
private fun formatPercent(fraction: Double): String = String.format(Locale.US, "%.3f%%", fraction * 100.0)
