package com.alexmcn.moonwalker

import android.content.Context

/**
 * „Creierul" auto-tuning al AUTO-extinderii. Nu condUce GPS-ul — doar decide setările
 * (rowM = distanță între rânduri, stepM = pas pe rând) pe baza FEEDBACK-ului real de
 * deblocare măsurat după fiecare bloc (CoverageController nu vede GPS, vede doar cât a
 * deblocat efectiv botul: ratio = celule_deblocate / celule_așteptate).
 *
 * OBIECTIV: maximizează rata de acoperire (area rate = viteză × rowM, m²/s) — adică teritoriu
 * pe secundă — CU CONDIȚIA ca acoperirea reală să rămână ≥ TARGET (~90%). Rânduri mai late și
 * viteză mai mare = mai eficient, dar dacă sar hexagoane → ratio scade → dă înapoi.
 *
 * METODĂ: hill-climbing pe coordonate (probează alternativ rowM apoi stepM). Fiecare bloc e
 * un „probe": crește un knob, acoperă, măsoară. Dacă ratio se ține → acceptă (baza urcă, knob mai
 * agresiv). Dacă ratio scade → respinge (knob sub bază, probe mai mic). Bazele bune se persistă,
 * deci botul pornește data viitoare de unde a învățat.
 *
 * SELF-CHECK: `record()` întoarce și `retry=true` dacă blocul curent a deblocat prea puțin →
 * MockService reacoperă blocul (mai lent/dens). DEAD: dacă N blocuri la rând deblochează ZERO
 * deși aveau celule de deblocat → pipeline-ul de injecție e mort (Xposed/root) → oprire + alertă.
 */
class CoverageController(private val ctx: Context, private val tickHz: Int) {

    data class Params(val rowM: Double, val stepM: Double, val tickHz: Int) {
        val speedKmh: Double get() = stepM * tickHz * 3.6
        val areaRate: Double get() = stepM * tickHz * rowM   // m²/s acoperiți (viteză × lățime bandă)
    }

    data class Outcome(
        val ratio: Double, val retry: Boolean, val dead: Boolean,
        val sampled: Boolean, val status: String
    )

    companion object {
        private const val PREFS = "mw_autotune"
        const val ROW_MIN = 40.0;  const val ROW_MAX = 170.0
        const val STEP_MIN = 15.0; const val STEP_MAX = 50.0
        private const val TARGET = 0.90       // acoperire-țintă acceptabilă
        private const val HIGH = 0.965        // peste asta avem margine → lărgim agresiv
        private const val RETRY_THRESHOLD = 0.80  // sub asta reacoperim blocul (self-check „întoarce-te")
        private const val MIN_SAMPLE = 25     // sub atâtea celule așteptate, ratio e zgomot → nu adapta
        private const val DEAD_LIMIT = 4      // blocuri la rând cu 0 deblocate (deși aveau) → pipeline mort
        private const val EMA_A = 0.45
    }

    // baza acceptată (cele mai bune setări confirmate) — punct de plecare pt. fiecare probe
    private var baseRow: Double
    private var baseStep: Double
    // pașii de probe (se micșorează la eșec, cresc la succes susținut)
    private var rowProbe = 10.0
    private var stepProbe = 4.0
    private var probeKnob = 0          // 0 = probează rowM, 1 = probează stepM
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

    /** Setările pentru următorul bloc NORMAL — baza + un probe (lărgire) pe knob-ul curent. */
    fun nextParams(): Params {
        val candRow  = if (probeKnob == 0) (baseRow + rowProbe).coerceAtMost(ROW_MAX) else baseRow
        val candStep = if (probeKnob == 1) (baseStep + stepProbe).coerceAtMost(STEP_MAX) else baseStep
        lastCandidate = Params(candRow, candStep, tickHz)
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
     * deblocate efectiv). Adaptează knob-urile și întoarce verdictul (retry / dead / status).
     */
    fun record(expected: Int, gained: Int): Outcome {
        blocksDone++
        if (expected < MIN_SAMPLE) {
            // eșantion prea mic → ratio nesigur; nu adaptăm, nu numărăm spre „dead"
            return Outcome(1.0, retry = false, dead = false, sampled = false,
                status = "bloc mic (%d cel)".format(expected))
        }
        val ratio = gained.toDouble() / expected
        lastRatio = ratio
        ema = if (ema < 0) ratio else EMA_A * ratio + (1 - EMA_A) * ema   // doar pt. afișare (trend)

        // detectare pipeline mort: deblocare ZERO deși aveam celule de deblocat
        if (gained == 0) deadStreak++ else deadStreak = 0
        if (deadStreak >= DEAD_LIMIT) {
            return Outcome(ratio, retry = false, dead = true, sampled = true,
                status = "PIPELINE MORT: 0 deblocate × $deadStreak blocuri — verifică Xposed/root")
        }

        // Hill-climbing pe coordonate: fiecare bloc e un probe la setări DIFERITE, deci decizia
        // accept/reject se ia pe ratio-ul ACESTUI bloc (nu pe ema — ema ar amesteca probe-uri
        // la setări diferite și ar accepta un candidat prost când istoricul recent e bun).
        val cand = lastCandidate
        if (ratio >= TARGET) {
            // candidatul ține acoperirea → acceptă-l ca bază nouă
            baseRow = cand.rowM; baseStep = cand.stepM
            if (ratio >= HIGH) {         // margine mare → probe mai agresiv data viitoare
                if (probeKnob == 0) rowProbe = (rowProbe * 1.3).coerceAtMost(20.0)
                else                stepProbe = (stepProbe * 1.3).coerceAtMost(8.0)
            }
            // memorează cea mai bună combinație fezabilă (area rate maxim)
            if (cand.areaRate > bestRate) { bestRate = cand.areaRate; bestRow = cand.rowM; bestStep = cand.stepM }
        } else {
            // candidatul a pierdut teritoriu → respinge: trage knob-ul sub bază + probe mai mic
            if (probeKnob == 0) {
                baseRow = (baseRow - rowProbe * 0.5).coerceIn(ROW_MIN, ROW_MAX)
                rowProbe = (rowProbe * 0.5).coerceAtLeast(3.0)
            } else {
                baseStep = (baseStep - stepProbe * 0.5).coerceIn(STEP_MIN, STEP_MAX)
                stepProbe = (stepProbe * 0.5).coerceAtLeast(2.0)
            }
        }
        probeKnob = probeKnob xor 1   // alternează knob-ul probat
        persist()

        val retry = ratio < RETRY_THRESHOLD
        val best = Params(bestRow, bestStep, tickHz)
        val status = "acop %.0f%% (ema %.0f%%) • învățat %.0fm/%.0fkm/h"
            .format(ratio * 100, ema * 100, bestRow, best.speedKmh)
        return Outcome(ratio, retry, dead = false, sampled = true, status = status)
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
