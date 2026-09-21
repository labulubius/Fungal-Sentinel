package org.fungalsentinel.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
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
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.max

class CameraController(
    context: Context,
    private val onCaptureReadyChanged: (Boolean) -> Unit,
    private val onRawCaptured: (RawCapture) -> Unit,
    private val onError: (String) -> Unit,
    private val onExposureStatusChanged: (ExposureStatus) -> Unit = {}
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    val cameraId: String = selectCameraId(cameraManager)
        ?: error("No camera is available on this device.")

    val characteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
    val support: CameraControlSupport = detectCameraSupport(characteristics)
    val ranges: CameraControlRanges = detectControlRanges(characteristics)
    private val autoExposureFpsRange: Range<Int>? = PreviewFrameRateSelector.choose(
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
            .orEmpty()
    )?.let { Range(it.first, it.last) }

    @Volatile
    var settings: CameraControlSettings = CameraControlSettings.defaults().clampedTo(ranges)
        private set

    @Volatile private var autoExposureState = AutoExposureState()
    private val controlLock = Any()
    private var controlRevision = 0L
    private var autoExposureLockTimedOut = false
    private var autoExposureLockTimeout: Runnable? = null
    private var autoExposureLockAttempt = 0L

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private var cameraDevice: CameraDevice? = null
    private var opening = false
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    @Volatile private var previewBufferSize: Size? = null
    private var rawImageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val rawLock = Any()
    private var pendingRawImage: Image? = null
    private var pendingCaptureResult: TotalCaptureResult? = null
    private var pendingCaptureToken: Long? = null
    private var pendingCaptureGeneration: Long? = null
    private var pendingRawTimeout: Runnable? = null
    private var activeRawDispatches = 0
    private val readersPendingClose = mutableListOf<ImageReader>()
    @Volatile private var lifecycleGeneration: Long = 0

    fun start() {
        val existing = backgroundThread
        if (existing?.isAlive == true) return
        backgroundThread = null
        backgroundHandler = null
        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    fun stop() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        thread.join(1_000)
        if (thread.isAlive) {
            thread.quit()
            thread.join(1_000)
        }
        // Retain ownership of a thread that has not stopped so start() cannot create a second one.
        if (!thread.isAlive && backgroundThread === thread) {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    @SuppressLint("MissingPermission")
    fun open(view: TextureView) {
        val generation = synchronized(lifecycleLock) {
            if (cameraDevice != null || opening) return
            opening = true
            ++lifecycleGeneration
        }
        try {
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
                        val current = invalidateCameraResources(generation, camera = camera)
                        if (!current) camera.close()
                        if (current) onError("Camera disconnected. Return to the app or reopen the preview to retry.")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        val current = invalidateCameraResources(generation, camera = camera)
                        if (!current) camera.close()
                        if (current) onError("Camera open failed: $error")
                    }
                },
                backgroundHandler
            )
        } catch (error: Exception) {
            val current = synchronized(lifecycleLock) {
                if (generation != lifecycleGeneration) {
                    false
                } else {
                    lifecycleGeneration++
                    opening = false
                    true
                }
            }
            if (current) {
                onCaptureReadyChanged(false)
                resetAutoExposureLock()
                onError("Camera could not be opened: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun close() {
        val (closingGeneration, resources) = synchronized(lifecycleLock) {
            val generation = lifecycleGeneration
            lifecycleGeneration++
            opening = false
            val current = listOf(captureSession, cameraDevice, previewSurface, rawImageReader)
            captureSession = null
            cameraDevice = null
            previewSurface = null
            previewBufferSize = null
            rawImageReader = null
            generation to current
        }
        onCaptureReadyChanged(false)
        resetAutoExposureLock()
        (resources[0] as? CameraCaptureSession)?.close()
        (resources[1] as? CameraDevice)?.close()
        (resources[2] as? Surface)?.release()
        (resources[3] as? ImageReader)?.let(::closeReaderWhenSafe)
        clearPendingRawForGeneration(closingGeneration)
    }

    private fun invalidateCameraResources(
        generation: Long,
        camera: CameraDevice? = null,
        failedSession: CameraCaptureSession? = null
    ): Boolean {
        var currentSession: CameraCaptureSession? = null
        var currentCamera: CameraDevice? = null
        var currentSurface: Surface? = null
        var currentReader: ImageReader? = null
        val current = synchronized(lifecycleLock) {
            if (generation != lifecycleGeneration) {
                false
            } else {
                lifecycleGeneration++
                opening = false
                currentSession = captureSession
                currentCamera = cameraDevice
                currentSurface = previewSurface
                currentReader = rawImageReader
                captureSession = null
                cameraDevice = null
                previewSurface = null
                previewBufferSize = null
                rawImageReader = null
                true
            }
        }
        if (!current) return false

        onCaptureReadyChanged(false)
        resetAutoExposureLock()
        clearPendingRawForGeneration(generation)
        failedSession?.close()
        if (currentSession !== failedSession) currentSession?.close()
        camera?.close()
        if (currentCamera !== camera) currentCamera?.close()
        currentSurface?.release()
        currentReader?.let(::closeReaderWhenSafe)
        return true
    }

    fun updateSettings(next: CameraControlSettings): CameraControlSettings {
        val current = synchronized(controlLock) {
            val previous = settings
            settings = next.clampedTo(ranges)
            if (previous.manualControlsEnabled != settings.manualControlsEnabled ||
                previous.meterThenLockEnabled != settings.meterThenLockEnabled
            ) {
                autoExposureState = autoExposureState.reset()
                autoExposureLockTimedOut = false
                cancelAutoExposureLockTimeoutLocked()
            }
            controlRevision++
            settings
        }
        notifyExposureStatus()
        applyRepeatingRequest()
        return current
    }

    fun updatePreviewTransform(view: TextureView) {
        val bufferSize = previewBufferSize ?: return
        if (view.width <= 0 || view.height <= 0) return
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        val displayRotation = displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0)
        val matrix = Matrix().apply {
            setValues(
                PreviewTransform.matrixValues(
                    viewWidth = view.width,
                    viewHeight = view.height,
                    bufferWidth = bufferSize.width,
                    bufferHeight = bufferSize.height,
                    sensorOrientationDegrees = sensorOrientation,
                    displayRotationDegrees = displayRotation,
                    frontFacing = frontFacing
                )
            )
        }
        view.setTransform(matrix)
    }

    fun captureRaw(captureToken: Long): String? {
        if (!support.raw) return "This device does not expose RAW capture."
        val exposureSnapshot = controlSnapshot()
        if (!exposureSnapshot.settings.manualControlsEnabled &&
            exposureSnapshot.settings.meterThenLockEnabled && support.autoExposureLock &&
            !exposureSnapshot.autoExposureState.locked
        ) {
            return if (exposureSnapshot.autoExposureLockTimedOut) {
                "Auto exposure lock timed out. Toggle meter-then-lock off and on to retry."
            } else {
                "Wait for auto exposure to finish locking."
            }
        }
        val resources = synchronized(lifecycleLock) {
            val camera = cameraDevice ?: return "Camera is not open."
            val session = captureSession ?: return "Capture session is not ready."
            val rawSurface = rawImageReader?.surface ?: return "RAW reader is not ready."
            val generation = lifecycleGeneration
            synchronized(rawLock) {
                if (pendingCaptureToken != null) return "A RAW capture is already in progress."
                pendingRawImage?.close()
                pendingRawImage = null
                pendingCaptureResult = null
                pendingCaptureToken = captureToken
                pendingCaptureGeneration = generation
                scheduleRawTimeoutLocked(
                    captureToken,
                    generation,
                    rawCaptureTimeoutMs(exposureSnapshot.settings)
                )
            }
            RawCaptureResources(camera, session, rawSurface, generation)
        }
        val camera = resources.camera
        val session = resources.session
        val rawSurface = resources.surface
        val generation = resources.generation
        return try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            requestBuilder.addTarget(rawSurface)
            val controlSnapshot = controlSnapshot()
            applyCameraSettings(
                requestBuilder,
                controlSnapshot.settings,
                controlSnapshot.autoExposureState,
                constrainAutoExposureFps = false
            )
            session.capture(
                requestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        if (generation != lifecycleGeneration) return
                        synchronized(rawLock) {
                            if (pendingCaptureToken == captureToken &&
                                pendingCaptureGeneration == generation
                            ) pendingCaptureResult = result
                        }
                        dispatchCompletedRawIfReady()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        if (generation == lifecycleGeneration && clearPendingRaw(captureToken, generation)) {
                            onError("RAW capture failed: ${failure.reason}")
                        }
                    }
                },
                backgroundHandler
            )
            null
        } catch (error: Exception) {
            clearPendingRaw(captureToken, generation)
            "RAW capture could not be submitted: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun startPreview(view: TextureView, generation: Long) {
        val camera = synchronized(lifecycleLock) {
            if (generation != lifecycleGeneration) return
            cameraDevice ?: return
        }
        val surfaceTexture = view.surfaceTexture ?: return
        onCaptureReadyChanged(false)

        val selectedPreviewSize = choosePreviewSize(view)
        surfaceTexture.setDefaultBufferSize(selectedPreviewSize.width, selectedPreviewSize.height)
        val newPreviewSurface = Surface(surfaceTexture)
        val newRawReader = if (support.raw) {
            val rawSize = chooseRawSize()
            ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
                setOnImageAvailableListener(
                    listener@{ reader ->
                        val image = try {
                            reader.acquireNextImage()
                        } catch (_: IllegalStateException) {
                            return@listener
                        } ?: return@listener
                        val acceptedImage = synchronized(lifecycleLock) {
                            if (generation != lifecycleGeneration || rawImageReader !== reader) {
                                false
                            } else {
                                synchronized(rawLock) {
                                    if (pendingCaptureToken == null) {
                                        false
                                    } else {
                                        pendingRawImage?.close()
                                        pendingRawImage = image
                                        true
                                    }
                                }
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
                previewBufferSize = selectedPreviewSize
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
        view.post {
            if (generation == lifecycleGeneration && view.surfaceTexture === surfaceTexture) {
                updatePreviewTransform(view)
            }
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
                    val current = invalidateCameraResources(generation, failedSession = session)
                    if (!current) session.close()
                    if (current) onError("Preview session configuration failed.")
                }
        }
        try {
            synchronized(lifecycleLock) {
                if (generation != lifecycleGeneration || cameraDevice !== camera) return
                camera.createCaptureSession(surfaces, callback, backgroundHandler)
            }
        } catch (error: Exception) {
            val current = invalidateCameraResources(generation)
            if (current) {
                onError(
                    "Preview session configuration failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    private fun applyRepeatingRequest() {
        val handler = backgroundHandler ?: return
        if (Looper.myLooper() != handler.looper) {
            handler.post(::applyRepeatingRequest)
            return
        }
        val (camera, session, surface, generation) = synchronized(lifecycleLock) {
            val currentCamera = cameraDevice ?: return
            val currentSession = captureSession ?: return
            val currentSurface = previewSurface ?: return
            PreviewResources(currentCamera, currentSession, currentSurface, lifecycleGeneration)
        }
        val snapshot = controlSnapshot()
        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            applyCameraSettings(
                requestBuilder,
                snapshot.settings,
                snapshot.autoExposureState,
                constrainAutoExposureFps = true
            )
            val request = requestBuilder.build()
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    val stateChanged = synchronized(controlLock) {
                        if (generation != lifecycleGeneration || snapshot.revision != controlRevision) {
                            return@synchronized false
                        }
                        val nextState = when {
                            !autoExposureLockTimedOut && snapshot.autoExposureState.lockRequested &&
                                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ->
                                autoExposureState.onLockConfirmed(settings, support)
                            !autoExposureLockTimedOut && !snapshot.autoExposureState.lockRequested &&
                                (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) ->
                                autoExposureState.onAeStable(settings, support)
                            else -> autoExposureState
                        }
                        if (nextState == autoExposureState) return@synchronized false
                        autoExposureState = nextState
                        controlRevision++
                        true
                    }
                    if (stateChanged) {
                        notifyExposureStatus()
                        applyRepeatingRequest()
                    }
                }
            }
            synchronized(lifecycleLock) {
                if (generation != lifecycleGeneration || captureSession !== session || previewSurface !== surface) return
                session.setRepeatingRequest(request, callback, handler)
            }
            notifyExposureStatus()
            updateAutoExposureLockTimeout(generation)
        } catch (error: Exception) {
            val current = synchronized(lifecycleLock) {
                generation == lifecycleGeneration && captureSession === session
            }
            if (current) onError("Preview request failed: ${error.message}")
        }
    }

    private fun applyCameraSettings(
        requestBuilder: CaptureRequest.Builder,
        currentSettings: CameraControlSettings,
        currentAutoExposureState: AutoExposureState,
        constrainAutoExposureFps: Boolean
    ) {
        val manualExposureEnabled = currentSettings.manualControlsEnabled && support.canUseManualControls
        // CONTROL_MODE stays AUTO so AF/AWB/post-processing remain independently controllable.
        requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (manualExposureEnabled) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentSettings.exposureTimeNs)
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentSettings.iso)
        } else {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (constrainAutoExposureFps) {
                autoExposureFpsRange?.let {
                    requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
            }
        }
        if (support.autoExposureLock) {
            requestBuilder.set(
                CaptureRequest.CONTROL_AE_LOCK,
                !manualExposureEnabled && currentSettings.meterThenLockEnabled &&
                    currentAutoExposureState.lockRequested
            )
        }

        if (support.manualFocus) {
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentSettings.focusDistanceDiopters)
        } else {
            safeAutoFocusMode()?.let { requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, it) }
        }
        requestBuilder.set(
            CaptureRequest.CONTROL_AWB_MODE,
            if (currentSettings.autoWhiteBalanceEnabled) CaptureRequest.CONTROL_AWB_MODE_AUTO
            else CaptureRequest.CONTROL_AWB_MODE_OFF
        )
        if (support.noiseReduction) {
            requestBuilder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                if (currentSettings.noiseReductionEnabled) CaptureRequest.NOISE_REDUCTION_MODE_FAST
                else CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
        }
        if (support.edgeEnhancement) {
            requestBuilder.set(
                CaptureRequest.EDGE_MODE,
                if (currentSettings.edgeEnhancementEnabled) CaptureRequest.EDGE_MODE_FAST
                else CaptureRequest.EDGE_MODE_OFF
            )
        }
        if (support.hotPixelCorrection) {
            requestBuilder.set(
                CaptureRequest.HOT_PIXEL_MODE,
                if (currentSettings.hotPixelCorrectionEnabled) CaptureRequest.HOT_PIXEL_MODE_FAST
                else CaptureRequest.HOT_PIXEL_MODE_OFF
            )
        }
    }

    private fun safeAutoFocusMode(): Int? {
        val available = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        return listOf(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_OFF
        ).firstOrNull(available::contains)
    }

    private fun updateAutoExposureLockTimeout(generation: Long) {
        synchronized(controlLock) {
            val waitingForLock = !settings.manualControlsEnabled &&
                settings.meterThenLockEnabled && support.autoExposureLock &&
                !autoExposureState.locked
            if (!waitingForLock) {
                cancelAutoExposureLockTimeoutLocked()
                return
            }
            if (autoExposureLockTimedOut || autoExposureLockTimeout != null) return

            val attempt = ++autoExposureLockAttempt
            val timeout = Runnable {
                val didTimeOut = synchronized(controlLock) {
                    if (attempt != autoExposureLockAttempt || generation != lifecycleGeneration ||
                        settings.manualControlsEnabled || !settings.meterThenLockEnabled ||
                        autoExposureState.locked
                    ) {
                        false
                    } else {
                        autoExposureLockTimeout = null
                        autoExposureLockTimedOut = true
                        autoExposureState = autoExposureState.reset()
                        controlRevision++
                        true
                    }
                }
                if (didTimeOut) {
                    notifyExposureStatus()
                    applyRepeatingRequest()
                    onError(
                        "Auto exposure lock timed out after 8 seconds. " +
                            "Toggle meter-then-lock off and on to retry."
                    )
                }
            }
            autoExposureLockTimeout = timeout
            timeoutHandler.postDelayed(timeout, AUTO_EXPOSURE_LOCK_TIMEOUT_MS)
        }
    }

    private fun cancelAutoExposureLockTimeoutLocked() {
        autoExposureLockTimeout?.let(timeoutHandler::removeCallbacks)
        autoExposureLockTimeout = null
        autoExposureLockAttempt++
    }

    private fun resetAutoExposureLock() {
        synchronized(controlLock) {
            autoExposureState = autoExposureState.reset()
            autoExposureLockTimedOut = false
            cancelAutoExposureLockTimeoutLocked()
            // Invalidates convergence callbacks from requests issued before a reset.
            controlRevision++
        }
        notifyExposureStatus()
    }

    private fun controlSnapshot(): ControlSnapshot = synchronized(controlLock) {
        ControlSnapshot(settings, autoExposureState, controlRevision, autoExposureLockTimedOut)
    }

    private fun notifyExposureStatus() {
        val snapshot = controlSnapshot()
        onExposureStatusChanged(snapshot.autoExposureState.status(snapshot.settings, support))
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
            pendingCaptureGeneration = null
            cancelPendingRawTimeoutLocked()
            activeRawDispatches++
            Triple(image, result, token)
        } ?: return
        val capture = RawCapture(
            image = completed.first,
            result = completed.second,
            cameraCharacteristics = characteristics,
            cameraId = cameraId,
            captureToken = completed.third,
            onClosed = ::releaseRawDispatch
        )
        try {
            onRawCaptured(capture)
        } catch (error: Exception) {
            capture.close()
            onError("RAW capture could not be handed off: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun scheduleRawTimeoutLocked(captureToken: Long, generation: Long, timeoutMs: Long) {
        cancelPendingRawTimeoutLocked()
        val timeout = Runnable {
            if (clearPendingRaw(captureToken, generation)) {
                onError("RAW capture timed out after ${timeoutMs / 1_000} seconds. Please try again.")
            }
        }
        pendingRawTimeout = timeout
        timeoutHandler.postDelayed(timeout, timeoutMs)
    }

    private fun cancelPendingRawTimeoutLocked() {
        pendingRawTimeout?.let(timeoutHandler::removeCallbacks)
        pendingRawTimeout = null
    }

    private fun clearPendingRaw(captureToken: Long, generation: Long): Boolean = synchronized(rawLock) {
        if (pendingCaptureToken != captureToken || pendingCaptureGeneration != generation) {
            return@synchronized false
        }
        pendingRawImage?.close()
        pendingRawImage = null
        pendingCaptureResult = null
        pendingCaptureToken = null
        pendingCaptureGeneration = null
        cancelPendingRawTimeoutLocked()
        true
    }

    private fun clearPendingRawForGeneration(generation: Long): Boolean = synchronized(rawLock) {
        if (pendingCaptureGeneration != generation) return@synchronized false
        pendingRawImage?.close()
        pendingRawImage = null
        pendingCaptureResult = null
        pendingCaptureToken = null
        pendingCaptureGeneration = null
        cancelPendingRawTimeoutLocked()
        true
    }

    private fun releaseRawDispatch() {
        val readersToClose = synchronized(rawLock) {
            activeRawDispatches--
            check(activeRawDispatches >= 0) { "RAW dispatch lease count became negative." }
            if (activeRawDispatches == 0) {
                readersPendingClose.toList().also { readersPendingClose.clear() }
            } else {
                emptyList()
            }
        }
        readersToClose.forEach(ImageReader::close)
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

    private fun choosePreviewSize(view: TextureView): Size {
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.toList()
            .orEmpty()
        if (sizes.isEmpty()) return Size(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))

        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        val rotation = PreviewTransform.relativeRotationDegrees(
            sensorOrientation,
            displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0),
            frontFacing
        )
        val targetWidth = if (rotation == 90 || rotation == 270) view.height else view.width
        val targetHeight = if (rotation == 90 || rotation == 270) view.width else view.height
        val targetAspect = targetWidth.toDouble() / targetHeight.coerceAtLeast(1)
        val targetArea = targetWidth.toLong() * targetHeight.toLong()
        val practicalSizes = sizes.filter { it.width.toLong() * it.height <= MAX_PREVIEW_PIXELS }
            .ifEmpty { sizes }

        return practicalSizes.minWithOrNull(
            compareBy<Size> {
                abs(it.width.toDouble() / it.height - targetAspect)
            }.thenBy {
                abs(it.width.toLong() * it.height - targetArea)
            }
        ) ?: sizes.first()
    }

    private fun chooseRawSize(): Size {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: Size(4000, 3000)
    }

    private fun displayRotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun detectCameraSupport(characteristics: CameraCharacteristics): CameraControlSupport {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val focusMax = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val noiseModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val hotPixelModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: intArrayOf()
        return CameraControlSupport(
            manualSensor = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            raw = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            manualFocus = focusMax > 0.0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF),
            noiseReduction = noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) &&
                noiseModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST),
            edgeEnhancement = edgeModes.contains(CaptureRequest.EDGE_MODE_OFF) &&
                edgeModes.contains(CaptureRequest.EDGE_MODE_FAST),
            hotPixelCorrection = hotPixelModes.contains(CaptureRequest.HOT_PIXEL_MODE_OFF) &&
                hotPixelModes.contains(CaptureRequest.HOT_PIXEL_MODE_FAST),
            autoExposureLock = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
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

    private data class PreviewResources(
        val camera: CameraDevice,
        val session: CameraCaptureSession,
        val surface: Surface,
        val generation: Long
    )

    private data class RawCaptureResources(
        val camera: CameraDevice,
        val session: CameraCaptureSession,
        val surface: Surface,
        val generation: Long
    )

    private data class ControlSnapshot(
        val settings: CameraControlSettings,
        val autoExposureState: AutoExposureState,
        val revision: Long,
        val autoExposureLockTimedOut: Boolean
    )

    private companion object {
        const val MAX_PREVIEW_PIXELS = 1920L * 1080L
        const val RAW_CAPTURE_TIMEOUT_MS = 15_000L
        const val AUTO_EXPOSURE_LOCK_TIMEOUT_MS = 8_000L

        fun rawCaptureTimeoutMs(settings: CameraControlSettings): Long {
            val exposureMs = if (settings.manualControlsEnabled) settings.exposureTimeNs / 1_000_000L else 0L
            return maxOf(RAW_CAPTURE_TIMEOUT_MS, exposureMs + 5_000L)
        }

        fun selectCameraId(manager: CameraManager): String? {
            val candidates = manager.cameraIdList.mapNotNull { id ->
                runCatching {
                    val characteristics = manager.getCameraCharacteristics(id)
                    val capabilities = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                    ) ?: intArrayOf()
                    val rawSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    CameraCandidate(
                        id = id,
                        backFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK,
                        rawOutput = capabilities.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
                        ) && !rawSizes.isNullOrEmpty(),
                        manualSensor = capabilities.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                        )
                    )
                }.getOrNull()
            }
            return CameraSelection.choose(candidates)
        }
    }
}
