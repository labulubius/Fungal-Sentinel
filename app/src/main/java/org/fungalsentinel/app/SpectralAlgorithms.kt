package org.fungalsentinel.app

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object SpectralAlgorithms {
    data class SpdData(val wavelengthsNm: DoubleArray, val intensity: DoubleArray)

    fun calibrateWavelength(
        profile: SpectralProfile,
        redNm: Double = 622.5,
        greenNm: Double = 522.5,
        blueNm: Double = 462.5
    ): WavelengthCalibration {
        require(profile.size >= 101) { "The spectral profile is too short." }
        val red = findPeak("R", redNm, "red", profile.red)
        val green = findPeak("G", greenNm, "green", profile.green)
        val blue = findPeak("B", blueNm, "blue", profile.blue)
        val slope = (red.centroidPixel - blue.centroidPixel) / (redNm - blueNm)
        require(abs(slope) > 1e-9) { "Red and blue peaks overlap." }
        val intercept = blue.centroidPixel - slope * blueNm
        val predictedGreen = slope * greenNm + intercept
        val errorNm = (green.centroidPixel - predictedGreen) / abs(slope)
        return WavelengthCalibration(slope, intercept, errorNm, listOf(red, green, blue), profile.xRoi)
    }

    fun calibrateResponse(
        profile: SpectralProfile,
        calibration: WavelengthCalibration,
        spd: SpdData,
        validRange: ClosedFloatingPointRange<Double> = 420.0..680.0
    ): SpectralResponse {
        validateSpd(spd)
        val points = (0 until profile.size).map { pixel ->
            val wl = calibration.pixelToWavelength(pixel.toDouble())
            val source = interpolate(spd.wavelengthsNm, spd.intensity, wl)
            doubleArrayOf(wl, source, profile.red[pixel], profile.green[pixel], profile.blue[pixel])
        }.sortedBy { it[0] }
        val maxSpd = points.maxOf { it[1] }
        require(maxSpd > 0.0) { "SPD contains no positive intensity." }
        val wl = DoubleArray(points.size)
        val channels = Array(3) { DoubleArray(points.size) }
        points.forEachIndexed { i, p ->
            wl[i] = p[0]
            val valid = p[0] in validRange && p[1] > maxSpd * 0.05
            for (channel in 0..2) channels[channel][i] = if (valid) p[channel + 2] / max(p[1], 1e-6) else 0.0
        }
        for (channel in channels.indices) {
            channels[channel] = movingAverage(channels[channel], 21)
            val maximum = channels[channel].maxOrNull() ?: 0.0
            require(maximum > 0.0) { "${SpectralChannel.entries[channel]} response is empty." }
            for (i in channels[channel].indices) {
                channels[channel][i] = if (wl[i] in validRange) max(0.0, channels[channel][i] / maximum) else 0.0
            }
        }
        return SpectralResponse(wl, channels[0], channels[1], channels[2], validRange)
    }

    fun analyzeSample(
        profile: SpectralProfile,
        calibration: WavelengthCalibration,
        response: SpectralResponse,
        fluorophore: Fluorophore
    ): SampleAnalysis {
        require(profile.size > 1) { "The sample profile is empty." }
        val source = when (fluorophore.channel) {
            SpectralChannel.RED -> profile.red
            SpectralChannel.GREEN -> profile.green
            SpectralChannel.BLUE -> profile.blue
        }
        val responseValues = when (fluorophore.channel) {
            SpectralChannel.RED -> response.red
            SpectralChannel.GREEN -> response.green
            SpectralChannel.BLUE -> response.blue
        }
        val baseline = percentile(source, 5.0)
        val sorted = source.indices.map { i ->
            val wl = calibration.pixelToWavelength(i.toDouble())
            val sensitivity = max(0.05, interpolate(response.wavelengthsNm, responseValues, wl))
            wl to max(0.0, source[i] - baseline) / sensitivity
        }.sortedBy { it.first }
        val wavelengths = DoubleArray(sorted.size) { sorted[it].first }
        val intensity = DoubleArray(sorted.size) { sorted[it].second }
        val low = fluorophore.peakWavelengthNm - fluorophore.integrationWidthNm / 2.0
        val high = fluorophore.peakWavelengthNm + fluorophore.integrationWidthNm / 2.0
        var area = 0.0
        var peak = 0.0
        for (i in 1 until wavelengths.size) {
            if (wavelengths[i - 1] >= low && wavelengths[i] <= high) {
                area += (wavelengths[i] - wavelengths[i - 1]) * (intensity[i] + intensity[i - 1]) / 2.0
                peak = max(peak, max(intensity[i], intensity[i - 1]))
            }
        }
        require(area.isFinite() && area >= 0.0) { "The integrated signal is invalid." }
        return SampleAnalysis(wavelengths, intensity, area, peak, fluorophore)
    }

    fun calculateConcentration(
        standards: List<StandardMeasurement>,
        sampleArea: Double
    ): ConcentrationResult {
        require(standards.size >= 2) { "At least two standards are required." }
        require(standards.map { it.concentration }.distinct().size >= 2) { "Standard concentrations must differ." }
        require(standards.all { it.concentration.isFinite() && it.area.isFinite() }) { "Standards contain invalid values." }
        val meanX = standards.map { it.concentration }.average()
        val meanY = standards.map { it.area }.average()
        val sxx = standards.sumOf { (it.concentration - meanX) * (it.concentration - meanX) }
        val sxy = standards.sumOf { (it.concentration - meanX) * (it.area - meanY) }
        val slope = sxy / sxx
        require(slope.isFinite() && abs(slope) > 1e-12) { "The standard curve has zero slope." }
        val intercept = meanY - slope * meanX
        val residual = standards.sumOf {
            val delta = it.area - (slope * it.concentration + intercept)
            delta * delta
        }
        val total = standards.sumOf {
            val delta = it.area - meanY
            delta * delta
        }
        val rSquared = if (total <= 1e-12) 1.0 else (1.0 - residual / total).coerceIn(0.0, 1.0)
        val predicted = (sampleArea - intercept) / slope
        val range = standards.minOf { it.concentration }..standards.maxOf { it.concentration }
        return ConcentrationResult(slope, intercept, rSquared, predicted, predicted !in range)
    }

    fun parseSpdCsv(text: String): SpdData {
        val rows = text.lineSequence().mapNotNull { line ->
            val columns = line.trim().split(',', ';', '\t').map { it.trim() }
            if (columns.size < 2) null else {
                val x = columns[0].toDoubleOrNull()
                val y = columns[1].toDoubleOrNull()
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) null else x to y
            }
        }.sortedBy { it.first }.toList()
        require(rows.size >= 2) { "SPD CSV must contain at least two numeric rows." }
        val unique = rows.distinctBy { it.first }
        val result = SpdData(
            DoubleArray(unique.size) { unique[it].first },
            DoubleArray(unique.size) { unique[it].second }
        )
        validateSpd(result)
        return result
    }

    internal fun movingAverage(values: DoubleArray, requestedWindow: Int): DoubleArray {
        if (values.isEmpty() || requestedWindow <= 1) return values.copyOf()
        val window = if (requestedWindow % 2 == 0) requestedWindow + 1 else requestedWindow
        val half = window / 2
        return DoubleArray(values.size) { i ->
            var sum = 0.0
            for (j in i - half..i + half) sum += values[j.coerceIn(values.indices)]
            sum / window
        }
    }

    internal fun percentile(values: DoubleArray, percentage: Double): Double {
        require(values.isNotEmpty())
        val sorted = values.copyOf().also { it.sort() }
        val position = percentage.coerceIn(0.0, 100.0) / 100.0 * (sorted.size - 1)
        val lower = floor(position).toInt()
        val fraction = position - lower
        return if (lower == sorted.lastIndex) sorted[lower] else sorted[lower] * (1.0 - fraction) + sorted[lower + 1] * fraction
    }

    private fun findPeak(name: String, wavelength: Double, channel: String, profile: DoubleArray): SpectralPeak {
        val smooth = movingAverage(profile, 11)
        val lo = 50
        val hi = smooth.size - 50
        require(hi > lo) { "Profile is too short for $name peak detection." }
        val baseline = percentile(smooth.copyOfRange(lo, hi), 10.0)
        val maximumPixel = (lo until hi).maxBy { smooth[it] - baseline }
        val height = max(0.0, smooth[maximumPixel] - baseline)
        require(height > 0.0) { "No positive $name peak was found." }
        val halfHeight = height / 2.0
        var left = maximumPixel
        var right = maximumPixel
        while (left > lo && smooth[left] - baseline > halfHeight) left--
        while (right < hi - 1 && smooth[right] - baseline > halfHeight) right++
        val centroidLo = max(lo, left - 45)
        val centroidHi = min(hi, right + 46)
        var weightedPixels = 0.0
        var weight = 0.0
        for (i in centroidLo until centroidHi) {
            val value = max(0.0, profile[i] - baseline)
            weightedPixels += i * value
            weight += value
        }
        require(weight > 0.0) { "$name peak centroid failed." }
        return SpectralPeak(name, wavelength, channel, weightedPixels / weight, maximumPixel, height)
    }

    private fun interpolate(x: DoubleArray, y: DoubleArray, target: Double): Double {
        require(x.size == y.size && x.isNotEmpty())
        if (target < x.first() || target > x.last()) return 0.0
        val found = x.binarySearch(target)
        if (found >= 0) return y[found]
        val upper = -found - 1
        if (upper <= 0 || upper >= x.size) return 0.0
        val fraction = (target - x[upper - 1]) / (x[upper] - x[upper - 1])
        return y[upper - 1] + fraction * (y[upper] - y[upper - 1])
    }

    private fun validateSpd(spd: SpdData) {
        require(spd.wavelengthsNm.size == spd.intensity.size && spd.wavelengthsNm.size >= 2)
        require((1 until spd.wavelengthsNm.size).all { spd.wavelengthsNm[it] > spd.wavelengthsNm[it - 1] }) {
            "SPD wavelengths must be strictly increasing."
        }
        require(spd.intensity.any { it > 0.0 }) { "SPD contains no positive intensity." }
    }
}
