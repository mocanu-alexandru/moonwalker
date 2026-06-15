package com.alexmcn.moonwalker

/**
 * O zonă de acoperit. Poate fi:
 *  - bounding box pur (polygon = null) → contains() e mereu true în box
 *  - poligon (listă de [lat,lon]) → contains() face point-in-polygon
 *
 * Suportă și găuri (holes) dacă e nevoie mai târziu.
 */
class Zone(
    val polygon: List<DoubleArray>? = null,   // [[lat,lon], ...] sau null pt bbox pur
    bboxLatMin: Double? = null,
    bboxLatMax: Double? = null,
    bboxLonMin: Double? = null,
    bboxLonMax: Double? = null,
    val name: String = ""
) {
    val latMin: Double
    val latMax: Double
    val lonMin: Double
    val lonMax: Double

    init {
        if (polygon != null && polygon.isNotEmpty()) {
            var laMin = Double.MAX_VALUE; var laMax = -Double.MAX_VALUE
            var loMin = Double.MAX_VALUE; var loMax = -Double.MAX_VALUE
            for (p in polygon) {
                if (p[0] < laMin) laMin = p[0]; if (p[0] > laMax) laMax = p[0]
                if (p[1] < loMin) loMin = p[1]; if (p[1] > loMax) loMax = p[1]
            }
            latMin = laMin; latMax = laMax; lonMin = loMin; lonMax = loMax
        } else {
            latMin = bboxLatMin ?: 0.0
            latMax = bboxLatMax ?: 0.0
            lonMin = bboxLonMin ?: 0.0
            lonMax = bboxLonMax ?: 0.0
        }
    }

    /** e punctul (lat,lon) în zonă? */
    fun contains(lat: Double, lon: Double): Boolean {
        // întâi bbox rapid
        if (lat < latMin || lat > latMax || lon < lonMin || lon > lonMax) return false
        val poly = polygon ?: return true  // bbox pur
        // ray casting point-in-polygon (folosim lon=x, lat=y)
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

    companion object {
        fun fromBbox(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double, name: String = "Box") =
            Zone(null, latMin, latMax, lonMin, lonMax, name)

        fun fromPolygon(poly: List<DoubleArray>, name: String = "") =
            Zone(poly, name = name)
    }
}
