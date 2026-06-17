package com.alexmcn.moonwalker

import kotlin.math.*

/**
 * Generează coordonate live pentru o serpentină (boustrophedon) peste o zonă.
 *
 * vertical=false → rânduri E-V (orizontal)
 * vertical=true  → coloane N-S (vertical, robot de gazon)
 */
class RouteGenerator(
    private val zone: Zone,
    private val rowSpacingM: Double = 130.0,
    private val stepM: Double = 75.0,
    private val vertical: Boolean = false,
    /** Dacă true, punctele care cad în zone deja deblocate (UnlockedMask) sunt sărite. */
    private val skipUnlocked: Boolean = false
) {
    private val M_LAT = 111_320.0
    private val latCenter = (zone.latMin + zone.latMax) / 2.0

    private val dLatLine = rowSpacingM / M_LAT
    private val dLonLine = rowSpacingM / (M_LAT * cos(Math.toRadians(latCenter)))

    private val nLines: Int = if (!vertical)
        max(1, ((zone.latMax - zone.latMin) / dLatLine).toInt() + 1)
    else
        max(1, ((zone.lonMax - zone.lonMin) / dLonLine).toInt() + 1)

    private var lineIndex = 0
    private var colInLine = 0
    private var currentLinePoints: List<DoubleArray> = emptyList()
    private var finished = false

    init { buildLine(0) }

    private fun skip(lat: Double, lon: Double): Boolean =
        skipUnlocked && UnlockedMask.isUnlocked(lat, lon)

    private fun buildLine(idx: Int) {
        if (idx >= nLines) { finished = true; currentLinePoints = emptyList(); return }
        val pts = ArrayList<DoubleArray>()
        if (!vertical) {
            val lat = zone.latMax - idx * dLatLine
            val kLon = M_LAT * cos(Math.toRadians(lat))
            val dLonStep = stepM / kLon
            var lon = zone.lonMin
            while (lon <= zone.lonMax) {
                if (zone.contains(lat, lon) && !skip(lat, lon)) pts.add(doubleArrayOf(lat, lon))
                lon += dLonStep
            }
        } else {
            val lon = zone.lonMin + idx * dLonLine
            val dLatStep = stepM / M_LAT
            var lat = zone.latMax
            while (lat >= zone.latMin) {
                if (zone.contains(lat, lon) && !skip(lat, lon)) pts.add(doubleArrayOf(lat, lon))
                lat -= dLatStep
            }
        }
        if (idx % 2 == 1) pts.reverse()
        currentLinePoints = pts
        colInLine = 0
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
        return currentLinePoints[colInLine++]
    }

    fun progress(): Double = if (nLines == 0) 1.0 else min(1.0, lineIndex.toDouble() / nLines)
    fun isFinished() = finished
    fun reset() { lineIndex = 0; colInLine = 0; finished = false; buildLine(0) }
    val totalRows: Int get() = nLines
    fun seekToRow(n: Int) {
        lineIndex = n.coerceIn(0, nLines - 1)
        colInLine = 0; finished = false; buildLine(lineIndex)
    }

    companion object {
        /**
         * Preview rapid al traseului.
         * Dacă zone.polygon există, fiecare linie e clipată la conturul real al poligonului
         * (scanline intersection) — nu mai apare ca dreptunghi.
         */
        fun computePreview(
            zone: Zone, rowM: Double,
            fromFraction: Double = 0.0, toFraction: Double = 1.0,
            maxRows: Int = 500,
            vertical: Boolean = false
        ): List<DoubleArray> {
            val poly = zone.polygon

            return if (!vertical) {
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
                    val goRight = idx % 2 == 0
                    if (poly != null) {
                        // Scanline: intersecții cu muchiile poligonului la latitudinea curentă
                        val lons = scanlineHits(poly, lat, coordIdx = 0, valueIdx = 1)
                        var k = 0
                        while (k + 1 < lons.size) {
                            if (goRight) {
                                result.add(doubleArrayOf(lat, lons[k]))
                                result.add(doubleArrayOf(lat, lons[k + 1]))
                            } else {
                                result.add(doubleArrayOf(lat, lons[k + 1]))
                                result.add(doubleArrayOf(lat, lons[k]))
                            }
                            k += 2
                        }
                    } else {
                        if (goRight) {
                            result.add(doubleArrayOf(lat, zone.lonMin))
                            result.add(doubleArrayOf(lat, zone.lonMax))
                        } else {
                            result.add(doubleArrayOf(lat, zone.lonMax))
                            result.add(doubleArrayOf(lat, zone.lonMin))
                        }
                    }
                    idx += step
                }
                result
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
                    val goDown = idx % 2 == 0
                    if (poly != null) {
                        // Scanline: intersecții cu muchiile poligonului la longitudinea curentă
                        val lats = scanlineHits(poly, lon, coordIdx = 1, valueIdx = 0)
                        var k = 0
                        while (k + 1 < lats.size) {
                            val lo = lats[k]; val hi = lats[k + 1]
                            if (goDown) {
                                result.add(doubleArrayOf(hi, lon))
                                result.add(doubleArrayOf(lo, lon))
                            } else {
                                result.add(doubleArrayOf(lo, lon))
                                result.add(doubleArrayOf(hi, lon))
                            }
                            k += 2
                        }
                    } else {
                        if (goDown) {
                            result.add(doubleArrayOf(zone.latMax, lon))
                            result.add(doubleArrayOf(zone.latMin, lon))
                        } else {
                            result.add(doubleArrayOf(zone.latMin, lon))
                            result.add(doubleArrayOf(zone.latMax, lon))
                        }
                    }
                    idx += step
                }
                result
            }
        }

        /**
         * Returnează valorile (sortate) pe axa valueIdx unde scanline la coord
         * intersectează muchiile poligonului poly.
         * coordIdx=0/valueIdx=1 → scanline orizontal (lat fix, vrem lon)
         * coordIdx=1/valueIdx=0 → scanline vertical (lon fix, vrem lat)
         */
        private fun scanlineHits(
            poly: List<DoubleArray>,
            coord: Double,
            coordIdx: Int,
            valueIdx: Int
        ): List<Double> {
            val hits = ArrayList<Double>()
            for (i in poly.indices) {
                val p1 = poly[i]; val p2 = poly[(i + 1) % poly.size]
                val c1 = p1[coordIdx]; val c2 = p2[coordIdx]
                // Convenție: contorizăm muchia dacă scanline trece strict prin interior
                if ((c1 <= coord && coord < c2) || (c2 <= coord && coord < c1)) {
                    val t = (coord - c1) / (c2 - c1)
                    hits.add(p1[valueIdx] + t * (p2[valueIdx] - p1[valueIdx]))
                }
            }
            hits.sort()
            return hits
        }

        /**
         * Estimează durata în secunde pentru parcurgerea zonei.
         * Folosește computePreview() (cu polygon clipping) pentru a obține lungimile reale
         * ale rândurilor/coloanelor, apoi le scalează la numărul total de linii din zonă.
         * fromFraction: de unde pornește (0.0 = tot, 0.5 = jumătate rămasă etc.)
         */
        fun estimateDuration(
            zone: Zone, rowM: Double, stepM: Double, hz: Int,
            vertical: Boolean, fromFraction: Double = 0.0
        ): Long {
            val preview = computePreview(zone, rowM, fromFraction, 1.0, 200, vertical)
            if (preview.size < 2) return 0L

            // Lungimea totală a rândurilor eșantionate
            var sampledLen = 0.0
            var i = 0
            while (i + 1 < preview.size) {
                sampledLen += haversine(preview[i], preview[i + 1])
                i += 2
            }
            val nSampled = preview.size / 2
            if (nSampled == 0) return 0L

            // Factor de scalare: câte linii totale există vs câte am eșantionat
            val M_LAT = 111_320.0
            val latCenter = (zone.latMin + zone.latMax) / 2.0
            val dLine = if (!vertical) rowM / M_LAT
                        else rowM / (M_LAT * cos(Math.toRadians(latCenter)))
            val nTotal = if (!vertical) max(1, ((zone.latMax - zone.latMin) / dLine).toInt() + 1)
                         else            max(1, ((zone.lonMax - zone.lonMin) / dLine).toInt() + 1)
            val fraction = 1.0 - fromFraction
            val nEffective = (nTotal * fraction).toInt().coerceAtLeast(1)
            val scale = nEffective.toDouble() / nSampled

            val totalLenM = sampledLen * scale
            val waypoints = (totalLenM / stepM).toLong()
            return if (hz > 0) waypoints / hz else waypoints
        }

        fun haversine(a: DoubleArray, b: DoubleArray): Double {
            val R = 6_371_000.0
            val la1 = Math.toRadians(a[0]); val la2 = Math.toRadians(b[0])
            val dLa = la2 - la1; val dLo = Math.toRadians(b[1] - a[1])
            val h = sin(dLa / 2).pow(2) + cos(la1) * cos(la2) * sin(dLo / 2).pow(2)
            return 2 * R * asin(sqrt(h))
        }
    }
}
