package com.uninorte.locator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Servidor con la API (cambiar aquí si se usa otro)
internal const val API_BASE_URL = "http://uninortelocator2.duckdns.org:8443"

data class Lugar(val nombre: String, val direccion: String, val lat: Double, val lon: Double)

data class RutaOpcion(
    val distanciaM: Double,
    val tiempoS: Double,
    val retrasoS: Double,
    val puntos: List<Pair<Double, Double>>   // (lat, lon), para dibujar en la etapa 2
)

private suspend fun getJson(ruta: String): String = withContext(Dispatchers.IO) {
    val conexion = URL("$API_BASE_URL$ruta").openConnection() as HttpURLConnection
    try {
        conexion.connectTimeout = 8_000
        conexion.readTimeout = 15_000
        val codigo = conexion.responseCode
        if (codigo !in 200..299) throw IOException("HTTP $codigo")
        conexion.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conexion.disconnect()
    }
}

internal suspend fun buscarLugares(texto: String, lat: Double?, lon: Double?): List<Lugar> {
    val q = URLEncoder.encode(texto, "UTF-8")
    val posicion = if (lat != null && lon != null) "&lat=$lat&lon=$lon" else ""
    val arreglo = JSONArray(getJson("/api/buscar?q=$q$posicion"))
    return List(arreglo.length()) { i ->
        val o = arreglo.getJSONObject(i)
        Lugar(o.optString("nombre"), o.optString("direccion"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

internal suspend fun pedirRutas(
    origenLat: Double, origenLon: Double, destinoLat: Double, destinoLon: Double
): List<RutaOpcion> {
    val arreglo = JSONArray(
        getJson(
            "/api/rutas?origen_lat=$origenLat&origen_lon=$origenLon" +
                    "&destino_lat=$destinoLat&destino_lon=$destinoLon"
        )
    )
    return List(arreglo.length()) { i ->
        val o = arreglo.getJSONObject(i)
        val pts = o.getJSONArray("puntos")
        RutaOpcion(
            distanciaM = o.optDouble("distancia_m", 0.0),
            tiempoS = o.optDouble("tiempo_s", 0.0),
            retrasoS = o.optDouble("retraso_trafico_s", 0.0),
            puntos = List(pts.length()) { j ->
                val p = pts.getJSONArray(j)
                p.getDouble(0) to p.getDouble(1)
            }
        )
    }
}