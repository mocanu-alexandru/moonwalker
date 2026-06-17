package com.alexmcn.moonwalker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.uber.h3core.H3Core
import java.io.File
import java.util.Arrays

/**
 * Zonele deja DEBLOCATE în Bump, citite DIRECT de pe device (root) din SQLite-ul Bump
 * (tabel footprint_spatial__v1, celule H3 res-10) — fără mască statică, fără sincronizare
 * manuală. Se reîmprospătează în foreground (la deschiderea appului și înainte de START),
 * niciodată dintr-un job de fundal (Samsung l-ar omorî).
 *
 * Membership exact: testăm `H3Core.latLngToCell(lat,lon,10) ∈ celule deblocate`.
 *
 * FAIL-SAFE: orice eroare (fără root, DB lipsă, H3 indisponibil) → isUnlocked = false
 * pentru tot → nu se sare NIMIC → acoperire completă. Niciodată nu sărim o celulă blocată
 * din cauza unei erori (a sări greșit pierde teritoriu; a NU sări doar mai conduce puțin).
 *
 * Cerințe: Moonwalker rulează pe ACELAȘI telefon (rootat) ca Bump, cu Bump logat.
 */
object UnlockedMask {
    private const val BUMP_PKG = "co.amo.android.location"
    private const val RES = 10

    @Volatile private var cells: LongArray? = null   // cell_index sortate (H3 res-10)
    @Volatile private var h3: H3Core? = null
    @Volatile var count: Int = 0; private set
    @Volatile var lastError: String? = null; private set

    val isReady: Boolean get() = cells != null && h3 != null

    /**
     * Reîmprospătează setul din DB-ul Bump (blocant — rulează pe thread separat, ~1-2s).
     * Întoarce true la succes. La eșec păstrează setul anterior dacă există (nu-l golește).
     */
    @Synchronized
    fun refresh(ctx: Context): Boolean {
        try {
            Log.i(TAG, "refresh: start")
            // newSystemInstance() = încarcă libh3-java.so via System.loadLibrary.
            // libh3-java.so folosește cos/sin/lroundl dar NU linkează libm în NEEDED → trebuie
            // să încărcăm întâi libm în namespace ca simbolurile matematice să se rezolve.
            if (h3 == null) {
                try { System.loadLibrary("m") } catch (t: Throwable) { Log.w(TAG, "loadLibrary m: $t") }
                h3 = H3Core.newSystemInstance()
            }
            Log.i(TAG, "refresh: H3 ok")

            val userDir = su("ls -d /data/data/$BUMP_PKG/files/app_group/*/ 2>/dev/null | head -1")
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: return failKeep("Bump negăsit/nelogat pe acest device")
            Log.i(TAG, "refresh: userDir=$userDir")

            val dst = File(ctx.cacheDir, "bump_fp.db")
            // copie root → cache Moonwalker, lizibilă; +wal/shm pt. consistență WAL
            su("cp ${userDir}main.db ${dst.path}; " +
               "cp ${userDir}main.db-wal ${dst.path}-wal 2>/dev/null; " +
               "cp ${userDir}main.db-shm ${dst.path}-shm 2>/dev/null; " +
               "chmod 666 ${dst.path}*") ?: return failKeep("copiere DB eșuată (root?)")
            if (!dst.exists()) return failKeep("DB Bump inaccesibil")

            // deschidem read-write pe COPIE ca SQLite să poată face checkpoint la WAL
            val db = SQLiteDatabase.openDatabase(dst.path, null, SQLiteDatabase.OPEN_READWRITE)
            val arr: LongArray
            try {
                val cur = db.rawQuery("SELECT cell_index FROM footprint_spatial__v1", null)
                arr = LongArray(cur.count)
                var i = 0
                while (cur.moveToNext()) arr[i++] = cur.getLong(0)
                cur.close()
            } finally { db.close() }
            arr.sort()

            cells = arr
            count = arr.size
            lastError = null
            Log.i(TAG, "refresh: OK cells=$count")
            return true
        } catch (e: Throwable) {
            // Throwable, NU doar Exception: H3 aruncă UnsatisfiedLinkError (un Error) dacă
            // nativul nu se încarcă — trebuie prins ca să NU crape appul (fail-safe).
            Log.e(TAG, "refresh: FAIL", e)
            return failKeep(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failKeep(msg: String): Boolean { Log.w(TAG, "refresh: failKeep: $msg"); lastError = msg; return false }
    private const val TAG = "UnlockedMask"

    /** True dacă punctul cade într-o celulă deblocată. Fail-safe: false la orice problemă. */
    fun isUnlocked(lat: Double, lon: Double): Boolean {
        val c = cells ?: return false
        val core = h3 ?: return false
        val cell = try { core.latLngToCell(lat, lon, RES) } catch (_: Throwable) { return false }
        return Arrays.binarySearch(c, cell) >= 0
    }

    /** Rulează o comandă prin `su -c`; null la eșec/lipsă root. */
    private fun su(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Log.i(TAG, "su exit=${p.exitValue()} out=${out.take(120)}")
        if (p.exitValue() == 0) out else null
    } catch (e: Exception) { Log.e(TAG, "su threw", e); null }
}
