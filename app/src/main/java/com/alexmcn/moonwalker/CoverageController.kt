package com.alexmcn.moonwalker

import android.content.Context

/**
 * „Creierul" auto-tuning al AUTO-extinderii. Nu condUce GPS-ul — doar decide setările
 * (rowM = distanță între rânduri, stepM = pas pe rând) pe baza FEEDBACK-ului real de
 * deblocare măsurat după fiecare bloc (CoverageController nu vede GPS, vede doar cât a
 * deblocat efectiv botul: ratio = celule_deblocate / celule_așteptate).
 *
 * OBIECTIV: acoperire COMPLETĂ DINTR-O SINGURĂ TRECERE — maximizează rata de acoperire (area rate =
 * viteză × rowM, m²/s) CU CONDIȚIA ca pasajul rapid singur să prindă ~TOATE hexagoanele (ratio ≥
 * TARGET ~98%). Adică: găsește cele mai LARGI rânduri / cel mai mare pas care încă NU lasă găuri.
 * Așa cleanup-ul „garanție 100%" din MockService devine o plasă de siguranță aproape goală — botul
 * nu mai trebuie „să se întoarcă". Cu cât lasă mai multe hexagoane nedescoperite, cu atât se
 * îndesește mai agresiv (pull ∝ deficit) ca să le prindă pe toate la trecerea următoare.
 *
 * METODĂ: hill-climbing pe `stepM` (singura pârghie reală în AUTO — vezi nextParams). Fiecare bandă e
 * un „probe": crește stepM (= viteză mai mare + interpolare mai rară), acoperă, măsoară. Dacă ratio se
 * ține ≥ TARGET → acceptă (baza urcă, probe mai agresiv). Dacă ratio scade → respinge și TRAGE ÎNAPOI
 * (mai dens) proporțional cu câte hexagoane a ratat → descoperă empiric plafonul de viteză al Bump.
 * Bazele bune se persistă, deci botul pornește data viitoare de unde a învățat. (`rowM` nu se mai
 * probează: driveCells/cleanup conduc cell-by-cell pe stepM, nu pe rânduri.)
 *
 * SEMNAL: `record()` primește acoperirea REALĂ a pasajului RAPID (înainte de cleanup) → învață pe
 * acoperirea de-o-trecere, nu pe 100%-ul post-cleanup. DEAD: dacă N blocuri la rând deblochează ZERO
 * deși aveau celule de deblocat → pipeline-ul de injecție e mort (Xposed/root) → oprire + alertă.
 */
class CoverageController(private val ctx: Context, private val tickHz: Int) {

    data class Params(val rowM: Double, val stepM: Double, val tickHz: Int) {
        val speedKmh: Double get() = stepM * tickHz * 3.6
        val areaRate: Double get() = stepM * tickHz * rowM   // m²/s acoperiți (viteză × lățime bandă)
    }

    data class Outcome(
        val ratio: Double, val dead: Boolean,
        val sampled: Boolean, val status: String
    )

    companion object {
        private const val PREFS = "mw_autotune"
        // ROW_MAX plafonat la ~100m: hexagoanele Bump (H3 res-10) au ~114m flat-to-flat, deci rânduri
        // >~100m sar benzi întregi de celule. Plafonul ține pasajul rapid la ~1-2% goluri (nu ~10%) →
        // cleanup-ul „garanție 100%" din MockService are doar câteva celule de țintit per bloc.
        const val ROW_MIN = 40.0;  const val ROW_MAX = 100.0
        const val STEP_MIN = 15.0; const val STEP_MAX = 50.0
        private const val TARGET = 0.98       // acoperire-țintă: ~completă DINTR-O TRECERE (nu ~90% + reveniri)
        private const val HIGH = 0.995        // practic perfect → mai avem margine să lărgim agresiv
        private const val MIN_SAMPLE = 25     // sub atâtea celule așteptate, ratio e zgomot → nu adapta
        private const val DEAD_LIMIT = 4      // unități la rând cu 0 deblocate (deși aveau) → pipeline mort
        private const val DEAD_BIG_SAMPLE = 1000  // o singură unitate cu atâtea celule și 0 deblocate = mort SIGUR
        private const val EMA_A = 0.45
    }

    // baza acceptată (cele mai bune setări confirmate) — punct de plecare pt. fiecare probe
    private var baseRow: Double        // FIX în AUTO: `rowM` NU mai e folosit la condus (driveCells/cleanup
                                       // merg cell-by-cell pe `stepM`+viteză, partitionBands pe BAND_M).
                                       // Rămâne doar pt. afișaj/areaRate; tuner-ul nu-l mai probează.
    private var baseStep: Double
    // pasul de probe pt. stepM (se micșorează la eșec, crește la succes susținut)
    private var stepProbe = 4.0
    private var lastCandidate: Params

    // cea mai bună combinație fezabilă văzută (ratio ≥ TARGET) cu cea mai mare area rate
    private var bestRow: Double; private var bestStep: Double; private var bestRate: Double

    private var ema = -1.0             // medie exponențială a acoperirii (-1 = neinițializat)
    private var deadStreak = 0
    var blocksDone = 0; private set
    var lastRatio = 0.0; private set

    init {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseRow  = p.getFloat("row",  75f).toDouble().coerceIn(ROW_MIN, ROW_MAX)
        baseStep = p.getFloat("step", 25f).toDouble().coerceIn(STEP_MIN, STEP_MAX)
        bestRow = baseRow; bestStep = baseStep
        bestRate = Params(baseRow, baseStep, tickHz).areaRate
        lastCandidate = Params(baseRow, baseStep, tickHz)
    }

    /**
     * Setările pentru următoarea bandă — baza + un probe (lărgire) pe `stepM` (singura pârghie reală în
     * AUTO: viteză = stepM×tickHz și densitatea de fix-uri pe celulă). Creșterea lui stepM ridică viteza
     * (și rărește interpolarea) → când depășește poarta Bump, ratio se prăbușește și `record()` trage
     * înapoi → tuner-ul descoperă empiric plafonul de viteză. `rowM` rămâne fix (nu se mai probează).
     */
    fun nextParams(): Params {
        val candStep = (baseStep + stepProbe).coerceAtMost(STEP_MAX)
        lastCandidate = Params(baseRow, candStep, tickHz)
        return lastCandidate
    }

    /** Setări sigure (cea mai bună bază confirmată, ușor mai dense) — pt. pasul de RETRY. */
    fun safeParams(): Params = Params(
        (bestRow * 0.85).coerceIn(ROW_MIN, ROW_MAX),
        (bestStep * 0.7).coerceIn(STEP_MIN, STEP_MAX),
        tickHz
    )

    /**
     * Înregistrează rezultatul real al blocului (din UnlockedMask: câte celule blocate au fost
     * deblocate efectiv). Adaptează knob-urile și întoarce verdictul (dead / status).
     */
    fun record(expected: Int, gained: Int): Outcome {
        blocksDone++
        if (expected < MIN_SAMPLE) {
            // eșantion prea mic → ratio nesigur; nu adaptăm, nu numărăm spre „dead"
            return Outcome(1.0, dead = false, sampled = false,
                status = "bloc mic (%d cel)".format(expected))
        }
        val ratio = gained.toDouble() / expected
        lastRatio = ratio
        ema = if (ema < 0) ratio else EMA_A * ratio + (1 - EMA_A) * ema   // doar pt. afișare (trend)

        // detectare pipeline mort: deblocare ZERO deși aveam celule de deblocat.
        // Cu măsurarea per JUDEȚ (eșantioane mari, rare), așteptarea a DEAD_LIMIT unități ar însemna
        // ore-zile de „acoperire" fără deblocare. Deci: o SINGURĂ unitate mare (≥ DEAD_BIG_SAMPLE celule)
        // cu 0 deblocate = mort SIGUR (nu zgomot) → trip imediat. Streak-ul rămâne pt. unități mici/ambigue.
        if (gained == 0) deadStreak++ else deadStreak = 0
        if ((gained == 0 && expected >= DEAD_BIG_SAMPLE) || deadStreak >= DEAD_LIMIT) {
            return Outcome(ratio, dead = true, sampled = true,
                status = "PIPELINE MORT: 0 deblocate din %d celule — verifică Xposed/root".format(expected))
        }

        // Hill-climbing pe coordonate: fiecare bloc e un probe la setări DIFERITE, deci decizia
        // accept/reject se ia pe ratio-ul ACESTUI bloc (nu pe ema — ema ar amesteca probe-uri
        // la setări diferite și ar accepta un candidat prost când istoricul recent e bun).
        val cand = lastCandidate
        if (ratio >= TARGET) {
            // candidatul (stepM lărgit) ține acoperirea → acceptă-l ca bază nouă
            baseStep = cand.stepM
            if (ratio >= HIGH) stepProbe = (stepProbe * 1.3).coerceAtMost(8.0)   // margine mare → probe mai agresiv
            // memorează cea mai bună combinație fezabilă (area rate maxim)
            if (cand.areaRate > bestRate) { bestRate = cand.areaRate; bestRow = cand.rowM; bestStep = cand.stepM }
        } else {
            // stepM prea mare a lăsat hexagoane nedescoperite (viteză peste poartă / interpolare prea rară)
            // → respinge și ÎNDESEȘTE proporțional cu DEFICITUL (câte a ratat), ca trecerea următoare să le
            // prindă pe TOATE dintr-o dată (fără reveniri).
            val shortfall = (TARGET - ratio).coerceIn(0.0, TARGET)
            val pull = 0.5 + shortfall / TARGET * 3.0   // 0.5 (abia sub țintă) … ~3.5 (acoperire slabă)
            baseStep = (baseStep - stepProbe * pull).coerceIn(STEP_MIN, STEP_MAX)
            stepProbe = (stepProbe * 0.5).coerceAtLeast(2.0)
        }
        persist()

        val best = Params(bestRow, bestStep, tickHz)
        val status = "acop %.0f%% (ema %.0f%%) • învățat %.0fm/%.0fkm/h"
            .format(ratio * 100, ema * 100, bestRow, best.speedKmh)
        return Outcome(ratio, dead = false, sampled = true, status = status)
    }

    /** Resetează contorul „pipeline mort" după un back-off (dă pipeline-ului o nouă șansă). */
    fun resetDead() { deadStreak = 0 }

    fun persist() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("row", bestRow.toFloat())
            .putFloat("step", bestStep.toFloat())
            .apply()
    }
}
