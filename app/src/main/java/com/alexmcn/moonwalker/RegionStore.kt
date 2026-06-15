package com.alexmcn.moonwalker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestionează datele de regiuni geografice:
 *  - lista de țări europene (bundled)
 *  - subdiviziuni nivel 1 (regiuni/provincii) și nivel 2 (județe/departamente)
 *    descărcate din GADM la prima utilizare, cacheuate local
 *  - detectare zonă prin Nominatim (reverse geocode la tap pe hartă)
 *
 * Pentru România se folosesc direct datele din Counties.kt (deja în APK).
 * Cache: context.filesDir/regions/{ISO3}_{1|2}.json
 */
object RegionStore {

    data class Country(val name: String, val iso2: String, val iso3: String)
    data class Subdivision(val name: String, val parent: String = "", val poly: List<DoubleArray>)
    data class TappedZone(val name: String, val poly: List<DoubleArray>?)

    val EUROPE: List<Country> = listOf(
        Country("Albania",                  "AL", "ALB"),
        Country("Andorra",                  "AD", "AND"),
        Country("Austria",                  "AT", "AUT"),
        Country("Belarus",                  "BY", "BLR"),
        Country("Belgia",                   "BE", "BEL"),
        Country("Bosnia și Herțegovina",    "BA", "BIH"),
        Country("Bulgaria",                 "BG", "BGR"),
        Country("Cehia",                    "CZ", "CZE"),
        Country("Cipru",                    "CY", "CYP"),
        Country("Croația",                  "HR", "HRV"),
        Country("Danemarca",                "DK", "DNK"),
        Country("Elveția",                  "CH", "CHE"),
        Country("Estonia",                  "EE", "EST"),
        Country("Finlanda",                 "FI", "FIN"),
        Country("Franța",                   "FR", "FRA"),
        Country("Germania",                 "DE", "DEU"),
        Country("Grecia",                   "GR", "GRC"),
        Country("Irlanda",                  "IE", "IRL"),
        Country("Islanda",                  "IS", "ISL"),
        Country("Italia",                   "IT", "ITA"),
        Country("Kosovo",                   "XK", "XKO"),
        Country("Letonia",                  "LV", "LVA"),
        Country("Liechtenstein",            "LI", "LIE"),
        Country("Lituania",                 "LT", "LTU"),
        Country("Luxemburg",                "LU", "LUX"),
        Country("Macedonia de Nord",        "MK", "MKD"),
        Country("Malta",                    "MT", "MLT"),
        Country("Marea Britanie",           "GB", "GBR"),
        Country("Moldova",                  "MD", "MDA"),
        Country("Muntenegru",               "ME", "MNE"),
        Country("Norvegia",                 "NO", "NOR"),
        Country("Olanda",                   "NL", "NLD"),
        Country("Polonia",                  "PL", "POL"),
        Country("Portugalia",               "PT", "PRT"),
        Country("România",                  "RO", "ROU"),
        Country("San Marino",               "SM", "SMR"),
        Country("Serbia",                   "RS", "SRB"),
        Country("Slovacia",                 "SK", "SVK"),
        Country("Slovenia",                 "SI", "SVN"),
        Country("Spania",                   "ES", "ESP"),
        Country("Suedia",                   "SE", "SWE"),
        Country("Ucraina",                  "UA", "UKR"),
        Country("Ungaria",                  "HU", "HUN"),
    )

    private fun cacheDir(ctx: Context) = File(ctx.filesDir, "regions").also { it.mkdirs() }

    /** Nivel 1: regiuni/provincii/județe. România → Counties.kt; altele → GADM download+cache */
    fun loadLevel1(ctx: Context, iso3: String, callback: (List<Subdivision>?) -> Unit) {
        if (iso3 == "ROU") {
            callback(Counties.names().map { n -> Subdivision(n, "", Counties.polygon(n) ?: emptyList()) })
            return
        }
        Thread {
            try {
                val cache = File(cacheDir(ctx), "${iso3}_1.json")
                callback(if (cache.exists()) loadCache(cache)
                         else downloadGadm(cache, "gadm41_${iso3}_1.json", "NAME_1", ""))
            } catch (e: Exception) { callback(null) }
        }.start()
    }

    /** Nivel 2: județe/departamente/municipii. Filtrat după parentName (nivel 1 selectat). */
    fun loadLevel2(ctx: Context, iso3: String, parentName: String, callback: (List<Subdivision>?) -> Unit) {
        Thread {
            try {
                val cache = File(cacheDir(ctx), "${iso3}_2.json")
                val all = if (cache.exists()) loadCache(cache)
                          else downloadGadm(cache, "gadm41_${iso3}_2.json", "NAME_2", "NAME_1")
                callback(all?.filter { it.parent.norm() == parentName.norm() })
            } catch (e: Exception) { callback(null) }
        }.start()
    }

    /**
     * Reverse geocode via Nominatim. Nivelul de detaliu depinde de zoom-ul hărții:
     * zoom < 7 → țară, 7-8 → regiune, 9-10 → județ, 11-12 → oraș, 13-14 → sat, 15+ → cartier
     */
    fun reverseGeocode(lat: Double, lon: Double, mapZoom: Double, callback: (TappedZone?) -> Unit) {
        val nomZoom = when {
            mapZoom < 7  -> 6
            mapZoom < 9  -> 8
            mapZoom < 11 -> 10
            mapZoom < 13 -> 12
            mapZoom < 15 -> 14
            else         -> 16
        }
        Thread {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse" +
                    "?lat=$lat&lon=$lon&zoom=$nomZoom&format=jsonv2&polygon_geojson=1"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Moonwalker/1.0 (alexutus@gmail.com)")
                conn.connectTimeout = 10_000; conn.readTimeout = 20_000
                if (conn.responseCode != 200) { callback(null); return@Thread }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val name = json.optString("display_name", "?").split(",").first().trim()
                val geo  = json.optJSONObject("geojson")
                val poly = if (geo != null) simplify(extractLargestPoly(geo) ?: emptyList(), 300) else null
                callback(TappedZone(name, poly?.takeIf { it.isNotEmpty() }))
            } catch (e: Exception) { callback(null) }
        }.start()
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun downloadGadm(cache: File, filename: String, nameKey: String, parentKey: String): List<Subdivision>? {
        val conn = URL("https://geodata.ucdavis.edu/gadm/gadm4.1/json/$filename").openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000; conn.readTimeout = 90_000
        if (conn.responseCode != 200) return null
        val raw = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val features = JSONObject(raw).getJSONArray("features")
        val result = ArrayList<Subdivision>(features.length())
        for (i in 0 until features.length()) {
            val feat   = features.getJSONObject(i)
            val props  = feat.getJSONObject("properties")
            val name   = props.optString(nameKey, "?")
            val parent = if (parentKey.isNotEmpty()) props.optString(parentKey, "") else ""
            val poly   = extractLargestPoly(feat.getJSONObject("geometry")) ?: continue
            result.add(Subdivision(name, parent, simplify(poly, 200)))
        }
        saveCache(cache, result)
        return result
    }

    private fun extractLargestPoly(geo: JSONObject): List<DoubleArray>? =
        when (geo.getString("type")) {
            "Polygon" -> parseRing(geo.getJSONArray("coordinates").getJSONArray(0))
            "MultiPolygon" -> {
                val c = geo.getJSONArray("coordinates")
                var best: List<DoubleArray>? = null; var bestN = 0
                for (i in 0 until c.length()) {
                    val r = parseRing(c.getJSONArray(i).getJSONArray(0))
                    if (r.size > bestN) { bestN = r.size; best = r }
                }
                best
            }
            else -> null
        }

    private fun parseRing(ring: JSONArray): List<DoubleArray> {
        val pts = ArrayList<DoubleArray>(ring.length())
        for (i in 0 until ring.length()) {
            val pt = ring.getJSONArray(i)
            pts.add(doubleArrayOf(pt.getDouble(1), pt.getDouble(0))) // GeoJSON [lon,lat] → [lat,lon]
        }
        return pts
    }

    private fun simplify(pts: List<DoubleArray>, max: Int): List<DoubleArray> {
        if (pts.size <= max) return pts
        val step = pts.size / max
        return pts.filterIndexed { i, _ -> i % step == 0 }
    }

    private fun saveCache(file: File, subs: List<Subdivision>) {
        val arr = JSONArray()
        for (s in subs) {
            val pArr = JSONArray()
            for (pt in s.poly) { val p = JSONArray(); p.put(pt[0]); p.put(pt[1]); pArr.put(p) }
            val o = JSONObject(); o.put("n", s.name); o.put("par", s.parent); o.put("p", pArr)
            arr.put(o)
        }
        file.writeText(arr.toString())
    }

    private fun loadCache(file: File): List<Subdivision> {
        val arr = JSONArray(file.readText())
        val result = ArrayList<Subdivision>(arr.length())
        for (i in 0 until arr.length()) {
            val o   = arr.getJSONObject(i)
            val pArr = o.getJSONArray("p")
            val poly = ArrayList<DoubleArray>(pArr.length())
            for (j in 0 until pArr.length()) {
                val p = pArr.getJSONArray(j); poly.add(doubleArrayOf(p.getDouble(0), p.getDouble(1)))
            }
            result.add(Subdivision(o.getString("n"), o.optString("par", ""), poly))
        }
        return result
    }

    /** Normalizează pentru comparare: minuscule, fără diacritice */
    private fun String.norm() = lowercase()
        .replace('ș', 's').replace('ş', 's').replace('ț', 't').replace('ţ', 't')
        .replace('ă', 'a').replace('â', 'a').replace('î', 'i')
}
