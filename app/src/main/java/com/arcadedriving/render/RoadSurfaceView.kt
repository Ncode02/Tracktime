package com.arcadedriving.render

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.arcadedriving.model.DriveState
import com.arcadedriving.model.GForceState
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
//  RoadSurfaceView  –  Vista principal de la carretera con efecto neón
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SurfaceView con hilo de render dedicado a 60 fps.
 *
 * Capas visuales (de atrás a delante):
 *   1. Cielo  – gradiente oscuro azul/violeta
 *   2. Suelo  – gris muy oscuro
 *   3. Estrellas estáticas con alpha variable
 *   4. Fulgor en la línea de horizonte (color neón)
 *   5. Carretera en perspectiva (trapezoide)
 *   6. Bordes neón con glow multicapa
 *   7. Línea central discontinua animada
 *   8. HUD  →  velocímetro · etiqueta de estado · valor G · G-meter
 *
 * El color neón cambia según [GForceState]:
 *   CRUISING  → Azul neón  #00BFFF
 *   FUN_ZONE  → Amarillo neón #FFE600
 *   LIMIT     → Rojo/naranja parpadeante
 */
class RoadSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    @Volatile
    var driveState: DriveState = DriveState.EMPTY

    private var renderThread: RoadRenderThread? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = RoadRenderThread(holder, this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { /* no-op */ }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderThread?.stopRendering()
        renderThread = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  RoadRenderThread  –  Bucle de render a 60 fps
// ─────────────────────────────────────────────────────────────────────────────

internal class RoadRenderThread(
    private val holder: SurfaceHolder,
    private val view: RoadSurfaceView
) : Thread("RoadRenderThread") {

    @Volatile private var running = true

    private val paint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path    = Path()

    private val TARGET_FPS = 60
    private val FRAME_MS   = 1000L / TARGET_FPS

    // Fases de animación
    private var dashPhase  = 0f   // 0..1, avanza con la velocidad
    private var flashPhase = 0f   // 0..2π, para parpadeo LIMIT

    // Punto de fuga suavizado (interpolación para curvas fluidas)
    private var smoothVpX = -1f   // -1 = no inicializado aún
    private val VP_ALPHA  = 0.09f // factor IIR: más bajo = curva más lenta/suave

    private val hudTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    // ─── Estrellas ────────────────────────────────────────────────────────────
    private data class Star(val xFrac: Float, val yFrac: Float, val alpha: Int, val size: Float)
    private val stars: List<Star> = List(90) {
        Star(
            xFrac = Math.random().toFloat(),
            yFrac = Math.random().toFloat(),
            alpha = (70 + (Math.random() * 185).toInt()).coerceAtMost(255),
            size  = (0.6f + Math.random().toFloat() * 1.4f)
        )
    }

    // ─── Control del hilo ────────────────────────────────────────────────────

    fun stopRendering() {
        running = false
        try { join(600) } catch (_: InterruptedException) { interrupt() }
    }

    override fun run() {
        while (running) {
            val frameStart = System.currentTimeMillis()

            val canvas = try {
                holder.lockCanvas()
            } catch (_: Exception) { null } ?: continue

            try {
                drawFrame(canvas, view.driveState)
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
            }

            val elapsed = System.currentTimeMillis() - frameStart
            val sleepMs = FRAME_MS - elapsed
            if (sleepMs > 0) {
                try { sleep(sleepMs) } catch (_: InterruptedException) { break }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FRAME PRINCIPAL
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawFrame(canvas: Canvas, state: DriveState) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        if (w == 0f || h == 0f) return

        // Factor de velocidad para animación de rayas (0 → parado, 1 → 60 km/h, 3.5 → muy rápido)
        val speedFactor = if (state.speedKmh < 0f) 0.4f
                          else (state.speedKmh / 60f).coerceIn(0.15f, 3.5f)

        // Actualizar fases
        dashPhase  = (dashPhase  + 0.011f * speedFactor) % 1f
        flashPhase = (flashPhase + 0.13f) % (2f * PI.toFloat())

        val neonColor = neonColorFor(state)
        val horizonY  = h * 0.38f

        // Punto de fuga con curva exagerada + interpolación IIR para transiciones fluidas
        // steerDirection > 0 → girando derecha → horizonte se mueve a la derecha
        val targetVpX = w / 2f + state.steerDirection * w * 0.38f
        if (smoothVpX < 0f) smoothVpX = w / 2f   // inicializar al centro la primera vez
        smoothVpX = VP_ALPHA * targetVpX + (1f - VP_ALPHA) * smoothVpX
        val vpX = smoothVpX.coerceIn(w * 0.12f, w * 0.88f)

        // ── Capas ──────────────────────────────────────────────────────────────
        drawSky(canvas, w, horizonY)
        drawGround(canvas, w, h, horizonY)
        drawStars(canvas, w, horizonY)
        drawHorizonGlow(canvas, w, horizonY, neonColor)
        drawRoad(canvas, w, h, vpX, horizonY, neonColor)
        drawCenterDashes(canvas, w, h, vpX, horizonY, neonColor)
        drawSpeedLines(canvas, w, h, vpX, horizonY, state, neonColor)
        drawGVignette(canvas, w, h, state)
        drawHUD(canvas, w, h, state, neonColor)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  COLOR NEÓN según estado
    // ─────────────────────────────────────────────────────────────────────────

    private fun neonColorFor(state: DriveState): Int = when (state.gForceState) {
        GForceState.CRUISING -> Color.parseColor("#00BFFF")   // Azul neón
        GForceState.FUN_ZONE -> Color.parseColor("#FFE600")   // Amarillo neón
        GForceState.LIMIT    -> {
            // Parpadeo entre rojo fuego y naranja
            val t = (sin(flashPhase) + 1f) / 2f               // 0..1
            val green = (t * 85).toInt()
            Color.rgb(255, green, 0)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CAPAS DE FONDO
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawSky(canvas: Canvas, w: Float, horizonY: Float) {
        val shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            Color.parseColor("#04040F"),
            Color.parseColor("#16072A"),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        paint.style  = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, horizonY, paint)
        paint.shader = null
    }

    private fun drawGround(canvas: Canvas, w: Float, h: Float, horizonY: Float) {
        paint.color = Color.parseColor("#080808")
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, horizonY, w, h, paint)
    }

    private fun drawStars(canvas: Canvas, w: Float, horizonY: Float) {
        paint.style = Paint.Style.FILL
        for (star in stars) {
            paint.color = Color.argb(star.alpha, 255, 255, 255)
            canvas.drawCircle(
                star.xFrac * w,
                star.yFrac * horizonY * 0.94f,
                star.size, paint
            )
        }
        paint.alpha = 255
    }

    /** Fulgor tenue en el horizonte con el color actual del neón */
    private fun drawHorizonGlow(canvas: Canvas, w: Float, horizonY: Float, neonColor: Int) {
        val r = Color.red(neonColor); val g = Color.green(neonColor); val b = Color.blue(neonColor)
        val shader = LinearGradient(
            0f, horizonY - 24f, 0f, horizonY + 24f,
            Color.argb(0, r, g, b),
            Color.argb(35, r, g, b),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        paint.style  = Paint.Style.FILL
        canvas.drawRect(0f, horizonY - 24f, w, horizonY + 24f, paint)
        paint.shader = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CARRETERA EN PERSPECTIVA
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawRoad(
        canvas: Canvas, w: Float, h: Float,
        vpX: Float, horizonY: Float, neonColor: Int
    ) {
        val halfBottom  = w * 0.37f
        val halfHorizon = 13f

        val lBot = w / 2f - halfBottom;  val rBot = w / 2f + halfBottom
        val lHrz = vpX - halfHorizon;    val rHrz = vpX + halfHorizon

        // Relleno de asfalto oscuro
        path.reset()
        path.moveTo(lBot, h); path.lineTo(rBot, h)
        path.lineTo(rHrz, horizonY); path.lineTo(lHrz, horizonY)
        path.close()
        paint.color = Color.parseColor("#0D0D0D")
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)

        // Bordes neón con glow multicapa
        drawNeonLine(canvas, lBot, h, lHrz, horizonY, neonColor)
        drawNeonLine(canvas, rBot, h, rHrz, horizonY, neonColor)
    }

    /**
     * Dibuja una línea con efecto glow neón.
     * 4 pasadas: outer glow → mid glow → inner glow → core brillante
     */
    private fun drawNeonLine(
        canvas: Canvas,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Int
    ) {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        paint.style    = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT

        paint.strokeWidth = 30f; paint.color = Color.argb(15, r, g, b)
        canvas.drawLine(x1, y1, x2, y2, paint)

        paint.strokeWidth = 16f; paint.color = Color.argb(50, r, g, b)
        canvas.drawLine(x1, y1, x2, y2, paint)

        paint.strokeWidth = 7f;  paint.color = Color.argb(120, r, g, b)
        canvas.drawLine(x1, y1, x2, y2, paint)

        paint.strokeWidth = 2.2f; paint.color = Color.argb(255, r, g, b)
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LÍNEA CENTRAL DISCONTINUA ANIMADA
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dibuja rayas de separación que avanzan hacia el observador.
     * t=0 → horizonte, t=1 → parte baja de la pantalla.
     * La fase dashPhase [0,1) produce la ilusión de movimiento.
     */
    private fun drawCenterDashes(
        canvas: Canvas, w: Float, h: Float,
        vpX: Float, horizonY: Float, neonColor: Int
    ) {
        val centerXBot = w / 2f
        val numDashes  = 14
        val dashLen    = 0.40f / numDashes   // longitud de cada raya en espacio t
        val r = Color.red(neonColor); val g = Color.green(neonColor); val b = Color.blue(neonColor)

        paint.style    = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        for (i in 0 until numDashes) {
            val tTop = ((i.toFloat() / numDashes) + dashPhase) % 1f
            val tBot = (tTop + dashLen).coerceAtMost(1f)

            if (tTop < 0.04f) continue     // invisible cerca del horizonte
            if (tBot <= tTop) continue     // raya fuera de rango

            val yTop = lerp(horizonY, h, tTop)
            val yBot = lerp(horizonY, h, tBot)
            val xTop = lerp(vpX, centerXBot, tTop)
            val xBot = lerp(vpX, centerXBot, tBot)

            val alpha  = (tTop * 215).toInt().coerceIn(0, 215)
            val strokeW = lerp(1f, 5f, tTop)

            paint.strokeWidth = strokeW
            paint.color = Color.argb(alpha, r, g, b)
            canvas.drawLine(xTop, yTop, xBot, yBot, paint)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HUD
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawHUD(canvas: Canvas, w: Float, h: Float, state: DriveState, neonColor: Int) {
        val r = Color.red(neonColor); val g = Color.green(neonColor); val b = Color.blue(neonColor)
        paint.typeface = hudTypeface
        paint.style    = Paint.Style.FILL

        // ── Valor G (arriba derecha, fuera del área del minimapa) ─────────────
        paint.textSize  = h * 0.030f
        paint.textAlign = Paint.Align.RIGHT
        paint.color = Color.argb(180, r, g, b)
        canvas.drawText("%.2fG".format(state.gTotal), w - 28f, h * 0.055f, paint)

        // ── Etiqueta de estado (encima del velocímetro) ───────────────────────
        paint.textSize  = h * 0.032f
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.argb(200, r, g, b)
        canvas.drawText(state.gForceState.label, w / 2f, h * 0.870f, paint)

        // ── Velocímetro (abajo centro) ────────────────────────────────────────
        val speedStr = if (state.speedKmh < 0f) "--  km/h"
                       else "${state.speedKmh.toInt().toString().padStart(3)} km/h"
        paint.textSize  = h * 0.072f
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.argb(255, r, g, b)
        canvas.drawText(speedStr, w / 2f, h * 0.938f, paint)

        // ── G-meter circular (abajo izquierda) ────────────────────────────────
        drawGMeter(canvas, w, h, state, neonColor)
    }

    /**
     * Medidor de G como círculo con bola que se desplaza según la fuerza lateral/longitudinal.
     * Anillo interior amarillo = umbral 0.5 G
     * Borde exterior = umbral 0.85 G
     */
    private fun drawGMeter(canvas: Canvas, w: Float, h: Float, state: DriveState, neonColor: Int) {
        val cx     = w * 0.135f
        val cy     = h * 0.882f
        val radius = w * 0.088f
        val maxG   = 0.85f

        val r = Color.red(neonColor); val g = Color.green(neonColor); val b = Color.blue(neonColor)

        // Fondo semitransparente
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(50, 0, 0, 0)
        canvas.drawCircle(cx, cy, radius + 4f, paint)

        // Anillo exterior (color neón tenue)
        paint.style       = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(85, r, g, b)
        canvas.drawCircle(cx, cy, radius, paint)

        // Anillo de threshold 0.5 G (amarillo tenue)
        paint.strokeWidth = 0.9f
        paint.color = Color.argb(55, 255, 220, 0)
        canvas.drawCircle(cx, cy, radius * (0.5f / maxG), paint)

        // Cruz de referencia
        paint.strokeWidth = 0.7f
        paint.color = Color.argb(35, 255, 255, 255)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

        // Posición de la bola G
        //   X → lateral  (steerDirection tiene el signo: + = derecha, - = izquierda)
        //   Y → longitudinal (hacia arriba = aceleración, hacia abajo = frenada)
        val normLat  = (state.steerDirection * state.gLateral / maxG).coerceIn(-1f, 1f)
        val normLong = (state.gLongitudinal  / maxG).coerceIn(-1f, 1f)

        val rawBallX = cx + normLat  * radius
        val rawBallY = cy - normLong * radius * 0.45f   // escala vertical reducida (frenada < curveo)

        val (ballX, ballY) = clampToCircle(rawBallX, rawBallY, cx, cy, radius * 0.90f)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, r, g, b)
        canvas.drawCircle(ballX, ballY, radius * 0.21f, paint)

        // Etiqueta "G" debajo del círculo
        paint.textSize  = radius * 0.40f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface  = hudTypeface
        paint.color = Color.argb(110, r, g, b)
        canvas.drawText("G", cx, cy + radius + radius * 0.56f, paint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  EFECTOS DE VELOCIDAD Y G
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Líneas radiales desde el horizonte que se intensifican con la velocidad.
     * Generan sensación de túnel / warp speed.
     * Activas a partir de 40 km/h.
     */
    private fun drawSpeedLines(
        canvas: Canvas, w: Float, h: Float,
        vpX: Float, horizonY: Float,
        state: DriveState, neonColor: Int
    ) {
        val speed = if (state.speedKmh < 0f) 0f else state.speedKmh
        if (speed < 40f) return

        val intensity = ((speed - 40f) / 80f).coerceIn(0f, 1f)   // 0 a 120+ km/h
        val numLines  = (10 + intensity * 18).toInt()
        val alpha     = (intensity * 100).toInt()
        val startFrac = 0.18f + intensity * 0.12f  // las líneas empiezan más lejos del VP

        val r = Color.red(neonColor); val g = Color.green(neonColor); val b = Color.blue(neonColor)
        paint.style     = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT

        for (i in 0 until numLines) {
            val angle  = (i.toFloat() / numLines) * 2f * PI.toFloat()
            val dx     = cos(angle).toFloat()
            val dy     = sin(angle).toFloat()
            val dist   = maxOf(w, h) * 1.2f

            val startX = vpX + dx * dist * startFrac
            val startY = horizonY + dy * dist * startFrac
            val endX   = vpX + dx * dist
            val endY   = horizonY + dy * dist

            paint.strokeWidth = lerp(0.8f, 2.5f, intensity)
            paint.color = Color.argb(alpha, r, g, b)
            canvas.drawLine(startX, startY, endX, endY, paint)
        }
    }

    /**
     * Viñeta de borde de pantalla que se activa con G alta (≥ 0.5).
     * - FUN_ZONE (0.5-0.85G): viñeta suave del color neón actual
     * - LIMIT (≥ 0.85G):      viñeta roja intensa que parpadea con flashPhase
     * Crea sensación física de las fuerzas en el cuerpo.
     */
    private fun drawGVignette(canvas: Canvas, w: Float, h: Float, state: DriveState) {
        val gT = state.gTotal
        if (gT < 0.45f) return

        val (vigColor, vigAlpha) = when (state.gForceState) {
            GForceState.FUN_ZONE -> {
                val intensity = ((gT - 0.50f) / 0.35f).coerceIn(0f, 1f)
                Pair(Color.parseColor("#FFE600"), (intensity * 55).toInt())
            }
            GForceState.LIMIT -> {
                val pulse = (sin(flashPhase * 1.5f) + 1f) / 2f
                Pair(Color.parseColor("#FF1800"), (40 + pulse * 65).toInt())
            }
            else -> return
        }

        val rV = Color.red(vigColor); val gV = Color.green(vigColor); val bV = Color.blue(vigColor)
        val shader = RadialGradient(
            w / 2f, h / 2f,
            maxOf(w, h) * 0.72f,
            Color.argb(0, rV, gV, bV),
            Color.argb(vigAlpha, rV, gV, bV),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        paint.style  = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UTILIDADES
    // ─────────────────────────────────────────────────────────────────────────

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun clampToCircle(
        px: Float, py: Float, cx: Float, cy: Float, r: Float
    ): Pair<Float, Float> {
        val dx = px - cx; val dy = py - cy
        val dist = hypot(dx, dy)
        return if (dist <= r) Pair(px, py)
        else Pair(cx + dx / dist * r, cy + dy / dist * r)
    }
}
