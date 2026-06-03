package com.arcadedriving

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.arcadedriving.render.RoadSurfaceView
import com.arcadedriving.sensor.SensorEngine
import com.arcadedriving.voice.VoiceEngine
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {

    private lateinit var roadView: RoadSurfaceView
    private lateinit var sensorEngine: SensorEngine
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var fusedLocation: FusedLocationProviderClient

    private val PERMISSION_REQUEST = 1001

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
        roadView = findViewById(R.id.roadSurfaceView)

        voiceEngine  = VoiceEngine(this)
        sensorEngine = SensorEngine(this) { state ->
            // Callback desde hilo de sensor (no UI thread) → escritura @Volatile segura
            roadView.driveState = state
            voiceEngine.evaluate(state)
        }

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        requestGpsPermission()
    }

    override fun onResume() {
        super.onResume()
        sensorEngine.start()
    }

    override fun onPause() {
        super.onPause()
        sensorEngine.stop()
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine.shutdown()
    }

    // ─── GPS ───────────────────────────────────────────────────────────────────

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            // loc.speed está en m/s → convertir a km/h
            sensorEngine.updateSpeed(loc.speed * 3.6f)
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
