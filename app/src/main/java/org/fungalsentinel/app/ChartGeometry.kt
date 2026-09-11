package org.fungalsentinel.app

import kotlin.math.abs
import kotlin.math.max

data class ChartPoint(val x: Double, val y: Double)
data class ChartErrorBar(val x: Double, val low: Double, val high: Double)

data class ChartRange(val min: Double, val max: Double) {
    init {
        require(min.isFinite() && max.isFinite() && max > min)
    }

    fun fraction(value: Double): Double {
        val span = max - min
        return if (span.isFinite()) (value - min) / span
        else (value / 2.0 - min / 2.0) / (max / 2.0 - min / 2.0)
    }
}

data class SpectrumChartGeometry(
    val points: List<ChartPoint>,
    /** Separate runs prevent a line from being drawn across invalid samples. */
    val segments: List<List<ChartPoint>>,
    val xRange: ChartRange,
    val yRange: ChartRange,
    val targetPeakNm: Double?,
    val integrationRangeNm: ClosedFloatingPointRange<Double>?
)

data class ConcentrationChartGeometry(
    val standards: List<ChartPoint>,
    val errorBars: List<ChartErrorBar>,
    val regressionLine: List<ChartPoint>,
    val unknown: ChartPoint?,
    val xRange: ChartRange,
    val yRange: ChartRange
)

/** Pure chart-domain preparation. Canvas code should only map these finite values to pixels. */
object ChartGeometry {
    fun pairedFinitePoints(
        x: DoubleArray,
        y: DoubleArray,
        allowedX: ClosedFloatingPointRange<Double>? = null
    ): List<ChartPoint> = pairedFiniteSegments(x, y, allowedX).flatten().sortedBy { it.x }

    fun pairedFiniteSegments(
        x: DoubleArray,
        y: DoubleArray,
        allowedX: ClosedFloatingPointRange<Double>? = null
    ): List<List<ChartPoint>> {
        val result = mutableListOf<List<ChartPoint>>()
        var run = mutableListOf<ChartPoint>()
        fun finishRun() {
            if (run.isNotEmpty()) result += run
            run = mutableListOf()
        }
        for (index in 0 until minOf(x.size, y.size)) {
            val px = x[index]
            val py = y[index]
            if (px.isFinite() && py.isFinite() && (allowedX == null || px in allowedX)) run += ChartPoint(px, py)
            else finishRun()
        }
        finishRun()
        return result
    }

    fun spectrum(
        wavelengthsNm: DoubleArray,
        correctedIntensity: DoubleArray,
        validRangeNm: ClosedFloatingPointRange<Double>,
        targetPeakNm: Double,
        integrationWidthNm: Double
    ): SpectrumChartGeometry {
        val valid = normalizedRange(validRangeNm) ?: (0.0..1.0)
        val segments = pairedFiniteSegments(wavelengthsNm, correctedIntensity, valid)
        val points = segments.flatten().sortedBy { it.x }
        val target = targetPeakNm.takeIf { it.isFinite() && it in valid }
        val halfWidth = integrationWidthNm / 2.0
        val interval = if (targetPeakNm.isFinite() && halfWidth.isFinite() && halfWidth >= 0.0) {
            val low = max(valid.start, targetPeakNm - halfWidth)
            val high = minOf(valid.endInclusive, targetPeakNm + halfWidth)
            if (high >= low) low..high else null
        } else null
        return SpectrumChartGeometry(
            points = points,
            segments = segments,
            xRange = ChartRange(valid.start, valid.endInclusive),
            yRange = paddedRange(points.map { it.y } + 0.0),
            targetPeakNm = target,
            integrationRangeNm = interval
        )
    }

    fun concentration(
        standards: List<StandardMeasurement>,
        result: ConcentrationResult?,
        sampleArea: Double?
    ): ConcentrationChartGeometry {
        val standardPoints = standards.mapNotNull {
            if (it.concentration.isFinite() && it.area.isFinite()) ChartPoint(it.concentration, it.area) else null
        }
        val errorBars = standards.mapNotNull {
            val mean = it.area
            val sd = it.sd
            if (it.concentration.isFinite() && mean.isFinite() && sd.isFinite() && sd >= 0.0) {
                ChartErrorBar(it.concentration, mean - sd, mean + sd)
            } else null
        }
        val unknown = if (result?.sampleConcentration?.isFinite() == true && sampleArea?.isFinite() == true) {
            ChartPoint(result.sampleConcentration, sampleArea)
        } else null
        val xValues = standardPoints.map { it.x } + listOfNotNull(unknown?.x)
        val initialX = paddedRange(xValues + 0.0)
        val line = if (result?.slope?.isFinite() == true && result.intercept.isFinite()) {
            listOf(
                ChartPoint(initialX.min, result.slope * initialX.min + result.intercept),
                ChartPoint(initialX.max, result.slope * initialX.max + result.intercept)
            ).filter { it.y.isFinite() }
        } else emptyList()
        val yValues = standardPoints.map { it.y } + errorBars.flatMap { listOf(it.low, it.high) } +
            listOfNotNull(unknown?.y) + line.map { it.y }
        return ConcentrationChartGeometry(standardPoints, errorBars, line, unknown, initialX, paddedRange(yValues + 0.0))
    }

    fun paddedRange(values: Iterable<Double>): ChartRange {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return ChartRange(0.0, 1.0)
        val low = finite.minOrNull()!!
        val high = finite.maxOrNull()!!
        if (high > low) {
            val span = high - low
            val padding = if (span.isFinite()) span * 0.08 else (high / 2.0 - low / 2.0) * 0.16
            val paddedLow = safeAdd(low, -padding)
            val paddedHigh = safeAdd(high, padding)
            if (paddedHigh > paddedLow) return ChartRange(paddedLow, paddedHigh)
            return ChartRange(low, high)
        }
        val padding = max(1.0, abs(low) * 0.08).takeIf { it.isFinite() } ?: Double.MAX_VALUE / 4.0
        val paddedLow = safeAdd(low, -padding)
        val paddedHigh = safeAdd(high, padding)
        return if (paddedHigh > paddedLow) ChartRange(paddedLow, paddedHigh)
        else if (low > -Double.MAX_VALUE) ChartRange(Math.nextDown(low), low)
        else ChartRange(low, Math.nextUp(low))
    }

    private fun safeAdd(value: Double, delta: Double): Double {
        val result = value + delta
        return when {
            result == Double.POSITIVE_INFINITY -> Double.MAX_VALUE
            result == Double.NEGATIVE_INFINITY -> -Double.MAX_VALUE
            else -> result
        }
    }

    private fun normalizedRange(range: ClosedFloatingPointRange<Double>): ClosedFloatingPointRange<Double>? {
        val low = range.start
        val high = range.endInclusive
        return if (low.isFinite() && high.isFinite() && high > low) low..high else null
    }
}
