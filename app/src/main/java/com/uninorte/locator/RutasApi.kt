package com.uninorte.locator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Servidor con la API (cambiar aquí si se usa otro)
internal const val API_BASE_URL = "http://mrlocator.me:8443"

data class Lugar(val nombre: String, val direccion: String, val lat: Double, val lon: Double)

data class RutaOpcion(
    val distanciaM: Double,
    val tiempoS: Double,
    val retrasoS: Double,
    val puntos: List<Pair<Double, Double>>,
    val litros: Double?,
    val costoCop: Double?,
    val prediccionId: Long?,
    val rutaId: Long?
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
    return parsearRutas(getJson(
        "/api/rutas?origen_lat=$origenLat&origen_lon=$origenLon" +
            "&destino_lat=$destinoLat&destino_lon=$destinoLon"
    ))
}

// isNull también cubre claves ausentes; nunca convertir null en NaN ni en "null".
private fun JSONObject.numeroOpcional(clave: String): Double? =
    if (isNull(clave)) null else optDouble(clave).takeIf { it.isFinite() }

private fun JSONObject.idOpcional(clave: String): Long? =
    if (isNull(clave)) null else getLong(clave)

internal fun parsearRutas(json: String): List<RutaOpcion> {
    val arreglo = JSONArray(json)
    return List(arreglo.length()) { i ->
        val o = arreglo.getJSONObject(i)
        val pts = o.getJSONArray("puntos")
        RutaOpcion(
            distanciaM = o.getDouble("distancia_m"),
            tiempoS = o.getDouble("tiempo_s"),
            retrasoS = o.optDouble("retraso_trafico_s", 0.0),
            puntos = List(pts.length()) { j ->
                val punto = pts.getJSONArray(j)
                punto.getDouble(0) to punto.getDouble(1)
            },
            litros = o.numeroOpcional("litros"),
            costoCop = o.numeroOpcional("costo_cop"),
            prediccionId = o.idOpcional("prediccion_id"),
            rutaId = o.idOpcional("ruta_id")
        )
    }
}

data class Recorrido(
    val id: String,
    val inicio: String,
    val fin: String?,
    val duracionS: Double,
    val distanciaKm: Double,
    val velMediaKmh: Double?,
    val litros: Double,
    val costoCop: Double?,
    val litrosMedidos: Double?,
    val costoMedidoCop: Double?,
    val modelo: String?,
    val factor: Double?,
    val enCurso: Boolean,
    val emulado: Boolean
)

internal fun parsearRecorridos(json: String): List<Recorrido> {
    val arreglo = JSONObject(json).getJSONArray("recorridos")
    return (0 until arreglo.length()).mapNotNull { i ->
        val o = arreglo.optJSONObject(i) ?: return@mapNotNull null
        val id = if (o.isNull("id")) null else o.optString("id").takeIf { it.isNotBlank() }
        val inicio = if (o.isNull("inicio")) null else o.optString("inicio").takeIf { it.isNotBlank() }
        val duracion = o.numeroOpcional("duracion_s")
        val distancia = o.numeroOpcional("distancia_km")
        val litros = o.numeroOpcional("litros")
        if (id == null || inicio == null || duracion == null || distancia == null || litros == null) {
            return@mapNotNull null
        }
        Recorrido(
            id = id, inicio = inicio,
            fin = if (o.isNull("fin")) null else o.getString("fin"),
            duracionS = duracion, distanciaKm = distancia,
            velMediaKmh = o.numeroOpcional("vel_media_kmh"), litros = litros,
            costoCop = o.numeroOpcional("costo_cop"),
            litrosMedidos = o.numeroOpcional("litros_medidos"),
            costoMedidoCop = o.numeroOpcional("costo_medido_cop"),
            modelo = if (o.isNull("modelo")) null else o.getString("modelo"),
            factor = o.numeroOpcional("factor"),
            enCurso = o.optBoolean("en_curso", false), emulado = o.optBoolean("emulado", false)
        )
    }
}

internal suspend fun pedirRecorridos(vid: String): List<Recorrido> =
    parsearRecorridos(getJson("/api/recorridos?vid=${URLEncoder.encode(vid, "UTF-8")}"))

internal suspend fun marcarRutaElegida(prediccionId: Long, rutaId: Long) = withContext(Dispatchers.IO) {
    val conexion = URL("$API_BASE_URL/api/rutas/elegida").openConnection() as HttpURLConnection
    try {
        conexion.connectTimeout = 8_000
        conexion.readTimeout = 15_000
        conexion.requestMethod = "POST"
        conexion.doOutput = true
        conexion.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        val cuerpo = JSONObject().put("prediccion_id", prediccionId).put("ruta_id", rutaId).toString()
        conexion.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(cuerpo) }
        val codigo = conexion.responseCode
        if (codigo !in 200..299) throw IOException("HTTP $codigo")
        conexion.inputStream.use { it.readBytes() }
        Unit
    } finally {
        conexion.disconnect()
    }
}
