package org.fungalsentinel.app

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import kotlin.math.max
import kotlin.math.min

object RawProfileExtractor {
    fun extract(
        image: Image,
        result: TotalCaptureResult,
        characteristics: CameraCharacteristics,
        cameraId: String,
        fixedXRoi: IntRange? = null
    ): SpectralProfile {
        require(image.format == ImageFormat.RAW_SENSOR) { "Expected RAW_SENSOR image." }
        val plane = image.planes.single()
        val width = image.width
        val height = image.height
        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val whiteLevel = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: 1023
        val dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val staticBlack = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        fun blackAt(x: Int, y: Int): Double = dynamicBlack?.get((y and 1) * 2 + (x and 1))?.toDouble()
            ?: staticBlack?.getOffsetForIndex(x and 1, y and 1)?.toDouble()
            ?: 0.0
        fun rawAt(x: Int, y: Int): Int {
            val offset = y * plane.rowStride + x * plane.pixelStride
            return plane.buffer.getShort(offset).toInt() and 0xffff
        }

        val roi = fixedXRoi?.also { validateFixedXRoi(it, width) } ?: run {
            // A sampled high percentile projection locates the strip without allocating a full RGB frame.
            val xProjection = DoubleArray(width)
            val sampled = DoubleArray((height + 3) / 4)
            for (x in 0 until width) {
                var count = 0
                var y = x and 1
                while (y < height) {
                    if ((y and 3) == (x and 3)) sampled[count++] = max(0.0, rawAt(x, y) - blackAt(x, y))
                    y++
                }
                xProjection[x] = if (count > 0) SpectralAlgorithms.percentile(sampled.copyOf(count), 99.5) else 0.0
            }
            val window = max(11, (width / 300) * 2 + 1)
            val smooth = SpectralAlgorithms.movingAverage(xProjection, window)
            val baseline = SpectralAlgorithms.percentile(smooth, 20.0)
            val peak = smooth.maxOrNull() ?: baseline
            val threshold = baseline + 0.35 * (peak - baseline)
            var bestStart = -1
            var bestEnd = -1
            var start = -1
            for (x in 0..width) {
                val active = x < width && smooth[x] > threshold
                if (active && start < 0) start = x
                if (!active && start >= 0) {
                    if (bestStart < 0 || x - start > bestEnd - bestStart) {
                        bestStart = start
                        bestEnd = x
                    }
                    start = -1
                }
            }
            if (bestStart < 0) {
                bestStart = (width * 0.35).toInt()
                bestEnd = (width * 0.65).toInt()
            }
            val x0 = max(0, bestStart - 80)
            val x1 = min(width, bestEnd + 80)
            require(x1 - x0 >= 4) { "Could not locate the illuminated ROI." }
            x0 until x1
        }
        val x0 = roi.first
        val x1 = roi.last + 1

        val red = DoubleArray(height)
        val green = DoubleArray(height)
        val blue = DoubleArray(height)
        val redValid = BooleanArray(height)
        val greenValid = BooleanArray(height)
        val blueValid = BooleanArray(height)
        var saturated = 0L
        var samples = 0L
        for (y in 0 until height) {
            val sums = DoubleArray(3)
            val counts = IntArray(3)
            for (x in x0 until x1) {
                val black = blackAt(x, y)
                val value = max(0.0, rawAt(x, y) - black)
                val channel = channelAt(cfa, x, y)
                sums[channel] += value
                counts[channel]++
                if (value >= 0.98 * max(1.0, whiteLevel - black)) saturated++
                samples++
            }
            if (counts[0] > 0) {
                red[y] = sums[0] / counts[0]
                redValid[y] = true
            }
            if (counts[1] > 0) {
                green[y] = sums[1] / counts[1]
                greenValid[y] = true
            }
            if (counts[2] > 0) {
                blue[y] = sums[2] / counts[2]
                blueValid[y] = true
            }
        }
        // Fill only rows with no Bayer sample; a measured signal of 0.0 is still valid.
        fillMissingRows(red, redValid)
        fillMissingRows(green, greenValid)
        fillMissingRows(blue, blueValid)

        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        normalizeForExposure(red, exposureTimeNs)
        normalizeForExposure(green, exposureTimeNs)
        normalizeForExposure(blue, exposureTimeNs)

        val blackLevel = dynamicBlack?.map { it.toDouble() }?.average()
            ?: staticBlack?.let { pattern -> (0..1).flatMap { y -> (0..1).map { x -> pattern.getOffsetForIndex(x, y).toDouble() } }.average() }
            ?: 0.0
        val metadata = CaptureMetadata(
            cameraId = cameraId,
            exposureTimeNs = exposureTimeNs,
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            blackLevel = blackLevel,
            whiteLevel = whiteLevel,
            cfaArrangement = cfa,
            width = width,
            height = height
        )
        return SpectralProfile(red, green, blue, roi, saturated.toDouble() / max(1L, samples), metadata)
    }

    fun metadataMatches(reference: CaptureMetadata, candidate: CaptureMetadata): Boolean =
        reference.cameraId == candidate.cameraId &&
            reference.width == candidate.width && reference.height == candidate.height &&
            reference.cfaArrangement == candidate.cfaArrangement &&
            reference.iso == candidate.iso &&
            kotlin.math.abs(reference.focusDistanceDiopters - candidate.focusDistanceDiopters) <= 0.01f

    internal fun validateFixedXRoi(roi: IntRange, width: Int) {
        require(width >= 4 && !roi.isEmpty() && roi.first >= 0 && roi.last < width && roi.last - roi.first + 1 >= 4) {
            "Fixed ROI must contain at least 4 columns and be inside the image."
        }
    }

    internal fun normalizeForExposure(values: DoubleArray, exposureTimeNs: Long) {
        require(exposureTimeNs > 0L) { "RAW capture does not contain a valid exposure time." }
        val exposureSeconds = exposureTimeNs / 1_000_000_000.0
        for (i in values.indices) values[i] /= exposureSeconds
    }

    internal fun fillMissingRows(values: DoubleArray, valid: BooleanArray) {
        require(values.size == valid.size) { "Signal and validity arrays must have the same size." }
        require(valid.any { it }) { "A Bayer channel contains no samples." }
        for (i in values.indices) {
            if (valid[i]) continue
            var lower = i - 1
            while (lower >= 0 && !valid[lower]) lower--
            var upper = i + 1
            while (upper < values.size && !valid[upper]) upper++
            values[i] = when {
                lower >= 0 && upper < values.size -> (values[lower] + values[upper]) / 2.0
                lower >= 0 -> values[lower]
                else -> values[upper]
            }
        }
    }

    /** 0=red, 1=green, 2=blue. */
    private fun channelAt(cfa: Int, x: Int, y: Int): Int {
        val index = (y and 1) * 2 + (x and 1)
        return when (cfa) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> intArrayOf(0, 1, 1, 2)[index]
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 2, 1)[index]
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 2, 0, 1)[index]
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(2, 1, 1, 0)[index]
            else -> error("Unsupported CFA arrangement: $cfa")
        }
    }
}
