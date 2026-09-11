package org.fungalsentinel.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Owns and auto-saves the in-progress experiment across Activity recreation and process death. */
class FssaViewModel(application: Application) : AndroidViewModel(application) {
    private val draftExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ExperimentDraft").apply { isDaemon = true }
    }
    private var pendingDraftSave: ScheduledFuture<*>? = null
    private var restoring = true

    var initialized = false
    private var currentFssaState by mutableStateOf(FssaUiState())
    var fssaState: FssaUiState
        get() = currentFssaState
        set(value) {
            currentFssaState = value
            scheduleDraftSave()
        }
    private var currentDngSaveMode by mutableStateOf(DngSaveMode.ALL)
    var dngSaveMode: DngSaveMode
        get() = currentDngSaveMode
        set(value) {
            currentDngSaveMode = value
            scheduleDraftSave()
        }
    var projectNameInput by mutableStateOf("")

    private var currentHistoryName by mutableStateOf("")
    var historyName: String
        get() = currentHistoryName
        set(value) {
            currentHistoryName = value
            scheduleDraftSave()
        }
    private var currentHistoryDirty by mutableStateOf(true)
    var historyDirty: Boolean
        get() = currentHistoryDirty
        set(value) {
            currentHistoryDirty = value
            scheduleDraftSave()
        }
    var historyRevision = 0L
    private var currentProjectId by mutableStateOf("")
    var projectId: String
        get() = currentProjectId
        set(value) {
            currentProjectId = value
            scheduleDraftSave()
        }
    private var currentProjectCreatedAtEpochMs by mutableStateOf(0L)
    var projectCreatedAtEpochMs: Long
        get() = currentProjectCreatedAtEpochMs
        set(value) {
            currentProjectCreatedAtEpochMs = value
            scheduleDraftSave()
        }
    var cameraSettings: CameraControlSettings? = null
        set(value) {
            field = value
            scheduleDraftSave()
        }

    val captureArtifacts = mutableListOf<CaptureArchiveArtifact>()
    val capturedProfiles = mutableListOf<CapturedProfileRecord>()
    val projectDngFiles = mutableListOf<StoredDng>()

    val hasActiveProject: Boolean get() = projectId.isNotBlank() && historyName.isNotBlank()

    init {
        ExperimentDraftStore.load(application)?.let { draft ->
            val hasRecoveredWork = draft.profiles.isNotEmpty() || draft.state.sampleAnalysis != null ||
                draft.state.standards.isNotEmpty()
            val recoveredName = draft.historyName.ifBlank { if (hasRecoveredWork) "Recovered project" else "" }
            val recoveredProjectId = draft.projectId.ifBlank {
                if (hasRecoveredWork) UUID.randomUUID().toString() else ""
            }
            val recoveredProjectActive = recoveredName.isNotBlank() && recoveredProjectId.isNotBlank()
            fssaState = draft.state.copy(
                busy = false,
                pendingCapture = null,
                step = if (recoveredProjectActive) draft.state.step else AnalysisStep.PROJECT,
                status = if (recoveredProjectActive) {
                    "Recovered unfinished experiment draft."
                } else {
                    "Enter a project name to begin."
                }
            )
            dngSaveMode = draft.dngSaveMode
            historyName = recoveredName
            historyDirty = draft.historyDirty
            historyRevision = draft.historyRevision
            projectId = recoveredProjectId
            projectCreatedAtEpochMs = draft.projectCreatedAtEpochMs.takeIf { it > 0L }
                ?: if (hasRecoveredWork) System.currentTimeMillis() else 0L
            cameraSettings = draft.cameraSettings
            captureArtifacts += draft.artifacts
            capturedProfiles += draft.profiles
            projectDngFiles += draft.projectDngFiles
            initialized = true
        }
        restoring = false
    }

    fun createProject(name: String) {
        require(!hasActiveProject) { "Reset the current project before creating another one." }
        require(isValidProjectName(name)) {
            "Project name must be 1–100 characters and contain a valid filename character."
        }
        val trimmedName = name.trim()
        projectNameInput = ""
        projectId = UUID.randomUUID().toString()
        projectCreatedAtEpochMs = System.currentTimeMillis()
        historyName = trimmedName
        historyRevision++
        historyDirty = false
        fssaState = fssaState.copy(
            step = AnalysisStep.POSITIONING,
            selectedCapturePurpose = AnalysisCapturePurpose.POSITIONING,
            status = "Project '$trimmedName' ready for wavelength calibration.",
            logs = fssaState.logs + "Step 1: project created: $trimmedName."
        )
    }

    fun markHistoryDirty() {
        historyRevision++
        historyDirty = true
    }

    fun recordProjectDng(expectedProjectId: String, storedDng: StoredDng): Boolean =
        synchronized(projectDngFiles) {
            if (projectId != expectedProjectId) return@synchronized false
            projectDngFiles += storedDng
            scheduleDraftSave()
            true
        }

    fun resetExperiment() {
        val current = fssaState
        synchronized(capturedProfiles) { capturedProfiles.clear() }
        synchronized(captureArtifacts) { captureArtifacts.clear() }
        synchronized(projectDngFiles) {
            projectId = ""
            projectDngFiles.clear()
        }
        historyName = ""
        projectNameInput = ""
        projectCreatedAtEpochMs = 0L
        historyRevision++
        historyDirty = false
        fssaState = FssaUiState(
            redWavelengthInput = current.redWavelengthInput,
            greenWavelengthInput = current.greenWavelengthInput,
            blueWavelengthInput = current.blueWavelengthInput,
            selectedFluorophore = current.selectedFluorophore,
            spdData = current.spdData,
            spdSource = current.spdSource,
            status = "Enter a project name to begin.",
            logs = listOf("Experiment reset. Camera settings, wavelengths, SPD and saved History were preserved.")
        )
    }

    /** Forces a lifecycle checkpoint after all already queued writes. */
    fun flushDraft(): Boolean {
        pendingDraftSave?.cancel(false)
        val checkpoint = snapshot()
        return runCatching {
            draftExecutor.submit { ExperimentDraftStore.save(getApplication(), checkpoint) }
                .get(10, TimeUnit.SECONDS)
        }.isSuccess
    }

    private fun scheduleDraftSave() {
        if (restoring) return
        pendingDraftSave?.cancel(false)
        pendingDraftSave = draftExecutor.schedule({
            runCatching { ExperimentDraftStore.save(getApplication(), snapshot()) }
        }, 300, TimeUnit.MILLISECONDS)
    }

    private fun snapshot() = ExperimentDraftSnapshot(
        state = fssaState.copy(busy = false, pendingCapture = null),
        dngSaveMode = dngSaveMode,
        historyName = historyName,
        historyDirty = historyDirty,
        historyRevision = historyRevision,
        cameraSettings = cameraSettings,
        projectId = projectId,
        projectCreatedAtEpochMs = projectCreatedAtEpochMs,
        profiles = synchronized(capturedProfiles) { capturedProfiles.toList() },
        artifacts = synchronized(captureArtifacts) { captureArtifacts.toList() },
        projectDngFiles = synchronized(projectDngFiles) { projectDngFiles.toList() }
    )

    override fun onCleared() {
        pendingDraftSave?.cancel(false)
        val finalSnapshot = snapshot()
        draftExecutor.execute {
            runCatching { ExperimentDraftStore.save(getApplication(), finalSnapshot) }
        }
        draftExecutor.shutdown()
        super.onCleared()
    }
}
