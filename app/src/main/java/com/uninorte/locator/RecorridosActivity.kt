package com.uninorte.locator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class RecorridosActivity : AppCompatActivity() {
    private lateinit var vehiculo: Vehiculo
    private lateinit var actualizar: MaterialButton
    private lateinit var estado: TextView
    private lateinit var resumen: TextView
    private lateinit var lista: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorridos)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }
        vehiculo = Vehiculo.obtener(this)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.titulo_recorridos,
            getString(if (vehiculo == Vehiculo.KIA_K3) R.string.vehiculo_kia else R.string.vehiculo_dos))
        actualizar = findViewById(R.id.btnActualizar)
        estado = findViewById(R.id.tvEstadoRecorridos)
        resumen = findViewById(R.id.tvResumen)
        lista = findViewById(R.id.listaRecorridos)
        actualizar.setOnClickListener { cargar() }
        cargar()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun cargar() {
        actualizar.isEnabled = false
        actualizar.setText(R.string.actualizar)
        findViewById<View>(R.id.progresoRecorridos).visibility = View.VISIBLE
        lista.visibility = View.GONE
        resumen.visibility = View.GONE
        estado.visibility = View.VISIBLE
        estado.setText(R.string.cargando_recorridos)
        lifecycleScope.launch {
            try {
                val recorridos = pedirRecorridos(if (vehiculo == Vehiculo.KIA_K3) "kia_k3" else "celular")
                val totales = resumirRecorridos(recorridos)
                resumen.text = resources.getQuantityString(R.plurals.resumen_recorridos,
                    totales.cantidad, totales.cantidad,
                    formatearLitros(totales.litros), formatearPesos(totales.costoCop))
                resumen.visibility = View.VISIBLE
                estado.visibility = if (recorridos.isEmpty()) View.VISIBLE else View.GONE
                estado.setText(R.string.recorridos_vacios)
                lista.adapter = AdaptadorRecorridos(recorridos)
                lista.visibility = if (recorridos.isEmpty()) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                estado.setText(R.string.recorridos_error)
                actualizar.setText(R.string.reintentar)
            } finally {
                findViewById<View>(R.id.progresoRecorridos).visibility = View.GONE
                actualizar.isEnabled = true
            }
        }
    }

    private inner class AdaptadorRecorridos(private val recorridos: List<Recorrido>) : BaseAdapter() {
        override fun getCount() = recorridos.size
        override fun getItem(position: Int) = recorridos[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val vista = convertView ?: LayoutInflater.from(this@RecorridosActivity)
                .inflate(R.layout.item_recorrido, parent, false)
            val recorrido = getItem(position)
            vista.findViewById<TextView>(R.id.fechaRecorrido).text = formatearInicio(recorrido.inicio)
            vista.findViewById<TextView>(R.id.costoRecorrido).text = recorrido.costoCop?.let(::formatearPesos) ?: getString(R.string.costo_no_disponible)
            vista.findViewById<TextView>(R.id.detalleRecorrido).text = getString(R.string.detalle_recorrido,
                formatearKm(recorrido.distanciaKm), formatearDuracion(recorrido.duracionS),
                formatearLitros(recorrido.litros))
            val medidos = listOfNotNull(recorrido.litrosMedidos?.let(::formatearLitros),
                recorrido.costoMedidoCop?.let(::formatearPesos))
            vista.findViewById<TextView>(R.id.medidoRecorrido).apply {
                visibility = if (vehiculo == Vehiculo.KIA_K3 && medidos.isNotEmpty()) View.VISIBLE else View.GONE
                text = getString(R.string.recorrido_medido, medidos.joinToString(" · "))
            }
            vista.findViewById<View>(R.id.etiquetaEnCurso).visibility = if (recorrido.enCurso) View.VISIBLE else View.GONE
            vista.findViewById<View>(R.id.etiquetaEmulado).visibility = if (recorrido.emulado) View.VISIBLE else View.GONE
            return vista
        }
    }
}
