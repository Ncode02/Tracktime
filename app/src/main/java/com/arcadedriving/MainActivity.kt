package com.arcadedriving

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.widget.TextView
import com.arcadedriving.model.GForceState
import com.arcadedriving.render.MiniMapView
import com.arcadedriving.render.RoadSurfaceView
import com.arcadedriving.sensor.SensorEngine
import com.arcadedriving.voice.VoiceEngine
import com.arcadedriving.voice.RoadCurveDetector
import com.google.android.gms.location.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var roadView: RoadSurfaceView
    private lateinit var miniMapView: MiniMapView
    private lateinit var sensorEngine: SensorEngine
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var curveDetector: RoadCurveDetector

    private val PERMISSION_REQUEST = 1001

    // Seguimiento del rumbo GPS para calcular la tasa de giro
    private var lastBearing    = Float.NaN
    private var lastBearingMs  = 0L

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pantalla siempre encendida + fullscreen inmersivo
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main)
        roadView    = findViewById(R.id.roadSurfaceView)
        miniMapView = findViewById(R.id.miniMapView)

        // Botones de zoom del mapa
        findViewById<TextView>(R.id.btnMapZoomIn).setOnClickListener  { miniMapView.zoomIn()  }
        findViewById<TextView>(R.id.btnMapZoomOut).setOnClickListener { miniMapView.zoomOut() }

        voiceEngine  = VoiceEngine(this)
        curveDetector = RoadCurveDetector { radiusM, isRight, distM ->
            voiceEngine.announceCurve(radiusM, isRight, distM)
        }
        sensorEngine = SensorEngine(this) { state ->
            // Callback desde hilo de sensor (no UI thread) → escritura @Volatile segura
            roadView.driveState = state
            voiceEngine.evaluate(state)
            // Sincronizar color del anillo del minimapa con el estado G
            miniMapView.updateNeonColor(neonColorFor(state.gForceState))
        }

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        requestGpsPermission()
    }

    override fun onResume() {
        super.onResume()
        sensorEngine.start()
        // Reinicia GPS: puede haberse parado en onPause (ej. al volver del diálogo de permisos)
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        sensorEngine.stop()
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine.shutdown()
        curveDetector.destroy()
        miniMapView.destroy()
    }

    /** Color neón base según estado G (sin parpadeo, eso lo maneja RoadSurfaceView). */
    private fun neonColorFor(state: GForceState): Int = when (state) {
        GForceState.CRUISING -> android.graphics.Color.parseColor("#00BFFF")
        GForceState.FUN_ZONE -> android.graphics.Color.parseColor("#FFE600")
        GForceState.LIMIT    -> android.graphics.Color.parseColor("#FF2200")
    }

    // ─── GPS ───────────────────────────────────────────────────────────────────

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            sensorEngine.updateSpeed(loc.speed * 3.6f)
            miniMapView.updateLocation(loc.latitude, loc.longitude)

            // ── Calcular tasa de giro en grados/s desde el rumbo GPS ──────────
            // loc.bearing sólo es fiable cuando hay movimiento (> ~5 km/h)
            if (loc.hasBearing() && loc.speed > 1.4f) {  // 1.4 m/s ≈ 5 km/h
                val now = System.currentTimeMillis()
                if (!lastBearing.isNaN() && now > lastBearingMs) {
                    val dt = (now - lastBearingMs) / 1000f
                    var delta = loc.bearing - lastBearing
                    // Normalizar a [-180, 180] para cruzar el 0°/360°
                    if (delta >  180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    val rate = delta / dt
                    sensorEngine.updateHeadingRate(rate)
                }
                lastBearing   = loc.bearing
                lastBearingMs = System.currentTimeMillis()
                // Rotar mapa con el rumbo (heading-up como Maps)
                miniMapView.setBearing(loc.bearing)
                // Detección de curvas por adelantado (OSM)
                curveDetector.update(loc.latitude, loc.longitude, loc.bearing, loc.speed)
            } else if (loc.speed <= 1.4f) {
                sensorEngine.updateHeadingRate(0f)  // parado = carretera recta
                lastBearing = Float.NaN
            }
        }
    }

    private fun requestGpsPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
        // Si deniega: la app funciona sin velocidad GPS (speed queda -1 → HUD muestra "--")
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        fusedLocation.requestLocationUpdates(request, locationCallback, mainLooper)
    }
}
