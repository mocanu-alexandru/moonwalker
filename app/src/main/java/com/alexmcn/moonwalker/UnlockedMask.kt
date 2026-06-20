package com.alexmcn.moonwalker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.uber.h3core.H3Core
import com.uber.h3core.util.LatLng
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
            // newSystemInstance() = încarcă libh3-java.so via System.loadLibrary. Folosim o copie
            // patched a .so (libm adăugat în NEEDED) din jniLibs, deci cos/sin/lroundl se rezolvă.
            if (h3 == null) h3 = H3Core.newSystemInstance()

            // find (fără glob de shell) → calea către main.db sub dir-ul de cont
            val dbPath = su("find /data/data/$BUMP_PKG/files/app_group -maxdepth 2 -name main.db -type f")
                ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.endsWith("main.db") }
                ?: return failKeep("Bump main.db negăsit (logat?)")

            val dst = File(ctx.cacheDir, "bump_fp.db")
            // copie root → cache Moonwalker, lizibilă; +wal/shm pt. consistență WAL
            su("cp '$dbPath' '${dst.path}'; " +
               "cp '$dbPath-wal' '${dst.path}-wal'; " +
               "cp '$dbPath-shm' '${dst.path}-shm'; " +
               "chmod 666 '${dst.path}'*") ?: return failKeep("copiere DB eșuată (root?)")
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

    // ──────────────────────────────────────────────────────────────────────────
    // SELF-CHECK: măsurarea acoperirii reale per bloc (cât a deblocat efectiv botul).
    // Folosit de CoverageController pentru retry + auto-tuning. Toate fail-safe (set gol
    // / 0 la orice eroare → controllerul tratează blocul ca „fără eșantion", nu adaptează greșit).
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Celulele H3 res-10 din bbox care sunt ÎNCĂ blocate (nu apar în setul deblocat curent).
     * = celulele pe care ne AȘTEPTĂM să le deblocăm acoperind acest bloc.
     * Se cheamă ÎNAINTE de acoperire (vs. setul curent). Întoarce [] dacă H3 indisponibil.
     */
    fun lockedCellsInBbox(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): LongArray {
        val core = h3 ?: return LongArray(0)
        val ring = listOf(
            LatLng(latMin, lonMin), LatLng(latMin, lonMax),
            LatLng(latMax, lonMax), LatLng(latMax, lonMin)
        )
        val all = try { core.polygonToCells(ring, emptyList(), RES) } catch (_: Throwable) { return LongArray(0) }
        val locked = cells ?: return all.toLongArray()   // nimic deblocat încă → toate sunt „expected"
        val out = ArrayList<Long>(all.size)
        for (c in all) if (Arrays.binarySearch(locked, c) < 0) out.add(c)
        return out.toLongArray()
    }

    /**
     * Ca `lockedCellsInBbox`, dar pe POLIGONUL real al unei zone (județ): celulele H3 res-10 al căror
     * centru cade în poligon și sunt ÎNCĂ blocate. = celulele pe care trebuie să le deblocăm acoperind
     * județul. Folosit pt. GARANȚIA 100% per județ (țintim direct ce rămâne blocat la final). Fail-safe: [].
     */
    fun lockedCellsInPolygon(ring: List<DoubleArray>): LongArray {
        val core = h3 ?: return LongArray(0)
        if (ring.size < 3) return LongArray(0)
        val poly = ring.map { LatLng(it[0], it[1]) }
        val all = try { core.polygonToCells(poly, emptyList(), RES) } catch (_: Throwable) { return LongArray(0) }
        val locked = cells ?: return all.toLongArray()
        val out = ArrayList<Long>(all.size)
        for (c in all) if (Arrays.binarySearch(locked, c) < 0) out.add(c)
        return out.toLongArray()
    }

    /**
     * GARANȚIA „între zone": celulele din bbox care sunt ÎNCĂ blocate dar au ≥ `minUnlockedNeighbors`
     * vecini deblocați = găuri izolate (cusături între județe deja acoperite, slivere de digitizare,
     * reziduuri ratate). Scoped la bbox (costul ∝ celule din bbox, nu tot setul deblocat al țării) →
     * rulat ca backstop după fiecare județ. Întoarce INDECȘII de celulă (reutilizabili de cleanup). [] fail-safe.
     */
    fun lockedIsolatedCellsInBbox(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double,
                                  minUnlockedNeighbors: Int = 4): LongArray {
        val core = h3 ?: return LongArray(0)
        val locked = cells ?: return LongArray(0)
        val ring = listOf(
            LatLng(latMin, lonMin), LatLng(latMin, lonMax),
            LatLng(latMax, lonMax), LatLng(latMax, lonMin)
        )
        val all = try { core.polygonToCells(ring, emptyList(), RES) } catch (_: Throwable) { return LongArray(0) }
        val out = ArrayList<Long>()
        for (c in all) {
            if (Arrays.binarySearch(locked, c) >= 0) continue        // deja deblocată
            val disk = try { core.gridDisk(c, 1) } catch (_: Throwable) { continue }
            var u = 0
            for (n in disk) if (n != c && Arrays.binarySearch(locked, n) >= 0) u++
            if (u >= minUnlockedNeighbors) out.add(c)
        }
        return out.toLongArray()
    }

    /**
     * Centrele [lat,lon] ale unui set DAT de celule (indiferent dacă-s deblocate sau nu). Folosit pt.
     * serpentina directă peste celulele blocate (rutare „doar prin nedeblocat"). Fail-safe: [].
     */
    fun cellsToCenters(cellsArr: LongArray): List<DoubleArray> {
        val core = h3 ?: return emptyList()
        val out = ArrayList<DoubleArray>(cellsArr.size)
        for (c in cellsArr) {
            val ll = try { core.cellToLatLng(c) } catch (_: Throwable) { continue }
            out.add(doubleArrayOf(ll.lat, ll.lng))
        }
        return out
    }

    /**
     * Câte din `expected` (celule blocate înainte de acoperire) apar ACUM în setul deblocat.
     * Se cheamă DUPĂ acoperire + refresh(). ratio = gainedAmong/expected.size = acoperirea reală.
     */
    fun gainedAmong(expected: LongArray): Int {
        val locked = cells ?: return 0
        var n = 0
        for (c in expected) if (Arrays.binarySearch(locked, c) >= 0) n++
        return n
    }

    /**
     * Centrele [lat,lon] ale celulelor din `expected` (cele blocate înainte de acoperire) care sunt
     * ÎNCĂ blocate ACUM (după acoperire + refresh()). = exact găurile rămase în acest bloc.
     * Folosit de cleanup-ul „garanție 100%": botul țintește direct fiecare centru, nu mai face
     * serpentină statistică. Fail-safe: [] dacă masca/H3 indisponibile (→ fără cleanup, dar fără greșeli).
     */
    fun stillLockedCenters(expected: LongArray): List<DoubleArray> {
        val locked = cells ?: return emptyList()
        val core = h3 ?: return emptyList()
        val out = ArrayList<DoubleArray>()
        for (c in expected) {
            if (Arrays.binarySearch(locked, c) < 0) {   // încă blocată
                val ll = try { core.cellToLatLng(c) } catch (_: Throwable) { continue }
                out.add(doubleArrayOf(ll.lat, ll.lng))
            }
        }
        return out
    }

    /**
     * SEEK & DESTROY: hexagoanele „găuri" — celule NEDEBLOCATE înconjurate de celule deblocate
     * (≥ minUnlockedNeighbors din 6 vecini deblocați) → hexagoane singulare ratate în interiorul
     * teritoriului acoperit. Frontiera exterioară a acoperirii are <4 vecini deblocați → e exclusă
     * automat (vrem doar găurile interne, nu marginea hărții).
     *
     * Algoritm: pt. fiecare celulă deblocată, vecinii ei care NU-s deblocați = candidați-gaură;
     * apoi păstrăm candidatul dacă ≥ minUnlockedNeighbors din vecinii LUI sunt deblocați.
     * Întoarce centrele [lat,lon]. Fail-safe: [] dacă H3/mask indisponibile.
     */
    fun isolatedLockedHoles(minUnlockedNeighbors: Int = 4): List<DoubleArray> {
        val unlocked = cells ?: return emptyList()
        val core = h3 ?: return emptyList()
        // candidați: vecini nedeblocați ai celulelor deblocate (frontieră + găuri)
        val candidates = HashSet<Long>()
        for (c in unlocked) {
            val ring = try { core.gridDisk(c, 1) } catch (_: Throwable) { continue }
            for (n in ring) if (n != c && Arrays.binarySearch(unlocked, n) < 0) candidates.add(n)
        }
        // păstrează doar găurile interne (suficient de înconjurate de deblocat)
        val out = ArrayList<DoubleArray>(candidates.size)
        for (cand in candidates) {
            val ring = try { core.gridDisk(cand, 1) } catch (_: Throwable) { continue }
            var u = 0
            for (n in ring) if (n != cand && Arrays.binarySearch(unlocked, n) >= 0) u++
            if (u >= minUnlockedNeighbors) {
                val ll = try { core.cellToLatLng(cand) } catch (_: Throwable) { continue }
                out.add(doubleArrayOf(ll.lat, ll.lng))
            }
        }
        return out
    }

    /** Rulează o comandă prin `su -c`; null la eșec/lipsă root. */
    // -M / --mount-master: rulează în namespace-ul global de mount (procesul app are
    // propriul mount namespace în care datele altor appuri pot să nu fie vizibile).
    private fun su(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-M", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() == 0) out else null
    } catch (e: Exception) { Log.e(TAG, "su threw", e); null }
}
