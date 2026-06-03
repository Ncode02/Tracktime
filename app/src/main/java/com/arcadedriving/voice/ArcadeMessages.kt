package com.arcadedriving.voice

import com.arcadedriving.model.GForceState

/**
 * Pools de mensajes arcade por estado de fuerza G.
 * Los mensajes se sirven en orden circular para variedad.
 */
object ArcadeMessages {

    // < 0.5 G && velocidad < 15 km/h
    private val stopped = listOf(
        "¡VAS PARAO!",
        "¿Aparcando o conduciendo?",
        "¡Mi abuela va más rápido!",
        "¡El semáforo está en verde, chaval!",
        "¿Nos vamos a pata o qué?"
    )

    // < 0.5 G && velocidad normal → hype tranquilo
    private val cruising = listOf(
        "¡Vamos, desconfínate!",
        "¡El motor quiere cantar, dale gas!",
        "¡La carretera es tuya!",
        "Más gas, que no mordemos.",
        "¡A ver si despegamos hoy!",
        "¡Esto está muy tranquilito para mi gusto!"
    )

    // 0.5 – 0.85 G → ritmo alegre
    private val funZone = listOf(
        "¡Buena trazada!",
        "¡Mantén el gas!",
        "¡Eso es pilotaje!",
        "¡Ahora sí que estamos!",
        "¡Bonita curva, crack!",
        "¡Perfecto, sigue así!",
        "¡Eso sí que es conducir!"
    )

    // ≥ 0.85 G → peligro de salida
    private val limit = listOf(
        "¡Frena, frena!",
        "¡Nos la damos!",
        "¡Esto no es la Fórmula Uno!",
        "¡Las ruedas están llorando!",
        "¡Frena que no somos un avión!",
        "¡Aquí acabamos en la cuneta!",
        "¡Los neumáticos te lo están pidiendo!"
    )

    private val indices = mutableMapOf<String, Int>()

    /**
     * Devuelve el siguiente mensaje para el estado dado, en ciclo continuo.
     * [speedKmh] = -1 significa que el GPS aún no tiene señal (no se habla de velocidad).
     */
    fun next(state: GForceState, speedKmh: Float): String {
        val pool = when {
            state == GForceState.CRUISING && speedKmh in 0f..15f -> stopped
            state == GForceState.CRUISING                        -> cruising
            state == GForceState.FUN_ZONE                        -> funZone
            state == GForceState.LIMIT                           -> limit
            else                                                 -> cruising
        }
        val key = state.name + if (speedKmh in 0f..15f) "_slow" else ""
        val i = indices.getOrDefault(key, 0)
        indices[key] = (i + 1) % pool.size
        return pool[i]
    }
}
