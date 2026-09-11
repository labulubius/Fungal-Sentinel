package org.fungalsentinel.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartGeometryTest {
    @Test
    fun spectrumUsesWavelengthCoordinatesAndClipsToValidRange() {
        val chart = ChartGeometry.spectrum(
            doubleArrayOf(410.0, 425.0, 500.0, 675.0, 690.0),
            doubleArrayOf(99.0, 1.0, 2.0, 3.0, 99.0),
            420.0..680.0,
            targetPeakNm = 500.0,
            integrationWidthNm = 40.0
        )

        assertEquals(listOf(425.0, 500.0, 675.0), chart.points.map { it.x })
        assertEquals(420.0, chart.xRange.min, 0.0)
        assertEquals(680.0, chart.xRange.max, 0.0)
        assertEquals(500.0, chart.targetPeakNm!!, 0.0)
        assertEquals(480.0, chart.integrationRangeNm!!.start, 0.0)
        assertEquals(520.0, chart.integrationRangeNm!!.endInclusive, 0.0)
        assertTrue(chart.xRange.fraction(500.0) != chart.xRange.fraction(425.0) + 0.5)
    }

    @Test
    fun spectrumHandlesMismatchedEmptyConstantAndNonfiniteData() {
        val chart = ChartGeometry.spectrum(
            doubleArrayOf(430.0, Double.NaN, 450.0, 460.0),
            doubleArrayOf(7.0, 8.0, Double.POSITIVE_INFINITY),
            420.0..680.0,
            targetPeakNm = Double.NaN,
            integrationWidthNm = Double.NaN
        )

        assertEquals(listOf(ChartPoint(430.0, 7.0)), chart.points)
        assertTrue(chart.yRange.max > chart.yRange.min)
        assertNull(chart.targetPeakNm)
        assertNull(chart.integrationRangeNm)

        val empty = ChartGeometry.spectrum(doubleArrayOf(), doubleArrayOf(), 420.0..680.0, 500.0, 20.0)
        assertTrue(empty.points.isEmpty())
        assertTrue(empty.yRange.max > empty.yRange.min)
    }

    @Test
    fun invalidSamplesSplitLineAndExtremeFiniteValuesDoNotOverflow() {
        val segments = ChartGeometry.pairedFiniteSegments(
            doubleArrayOf(1.0, 2.0, 3.0),
            doubleArrayOf(4.0, Double.NaN, 6.0)
        )
        assertEquals(listOf(listOf(ChartPoint(1.0, 4.0)), listOf(ChartPoint(3.0, 6.0))), segments)

        val range = ChartGeometry.paddedRange(listOf(-Double.MAX_VALUE, Double.MAX_VALUE))
        assertTrue(range.min.isFinite())
        assertTrue(range.max.isFinite())
        assertTrue(range.fraction(0.0).isFinite())
        val constant = ChartGeometry.paddedRange(listOf(Double.MAX_VALUE))
        assertTrue(constant.min.isFinite())
        assertTrue(constant.max.isFinite())
        assertTrue(constant.max > constant.min)
    }

    @Test
    fun integrationIntervalIsClippedToValidWavelengthRange() {
        val chart = ChartGeometry.spectrum(doubleArrayOf(), doubleArrayOf(), 420.0..680.0, 425.0, 20.0)
        assertEquals(420.0, chart.integrationRangeNm!!.start, 0.0)
        assertEquals(435.0, chart.integrationRangeNm!!.endInclusive, 0.0)
    }

    @Test
    fun concentrationIncludesStandardsFitAndOutOfRangeUnknown() {
        val standards = listOf(StandardMeasurement(0.0, 2.0), StandardMeasurement(1.0, 5.0), StandardMeasurement(2.0, 8.0))
        val result = ConcentrationResult(3.0, 2.0, 1.0, 3.0, true)
        val chart = ChartGeometry.concentration(standards, result, sampleArea = 11.0)

        assertEquals(3, chart.standards.size)
        assertEquals(3, chart.errorBars.size)
        assertEquals(ChartErrorBar(0.0, 2.0, 2.0), chart.errorBars.first())
        assertEquals(ChartPoint(3.0, 11.0), chart.unknown)
        assertEquals(2, chart.regressionLine.size)
        chart.regressionLine.forEach { assertEquals(3.0 * it.x + 2.0, it.y, 1e-10) }
        assertTrue(chart.xRange.min < 0.0)
        assertTrue(chart.xRange.max > 3.0)
        assertTrue(chart.yRange.min < 0.0)
        assertTrue(chart.yRange.max > 11.0)
    }

    @Test
    fun concentrationRangeIncludesReplicateErrorBars() {
        val standard = StandardMeasurement(1.0, doubleArrayOf(-10.0, 10.0))
        val chart = ChartGeometry.concentration(listOf(standard), null, null)
        assertTrue(chart.errorBars.single().low < -14.0)
        assertTrue(chart.errorBars.single().high > 14.0)
        assertTrue(chart.yRange.min < chart.errorBars.single().low)
        assertTrue(chart.yRange.max > chart.errorBars.single().high)
    }

    @Test
    fun concentrationIgnoresNonfiniteSampleAndWorksWithoutRegression() {
        val chart = ChartGeometry.concentration(
            listOf(StandardMeasurement(4.0, 9.0)),
            result = null,
            sampleArea = Double.POSITIVE_INFINITY
        )
        assertEquals(listOf(ChartPoint(4.0, 9.0)), chart.standards)
        assertTrue(chart.regressionLine.isEmpty())
        assertNull(chart.unknown)
        assertTrue(chart.xRange.max > chart.xRange.min)
        assertTrue(chart.yRange.max > chart.yRange.min)
    }
}
