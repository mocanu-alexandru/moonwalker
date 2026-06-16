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
        const val ACTION_STOP = "com.alexmcn.moonwalker.STOP"

        // stare observabilă pentru UI
        @Volatile var running = false
        @Volatile var curLat = 0.0
        @Volatile var curLon = 0.0
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }

        val tickHz = (intent?.getIntExtra(EXTRA_TICK_HZ, 1) ?: 1).coerceIn(1, 100)
        val rowM = intent?.getDoubleExtra(EXTRA_ROW_M, 130.0) ?: 130.0
        val stepM = intent?.getDoubleExtra(EXTRA_STEP_M, 75.0) ?: 75.0
        val vertical = intent?.getBooleanExtra(EXTRA_VERTICAL, false) ?: false
        val loop = intent?.getBooleanExtra(EXTRA_LOOP, true) ?: true
        val skipFraction = intent?.getDoubleExtra(EXTRA_SKIP_FRACTION, 0.0) ?: 0.0
        val polyStr = intent?.getStringExtra(EXTRA_POLY)

        // Repornire fără date (intent gol de la sticky/onTaskRemoved) → nu porni traseu bogus.
        // UI-ul trimite mereu EXTRA_POLY; lipsa lui = restart de sistem, nu start real.
        if (polyStr.isNullOrBlank()) {
            if (!running) stopEverything()
            return if (running) START_STICKY else START_NOT_STICKY
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

        startForeground(NOTIF_ID, buildNotif("pornire..."))
        startWalking(zone, tickHz, rowM, stepM, vertical, loop, skipFraction, realStart)
        return START_STICKY
    }

    private fun startWalking(
        zone: Zone, tickHz: Int, rowM: Double, stepM: Double,
        vertical: Boolean, loop: Boolean, skipFraction: Double = 0.0,
        realStart: DoubleArray? = null
    ) {
        // Fix dublă-rulare: dacă userul schimbă setări și repornește, oprește COMPLET
        // thread-ul vechi (sincron) înainte de a reseta stopFlag — altfel thread-ul vechi
        // vede stopFlag resetat la false și continuă cu setările vechi în paralel.
        stopPreviousRun()

        stopFlag = false
        running = true
        val speedKmh = stepM * tickHz * 3.6

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moonwalker:injection")
            .also { it.acquire(12 * 60 * 60 * 1000L) }  // max 12h

        // Mockăm TOATE provider-ele LocationManager ca FakeTraveler/warren-bank (apps open-source
        // care DEBLOCHEAZĂ astfel de aplicații): NETWORK + GPS + FUSED_PROVIDER (Android 12+).
        // FUSED_PROVIDER (API 31+) e providerul canonic pe care îl citesc FLP, Maps și path-ul de
        // unlock al Bump — îl rateam complet, de-aia Bump nu înregistra mișcarea.
        activeProviders = mutableListOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        )
        if (Build.VERSION.SDK_INT >= 31) activeProviders.add(LocationManager.FUSED_PROVIDER)
        try {
            for (p in activeProviders) addMockProvider(p)
        } catch (e: SecurityException) {
            statusText = "EROARE: app-ul nu e setat ca Mock Location în Developer Options"
            stopEverything(); return
        }
        // Canalul Google Play Services FLP — degradează grațios dacă eșuează
        try {
            fusedClient.setMockMode(true)
                .addOnSuccessListener { flpActive = true }
                .addOnFailureListener { flpActive = false }
        } catch (_: SecurityException) { flpActive = false }

        // Păstrăm ultima locație între rulări. Dacă există o rulare anterioară (curLat valid):
        //  - o injectăm IMEDIAT în provider-ele proaspete (FLP nu mai cade pe acasă în gol)
        //  - o folosim și ca ORIGINE a tranziției (nu realStart=acasă), deci traseul nou
        //    continuă lin din ultima poziție, fără salt la domiciliu.
        val lastValid = curLat != 0.0 && !curLat.isNaN()
        val transitionOrigin: DoubleArray? = if (lastValid) doubleArrayOf(curLat, curLon) else realStart
        if (lastValid) pushLocation(curLat, curLon, 0.0)

        thread = Thread {

            var gen = RouteGenerator(zone, rowM, stepM, vertical)
            if (skipFraction > 0.0) gen.seekToRow((skipFraction * gen.totalRows).toInt())
            // Decuplăm netezimea de viteză: injectăm la o rată internă fluidă (≥12Hz) indiferent
            // de Hz-ul ales, mutând proporțional mai puțini metri per injecție. Viteza rămâne
            // exactă (metersPerTick * injectHz = speedMps), dar mișcarea nu mai e sacadată.
            val speedMps = stepM * tickHz                 // = speedKmh/3.6
            // Rată internă ≥12Hz ȘI multiplu al tickHz, ca sub-pașii per waypoint să fie întregi
            // (fără eroare de rotunjire în viteză) dar mișcarea să fie fluidă.
            val injectHz = tickHz * max(1, ceil(12.0 / tickHz).toInt())
            val tickMs = 1000L / injectHz
            val metersPerTick = speedMps / injectHz       // distanță per injecție la rata fluidă
            pointsDone = 0
            var lastUiMs = 0L

            // Tranziție lină de la ultima poziție (sau locația reală la prima rulare) spre traseu
            val firstPt = gen.next()
            var prev: DoubleArray? = if (firstPt != null) {
                if (transitionOrigin != null) doTransition(transitionOrigin, firstPt, tickMs, speedKmh)
                firstPt
            } else null

            while (!stopFlag) {
                val target = gen.next()
                if (target == null) {
                    if (loop) { gen = RouteGenerator(zone, rowM, stepM, vertical); continue }
                    else { statusText = "GATA - zonă acoperită"; break }
                }

                // dacă saltul până la următorul punct e mai mare decât metersPerTick,
                // îl spargem în pași intermediari ca să fie playback fluid
                val from = prev ?: target
                val dist = RouteGenerator.haversine(from, target)
                val steps = max(1, ceil(dist / metersPerTick).toInt())
                for (s in 1..steps) {
                    if (stopFlag) break
                    val t = s.toDouble() / steps
                    val lat = from[0] + (target[0] - from[0]) * t
                    val lon = from[1] + (target[1] - from[1]) * t
                    pushLocation(lat, lon, speedKmh)
                    curLat = lat; curLon = lon
                    progress = gen.progress()
                    pointsDone++
                    // Actualizăm UI/notificarea ~3×/sec, nu la fiecare injecție (rebuild-ul
                    // notificării la 12+Hz cauza jank și încetinea bucla).
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastUiMs >= 333) {
                        lastUiMs = nowMs
                        val ch = if (flpActive) "GPS+FUSED" else "GPS (fused eșuat)"
                        statusText = "rulează • %.1f%% • %s".format(progress * 100, ch)
                        updateNotif("%.1f%% • %d pct • %s".format(progress * 100, pointsDone, ch))
                    }
                    try { Thread.sleep(tickMs) } catch (_: InterruptedException) {}
                }
                prev = target
            }
            // Oprire completă DOAR dacă am ieșit natural (traseu terminat), nu dacă am fost
            // înlocuiți de un restart (care a setat stopFlag=true și face propriul teardown).
            if (!stopFlag) stopEverything()
        }
        thread?.start()
    }

    /**
     * Injectează treptat de la `from` (locația reală) la `to` (primul waypoint al traseului).
     * FLP folosește filtrare Kalman; o teleportare instantă e respinsă sau cauzează jumping.
     * Returnează `to` ca să devină `prev` în bucla principală.
     */
    private fun doTransition(from: DoubleArray, to: DoubleArray, tickMs: Long, speedKmh: Double): DoubleArray {
        // Nu injectăm niciodată locația reală (acasă) — FLP ar învăța-o ca referință
        // și ar continua să tragă spre ea. Pornim direct la țintă sau interpolăm de acolo.
        val distM = RouteGenerator.haversine(from, to)
        if (distM < 500.0) return to
        val nSteps = when {
            distM < 5_000.0  -> 5
            distM < 50_000.0 -> 10
            else             -> 15
        }
        for (s in 1..nSteps) {
            if (stopFlag) break
            val t = s.toDouble() / nSteps
            val lat = from[0] + (to[0] - from[0]) * t
            val lon = from[1] + (to[1] - from[1]) * t
            pushLocation(lat, lon, speedKmh * t)
            curLat = lat; curLon = lon
            statusText = "pornire…"
            try { Thread.sleep(tickMs) } catch (_: InterruptedException) {}
        }
        return to
    }

    private fun pushLocation(lat: Double, lon: Double, speedKmh: Double) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val brg = if (!prevLat.isNaN()) calcBearing(prevLat, prevLon, lat, lon) else 0f
        prevLat = lat; prevLon = lon
        // Câmpuri ca în referința FakeTraveler (accuracy 3m realist, fără extras "satellites").
        fun fill(loc: Location) = loc.apply {
            latitude = lat; longitude = lon; altitude = 3.0
            accuracy = 3.0f
            time = now; elapsedRealtimeNanos = elapsed
            speed = (speedKmh / 3.6).toFloat()
            bearing = brg
            bearingAccuracyDegrees = 0.1f
            speedAccuracyMetersPerSecond = 0.01f
            verticalAccuracyMeters = 0.1f
        }
        // Injectăm aceeași poziție în toate provider-ele LocationManager (network/gps/fused)
        for (p in activeProviders) {
            try { lm.setTestProviderLocation(p, fill(Location(p))) } catch (_: Exception) {}
        }
        // Și în Play Services FLP direct (clienții care îl folosesc explicit)
        if (flpActive) {
            try { fusedClient.setMockLocation(fill(Location("fused"))) } catch (_: Exception) {}
        }
    }

    private fun calcBearing(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Float {
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
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
     * Înregistrează un test provider, ștergându-l întâi (ca să nu crape cu "already added")
     * și reîncercând — exact tehnica FakeTraveler care reușește și pe FUSED_PROVIDER (API 31+).
     * requiresSatellite=false pentru toate (inclusiv gps) ca în referință.
     */
    private fun addMockProvider(p: String, maxRetry: Int = 3) {
        var lastErr: Exception? = null
        repeat(maxRetry) {
            try {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                lm.addTestProvider(
                    p,
                    /*requiresNetwork=*/   false,
                    /*requiresSatellite=*/ false,
                    /*requiresCell=*/      false,
                    /*hasMonetaryCost=*/   false,
                    /*supportsAltitude=*/  false,
                    /*supportsSpeed=*/     true,
                    /*supportsBearing=*/   true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
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
        prevLat = Double.NaN; prevLon = Double.NaN
        teardownMock(stopMockMode = true)
        if (statusText.startsWith("rulează")) statusText = "oprit"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopFlag = true
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
        val stopIntent = Intent(this, MockService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("Moonwalker rulează")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }
    private fun updateNotif(text: String) {
        (getSystemService(NotificationManager::class.java)).notify(NOTIF_ID, buildNotif(text))
    }
}
