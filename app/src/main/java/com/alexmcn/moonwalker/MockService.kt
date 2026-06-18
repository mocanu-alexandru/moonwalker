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
        private const val AUTO_BLOCK_M = 3000.0          // latura unui bloc de acoperire (3 km)
        private const val AUTO_MAX_BLOCKS = 50000        // plafon de siguranță (rază ~670 km)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELEASE) { stopEverything(); clearResume(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) { pauseRoute(); return START_STICKY }

        val tickHz = (intent?.getIntExtra(EXTRA_TICK_HZ, 1) ?: 1).coerceIn(1, 100)
        val rowM = intent?.getDoubleExtra(EXTRA_ROW_M, 130.0) ?: 130.0
        val stepM = intent?.getDoubleExtra(EXTRA_STEP_M, 75.0) ?: 75.0
        val vertical = intent?.getBooleanExtra(EXTRA_VERTICAL, false) ?: false
        val loop = intent?.getBooleanExtra(EXTRA_LOOP, true) ?: true
        val skipFraction = intent?.getDoubleExtra(EXTRA_SKIP_FRACTION, 0.0) ?: 0.0
        val skipUnlocked = intent?.getBooleanExtra(EXTRA_SKIP_UNLOCKED, false) ?: false
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) ?: false
        // Auto sare MEREU deblocatele → are nevoie de mască. Asigură un set proaspăt înainte de rulare.
        if ((skipUnlocked || auto) && !UnlockedMask.isReady) UnlockedMask.refresh(applicationContext)
        val polyStr = intent?.getStringExtra(EXTRA_POLY)

        // Repornire fără date (intent gol de la sticky/onTaskRemoved) → nu porni traseu bogus.
        // UI-ul trimite mereu EXTRA_POLY; lipsa lui = restart de sistem, nu start real.
        if (polyStr.isNullOrBlank()) {
            // restart sticky cu intent gol: dacă rulam → lăsăm sticky; dacă eram parcați (holding)
            // → NU opri (rămânem parcați); altfel oprește complet.
            if (!running && !holding) stopEverything()
            return if (running || holding) START_STICKY else START_NOT_STICKY
        }

        val zone: Zone = if (polyStr != null && polyStr.isNotBlank()) {
            val pts = polyStr.split(";").mapNotNull {
                val a = it.split(","); if (a.size == 2)
                    doubleArrayOf(a[0].toDouble(), a[1].toDouble()) else null
            }
            Zone.fromPolygon(pts)
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
            resumeOrigin ?: realStart, skipUnlocked, resumeRow, auto)
        return START_STICKY
    }

    private fun startWalking(
        zone: Zone, tickHz: Int, rowM: Double, stepM: Double,
        vertical: Boolean, loop: Boolean, skipFraction: Double = 0.0,
        realStart: DoubleArray? = null, skipUnlocked: Boolean = false, resumeRow: Int = -1,
        auto: Boolean = false
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

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moonwalker:injection")
            .also { it.acquire(12 * 60 * 60 * 1000L) }  // max 12h

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

            if (auto) {
                // AUTO-EXTINDERE: spirală pătrată de blocuri din anchor (locația ta), cel-mai-aproape-
                // întâi; fiecare bloc acoperit cu serpentină sărind deblocatele. Blocurile complet
                // deblocate sunt trecute aproape instant. Rulează până la STOP.
                val anchor = transitionOrigin ?: doubleArrayOf(IASI_LAT, IASI_LON)
                val mLat = 111_320.0
                val dLatB = AUTO_BLOCK_M / mLat
                val dLonB = AUTO_BLOCK_M / (mLat * cos(Math.toRadians(anchor[0])))
                var x = 0; var y = 0; var dx = 0; var dy = -1; var blockN = 0
                while (!stopFlag && blockN < AUTO_MAX_BLOCKS) {
                    val cLat = anchor[0] + y * dLatB; val cLon = anchor[1] + x * dLonB
                    val bz = Zone.fromBbox(cLat - dLatB / 2, cLat + dLatB / 2, cLon - dLonB / 2, cLon + dLonB / 2)
                    val g = RouteGenerator(bz, rowM, stepM, vertical, true)  // skip MEREU în auto
                    var t = g.next()
                    while (t != null && !stopFlag) {
                        prev = drive(prev ?: t, t, tickMs, speedKmh, metersPerTick,
                            "AUTO • bloc %d • %d pct".format(blockN, pointsDone))
                        t = g.next()
                    }
                    blockN++
                    // avans spirală pătrată (blocuri consecutive adiacente)
                    if (x == y || (x < 0 && x == -y) || (x > 0 && x == 1 - y)) { val n = -dy; dy = dx; dx = n }
                    x += dx; y += dy
                }
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
        for (p in activeProviders) {
            try { lm.setTestProviderLocation(p, fill(Location(p))) } catch (_: Exception) {}
        }
    }

    /**
     * Eliberează resursele de mock (wakelock, test provider gps, opțional FLP mock mode).
     * `stopMockMode=false` la repornire: păstrăm FLP în mock mode ca să nu facem
     * off→on inutil (race). `stopMockMode=true` la oprire reală.
     */
    private fun teardownMock(stopMockMode: Boolean) {
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
