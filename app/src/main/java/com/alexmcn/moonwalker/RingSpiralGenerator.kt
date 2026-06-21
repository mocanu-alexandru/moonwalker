package com.alexmcn.moonwalker

import kotlin.math.*

/**
 * Generator „blast radius": descoperă în PĂTRATE CONCENTRICE din ce în ce mai mari pornind dintr-un
 * centru (primul fix GPS / punctul de intrare în județ), trasând STRICT laturile fiecărui pătrat —
 * ca o undă de șoc care se extinde. Pasul dintre inele = `pitchM` (≈ lățimea unei celule H3), deci
 * laturile inelelor consecutive sunt la ≤ o lățime de hexagon → uniunea lor ACOPERĂ tot (inclusiv
 * diagonalele: în distanță L∞ orice punct e la ≤ pitch/2 de o latură). Lungime de drum ≈ Aria/pitch,
 * identică cu o serpentină — dar ordinea radiază din centru (deblochezi întâi ce-i lângă tine).
 *
 *  • clip la `zone` (poligon județ / țară): punctele din afara poligonului NU se emit (driverul le sare);
 *  • `skipUnlocked`: punctele care cad în celule deja deblocate NU se emit (driverul trece rapid peste);
 *  • inelele se extind până depășesc bbox-ul zonei față de centru (rază L∞ maximă).
 *
 * Emite puncte unul câte unul (`next()`), inel cu inel (k = 0,1,2,...), fiecare inel parcurs continuu
 * în sens orar; inelele se leagă la colț (salt de un pitch → driverul îl tratează ca pe orice pas).
 */
class RingSpiralGenerator(
    private val zone: Zone,
    private val centerLat: Double,
    private val centerLon: Double,
    private val pitchM: Double,
    private val stepM: Double,
    private val skipUnlocked: Boolean = false
) {
    private val mLat = 111_320.0
    private val kLon = mLat * cos(Math.toRadians(centerLat))

    // Rază L∞ (metri) până la cel mai îndepărtat colț al bbox-ului zonei față de centru → terminare.
    private val maxR: Double = run {
        val n = abs(zone.latMax - centerLat) * mLat
        val s = abs(centerLat - zone.latMin) * mLat
        val e = abs(zone.lonMax - centerLon) * kLon
        val w = abs(centerLon - zone.lonMin) * kLon
        max(max(n, s), max(e, w)) + pitchM
    }

    private var ring = 0
    private var ringPts: List<DoubleArray> = emptyList()
    private var idx = 0
    private var finished = false

    init { buildRing() }

    private fun toLatLon(northM: Double, eastM: Double) =
        doubleArrayOf(centerLat + northM / mLat, centerLon + eastM / kLon)

    private fun keep(p: DoubleArray): Boolean =
        zone.contains(p[0], p[1]) && !(skipUnlocked && UnlockedMask.isUnlocked(p[0], p[1]))

    /** Construiește următorul inel ne-gol (sare inelele complet în afara poligonului / deblocate). */
    private fun buildRing() {
        while (true) {
            if (ring == 0) {
                ring++
                val c = toLatLon(0.0, 0.0)
                if (keep(c)) { ringPts = listOf(c); idx = 0; return }
                continue
            }
            val r = ring * pitchM
            if (r > maxR) { finished = true; ringPts = emptyList(); return }
            ring++

            val side = 2 * r
            val nSeg = max(1, ceil(side / stepM).toInt())
            val stp = side / nSeg
            val pts = ArrayList<DoubleArray>(nSeg * 4)
            // laturile pătratului în sens orar, pornind din colțul nord-vest (north=+r, east=-r)
            var e = -r;        while (e <= r) { pts.add(toLatLon(r, e));  e += stp }   // sus  V→E
            var nn = r - stp;  while (nn >= -r) { pts.add(toLatLon(nn, r)); nn -= stp } // dreapta N→S
            e = r - stp;       while (e >= -r) { pts.add(toLatLon(-r, e)); e -= stp }   // jos  E→V
            nn = -r + stp;     while (nn < r)  { pts.add(toLatLon(nn, -r)); nn += stp } // stânga S→N

            val kept = ArrayList<DoubleArray>(pts.size)
            for (p in pts) if (keep(p)) kept.add(p)
            if (kept.isNotEmpty()) { ringPts = kept; idx = 0; return }
            // inel complet în afară / deblocat → încearcă următorul (ring deja incrementat)
        }
    }

    fun next(): DoubleArray? {
        if (finished) return null
        while (idx >= ringPts.size) {
            buildRing()
            if (finished) return null
        }
        return ringPts[idx++]
    }
}
