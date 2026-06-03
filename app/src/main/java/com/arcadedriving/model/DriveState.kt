package com.arcadedriving.model

/**
 * Snapshot inmutable del estado de conducción en un instante.
 *
 * @param gLateral       G lateral (izq/der) en gs
 * @param gLongitudinal  G longitudinal (accel/freno) en gs
 * @param gTotal         Magnitud total horizontal en gs
 * @param gForceState    Estado derivado de gTotal
 * @param speedKmh       Velocidad GPS en km/h; -1 si GPS aún no disponible
 * @param steerDirection Signo lateral del acelerómetro: +1 = derecha, -1 = izquierda
 * @param headingRate    Variación de rumbo GPS en grados/s. Positivo = gira a la derecha.
 *                       0 si GPS sin señal o velocidad < 5 km/h.
 */
data class DriveState(
    val gLateral: Float      = 0f,
    val gLongitudinal: Float = 0f,
    val gTotal: Float        = 0f,
    val gForceState: GForceState = GForceState.CRUISING,
    val speedKmh: Float      = -1f,
    val steerDirection: Float = 0f,   // rango [-1, +1] del acelerómetro
    val headingRate: Float   = 0f     // grados/s del GPS; más fiable que acelerómetro
) {
    companion object {
        val EMPTY = DriveState()
    }
}
