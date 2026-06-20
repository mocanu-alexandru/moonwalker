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

    /**
     * Salvează ancora (= originea blast-radius, ≈ „acasă") + indexul județului curent din ordinea
     * nearest-first. Reluarea sare județele deja terminate. Ancora se persistă din primul fix → ordinea
     * județelor (deterministă din ancoră) e identică la fiecare repornire, deci `idx` rămâne valid.
     */
    fun saveCounty(ctx: Context, anchorLat: Double, anchorLon: Double, idx: Int) {
        ctx.prefs().edit()
            .putLong("aLat", anchorLat.toRawBits())
            .putLong("aLon", anchorLon.toRawBits())
            .putInt("cIdx", idx)
            .apply()
    }

    /**
     * Indexul județului de la care se reia, dacă ancora salvată ≈ cea curentă (aceeași origine →
     * aceeași ordine de județe). Altfel 0 (origine nouă → reordonează de la zero). Reboot/redeschidere
     * continuă de la județul neterminat, nu reia tot.
     */
    fun countyIndex(ctx: Context, anchorLat: Double, anchorLon: Double): Int {
        val p = ctx.prefs()
        if (!p.contains("cIdx")) return 0
        val sLat = Double.fromBits(p.getLong("aLat", 0L))
        val sLon = Double.fromBits(p.getLong("aLon", 0L))
        if (haversine(anchorLat, anchorLon, sLat, sLon) > RESUME_RADIUS_M) return 0
        return p.getInt("cIdx", 0)
    }

    /**
     * Ancora spiralei salvată (≈ „acasă"), independent de GPS. Preferată la RESUME/RESTART în locul
     * unei citiri `getLastKnownLocation` care, după teardown-ul mock-ului, poate întoarce încă ultima
     * poziție MOCK (frontiera) → dacă e >4km de ancoră, spirala s-ar re-centra pe locul blocat. Cu
     * ancora salvată, `loadSpiral` se potrivește mereu (distanță 0) → reia frontiera garantat.
     */
    fun anchor(ctx: Context): DoubleArray? {
        val p = ctx.prefs()
        if (!p.contains("aLat")) return null
        return doubleArrayOf(Double.fromBits(p.getLong("aLat", 0L)), Double.fromBits(p.getLong("aLon", 0L)))
    }

    /**
     * Loop-guard pt. watcher-ul „blocat": numără restart-urile consecutive la ACELAȘI bloc. Dacă
     * blocul a avansat de la ultimul restart → streak=1 (progres, totul ok); altfel incrementează.
     * Caller-ul oprește hammeringul când streak-ul devine mare. Resetat de clear() (RELEASE/țară gata).
     */
    fun noteRestart(ctx: Context, blockN: Int): Int {
        val p = ctx.prefs()
        val lastBlk = p.getInt("rsBlk", -1)
        val streak = if (blockN == lastBlk) p.getInt("rsStreak", 0) + 1 else 1
        p.edit().putInt("rsBlk", blockN).putInt("rsStreak", streak).apply()
        return streak
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
