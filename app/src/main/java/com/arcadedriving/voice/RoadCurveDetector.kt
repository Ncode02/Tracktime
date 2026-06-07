package com.arcadedriving.voice

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * Detecta curvas por adelantado usando la geometría real de la carretera (OSM / Overpass API).
 *
 * Flujo:
 *  1. Cada GPS update comprueba si hay curvas próximas ya descargadas.
 *  2. Si el coche se ha movido > 120 m desde la última consulta, descarga los nodos
 *     de la carretera actual (Overpass) en background.
 *  3. Busca el tramo en que nos encontramos (cercanía + bearing).
 *  4. Calcula el radio de curvatura de los nodos por delante.
 *  5. Cuando la distancia a una curva < umbral de aviso (≈ 9s a la velocidad actual),
 *     llama a [onCurveAhead].
 *
 * Llamar a [destroy] en Activity.onDestroy para cancelar las corrutinas.
 */
class RoadCurveDetector(
    private val onCurveAhead: (radiusM: Float, isRight: Boolean, distM: Float) -> Unit
) {
    private data class CurvePoint(
        val lat: Double, val lon: Double,
        val radiusM: Float, val isRight: Boolean
    )

    private val scope        = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fetching     = AtomicBoolean(false)

    private var upcoming: List<CurvePoint> = emptyList()
    private val announced    = mutableSetOf<String>()   // claves ya anunciadas
    private var lastQueryLat = Double.NaN
    private var lastQueryLon = Double.NaN

    // ─── API pública ──────────────────────────────────────────────────────────

    /** Llamar desde el hilo principal en cada actualización GPS. */
    fun update(lat: Double, lon: Double, bearing: Float, speedMs: Float) {
        // 1) Comprobar curvas próximas ya descargadas
        val announceDist = (speedMs * 9f).coerceIn(80f, 350f)  // ~9 s por adelantado
        val keep = mutableListOf<CurvePoint>()
        for (curve in upcoming) {
            val dist = haversineM(lat, lon, curve.lat, curve.lon).toFloat()
            if (dist < 25f) continue   // ya pasamos esta curva, descartarla
            val key = "${(curve.lat * 1e4).toLong()}_${(curve.lon * 1e4).toLong()}"
            if (dist < announceDist && !announced.contains(key)) {
                announced.add(key)
                onCurveAhead(curve.radiusM, curve.isRight, dist)
            }
            keep.add(curve)
        }
        upcoming = keep

        // 2) Refrescar datos OSM si nos movimos > 120 m
        val moved = if (lastQueryLat.isNaN()) Float.MAX_VALUE
                    else haversineM(lat, lon, lastQueryLat, lastQueryLon).toFloat()

        if (moved > 120f && fetching.compareAndSet(false, true)) {
            lastQueryLat = lat
            lastQueryLon = lon
            scope.launch {
                try {
                    val curves = fetchAndAnalyze(lat, lon, bearing)
                    withContext(Dispatchers.Main) {
                        upcoming  = curves
                        announced.clear()
                    }
                } catch (_: Exception) {
                } finally {
                    fetching.set(false)
                }
            }
        }
    }

    fun destroy() { scope.cancel() }

    // ─── Descarga y análisis OSM ──────────────────────────────────────────────

    private fun fetchAndAnalyze(lat: Double, lon: Double, bearing: Float): List<CurvePoint> {
        // Overpass: obtener las vías y sus nodos en un radio de 250 m
        val query = "[out:json][timeout:8];\n" +
            "way(around:250,$lat,$lon)" +
            "[highway~\"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|road)\$\"];\n" +
            "(._;>;);\nout body;"

        val conn = URL("https://overpass-api.de/api/interpreter")
            .openConnection() as HttpURLConnection
        conn.requestMethod  = "POST"
        conn.doOutput       = true
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
        conn.connect()

        if (conn.responseCode != 200) { conn.disconnect(); return emptyList() }

        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        conn.disconnect()

        val nodeMap = mutableMapOf<Long, Pair<Double, Double>>()
        val ways    = mutableListOf<List<Long>>()
        val elems   = json.getJSONArray("elements")

        for (i in 0 until elems.length()) {
            val el = elems.getJSONObject(i)
            when (el.getString("type")) {
                "node" -> nodeMap[el.getLong("id")] =
                    Pair(el.getDouble("lat"), el.getDouble("lon"))
                "way"  -> {
                    val ns = el.getJSONArray("nodes")
                    ways.add(List(ns.length()) { ns.getLong(it) })
                }
            }
        }

        val best = selectBestWay(ways, nodeMap, lat, lon, bearing) ?: return emptyList()
        return extractCurves(best, nodeMap, lat, lon, bearing)
    }

    /** Elige el way cuyo tramo más cercano tenga un bearing similar al actual. */
    private fun selectBestWay(
        ways: List<List<Long>>,
        nodes: Map<Long, Pair<Double, Double>>,
        lat: Double, lon: Double, bearing: Float
    ): List<Long>? {
        var best: List<Long>? = null
        var bestScore = Float.MAX_VALUE

        for (way in ways) {
            for (i in 0 until way.size - 1) {
                val a = nodes[way[i]]    ?: continue
                val b = nodes[way[i+1]] ?: continue
                val midLat = (a.first  + b.first)  / 2.0
                val midLon = (a.second + b.second) / 2.0
                val dist   = haversineM(lat, lon, midLat, midLon).toFloat()
                val segBearing = bearing(a.first, a.second, b.first, b.second)
                var diff = abs(segBearing - bearing)
                if (diff > 180f) diff = 360f - diff
                val score = dist + minOf(diff, abs(diff - 180f)) * 4f
                if (score < bestScore) { bestScore = score; best = way }
            }
        }
        return best
    }

    /** Extrae los puntos de curva por delante en el tramo, ordenados por distancia. */
    private fun extractCurves(
        way: List<Long>,
        nodes: Map<Long, Pair<Double, Double>>,
        lat: Double, lon: Double, bearing: Float
    ): List<CurvePoint> {
        // Nodo de inicio: el más cercano a la posición actual
        var startIdx = 0; var minD = Double.MAX_VALUE
        for (i in way.indices) {
            val n = nodes[way[i]] ?: continue
            val d = haversineM(lat, lon, n.first, n.second)
            if (d < minD) { minD = d; startIdx = i }
        }

        // Dirección de recorrido (¿vamos hacia índices crecientes o decrecientes?)
        val forward: Boolean = run {
            if (startIdx >= way.size - 1) return@run false
            val a = nodes[way[startIdx]]     ?: return@run true
            val b = nodes[way[startIdx + 1]] ?: return@run true
            val segB = bearing(a.first, a.second, b.first, b.second)
            var diff = abs(segB - bearing); if (diff > 180f) diff = 360f - diff
            diff < 90f
        }

        val result = mutableListOf<CurvePoint>()
        val range  = if (forward) (startIdx until way.size - 2) else (startIdx downTo 2)

        for (idx in range) {
            val iA = idx
            val iB = if (forward) idx + 1 else idx - 1
            val iC = if (forward) idx + 2 else idx - 2
            if (iA < 0 || iA >= way.size) continue
            if (iB < 0 || iB >= way.size) continue
            if (iC < 0 || iC >= way.size) continue

            val a = nodes[way[iA]] ?: continue
            val b = nodes[way[iB]] ?: continue
            val c = nodes[way[iC]] ?: continue

            val bearAB = bearing(a.first, a.second, b.first, b.second)
            val bearBC = bearing(b.first, b.second, c.first, c.second)
            var delta  = bearBC - bearAB
            if (delta >  180f) delta -= 360f
            if (delta < -180f) delta += 360f

            if (abs(delta) < 8f) continue   // cambio de rumbo demasiado suave

            val distAB = haversineM(a.first, a.second, b.first, b.second)
            val radius = (distAB / Math.toRadians(abs(delta).toDouble()))
                .toFloat().coerceIn(10f, 9_999f)

            if (radius > 600f) continue     // curva muy abierta, no avisar

            val distToB = haversineM(lat, lon, b.first, b.second).toFloat()
            if (distToB < 30f) continue     // ya pasada

            result.add(CurvePoint(b.first, b.second, radius, delta > 0f))
        }

        return result.sortedBy { haversineM(lat, lon, it.lat, it.lon) }
    }

    // ─── Utilidades geográficas ────────────────────────────────────────────────

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1.isNaN() || lon1.isNaN() || lat2.isNaN() || lon2.isNaN()) return Double.MAX_VALUE
        val R    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a    = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2.0 * asin(sqrt(a))
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}
