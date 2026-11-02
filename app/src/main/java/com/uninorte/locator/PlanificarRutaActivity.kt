package com.uninorte.locator

import android.Manifest
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
    private lateinit var lvRutas: ListView

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
        setContentView(R.layout.activity_planificar_ruta)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        etDestino = findViewById(R.id.etDestino)
        tvOrigen = findViewById(R.id.tvOrigen)
        tvEstado = findViewById(R.id.tvEstado)
        lvLugares = findViewById(R.id.lvLugares)
        lvRutas = findViewById(R.id.lvRutas)

        findViewById<MaterialButton>(R.id.btnBuscar).setOnClickListener { buscar() }
        etDestino.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_SEARCH) { buscar(); true } else false
        }
        lvLugares.setOnItemClickListener { _, _, posicion, _ -> calcularRutas(lugares[posicion]) }
        lvRutas.setOnItemClickListener { _, _, posicion, _ ->
            // Etapa 2: aquí se dibujará la ruta y se enviará la elegida al servidor.
            tvEstado.text = getString(R.string.route_chosen, posicion + 1)
        }

        obtenerOrigen()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
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
        lvRutas.visibility = View.GONE
        lifecycleScope.launch {
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
        tvEstado.text = getString(R.string.calculating)
        lifecycleScope.launch {
            try {
                val resultado = pedirRutas(o.first, o.second, lugar.lat, lugar.lon)
                rutas = resultado
                if (resultado.isEmpty()) {
                    tvEstado.text = getString(R.string.no_results)
                    return@launch
                }
                lvRutas.adapter = ArrayAdapter(
                    this@PlanificarRutaActivity, R.layout.item_lista,
                    resultado.mapIndexed { i, r -> describirRuta(i, r) }
                )
                lvLugares.visibility = View.GONE
                lvRutas.visibility = View.VISIBLE
                tvEstado.text = getString(R.string.routes_to, lugar.nombre)
            } catch (e: Exception) {
                tvEstado.text = getString(R.string.error_network, e.message ?: "")
            }
        }
    }

    private fun describirRuta(indice: Int, ruta: RutaOpcion): String {
        val km = ruta.distanciaM / 1000.0
        val minutos = Math.round(ruta.tiempoS / 60.0)
        val retraso = Math.round(ruta.retrasoS / 60.0)
        val base = getString(R.string.route_item, indice + 1, km, minutos)
        return if (retraso > 0) base + getString(R.string.route_traffic_extra, retraso) else base
    }
}