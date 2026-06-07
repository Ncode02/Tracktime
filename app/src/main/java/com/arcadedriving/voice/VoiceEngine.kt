package com.arcadedriving.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.arcadedriving.model.DriveState
import com.arcadedriving.model.GForceState
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Motor TTS arcade.
 *
 * Reglas anti-spam:
 *  - Cooldown por estado: LIMIT 3.5s · FUN_ZONE 6s · CRUISING 12s
 *  - No habla si ya está hablando (QUEUE_FLUSH descartaría la frase anterior,
 *    lo cual se siente brusco; esperamos a que termine)
 *  - Ignora el estado si llevamos < 800 ms en él (evita flapping rápido)
 *  - No habla de velocidad hasta que el GPS tiene señal (speedKmh >= 0)
 */
class VoiceEngine(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private val ready    = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    // Cooldown en ms por estado
    private val cooldownMs = mapOf(
        GForceState.CRUISING to 12_000L,
        GForceState.FUN_ZONE to  6_000L,
        GForceState.LIMIT    to  3_500L
    )

    private val lastSpoken  = mutableMapOf<GForceState, Long>()
    private val stateEntry  = AtomicLong(0L)
    private var lastState   = GForceState.CRUISING

    private val MIN_STATE_MS = 800L   // tiempo mínimo en un estado antes de hablar

    // ─── TextToSpeech.OnInitListener ─────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return

        val langResult = tts.setLanguage(Locale("es", "ES"))
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED) return

        tts.setSpeechRate(1.12f)   // ligeramente rápido → tono más vivo
        tts.setPitch(1.08f)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking.set(true) }
            override fun onDone(id: String?)  { speaking.set(false) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { speaking.set(false) }
        })

        ready.set(true)
    }

    // ─── API pública ──────────────────────────────────────────────────────────

    /** No hace nada — solo curvas activas. Mantenida para compatibilidad. */
    fun evaluate(state: DriveState) { /* desactivado: solo TTS de curvas */ }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    /**
     * Anuncia una curva detectada por adelantado con OSM.
     * @param radiusMeters  radio estimado en metros
     * @param isRight       true = giro a la derecha
     * @param distanceMeters distancia hasta la curva en metros
     */
    fun announceCurve(radiusMeters: Float, isRight: Boolean, distanceMeters: Float) {
        if (!ready.get()) return
        val dir    = if (isRight) "derecha" else "izquierda"
        val meters = ((distanceMeters + 25f) / 50f).toInt() * 50   // redondeo a ±50 m
        val (tipo, queue) = when {
            radiusMeters < 60f  -> Pair("muy cerrada, ¡frena!",  TextToSpeech.QUEUE_FLUSH)
            radiusMeters < 150f -> Pair("cerrada",               TextToSpeech.QUEUE_ADD)
            radiusMeters < 400f -> Pair("abierta",               TextToSpeech.QUEUE_ADD)
            else                -> Pair("muy abierta",           TextToSpeech.QUEUE_ADD)
        }
        tts.speak("En $meters metros, curva $tipo a la $dir", queue, null, "curve_${System.nanoTime()}")
        speaking.set(true)
    }

    // ─── Interno ──────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        // QUEUE_FLUSH asegura que un mensaje urgente (LIMIT) interrumpe al anterior
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arcade_${System.nanoTime()}")
    }
}
