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
 * Mapa de carretera a pantalla completa sobre la zona del cielo.
 *
 * Diseño:
 *  - Overlay match_parent (cubre toda la pantalla)
 *  - Solo dibuja en el top HORIZON_FRAC (38%) → mismo borde que el cielo de RoadSurfaceView
 *  - Rectangular sin recorte circular
 *  - Zoom 17 → ~300 m visibles → ves las calles que te rodean
 *  - Crosshair neón fijo en el centro = posición actual
 *  - Gradiente de fundido en el borde inferior → fusión suave con el horizonte
 *  - Tinte noche sobre los tiles OSM (colores día → paleta oscura neón)
 */
class MiniMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile var latitude:    Double  = 0.0
    @Volatile var longitude:   Double  = 0.0
    @Volatile var hasLocation: Boolean = false
    @Volatile var mapBearing:  Float   = 0f   // rumbo GPS en grados (0=Norte, 90=Este)

    private var neonColor: Int = Color.parseColor("#00BFFF")

    // Debe coincidir con horizonY de RoadSurfaceView (h * 0.38f)
    private val HORIZON_FRAC = 0.38f

    // Zoom 17 → tile ≈ 300 m de ancho a latitud 40°. Se ven ~3 tiles → ~900 m
    private var ZOOM       = 17
    private val ZOOM_MIN   = 14   // zoom out: ~5 km
    private val ZOOM_MAX   = 19   // zoom in: ~50 m
    private val TILE_SIZE  = 256
    // Tiles visibles en el ancho de pantalla completo
    private val TILES_WIDE = 3.2f

    private val tileCache    = LinkedHashMap<String, Bitmap>(120, 0.75f, true)
    private val loadingTiles = HashSet<String>()
    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tilePaint    = Paint(Paint.FILTER_BITMAP_FLAG)
    private val uiPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hudTypeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    // ─────────────────────────────────────────────────────────────────────────
    //  DRAW
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w    = width.toFloat()
        val h    = height.toFloat()
        val mapH = h * HORIZON_FRAC
        if (w == 0f || mapH == 0f) return

        val cx = w / 2f
        val cy = mapH / 2f

        // 1) Tiles con rotación heading-up (el rumbo GPS apunta siempre arriba)
        canvas.save()
        canvas.clipRect(0f, 0f, w, mapH)
        if (mapBearing != 0f) canvas.rotate(-mapBearing, cx, cy)
        if (hasLocation) {
            drawTiles(canvas, w, mapH, cx, cy)
        } else {
            drawPlaceholder(canvas, w, mapH, cx, cy)
        }
        canvas.restore()

        // 2) Fundido inferior (no rotado)
        canvas.save()
        canvas.clipRect(0f, 0f, w, mapH)
        drawBottomFade(canvas, w, mapH)
        canvas.restore()

        // 3) Crosshair + brújula (no rotados, siempre encima)
        if (hasLocation) {
            canvas.save()
            canvas.clipRect(0f, 0f, w, mapH)
            drawCrosshair(canvas, cx, cy)
            drawCompass(canvas, w, mapH)
            canvas.restore()
        }
    }

    // ─── Tiles OSM ────────────────────────────────────────────────────────────

    private fun drawTiles(canvas: Canvas, w: Float, mapH: Float, cx: Float, cy: Float) {
        val n         = (1 shl ZOOM).toDouble()
        val globalPxX = ((longitude + 180.0) / 360.0) * n * TILE_SIZE
        val latRad    = Math.toRadians(latitude)
        val globalPxY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE_SIZE

        val tileX   = (globalPxX / TILE_SIZE).toInt()
        val tileY   = (globalPxY / TILE_SIZE).toInt()
        val offsetX = (globalPxX - tileX * TILE_SIZE).toFloat()
        val offsetY = (globalPxY - tileY * TILE_SIZE).toFloat()

        // Escala: TILES_WIDE tiles caben en el ancho total de la pantalla
        val scale          = w / (TILE_SIZE * TILES_WIDE)
        val tileScreenSize = TILE_SIZE * scale

        for (dy in -5..5) {
            for (dx in -5..5) {
                val tx        = tileX + dx
                val ty        = tileY + dy
                if (ty < 0 || ty >= n.toInt()) continue
                // Wrapping horizontal para tiles que cruzan el antimeridiano
                val txW = ((tx % n.toInt()) + n.toInt()) % n.toInt()
                val key = "$ZOOM/$txW/$ty"

                val screenLeft = cx + (dx * TILE_SIZE - offsetX) * scale
                val screenTop  = cy + (dy * TILE_SIZE - offsetY) * scale
                val dest       = RectF(screenLeft, screenTop,
                                       screenLeft + tileScreenSize,
                                       screenTop  + tileScreenSize)

                val bmp = tileCache[key]
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, dest, tilePaint)
                } else {
                    uiPaint.style = Paint.Style.FILL
                    uiPaint.color = Color.parseColor("#05051A")
                    canvas.drawRect(dest, uiPaint)
                    fetchTile(key, txW, ty)
                }
            }
        }
    }

    // ─── Brújula (indicador de Norte) ────────────────────────────────────────────

    /**
     * Mini brújula en la esquina superior izquierda.
     * La aguja roja apunta siempre hacia el Norte geográfico
     * aunque el mapa esté rotado (heading-up).
     * mapBearing = rumbo actual: la aguja se rota -mapBearing para compensar.
     */
    private fun drawCompass(canvas: Canvas, w: Float, mapH: Float) {
        val icx = w * 0.07f
        val icy = mapH * 0.22f
        val r   = mapH * 0.08f

        // Fondo circular oscuro
        uiPaint.style = Paint.Style.FILL
        uiPaint.color = Color.argb(140, 0, 0, 0)
        canvas.drawCircle(icx, icy, r, uiPaint)

        uiPaint.style       = Paint.Style.STROKE
        uiPaint.strokeWidth = 1.2f
        uiPaint.color       = Color.argb(90, 0, 191, 255)
        canvas.drawCircle(icx, icy, r, uiPaint)

        // Aguja girada con el bearing (Norte siempre visible en su posición real)
        canvas.save()
        canvas.rotate(-mapBearing, icx, icy)

        // Mitad Norte (roja, apunta arriba cuando mapBearing==0)
        uiPaint.style = Paint.Style.FILL
        uiPaint.color = Color.argb(230, 255, 50, 50)
        val np = Path()
        np.moveTo(icx, icy - r * 0.78f)
        np.lineTo(icx - r * 0.22f, icy + r * 0.05f)
        np.lineTo(icx + r * 0.22f, icy + r * 0.05f)
        np.close()
        canvas.drawPath(np, uiPaint)

        // Mitad Sur (gris)
        uiPaint.color = Color.argb(150, 140, 140, 140)
        val sp = Path()
        sp.moveTo(icx, icy + r * 0.78f)
        sp.lineTo(icx - r * 0.22f, icy + r * 0.05f)
        sp.lineTo(icx + r * 0.22f, icy + r * 0.05f)
        sp.close()
        canvas.drawPath(sp, uiPaint)

        canvas.restore()

        // Letra "N" fija en la punta de la aguja girada
        canvas.save()
        canvas.rotate(-mapBearing, icx, icy)
        uiPaint.style     = Paint.Style.FILL
        uiPaint.color     = Color.argb(220, 255, 210, 210)
        uiPaint.textAlign = Paint.Align.CENTER
        uiPaint.typeface  = hudTypeface
        uiPaint.textSize  = r * 0.60f
        canvas.drawText("N", icx, icy - r * 0.88f, uiPaint)
        canvas.restore()
    }

    // ─── Placeholder sin GPS ──────────────────────────────────────────────────

    private fun drawPlaceholder(canvas: Canvas, w: Float, mapH: Float, cx: Float, cy: Float) {
        uiPaint.style = Paint.Style.FILL
        uiPaint.color = Color.parseColor("#05051A")
        canvas.drawRect(0f, 0f, w, mapH, uiPaint)

        uiPaint.color     = Color.argb(150, 0, 191, 255)
        uiPaint.textAlign = Paint.Align.CENTER
        uiPaint.typeface  = hudTypeface
        uiPaint.textSize  = mapH * 0.18f
        canvas.drawText("GPS", cx, cy - mapH * 0.05f, uiPaint)
        uiPaint.textSize = mapH * 0.11f
        canvas.drawText("buscando señal...", cx, cy + mapH * 0.18f, uiPaint)
    }

    // ─── Gradiente de fundido inferior ────────────────────────────────────────

    private fun drawBottomFade(canvas: Canvas, w: Float, mapH: Float) {
        val fadeH  = mapH * 0.22f
        val shader = LinearGradient(
            0f, mapH - fadeH, 0f, mapH,
            Color.TRANSPARENT,
            Color.parseColor("#080808"),   // igual que ground de RoadSurfaceView
            Shader.TileMode.CLAMP
        )
        uiPaint.shader = shader
        uiPaint.style  = Paint.Style.FILL
        canvas.drawRect(0f, mapH - fadeH, w, mapH, uiPaint)
        uiPaint.shader = null
    }

    // ─── Crosshair de posición ────────────────────────────────────────────────

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val r = Color.red(neonColor)
        val g = Color.green(neonColor)
        val b = Color.blue(neonColor)
        val arm = 26f   // longitud de cada brazo del crosshair
        val gap =  9f   // hueco central

        uiPaint.style     = Paint.Style.STROKE
        uiPaint.strokeCap = Paint.Cap.ROUND

        // Glow
        uiPaint.strokeWidth = 8f
        uiPaint.color = Color.argb(45, r, g, b)
        canvas.drawLine(cx - arm, cy, cx + arm, cy, uiPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, uiPaint)

        // Core con hueco central
        uiPaint.strokeWidth = 2f
        uiPaint.color = Color.argb(230, r, g, b)
        canvas.drawLine(cx - arm, cy, cx - gap, cy, uiPaint)
        canvas.drawLine(cx + gap, cy, cx + arm, cy, uiPaint)
        canvas.drawLine(cx, cy - arm, cx, cy - gap, uiPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + arm, uiPaint)

        // Punto central rojo
        uiPaint.style = Paint.Style.FILL
        uiPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 6f, uiPaint)
        uiPaint.color = Color.parseColor("#FF2020")
        canvas.drawCircle(cx, cy, 4f, uiPaint)
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
                conn.connectTimeout = 7_000
                conn.readTimeout    = 7_000
                conn.setRequestProperty("User-Agent", "ArcadeDriveApp/1.0 (personal use)")
                conn.connect()
                if (conn.responseCode == 200) {
                    val raw = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    raw?.let { applyNightTint(it) }
                } else { conn.disconnect(); null }
            } catch (_: Exception) { null }

            withContext(Dispatchers.Main) {
                loadingTiles.remove(key)
                if (bmp != null) {
                    if (tileCache.size >= 120) {
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

    /** Tiles OSM día → paleta oscura neón */
    private fun applyNightTint(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c   = Canvas(out)
        val p   = Paint()
        p.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                0.22f, 0f,    0f,    0f, 0f,
                0f,    0.26f, 0f,    0f, 0f,
                0f,    0f,    0.50f, 0f, 10f,
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

    fun setBearing(degrees: Float) {
        if (mapBearing != degrees) {
            mapBearing = degrees
            post { invalidate() }
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        latitude    = lat
        longitude   = lon
        hasLocation = true
        post { invalidate() }
    }

    fun updateNeonColor(color: Int) {
        if (neonColor != color) {
            neonColor = color
            post { invalidate() }
        }
    }

    fun zoomIn() {
        if (ZOOM < ZOOM_MAX) {
            ZOOM++
            clearTileCache()
            post { invalidate() }
        }
    }

    fun zoomOut() {
        if (ZOOM > ZOOM_MIN) {
            ZOOM--
            clearTileCache()
            post { invalidate() }
        }
    }

    private fun clearTileCache() {
        tileCache.values.forEach { if (!it.isRecycled) it.recycle() }
        tileCache.clear()
        loadingTiles.clear()
    }

    fun destroy() {
        scope.cancel()
        tileCache.values.forEach { if (!it.isRecycled) it.recycle() }
        tileCache.clear()
    }
}
