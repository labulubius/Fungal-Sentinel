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
import android.hardware.camera2.CaptureResult
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
    private val onRawCaptured: (Image, TotalCaptureResult, CameraCharacteristics, String, Long) -> Unit,
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

    private val lifecycleLock = Any()
    private var cameraDevice: CameraDevice? = null
    private var opening = false
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawImageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val rawLock = Any()
    private var pendingRawImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var pendingCaptureToken: Long? = null
    private var activeRawDispatches = 0
    private val readersPendingClose = mutableListOf<ImageReader>()
    @Volatile private var lifecycleGeneration: Long = 0

    fun start() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    fun stop() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        thread.join(1_000)
        if (thread.isAlive) thread.quit()
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    fun open(view: TextureView) {
        val generation = synchronized(lifecycleLock) {
            if (cameraDevice != null || opening) return
            opening = true
            ++lifecycleGeneration
        }
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val accepted = synchronized(lifecycleLock) {
                        if (generation != lifecycleGeneration) {
                            false
                        } else {
                            opening = false
                            cameraDevice = camera
                            true
                        }
                    }
                    if (!accepted) {
                        camera.close()
                        return
                    }
                    startPreview(view, generation)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    synchronized(lifecycleLock) {
                        if (generation == lifecycleGeneration) {
                            opening = false
                            if (cameraDevice === camera) cameraDevice = null
                        }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val current = synchronized(lifecycleLock) {
                        if (generation != lifecycleGeneration) {
                            false
                        } else {
                            opening = false
                            if (cameraDevice === camera) cameraDevice = null
                            true
                        }
                    }
                    if (current) onError("Camera open failed: $error")
                }
            },
            backgroundHandler
        )
    }

    fun close() {
        val resources = synchronized(lifecycleLock) {
            lifecycleGeneration++
            opening = false
            val current = listOf(captureSession, cameraDevice, previewSurface, rawImageReader)
            captureSession = null
            cameraDevice = null
            previewSurface = null
            rawImageReader = null
            current
        }
        onCaptureReadyChanged(false)
        (resources[0] as? CameraCaptureSession)?.close()
        (resources[1] as? CameraDevice)?.close()
        (resources[2] as? Surface)?.release()
        (resources[3] as? ImageReader)?.let(::closeReaderWhenSafe)
        synchronized(rawLock) {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingCaptureResult = null
            pendingCaptureToken = null
        }
    }

    fun updateSettings(next: CameraControlSettings): CameraControlSettings {
        settings = next.clampedTo(ranges)
        applyRepeatingRequest()
        return settings
    }

    fun captureRaw(captureToken: Long): String? {
        if (!support.raw) return "This device does not expose RAW capture."
        val camera = cameraDevice ?: return "Camera is not open."
        val session = captureSession ?: return "Capture session is not ready."
        val rawSurface = rawImageReader?.surface ?: return "RAW reader is not ready."

        synchronized(rawLock) {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingCaptureResult = null
            pendingCaptureToken = captureToken
        }

        val generation = lifecycleGeneration
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
                    if (generation != lifecycleGeneration) return
                    synchronized(rawLock) { pendingCaptureResult = result }
                    dispatchCompletedRawIfReady()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    if (generation == lifecycleGeneration) {
                        synchronized(rawLock) {
                            pendingRawImage?.close()
                            pendingRawImage = null
                            pendingCaptureResult = null
                            pendingCaptureToken = null
                        }
                        onError("RAW capture failed: ${failure.reason}")
                    }
                }
            },
            backgroundHandler
        )
        return null
    }

    private fun startPreview(view: TextureView, generation: Long) {
        val camera = synchronized(lifecycleLock) {
            if (generation != lifecycleGeneration) return
            cameraDevice ?: return
        }
        val surfaceTexture = view.surfaceTexture ?: return
        onCaptureReadyChanged(false)

        surfaceTexture.setDefaultBufferSize(view.width, view.height)
        val newPreviewSurface = Surface(surfaceTexture)
        val newRawReader = if (support.raw) {
            val rawSize = chooseRawSize()
            ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                setOnImageAvailableListener(
                    { reader ->
                        val image = reader.acquireNextImage()
                        val acceptedImage = synchronized(lifecycleLock) {
                            if (generation != lifecycleGeneration || rawImageReader !== reader) {
                                false
                            } else {
                                synchronized(rawLock) {
                                    pendingRawImage?.close()
                                    pendingRawImage = image
                                }
                                true
                            }
                        }
                        if (acceptedImage) {
                            dispatchCompletedRawIfReady()
                        } else {
                            image.close()
                        }
                    },
                    backgroundHandler
                )
            }
        } else {
            null
        }

        var previousSurface: Surface? = null
        var previousReader: ImageReader? = null
        val accepted = synchronized(lifecycleLock) {
            if (generation != lifecycleGeneration || cameraDevice !== camera) {
                false
            } else {
                previousSurface = previewSurface
                previousReader = rawImageReader
                previewSurface = newPreviewSurface
                rawImageReader = newRawReader
                true
            }
        }
        previousSurface?.release()
        previousReader?.let(::closeReaderWhenSafe)
        if (!accepted) {
            newPreviewSurface.release()
            newRawReader?.close()
            return
        }

        val surfaces = buildList {
            add(newPreviewSurface)
            newRawReader?.surface?.let(::add)
        }
        val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    val accepted = synchronized(lifecycleLock) {
                        if (generation != lifecycleGeneration) {
                            false
                        } else {
                            captureSession = session
                            true
                        }
                    }
                    if (!accepted) {
                        session.close()
                        return
                    }
                    applyRepeatingRequest()
                    onCaptureReadyChanged(rawImageReader != null)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val current = synchronized(lifecycleLock) { generation == lifecycleGeneration }
                    if (current) onError("Preview session configuration failed.")
                }
        }
        try {
            synchronized(lifecycleLock) {
                if (generation != lifecycleGeneration || cameraDevice !== camera) return
                camera.createCaptureSession(surfaces, callback, backgroundHandler)
            }
        } catch (error: Exception) {
            val current = synchronized(lifecycleLock) { generation == lifecycleGeneration }
            if (current) onError("Preview session configuration failed: ${error.message}")
        }
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
        val manualExposureEnabled = settings.manualControlsEnabled && support.canUseManualControls
        if (manualExposureEnabled) {
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

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
            val token = pendingCaptureToken ?: return@synchronized null
            val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (resultTimestamp != null && image.timestamp != resultTimestamp) {
                if (image.timestamp < resultTimestamp) {
                    image.close()
                    pendingRawImage = null
                } else {
                    pendingCaptureResult = null
                }
                return@synchronized null
            }
            pendingRawImage = null
            pendingCaptureResult = null
            pendingCaptureToken = null
            activeRawDispatches++
            Triple(image, result, token)
        } ?: return
        try {
            onRawCaptured(completed.first, completed.second, characteristics, cameraId, completed.third)
        } finally {
            val readersToClose = synchronized(rawLock) {
                activeRawDispatches--
                if (activeRawDispatches == 0) {
                    readersPendingClose.toList().also { readersPendingClose.clear() }
                } else {
                    emptyList()
                }
            }
            readersToClose.forEach(ImageReader::close)
        }
    }

    private fun closeReaderWhenSafe(reader: ImageReader) {
        val closeNow = synchronized(rawLock) {
            if (activeRawDispatches == 0) true else {
                readersPendingClose += reader
                false
            }
        }
        if (closeNow) reader.close()
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
