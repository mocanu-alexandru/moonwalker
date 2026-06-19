package com.alexmcn.moonwalker

import kotlin.math.*

/**
 * Granița României, derivată din poligoanele de județe (Counties). Folosită de AUTO-extindere
 * ca să acopere DOAR România (din Iași spre exterior), fără să se reverse în Ucraina/Moldova.
 *
 *  • contains(lat,lon) — punctul cade în ORICE județ (bbox-prefilter + ray-cast pe fiecare poligon).
 *  • maxRingBlocks(anchor, blockM) — câte „inele" de blocuri trebuie parcurse de la anchor (Iași)
 *    până la cel mai îndepărtat colț al țării → criteriu de terminare a spiralei.
 */
object RomaniaGeo {
    private val polys: List<List<DoubleArray>> by lazy {
        Counties.names().mapNotNull { Counties.polygon(it) }.filter { it.isNotEmpty() }
    }

    /** [latMin, latMax, lonMin, lonMax] al întregii țări. */
    val bbox: DoubleArray by lazy {
        var laMin = 90.0; var laMax = -90.0; var loMin = 180.0; var loMax = -180.0
        for (p in polys) for (pt in p) {
            if (pt[0] < laMin) laMin = pt[0]; if (pt[0] > laMax) laMax = pt[0]
            if (pt[1] < loMin) loMin = pt[1]; if (pt[1] > loMax) loMax = pt[1]
        }
        doubleArrayOf(laMin, laMax, loMin, loMax)
    }

    fun contains(lat: Double, lon: Double): Boolean {
        val b = bbox
        if (lat < b[0] || lat > b[1] || lon < b[2] || lon > b[3]) return false
        for (poly in polys) if (pointInPoly(lat, lon, poly)) return true
        return false
    }

    /**
     * Un bloc (centru + cele 4 colțuri) atinge România dacă oricare din cele 5 puncte e în țară.
     * Prinde și blocurile de graniță al căror centru pică în afară dar care conțin teritoriu RO.
     */
    fun blockTouches(cLat: Double, cLon: Double, halfLatM: Double, halfLonM: Double): Boolean {
        val dLat = halfLatM / 111_320.0
        val dLon = halfLonM / (111_320.0 * cos(Math.toRadians(cLat)))
        if (contains(cLat, cLon)) return true
        if (contains(cLat - dLat, cLon - dLon)) return true
        if (contains(cLat - dLat, cLon + dLon)) return true
        if (contains(cLat + dLat, cLon - dLon)) return true
        if (contains(cLat + dLat, cLon + dLon)) return true
        return false
    }

    /** Câte inele de blocuri (max|x|,|y|) de la anchor acoperă tot bbox-ul țării → terminare spirală. */
    fun maxRingBlocks(anchorLat: Double, anchorLon: Double, blockM: Double): Int {
        val b = bbox
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(anchorLat))
        val ringsLat = max(abs(anchorLat - b[0]), abs(anchorLat - b[1])) * mLat / blockM
        val ringsLon = max(abs(anchorLon - b[2]), abs(anchorLon - b[3])) * mLon / blockM
        return ceil(max(ringsLat, ringsLon)).toInt() + 2   // +2 margine de graniță
    }

    private fun pointInPoly(lat: Double, lon: Double, poly: List<DoubleArray>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val yi = poly[i][0]; val xi = poly[i][1]
            val yj = poly[j][0]; val xj = poly[j][1]
            if ((yi > lat) != (yj > lat) &&
                lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
