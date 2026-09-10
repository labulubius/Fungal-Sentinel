package org.fungalsentinel.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private val logTag = "FungalSentinel"

    private lateinit var cameraId: String

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraCharacteristics: CameraCharacteristics

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var rawImageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val rawLock = Any()
    private var pendingRawImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null

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

        cameraManager = getSystemService(CameraManager::class.java)
        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: error("No back-facing camera is available.")
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        cameraSupport = detectCameraSupport(cameraCharacteristics)
        controlRanges = detectControlRanges(cameraCharacteristics)
        cameraSettings = CameraControlSettings.manualDefaults()
            .copy(manualControlsEnabled = cameraSupport.canUseManualControls)
            .clampedTo(controlRanges)

        startBackgroundThread()

        if (hasCameraPermission()) {
            showCameraPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        val view = textureView
        if (hasCameraPermission() && view != null && view.isAvailable) {
            openCamera(view)
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
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
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            TextureView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        surface: SurfaceTexture,
                                        width: Int,
                                        height: Int
                                    ) {
                                        openCamera(this@apply)
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        surface: SurfaceTexture,
                                        width: Int,
                                        height: Int
                                    ) {
                                    }

                                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                        closeCamera()
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                    }
                                }

                                textureView = this
                            }
                        }
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
                                .background(Color.Black.copy(alpha = 0.34f))
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

    @Suppress("MissingPermission")
    private fun openCamera(view: TextureView) {
        if (!hasCameraPermission()) return
        if (cameraDevice != null) return

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview(view)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    showMessage("Camera open failed: $error")
                }
            },
            backgroundHandler
        )
    }

    private fun startPreview(view: TextureView) {
        val camera = cameraDevice ?: return
        val surfaceTexture = view.surfaceTexture ?: return
        updateCaptureReady(false)

        surfaceTexture.setDefaultBufferSize(view.width, view.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)

        rawImageReader?.close()
        rawImageReader = if (cameraSupport.raw) {
            val rawSize = chooseRawSize()
            ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                2
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireNextImage()
                        synchronized(rawLock) {
                            pendingRawImage?.close()
                            pendingRawImage = image
                            trySavePendingDngLocked()
                        }
                    },
                    backgroundHandler
                )
            }
        } else {
            null
        }

        val surfaces = buildList {
            previewSurface?.let(::add)
            rawImageReader?.surface?.let(::add)
        }

        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    applyRepeatingRequest()
                    updateCaptureReady(rawImageReader != null)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    showMessage("Preview session configuration failed.")
                }
            },
            backgroundHandler
        )
    }

    private fun updateCameraSettings(next: CameraControlSettings) {
        cameraSettings = next.clampedTo(controlRanges)
        applyRepeatingRequest()
    }

    private fun applyRepeatingRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(surface)
        applyCameraSettings(requestBuilder)

        session.setRepeatingRequest(
            requestBuilder.build(),
            null,
            backgroundHandler
        )
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
        if (!cameraSupport.raw) {
            capturePreconditionFailed("This device does not expose RAW capture.")
            return
        }

        val camera = cameraDevice ?: run {
            capturePreconditionFailed("Camera is not open.")
            return
        }
        val session = captureSession ?: run {
            capturePreconditionFailed("Capture session is not ready.")
            return
        }
        if (!captureReady) {
            capturePreconditionFailed("Camera is still preparing.")
            return
        }
        val rawSurface = rawImageReader?.surface ?: run {
            capturePreconditionFailed("RAW reader is not ready.")
            return
        }

        synchronized(rawLock) {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingCaptureResult = null
        }

        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        requestBuilder.addTarget(rawSurface)
        applyCameraSettings(requestBuilder)

        session.capture(
            requestBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    synchronized(rawLock) {
                        pendingCaptureResult = result
                        trySavePendingDngLocked()
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    updateFssaError("RAW capture failed: ${failure.reason}")
                    showMessage("RAW capture failed: ${failure.reason}")
                }
            },
            backgroundHandler
        )
    }

    private fun capturePreconditionFailed(message: String) {
        showMessage(message)
        if (fssaState.pendingCapture != null) updateFssaError(message)
    }

    private fun applyCameraSettings(requestBuilder: CaptureRequest.Builder) {
        val settings = cameraSettings
        val manualEnabled = settings.manualControlsEnabled && cameraSupport.canUseManualControls

        if (!manualEnabled) {
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            return
        }

        requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
        requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)

        if (cameraSupport.manualFocus) {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistanceDiopters)
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        requestBuilder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            if (settings.autoWhiteBalanceEnabled) {
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            } else {
                CaptureRequest.CONTROL_AWB_MODE_OFF
            }
        )

        if (cameraSupport.noiseReduction) {
            requestBuilder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                if (settings.noiseReductionEnabled) {
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF
                }
            )
        }
        if (cameraSupport.edgeEnhancement) {
            requestBuilder.set(
                CaptureRequest.EDGE_MODE,
                if (settings.edgeEnhancementEnabled) {
                    CaptureRequest.EDGE_MODE_FAST
                } else {
                    CaptureRequest.EDGE_MODE_OFF
                }
            )
        }
        if (cameraSupport.hotPixelCorrection) {
            requestBuilder.set(
                CaptureRequest.HOT_PIXEL_MODE,
                if (settings.hotPixelCorrectionEnabled) {
                    CaptureRequest.HOT_PIXEL_MODE_FAST
                } else {
                    CaptureRequest.HOT_PIXEL_MODE_OFF
                }
            )
        }
    }

    private fun detectCameraSupport(characteristics: CameraCharacteristics): CameraControlSupport {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val focusMax = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
        val noiseModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?: intArrayOf()
        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?: intArrayOf()
        val hotPixelModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
            ?: intArrayOf()

        return CameraControlSupport(
            manualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            raw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            manualFocus = focusMax > 0.0f,
            noiseReduction = noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) &&
                noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST),
            edgeEnhancement = edgeModes.contains(CaptureRequest.EDGE_MODE_OFF) &&
                edgeModes.contains(CaptureRequest.EDGE_MODE_FAST),
            hotPixelCorrection = hotPixelModes.contains(CaptureRequest.HOT_PIXEL_MODE_OFF) &&
                hotPixelModes.contains(CaptureRequest.HOT_PIXEL_MODE_FAST)
        )
    }

    private fun detectControlRanges(characteristics: CameraCharacteristics): CameraControlRanges {
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1_000_000L, 100_000_000L)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(50, 3_200)
        val focusMax = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

        return CameraControlRanges(
            exposureTimeNs = exposureRange.lower..exposureRange.upper,
            iso = isoRange.lower..isoRange.upper,
            focusDistanceDiopters = 0.0f..max(0.0f, focusMax)
        )
    }

    private fun chooseRawSize(): Size {
        val streamMap = cameraCharacteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )
        val rawSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
        return rawSizes
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(4000, 3000)
    }

    private fun trySavePendingDngLocked() {
        val image = pendingRawImage ?: return
        val result = pendingCaptureResult ?: return

        pendingRawImage = null
        pendingCaptureResult = null
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
            saveDng(fileName, image, result)
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

    private fun saveDng(
        fileName: String,
        image: Image,
        captureResult: TotalCaptureResult
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, AppConstants.MEDIASTORE_DNG_DIRECTORY)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("MediaStore insert returned null")

            contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output) { "Could not open output stream" }
                DngCreator(cameraCharacteristics, captureResult).use { dngCreator ->
                    dngCreator.writeImage(output, image)
                }
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            val dir = File(getExternalFilesDir(null), AppConstants.LEGACY_DNG_DIRECTORY)
            dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { output ->
                DngCreator(cameraCharacteristics, captureResult).use { dngCreator ->
                    dngCreator.writeImage(output, image)
                }
            }
        }
    }

    private fun closeCamera() {
        updateCaptureReady(false)

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null

        previewSurface?.release()
        previewSurface = null

        rawImageReader?.close()
        rawImageReader = null

        synchronized(rawLock) {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingCaptureResult = null
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return

        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
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
