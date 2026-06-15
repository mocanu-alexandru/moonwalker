package com.alexmcn.moonwalker

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.*
import androidx.core.app.NotificationCompat
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
    }

    private lateinit var lm: LocationManager
    // Injectăm în ambii provideri ca să prevenim jumpingul între GPS fake și NETWORK real
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private var thread: Thread? = null
    @Volatile private var stopFlag = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
        // viteza efectivă = stepM × Hz × 3.6 km/h (un waypoint per tick la această viteză)
        val speedKmh = stepM * tickHz * 3.6
        // setup mock provider
        try {
            for (p in providers) {
                lm.addTestProvider(
                    p, false, false, false, false,
                    true, true, true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(p, true)
            }
        } catch (e: SecurityException) {
            statusText = "EROARE: app-ul nu e setat ca Mock Location în Developer Options"
            stopEverything(); return
        } catch (e: Exception) {
            statusText = "EROARE mock provider: ${e.message}"
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
                    statusText = "rulează • %.1f%%".format(progress * 100)
                    updateNotif("%.1f%% • %d pct".format(progress * 100, pointsDone))
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
        val distM = RouteGenerator.haversine(from, to)
        if (distM < 500.0) {
            // Prea aproape — injectăm direct locația reală 2 ticks, fără interpolare
            repeat(2) {
                if (!stopFlag) {
                    pushLocation(from[0], from[1], 0.0)
                    curLat = from[0]; curLon = from[1]
                    try { Thread.sleep(tickMs) } catch (_: InterruptedException) {}
                }
            }
            return to
        }
        val nSteps = when {
            distM < 5_000.0  -> 5
            distM < 50_000.0 -> 10
            else             -> 15
        }
        // 2 ticks la locația reală (FLP calibrare)
        repeat(2) {
            if (!stopFlag) {
                pushLocation(from[0], from[1], 0.0)
                curLat = from[0]; curLon = from[1]
                try { Thread.sleep(tickMs) } catch (_: InterruptedException) {}
            }
        }
        // nSteps ticks de interpolare liniară spre primul waypoint
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
        for (p in providers) {
            try {
                val loc = Location(p).apply {
                    latitude = lat; longitude = lon; altitude = 100.0
                    accuracy = 3.0f
                    time = now; elapsedRealtimeNanos = elapsed
                    speed = (speedKmh / 3.6).toFloat()
                    bearingAccuracyDegrees = 1.0f
                    speedAccuracyMetersPerSecond = 1.0f
                    verticalAccuracyMeters = 1.0f
                }
                lm.setTestProviderLocation(p, loc)
            } catch (_: Exception) {}
        }
    }

    private fun stopEverything() {
        stopFlag = true
        running = false
        for (p in providers) {
            try { lm.setTestProviderEnabled(p, false) } catch (_: Exception) {}
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
        }
        if (statusText.startsWith("rulează")) statusText = "oprit"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopFlag = true
        for (p in providers) { try { lm.removeTestProvider(p) } catch (_: Exception) {} }
        super.onDestroy()
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
