package com.arcadedriving.model

/**
 * Estado de conducción basado en la fuerza G lateral total.
 *
 * Umbrales calibrados para conducción real en carretera asfaltada:
 *  - CRUISING : < 0.50 G  → conducción tranquila
 *  - FUN_ZONE : 0.50–0.85 G → zona de ritmo, neumáticos trabajando
 *  - LIMIT    : ≥ 0.85 G  → límite de adherencia, peligro de salida
 *
 * Los neumáticos de calle tienen μ ≈ 0.8–0.9, por lo que 0.85 G
 * representa el borde real del grip disponible.
 */
enum class GForceState(val label: String) {

    CRUISING("CRUISING"),
    FUN_ZONE("FUN ZONE"),
    LIMIT("¡LÍMITE!");

    companion object {
        private const val THRESHOLD_FUN   = 0.50f
        private const val THRESHOLD_LIMIT = 0.85f

        fun from(gTotal: Float): GForceState = when {
            gTotal >= THRESHOLD_LIMIT -> LIMIT
            gTotal >= THRESHOLD_FUN   -> FUN_ZONE
            else                      -> CRUISING
        }
    }
}
