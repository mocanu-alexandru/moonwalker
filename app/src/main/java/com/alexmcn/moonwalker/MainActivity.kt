package com.alexmcn.moonwalker

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * UI minimal, MOD UNIC AUTONOM. La deschiderea aplicației pornește SINGUR acoperirea autonomă a
 * României (seek găuri apropiate → pătrate concentrice → seek final). Nu există mod manual / panou
 * developer / butoane extra: ecranul e doar harta + status + un singur buton de control (pornește /
 * pauză / oprire) care e doar un override peste pornirea automată.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var permsLbl: TextView
    private lateinit var btnStop: Button
    private lateinit var btnTour: Button

    private var curMarker: Marker? = null
    private val ui = Handler(Looper.getMainLooper())

    // Ultima locație reală cunoscută (capturată cât timp mock-ul NU rulează) — ancoră pt. AUTO + „acasă"
    private var homeLocation: GeoPoint? = null
    private val lm by lazy {
        getSystemService(LOCATION_SERVICE) as android.location.LocationManager
    }

    companion object {
        // Pornire automată O SINGURĂ DATĂ pe viața procesului (process-scoped, NU instance): rotația /
        // config-change reface onCreate, dar dacă userul a oprit, NU repornim. La relansare proces = fresh.
        @Volatile private var autoStarted = false
        // La fel, verificările de setup (update + mock location) o singură dată per proces.
        @Volatile private var setupChecked = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        permsLbl = findViewById(R.id.permsLbl)
        permsLbl.setOnClickListener { openBatterySettings() }
        map      = findViewById(R.id.map)
        status   = findViewById(R.id.status)
        btnStop  = findViewById(R.id.btnStop)
        btnTour  = findViewById(R.id.btnTour)

        setupMap()
        setupButtons()
        requestPerms()
        pollStatus()
        showVersion()
        verifySetupAtStartup()   // update + mock-location verificate o dată la pornire (ca Lockito)

        // Citește masca (root + Bump) pe thread separat; la succes pornește AUTO automat.
        refreshMaskThenAutoStart()
    }

    // ── Hartă ────────────────────────────────────────────────────────────────

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(8.0)
        map.controller.setCenter(GeoPoint(47.15, 27.52))
    }

    // ── Butoane ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        // Buton UNIC, conștient de stare (vezi pollStatus pt. etichetă):
        //  • rulează  → apăsare scurtă = PAUZĂ (parcat); apăsare lungă = oprire completă (acasă)
        //  • parcat   → apăsare scurtă = CONTINUĂ;        apăsare lungă = oprire completă
        //  • oprit    → apăsare scurtă = PORNEȘTE (autonom)
        btnStop.setOnClickListener {
            if (MockService.running) {
                startService(Intent(this, MockService::class.java).apply { action = MockService.ACTION_STOP })
                toast("⏸ Parcat — apasă din nou ca să continui (ține apăsat = oprire completă)")
            } else {
                startAuto()
            }
        }
        btnStop.setOnLongClickListener {
            startService(Intent(this, MockService::class.java).apply { action = MockService.ACTION_RELEASE })
            toast("■ Oprit complet — revii la GPS real")
            true
        }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { goHome() }
        btnTour.setOnClickListener { startWorldTour() }
    }

    /**
     * MOD „TUR CAPITALE": conduce prin toate capitalele lumii (nearest-first + 2-opt din locația ta),
     * la ~1080 km/h, în buclă. Pură călătorie — NU folosește masca/calibrarea, deci merge oriunde.
     * Override peste pornirea automată; butonul de sus (STOP/CONTINUĂ) controlează apoi pauza/oprirea.
     */
    private fun startWorldTour() {
        // poly „ancoră" doar ca să treacă verificarea din service; turul folosește locația reală + capitalele.
        val h = homeLocation
        val poly = if (h != null)
            "%.5f,%.5f;%.5f,%.5f;%.5f,%.5f".format(
                h.latitude - 0.003, h.longitude - 0.003,
                h.latitude + 0.003, h.longitude + 0.003,
                h.latitude + 0.003, h.longitude - 0.003)
        else "47.157,27.577;47.163,27.583;47.163,27.577"
        val i = Intent(this, MockService::class.java).apply {
            // 6 inj/s × pas 100m = ~2160 km/h (tur „pură călătorie", fără validare Bump → pas mărit ok).
            putExtra(MockService.EXTRA_TICK_HZ, 6)
            putExtra(MockService.EXTRA_ROW_M, 75.0)
            putExtra(MockService.EXTRA_STEP_M, 100.0)
            putExtra(MockService.EXTRA_LOOP, true)
            putExtra(MockService.EXTRA_WORLD_TOUR, true)
            putExtra(MockService.EXTRA_POLY, poly)
            // Tur COMPLET pornind din Chișinău (cerut de user). Trimis la fiecare pornire → fiabil
            // indiferent de starea persistată; serviciul îl folosește ca ancoră + punct de start.
            putExtra(MockService.EXTRA_TOUR_RESUME_CAP, "Chișinău")
        }
        startForegroundService(i)
        toast("🌍 TUR CAPITALE — pornește din Chișinău prin toate capitalele lumii (~2160 km/h)")
    }

    /**
     * Verificări O SINGURĂ DATĂ la pornirea aplicației (ca Lockito) — fără butoane permanente:
     *  • UPDATE: verifică/instalează în fundal la pornire (silent — fără toast dacă e la zi);
     *  • MOCK LOCATION: dacă Moonwalker nu e setat ca aplicație Mock Location, deschide Developer
     *    Options o dată ca să-l activezi. După setare, la următoarea pornire nu mai deschide nimic.
     * Process-scoped (setupChecked) → rotația/config-change NU re-declanșează.
     */
    private fun verifySetupAtStartup() {
        if (setupChecked) return
        setupChecked = true
        ui.postDelayed({ UpdateManager.checkAndInstall(this, silent = true) }, 3000)
        if (!isMockLocationEnabled()) {
            toast("Activează Moonwalker ca aplicație Mock Location (Developer Options)")
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    // ── Mască deblocate + pornire automată ────────────────────────────────────

    /** Citește zonele deblocate din Bump (root + H3) pe thread separat; la succes încearcă auto-start. */
    private fun refreshMaskThenAutoStart() {
        Thread {
            UnlockedMask.refresh(applicationContext)
            ui.post { maybeAutoStart() }
        }.start()
    }

    /**
     * Pornește AUTO automat o singură dată per proces, când totul e gata (mască + mock location).
     * Dacă userul a oprit deja (running/holding) marchează ca pornit și nu mai intervine.
     */
    private fun maybeAutoStart() {
        if (autoStarted) return
        if (MockService.running || MockService.holding) { autoStarted = true; return }
        if (!UnlockedMask.isReady) return       // mască indisponibilă (root/Bump) → așteaptă, retry pe onResume
        if (!isMockLocationEnabled()) return     // setup incomplet → userul fixează, retry pe onResume
        autoStarted = true
        startAuto()
    }

    /**
     * MOD UNIC AUTONOM: acoperă toată ROMÂNIA pornind din locația ta — întâi curăță găurile singulare
     * apropiate (seek nearest-first), apoi extinde în pătrate concentrice, apoi seek final pe margini.
     * Învață singur setările (CoverageController) ca să prindă tot dintr-o trecere. Necesită masca
     * (root + Bump) + Mock Location în Developer Options. Rulează până la STOP.
     */
    private fun startAuto() {
        if (!UnlockedMask.isReady) {
            toast("Masca nu e gata (root/Bump?) — fără ea nu pot măsura/sări deblocatele"); return
        }
        // poly „ancoră" doar ca să treacă verificarea din service; auto folosește locația reală.
        val h = homeLocation
        val poly = if (h != null)
            "%.5f,%.5f;%.5f,%.5f;%.5f,%.5f".format(
                h.latitude - 0.003, h.longitude - 0.003,
                h.latitude + 0.003, h.longitude + 0.003,
                h.latitude + 0.003, h.longitude - 0.003)
        else "47.157,27.577;47.163,27.583;47.163,27.577"
        val i = Intent(this, MockService::class.java).apply {
            // cadența de injecție (Hz) e fixă în auto — controllerul reglează rowM & pasul (viteza).
            // Pornim de la „soft spot": 6 inj/s, rânduri 75m, pas 25m (≈540 km/h) — apoi auto-tuning.
            putExtra(MockService.EXTRA_TICK_HZ, 6)
            putExtra(MockService.EXTRA_ROW_M, 75.0)
            putExtra(MockService.EXTRA_STEP_M, 25.0)
            putExtra(MockService.EXTRA_VERTICAL, false)
            putExtra(MockService.EXTRA_LOOP, false)
            putExtra(MockService.EXTRA_AUTO, true)
            putExtra(MockService.EXTRA_POLY, poly)
        }
        startForegroundService(i)
        toast("🤖 AUTO pornit: blast-radius în pătrate concentrice, județ cu județ (nearest-first) + backstop cusături")
    }

    /** Centrează harta pe ultima locație reală cunoscută (acasă). */
    private fun goHome() {
        val target = homeLocation ?: run {
            val loc = try {
                lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) { null }
            loc?.let { GeoPoint(it.latitude, it.longitude) }
        }
        if (target != null) {
            map.controller.animateTo(target)
            map.controller.setZoom(16.0)
        } else {
            toast("Locație necunoscută încă")
        }
    }

    // ── Polling status & marker GPS ───────────────────────────────────────────

    private fun pollStatus() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                val running = MockService.running
                val holding = MockService.holding
                val active = running || holding   // parcat = tot mock activ (NU e locația reală)
                if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Doar când mock-ul e COMPLET oprit, getLastKnownLocation = locația reală → "acasă".
                // Cât timp e parcat (holding), lm întoarce poziția mock → NU o lua drept acasă.
                if (!active) {
                    try {
                        val real = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        if (real != null) homeLocation = GeoPoint(real.latitude, real.longitude)
                    } catch (_: SecurityException) {}
                }
                val lat = MockService.curLat
                val lon = MockService.curLon
                status.text = when {
                    running -> "● %s\nlat %.5f  lon %.5f\npuncte: %d".format(
                        MockService.statusText, lat, lon, MockService.pointsDone)
                    holding -> "⏸ parcat\nlat %.5f  lon %.5f".format(lat, lon)
                    else    -> "○ ${MockService.statusText}"
                }
                btnStop.text = when {
                    running -> "■ STOP  (ține apăsat = oprire completă)"
                    holding -> "▶ CONTINUĂ  (ține apăsat = oprire completă)"
                    else    -> "🤖 PORNEȘTE — acoperă România (autonom)"
                }

                if (active && lat != 0.0) {
                    if (curMarker == null) {
                        curMarker = Marker(map).apply {
                            icon = makeDotIcon()
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            map.overlays.add(this)
                        }
                    }
                    curMarker!!.position = GeoPoint(lat, lon)
                    map.invalidate()
                } else if (!running && curMarker != null) {
                    map.overlays.remove(curMarker); curMarker = null; map.invalidate()
                }
                ui.postDelayed(this, 1000)
            }
        }, 500)
    }

    // ── Permisiuni / setup ────────────────────────────────────────────────────

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) openBatterySettings()
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun makeDotIcon(): android.graphics.drawable.BitmapDrawable {
        val px = (20 * resources.displayMetrics.density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).also { cv ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = 0xFF1565C0.toInt()
            cv.drawCircle(px / 2f, px / 2f, px / 2f * 0.9f, p)
            p.color = 0xFFFFFFFF.toInt()
            cv.drawCircle(px / 2f, px / 2f, px / 2f * 0.35f, p)
        }
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun showVersion() {
        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.versionTv).text = "Moonwalker $v"
        } catch (_: Exception) {}
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume(); map.onResume(); refreshPerms()
        // Retry pornire automată dacă încă nu s-a pornit (ex. userul tocmai a activat Mock Location
        // sau root-ul a devenit disponibil). Guard-ul autoStarted previne repornirea după un STOP.
        if (!autoStarted) {
            if (UnlockedMask.isReady) maybeAutoStart() else refreshMaskThenAutoStart()
        }
    }
    override fun onPause()  { super.onPause();  map.onPause()  }

    private fun refreshPerms() {
        val issues = mutableListOf<String>()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            issues.add("✗ GPS")

        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            issues.add("✗ Notificări")

        if (!isMockLocationEnabled())
            issues.add("✗ Mock Location (Developer Options)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls())
            issues.add("✗ Instalare APK (permite surse necunoscute)")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            issues.add("✗ Optimizare baterie activă — atinge pentru a dezactiva")

        if (issues.isEmpty()) {
            permsLbl.visibility = View.GONE
        } else {
            permsLbl.text = issues.joinToString("\n")
            permsLbl.setTextColor(0xFFFF5252.toInt())
            permsLbl.visibility = View.VISIBLE
        }
    }

    private fun isMockLocationEnabled(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
