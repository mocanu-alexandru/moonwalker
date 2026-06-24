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
        const val EXTRA_WORLD_TOUR = "worldTour"         // TUR CAPITALE: conduce prin capitalele lumii (nearest-first + 2-opt)
        const val EXTRA_TOUR_RESUME_CAP = "tourResumeCap" // TUR: continuă DUPĂ această capitală (ex. ultima deblocată), o singură dată
        private const val H3_RES10_AREA_M2 = 15047.0     // aria medie a unei celule H3 res-10 (≈0.0150 km²)
        private const val FRESH_LOCKED_FRACTION = 0.5    // ≥ atât din județ încă blocat = „proaspăt" → pătrate concentrice (blast); sub = serpentină doar peste celulele blocate
        // CALIBRARE (o dată per device, la primul start AUTO): măsoară poarta reală a Bump — e limitat de
        // VITEZĂ sau de DWELL (fix-uri/celulă)? — pe câteva petice mici proaspete, apoi aplică tickHz+pas sigure.
        private const val CALIB_TRIALS = 7                // câte configurări testăm (1 control + 1 dwell + 5 viteze)
        private const val CALIB_MIN = 50                  // celule blocate minime într-un patch ca eșantionul să fie valid
        private const val CALIB_PATCH_DEG = 0.013         // ~1.4 km latura unui patch de test
        private const val CALIB_MAX_RING = 12             // câte inele de petice scanăm spre exterior până renunțăm
        private const val CALIB_PASS = 0.95               // ratio sub care un config e considerat „peste poarta Bump"
        private const val SETTLE_MS = 3000L              // pauză înainte de citirea footprint-ului (Bump scrie cu mică întârziere)
        private const val WAKELOCK_MS = 60 * 60 * 1000L  // timeout wakelock; reînnoit per bloc (renewWakelock)
        private const val STALL_MS = 90_000L             // fără progres atâta timp → watchdog face self-heal
        private const val INJECT_FAIL_LIMIT = 30         // injecții consecutive eșuate → re-adaugă providerii
        private const val DEAD_BACKOFF_MS = 60_000L      // pauză după „pipeline mort" înainte de reîncercare
        private const val SEEK_MAX_PASSES = 4            // pasaje seek & destroy (re-scanare găuri) înainte de oprire
        private const val SEEK_HITS = 3                  // câte fix-uri staționare „lovesc" fiecare gaură
        private const val CLEANUP_MAX_PASSES = 3         // pasaje „garanție 100%" per bloc (țintire directă a celulelor rămase)
        private const val STUCK_MS = 8 * 60_000L         // poziția nu iese din raza STUCK_RADIUS atâta timp → „blocat" → restart
        private const val STUCK_RADIUS_M = 250.0         // sub atâta deplasare = considerat pe loc (sub o latură de bloc)
        private const val RESTART_MAX_STREAK = 3         // restart-uri consecutive la același bloc → nu mai hamerui (back-off + alertă)
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
    // Watcher „blocat într-o zonă": ultima dată când poziția chiar a ieșit din rază + punctul de referință.
    @Volatile private var lastMoveMs = 0L
    private var wpLat = Double.NaN; private var wpLon = Double.NaN
    @Volatile private var autoBlockN = 0   // blocul curent din spirală (pt. loop-guard-ul restart-ului)
    @Volatile private var cleanupActive = false   // cleanup pe un cluster strâns (lovituri staționare) → poziția
                                                  // stă legitim în <STUCK_RADIUS → watcher-ul NU trebuie să dea restart fals

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
        val worldTour = intent?.getBooleanExtra(EXTRA_WORLD_TOUR, false) ?: false
        val tourResumeCap = intent?.getStringExtra(EXTRA_TOUR_RESUME_CAP)
        // Auto/seek sar/folosesc masca → are nevoie de ea. Asigură un set proaspăt înainte de rulare.
        if ((skipUnlocked || auto || seek) && !UnlockedMask.isReady) UnlockedMask.refresh(applicationContext)
        val polyStr = intent?.getStringExtra(EXTRA_POLY)

        // Repornire fără date (intent gol de la sticky/onTaskRemoved) → nu porni traseu bogus.
        // UI-ul trimite mereu EXTRA_POLY; lipsa lui = restart de sistem, nu start real.
        if (polyStr.isNullOrBlank()) {
            // AUTONOMIE: dacă AUTO era activ și nu rulăm acum (proces omorât/restart sticky),
            // reia AUTO singur din parametrii persistați (reia de la județul neterminat, nu din Iași).
            if (!running && !holding && AutoState.isActive(applicationContext)) {
                val savedPoly = AutoState.poly(applicationContext)
                if (!savedPoly.isNullOrBlank()) {
                    val z = Zone.fromPolygon(parsePoly(savedPoly))
                    curPolyKey = savedPoly
                    // ANCORĂ la RESUME/RESTART: preferă ancora SALVATĂ (= originea blast-radius), NU
                    // getLastKnownLocation — după teardown-ul mock-ului last-known poate fi încă poziția
                    // MOCK; dacă e >4km de ancoră, countyIndex n-ar potrivi → ordinea județelor s-ar
                    // recalcula din alt punct. Cu ancora salvată, originea e identică → reia exact județul.
                    val rs: DoubleArray? = AutoState.anchor(applicationContext) ?: try {
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

        // „Pornire la rece" = NU rulăm și nu suntem parcați în memorie (proces proaspăt / alt mod).
        // Resume GENUIN = parcat/rulează cu poziție curentă reală (continuă din traseu, în memorie).
        val coldStart = !((running || holding) && (curLat != 0.0 || curLon != 0.0))

        // Persistă ULTIMA LOCAȚIE GPS REALĂ (doar la rece, mock oprit) = „ultimul hexagon deblocat".
        // O folosim ca origine pt. TUR după un restart de proces, fără a recurge la „acasă".
        if (coldStart) validGeo(realStart)?.let {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("gpsLat", it[0].toRawBits()).putLong("gpsLon", it[1].toRawBits())
                .putBoolean("gpsValid", true).apply()
        }

        // RELUARE de unde am rămas (fără revenire acasă):
        //  - origine = ultima poziție (dacă suntem parcați/rulăm, sau din prefs după restart proces)
        //  - rând = progresul salvat, DOAR dacă reluăm același traseu (același poly)
        var resumeOrigin: DoubleArray? = null
        var resumeRow = -1
        if (!coldStart) {
            resumeOrigin = validGeo(doubleArrayOf(curLat, curLon))
            if (curPolyKey == polyStr) resumeRow = curRow
        } else {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (p.getBoolean("valid", false)) {
                resumeOrigin = validGeo(doubleArrayOf(
                    Double.fromBits(p.getLong("lat", 0L)),
                    Double.fromBits(p.getLong("lon", 0L))))
                if (p.getString("key", "") == polyStr) resumeRow = p.getInt("row", 0)
            }
        }
        curPolyKey = polyStr

        // Ultima locație GPS reală persistată (origine pt. TUR la rece, după restart de proces).
        val lastGps = getSharedPreferences(PREFS, MODE_PRIVATE).let { p ->
            if (p.getBoolean("gpsValid", false)) validGeo(doubleArrayOf(
                Double.fromBits(p.getLong("gpsLat", 0L)),
                Double.fromBits(p.getLong("gpsLon", 0L)))) else null
        }

        val originForRun: DoubleArray?
        if (worldTour) {
            // TUR CAPITALE — pleacă de la ULTIMUL HEXAGON DEBLOCAT / ULTIMA LOCAȚIE GPS, niciodată
            // „acasă" sau [0,0]:
            //  • la rece (proces nou / vii din AUTO) → poziția curentă reală sau ultimul GPS salvat,
            //    și RE-ANCORĂM (resumeRow=-1) ca să recalculăm ordinea din locul tău real;
            //  • resume GENUIN (parcat) → continuă pe traseul curent, fără repetare.
            originForRun = if (coldStart) {
                resumeRow = -1
                validGeo(realStart) ?: lastGps ?: resumeOrigin
            } else {
                resumeOrigin ?: validGeo(realStart) ?: lastGps
            }
        } else {
            originForRun = resumeOrigin ?: validGeo(realStart)
        }

        startForeground(NOTIF_ID, buildNotif("pornire..."))
        startWalking(zone, tickHz, rowM, stepM, vertical, loop, skipFraction,
            originForRun, skipUnlocked, resumeRow, auto, seek, worldTour, tourResumeCap)
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
        auto: Boolean = false, seek: Boolean = false, worldTour: Boolean = false,
        tourResumeCap: String? = null
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
            } else if (worldTour) {
                // TUR CAPITALE — vezi runWorldTour(). Pură călătorie (fără mască/calibrare/cleanup).
                runWorldTour(transitionOrigin, tickMs, speedKmh, metersPerTick, loop, resumeRow)
            } else if (auto) {
                // MOD AUTONOM „BLAST RADIUS pe JUDEȚE" — vezi runAutoCounties().
                runAutoCounties(transitionOrigin, tickHz)
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
            // Reînnoiește wakelock-ul periodic CHIAR în bucla de drive: un singur leg poate dura ore
            // (turul capitalelor traversează oceane — mii de km la rând). Apelanții normali au legi
            // scurte și renew extern, deci asta e inofensiv pentru ei; pentru tur e vital.
            if (s % 256 == 0) renewWakelock()
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
     * MOD AUTONOM „BLAST RADIUS pe JUDEȚE": descoperă în PĂTRATE CONCENTRICE tot mai mari din primul fix
     * GPS, JUDEȚ cu JUDEȚ (nearest-first), cu GARANȚIE 100% pe celulele fiecărui județ + BACKSTOP de
     * cusătură. O singură undă continuă din centru spre margine (FĂRĂ benzi → fără frânat/cleanup la
     * fiecare 3 km, fără întoarceri care lasă goluri): cel mai rapid mod de a acoperi toată suprafața.
     *
     * Pt. fiecare județ, în ordine nearest-first din origine:
     *   1. măsoară celulele ÎNCĂ blocate din POLIGONUL județului (`expected`);
     *   2. baleiere: PROASPĂT (≥ FRESH_LOCKED_FRACTION blocat) → `coverRings` (pătrate concentrice din
     *      centru, RingSpiralGenerator, clip la poligon, sare deblocatele); PARȚIAL → `coverLockedDirect`
     *      (serpentină doar peste celulele blocate, fără traversat tot golul deblocat al județului);
     *   3. settle → re-măsoară → AUTO-TUNING o dată per JUDEȚ (pe pasajul rapid, înainte de cleanup);
     *   4. GARANȚIE 100%: țintește direct fiecare celulă rămasă blocată (`cleanupBlockToFull`, determinist);
     *   5. BACKSTOP cusături: găuri izolate din bbox-ul județului (orfani de graniță / slivere) → țintite.
     * Reia de la județul neterminat (AutoState.countyIndex). Originea persistată → ordine deterministă.
     */
    private fun runAutoCounties(origin0: DoubleArray?, tickHz: Int) {
        val origin = origin0 ?: doubleArrayOf(IASI_LAT, IASI_LON)
        UnlockedMask.refresh(applicationContext)

        // CALIBRARE o dată per device (auto + aplicare CONSERVATOARE): măsoară poarta Bump și seedează DOAR
        // pasul tuner-ului (nu tickHz → podeaua rămâne sub 540, mereu recuperabil). Persistat în mw_autotune
        // (`step` = sămânță citită de CoverageController la init). Plasă: dead pe calibrare → revin la soft-spot.
        val prefs = applicationContext.getSharedPreferences("mw_autotune", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("calib_done", false) && !stopFlag) {
            val res = runCalibration(origin, tickHz)
            if (res != null) {
                prefs.edit()
                    .putBoolean("calib_done", true)
                    .putFloat("step", res.seedStep.toFloat())     // sămânță pt. tuner (baseStep)
                    .putString("calib_verdict", res.verdict)
                    .apply()
                statusText = "CALIBRARE ✓ ${res.verdict}"; updateNotif(statusText)
            }
        }

        val ctrl = CoverageController(applicationContext, tickHz)

        val counties = orderCountiesNearestFirst(origin)
        if (counties.isEmpty()) { statusText = "AUTO: fără județe (Counties gol?)"; return }

        // RELUARE: sări județele deja terminate. Persistă ORIGINEA imediat → AutoState.anchor() proaspătă
        // din start (ordinea județelor e deterministă din origine, deci indexul rămâne valid la restart).
        val startIdx = AutoState.countyIndex(applicationContext, origin[0], origin[1])
        AutoState.saveCounty(applicationContext, origin[0], origin[1], startIdx)

        var prev: DoubleArray? = origin
        var ci = startIdx
        var calibReverted = false   // plasă: primul „dead" cât e calibrarea aplicată ⇒ viteză prea mare, nu pipeline mort
        while (ci < counties.size && !stopFlag) {
            val name = counties[ci]
            val poly = Counties.polygon(name)
            if (poly == null || poly.size < 3) { ci++; continue }
            val zone = Zone.fromPolygon(poly, name)
            autoBlockN = ci   // expune unitatea curentă pt. watcher-ul „blocat" / loop-guard restart
            renewWakelock()
            try {
                // (1) ce e ÎNCĂ blocat în județ
                val expected = UnlockedMask.lockedCellsInPolygon(poly)
                if (expected.isEmpty()) {
                    statusText = "AUTO • %s deja deblocat (%d/%d)".format(name, ci + 1, counties.size)
                    AutoState.saveCounty(applicationContext, origin[0], origin[1], ci + 1); ci++; continue
                }

                // (2) BALEIERE în PĂTRATE CONCENTRICE din centru (blast-radius CONTINUU — o singură undă din
                // mijloc spre margine, fără benzi/frânat). PROASPĂT (≥ FRESH_LOCKED_FRACTION blocat) →
                // `coverRings` (RingSpiralGenerator). PARȚIAL → `coverLockedDirect` (serpentină doar peste
                // celulele blocate, fără traversat tot golul). Centru: jud. de ACASĂ (ci==0) → originea GPS;
                // restul → centroidul (inelele pornesc din interiorul județului, nu din `prev` aflat departe).
                val p = ctrl.nextParams()
                val lockedFraction = expected.size / max(1.0, polygonCellCapacity(poly, zone))
                prev = if (lockedFraction >= FRESH_LOCKED_FRACTION) {
                    val center = if (ci == 0) origin else centroid(poly)
                    coverRings(zone, center[0], center[1], p, prev,
                        "AUTO • %s (%d/%d) • blast %.0fm/%.0fkm/h".format(name, ci + 1, counties.size, p.rowM, p.speedKmh))
                } else {
                    coverLockedDirect(expected, p, prev,
                        "AUTO • %s (%d/%d) • țintit %.0f%% blocat".format(name, ci + 1, counties.size, lockedFraction * 100))
                }

                // (3) settle → re-măsoară → AUTO-TUNING o dată per JUDEȚ (pe pasajul RAPID, înainte de cleanup)
                try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) {}
                if (!UnlockedMask.refresh(applicationContext)) {
                    statusText = "AUTO • %s acoperit (măsurare indisponibilă)".format(name)
                } else {
                    val oc = ctrl.record(expected.size, UnlockedMask.gainedAmong(expected))
                    if (oc.dead) {
                        // PLASĂ calibrare: calibrarea tocmai a dovedit pipeline-ul VIU (a deblocat petice),
                        // deci un „dead" acum = viteză prea agresivă, nu pipeline mort. Revino la soft-spot
                        // (≈540), șterge calibrarea, reia județul curat. Doar dacă și soft-spot-ul moare după
                        // → e pipeline mort real (alerta de mai jos). (Și pe RESUME: o calibrare persistată
                        // proastă se autocorectează la fel.)
                        if (!calibReverted && prefs.getBoolean("calib_done", false)) {
                            ctrl.resetToSoftSpot()
                            prefs.edit().putBoolean("calib_done", false).remove("calib_verdict").apply()
                            calibReverted = true
                            statusText = "⚠ calibrare prea agresivă → revin la soft-spot (≈540 km/h)"; updateNotif(statusText)
                            UnlockedMask.refresh(applicationContext)
                            continue   // ci neschimbat → reia județul la viteză sigură
                        }
                        // NU renunța (autonomie): alertă + back-off + self-heal, apoi REIA același județ.
                        statusText = "⚠ ${oc.status} — reîncerc în 60s"; updateNotif(statusText)
                        reacquireProviders()
                        try { Thread.sleep(DEAD_BACKOFF_MS) } catch (_: InterruptedException) {}
                        UnlockedMask.refresh(applicationContext); ctrl.resetDead()
                        continue   // ci neschimbat → reîncearcă județul
                    }
                    statusText = "AUTO • %s • %s".format(name, oc.status)
                    // (4) GARANȚIE 100% pe celulele județului + (5) BACKSTOP cusături „între zone"
                    val sp = ctrl.safeParams()
                    prev = cleanupBlockToFull(expected, prev, 1000L / sp.tickHz, sp.speedKmh, sp.stepM, ci + 1)
                    prev = cleanupSeams(zone, prev, sp, ci + 1)
                }
                AutoState.saveCounty(applicationContext, origin[0], origin[1], ci + 1)
            } catch (e: Exception) {
                android.util.Log.w("MockService", "AUTO județ $name eroare: ${e.message}")
            }
            ci++
        }
        if (!stopFlag) {
            statusText = "AUTO ✓ România acoperită (%d județe)".format(counties.size)
            AutoState.clear(applicationContext)   // gata → nu mai reporni automat
        }
    }

    /**
     * Ordonează județele nearest-first din origine (greedy pe centroizi, distanță planară ieftină).
     * Județul care CONȚINE originea e forțat PRIMUL (acolo blast-radius pornește din punctul tău GPS,
     * centrat pe GPS — vezi runAutoCounties; altfel originea ar cădea în afara primului județ și ar
     * genera conectori inutili). Determinist (origine + listă fixă) → reluarea pe index e validă.
     */
    private fun orderCountiesNearestFirst(origin: DoubleArray): List<String> {
        val cents = LinkedHashMap<String, DoubleArray>()
        var home: String? = null
        for (n in Counties.names()) Counties.polygon(n)?.let { poly ->
            if (poly.isNotEmpty()) {
                cents[n] = centroid(poly)
                if (home == null && Zone.fromPolygon(poly).contains(origin[0], origin[1])) home = n
            }
        }
        val remaining = ArrayList(cents.keys)
        val out = ArrayList<String>(remaining.size)
        var cur = origin
        val kLon = cos(Math.toRadians(origin[0]))
        home?.let { remaining.remove(it); out.add(it); cur = cents[it]!! }   // județul de acasă primul
        while (remaining.isNotEmpty()) {
            var bestI = 0; var bestD = Double.MAX_VALUE
            for (i in remaining.indices) {
                val c = cents[remaining[i]]!!
                val dLat = c[0] - cur[0]; val dLon = (c[1] - cur[1]) * kLon
                val d = dLat * dLat + dLon * dLon
                if (d < bestD) { bestD = d; bestI = i }
            }
            val name = remaining.removeAt(bestI)
            out.add(name); cur = cents[name]!!
        }
        return out
    }

    private fun centroid(poly: List<DoubleArray>): DoubleArray {
        var la = 0.0; var lo = 0.0
        for (p in poly) { la += p[0]; lo += p[1] }
        return doubleArrayOf(la / poly.size, lo / poly.size)
    }

    /**
     * Acoperă un județ cu pattern-ul BLAST RADIUS — PĂTRATE CONCENTRICE tot mai mari din `(cLat,cLon)`,
     * clipate la poligon, sărind deblocatele (RingSpiralGenerator). O singură undă continuă din centru
     * spre margine → drumul radiază din punctul tău, deblochezi întâi ce-i lângă tine. Întoarce ultima
     * poziție (continuitate fizică).
     *
     * TOT (inclusiv salturile peste goluri deblocate / arce non-poligon) e condus la `p.speedKmh` = viteza
     * de acoperire pe care TUNER-ul a găsit-o sigură (acceptată de Bump). NU folosim o viteză de conector
     * fixă mai mare: ea ocolea limita tuner-ului → peste poarta Bump → poziții respinse ca „warp" → 0
     * deblocări. `drive` interpolează la stepM, deci golurile rămân traversate continuu (fără teleport).
     */
    private fun coverRings(zone: Zone, cLat: Double, cLon: Double, p: CoverageController.Params,
                           prev0: DoubleArray?, status: String): DoubleArray? {
        val tickMs = 1000L / p.tickHz
        val g = RingSpiralGenerator(zone, cLat, cLon, p.rowM, p.stepM, skipUnlocked = true)
        var prev = prev0
        var i = 0
        var pt = g.next()
        while (pt != null && !stopFlag) {
            if (i++ % 64 == 0) renewWakelock()
            prev = drive(prev ?: pt, pt, tickMs, p.speedKmh, p.stepM, status)
            pt = g.next()
        }
        return prev
    }

    /**
     * Serpentină DIRECTĂ peste celulele blocate (rutare „doar prin nedeblocat", minim de deblocat
     * traversat): centrele celulelor `expected` ordonate boustrophedon (benzi de latitudine, local) și
     * conduse CONTINUU la `p.speedKmh` (NU fix-uri staționare — acoperim trecând prin ele). Pt. județe
     * majoritar deblocate, unde inelele ar traversa toată lățimea județului ca să atingă câteva petice.
     * Întoarce ultima poziție.
     */
    private fun coverLockedDirect(expected: LongArray, p: CoverageController.Params,
                                  prev0: DoubleArray?, status: String): DoubleArray? {
        val tickMs = 1000L / p.tickHz
        val ordered = orderLawnmower(UnlockedMask.cellsToCenters(expected))
        var prev = prev0
        for ((idx, h) in ordered.withIndex()) {
            if (stopFlag) break
            if (idx % 64 == 0) renewWakelock()
            prev = drive(prev ?: h, h, tickMs, p.speedKmh, p.stepM, status)
        }
        return prev
    }

    /** Estimează câte celule H3 res-10 încap în poligon (arie shoelace în metri / aria unei celule). */
    private fun polygonCellCapacity(poly: List<DoubleArray>, zone: Zone): Double {
        val mLat = 111_320.0
        val mLon = mLat * cos(Math.toRadians((zone.latMin + zone.latMax) / 2.0))
        var a2 = 0.0
        for (i in poly.indices) {
            val j = (i + 1) % poly.size
            val x1 = poly[i][1] * mLon; val y1 = poly[i][0] * mLat
            val x2 = poly[j][1] * mLon; val y2 = poly[j][0] * mLat
            a2 += x1 * y2 - x2 * y1
        }
        return abs(a2) / 2.0 / H3_RES10_AREA_M2
    }

    private data class CalibResult(val seedStep: Double, val verdict: String)

    /**
     * CALIBRARE (o dată per device): măsoară EMPIRIC poarta Bump în loc s-o ghicim (vezi 4891e40 — 1080
     * ghicit a dat 0 deblocări). Întrebarea-cheie: Bump e limitat de VITEZĂ sau de DWELL (câte fix-uri în
     * celulă)? — arată identic la un punct, dar cer strategii opuse. Bateria, pe petice mici PROASPETE:
     *   • CONTROL  (tickHz, pas 25 → ~540 km/h, ~5 fix/cel) vs DWELL (2×tickHz, pas 12.5 → ~540 km/h,
     *     ~10 fix/cel): aceeași viteză, dublu fix-uri. DWELL >> CONTROL ⇒ limitat de fix-uri (tickHz e pârghia).
     *   • SCAN VITEZĂ (pas 20 fix, tickHz crescător 8→20 ⇒ 576…1440 km/h, fix/cel ≈ const): cea mai mare
     *     viteză cu ratio ≥ CALIB_PASS = poarta reală.
     * APLICĂ (auto, CONSERVATOR): NU schimbă tickHz (rămâne baza) — altfel s-ar fixa podeaua tuner-ului
     * (STEP_MIN×tickHz) PESTE soft-spot-ul sigur de 540, iar un pass spurios la Hz mare ar bloca toată țara
     * prea repede → 0 deblocări → „PIPELINE MORT" perpetuu, irecuperabil. În schimb produce doar un PAS-SĂMÂNȚĂ
     * pt. tuner, ∈[soft-spot 25, STEP_MAX 50] (≡ 540…1080 km/h la baza tickHz), cu margine 0.85 pe poartă.
     * Tuner-ul poate apoi urca SAU coborî (podeaua rămâne 324 km/h) → mereu există drum înapoi la sigur.
     * Dacă e DWELL-limit (a accelera cere mai multe fix-uri = tickHz↑, ce evităm pt. siguranță) → rămâne la
     * soft-spot. Plasă: dacă primul județ moare cu calibrarea aplicată, runAutoCounties revine la soft-spot.
     * Sigur prin design: un patch testat „peste poartă" doar nu se deblochează (se acoperă normal după).
     * Întoarce null dacă nu găsește teren blocat de măsurat (reia data viitoare).
     */
    private fun runCalibration(origin: DoubleArray, baseTickHz: Int): CalibResult? {
        statusText = "CALIBRARE: caut petice blocate…"; updateNotif(statusText)
        val patches = findFreshPatches(origin, CALIB_TRIALS)
        if (patches.size < 3) return null   // prea puțin teren blocat lângă origine → sări (reia data viitoare)

        class Trial(val tickHz: Int, val stepM: Double, val tag: String)
        val battery = listOf(
            Trial(baseTickHz,     25.0,  "control"),   // ~540 km/h, ~5 fix/cel
            Trial(baseTickHz * 2, 12.5,  "dwell"),     // ~540 km/h, ~10 fix/cel (test dwell)
            Trial(8,  20.0, "v"), Trial(10, 20.0, "v"), Trial(13, 20.0, "v"),
            Trial(16, 20.0, "v"), Trial(20, 20.0, "v") // scan viteză 576…1440 km/h, ~6.6 fix/cel const
        ).take(patches.size)

        var prev: DoubleArray? = origin
        val res = ArrayList<Triple<Trial, Int, Int>>()   // trial, expected, gained
        val safeTickMs = 1000L / baseTickHz
        val safeSpeed = 25.0 * baseTickHz * 3.6
        for ((i, tr) in battery.withIndex()) {
            if (stopFlag) break
            val b = patches[i]
            val cen = doubleArrayOf((b[0] + b[1]) / 2.0, (b[2] + b[3]) / 2.0)
            // transit SIGUR până la patch (viteza de bază, nu cea de test) → intrarea nu e warp-uită
            prev = drive(prev ?: cen, cen, safeTickMs, safeSpeed, 25.0, "CALIBRARE: spre patch ${i + 1}")
            try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) {}
            UnlockedMask.refresh(applicationContext)
            val expected = UnlockedMask.lockedCellsInBbox(b[0], b[1], b[2], b[3])
            if (expected.size < CALIB_MIN) continue   // patch deja (cvasi) deblocat → fără eșantion valid
            val p = CoverageController.Params(75.0, tr.stepM, tr.tickHz)
            val speed = p.speedKmh
            prev = coverLockedDirect(expected, p, prev,
                "CALIBRARE %d/%d • %.0f km/h (%dHz)".format(i + 1, battery.size, speed, tr.tickHz))
            try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) {}
            if (!UnlockedMask.refresh(applicationContext)) continue
            val gained = UnlockedMask.gainedAmong(expected)
            res.add(Triple(tr, expected.size, gained))
            android.util.Log.i("MockService",
                "CALIB %s tickHz=%d step=%.1f v=%.0f ratio=%.2f (%d/%d)".format(
                    tr.tag, tr.tickHz, tr.stepM, speed, gained.toDouble() / expected.size, gained, expected.size))
        }
        // întoarcere SIGURĂ la origine: bucla de județe pornește cu prev=origine, deci poziția acceptată de
        // Bump trebuie să FIE origine la final (altfel primul drive al județului ar fi un teleport = warp).
        if (!stopFlag) drive(prev ?: origin, origin, safeTickMs, safeSpeed, 25.0, "CALIBRARE: revin la origine")
        if (res.isEmpty()) return null

        fun ratioOf(t: Triple<Trial, Int, Int>) = t.third.toDouble() / t.second
        val ctrlR = res.firstOrNull { it.first.tag == "control" }?.let { ratioOf(it) }
        val dwellR = res.firstOrNull { it.first.tag == "dwell" }?.let { ratioOf(it) }
        val dwellLimited = ctrlR != null && dwellR != null && dwellR - ctrlR > 0.05 && ctrlR < 0.95
        // poarta = cea mai mare VITEZĂ (m/s) care a trecut (ratio ≥ CALIB_PASS); dacă nimic n-a trecut →
        // rămâi la soft-spot (nu accelera). Aplicare conservatoare: doar un PAS-sămânță la baza tickHz.
        val gateMps = res.filter { ratioOf(it) >= CALIB_PASS }
            .maxOfOrNull { it.first.stepM * it.first.tickHz } ?: (25.0 * baseTickHz)
        val seedStep = if (dwellLimited) 25.0   // fix-limitat → speedup cere tickHz↑ (amânat) → soft-spot sigur
                       else (0.85 * gateMps / baseTickHz).coerceIn(25.0, CoverageController.STEP_MAX)
        val appliedKmh = seedStep * baseTickHz * 3.6
        val verdict = "%s • poartă≈%.0f km/h → pornesc la %.0f km/h (pas %.0fm)".format(
            if (dwellLimited) "DWELL-limit" else "VITEZĂ-limit", gateMps * 3.6, appliedKmh, seedStep)
        android.util.Log.i("MockService", "CALIB VERDICT: $verdict")
        return CalibResult(seedStep, verdict)
    }

    /**
     * Găsește până la `count` petice (bbox ~CALIB_PATCH_DEG) cu ≥ CALIB_MIN celule ÎNCĂ blocate, scanând în
     * inele L∞ tot mai mari din origine (cele de lângă casă sunt deblocate → sărite automat). Petice spațiate
     * (pas = 2× latura) ca să nu se suprapună → fiecare trial are eșantion proaspăt. Fail-safe: listă scurtă.
     */
    private fun findFreshPatches(origin: DoubleArray, count: Int): List<DoubleArray> {
        val half = CALIB_PATCH_DEG / 2.0
        val cosLat = cos(Math.toRadians(origin[0])).coerceAtLeast(0.1)
        val stepLat = CALIB_PATCH_DEG * 2.0
        val stepLon = stepLat / cosLat
        val lonHalf = half / cosLat
        val out = ArrayList<DoubleArray>()
        var r = 0
        while (out.size < count && r <= CALIB_MAX_RING && !stopFlag) {
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dy), abs(dx)) != r) continue   // doar perimetrul inelului
                val latC = origin[0] + dy * stepLat
                val lonC = origin[1] + dx * stepLon
                val bbox = doubleArrayOf(latC - half, latC + half, lonC - lonHalf, lonC + lonHalf)
                if (UnlockedMask.lockedCellsInBbox(bbox[0], bbox[1], bbox[2], bbox[3]).size >= CALIB_MIN) {
                    out.add(bbox)
                    if (out.size >= count) return out
                }
            }
            r++
        }
        return out
    }

    /**
     * BACKSTOP cusături „între zone": țintește direct găurile izolate din bbox-ul județului (celule încă
     * blocate înconjurate de deblocat — cusături între județe deja acoperite, slivere de digitizare,
     * reziduuri ratate). Reutilizează cleanupBlockToFull pe mulțimea orfană. Scoped la bbox → ieftin.
     */
    private fun cleanupSeams(zone: Zone, prev0: DoubleArray?, p: CoverageController.Params, unit: Int): DoubleArray? {
        val orphans = UnlockedMask.lockedIsolatedCellsInBbox(zone.latMin, zone.latMax, zone.lonMin, zone.lonMax)
        if (orphans.isEmpty()) return prev0
        return cleanupBlockToFull(orphans, prev0, 1000L / p.tickHz, p.speedKmh, p.stepM, unit)
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
        // Lovituri staționare pe un cluster strâns → poziția stă legitim în <STUCK_RADIUS. Suspendă
        // detecția „blocat" a watcher-ului cât timp curățăm (altfel un cleanup lung = restart fals).
        cleanupActive = true
        try {
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
        } finally { cleanupActive = false }
        return prev
    }

    /**
     * SEEK & DESTROY: vânează hexagoanele singulare nedeblocate (găuri în interiorul acoperirii) și
     * le deblochează, mergând fizic de la una la alta (fără warp), MEREU la cea mai apropiată de
     * poziția curentă (nearest-first), ca să nu sară în cealaltă parte a țării.
     * După fiecare pasaj RE-SCANEAZĂ (self-check): găurile deblocate dispar, cele ratate se reîncearcă.
     * Se oprește când nu mai rămân găuri sau după SEEK_MAX_PASSES (ex. pipeline mort → găurile persistă).
     */
    private fun runSeekAndDestroy(prev0: DoubleArray?, tickMs: Long, speedKmh: Double, metersPerTick: Double,
                                  tag: String = "SEEK"): DoubleArray? {
        var prev = prev0
        var pass = 0
        while (!stopFlag && pass < SEEK_MAX_PASSES) {
            renewWakelock()
            statusText = "$tag • scanez găuri..."; updateNotif(statusText)
            UnlockedMask.refresh(applicationContext)
            val holes = UnlockedMask.isolatedLockedHoles()
            if (holes.isEmpty()) { statusText = "$tag ✓ fără găuri rămase (pasaj $pass)"; break }
            // Nearest-first din POZIȚIA CURENTĂ: mergi mereu la cea mai apropiată gaură, apoi re-țintește
            // de-acolo cea mai apropiată ș.a.m.d. — fără salturi în cealaltă parte a țării (≪ timp).
            val ordered = orderNearestFirst(prev, holes)
            val total = ordered.size
            for ((idx, h) in ordered.withIndex()) {
                if (stopFlag) break
                if (idx % 16 == 0) renewWakelock()
                prev = drive(prev ?: h, h, tickMs, speedKmh, metersPerTick,
                    "🎯 $tag • pasaj %d • %d/%d găuri".format(pass + 1, idx + 1, total))
                // „lovește" celula: câteva fix-uri staționare pe centru ca Bump s-o înregistreze sigur
                repeat(SEEK_HITS) {
                    if (!stopFlag) { pushLocation(h[0], h[1], 0.0); try { Thread.sleep(tickMs) } catch (_: InterruptedException) {} }
                }
            }
            pass++
        }
        if (!stopFlag && !statusText.startsWith("$tag ✓")) statusText = "$tag: oprit"
        return prev
    }

    /**
     * TUR CAPITALE: conduce CONTINUU (fără teleport) prin capitalele lumii, în ordine nearest-first din
     * poziția curentă + ameliorare 2-opt (metrică HAVERSINE — corectă la scară globală, spre deosebire de
     * metrica planară a modului România). Fiecare „leg" capitală→capitală e condus de `drive` la viteza
     * configurată; legile lungi (oceane) sunt interpolate la `metersPerTick`, deci mișcarea rămâne fizică
     * (Bump nu o vede ca teleport). Antimeridian: ținta se ajustează ±360° ca interpolarea să meargă pe
     * drumul SCURT, iar `pushLocation` re-normalizează longitudinea injectată în [-180,180]. NU folosește
     * masca/calibrarea/AutoState (pură călătorie). `loop` → reia turul de la capăt la final.
     */
    private fun runWorldTour(origin0: DoubleArray?, tickMs: Long, speedKmh: Double,
                             metersPerTick: Double, loop: Boolean, resumeRow: Int = -1,
                             resumeCapName: String? = null) {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        // CONTINUARE de la o capitală anume (ex. „ultima deblocată = Port Moresby"). Alegerea o
        // PERSISTĂM (nu doar one-shot din UI) ca să rămână validă la pauză/reluare/restart: tot traseul
        // rămas se recalculează DETERMINIST din ea, deci progresul (curRow) indexează mereu aceeași listă.
        if (resumeCapName != null) p.edit().putString("tour_resume_cap", resumeCapName).apply()
        val capName = resumeCapName ?: p.getString("tour_resume_cap", null)
        val resumeCap = capName?.let { name -> Capitals.all.firstOrNull { it.name.equals(name, true) } }

        // ANCORĂ STABILĂ pentru ordinea de bază (home): fixată o dată și refolosită → ordine identică la
        // reluare. Re-ancorăm din origin0 DOAR la un tur nou (fără resumeCap, fără progres salvat).
        val savedAnchor = if (p.getBoolean("tour_anchor_valid", false))
            validGeo(doubleArrayOf(Double.fromBits(p.getLong("tour_anchor_lat", 0L)),
                                   Double.fromBits(p.getLong("tour_anchor_lon", 0L)))) else null
        // HOME real persistat (GPS) — folosit ca ancoră la continuarea după capitală: origin0 poate fi
        // poziția curentă mock (chiar capitala!), iar dacă am ancora ordinea de bază pe ea, „vizitate"
        // ar ieși greșit (doar capitala) → s-ar reface toată Europa/Asia. Home-ul ne dă ordinea reală.
        val persistedGps = if (p.getBoolean("gpsValid", false))
            validGeo(doubleArrayOf(Double.fromBits(p.getLong("gpsLat", 0L)),
                                   Double.fromBits(p.getLong("gpsLon", 0L)))) else null
        fun persistAnchor(a: DoubleArray): DoubleArray {
            p.edit().putBoolean("tour_anchor_valid", true)
                .putLong("tour_anchor_lat", a[0].toRawBits())
                .putLong("tour_anchor_lon", a[1].toRawBits()).apply()
            return a
        }
        val anchor: DoubleArray = when {
            savedAnchor != null && (resumeCap != null || resumeRow > 0) -> savedAnchor   // ordine stabilă
            resumeCap != null -> persistAnchor(persistedGps ?: doubleArrayOf(IASI_LAT, IASI_LON))   // home, NU origin0 (poate fi chiar capitala)
            else -> persistAnchor(validGeo(origin0) ?: doubleArrayOf(IASI_LAT, IASI_LON))  // tur nou din locația ta
        }
        statusText = "🌍 TUR: calculez ordinea capitalelor…"; updateNotif(statusText)
        val baseOrder = orderCapitalsTour(anchor)
        if (baseOrder.isEmpty()) { statusText = "🌍 TUR: listă capitale goală"; return }

        val tour: List<Capitals.Cap>
        val startPoint: DoubleArray   // de unde pornim când NU reluăm la mijloc
        if (resumeCap != null) {
            // SCOATE capitalele deja parcurse din listă: în ordinea home, tot ce e până la & inclusiv
            // capitala curentă (Port Moresby) = făcut. Re-optimizăm DOAR rămasele, pornind chiar de la
            // capitala curentă → cel mai scurt traseu peste ce-a mai rămas, fără să retrecem pe unde-am fost.
            val ci = baseOrder.indexOfFirst { it.name == resumeCap.name }
            val visited = if (ci >= 0) baseOrder.subList(0, ci + 1).mapTo(HashSet()) { it.name }
                          else hashSetOf(resumeCap.name)
            val remaining = Capitals.all.filter { it.name !in visited }
            tour = orderCapitalsTour(resumeCap.coord(), remaining)
            startPoint = resumeCap.coord()
            statusText = "🌍 TUR: continuă de la ${resumeCap.name} • ${tour.size} rămase (${visited.size} sărite)"
            updateNotif(statusText)
        } else {
            tour = baseOrder
            startPoint = validGeo(origin0) ?: anchor   // mereu normalizat în [-180,180]
        }
        if (tour.isEmpty()) {   // totul deja parcurs → uită continuarea, data viitoare tur complet
            p.edit().remove("tour_resume_cap").apply()
            statusText = "🌍 TUR ✓ toate capitalele deja parcurse"; updateNotif(statusText); return
        }
        val total = tour.size
        // Prima aplicare a continuării (resumeCapName != null) pleacă mereu din capul listei RĂMASE
        // (idx 0), niciodată cu un resumeRow rămas din alt context. La reluare genuină (resumeCapName
        // null) resumeRow indexează exact aceeași listă rămasă (deterministă) → continuă din poziția curentă.
        var idx = if (resumeCapName == null && resumeRow > 0) resumeRow.coerceIn(0, total) else 0
        var prev = if (idx > 0) (validGeo(origin0) ?: startPoint) else startPoint
        curRow = idx
        do {
            while (idx < total) {
                if (stopFlag) return
                curRow = idx          // progres persistabil (pauseRoute → persistResume salvează „row")
                val cap = tour[idx]
                renewWakelock()
                // antimeridian: du ținta pe drumul SCURT față de poziția curentă
                var tgtLon = cap.lon
                val dLon = tgtLon - prev[1]
                if (dLon > 180.0) tgtLon -= 360.0 else if (dLon < -180.0) tgtLon += 360.0
                val distKm = RouteGenerator.haversine(prev, doubleArrayOf(cap.lat, cap.lon)) / 1000.0
                drive(prev, doubleArrayOf(cap.lat, tgtLon), tickMs, speedKmh, metersPerTick,
                    "🌍 TUR • %d/%d • %s (%.0f km)".format(idx + 1, total, cap.name, distKm))
                prev = doubleArrayOf(cap.lat, cap.lon)   // capitala reală, în [-180,180]
                idx++
            }
            statusText = "🌍 TUR ✓ %d capitale parcurse".format(total); updateNotif(statusText)
            // Rămasele s-au terminat → uită continuarea: un viitor start face iar tur complet.
            if (resumeCap != null) p.edit().remove("tour_resume_cap").apply()
            idx = 0; curRow = 0   // tur complet → la loop reluăm de la prima capitală
        } while (loop && !stopFlag)
    }

    /**
     * Ordonează capitalele pt. CEL MAI SCURT tur: nearest-neighbor lacom din `origin` (haversine),
     * apoi îmbunătățire locală pe un DRUM DESCHIS (origine fixă la stânga, fără ciclu de închidere):
     *   • 2-opt  — inversează un segment cât timp scurtează traseul (rezolvă încrucișările);
     *   • Or-opt — relocă un segment de 1..3 capitale (drept sau inversat) acolo unde scurtează mai
     *     mult (rezolvă „cârligele" rămase de la nearest-neighbor).
     * Alternăm cele două până nu mai scade lungimea. Aplicăm DOAR mișcări strict mai bune (> eps) →
     * rezultatul nu poate fi mai prost ca NN+2-opt. N≈195 → câteva pasaje O(N²) = ieftin (<2s).
     * Determinist (`caps` fix + `origin`) → exact același tur la reluare (de asta depinde resume-ul).
     * `caps` = lista de ordonat (implicit toate); la CONTINUARE primește doar capitalele RĂMASE.
     */
    private fun orderCapitalsTour(origin: DoubleArray, caps: List<Capitals.Cap> = Capitals.all): List<Capitals.Cap> {
        if (caps.size <= 2) return caps
        fun hav(a: DoubleArray, b: DoubleArray) = RouteGenerator.haversine(a, b)
        // (1) nearest-neighbor din origine
        val remaining = ArrayList(caps)
        val route = ArrayList<Capitals.Cap>(caps.size)
        var cur = origin
        while (remaining.isNotEmpty()) {
            var bestI = 0; var bestD = Double.MAX_VALUE
            for (i in remaining.indices) {
                val d = hav(cur, remaining[i].coord())
                if (d < bestD) { bestD = d; bestI = i }
            }
            val c = remaining.removeAt(bestI); route.add(c); cur = c.coord()
        }
        val n = route.size
        fun co(i: Int) = route[i].coord()   // doar returnează DoubleArray-ul stocat (ieftin)
        // (2) îmbunătățire locală alternând 2-opt + Or-opt până la convergență
        var improved = true; var pass = 0
        while (improved && pass < 30 && !stopFlag) {
            improved = false; pass++
            // --- 2-opt: inversează route[i..k] dacă scurtează ---
            for (i in 0 until n - 1) {
                for (k in i + 1 until n) {
                    val aPrev = if (i == 0) origin else co(i - 1)
                    val ai = co(i); val ck = co(k)
                    val cNext = if (k + 1 < n) co(k + 1) else null
                    val before = hav(aPrev, ai) + (if (cNext != null) hav(ck, cNext) else 0.0)
                    val after = hav(aPrev, ck) + (if (cNext != null) hav(ai, cNext) else 0.0)
                    if (after + 1e-6 < before) {
                        var lo = i; var hi = k
                        while (lo < hi) { val t = route[lo]; route[lo] = route[hi]; route[hi] = t; lo++; hi-- }
                        improved = true
                    }
                }
            }
            // --- Or-opt: scoate segmentul [i, i+L) și reinserează-l unde scurtează cel mai mult ---
            for (segLen in 1..3) {
                var i = 0
                while (i + segLen <= n) {
                    val oldPrev = if (i == 0) origin else co(i - 1)
                    val segFirst = co(i); val segLast = co(i + segLen - 1)
                    val oldNext = if (i + segLen < n) co(i + segLen) else null
                    // câștigul din închiderea golului lăsat de segment
                    val removalSaving = hav(oldPrev, segFirst) +
                        (if (oldNext != null) hav(segLast, oldNext) - hav(oldPrev, oldNext) else 0.0)
                    val seg = ArrayList(route.subList(i, i + segLen))
                    val rest = ArrayList<Capitals.Cap>(n - segLen)
                    rest.addAll(route.subList(0, i)); rest.addAll(route.subList(i + segLen, n))
                    val m = rest.size
                    var bestGain = 1e-6   // pragul: doar mutări STRICT mai bune
                    var bestG = -1; var bestRev = false
                    for (g in 0..m) {
                        val pPrev = if (g == 0) origin else rest[g - 1].coord()
                        val pNext = if (g < m) rest[g].coord() else null
                        val gapBase = if (pNext != null) hav(pPrev, pNext) else 0.0
                        val sF = seg.first().coord(); val sL = seg.last().coord()
                        val addF = hav(pPrev, sF) + (if (pNext != null) hav(sL, pNext) else 0.0) - gapBase
                        val addR = hav(pPrev, sL) + (if (pNext != null) hav(sF, pNext) else 0.0) - gapBase
                        if (removalSaving - addF > bestGain) { bestGain = removalSaving - addF; bestG = g; bestRev = false }
                        if (segLen > 1 && removalSaving - addR > bestGain) { bestGain = removalSaving - addR; bestG = g; bestRev = true }
                    }
                    if (bestG >= 0) {
                        if (bestRev) seg.reverse()
                        rest.addAll(bestG, seg)
                        route.clear(); route.addAll(rest)
                        improved = true
                    }
                    i++
                }
            }
        }
        return route
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

    private fun pushLocation(lat: Double, lonRaw: Double, speedKmh: Double) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        // Normalizează longitudinea în [-180,180]: turul capitalelor poate conduce „peste" antimeridian
        // (ex. Wellington 174° → Apia 189° ca să meargă pe drumul scurt) → readuce în interval la injecție.
        val lon = ((lonRaw + 540.0) % 360.0) - 180.0
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
        val now0 = SystemClock.elapsedRealtime()
        lastProgressMs = now0; lastMoveMs = now0
        wpLat = Double.NaN; wpLon = Double.NaN
        watchdog = Thread {
            while (!watchdogStop) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                if (watchdogStop || !running || holding) continue
                val nowMs = SystemClock.elapsedRealtime()
                // (1) STALL injecție: providerul pierdut (nicio injecție reușită) → self-heal pe loc.
                if (nowMs - lastProgressMs > STALL_MS) {
                    reacquireProviders(); renewWakelock()
                    lastProgressMs = nowMs
                }
                // (2) BLOCAT ÎN ZONĂ: injectează, dar poziția nu iese din rază de mult timp (thread agățat,
                // lovituri staționare la nesfârșit etc.) → restart AUTO (doar în mod autonom). NU în timpul
                // cleanup-ului: acolo lovim staționar un cluster strâns → e progres legitim, nu blocaj.
                val lat = curLat; val lon = curLon
                if (cleanupActive) {
                    wpLat = lat; wpLon = lon; lastMoveMs = nowMs   // resetează fereastra → fără restart fals
                } else if (lat != 0.0 || lon != 0.0) {
                    if (wpLat.isNaN() ||
                        RouteGenerator.haversine(doubleArrayOf(wpLat, wpLon), doubleArrayOf(lat, lon)) > STUCK_RADIUS_M) {
                        wpLat = lat; wpLon = lon; lastMoveMs = nowMs   // s-a mișcat real → resetează fereastra
                    } else if (AutoState.isActive(applicationContext) && nowMs - lastMoveMs > STUCK_MS) {
                        if (forceRestart()) break                      // a programat restartul → ieși din watchdog
                        wpLat = lat; wpLon = lon; lastMoveMs = nowMs   // back-off → mai dă-i o fereastră
                    }
                }
            }
        }.also { it.start() }
    }

    /**
     * Watcher „blocat": repornește serviciul AUTO (resume din AutoState cu ancora SALVATĂ → reia
     * frontiera, nu re-centrează). Loop-guard: dacă restartăm de RESTART_MAX_STREAK ori la ACELAȘI bloc
     * fără progres → nu mai hamerui, alertă sticky + back-off (lasă bucla să mai încerce singură).
     * Întoarce true dacă a programat restartul (caller iese din watchdog), false la back-off.
     */
    private fun forceRestart(): Boolean {
        val streak = AutoState.noteRestart(applicationContext, autoBlockN)
        if (streak >= RESTART_MAX_STREAK) {
            statusText = "⚠ blocat repetat la blocul $autoBlockN — back-off (verifică pipeline)"; updateNotif(statusText)
            return false
        }
        statusText = "⚠ pare blocat — restart automat (#$streak)"; updateNotif(statusText)
        // intent gol → la repornire onStartCommand reia AUTO din AutoState (ancoră salvată = acasă).
        val restart = PendingIntent.getService(this, 7,
            Intent(applicationContext, MockService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2000L, restart)
        stopEverything()
        return true
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
        // NU persista o poziție bidon [0,0] (Golful Guineea): ar deveni „origine" la următoarea
        // pornire → turul ar pleca din ocean și n-ar debloca nimic lângă tine.
        if (validGeo(doubleArrayOf(curLat, curLon)) == null) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("valid", true)
            .putLong("lat", curLat.toRawBits())
            .putLong("lon", curLon.toRawBits())
            .putInt("row", curRow)
            .putString("key", curPolyKey)
            .apply()
    }

    /**
     * Coordonată „reală" sau null: respinge null, valorile în afara intervalului și ≈[0,0] (lat&lon
     * sub ~100m de Null Island = fix lipsă / placeholder, NU o locație validă). Folosit ca să nu
     * pornim niciodată turul/coverage din [0,0].
     */
    private fun validGeo(p: DoubleArray?): DoubleArray? =
        if (p != null && abs(p[0]) <= 90.0 && abs(p[1]) <= 180.0 &&
            (abs(p[0]) > 0.001 || abs(p[1]) > 0.001)) p else null

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
