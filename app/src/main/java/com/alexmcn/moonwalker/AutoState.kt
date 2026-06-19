package com.alexmcn.moonwalker

import android.content.Context
import kotlin.math.*

/**
 * Stare persistentă a modului AUTO — ca să ruleze cu adevărat nesupravegheat:
 *  • flag „auto activ" + parametrii ancoră (poly, tickHz) → repornire automată după kill/reboot;
 *  • poziția spiralei (x,y,dx,dy,blockN) + ancora → REIA de unde a rămas, nu de la Iași de fiecare dată.
 *
 * Spirala se reia DOAR dacă ancora salvată e aproape de cea curentă (aceeași „casă"); altfel se
 * resetează (te-ai mutat → pornește spirală nouă din noua locație).
 */
object AutoState {
    private const val PREFS = "mw_auto"
    private const val RESUME_RADIUS_M = 4000.0   // sub atât = aceeași ancoră → reia spirala

    fun setActive(ctx: Context, poly: String, tickHz: Int) {
        ctx.prefs().edit()
            .putBoolean("active", true)
            .putString("poly", poly)
            .putInt("tickHz", tickHz)
            .apply()
    }

    fun isActive(ctx: Context): Boolean = ctx.prefs().getBoolean("active", false)
    fun poly(ctx: Context): String? = ctx.prefs().getString("poly", null)
    fun tickHz(ctx: Context): Int = ctx.prefs().getInt("tickHz", 6)

    /** Oprire completă (RELEASE) → nu mai reporni automat; uită și spirala. */
    fun clear(ctx: Context) {
        ctx.prefs().edit().clear().apply()
    }

    /** Salvează poziția spiralei + ancora (după fiecare bloc) ca să putem relua exact. */
    fun saveSpiral(ctx: Context, anchorLat: Double, anchorLon: Double,
                   x: Int, y: Int, dx: Int, dy: Int, blockN: Int) {
        ctx.prefs().edit()
            .putLong("aLat", anchorLat.toRawBits())
            .putLong("aLon", anchorLon.toRawBits())
            .putInt("sx", x).putInt("sy", y)
            .putInt("sdx", dx).putInt("sdy", dy)
            .putInt("sBlk", blockN)
            .apply()
    }

    /**
     * Întoarce [x,y,dx,dy,blockN] dacă există o spirală salvată cu ancoră ≈ cea curentă; altfel null
     * (→ pornește spirală nouă). Așa reboot-ul/redeschiderea continuă frontiera, nu reia tot.
     */
    fun loadSpiral(ctx: Context, anchorLat: Double, anchorLon: Double): IntArray? {
        val p = ctx.prefs()
        if (!p.contains("sx")) return null
        val sLat = Double.fromBits(p.getLong("aLat", 0L))
        val sLon = Double.fromBits(p.getLong("aLon", 0L))
        if (haversine(anchorLat, anchorLon, sLat, sLon) > RESUME_RADIUS_M) return null
        return intArrayOf(
            p.getInt("sx", 0), p.getInt("sy", 0),
            p.getInt("sdx", 0), p.getInt("sdy", -1),
            p.getInt("sBlk", 0)
        )
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val h = sin(dLa / 2).pow(2) +
                cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
