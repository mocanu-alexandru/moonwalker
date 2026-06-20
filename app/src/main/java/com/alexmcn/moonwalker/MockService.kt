package com.alexmcn.moonwalker

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlin.math.*

/**
 * Serviciu foreground care injectează poziții GPS prin mock provider.
 * Generează traseul live (RouteGenerator) și avansează cu viteza setată.
 *
 * Control prin Intent extras:
 *   EXTRA_LAT_MIN/MAX, EXTRA_LON_MIN/MAX  (bbox) sau
 *   EXTRA_POLY (string "lat,lon;lat,lon;...")
 *   EXTRA_SPEED_KMH, EXTRA_ROW_M, EXTRA_STEP_M, EXTRA_VERTICAL, EXTRA_LOOP
 */
class MockService : Service() {

    companion object {
        const val CH_ID = "moonwalker"
        const val NOTIF_ID = 1
        const val EXTRA_LAT_MIN = "latMin"; const val EXTRA_LAT_MAX = "latMax"
        const val EXTRA_LON_MIN = "lonMin"; const val EXTRA_LON_MAX = "lonMax"
        const val EXTRA_POLY = "poly"
        const val EXTRA_TICK_HZ = "tickHz"    // injecții GPS/secundă (default 1)
        const val EXTRA_ROW_M = "rowM"; const val EXTRA_STEP_M = "stepM"
        const val EXTRA_VERTICAL = "vertical"; const val EXTRA_LOOP = "loop"
        const val EXTRA_SKIP_FRACTION = "skipFraction"
        const val EXTRA_SKIP_UNLOCKED = "skipUnlocked"   // sari zonele deja deblocate (UnlockedMask)
        const val EXTRA_AUTO = "auto"                    // auto-extindere din locație (spirală blocuri)
        const val EXTRA_SEEK = "seek"                    // seek & destroy: vânează găurile (hexagoane singulare nedeblocate)
        private const val AUTO_BLOCK_M = 3000.0          // latura unui bloc de acoperire (3 km)
        private const val AUTO_INSET_M = 250.0           // margine ne-măsurată (celulele de graniță le acoperă blocul vecin)
        private const val SETTLE_MS = 3000L              // pauză înainte de citirea footprint-ului (Bump scrie cu mică întârziere)
        private const val WAKELOCK_MS = 60 * 60 * 1000L  // timeout wakelock; reînnoit per bloc (renewWakelock)
        private const val STALL_MS = 90_000L             // fără progres atâta timp → watchdog face self-heal
        private const val INJECT_FAIL_LIMIT = 30         // injecții consecutive eșuate → re-adaugă providerii
        private const val DEAD_BACKOFF_MS = 60_000L      // pauză după „pipeline mort" înainte de reîncercare
        private const val SEEK_MAX_PASSES = 4            // pasaje seek & destroy (re-scanare găuri) înainte de oprire
        private const val SEEK_HITS = 3                  // câte fix-uri staționare „lovesc" fiecare gaură
        private const val CLEANUP_MAX_PASSES = 3         // pasaje „garanție 100%" per bloc (țintire directă a celulelor rămase)
        private const val IASI_LAT = 47.16; private const val IASI_LON = 27.58  // fallback anchor
        const val ACTION_STOP = "com.alexmcn.moonwalker.STOP"        // PAUZĂ: rămâi parcat pe loc
        const val ACTION_RELEASE = "com.alexmcn.moonwalker.RELEASE"  // oprire completă: revii la GPS real

        private const val PREFS = "mw_resume"

        // stare observabilă pentru UI
        @Volatile var running = false
        @Volatile var holding = false     // true cât timp suntem „parcați" (pauză, fără mișcare)
        @Volatile var curLat = 0.0
        @Volatile var curLon = 0.0
        @Volatile var curRow = 0          // rândul curent din serpentină (pt. reluare)
        @Volatile var progress = 0.0
        @Volatile var pointsDone = 0
        @Volatile var statusText = "oprit"
        @Volatile var flpActive = false   // true dacă FLP setMockMode (canalul "Google fused") a reușit
    }

    private lateinit var lm: LocationManager
    private lateinit var fusedClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeProviders = mutableListOf<String>()
    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    // Pauză-hold: thread care reinjectează ultima poziție ca să rămânem „parcați" (Bump nu revine acasă)
    private var holdThread: Thread? = null
    @Volatile private var holdStop = false
    private var curPolyKey = ""          // poly-ul rulării curente (pt. a ști dacă reluăm același traseu)
    @Volatile private var lastUiMs = 0L  // throttling update notificare/status
    @Volatile private var lastProgressMs = 0L   // ultima injecție reușită (watchdog stagnare)
    @Volatile private var injectFails = 0       // injecții consecutive eșuate (self-heal provider)
    private var watchdog: Thread? = null
    @Volatile private var watchdogStop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELEASE) {
            AutoState.clear(applicationContext)   // oprire reală → nu mai reporni AUTO automat
            stopEverything(); clearResume(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) { pauseRoute(); return START_STICKY }

        val tickHz = (intent?.getIntExtra(EXTRA_TICK_HZ, 1) ?: 1).coerceIn(1, 100)
        val rowM = intent?.getDoubleExtra(EXTRA_ROW_M, 130.0) ?: 130.0
        val stepM = intent?.getDoubleExtra(EXTRA_STEP_M, 75.0) ?: 75.0
        val vertical = intent?.getBooleanExtra(EXTRA_VERTICAL, false) ?: false
        val loop = intent?.getBooleanExtra(EXTRA_LOOP, true) ?: true
        val skipFraction = intent?.getDoubleExtra(EXTRA_SKIP_FRACTION, 0.0) ?: 0.0
        val skipUnlocked = intent?.getBooleanExtra(EXTRA_SKIP_UNLOCKED, false) ?: false
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) ?: false
        val seek = intent?.getBooleanExtra(EXTRA_SEEK, false) ?: false
        // Auto/seek sar/folosesc masca → are nevoie de ea. Asigură un set proaspăt înainte de rulare.
        if ((skipUnlocked || auto || seek) && !UnlockedMask.isReady) UnlockedMask.refresh(applicationContext)
        val polyStr = intent?.getStringExtra(EXTRA_POLY)

        // Repornire fără date (intent gol de la sticky/onTaskRemoved) → nu porni traseu bogus.
        // UI-ul trimite mereu EXTRA_POLY; lipsa lui = restart de sistem, nu start real.
        if (polyStr.isNullOrBlank()) {
            // AUTONOMIE: dacă AUTO era activ și nu rulăm acum (proces omorât/restart sticky),
            // reia AUTO singur din parametrii persistați (spirala se reia de la frontieră, nu din Iași).
            if (!running && !holding && AutoState.isActive(applicationContext)) {
                val savedPoly = AutoState.poly(applicationContext)
                if (!savedPoly.isNullOrBlank()) {
                    val z = Zone.fromPolygon(parsePoly(savedPoly))
                    curPolyKey = savedPoly
                    // citește locația reală ca ancoră (înainte de addTestProvider) → spirala se reia corect
                    val rs: DoubleArray? = try {
                        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (loc != null) doubleArrayOf(loc.latitude, loc.longitude) else null
                    } catch (_: SecurityException) { null }
                    startForeground(NOTIF_ID, buildNotif("AUTO: reluare automată..."))
                    startWalking(z, AutoState.tickHz(applicationContext), 75.0, 25.0,
                        false, false, 0.0, rs, false, -1, auto = true)
                    return START_STICKY
                }
            }
            // restart sticky cu intent gol: dacă rulam → lăsăm sticky; dacă eram parcați (holding)
            // → NU opri (rămânem parcați); altfel oprește complet.
            if (!running && !holding) stopEverything()
            return if (running || holding) START_STICKY else START_NOT_STICKY
        }

        // AUTONOMIE: marchează AUTO ca activ + persistă parametrii ancoră → repornire automată
        // după kill/reboot (onStartCommand sticky / BootReceiver).
        if (auto && !polyStr.isNullOrBlank()) AutoState.setActive(applicationContext, polyStr, tickHz)

        val zone: Zone = if (polyStr != null && polyStr.isNotBlank()) {
            Zone.fromPolygon(parsePoly(polyStr))
        } else {
            Zone.fromBbox(
                intent?.getDoubleExtra(EXTRA_LAT_MIN, 0.0) ?: 0.0,
                intent?.getDoubleExtra(EXTRA_LAT_MAX, 0.0) ?: 0.0,
                intent?.getDoubleExtra(EXTRA_LON_MIN, 0.0) ?: 0.0,
                intent?.getDoubleExtra(EXTRA_LON_MAX, 0.0) ?: 0.0
            )
        }

        // Citim locația reală ÎNAINTE de addTestProvider — după aceea lm returnează locația mock
        val realStart: DoubleArray? = try {
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) doubleArrayOf(loc.latitude, loc.longitude) else null
        } catch (_: SecurityException) { null }

        // RELUARE de unde am rămas (fără revenire acasă):
        //  - origine = ultima poziție (dacă suntem parcați/rulăm, sau din prefs după restart proces)
        //  - rând = progresul salvat, DOAR dacă reluăm același traseu (același poly)
        var resumeOrigin: DoubleArray? = null
        var resumeRow = -1
        if ((running || holding) && (curLat != 0.0 || curLon != 0.0)) {
            resumeOrigin = doubleArrayOf(curLat, curLon)
            if (curPolyKey == polyStr) resumeRow = curRow
        } else {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (p.getBoolean("valid", false)) {
                resumeOrigin = doubleArrayOf(
                    Double.fromBits(p.getLong("lat", 0L)),
                    Double.fromBits(p.getLong("lon", 0L)))
                if (p.getString("key", "") == polyStr) resumeRow = p.getInt("row", 0)
            }
        }
        curPolyKey = polyStr

        startForeground(NOTIF_ID, buildNotif("pornire..."))
        startWalking(zone, tickHz, rowM, stepM, vertical, loop, skipFraction,
            resumeOrigin ?: realStart, skipUnlocked, resumeRow, auto, seek)
        return START_STICKY
    }

    private fun parsePoly(poly: String): List<DoubleArray> =
        poly.split(";").mapNotNull {
            val a = it.split(","); if (a.size == 2)
                doubleArrayOf(a[0].toDouble(), a[1].toDouble()) else null
        }

    private fun startWalking(
        zone: Zone, tickHz: Int, rowM: Double, stepM: Double,
        vertical: Boolean, loop: Boolean, skipFraction: Double = 0.0,
        realStart: DoubleArray? = null, skipUnlocked: Boolean = false, resumeRow: Int = -1,
        auto: Boolean = false, seek: Boolean = false
    ) {
        // Fix dublă-rulare: dacă userul schimbă setări și repornește, oprește COMPLET
        // thread-ul vechi (sincron) înainte de a reseta stopFlag — altfel thread-ul vechi
        // vede stopFlag resetat la false și continuă cu setările vechi în paralel.
        stopHold()
        stopPreviousRun()

        stopFlag = false
        running = true
        holding = false
        val speedKmh = stepM * tickHz * 3.6

        // Non-reference-counted: fiecare acquire() doar RESETEAZĂ timeout-ul (renewWakelock() per
        // bloc) → rulări de zile fără ca CPU să adoarmă, fără să stivuim acquire-uri.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moonwalker:injection")
            .apply { setReferenceCounted(false); acquire(WAKELOCK_MS) }
        startWatchdog()

        // LOCKITO-PARITY — injecție prin LocationManager (GPS + network test providers), NU FLP.
        //   De ce NU FLP setMockMode/setMockLocation:
        //     • are rate-limit GMS "too fast" (~90 km/h max; >atât → "location delivery blocked - too fast")
        //     • e marcat isMock=true → Bump îl vede source=Simulator → respins ca MockPosition
        //   LocationManager test provider (ca Lockito): GMS îl fuzionează în FLP FĂRĂ "too fast"
        //   (Lockito merge la 500+ km/h și deblochează), iar isMock e curățat de modulul Vector
        //   (createFromParcel → setMock(false)) → source=Phone → acceptat.
        activeProviders = mutableListOf()
        try {
            addMockProvider(LocationManager.GPS_PROVIDER)
            activeProviders.add(LocationManager.GPS_PROVIDER)
            try { addMockProvider(LocationManager.NETWORK_PROVIDER); activeProviders.add(LocationManager.NETWORK_PROVIDER) }
            catch (_: Exception) { /* network provider opțional */ }
            flpActive = true   // reutilizat ca indicator "injecție activă" pentru UI
        } catch (_: Exception) {
            statusText = "EROARE: app-ul nu e setat ca Mock Location în Developer Options"
            stopEverything(); return
        }

        // FĂRĂ teleport: pornim din locația REALĂ curentă și ne apropiem CONTINUU de traseu, la
        // viteza configurată. Bump (core Rust) aruncă locațiile "warped" (teleportate); un salt
        // de-acasă direct în zonă ar face fiecare punct al traseului "warped" față de ultima
        // locație acceptată de Bump (acasă) → toate respinse, niciun footprint.
        val transitionOrigin: DoubleArray? = realStart

        thread = Thread {
            val tickMs = 1000L / tickHz
            val metersPerTick = stepM     // un waypoint per tick → viteză = stepM*tickHz m/s
            pointsDone = 0; lastUiMs = 0L
            var prev: DoubleArray? = transitionOrigin

            if (seek) {
                runSeekAndDestroy(prev, tickMs, speedKmh, metersPerTick)
            } else if (auto) {
                // AUTO-EXTINDERE AUTONOMĂ + SELF-CHECK + AUTO-TUNING:
                //  • spirală pătrată de blocuri (3 km) din anchor (locația ta / Iași), cel-mai-aproape-întâi,
                //    LIMITATĂ la România (RomaniaGeo) — pornește de-acasă din Iași și acoperă toată țara;
                //  • pt. fiecare bloc: măsoară ce e ÎNCĂ blocat (UnlockedMask) → acoperă → re-măsoară cât
                //    a deblocat efectiv (ratio). Blocurile deja 100% deblocate se sar instant;
                //  • SELF-CHECK: dacă ratio prea mic → REACOPERĂ blocul (mai dens/lent) = „se întoarce";
                //  • AUTO-TUNING: CoverageController ajustează singur rowM/viteza ca să maximizeze
                //    teritoriul/secundă păstrând acoperirea ≥ ~90%. Rulează până la STOP sau țară acoperită.
                val anchor = transitionOrigin ?: doubleArrayOf(IASI_LAT, IASI_LON)
                val ctrl = CoverageController(applicationContext, tickHz)
                val mLat = 111_320.0
                val dLatB = AUTO_BLOCK_M / mLat
                val dLonB = AUTO_BLOCK_M / (mLat * cos(Math.toRadians(anchor[0])))
                val maxRing = RomaniaGeo.maxRingBlocks(anchor[0], anchor[1], AUTO_BLOCK_M)
                // O singură citire a măștii ÎNAINTE de buclă; după aceea doar acoperirea de blocuri
                // schimbă setul deblocat, deci refresh-ul de DUPĂ fiecare bloc ține masca la zi.
                // Blocurile sărite (deja deblocate / în afara RO) NU plătesc copierea DB (~ms vs 1-2s).
                UnlockedMask.refresh(applicationContext)
                // RELUARE spirală (autonomie): continuă de la FRONTIERĂ dacă ancora ≈ aceeași casă,
                // nu de la Iași la fiecare repornire (reboot/kill/redeschidere) → fără re-parcurs.
                var x = 0; var y = 0; var dx = 0; var dy = -1; var blockN = 0
                AutoState.loadSpiral(applicationContext, anchor[0], anchor[1])?.let { s ->
                    x = s[0]; y = s[1]; dx = s[2]; dy = s[3]; blockN = s[4]
                }
                while (!stopFlag) {
                    if (max(abs(x), abs(y)) > maxRing) {
                        statusText = "AUTO ✓ România acoperită (%d blocuri)".format(blockN)
                        AutoState.clear(applicationContext)   // gata → nu mai reporni automat
                        break
                    }
                    val cLat = anchor[0] + y * dLatB; val cLon = anchor[1] + x * dLonB
                    // avans spirală ACUM (înainte de orice `continue`) ca să nu buclăm la infinit
                    if (x == y || (x < 0 && x == -y) || (x > 0 && x == 1 - y)) { val n = -dy; dy = dx; dx = n }
                    x += dx; y += dy

                    // sari blocurile complet în afara României (centru + 4 colțuri toate în afară)
                    if (!RomaniaGeo.blockTouches(cLat, cLon, AUTO_BLOCK_M / 2, AUTO_BLOCK_M / 2)) continue
                    blockN++
                    renewWakelock()   // ține CPU treaz pe rulări de zile

                    // WATCHDOG/robustețe: un bloc prost (H3, măsurare) NU trebuie să omoare toată rularea.
                    try {
                        // SELF-CHECK pas 1: ce e ÎNCĂ blocat în interiorul blocului (inset), ÎNAINTE de acoperire.
                        // Folosim masca în memorie (împrospătată după blocul precedent) — fără copiere DB aici.
                        val iLat = (AUTO_BLOCK_M / 2 - AUTO_INSET_M) / mLat
                        val iLon = (AUTO_BLOCK_M / 2 - AUTO_INSET_M) / (mLat * cos(Math.toRadians(cLat)))
                        val expected = UnlockedMask.lockedCellsInBbox(cLat - iLat, cLat + iLat, cLon - iLon, cLon + iLon)
                        if (expected.isEmpty()) {
                            statusText = "AUTO • bloc %d deja deblocat".format(blockN)
                            AutoState.saveSpiral(applicationContext, anchor[0], anchor[1], x, y, dx, dy, blockN)
                            continue
                        }

                        val bz = Zone.fromBbox(cLat - dLatB / 2, cLat + dLatB / 2, cLon - dLonB / 2, cLon + dLonB / 2)
                        val p = ctrl.nextParams()
                        prev = coverBlock(bz, p, prev,
                            "AUTO • bloc %d • %.0fm/%.0fkm/h".format(blockN, p.rowM, p.speedKmh))

                        // SELF-CHECK pas 2: re-măsoară acoperirea reală + lasă controllerul să se auto-acordeze.
                        // Settle: Bump scrie footprint-ul cu mică întârziere → fără pauză am subnumăra → retry fals.
                        try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) {}
                        if (!UnlockedMask.refresh(applicationContext)) {
                            // TOLERANȚĂ: refresh eșuat (root hiccup) → fără măsurătoare validă NU adaptăm și
                            // NU numărăm spre „pipeline mort"; mergem mai departe (fail-safe).
                            statusText = "AUTO • bloc %d acoperit (măsurare indisponibilă)".format(blockN)
                        } else {
                            // AUTO-TUNING: hrănim controllerul DOAR cu acoperirea pasajului RAPID (înainte de
                            // cleanup). Dacă i-am da 100% post-cleanup, ar lărgi rândurile la fiecare bloc →
                            // pasaj rapid tot mai prost, cleanup tot mai lung. Semnalul lui = pasajul rapid.
                            val oc = ctrl.record(expected.size, UnlockedMask.gainedAmong(expected))
                            if (oc.dead) {
                                // NU renunța (autonomie): alertă zgomotoasă + back-off + self-heal, apoi reia.
                                statusText = "⚠ ${oc.status} — reîncerc în 60s"; updateNotif(statusText)
                                reacquireProviders()
                                try { Thread.sleep(DEAD_BACKOFF_MS) } catch (_: InterruptedException) {}
                                UnlockedMask.refresh(applicationContext)
                                ctrl.resetDead()
                            } else {
                                statusText = "AUTO • bloc %d • %s".format(blockN, oc.status)
                                // GARANȚIE 100% („întoarce-te până le acoperă"): pasajul rapid lasă prin design
                                // ~1-2% celule. Acum țintim DIRECT centrul fiecărei celule RĂMASE blocate din
                                // `expected` și o lovim (fix staționar), repetând până blocul e 100% (sau
                                // CLEANUP_MAX_PASSES dacă pipeline-ul ratează unele). NU hrănește tuner-ul.
                                val sp = ctrl.safeParams()
                                prev = cleanupBlockToFull(expected, prev, 1000L / sp.tickHz,
                                    sp.speedKmh, sp.stepM, blockN)
                            }
                        }
                        AutoState.saveSpiral(applicationContext, anchor[0], anchor[1], x, y, dx, dy, blockN)
                    } catch (e: Exception) {
                        android.util.Log.w("MockService", "AUTO bloc $blockN eroare: ${e.message}")
                    }
                }
                if (!stopFlag && !statusText.startsWith("AUTO ✓") && !statusText.startsWith("⚠"))
                    statusText = "AUTO: oprit"
            } else {
                var gen = RouteGenerator(zone, rowM, stepM, vertical, skipUnlocked)
                when {
                    resumeRow >= 0     -> gen.seekToRow(resumeRow.coerceIn(0, gen.totalRows - 1))
                    skipFraction > 0.0 -> gen.seekToRow((skipFraction * gen.totalRows).toInt())
                }
                val firstPt = gen.next()
                prev = if (firstPt != null) {
                    if (transitionOrigin != null) drive(transitionOrigin, firstPt, tickMs, speedKmh, metersPerTick, "apropiere de zonă")
                    firstPt
                } else null
                while (!stopFlag) {
                    val target = gen.next()
                    if (target == null) {
                        if (loop) { gen = RouteGenerator(zone, rowM, stepM, vertical, skipUnlocked); continue }
                        else { statusText = "GATA - zonă acoperită"; break }
                    }
                    curRow = gen.currentRow; progress = gen.progress()
                    val ch = if (flpActive) "GPS" else "?"
                    prev = drive(prev ?: target, target, tickMs, speedKmh, metersPerTick,
                        "rulează • %.1f%% • %s".format(progress * 100, ch))
                }
            }
            // Oprire completă DOAR dacă am ieșit natural, nu dacă am fost înlocuiți de un restart.
            if (!stopFlag) stopEverything()
        }
        thread?.start()
    }

    /**
     * Conduce CONTINUU de la `from` la `to` în pași de `metersPerTick` (interpolare), injectând
     * fiecare punct și dormind `tickMs` între ele — așa Bump vede deplasare fizică reală (nu warp).
     * Actualizează curLat/curLon/pointsDone și (throttled ~3×/s) notificarea. Returnează `to`.
     * Folosit pentru TOT: tranziție inițială, pașii traseului, salturile între blocuri (auto).
     */
    private fun drive(from: DoubleArray, to: DoubleArray, tickMs: Long, speedKmh: Double,
                      metersPerTick: Double, status: String): DoubleArray {
        val distM = RouteGenerator.haversine(from, to)
        val steps = max(1, ceil(distM / metersPerTick).toInt())
        for (s in 1..steps) {
            if (stopFlag) return to
            val t = s.toDouble() / steps
            val lat = from[0] + (to[0] - from[0]) * t
            val lon = from[1] + (to[1] - from[1]) * t
            pushLocation(lat, lon, speedKmh)
            curLat = lat; curLon = lon; pointsDone++
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastUiMs >= 333) {
                lastUiMs = nowMs; statusText = status; updateNotif(status)
            }
            try { Thread.sleep(tickMs) } catch (_: InterruptedException) {}
        }
        return to
    }

    /**
     * Acoperă un bloc (serpentină, sărind MEREU deblocatele) cu setările `p` date de controller.
     * Întoarce ultima poziție (pt. continuitate fizică spre blocul următor — fără teleport).
     */
    private fun coverBlock(bz: Zone, p: CoverageController.Params, prev0: DoubleArray?, status: String): DoubleArray? {
        val tickMs = 1000L / p.tickHz
        val g = RouteGenerator(bz, p.rowM, p.stepM, false, true)   // vertical=false, skipUnlocked=true
        // Materializăm serpentina ca să-i putem alege sensul (vezi mai jos). Doar puncte din zone
        // ÎNCĂ blocate (skipUnlocked) → câteva mii de puncte, cost neglijabil.
        val pts = ArrayList<DoubleArray>()
        var t = g.next()
        while (t != null) { pts.add(t); t = g.next() }
        if (pts.isEmpty()) return prev0
        // CONTINUITATE între blocuri („spirală fără salturi"): serpentina are 2 capete; pornește din
        // cel mai apropiat de unde am rămas (prev0). Dacă ultimul capăt e mai aproape, o parcurgem
        // INVERS (tot serpentină validă, doar în sens opus) → fără „cusătură" lungă de la bloc la bloc.
        if (prev0 != null &&
            RouteGenerator.haversine(prev0, pts.last()) < RouteGenerator.haversine(prev0, pts.first()))
            pts.reverse()
        var prev = prev0
        for (pt in pts) {
            if (stopFlag) break
            prev = drive(prev ?: pt, pt, tickMs, p.speedKmh, p.stepM, status)
        }
        return prev
    }

    /**
     * GARANȚIE 100% per bloc: după pasajul rapid (care lasă prin design ~1-2% goluri), țintește DIRECT
     * centrul fiecărei celule din `expected` care e ÎNCĂ blocată și o „lovește" (câteva fix-uri
     * staționare — mult mai sigur decât o serpentină mai densă, care rămâne statistică). Re-măsoară după
     * fiecare pasaj (self-check): celulele prinse dispar, cele ratate se reîncearcă, până la 0 rămase
     * sau CLEANUP_MAX_PASSES (ex. pipeline care ratează → nu buclăm la infinit). Întoarce ultima poziție.
     */
    private fun cleanupBlockToFull(
        expected: LongArray, prev0: DoubleArray?, tickMs: Long,
        speedKmh: Double, stepM: Double, blockN: Int
    ): DoubleArray? {
        var prev = prev0
        var pass = 0
        // Masca e deja proaspătă (refresh din apelant pt. record) → primul pasaj folosește starea curentă.
        while (!stopFlag && pass < CLEANUP_MAX_PASSES) {
            val centers = UnlockedMask.stillLockedCenters(expected)
            if (centers.isEmpty()) { statusText = "AUTO • bloc %d ✓ 100%%".format(blockN); break }
            val ordered = orderLawnmower(centers)
            for ((idx, h) in ordered.withIndex()) {
                if (stopFlag) break
                if (idx % 16 == 0) renewWakelock()
                prev = drive(prev ?: h, h, tickMs, speedKmh, stepM,
                    "AUTO • bloc %d • umplu găuri %d/%d (pas %d)".format(blockN, idx + 1, ordered.size, pass + 1))
                // „lovește" celula: câteva fix-uri staționare pe centru ca Bump s-o înregistreze sigur
                repeat(SEEK_HITS) {
                    if (!stopFlag) { pushLocation(h[0], h[1], 0.0); try { Thread.sleep(tickMs) } catch (_: InterruptedException) {} }
                }
            }
            // Settle + re-măsoară: găurile lovite dispar din mască → pasajul următor țintește doar ce-a rămas.
            try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) {}
            if (!UnlockedMask.refresh(applicationContext)) break   // fără măsurătoare validă → ieși (fail-safe)
            pass++
        }
        return prev
    }

    /**
     * SEEK & DESTROY: vânează hexagoanele singulare nedeblocate (găuri în interiorul acoperirii) și
     * le deblochează, mergând fizic de la una la alta (fără warp), MEREU la cea mai apropiată de
     * poziția curentă (nearest-first), ca să nu sară în cealaltă parte a țării.
     * După fiecare pasaj RE-SCANEAZĂ (self-check): găurile deblocate dispar, cele ratate se reîncearcă.
     * Se oprește când nu mai rămân găuri sau după SEEK_MAX_PASSES (ex. pipeline mort → găurile persistă).
     */
    private fun runSeekAndDestroy(prev0: DoubleArray?, tickMs: Long, speedKmh: Double, metersPerTick: Double) {
        var prev = prev0
        var pass = 0
        while (!stopFlag && pass < SEEK_MAX_PASSES) {
            renewWakelock()
            statusText = "SEEK • scanez găuri..."; updateNotif(statusText)
            UnlockedMask.refresh(applicationContext)
            val holes = UnlockedMask.isolatedLockedHoles()
            if (holes.isEmpty()) { statusText = "SEEK ✓ fără găuri rămase (pasaj $pass)"; break }
            // Nearest-first din POZIȚIA CURENTĂ: mergi mereu la cea mai apropiată gaură, apoi re-țintește
            // de-acolo cea mai apropiată ș.a.m.d. — fără salturi în cealaltă parte a țării (≪ timp).
            val ordered = orderNearestFirst(prev, holes)
            val total = ordered.size
            for ((idx, h) in ordered.withIndex()) {
                if (stopFlag) break
                if (idx % 16 == 0) renewWakelock()
                prev = drive(prev ?: h, h, tickMs, speedKmh, metersPerTick,
                    "🎯 SEEK • pasaj %d • %d/%d găuri".format(pass + 1, idx + 1, total))
                // „lovește" celula: câteva fix-uri staționare pe centru ca Bump s-o înregistreze sigur
                repeat(SEEK_HITS) {
                    if (!stopFlag) { pushLocation(h[0], h[1], 0.0); try { Thread.sleep(tickMs) } catch (_: InterruptedException) {} }
                }
            }
            pass++
        }
        if (!stopFlag && !statusText.startsWith("SEEK ✓")) statusText = "SEEK: oprit"
    }

    /**
     * Ordonează găurile prin nearest-neighbor lacom pornind din `start` (poziția curentă): la fiecare
     * pas alege gaura cea mai apropiată de unde ești ACUM. Minimizează drumul total — botul nu mai e
     * trimis în cealaltă parte a țării ca la traseul pe benzi. Distanță planară ieftină (equirectangular,
     * fără trig per comparație) — suficient pt. „cel mai apropiat". O(N²), dar N (găuri izolate) e mic.
     */
    private fun orderNearestFirst(start: DoubleArray?, holes: List<DoubleArray>): List<DoubleArray> {
        if (holes.size <= 1) return holes
        val remaining = ArrayList(holes)
        val out = ArrayList<DoubleArray>(holes.size)
        var cur = start ?: remaining.removeAt(remaining.size - 1).also { out.add(it) }
        val kLon = cos(Math.toRadians(cur[0]))   // scalare longitudine→metri la latitudinea zonei
        while (remaining.isNotEmpty()) {
            var bestIdx = 0; var bestD = Double.MAX_VALUE
            for (i in remaining.indices) {
                val h = remaining[i]
                val dLat = h[0] - cur[0]; val dLon = (h[1] - cur[1]) * kLon
                val d = dLat * dLat + dLon * dLon
                if (d < bestD) { bestD = d; bestIdx = i }
            }
            cur = remaining.removeAt(bestIdx)
            out.add(cur)
        }
        return out
    }

    /** Ordonează găurile pe un traseu boustrophedon (benzi de latitudine, direcție alternantă). */
    private fun orderLawnmower(holes: List<DoubleArray>): List<DoubleArray> {
        if (holes.size <= 2) return holes
        val binDeg = 0.02   // ~2.2 km lățime bandă
        return holes.sortedWith(compareBy(
            { floor(it[0] / binDeg).toInt() },
            { if (floor(it[0] / binDeg).toInt() % 2 == 0) it[1] else -it[1] }
        ))
    }

    private fun pushLocation(lat: Double, lon: Double, speedKmh: Double) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        prevLat = lat; prevLon = lon
        // Câmpuri EXACT ca Lockito (decompilat s5.b.a): doar time, lat/lon, altitude, speed,
        // accuracy, elapsedRealtimeNanos. FĂRĂ bearing, FĂRĂ sub-acurateți, FĂRĂ extras —
        // câmpurile în plus probabil derutau calculul de viteză al Bump.
        fun fill(loc: Location) = loc.apply {
            time = now
            latitude = lat; longitude = lon; altitude = 3.0
            speed = (speedKmh / 3.6).toFloat()
            accuracy = 3.0f
            elapsedRealtimeNanos = elapsed
        }
        // Injecție DOAR prin LocationManager test providers (Lockito-style). GMS fuzionează GPS-ul
        // în FLP fără rate-limit "too fast" → permite viteze mari (500+ km/h). FĂRĂ FLP setMockLocation.
        var anyOk = false
        for (p in activeProviders) {
            try { lm.setTestProviderLocation(p, fill(Location(p))); anyOk = true } catch (_: Exception) {}
        }
        // SELF-HEAL: dacă sistemul a scos test provider-ul (toate injecțiile pică), re-adaugă-l
        // automat după un prag de eșecuri consecutive — fără asta botul „conduce" fără să injecteze.
        if (anyOk || activeProviders.isEmpty()) {
            injectFails = 0
            lastProgressMs = SystemClock.elapsedRealtime()
        } else {
            if (++injectFails >= INJECT_FAIL_LIMIT) { injectFails = 0; reacquireProviders() }
        }
    }

    /** Re-înregistrează test providerii (self-heal) dacă sistemul i-a scos în timpul rulării. */
    private fun reacquireProviders() {
        val want = if (activeProviders.isEmpty()) listOf(LocationManager.GPS_PROVIDER) else activeProviders.toList()
        for (p in want) { try { addMockProvider(p); if (p !in activeProviders) activeProviders.add(p) } catch (_: Exception) {} }
    }

    /** Reînnoiește wakelock-ul (resetează timeout-ul) — pt. rulări de zile fără adormirea CPU. */
    private fun renewWakelock() {
        try { wakeLock?.acquire(WAKELOCK_MS) } catch (_: Exception) {}
    }

    /**
     * Watchdog: dacă progresul (pointsDone via lastProgressMs) stagnează cât rulăm (nu parcați),
     * cea mai probabilă cauză e pierderea test provider-ului → forțează self-heal (re-adaugă) +
     * reînnoiește wakelock-ul. Un singur thread ușor, verifică la 30s. Nu omoară thread-ul de
     * injecție (riscant); doar repară cauza, iar acesta reia singur.
     */
    private fun startWatchdog() {
        stopWatchdog()
        watchdogStop = false
        lastProgressMs = SystemClock.elapsedRealtime()
        watchdog = Thread {
            while (!watchdogStop) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                if (watchdogStop || !running || holding) continue
                val idle = SystemClock.elapsedRealtime() - lastProgressMs
                if (idle > STALL_MS) {
                    reacquireProviders()
                    renewWakelock()
                    lastProgressMs = SystemClock.elapsedRealtime()  // dă-i o nouă fereastră
                }
            }
        }.also { it.start() }
    }

    private fun stopWatchdog() {
        watchdogStop = true
        watchdog?.interrupt()
        watchdog = null
    }

    /**
     * Eliberează resursele de mock (wakelock, test provider gps, opțional FLP mock mode).
     * `stopMockMode=false` la repornire: păstrăm FLP în mock mode ca să nu facem
     * off→on inutil (race). `stopMockMode=true` la oprire reală.
     */
    private fun teardownMock(stopMockMode: Boolean) {
        stopWatchdog()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        if (stopMockMode) {
            flpActive = false
            try { fusedClient.setMockMode(false) } catch (_: Exception) {}
        }
        for (p in activeProviders) {
            try { lm.setTestProviderEnabled(p, false) } catch (_: Exception) {}
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
        }
        activeProviders.clear()
    }

    /**
     * Înregistrează un test provider COPIIND proprietățile reale ale providerului (ca Lockito
     * n5.r.f): dacă providerul există, oglindim requiresNetwork/Satellite/Cell, supports*,
     * power, accuracy reale; altfel fallback (true,true,false,false,true,true,true,HIGH,FINE).
     * Așa "network" nu mai pretinde greșit satelit/FINE. Remove-first + retry.
     */
    private fun addMockProvider(p: String, maxRetry: Int = 3) {
        var lastErr: Exception? = null
        repeat(maxRetry) {
            try {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= 31) {
                    val pr = try { lm.getProviderProperties(p) } catch (_: Exception) { null }
                    if (pr != null) lm.addTestProvider(
                        p, pr.hasNetworkRequirement(), pr.hasSatelliteRequirement(),
                        pr.hasCellRequirement(), pr.hasMonetaryCost(), pr.hasAltitudeSupport(),
                        pr.hasSpeedSupport(), pr.hasBearingSupport(), pr.powerUsage, pr.accuracy)
                    else lm.addTestProvider(p, true, true, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE)
                } else {
                    @Suppress("DEPRECATION")
                    val lp = try { lm.getProvider(p) } catch (_: Exception) { null }
                    @Suppress("DEPRECATION")
                    if (lp != null) lm.addTestProvider(
                        p, lp.requiresNetwork(), lp.requiresSatellite(), lp.requiresCell(),
                        lp.hasMonetaryCost(), lp.supportsAltitude(), lp.supportsSpeed(),
                        lp.supportsBearing(), lp.powerRequirement, lp.accuracy)
                    else lm.addTestProvider(p, true, true, false, false, true, true, true, 3, 1)
                }
                lm.setTestProviderEnabled(p, true)
                return
            } catch (e: Exception) { lastErr = e }
        }
        throw SecurityException("Mock provider eșuat ($p): ${lastErr?.message}")
    }

    /** Oprește complet thread-ul de injecție anterior (sincron) înainte de o repornire. */
    private fun stopPreviousRun() {
        stopFlag = true
        thread?.interrupt()
        try { thread?.join(3000) } catch (_: InterruptedException) {}
        thread = null
        teardownMock(stopMockMode = false)
    }

    private fun stopEverything() {
        stopFlag = true
        running = false
        stopHold()
        prevLat = Double.NaN; prevLon = Double.NaN
        teardownMock(stopMockMode = true)
        if (statusText.startsWith("rulează") || statusText.startsWith("⏸")) statusText = "oprit"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * PAUZĂ (butonul Stop): oprește mișcarea dar rămâne „parcat" pe ultima poziție — un thread de
     * hold reinjectează aceeași locație prin FLP, deci Bump NU revine acasă. Reluarea continuă de aici.
     */
    private fun pauseRoute() {
        if (!running && !holding) { stopEverything(); return }
        stopFlag = true
        thread?.interrupt()
        try { thread?.join(3000) } catch (_: InterruptedException) {}
        thread = null
        running = false
        persistResume()
        startHold()
    }

    private fun startHold() {
        if (curLat == 0.0 && curLon == 0.0) { stopEverything(); return }
        stopHold()
        holdStop = false
        holding = true
        statusText = "⏸ parcat"
        updateNotif("⏸ parcat • apasă START ca să continui (long-press Stop = oprire completă)")
        val lat = curLat; val lon = curLon
        holdThread = Thread {
            while (!holdStop) {
                pushLocation(lat, lon, 0.0)   // staționar, viteză 0
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }.also { it.start() }
    }

    private fun stopHold() {
        holdStop = true
        holdThread?.interrupt()
        try { holdThread?.join(2000) } catch (_: InterruptedException) {}
        holdThread = null
        holding = false
    }

    /** Salvează poziția + rândul curent ca să putem relua exact de aici (supraviețuiește repornirii procesului). */
    private fun persistResume() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("valid", true)
            .putLong("lat", curLat.toRawBits())
            .putLong("lon", curLon.toRawBits())
            .putInt("row", curRow)
            .putString("key", curPolyKey)
            .apply()
    }

    private fun clearResume() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    override fun onDestroy() {
        stopFlag = true
        stopHold()
        teardownMock(stopMockMode = true)
        super.onDestroy()
    }

    // Dacă serviciul e omorât când userul dă swipe din recents, se repornește după 1s.
    // Nu se declanșează când userul apasă STOP explicit (stopSelf() nu cheamă onTaskRemoved).
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (running) {
            val restart = PendingIntent.getService(
                this, 1,
                Intent(applicationContext, MockService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
                .set(android.app.AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000L, restart)
        }
        super.onTaskRemoved(rootIntent)
    }

    // ---- notificare foreground ----
    private fun createChannel() {
        val ch = NotificationChannel(CH_ID, "Moonwalker", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
    }
    private fun buildNotif(text: String): Notification {
        val pausePi = PendingIntent.getService(this, 0,
            Intent(this, MockService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val releasePi = PendingIntent.getService(this, 2,
            Intent(this, MockService::class.java).apply { action = ACTION_RELEASE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Moonwalker rulează")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Pauză (parcat)", pausePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop (acasă)", releasePi)
            .build()
    }
    private fun updateNotif(text: String) {
        (getSystemService(NotificationManager::class.java)).notify(NOTIF_ID, buildNotif(text))
    }
}
