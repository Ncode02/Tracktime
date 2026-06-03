package com.arcadedriving.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.arcadedriving.model.DriveState
import com.arcadedriving.model.GForceState
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motor de sensores.
 *
 * Usa TYPE_LINEAR_ACCELERATION (ya sin gravedad, proporcionado por el SO via
 * fusión IMU). Si el dispositivo no lo tiene, cae a TYPE_ACCELEROMETER y elimina
 * la gravedad manualmente con un filtro paso-bajo lento.
 *
 * El filtro ALPHA_FAST suaviza el ruido sin añadir latencia perceptible a 60 Hz.
 *
 * Orientación asumida: móvil en soporte de salpicadero, orientación PORTRAIT.
 *   · Eje X → lateral del coche (izquierda/derecha)
 *   · Eje Y → longitudinal del coche (adelante/atrás)
 */
class SensorEngine(
    context: Context,
    private val onUpdate: (DriveState) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensor preferido: LINEAR_ACCELERATION (virtual, ya sin gravedad)
    // Fallback: acelerómetro crudo
    private val primarySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val usingRawAccelerometer: Boolean =
        primarySensor?.type == Sensor.TYPE_ACCELEROMETER

    private val G = 9.81f

    // Filtro paso-bajo lento para extraer gravedad (solo si usamos acelerómetro crudo)
    private val ALPHA_GRAVITY = 0.04f
    private var gravX = 0f
    private var gravY = 0f

    // Filtro paso-bajo rápido para suavizar señal de aceleración lineal
    private val ALPHA_SMOOTH = 0.18f
    private var smoothX = 0f
    private var smoothY = 0f

    @Volatile
    private var currentState = DriveState.EMPTY

    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        primarySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Llamar desde el callback de GPS para actualizar la velocidad. */
    fun updateSpeed(speedKmh: Float) {
        currentState = currentState.copy(speedKmh = speedKmh)
    }

    fun getCurrentState(): DriveState = currentState

    // ─── SensorEventListener ──────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        var rawX = event.values[0]
        var rawY = event.values[1]

        if (usingRawAccelerometer) {
            // Extraer gravedad con filtro IIR lento
            gravX = ALPHA_GRAVITY * rawX + (1f - ALPHA_GRAVITY) * gravX
            gravY = ALPHA_GRAVITY * rawY + (1f - ALPHA_GRAVITY) * gravY
            // Aceleración lineal = total - gravedad
            rawX -= gravX
            rawY -= gravY
        }

        // Suavizado para reducir ruido del sensor
        smoothX = ALPHA_SMOOTH * rawX + (1f - ALPHA_SMOOTH) * smoothX
        smoothY = ALPHA_SMOOTH * rawY + (1f - ALPHA_SMOOTH) * smoothY

        val gLateral = abs(smoothX) / G
        val gLong    = abs(smoothY) / G
        val gTotal   = sqrt(gLateral * gLateral + gLong * gLong)

        // smoothX positivo = el coche gira a la izquierda (la inercia empuja hacia la derecha)
        // → steerDirection positivo = curva a la derecha
        val steerDir = (smoothX / G).coerceIn(-1f, 1f)

        val newState = DriveState(
            gLateral       = gLateral,
            gLongitudinal  = gLong,
            gTotal         = gTotal,
            gForceState    = GForceState.from(gTotal),
            speedKmh       = currentState.speedKmh,
            steerDirection = steerDir
        )

        currentState = newState
        onUpdate(newState)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
