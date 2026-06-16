package com.alexmcn.moonwalker

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
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
        @Volatile var flpActive = false   // true dacă setMockMode(true) a reușit
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
        stopFlag = false
        running = true
        val speedKmh = stepM * tickHz * 3.6

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moonwalker:injection")
            .also { it.acquire(12 * 60 * 60 * 1000L) }  // max 12h

        // FLP PUR — un singur sistem de mock. Bump, Maps și Waze citesc toți prin FLP.
        // setMockMode(true) golește cache-ul FLP și forțează raportarea DOAR a locațiilor
        // din setMockLocation, pentru toți clienții (inclusiv alte procese).
        // Rularea simultană cu addTestProvider(gps+network) crea DOUĂ surse de mock care
        // se contraziceau → locația sărea haotic. Acum lăsăm FLP-ul singura sursă.
        activeProviders = mutableListOf()
        try {
            fusedClient.setMockMode(true)
                .addOnSuccessListener { flpActive = true }
                .addOnFailureListener {
                    flpActive = false
                    statusText = "EROARE: app-ul nu e setat ca Mock Location în Developer Options"
                    stopEverything()
                }
        } catch (_: SecurityException) {
            statusText = "EROARE: app-ul nu e setat ca Mock Location în Developer Options"
            stopEverything(); return
        }

        thread = Thread {

            var gen = RouteGenerator(zone, rowM, stepM, vertical)
            if (skipFraction > 0.0) gen.seekToRow((skipFraction * gen.totalRows).toInt())
            val tickMs = 1000L / tickHz        // ms între injecții
            val metersPerTick = speedKmh * 1000.0 / 3600.0  // = stepM (1 waypoint per tick)
            pointsDone = 0

            // Tranziție lină de la locația reală la primul waypoint din traseu
            val firstPt = gen.next()
            var prev: DoubleArray? = if (firstPt != null) {
                if (realStart != null) doTransition(realStart, firstPt, tickMs, speedKmh)
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
                    val flp = if (flpActive) "FLP:DA" else "FLP:NU"
                    statusText = "rulează • %.1f%% • %s".format(progress * 100, flp)
                    updateNotif("%.1f%% • %d pct • %s".format(progress * 100, pointsDone, flp))
                    try { Thread.sleep(tickMs) } catch (_: InterruptedException) {}
                }
                prev = target
            }
            stopEverything()
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
        // Singura sursă: FLP. setMockLocation împinge poziția curentă direct în FLP,
        // care o raportează tuturor clienților (Bump/Maps/Waze). Bearing + speed explicite
        // ca FLP să estimeze viteza vectorial. Guard pe flpActive: nu împingem înainte ca
        // setMockMode(true) să fi reușit (altfel apelul ar eșua oricum).
        if (flpActive) {
            try {
                fusedClient.setMockLocation(Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = lat; longitude = lon; altitude = 100.0
                    accuracy = 1.0f
                    time = now; elapsedRealtimeNanos = elapsed
                    speed = (speedKmh / 3.6).toFloat()
                    bearing = brg
                    bearingAccuracyDegrees = 0.5f
                    speedAccuracyMetersPerSecond = 0.05f
                    verticalAccuracyMeters = 0.2f
                    extras = Bundle().apply { putInt("satellites", 8) }
                })
            } catch (_: Exception) {}
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

    private fun stopEverything() {
        stopFlag = true
        running = false
        flpActive = false
        prevLat = Double.NaN; prevLon = Double.NaN
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        Handler(Looper.getMainLooper()).post {
            try { fusedClient.setMockMode(false) } catch (_: Exception) {}
        }
        for (p in activeProviders) {
            try { lm.setTestProviderEnabled(p, false) } catch (_: Exception) {}
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
        }
        activeProviders.clear()
        if (statusText.startsWith("rulează")) statusText = "oprit"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopFlag = true
        for (p in activeProviders) { try { lm.removeTestProvider(p) } catch (_: Exception) {} }
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
