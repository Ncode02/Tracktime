package com.arcadedriving.render

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Mini-mapa circular estilo GTA usando tiles de OpenStreetMap.
 *
 * - Descarga tiles OSM en background y los cachea en memoria (LRU, máx 80)
 * - Aplica tinte oscuro/neón a cada tile para encajar con la estética de la app
 * - Anillo exterior cambia de color según el estado G (igual que la carretera)
 * - Punto rojo fijo en el centro = posición actual
 * - Zoom 15 ≈ 1.2 km por tile → se ven ~3 km alrededor a velocidades de carretera
 *
 * Posición en el layout: top-left sobre el cielo de RoadSurfaceView.
 * Los elementos HUD de RoadSurfaceView (label, G value) están en top-center
 * y top-right, por lo que no hay solapamiento.
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile var latitude:    Double  = 0.0
    @Volatile var longitude:   Double  = 0.0
    @Volatile var hasLocation: Boolean = false

    private var neonColor: Int = Color.parseColor("#00BFFF")

    // ── Configuración de tiles ─────────────────────────────────────────────
    private val ZOOM      = 15
    private val TILE_SIZE = 256

    // LRU cache: LinkedHashMap con accessOrder=true → primer elemento = menos usado
    private val tileCache   = LinkedHashMap<String, Bitmap>(64, 0.75f, true)
    private val loadingTiles = HashSet<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Objetos de dibujo ─────────────────────────────────────────────────
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val uiPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath    = Path()
    private val hudTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    // ─────────────────────────────────────────────────────────────────────────
    //  DRAW
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val cx     = w / 2f
        val cy     = h / 2f
        val radius = minOf(w, h) / 2f - 5f

        // ── Recortar en círculo ────────────────────────────────────────────
        canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        if (hasLocation) {
            drawTiles(canvas, cx, cy, radius)
        } else {
            drawPlaceholder(canvas, w, h, cx, cy, radius)
        }

        canvas.restore()   // libera el clip

        // ── Punto de posición (encima del clip, siempre al centro) ─────────
        if (hasLocation) drawPositionDot(canvas, cx, cy)

        // ── Anillo neón ────────────────────────────────────────────────────
        drawNeonRing(canvas, cx, cy, radius)
    }

    // ── Tiles OSM ────────────────────────────────────────────────────────────

    private fun drawTiles(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val n = (1 shl ZOOM).toDouble()

        // Posición actual → píxel global en el espacio de tiles
        val globalPxX = ((longitude + 180.0) / 360.0) * n * TILE_SIZE
        val latRad    = Math.toRadians(latitude)
        val globalPxY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE_SIZE

        val tileX   = (globalPxX / TILE_SIZE).toInt()
        val tileY   = (globalPxY / TILE_SIZE).toInt()
        val offsetX = (globalPxX - tileX * TILE_SIZE).toFloat()
        val offsetY = (globalPxY - tileY * TILE_SIZE).toFloat()

        // Escala: mostrar 2.5 tiles en el diámetro del círculo ≈ 3 km a zoom 15
        val scale          = (radius * 2f) / (TILE_SIZE * 2.5f)
        val tileScreenSize = TILE_SIZE * scale

        for (dy in -2..2) {
            for (dx in -2..2) {
                val tx = tileX + dx
                val ty = tileY + dy
                if (ty < 0 || ty >= n.toInt()) continue

                val key        = "$ZOOM/$tx/$ty"
                val screenLeft = cx + (dx * TILE_SIZE - offsetX) * scale
                val screenTop  = cy + (dy * TILE_SIZE - offsetY) * scale
                val destRect   = RectF(screenLeft, screenTop,
                                       screenLeft + tileScreenSize,
                                       screenTop  + tileScreenSize)

                val bmp = tileCache[key]
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, destRect, bitmapPaint)
                } else {
                    // Fondo oscuro mientras descarga
                    uiPaint.style = Paint.Style.FILL
                    uiPaint.color = Color.parseColor("#080818")
                    canvas.drawRect(destRect, uiPaint)
                    fetchTile(key, tx, ty)
                }
            }
        }
    }

    // ── Placeholder sin GPS ──────────────────────────────────────────────────

    private fun drawPlaceholder(
        canvas: Canvas, w: Float, h: Float,
        cx: Float, cy: Float, radius: Float
    ) {
        uiPaint.color = Color.parseColor("#08081A")
        uiPaint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, uiPaint)

        uiPaint.color     = Color.argb(140, 0, 191, 255)
        uiPaint.textAlign = Paint.Align.CENTER
        uiPaint.typeface  = hudTypeface
        uiPaint.textSize  = radius * 0.32f
        canvas.drawText("GPS", cx, cy - radius * 0.05f, uiPaint)
        uiPaint.textSize = radius * 0.21f
        canvas.drawText("buscando...", cx, cy + radius * 0.30f, uiPaint)
    }

    // ── Punto de posición ────────────────────────────────────────────────────

    private fun drawPositionDot(canvas: Canvas, cx: Float, cy: Float) {
        uiPaint.style = Paint.Style.FILL
        uiPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 7f, uiPaint)
        uiPaint.color = Color.parseColor("#FF3030")
        canvas.drawCircle(cx, cy, 4.5f, uiPaint)
    }

    // ── Anillo neón ──────────────────────────────────────────────────────────

    private fun drawNeonRing(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val r = Color.red(neonColor)
        val g = Color.green(neonColor)
        val b = Color.blue(neonColor)

        uiPaint.style       = Paint.Style.STROKE
        uiPaint.strokeCap   = Paint.Cap.BUTT

        // Glow exterior difuso
        uiPaint.strokeWidth = 12f
        uiPaint.color       = Color.argb(35, r, g, b)
        canvas.drawCircle(cx, cy, radius + 4f, uiPaint)

        // Glow medio
        uiPaint.strokeWidth = 6f
        uiPaint.color       = Color.argb(80, r, g, b)
        canvas.drawCircle(cx, cy, radius + 1f, uiPaint)

        // Core neón
        uiPaint.strokeWidth = 2f
        uiPaint.color       = Color.argb(230, r, g, b)
        canvas.drawCircle(cx, cy, radius, uiPaint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DESCARGA Y TINTE DE TILES
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchTile(key: String, tx: Int, ty: Int) {
        if (loadingTiles.contains(key)) return
        loadingTiles.add(key)

        scope.launch(Dispatchers.IO) {
            val bmp: Bitmap? = try {
                val conn = URL("https://tile.openstreetmap.org/$ZOOM/$tx/$ty.png")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 6_000
                conn.readTimeout    = 6_000
                conn.setRequestProperty("User-Agent", "ArcadeDriveApp/1.0 (personal use)")
                conn.connect()
                if (conn.responseCode == 200) {
                    val raw = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    raw?.let { applyNightTint(it) }
                } else {
                    conn.disconnect(); null
                }
            } catch (_: Exception) { null }

            withContext(Dispatchers.Main) {
                loadingTiles.remove(key)
                if (bmp != null) {
                    // Evictar el tile menos usado si el caché está lleno
                    if (tileCache.size >= 80) {
                        val lru = tileCache.entries.iterator().next()
                        if (!lru.value.isRecycled) lru.value.recycle()
                        tileCache.remove(lru.key)
                    }
                    tileCache[key] = bmp
                    invalidate()
                }
            }
        }
    }

    /**
     * Transforma los tiles OSM (colores día) a una paleta oscura con tinte azulado,
     * coherente con el fondo de la app.
     *
     * Matriz de color:  ⎡ 0.25  0     0     0   0  ⎤
     *                   ⎢ 0     0.30  0     0   0  ⎥  → oscuro, tono azul
     *                   ⎣ 0     0     0.55  0   8  ⎦  → canal azul ligeramente realzado
     */
    private fun applyNightTint(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c   = Canvas(out)
        val p   = Paint()
        p.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                0.25f, 0f,    0f,    0f, 0f,
                0f,    0.30f, 0f,    0f, 0f,
                0f,    0f,    0.55f, 0f, 8f,
                0f,    0f,    0f,    1f, 0f
            ))
        )
        c.drawBitmap(src, 0f, 0f, p)
        src.recycle()
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  API PÚBLICA
    // ─────────────────────────────────────────────────────────────────────────

    fun updateLocation(lat: Double, lon: Double) {
        latitude  = lat
        longitude = lon
        hasLocation = true
        post { invalidate() }
    }

    /** Sincroniza el color del anillo con el estado G de la carretera. */
    fun updateNeonColor(color: Int) {
        if (neonColor != color) {
            neonColor = color
            post { invalidate() }
        }
    }

    fun destroy() {
        scope.cancel()
        tileCache.values.forEach { if (!it.isRecycled) it.recycle() }
        tileCache.clear()
    }
}
