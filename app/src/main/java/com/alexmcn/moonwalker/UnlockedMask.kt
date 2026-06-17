package com.alexmcn.moonwalker

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.zip.GZIPInputStream
import kotlin.math.floor

/**
 * Masca zonelor deja DEBLOCATE în Bump (footprint), extrasă din SQLite-ul Bump
 * (tabel footprint_spatial__v1, celule H3 res-10) și rasterizată într-o grilă de 50 m.
 *
 * Scop: la generarea traseului, sărim punctele care cad în zone deja deblocate,
 * ca botul să rute­ze DOAR prin hexagoanele blocate (mai puțin condus).
 *
 * Format fișier (gzip): [int32 LE count][count × int64 LE chei sortate].
 * Cheia = (iLat shl 17) or iLon, cu iLat=floor(lat/DLAT), iLon=floor(lon/DLON).
 * Constantele DLAT/DLON TREBUIE să fie identice cu cele din scriptul de generare (REF_LAT=47, GRID=50m).
 *
 * Sursă (în ordine): fișier extern getExternalFilesDir/unlocked_mask.bin.gz (reîmprospătabil
 * via `adb push`), altfel asset-ul bundle-uit unlocked_mask.bin.gz.
 */
object UnlockedMask {
    // Trebuie să corespundă generatorului (Python): GRID_M=50, REF_LAT=47.
    private const val DLAT = 50.0 / 111_320.0
    private const val DLON = 50.0 / (111_320.0 * 0.6819983600624985) // cos(47°)

    @Volatile private var keys: LongArray? = null
    @Volatile var count: Int = 0; private set

    val isLoaded: Boolean get() = keys != null

    /** Încarcă masca o singură dată (idempotent). Sigur de apelat din Activity și din Service. */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (keys != null) return
        val bytes = try {
            val ext = File(ctx.getExternalFilesDir(null), "unlocked_mask.bin.gz")
            val raw = if (ext.exists()) ext.inputStream() else ctx.assets.open("unlocked_mask.bin.gz")
            GZIPInputStream(raw).use { it.readBytes() }
        } catch (_: Exception) { null } ?: return

        try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.int
            val arr = LongArray(n)
            for (i in 0 until n) arr[i] = buf.long
            // fișierul e deja sortat la generare; sortăm defensiv ca binarySearch să fie corect
            arr.sort()
            keys = arr
            count = n
        } catch (_: Exception) { /* fișier corupt → mască inactivă */ }
    }

    private fun keyOf(lat: Double, lon: Double): Long {
        val iLat = floor(lat / DLAT).toLong()
        val iLon = floor(lon / DLON).toLong()
        return (iLat shl 17) or iLon
    }

    /** True dacă punctul (lat,lon) e într-o celulă deja deblocată. */
    fun isUnlocked(lat: Double, lon: Double): Boolean {
        val k = keys ?: return false
        return Arrays.binarySearch(k, keyOf(lat, lon)) >= 0
    }
}
