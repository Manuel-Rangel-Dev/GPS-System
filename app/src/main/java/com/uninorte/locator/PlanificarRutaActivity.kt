package com.uninorte.locator

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.res.ColorStateList
import android.util.TypedValue
import androidx.core.widget.TextViewCompat
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class PlanificarRutaActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var etDestino: EditText
    private lateinit var tvOrigen: TextView
    private lateinit var tvEstado: TextView
    private lateinit var lvLugares: ListView
    private lateinit var mapa: MapView
    private var destino: Lugar? = null
    private var elegida = 0
    private var mostrandoRutas = false
    private var peticion: Job? = null
    private var lineas: List<Polyline> = emptyList()

    private var origen: Pair<Double, Double>? = null   // (lat, lon) del celular
    private var lugares: List<Lugar> = emptyList()
    private var rutas: List<RutaOpcion> = emptyList()

    private val permisoUbicacion = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permisos ->
        if (permisos[Manifest.permission.ACCESS_FINE_LOCATION] == true) obtenerOrigen()
        else tvOrigen.text = getString(R.string.origin_no_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "mapa").apply { mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "teselas").apply { mkdirs() }
        }
        setContentView(R.layout.activity_planificar_ruta)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        etDestino = findViewById(R.id.etDestino)
        tvOrigen = findViewById(R.id.tvOrigen)
        tvEstado = findViewById(R.id.tvEstado)
        lvLugares = findViewById(R.id.lvLugares)
        mapa = findViewById(R.id.mapa)
        mapa.setTileSource(TileSourceFactory.MAPNIK)
        mapa.setMultiTouchControls(true)
        mapa.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        findViewById<MaterialButton>(R.id.btnNavegar).setOnClickListener { navegar() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mostrandoRutas) mostrarBusqueda() else finish()
            }
        })

        findViewById<MaterialButton>(R.id.btnBuscar).setOnClickListener { buscar() }
        etDestino.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_SEARCH) { buscar(); true } else false
        }
        lvLugares.setOnItemClickListener { _, _, posicion, _ -> calcularRutas(lugares[posicion]) }

        obtenerOrigen()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    @Suppress("MissingPermission")
    private fun obtenerOrigen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permisoUbicacion.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        tvOrigen.text = getString(R.string.origin_searching)
        lifecycleScope.launch {
            try {
                val peticion = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build()
                val cancelacion = CancellationTokenSource()
                val ubicacion = try {
                    fusedLocationClient.getCurrentLocation(peticion, cancelacion.token).await()
                } finally {
                    cancelacion.cancel()
                }
                if (ubicacion == null) {
                    tvOrigen.text = getString(R.string.origin_unavailable)
                    return@launch
                }
                origen = ubicacion.latitude to ubicacion.longitude
                tvOrigen.text = getString(R.string.origin_ok, ubicacion.latitude, ubicacion.longitude)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                tvOrigen.text = getString(R.string.origin_unavailable)
            }
        }
    }

    private fun buscar() {
        val texto = etDestino.text.toString().trim()
        if (texto.length < 3) {
            tvEstado.text = getString(R.string.type_more)
            return
        }
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(etDestino.windowToken, 0)
        tvEstado.text = getString(R.string.searching)
        peticion?.cancel()
        peticion = lifecycleScope.launch {
            try {
                val resultado = buscarLugares(texto, origen?.first, origen?.second)
                lugares = resultado
                lvLugares.adapter = ArrayAdapter(
                    this@PlanificarRutaActivity, R.layout.item_lista,
                    resultado.map {
                        if (it.direccion.isNotBlank() && it.direccion != it.nombre)
                            "${it.nombre}\n${it.direccion}" else it.nombre
                    }
                )
                lvLugares.visibility = View.VISIBLE
                tvEstado.text = getString(
                    if (resultado.isEmpty()) R.string.no_results else R.string.choose_destination
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                tvEstado.text = getString(R.string.error_network, e.message ?: "")
            }
        }
    }

    private fun calcularRutas(lugar: Lugar) {
        val o = origen
        if (o == null) {
            tvEstado.text = getString(R.string.origin_unavailable)
            return
        }
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(etDestino.windowToken, 0)
        etDestino.clearFocus()
        tvEstado.text = getString(R.string.calculating)
        peticion?.cancel()
        peticion = lifecycleScope.launch {
            try {
                val resultado = pedirRutas(o.first, o.second, lugar.lat, lugar.lon)
                rutas = resultado
                if (resultado.isEmpty()) {
                    tvEstado.text = getString(R.string.no_results)
                    return@launch
                }
                destino = lugar
                elegida = 0
                mostrarRutas()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                tvEstado.text = getString(R.string.error_network, e.message ?: "")
            }
        }
    }

    private fun mostrarBusqueda() {
        mostrandoRutas = false
        findViewById<View>(R.id.panelRutas).visibility = View.GONE
        findViewById<View>(R.id.panelBusqueda).visibility = View.VISIBLE
        supportActionBar?.setTitle(R.string.title_plan_route)
    }

    private fun mostrarRutas() {
        mostrandoRutas = true
        findViewById<View>(R.id.panelBusqueda).visibility = View.GONE
        findViewById<View>(R.id.panelRutas).visibility = View.VISIBLE
        supportActionBar?.title = getString(R.string.routes_to, destino?.nombre)
        val selector = findViewById<ChipGroup>(R.id.selectorRutas)
        selector.removeAllViews()
        rutas.forEachIndexed { indice, _ ->
            selector.addView(Chip(this).apply {
                id = View.generateViewId()
                text = getString(R.string.ruta_numero, indice + 1)
                isCheckable = true
                isCheckedIconVisible = false
                setTextColor(ContextCompat.getColor(this@PlanificarRutaActivity, R.color.white))
                chipBackgroundColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ContextCompat.getColor(this@PlanificarRutaActivity, R.color.uninorte_red),
                        ContextCompat.getColor(this@PlanificarRutaActivity, R.color.card_background)))
                chipStrokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@PlanificarRutaActivity, R.color.divider))
                chipStrokeWidth = resources.displayMetrics.density
                setOnClickListener { seleccionarRuta(indice) }
            })
        }
        dibujarRutas()
        seleccionarRuta(elegida)
        val puntos = rutas.flatMap { it.puntos }.map { GeoPoint(it.first, it.second) } +
            listOfNotNull(origen?.let { GeoPoint(it.first, it.second) },
                destino?.let { GeoPoint(it.lat, it.lon) })
        mapa.doOnLayout {
            if (puntos.isNotEmpty()) {
                mapa.zoomToBoundingBox(BoundingBox.fromGeoPoints(puntos), false,
                    (32 * resources.displayMetrics.density).toInt(), 18.0, null)
            }
        }
    }

    private fun seleccionarRuta(indice: Int) {
        elegida = indice
        val selector = findViewById<ChipGroup>(R.id.selectorRutas)
        selector.check(selector.getChildAt(indice).id)
        val ruta = rutas[indice]
        val estimado = ruta.litros != null && ruta.costoCop != null
        findViewById<TextView>(R.id.tvCosto).apply {
            text = if (estimado) formatearPesos(ruta.costoCop!!) else getString(R.string.sin_estimado)
            // Sin estimado permanece en una línea y solo reduce su tamaño si no cabe.
            maxLines = if (estimado) Int.MAX_VALUE else 1
            if (estimado) {
                TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            } else {
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 18, 36, 1, TypedValue.COMPLEX_UNIT_SP)
            }
        }
        findViewById<TextView>(R.id.tvLitros).apply {
            visibility = if (estimado) View.VISIBLE else View.GONE
            text = ruta.litros?.let(::formatearLitros)
        }
        findViewById<View>(R.id.tvSinEstimado).visibility = if (estimado) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvDetalleRuta).text = getString(R.string.detalle_ruta,
            formatearKm(ruta.distanciaM / 1000), formatearDuracion(ruta.tiempoS)) +
            if (ruta.retrasoS > 0) getString(R.string.trafico_ruta, formatearDuracion(ruta.retrasoS)) else ""
        lineas.forEachIndexed { numero, linea ->
            linea.outlinePaint.color = ContextCompat.getColor(this,
                if (numero == indice) R.color.uninorte_red else R.color.text_secondary)
            linea.outlinePaint.strokeWidth = (if (numero == indice) 7f else 4f) * resources.displayMetrics.density
        }
        mapa.overlays.remove(lineas[indice])
        // Mantener los marcadores encima de todas las líneas.
        mapa.overlays.add(lineas.size - 1, lineas[indice])
        mapa.invalidate()
    }

    private fun dibujarRutas() {
        mapa.overlays.clear()
        lineas = rutas.indices.map { indice ->
            Polyline(mapa).apply {
                setPoints(rutas[indice].puntos.map { GeoPoint(it.first, it.second) })
                setOnClickListener { _, _, _ ->
                    mapa.post { seleccionarRuta(indice) }
                    true
                }
            }
        }
        mapa.overlays.addAll(lineas)
        origen?.let { agregarMarcador(GeoPoint(it.first, it.second), R.string.marcador_origen,
            R.drawable.marcador_origen, Marker.ANCHOR_CENTER) }
        destino?.let { agregarMarcador(GeoPoint(it.lat, it.lon), R.string.marcador_destino,
            R.drawable.marcador_destino, Marker.ANCHOR_BOTTOM) }
        mapa.invalidate()
    }

    private fun agregarMarcador(punto: GeoPoint, titulo: Int, icono: Int, anclaVertical: Float) {
        mapa.overlays.add(Marker(mapa).apply {
            position = punto
            title = getString(titulo)
            icon = ContextCompat.getDrawable(this@PlanificarRutaActivity, icono)
            setAnchor(Marker.ANCHOR_CENTER, anclaVertical)
            setInfoWindow(null)
        })
    }

    private fun navegar() {
        val o = origen ?: return
        val d = destino ?: return
        val ruta = rutas.getOrNull(elegida) ?: return
        if (ruta.prediccionId != null && ruta.rutaId != null) {
            lifecycleScope.launch {
                try {
                    marcarRutaElegida(ruta.prediccionId, ruta.rutaId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("PlanificarRuta", "No se pudo registrar la ruta elegida", e)
                }
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse(urlGoogleMaps(o.first, o.second, d.lat, d.lon))))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.maps_no_disponible, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        mapa.onResume()
    }

    override fun onPause() {
        mapa.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mapa.onDetach()
        super.onDestroy()
    }
}
