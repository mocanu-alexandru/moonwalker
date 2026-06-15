package com.alexmcn.moonwalker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var rowBar: SeekBar
    private lateinit var speedLbl: TextView
    private lateinit var rowLbl: TextView
    private lateinit var countySpinner: Spinner
    private lateinit var modeSpinner: Spinner

    private val drawnPoints = ArrayList<GeoPoint>()
    private var drawnPolyOverlay: Polygon? = null
    private var curMarker: Marker? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(8.0)
        map.controller.setCenter(GeoPoint(47.15, 27.52))  // Iași default

        status = findViewById(R.id.status)
        speedBar = findViewById(R.id.speedBar)
        rowBar = findViewById(R.id.rowBar)
        speedLbl = findViewById(R.id.speedLbl)
        rowLbl = findViewById(R.id.rowLbl)
        countySpinner = findViewById(R.id.countySpinner)
        modeSpinner = findViewById(R.id.modeSpinner)

        // moduri de definire zonă
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Vizibil pe hartă (bbox)", "Județ din listă", "Desenez pe hartă"))

        // listă județe
        countySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Counties.names())
        countySpinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                showCountyOnMap(Counties.names()[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        })

        // sliders
        speedBar.max = 1000; speedBar.progress = 120
        rowBar.max = 300; rowBar.progress = 130
        speedBar.setOnSeekBarChangeListener(simpleSeek { speedLbl.text = "Viteză: ${speedBar.progress} km/h" })
        rowBar.setOnSeekBarChangeListener(simpleSeek { rowLbl.text = "Distanță rânduri: ${rowBar.progress} m" })
        speedLbl.text = "Viteză: 120 km/h"
        rowLbl.text = "Distanță rânduri: 130 m"

        // desen pe hartă
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (modeSpinner.selectedItemPosition == 2 && p != null) {
                    drawnPoints.add(p); redrawDrawn()
                }
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean { return false }
        }
        map.overlays.add(MapEventsOverlay(receiver))

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            drawnPoints.clear(); drawnPolyOverlay?.let { map.overlays.remove(it) }; map.invalidate()
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startService() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopService() }
        findViewById<Button>(R.id.btnMock).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            UpdateManager.checkAndInstall(this)
        }

        requestPerms()
        pollStatus()
    }

    private fun showCountyOnMap(name: String) {
        val poly = Counties.polygon(name) ?: return
        map.overlays.removeAll { it is Polygon && it != drawnPolyOverlay }
        val p = Polygon().apply {
            points = poly.map { GeoPoint(it[0], it[1]) }
            fillPaint.color = 0x3300FF88.toInt()
            outlinePaint.color = 0xFFFF5252.toInt()
            outlinePaint.strokeWidth = 4f
        }
        map.overlays.add(p)
        // centrează
        val cLat = poly.map { it[0] }.average(); val cLon = poly.map { it[1] }.average()
        map.controller.animateTo(GeoPoint(cLat, cLon))
        map.invalidate()
    }

    private fun redrawDrawn() {
        drawnPolyOverlay?.let { map.overlays.remove(it) }
        if (drawnPoints.size >= 2) {
            drawnPolyOverlay = Polygon().apply {
                points = drawnPoints.toList()
                fillPaint.color = 0x3300AAFF.toInt()
                outlinePaint.color = 0xFF00AAFF.toInt()
                outlinePaint.strokeWidth = 4f
            }
            map.overlays.add(drawnPolyOverlay)
        }
        map.invalidate()
    }

    private fun startService() {
        val i = Intent(this, MockService::class.java)
        i.putExtra(MockService.EXTRA_SPEED_KMH, speedBar.progress.toDouble())
        i.putExtra(MockService.EXTRA_ROW_M, rowBar.progress.toDouble())
        i.putExtra(MockService.EXTRA_STEP_M, 75.0)
        i.putExtra(MockService.EXTRA_VERTICAL, false)
        i.putExtra(MockService.EXTRA_LOOP, true)

        when (modeSpinner.selectedItemPosition) {
            0 -> { // bbox din ce e vizibil pe hartă
                val bb = map.boundingBox
                i.putExtra(MockService.EXTRA_LAT_MIN, bb.latSouth)
                i.putExtra(MockService.EXTRA_LAT_MAX, bb.latNorth)
                i.putExtra(MockService.EXTRA_LON_MIN, bb.lonWest)
                i.putExtra(MockService.EXTRA_LON_MAX, bb.lonEast)
            }
            1 -> { // județ
                val poly = Counties.polygon(countySpinner.selectedItem as String)
                if (poly != null) i.putExtra(MockService.EXTRA_POLY,
                    poly.joinToString(";") { "${it[0]},${it[1]}" })
            }
            2 -> { // desen
                if (drawnPoints.size < 3) { toast("Desenează cel puțin 3 puncte"); return }
                i.putExtra(MockService.EXTRA_POLY,
                    drawnPoints.joinToString(";") { "${it.latitude},${it.longitude}" })
            }
        }
        startForegroundService(i)
        toast("Pornit")
    }

    private fun stopService() {
        val i = Intent(this, MockService::class.java).apply { action = MockService.ACTION_STOP }
        startService(i)
    }

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
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            map.overlays.add(this)
                        }
                    }
                    curMarker!!.position = GeoPoint(lat, lon)
                } else if (!running && curMarker != null) {
                    map.overlays.remove(curMarker)
                    curMarker = null
                }
                map.invalidate()

                ui.postDelayed(this, 1000)
            }
        }, 500)
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val need = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun simpleSeek(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
