package org.fungalsentinel.app

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val logTag = "FungalSentinel"

    private lateinit var cameraController: CameraController
    private var textureView: TextureView? = null

    private var sidebarVisible by mutableStateOf(false)
    private var sidebarDetailVisible by mutableStateOf(false)
    private var sidebarSection by mutableStateOf(SidebarSection.ANALYZE)
    private var analyzeExpanded by mutableStateOf(true)
    private var cameraSupport by mutableStateOf(CameraControlSupport(false, false, false, false, false, false))
    private var controlRanges by mutableStateOf(CameraControlRanges.fallback)
    private var cameraSettings by mutableStateOf(CameraControlSettings.manualDefaults())
    private var captureReady by mutableStateOf(false)
    private var fssaState by mutableStateOf(FssaUiState())

    private val spdPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read the selected file.")
            val data = SpectralAlgorithms.parseSpdCsv(text)
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "true_spd.csv"
            fssaState = fssaState.copy(
                spdData = data,
                spdFileName = name,
                spectralResponse = null,
                sampleAnalysis = null,
                standards = emptyList(),
                concentrationResult = null,
                status = "Loaded ${data.wavelengthsNm.size} true-SPD points; downstream results were cleared.",
                logs = fssaState.logs + "SPD: loaded $name (${data.wavelengthsNm.size} points)."
            )
        } catch (error: Exception) {
            updateFssaError("SPD import failed: ${error.message}")
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showCameraPreview()
            } else {
                showMessage("Camera permission denied.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraController = CameraController(
            context = this,
            onCaptureReadyChanged = ::updateCaptureReady,
            onRawCaptured = ::handleRawCapture,
            onError = ::handleCameraError
        )
        cameraSupport = cameraController.support
        controlRanges = cameraController.ranges
        cameraSettings = cameraController.settings
        cameraController.start()

        if (hasCameraPermission()) {
            showCameraPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        cameraController.start()

        val view = textureView
        if (hasCameraPermission() && view != null && view.isAvailable) {
            cameraController.open(view)
        }
    }

    override fun onPause() {
        cameraController.close()
        cameraController.stop()
        super.onPause()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showCameraPreview() {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    CameraPreview(
                        onViewCreated = { textureView = it },
                        onSurfaceAvailable = cameraController::open,
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
                                .background(Color.Black.copy(alpha = 0.58f))
                        ) {
                            Text("☰", color = Color.White)
                        }
                    }

                    if (sidebarVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.16f))
                                .clickable {
                                    if (sidebarDetailVisible) {
                                        sidebarDetailVisible = false
                                    } else {
                                        sidebarVisible = false
                                    }
                                }
                        )
                        val captureEnabled = cameraSupport.raw && captureReady &&
                            cameraSupport.canUseManualControls && cameraSettings.manualControlsEnabled &&
                            !fssaState.busy && when (fssaState.step) {
                                AnalysisStep.POSITIONING -> true
                                AnalysisStep.RESPONSE -> fssaState.wavelengthCalibration != null
                                AnalysisStep.SAMPLE -> fssaState.spectralResponse != null
                                AnalysisStep.CONCENTRATION -> fssaState.spectralResponse != null &&
                                    fssaState.standardConcentrationInput.toDoubleOrNull() != null &&
                                    fssaState.standards.size < 5
                            }
                        AppSidebar(
                            state = fssaState,
                            section = sidebarSection,
                            analyzeExpanded = analyzeExpanded,
                            detailVisible = sidebarDetailVisible,
                            captureEnabled = captureEnabled,
                            settings = cameraSettings,
                            ranges = controlRanges,
                            support = cameraSupport,
                            onClose = {
                                sidebarDetailVisible = false
                                sidebarVisible = false
                            },
                            onCloseDetail = { sidebarDetailVisible = false },
                            onAnalyzeClicked = {
                                if (sidebarSection == SidebarSection.ANALYZE) {
                                    analyzeExpanded = !analyzeExpanded
                                } else {
                                    sidebarSection = SidebarSection.ANALYZE
                                    analyzeExpanded = true
                                }
                            },
                            onStepChanged = {
                                val selectedAgain = sidebarSection == SidebarSection.ANALYZE &&
                                    fssaState.step == it && sidebarDetailVisible
                                sidebarSection = SidebarSection.ANALYZE
                                sidebarDetailVisible = !selectedAgain
                                fssaState = fssaState.copy(step = it)
                            },
                            onParametersClicked = {
                                val selectedAgain = sidebarSection == SidebarSection.PARAMETERS &&
                                    sidebarDetailVisible
                                sidebarSection = SidebarSection.PARAMETERS
                                sidebarDetailVisible = !selectedAgain
                            },
                            onCapture = {
                                val purpose = when (fssaState.step) {
                                    AnalysisStep.POSITIONING -> AnalysisCapturePurpose.POSITIONING
                                    AnalysisStep.RESPONSE -> AnalysisCapturePurpose.RESPONSE
                                    AnalysisStep.SAMPLE -> AnalysisCapturePurpose.SAMPLE
                                    AnalysisStep.CONCENTRATION -> AnalysisCapturePurpose.STANDARD
                                }
                                startAnalysisCapture(purpose)
                            },
                            onSettingsChanged = ::updateCameraSettings,
                            onImportSpd = { spdPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                            onFluorophoreChanged = {
                                if (it != fssaState.selectedFluorophore) {
                                    fssaState = fssaState.copy(
                                        selectedFluorophore = it,
                                        sampleAnalysis = null,
                                        standards = emptyList(),
                                        concentrationResult = null,
                                        status = "Fluorophore changed; sample and standards were cleared."
                                    )
                                }
                            },
                            onStandardConcentrationChanged = {
                                fssaState = fssaState.copy(standardConcentrationInput = it)
                            },
                            onCalculateConcentration = ::calculateConcentration,
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    }
                }
            }
        }
    }

    private fun updateCameraSettings(next: CameraControlSettings) {
        cameraSettings = cameraController.updateSettings(next)
    }

    private fun startAnalysisCapture(purpose: AnalysisCapturePurpose) {
        if (purpose != AnalysisCapturePurpose.POSITIONING && fssaState.wavelengthCalibration == null) {
            updateFssaError("Complete wavelength calibration first.")
            return
        }
        fssaState = fssaState.copy(
            busy = true,
            pendingCapture = purpose,
            status = "Capturing ${purpose.name.lowercase()} RAW…"
        )
        captureRawDng()
    }

    private fun calculateConcentration() {
        val sample = fssaState.sampleAnalysis ?: return updateFssaError("Analyze a sample first.")
        try {
            val result = SpectralAlgorithms.calculateConcentration(fssaState.standards, sample.area)
            fssaState = fssaState.copy(
                concentrationResult = result,
                status = "Concentration calculated.",
                logs = fssaState.logs +
                    "Step 4: I=${formatDouble(result.slope)}C+${formatDouble(result.intercept)}, " +
                    "R²=${formatDouble(result.rSquared)}, sample C=${formatDouble(result.sampleConcentration)}."
            )
        } catch (error: Exception) {
            updateFssaError(error.message ?: "Concentration calculation failed.")
        }
    }

    private fun captureRawDng() {
        if (!captureReady) {
            capturePreconditionFailed("Camera is still preparing.")
            return
        }
        cameraController.captureRaw()?.let(::capturePreconditionFailed)
    }

    private fun capturePreconditionFailed(message: String) {
        showMessage(message)
        if (fssaState.pendingCapture != null) updateFssaError(message)
    }

    private fun handleRawCapture(
        image: Image,
        result: TotalCaptureResult,
        cameraCharacteristics: CameraCharacteristics,
        cameraId: String
    ) {
        val purpose = fssaState.pendingCapture

        try {
            val prefix = when (purpose) {
                AnalysisCapturePurpose.POSITIONING -> "positioning"
                AnalysisCapturePurpose.RESPONSE -> "spd"
                AnalysisCapturePurpose.SAMPLE -> "sample"
                AnalysisCapturePurpose.STANDARD -> "standard_${fssaState.standardConcentrationInput}"
                null -> "fungal_sentinel"
            }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val fileName = "${prefix}_${System.currentTimeMillis()}.dng"
            DngStorage.save(this, cameraCharacteristics, fileName, image, result)
            Log.i(logTag, "Saved DNG: $fileName")
            if (purpose != null) {
                val profile = RawProfileExtractor.extract(image, result, cameraCharacteristics, cameraId)
                processAnalysisCapture(purpose, profile)
            }
            showMessage("Saved: $fileName")
        } catch (e: Exception) {
            Log.e(logTag, "DNG capture processing failed", e)
            updateFssaError("Capture processing failed: ${e.message}")
            showMessage("Capture processing failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun processAnalysisCapture(
        purpose: AnalysisCapturePurpose,
        profile: SpectralProfile
    ) {
        val current = fssaState
        try {
            val next = when (purpose) {
                AnalysisCapturePurpose.POSITIONING -> {
                    val calibration = SpectralAlgorithms.calibrateWavelength(profile)
                    current.copy(
                        busy = false,
                        pendingCapture = null,
                        wavelengthCalibration = calibration,
                        spectralResponse = null,
                        sampleAnalysis = null,
                        standards = emptyList(),
                        concentrationResult = null,
                        lockedMetadata = profile.metadata,
                        lastProfile = profile,
                        status = "Wavelength calibration completed; G error ${formatDouble(calibration.validationErrorNm)} nm.",
                        logs = current.logs +
                            "Step 1: p=${formatDouble(calibration.slopePixelsPerNm)}λ+${formatDouble(calibration.interceptPixels)}, " +
                            "G error=${formatDouble(calibration.validationErrorNm)} nm, ROI=${profile.xRoi.first}..${profile.xRoi.last}."
                    )
                }
                AnalysisCapturePurpose.RESPONSE -> {
                    val calibration = requireNotNull(current.wavelengthCalibration)
                    val spd = current.spdData ?: SpectralAlgorithms.defaultSpd()
                    val response = SpectralAlgorithms.calibrateResponse(profile, calibration, spd)
                    current.copy(
                        busy = false,
                        pendingCapture = null,
                        spectralResponse = response,
                        sampleAnalysis = null,
                        standards = emptyList(),
                        concentrationResult = null,
                        lastProfile = profile,
                        status = "Spectral response generated.",
                        logs = current.logs + "Step 2: calibrated R/G/B response over 420–680 nm using ${current.spdFileName ?: "simulated default SPD"}."
                    )
                }
                AnalysisCapturePurpose.SAMPLE -> {
                    val analysis = SpectralAlgorithms.analyzeSample(
                        profile,
                        requireNotNull(current.wavelengthCalibration),
                        requireNotNull(current.spectralResponse),
                        current.selectedFluorophore
                    )
                    current.copy(
                        busy = false,
                        pendingCapture = null,
                        sampleAnalysis = analysis,
                        concentrationResult = null,
                        lastProfile = profile,
                        status = "Sample spectrum analyzed.",
                        logs = current.logs +
                            "Step 3: ${analysis.fluorophore.displayName}, area=${formatDouble(analysis.area)}, peak=${formatDouble(analysis.peak)}."
                    )
                }
                AnalysisCapturePurpose.STANDARD -> {
                    val concentration = current.standardConcentrationInput.toDoubleOrNull()
                        ?: error("Enter a valid standard concentration.")
                    val analysis = SpectralAlgorithms.analyzeSample(
                        profile,
                        requireNotNull(current.wavelengthCalibration),
                        requireNotNull(current.spectralResponse),
                        current.selectedFluorophore
                    )
                    current.copy(
                        busy = false,
                        pendingCapture = null,
                        standards = current.standards + StandardMeasurement(concentration, analysis.area),
                        standardConcentrationInput = "",
                        concentrationResult = null,
                        lastProfile = profile,
                        status = "Standard ${current.standards.size + 1} recorded.",
                        logs = current.logs + "Step 4 standard: C=${formatDouble(concentration)}, area=${formatDouble(analysis.area)}."
                    )
                }
            }
            runOnUiThread { fssaState = next }
        } catch (error: Exception) {
            updateFssaError("Analysis failed: ${error.message}")
        }
    }

    private fun updateFssaError(message: String) {
        runOnUiThread {
            fssaState = fssaState.copy(
                busy = false,
                pendingCapture = null,
                status = message,
                logs = fssaState.logs + "ERROR: $message"
            )
        }
    }

    private fun handleCameraError(message: String) {
        showMessage(message)
        if (fssaState.pendingCapture != null) updateFssaError(message)
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
}

private fun formatDouble(value: Double): String = String.format(Locale.US, "%.5g", value)
