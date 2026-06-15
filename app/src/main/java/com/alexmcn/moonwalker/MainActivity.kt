package com.alexmcn.moonwalker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
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
    private lateinit var status: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var rowBar: SeekBar
    private lateinit var posBar: SeekBar
    private lateinit var speedLbl: TextView
    private lateinit var rowLbl: TextView
    private lateinit var posLbl: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var btnToggle: Button

    // ── Stare zonă selectată ────────────────────────────────────────────────
    private var selectedPoly: List<DoubleArray>? = null
    private var selectedName: String = ""
    private var zoneOutline: Polygon? = null
    private var routeSkipOverlay: Polyline? = null
    private var routeActiveOverlay: Polyline? = null
    private var curMarker: Marker? = null
    private val ui = Handler(Looper.getMainLooper())

    // ── Stare spinere (tracking ca să prevenim cascade onItemSelected) ──────
    private var countryPos = -1   // -1 = neiniţializat
    private var level1Pos  = -1
    private var level2Pos  = -1
    private var currentIso3 = ""
    private var level1Data: List<RegionStore.Subdivision> = emptyList()
    private var level2Data: List<RegionStore.Subdivision> = emptyList()

    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map            = findViewById(R.id.map)
        zoneLbl        = findViewById(R.id.zoneLbl)
        countrySpinner = findViewById(R.id.countrySpinner)
        level1Spinner  = findViewById(R.id.level1Spinner)
        level2Spinner  = findViewById(R.id.level2Spinner)
        status         = findViewById(R.id.status)
        speedBar       = findViewById(R.id.speedBar)
        rowBar         = findViewById(R.id.rowBar)
        posBar         = findViewById(R.id.posBar)
        speedLbl       = findViewById(R.id.speedLbl)
        rowLbl         = findViewById(R.id.rowLbl)
        posLbl         = findViewById(R.id.posLbl)
        controlPanel   = findViewById(R.id.controlPanel)
        btnToggle      = findViewById(R.id.btnToggle)

        setupMap()
        setupSpinners()
        setupSliders()
        setupButtons()
        requestPerms()
        pollStatus()
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
                        } else {
                            zoneLbl.text = if (selectedName.isNotEmpty()) selectedName
                                           else "Apasă pe hartă sau alege din liste"
                            toast("Nu s-a putut detecta zona (necesită internet)")
                        }
                    }
                }
                return true
            }
            override fun longPressHelper(p: GeoPoint?) = false
        }
        map.overlays.add(MapEventsOverlay(receiver))
    }

    // ── Spinere ierarhice ────────────────────────────────────────────────────

    private fun setupSpinners() {
        // Populează lista țărilor (placeholder la poziția 0)
        val countryNames = listOf("— alege țara —") + RegionStore.EUROPE.map { it.name }
        countrySpinner.adapter = spinnerAdapter(countryNames)
        level1Spinner.adapter  = spinnerAdapter(listOf("— alege —"))
        level2Spinner.adapter  = spinnerAdapter(listOf("—"))

        countrySpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == countryPos) return
                countryPos = pos
                // reset cascadă
                level1Pos = -1; level2Pos = -1
                level1Data = emptyList(); level2Data = emptyList()
                if (pos == 0) {
                    level1Spinner.adapter = spinnerAdapter(listOf("— alege —"))
                    level2Spinner.adapter = spinnerAdapter(listOf("—"))
                    return
                }
                val country = RegionStore.EUROPE[pos - 1]
                currentIso3 = country.iso3
                level1Spinner.adapter = spinnerAdapter(listOf("Se încarcă…"))
                level2Spinner.adapter = spinnerAdapter(listOf("—"))
                RegionStore.loadLevel1(this@MainActivity, country.iso3) { subs ->
                    runOnUiThread {
                        if (subs.isNullOrEmpty()) {
                            level1Spinner.adapter = spinnerAdapter(listOf("Fără date"))
                        } else {
                            level1Data = subs
                            level1Spinner.adapter = spinnerAdapter(
                                listOf("— alege regiunea —") + subs.map { it.name }
                            )
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
                setZone(sub.name, sub.poly)
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
                if (pos == 0 || level2Data.isEmpty()) return  // placeholder → păstrăm zona nivel 1
                val sub = level2Data[pos - 1]
                setZone(sub.name, sub.poly)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        })
    }

    // ── Zona selectată ────────────────────────────────────────────────────────

    private fun setZone(name: String, poly: List<DoubleArray>) {
        selectedPoly = poly
        selectedName = name
        zoneLbl.text = name

        zoneOutline?.let { map.overlays.remove(it) }
        zoneOutline = Polygon().apply {
            points = poly.map { GeoPoint(it[0], it[1]) }
            fillPaint.color  = 0x3300FF88.toInt()
            outlinePaint.color = 0xFFFF5252.toInt()
            outlinePaint.strokeWidth = 3f
        }
        map.overlays.add(zoneOutline)

        val cLat = poly.map { it[0] }.average()
        val cLon = poly.map { it[1] }.average()
        map.controller.animateTo(GeoPoint(cLat, cLon))
        refreshPreview()
    }

    // ── Preview traseu ────────────────────────────────────────────────────────

    private fun refreshPreview() {
        routeSkipOverlay?.let { map.overlays.remove(it); routeSkipOverlay = null }
        routeActiveOverlay?.let { map.overlays.remove(it); routeActiveOverlay = null }

        val poly = selectedPoly ?: run { map.invalidate(); return }
        val zone    = Zone.fromPolygon(poly)
        val rowM    = maxOf(10.0, rowBar.progress.toDouble())
        val skipFrac = posBar.progress / 100.0

        if (skipFrac > 0.0) {
            val pts = RouteGenerator.computePreview(zone, rowM, 0.0, skipFrac)
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

        val activePts = RouteGenerator.computePreview(zone, rowM, skipFrac, 1.0)
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
        speedBar.max = 1000; speedBar.progress = 120
        rowBar.max   = 300;  rowBar.progress   = 130
        posBar.max   = 100;  posBar.progress   = 0

        speedBar.setOnSeekBarChangeListener(simpleSeek {
            speedLbl.text = "Viteză: ${speedBar.progress} km/h"
        })
        rowBar.setOnSeekBarChangeListener(simpleSeek {
            rowLbl.text = "Distanță rânduri: ${rowBar.progress} m"
            refreshPreview()
        })
        posBar.setOnSeekBarChangeListener(simpleSeek {
            posLbl.text = "Start din: ${posBar.progress}%"
            refreshPreview()
        })

        speedLbl.text = "Viteză: 120 km/h"
        rowLbl.text   = "Distanță rânduri: 130 m"
        posLbl.text   = "Start din: 0%"
    }

    // ── Butoane ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        btnToggle.setOnClickListener {
            val show = controlPanel.visibility == View.GONE
            controlPanel.visibility = if (show) View.VISIBLE else View.GONE
            btnToggle.text = if (show) "▼ ascunde" else "▲ arată controale"
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

        val i = Intent(this, MockService::class.java)
        i.putExtra(MockService.EXTRA_SPEED_KMH,     speedBar.progress.toDouble())
        i.putExtra(MockService.EXTRA_ROW_M,          rowBar.progress.toDouble())
        i.putExtra(MockService.EXTRA_STEP_M,          75.0)
        i.putExtra(MockService.EXTRA_VERTICAL,        false)
        i.putExtra(MockService.EXTRA_LOOP,            true)
        i.putExtra(MockService.EXTRA_SKIP_FRACTION,  posBar.progress / 100.0)
        i.putExtra(MockService.EXTRA_POLY,           poly.joinToString(";") { "${it[0]},${it[1]}" })
        startForegroundService(i)
        toast("Pornit: $selectedName")
    }

    // ── Polling status & marker GPS ───────────────────────────────────────────

    private fun pollStatus() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                val running = MockService.running
                val lat = MockService.curLat
                val lon = MockService.curLon
                status.text = if (running)
                    "● %s\nlat %.5f  lon %.5f\npuncte: %d".format(
                        MockService.statusText, lat, lon, MockService.pointsDone)
                else "○ ${MockService.statusText}"

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

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause()  { super.onPause();  map.onPause()  }
}
