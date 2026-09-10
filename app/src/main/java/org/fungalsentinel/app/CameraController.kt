package org.fungalsentinel.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.max

class CameraController(
    context: Context,
    private val onCaptureReadyChanged: (Boolean) -> Unit,
    private val onRawCaptured: (Image, TotalCaptureResult, CameraCharacteristics, String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    val cameraId: String = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: error("No back-facing camera is available.")

    val characteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
    val support: CameraControlSupport = detectCameraSupport(characteristics)
    val ranges: CameraControlRanges = detectControlRanges(characteristics)

    var settings: CameraControlSettings = CameraControlSettings.manualDefaults()
        .copy(manualControlsEnabled = support.canUseManualControls)
        .clampedTo(ranges)
        private set

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawImageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val rawLock = Any()
    private var pendingRawImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null

    fun start() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    fun stop() {
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    fun open(view: TextureView) {
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
                    onError("Camera open failed: $error")
                }
            },
            backgroundHandler
        )
    }

    fun close() {
        onCaptureReadyChanged(false)
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

    fun updateSettings(next: CameraControlSettings): CameraControlSettings {
        settings = next.clampedTo(ranges)
        applyRepeatingRequest()
        return settings
    }

    fun captureRaw(): String? {
        if (!support.raw) return "This device does not expose RAW capture."
        val camera = cameraDevice ?: return "Camera is not open."
        val session = captureSession ?: return "Capture session is not ready."
        val rawSurface = rawImageReader?.surface ?: return "RAW reader is not ready."

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
                    synchronized(rawLock) { pendingCaptureResult = result }
                    dispatchCompletedRawIfReady()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    onError("RAW capture failed: ${failure.reason}")
                }
            },
            backgroundHandler
        )
        return null
    }

    private fun startPreview(view: TextureView) {
        val camera = cameraDevice ?: return
        val surfaceTexture = view.surfaceTexture ?: return
        onCaptureReadyChanged(false)

        surfaceTexture.setDefaultBufferSize(view.width, view.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)

        rawImageReader?.close()
        rawImageReader = if (support.raw) {
            val rawSize = chooseRawSize()
            ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireNextImage()
                        synchronized(rawLock) {
                            pendingRawImage?.close()
                            pendingRawImage = image
                        }
                        dispatchCompletedRawIfReady()
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
                    onCaptureReadyChanged(rawImageReader != null)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError("Preview session configuration failed.")
                }
            },
            backgroundHandler
        )
    }

    private fun applyRepeatingRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(surface)
        applyCameraSettings(requestBuilder)
        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
    }

    private fun applyCameraSettings(requestBuilder: CaptureRequest.Builder) {
        val manualEnabled = settings.manualControlsEnabled && support.canUseManualControls
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

        if (support.manualFocus) {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistanceDiopters)
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        requestBuilder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            if (settings.autoWhiteBalanceEnabled) CaptureRequest.CONTROL_AWB_MODE_AUTO
            else CaptureRequest.CONTROL_AWB_MODE_OFF
        )
        if (support.noiseReduction) {
            requestBuilder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                if (settings.noiseReductionEnabled) CaptureRequest.NOISE_REDUCTION_MODE_FAST
                else CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
        }
        if (support.edgeEnhancement) {
            requestBuilder.set(
                CaptureRequest.EDGE_MODE,
                if (settings.edgeEnhancementEnabled) CaptureRequest.EDGE_MODE_FAST
                else CaptureRequest.EDGE_MODE_OFF
            )
        }
        if (support.hotPixelCorrection) {
            requestBuilder.set(
                CaptureRequest.HOT_PIXEL_MODE,
                if (settings.hotPixelCorrectionEnabled) CaptureRequest.HOT_PIXEL_MODE_FAST
                else CaptureRequest.HOT_PIXEL_MODE_OFF
            )
        }
    }

    private fun dispatchCompletedRawIfReady() {
        val completed = synchronized(rawLock) {
            val image = pendingRawImage ?: return@synchronized null
            val result = pendingCaptureResult ?: return@synchronized null
            pendingRawImage = null
            pendingCaptureResult = null
            image to result
        } ?: return
        onRawCaptured(completed.first, completed.second, characteristics, cameraId)
    }

    private fun chooseRawSize(): Size {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(4000, 3000)
    }

    private fun detectCameraSupport(characteristics: CameraCharacteristics): CameraControlSupport {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val focusMax = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
        val noiseModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val hotPixelModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: intArrayOf()
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
}
