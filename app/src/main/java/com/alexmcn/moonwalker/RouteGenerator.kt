package com.alexmcn.moonwalker

import kotlin.math.*

/**
 * Generează coordonate live pentru o serpentină (boustrophedon) peste o zonă,
 * fără să stocheze fișiere. Cheamă next() repetat ca să obții următorul punct.
 *
 * Optimizat pentru deblocare hexagoane ~150m (Țesătura/Bump):
 *  - rândurile sunt la pasul vertical al hexagonului (rowSpacingM)
 *  - pe fiecare rând, pași la stepM
 *  - point-in-polygon ca să nu iasă din zonă (sare segmentele din afară)
 */
class RouteGenerator(
    private val zone: Zone,
    private val rowSpacingM: Double = 130.0,   // distanța între rânduri (m)
    private val stepM: Double = 75.0,          // pasul pe rând (m)
    private val vertical: Boolean = false      // false = rânduri E-V, true = N-S
) {
    private val M_LAT = 111_320.0

    // bounding box al zonei
    private val latMin = zone.latMin
    private val latMax = zone.latMax
    private val lonMin = zone.lonMin
    private val lonMax = zone.lonMax

    // starea serpentinei
    private var rowIndex = 0
    private var colInRow = 0
    private var currentRowPoints: List<DoubleArray> = emptyList()
    private var finished = false

    private val dLatRow = rowSpacingM / M_LAT

    /** numărul total estimat de rânduri */
    private val nRows = max(1, ((latMax - latMin) / dLatRow).toInt() + 1)

    init {
        buildRow(0)
    }

    /** construiește lista de puncte pentru un rând (deja filtrate prin point-in-polygon) */
    private fun buildRow(idx: Int) {
        if (idx >= nRows) { finished = true; currentRowPoints = emptyList(); return }
        // de la NORD spre SUD
        val lat = latMax - idx * dLatRow
        val kLon = M_LAT * cos(Math.toRadians(lat))
        val dLon = stepM / kLon
        val pts = ArrayList<DoubleArray>()
        var lon = lonMin
        while (lon <= lonMax) {
            if (zone.contains(lat, lon)) {
                pts.add(doubleArrayOf(lat, lon))
            }
            lon += dLon
        }
        // serpentină: rândurile impare le inversăm
        if (idx % 2 == 1) pts.reverse()
        currentRowPoints = pts
        colInRow = 0
        // dacă rândul e gol (în afara poligonului), sari la următorul
        if (pts.isEmpty() && idx + 1 < nRows) buildRow(idx + 1)
    }

    /** întoarce următoarea coordonată [lat, lon] sau null dacă s-a terminat zona */
    fun next(): DoubleArray? {
        if (finished) return null
        while (colInRow >= currentRowPoints.size) {
            rowIndex++
            if (rowIndex >= nRows) { finished = true; return null }
            buildRow(rowIndex)
            if (finished) return null
        }
        val p = currentRowPoints[colInRow]
        colInRow++
        return p
    }

    /** progres aproximativ 0..1 */
    fun progress(): Double {
        if (nRows == 0) return 1.0
        return min(1.0, rowIndex.toDouble() / nRows)
    }

    fun isFinished() = finished

    fun reset() {
        rowIndex = 0; colInRow = 0; finished = false
        buildRow(0)
    }

    val totalRows: Int get() = nRows

    fun seekToRow(n: Int) {
        rowIndex = n.coerceIn(0, nRows - 1)
        colInRow = 0
        finished = false
        buildRow(rowIndex)
    }

    companion object {
        /**
         * Generează punctele de capăt ale rândurilor pentru preview pe hartă (bbox-based,
         * fără clipping la poligon — instantaneu, O(maxRows)).
         * [fromFraction, toFraction] determină ce porțiune din traseu se returnează.
         */
        fun computePreview(
            zone: Zone, rowM: Double,
            fromFraction: Double = 0.0, toFraction: Double = 1.0,
            maxRows: Int = 500
        ): List<DoubleArray> {
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
        }

        /** distanță haversine în metri între două puncte [lat,lon] */
        fun haversine(a: DoubleArray, b: DoubleArray): Double {
            val R = 6_371_000.0
            val la1 = Math.toRadians(a[0]); val la2 = Math.toRadians(b[0])
            val dLa = la2 - la1
            val dLo = Math.toRadians(b[1] - a[1])
            val h = sin(dLa/2).pow(2) + cos(la1)*cos(la2)*sin(dLo/2).pow(2)
            return 2 * R * asin(sqrt(h))
        }
    }
}
