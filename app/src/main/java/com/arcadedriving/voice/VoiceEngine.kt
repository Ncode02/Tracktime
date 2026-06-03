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

    /**
     * Evalúa el estado actual y dispara TTS si procede.
     * Llamar desde cualquier hilo (sensor thread).
     */
    fun evaluate(state: DriveState) {
        if (!ready.get() || speaking.get()) return

        val gState = state.gForceState
        val now    = System.currentTimeMillis()

        // Detectar cambio de estado
        if (gState != lastState) {
            lastState = gState
            stateEntry.set(now)
        }

        // Esperar mínimo en el estado (evita hablar por picos instantáneos)
        if (now - stateEntry.get() < MIN_STATE_MS) return

        // Comprobar cooldown
        val last     = lastSpoken.getOrDefault(gState, 0L)
        val cooldown = cooldownMs.getOrDefault(gState, 10_000L)
        if (now - last < cooldown) return

        // No hablar de velocidad si GPS aún no disponible
        if (gState == GForceState.CRUISING && state.speedKmh < 0f) return

        val msg = ArcadeMessages.next(gState, state.speedKmh)
        lastSpoken[gState] = now
        speak(msg)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    /**
     * Anuncia una curva detectada por GPS.
     * @param radiusMeters radio estimado en metros (speed / headingRate_rad_s)
     * @param isRight true = giro a la derecha
     */
    fun announceCurve(radiusMeters: Float, isRight: Boolean) {
        if (!ready.get()) return
        val dir = if (isRight) "derecha" else "izquierda"
        val (tipo, queue) = when {
            radiusMeters < 60f  -> Pair("¡Muy cerrada! ¡Frena!",  TextToSpeech.QUEUE_FLUSH)
            radiusMeters < 150f -> Pair("cerrada",                 TextToSpeech.QUEUE_ADD)
            radiusMeters < 400f -> Pair("abierta",                 TextToSpeech.QUEUE_ADD)
            else                -> Pair("muy abierta",             TextToSpeech.QUEUE_ADD)
        }
        tts.speak("Curva $tipo, $dir", queue, null, "curve_${System.nanoTime()}")
        speaking.set(true)
    }

    // ─── Interno ──────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        // QUEUE_FLUSH asegura que un mensaje urgente (LIMIT) interrumpe al anterior
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "arcade_${System.nanoTime()}")
    }
}
