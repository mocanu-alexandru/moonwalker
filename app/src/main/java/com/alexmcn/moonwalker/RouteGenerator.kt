package com.alexmcn.moonwalker

import kotlin.math.*

/**
 * Generează coordonate live pentru o serpentină (boustrophedon) peste o zonă.
 *
 * vertical=false → rânduri E-V (orizontal, ca un TV raster)
 * vertical=true  → coloane N-S (vertical, ca un aspirator/robot de gazon)
 */
class RouteGenerator(
    private val zone: Zone,
    private val rowSpacingM: Double = 130.0,
    private val stepM: Double = 75.0,
    private val vertical: Boolean = false
) {
    private val M_LAT = 111_320.0
    private val latCenter = (zone.latMin + zone.latMax) / 2.0

    // spacing între linii (rând sau coloană) în coordonate geografice
    private val dLatLine = rowSpacingM / M_LAT
    private val dLonLine = rowSpacingM / (M_LAT * cos(Math.toRadians(latCenter)))

    /** numărul total de linii (rânduri orizontale sau coloane verticale) */
    private val nLines: Int = if (!vertical)
        max(1, ((zone.latMax - zone.latMin) / dLatLine).toInt() + 1)
    else
        max(1, ((zone.lonMax - zone.lonMin) / dLonLine).toInt() + 1)

    private var lineIndex = 0
    private var colInLine = 0
    private var currentLinePoints: List<DoubleArray> = emptyList()
    private var finished = false

    init { buildLine(0) }

    private fun buildLine(idx: Int) {
        if (idx >= nLines) { finished = true; currentLinePoints = emptyList(); return }
        val pts = ArrayList<DoubleArray>()

        if (!vertical) {
            // Rând orizontal: latitudine fixă, pas pe longitudine
            val lat = zone.latMax - idx * dLatLine
            val kLon = M_LAT * cos(Math.toRadians(lat))
            val dLonStep = stepM / kLon
            var lon = zone.lonMin
            while (lon <= zone.lonMax) {
                if (zone.contains(lat, lon)) pts.add(doubleArrayOf(lat, lon))
                lon += dLonStep
            }
        } else {
            // Coloană verticală: longitudine fixă, pas pe latitudine
            val lon = zone.lonMin + idx * dLonLine
            val dLatStep = stepM / M_LAT
            var lat = zone.latMax
            while (lat >= zone.latMin) {
                if (zone.contains(lat, lon)) pts.add(doubleArrayOf(lat, lon))
                lat -= dLatStep
            }
        }

        // serpentină: liniile impare sunt parcurse în sens invers
        if (idx % 2 == 1) pts.reverse()
        currentLinePoints = pts
        colInLine = 0
        // dacă linia e în afara poligonului, sari la următoarea
        if (pts.isEmpty() && idx + 1 < nLines) buildLine(idx + 1)
    }

    fun next(): DoubleArray? {
        if (finished) return null
        while (colInLine >= currentLinePoints.size) {
            lineIndex++
            if (lineIndex >= nLines) { finished = true; return null }
            buildLine(lineIndex)
            if (finished) return null
        }
        val p = currentLinePoints[colInLine]
        colInLine++
        return p
    }

    fun progress(): Double = if (nLines == 0) 1.0 else min(1.0, lineIndex.toDouble() / nLines)
    fun isFinished() = finished

    fun reset() { lineIndex = 0; colInLine = 0; finished = false; buildLine(0) }

    val totalRows: Int get() = nLines

    fun seekToRow(n: Int) {
        lineIndex = n.coerceIn(0, nLines - 1)
        colInLine = 0; finished = false
        buildLine(lineIndex)
    }

    companion object {
        /**
         * Preview rapid al traseului (bbox-based, fără clipping la poligon).
         * vertical=false → linii orizontale, vertical=true → coloane verticale.
         */
        fun computePreview(
            zone: Zone, rowM: Double,
            fromFraction: Double = 0.0, toFraction: Double = 1.0,
            maxRows: Int = 500,
            vertical: Boolean = false
        ): List<DoubleArray> {
            if (!vertical) {
                val dLatRow = rowM / 111_320.0
                val nRows = max(1, ((zone.latMax - zone.latMin) / dLatRow).toInt() + 1)
                val startRow = (fromFraction * nRows).toInt().coerceIn(0, nRows)
                val endRow   = (toFraction   * nRows).toInt().coerceIn(0, nRows)
                val range = endRow - startRow
                if (range <= 0) return emptyList()
                val step = max(1, range / maxRows)
                val result = ArrayList<DoubleArray>((range / step + 1) * 2)
                var idx = startRow
                while (idx < endRow) {
                    val lat = zone.latMax - idx * dLatRow
                    if (idx % 2 == 0) {
                        result.add(doubleArrayOf(lat, zone.lonMin))
                        result.add(doubleArrayOf(lat, zone.lonMax))
                    } else {
                        result.add(doubleArrayOf(lat, zone.lonMax))
                        result.add(doubleArrayOf(lat, zone.lonMin))
                    }
                    idx += step
                }
                return result
            } else {
                val latCenter = (zone.latMin + zone.latMax) / 2.0
                val dLon = rowM / (111_320.0 * cos(Math.toRadians(latCenter)))
                val nCols = max(1, ((zone.lonMax - zone.lonMin) / dLon).toInt() + 1)
                val startCol = (fromFraction * nCols).toInt().coerceIn(0, nCols)
                val endCol   = (toFraction   * nCols).toInt().coerceIn(0, nCols)
                val range = endCol - startCol
                if (range <= 0) return emptyList()
                val step = max(1, range / maxRows)
                val result = ArrayList<DoubleArray>((range / step + 1) * 2)
                var idx = startCol
                while (idx < endCol) {
                    val lon = zone.lonMin + idx * dLon
                    if (idx % 2 == 0) {
                        result.add(doubleArrayOf(zone.latMax, lon))
                        result.add(doubleArrayOf(zone.latMin, lon))
                    } else {
                        result.add(doubleArrayOf(zone.latMin, lon))
                        result.add(doubleArrayOf(zone.latMax, lon))
                    }
                    idx += step
                }
                return result
            }
        }

        fun haversine(a: DoubleArray, b: DoubleArray): Double {
            val R = 6_371_000.0
            val la1 = Math.toRadians(a[0]); val la2 = Math.toRadians(b[0])
            val dLa = la2 - la1; val dLo = Math.toRadians(b[1] - a[1])
            val h = sin(dLa/2).pow(2) + cos(la1)*cos(la2)*sin(dLo/2).pow(2)
            return 2 * R * asin(sqrt(h))
        }
    }
}
