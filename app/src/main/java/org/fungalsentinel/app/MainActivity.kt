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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private val cameraId = "0"

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

    private var settingsPanelVisible by mutableStateOf(false)
    private var cameraSupport by mutableStateOf(CameraControlSupport(false, false, false, false, false, false))
    private var controlRanges by mutableStateOf(CameraControlRanges.fallback)
    private var cameraSettings by mutableStateOf(CameraControlSettings.manualDefaults())

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

                    IconButton(
                        onClick = { settingsPanelVisible = !settingsPanelVisible },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.48f))
                    ) {
                        Text("⚙", color = Color.White)
                    }

                    Button(
                        onClick = { captureRawDng() },
                        enabled = cameraSupport.raw,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = Color.DarkGray,
                            disabledContentColor = Color.LightGray
                        )
                    ) {
                        Text(if (cameraSupport.raw) "Save DNG" else "RAW Unsupported")
                    }

                    if (settingsPanelVisible) {
                        CameraSettingsPanel(
                            settings = cameraSettings,
                            ranges = controlRanges,
                            support = cameraSupport,
                            onSettingsChanged = ::updateCameraSettings,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(start = 12.dp, top = 72.dp, end = 12.dp, bottom = 96.dp)
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

    private fun captureRawDng() {
        if (!cameraSupport.raw) {
            showMessage("This device does not expose RAW capture.")
            return
        }

        val camera = cameraDevice ?: run {
            showMessage("Camera is not open.")
            return
        }
        val session = captureSession ?: run {
            showMessage("Capture session is not ready.")
            return
        }
        val rawSurface = rawImageReader?.surface ?: run {
            showMessage("RAW reader is not ready.")
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
                    showMessage("RAW capture failed: ${failure.reason}")
                }
            },
            backgroundHandler
        )
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

        requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
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

        try {
            val fileName = "fungal_sentinel_${System.currentTimeMillis()}.dng"
            saveDng(fileName, image, result)
            showMessage("Saved: $fileName")
        } catch (e: Exception) {
            showMessage("DNG save failed: ${e.message}")
        } finally {
            image.close()
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
}

@androidx.compose.runtime.Composable
private fun CameraSettingsPanel(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    support: CameraControlSupport,
    onSettingsChanged: (CameraControlSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.78f),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Manual controls", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        support.summaryText(),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = settings.manualControlsEnabled && support.canUseManualControls,
                    enabled = support.canUseManualControls,
                    onCheckedChange = { checked ->
                        onSettingsChanged(settings.copy(manualControlsEnabled = checked))
                    }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.22f))

            val controlsEnabled = settings.manualControlsEnabled && support.canUseManualControls

            ExposureSlider(
                settings = settings,
                ranges = ranges,
                enabled = controlsEnabled,
                onSettingsChanged = onSettingsChanged
            )
            IsoSlider(
                settings = settings,
                ranges = ranges,
                enabled = controlsEnabled,
                onSettingsChanged = onSettingsChanged
            )
            FocusSlider(
                settings = settings,
                ranges = ranges,
                enabled = controlsEnabled && support.manualFocus,
                onSettingsChanged = onSettingsChanged
            )
            SettingSwitch(
                label = "Auto white balance",
                checked = settings.autoWhiteBalanceEnabled,
                enabled = controlsEnabled,
                onCheckedChange = { onSettingsChanged(settings.copy(autoWhiteBalanceEnabled = it)) }
            )
            SettingSwitch(
                label = "Noise reduction",
                checked = settings.noiseReductionEnabled,
                enabled = controlsEnabled && support.noiseReduction,
                onCheckedChange = { onSettingsChanged(settings.copy(noiseReductionEnabled = it)) }
            )
            SettingSwitch(
                label = "Edge enhancement",
                checked = settings.edgeEnhancementEnabled,
                enabled = controlsEnabled && support.edgeEnhancement,
                onCheckedChange = { onSettingsChanged(settings.copy(edgeEnhancementEnabled = it)) }
            )
            SettingSwitch(
                label = "Hot pixel correction",
                checked = settings.hotPixelCorrectionEnabled,
                enabled = controlsEnabled && support.hotPixelCorrection,
                onCheckedChange = { onSettingsChanged(settings.copy(hotPixelCorrectionEnabled = it)) }
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun ExposureSlider(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    enabled: Boolean,
    onSettingsChanged: (CameraControlSettings) -> Unit
) {
    val minMs = max(0.1f, ranges.exposureTimeNs.first / 1_000_000f)
    val maxMs = max(minMs, min(30_000f, ranges.exposureTimeNs.last / 1_000_000f))
    val valueMs = (settings.exposureTimeNs / 1_000_000f).coerceIn(minMs, maxMs)

    LabeledSlider(
        label = "Exposure",
        valueText = "${formatFloat(valueMs)} ms",
        value = valueMs,
        valueRange = minMs..maxMs,
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(settings.copy(exposureTimeNs = (it * 1_000_000L).roundToLong()))
        }
    )
}

@androidx.compose.runtime.Composable
private fun IsoSlider(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    enabled: Boolean,
    onSettingsChanged: (CameraControlSettings) -> Unit
) {
    LabeledSlider(
        label = "ISO",
        valueText = settings.iso.toString(),
        value = settings.iso.toFloat(),
        valueRange = ranges.iso.first.toFloat()..ranges.iso.last.toFloat(),
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(settings.copy(iso = it.roundToInt()))
        }
    )
}

@androidx.compose.runtime.Composable
private fun FocusSlider(
    settings: CameraControlSettings,
    ranges: CameraControlRanges,
    enabled: Boolean,
    onSettingsChanged: (CameraControlSettings) -> Unit
) {
    LabeledSlider(
        label = "Focus",
        valueText = "${formatFloat(settings.focusDistanceDiopters)} D",
        value = settings.focusDistanceDiopters,
        valueRange = ranges.focusDistanceDiopters.start..ranges.focusDistanceDiopters.endInclusive,
        enabled = enabled,
        onValueChange = {
            onSettingsChanged(settings.copy(focusDistanceDiopters = it))
        }
    )
}

@androidx.compose.runtime.Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = if (enabled) Color.White else Color.Gray)
            Text(valueText, color = if (enabled) Color.White else Color.Gray)
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled
        )
    }
}

@androidx.compose.runtime.Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = if (enabled) Color.White else Color.Gray)
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun CameraControlSupport.summaryText(): String {
    return "Manual: ${manualSensor.status()}  RAW: ${raw.status()}  Focus: ${manualFocus.status()}"
}

private fun Boolean.status(): String = if (this) "supported" else "unsupported"

private fun formatFloat(value: Float): String {
    return if (value >= 10f) {
        value.roundToInt().toString()
    } else {
        String.format("%.1f", value)
    }
}
