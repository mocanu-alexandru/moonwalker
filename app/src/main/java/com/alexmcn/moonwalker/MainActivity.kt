package com.alexmcn.moonwalker

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    // ── UI ──────────────────────────────────────────────────────────────────
    private lateinit var map: MapView
    private lateinit var zoneLbl: TextView
    private lateinit var countrySpinner: Spinner
    private lateinit var level1Spinner: Spinner
    private lateinit var level2Spinner: Spinner
    private lateinit var btnDirection: ToggleButton
    private lateinit var status: TextView
    private lateinit var hzBar: SeekBar
    private lateinit var rowBar: SeekBar
    private lateinit var stepBar: SeekBar
    private lateinit var posBar: SeekBar
    private lateinit var hzLbl: TextView
    private lateinit var rowLbl: TextView
    private lateinit var stepLbl: TextView
    private lateinit var posLbl: TextView
    private lateinit var permsLbl: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var btnToggle: Button

    // ── Stare zonă ───────────────────────────────────────────────────────────
    private var selectedPoly: List<DoubleArray>? = null
    private var selectedName: String = ""
    private var zoneOutline: Polygon? = null
    private var routeSkipOverlay: Polyline? = null
    private var routeActiveOverlay: Polyline? = null
    private var curMarker: Marker? = null
    private val ui = Handler(Looper.getMainLooper())
    private var isVertical = false

    // ── Stare spinere ────────────────────────────────────────────────────────
    // Tracking poziție ca să prevenim cascade spurioase onItemSelected
    private var countryPos = -1
    private var level1Pos  = -1
    private var level2Pos  = -1
    private var currentIso3 = ""
    private var level1Data: List<RegionStore.Subdivision> = emptyList()
    private var level2Data: List<RegionStore.Subdivision> = emptyList()

    // Folosit la auto-select din tap pe hartă — previne re-setarea zonei din cascada spinnerelor
    private var spinnerAutoSelect = false
    private var pendingLevel1Name: String? = null

    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        permsLbl       = findViewById(R.id.permsLbl)
        map            = findViewById(R.id.map)
        zoneLbl        = findViewById(R.id.zoneLbl)
        countrySpinner = findViewById(R.id.countrySpinner)
        level1Spinner  = findViewById(R.id.level1Spinner)
        level2Spinner  = findViewById(R.id.level2Spinner)
        btnDirection   = findViewById(R.id.btnDirection)
        status         = findViewById(R.id.status)
        hzBar          = findViewById(R.id.hzBar)
        rowBar         = findViewById(R.id.rowBar)
        stepBar        = findViewById(R.id.stepBar)
        posBar         = findViewById(R.id.posBar)
        hzLbl          = findViewById(R.id.hzLbl)
        rowLbl         = findViewById(R.id.rowLbl)
        stepLbl        = findViewById(R.id.stepLbl)
        posLbl         = findViewById(R.id.posLbl)
        controlPanel   = findViewById(R.id.controlPanel)
        btnToggle      = findViewById(R.id.btnToggle)

        setupMap()
        setupSpinners()
        setupSliders()
        setupButtons()
        requestPerms()
        pollStatus()
        showVersion()
        ui.postDelayed({ UpdateManager.checkAndInstall(this, silent = true) }, 3000)
    }

    // ── Hartă ────────────────────────────────────────────────────────────────

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(8.0)
        map.controller.setCenter(GeoPoint(47.15, 27.52))

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                zoneLbl.text = "Se detectează zona…"
                RegionStore.reverseGeocode(p.latitude, p.longitude, map.zoomLevelDouble) { zone ->
                    runOnUiThread {
                        if (zone?.poly != null) {
                            setZone(zone.name, zone.poly)
                            autoSelectSpinners(zone.countryCode, zone.level1Name)
                        } else {
                            updateTapHint()
                            toast("Nu s-a putut detecta zona (necesită internet)")
                        }
                    }
                }
                return true
            }
            override fun longPressHelper(p: GeoPoint?) = false
        }
        map.overlays.add(MapEventsOverlay(receiver))

        // Actualizează hint-ul în zoneLbl când zoom-ul se schimbă (doar dacă nu e selectată o zonă)
        map.addMapListener(object : MapListener {
            override fun onScroll(e: ScrollEvent?) = false
            override fun onZoom(e: ZoomEvent?): Boolean {
                if (selectedName.isEmpty()) updateTapHint()
                return false
            }
        })
        updateTapHint()
    }

    // ── Spinere ierarhice ─────────────────────────────────────────────────────

    private fun setupSpinners() {
        val countryNames = listOf("— alege țara —") + RegionStore.EUROPE.map { it.name }
        countrySpinner.adapter = spinnerAdapter(countryNames)
        level1Spinner.adapter  = spinnerAdapter(listOf("— alege —"))
        level2Spinner.adapter  = spinnerAdapter(listOf("—"))

        countrySpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == countryPos) return
                countryPos = pos
                level1Pos = -1; level2Pos = -1
                level1Data = emptyList(); level2Data = emptyList()
                if (pos == 0) {
                    level1Spinner.adapter = spinnerAdapter(listOf("— alege —"))
                    level2Spinner.adapter = spinnerAdapter(listOf("—"))
                    selectedName = ""; selectedPoly = null
                    zoneOutline?.let { map.overlays.remove(it) }; zoneOutline = null
                    routeSkipOverlay?.let { map.overlays.remove(it) }; routeSkipOverlay = null
                    routeActiveOverlay?.let { map.overlays.remove(it) }; routeActiveOverlay = null
                    map.invalidate(); updateTapHint()
                    return
                }
                val country = RegionStore.EUROPE[pos - 1]
                currentIso3 = country.iso3
                level1Spinner.adapter = spinnerAdapter(listOf("Se încarcă…"))
                level2Spinner.adapter = spinnerAdapter(listOf("—"))
                // Contur țară din Nominatim (vizual imediat, înlocuit de regiune la selecție)
                zoneLbl.text = "Se încarcă ${country.name}…"
                RegionStore.searchCountryPolygon(this@MainActivity, country.iso2) { poly ->
                    runOnUiThread {
                        if (poly != null && selectedName.isEmpty()) setZone(country.name, poly)
                        else if (poly != null && selectedName == country.name) setZone(country.name, poly)
                    }
                }
                RegionStore.loadLevel1(this@MainActivity, country.iso3) { subs ->
                    runOnUiThread {
                        if (subs.isNullOrEmpty()) {
                            level1Spinner.adapter = spinnerAdapter(listOf("Fără date"))
                            spinnerAutoSelect = false
                        } else {
                            level1Data = subs
                            level1Spinner.adapter = spinnerAdapter(
                                listOf("— alege regiunea —") + subs.map { it.name }
                            )
                            if (spinnerAutoSelect) tryAutoSelectLevel1()
                        }
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        })

        level1Spinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == level1Pos) return
                level1Pos = pos
                level2Pos = -1; level2Data = emptyList()
                if (pos == 0 || level1Data.isEmpty()) {
                    level2Spinner.adapter = spinnerAdapter(listOf("—"))
                    return
                }
                val sub = level1Data[pos - 1]
                // Dacă selecția vine din tap (spinnerAutoSelect), nu suprascrim zona Nominatim
                if (!spinnerAutoSelect) setZone(sub.name, sub.poly)
                spinnerAutoSelect = false  // consumat
                level2Spinner.adapter = spinnerAdapter(listOf("Se descarcă…"))
                RegionStore.loadLevel2(this@MainActivity, currentIso3, sub.name) { subs ->
                    runOnUiThread {
                        if (subs.isNullOrEmpty()) {
                            level2Spinner.adapter = spinnerAdapter(listOf("— fără subdiviziuni —"))
                        } else {
                            level2Data = subs
                            level2Spinner.adapter = spinnerAdapter(
                                listOf("— ${sub.name} (nivel 1) —") + subs.map { it.name }
                            )
                        }
                    }
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        })

        level2Spinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == level2Pos) return
                level2Pos = pos
                if (pos == 0 || level2Data.isEmpty()) return
                val sub = level2Data[pos - 1]
                setZone(sub.name, sub.poly)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        })
    }

    /**
     * Încearcă să selecteze automat în spinere după un tap pe hartă.
     * Setăm spinnerAutoSelect=true ca să nu suprascriem zona Nominatim
     * când cascada onItemSelected se declanșează programatic.
     */
    private fun autoSelectSpinners(countryCode: String?, level1Name: String?) {
        val iso2 = countryCode?.uppercase() ?: return
        val countryIdx = RegionStore.EUROPE.indexOfFirst { it.iso2 == iso2 }
        if (countryIdx < 0) return

        pendingLevel1Name = level1Name
        spinnerAutoSelect = true

        val spinnerPos = countryIdx + 1  // +1 pentru placeholder
        if (spinnerPos == countryPos) {
            // Aceeași țară deja selectată — level1Data deja încărcată
            tryAutoSelectLevel1()
        } else {
            // Altă țară — declanșăm cascada (spinnerAutoSelect rămâne true până la level1)
            countrySpinner.setSelection(spinnerPos)
        }
    }

    /** Caută pendingLevel1Name în level1Data și selectează în level1Spinner dacă găsește. */
    private fun tryAutoSelectLevel1() {
        val pending = pendingLevel1Name ?: run { spinnerAutoSelect = false; return }
        pendingLevel1Name = null
        val norm = pending.normForMatch()
        val idx = level1Data.indexOfFirst { sub ->
            val sNorm = sub.name.normForMatch()
            sNorm == norm || sNorm.contains(norm) || norm.contains(sNorm)
        }
        if (idx >= 0) {
            level1Spinner.setSelection(idx + 1)
        } else {
            spinnerAutoSelect = false
        }
    }

    // ── Zona selectată + estimare timp ───────────────────────────────────────

    private fun setZone(name: String, poly: List<DoubleArray>) {
        selectedPoly = poly
        selectedName = name
        zoneLbl.text = name   // provizoriu; estimateAndShow() completează

        zoneOutline?.let { map.overlays.remove(it) }
        zoneOutline = Polygon().apply {
            points = poly.map { GeoPoint(it[0], it[1]) }
            fillPaint.color    = 0x3300FF88.toInt()
            outlinePaint.color = 0xFFFF5252.toInt()
            outlinePaint.strokeWidth = 3f
        }
        map.overlays.add(zoneOutline)

        val cLat = poly.map { it[0] }.average()
        val cLon = poly.map { it[1] }.average()
        map.controller.animateTo(GeoPoint(cLat, cLon))
        refreshPreview()
        estimateAndShow()
    }

    private fun estimateAndShow() {
        val poly = selectedPoly ?: return
        val zone     = Zone.fromPolygon(poly)
        val rowM     = maxOf(10.0, rowBar.progress.toDouble())
        val hz       = hzBar.progress + 1
        val skipFrac = posBar.progress / 100.0
        val stepM    = (stepBar.progress + 1).toDouble()
        val sec      = RouteGenerator.estimateDuration(zone, rowM, stepM, hz, isVertical, skipFrac)
        val timeStr  = fmtDuration(sec)
        val suffix   = if (skipFrac > 0.0) "  •  ~$timeStr restant" else "  •  ~$timeStr"
        zoneLbl.text = "$selectedName$suffix"
    }

    private fun fmtDuration(sec: Long): String = when {
        sec <= 0    -> "< 1s"
        sec < 60    -> "${sec}s"
        sec < 3600  -> "${sec / 60}min ${sec % 60}s"
        else        -> "${sec / 3600}h ${(sec % 3600) / 60}min"
    }

    // ── Preview traseu ────────────────────────────────────────────────────────

    private fun refreshPreview() {
        routeSkipOverlay?.let   { map.overlays.remove(it); routeSkipOverlay = null }
        routeActiveOverlay?.let { map.overlays.remove(it); routeActiveOverlay = null }

        val poly = selectedPoly ?: run { map.invalidate(); return }
        val zone     = Zone.fromPolygon(poly)
        val rowM     = maxOf(10.0, rowBar.progress.toDouble())
        val skipFrac = posBar.progress / 100.0

        if (skipFrac > 0.0) {
            val pts = RouteGenerator.computePreview(zone, rowM, 0.0, skipFrac, vertical = isVertical)
            if (pts.isNotEmpty()) {
                routeSkipOverlay = Polyline().apply {
                    setPoints(pts.map { GeoPoint(it[0], it[1]) })
                    outlinePaint.color = 0xFF888888.toInt()
                    outlinePaint.strokeWidth = 1.0f
                    outlinePaint.alpha = 80
                }
                map.overlays.add(routeSkipOverlay)
            }
        }

        val activePts = RouteGenerator.computePreview(zone, rowM, skipFrac, 1.0, vertical = isVertical)
        if (activePts.isNotEmpty()) {
            routeActiveOverlay = Polyline().apply {
                setPoints(activePts.map { GeoPoint(it[0], it[1]) })
                outlinePaint.color = 0xFF1565C0.toInt()
                outlinePaint.strokeWidth = 1.5f
                outlinePaint.alpha = 200
            }
            map.overlays.add(routeActiveOverlay)
        }
        map.invalidate()
    }

    // ── Slidere ───────────────────────────────────────────────────────────────

    private fun setupSliders() {
        // hzBar: 1-20 injecții/secundă (valoarea afișată = progress + 1)
        hzBar.max = 19; hzBar.progress = 0   // 0→1Hz … 19→20Hz
        rowBar.max = 300; rowBar.progress = 100
        stepBar.max = 50; stepBar.progress = 9  // progress+1 → range 1-51m, default 10m
        posBar.max = 100; posBar.progress = 0

        hzBar.setOnSeekBarChangeListener(simpleSeek { updateHzLabel(); estimateAndShow() })
        rowBar.setOnSeekBarChangeListener(simpleSeek {
            rowLbl.text = "Distanță rânduri: ${rowBar.progress} m"
            updateHzLabel()
            refreshPreview()
            estimateAndShow()
        })
        stepBar.setOnSeekBarChangeListener(simpleSeek {
            val m = stepBar.progress + 1
            stepLbl.text = "Pas pe rând: $m m"
            updateHzLabel()
            refreshPreview()
            estimateAndShow()
        })
        posBar.setOnSeekBarChangeListener(simpleSeek {
            posLbl.text = "Start din: ${posBar.progress}%"
            refreshPreview()
            estimateAndShow()
        })

        updateHzLabel()
        rowLbl.text = "Distanță rânduri: 100 m"
        stepLbl.text = "Pas pe rând: 10 m"
        posLbl.text = "Start din: 0%"
    }

    private fun updateHzLabel() {
        val hz = hzBar.progress + 1
        val stepM = stepBar.progress + 1
        val speedKmh = (stepM * hz * 3.6).toInt()
        hzLbl.text = "$hz inj/s → $speedKmh km/h virtual"
    }

    // ── Butoane ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnToggle.setOnClickListener {
            val show = controlPanel.visibility == View.GONE
            controlPanel.visibility = if (show) View.VISIBLE else View.GONE
            btnToggle.text = if (show) "▼ ascunde" else "▲ arată controale"
        }
        btnDirection.setOnCheckedChangeListener { _, checked ->
            isVertical = checked
            refreshPreview()
            estimateAndShow()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startService() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, MockService::class.java).apply { action = MockService.ACTION_STOP })
        }
        findViewById<Button>(R.id.btnMock).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            UpdateManager.checkAndInstall(this)
        }
    }

    // ── Pornire serviciu ──────────────────────────────────────────────────────

    private fun startService() {
        val poly = selectedPoly
        if (poly == null) { toast("Selectează o zonă mai întâi"); return }

        val hz = hzBar.progress + 1
        val stepM = (stepBar.progress + 1).toDouble()
        val speedKmh = (stepM * hz * 3.6).toInt()
        val i = Intent(this, MockService::class.java)
        i.putExtra(MockService.EXTRA_TICK_HZ,        hz)
        i.putExtra(MockService.EXTRA_ROW_M,          rowBar.progress.toDouble())
        i.putExtra(MockService.EXTRA_STEP_M,          stepM)
        i.putExtra(MockService.EXTRA_VERTICAL,        isVertical)
        i.putExtra(MockService.EXTRA_LOOP,            true)
        i.putExtra(MockService.EXTRA_SKIP_FRACTION,  posBar.progress / 100.0)
        i.putExtra(MockService.EXTRA_POLY,           poly.joinToString(";") { "${it[0]},${it[1]}" })
        startForegroundService(i)
        val dir = if (isVertical) "vertical" else "orizontal"
        toast("Pornit $dir: $selectedName • $hz Hz • $speedKmh km/h")
    }

    // ── Polling status & marker GPS ───────────────────────────────────────────

    private fun pollStatus() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                val running = MockService.running
                val lat = MockService.curLat
                val lon = MockService.curLon
                status.text = if (running) {
                    "● %s\nlat %.5f  lon %.5f\npuncte: %d".format(
                        MockService.statusText, lat, lon, MockService.pointsDone)
                } else "○ ${MockService.statusText}"

                if (running && lat != 0.0) {
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
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

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun simpleSeek(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun updateTapHint() {
        val level = when {
            map.zoomLevelDouble < 6  -> "țară"
            map.zoomLevelDouble < 8  -> "regiune"
            map.zoomLevelDouble < 10 -> "județ / district"
            map.zoomLevelDouble < 12 -> "oraș"
            map.zoomLevelDouble < 14 -> "comună / sat"
            else                     -> "cartier / stradă"
        }
        zoneLbl.text = "Apasă pe hartă → $level"
    }

    private fun showVersion() {
        try {
            val v = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.versionTv).text = "Moonwalker $v"
        } catch (_: Exception) {}
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); map.onResume(); refreshPerms() }
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

        if (issues.isEmpty()) {
            permsLbl.visibility = View.GONE
        } else {
            permsLbl.text = issues.joinToString("   ")
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
