package com.uninorte.locator

import android.os.Bundle
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ScrollView
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
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

    private var rango = RangoFechas.inicial(LocalDate.now())
    private var desplegado = false
    private val idioma = Locale("es", "CO")
    private val fechaCorta = DateTimeFormatter.ofPattern("dd MMM yyyy", idioma)
    private val fechaHoraBarra = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ROOT)
    private fun corta(dia: LocalDate): String = dia.format(fechaCorta).replace(".", "")
        .split(" ").mapIndexed { indice, parte -> if (indice == 1) parte.replaceFirstChar { it.titlecase(idioma) } else parte }.joinToString(" ")
    private val camposDesde = intArrayOf(R.id.desdeHH, R.id.desdeMM, R.id.desdeSS)
    private val camposHasta = intArrayOf(R.id.hastaHH, R.id.hastaMM, R.id.hastaSS)

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
        supportActionBar?.title = if (ModoApp.obtener(this) == ModoApp.PRODUCTO) getString(R.string.rutas_hechas)
            else getString(R.string.titulo_recorridos,
            getString(if (vehiculo == Vehiculo.KIA_K3) R.string.vehiculo_kia else R.string.vehiculo_dos))
        actualizar = findViewById(R.id.btnActualizar)
        estado = findViewById(R.id.tvEstadoRecorridos)
        resumen = findViewById(R.id.tvResumen)
        lista = findViewById(R.id.listaRecorridos)
        configurarSelector(savedInstanceState)
        actualizar.setOnClickListener {
            guardarHoras()
            if (!rango.esValido()) {
                estado.visibility = View.VISIBLE
                estado.setText(R.string.rango_invertido)
            } else {
                desplegar(false)
                cargar()
            }
        }
        cargar()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun cargar() {
        if (!rango.esValido()) {
            estado.visibility = View.VISIBLE
            estado.setText(R.string.rango_invertido)
            return
        }
        actualizar.isEnabled = false
        actualizar.setText(R.string.ver_recorridos)
        findViewById<View>(R.id.progresoRecorridos).visibility = View.VISIBLE
        lista.visibility = View.GONE
        resumen.visibility = View.GONE
        findViewById<View>(R.id.tvResumenDetalle).visibility = View.GONE
        val consulta = rango
        estado.visibility = View.VISIBLE
        estado.setText(R.string.cargando_recorridos)
        lifecycleScope.launch {
            try {
                val respuesta = pedirRecorridos(if (vehiculo == Vehiculo.KIA_K3) "kia_k3" else "celular",
                    consulta.desdeApi(), consulta.hastaApi())
                val recorridos = respuesta.recorridos
                val totales = resumirRecorridos(recorridos)
                resumen.text = if (totales.cantidad == 0) getString(R.string.recorridos_vacios)
                    else resources.getQuantityString(R.plurals.resumen_recorridos,
                        totales.cantidad, totales.cantidad,
                        formatearLitrosResumen(totales.litros), formatearPesos(totales.costoCop))
                resumen.visibility = View.VISIBLE
                val detalles = mutableListOf<String>()
                if (totales.cantidad > 0) detalles.add(getString(R.string.estimado_modelo))
                if (vehiculo == Vehiculo.KIA_K3 && (totales.litrosMedidos != null || totales.costoMedidoCop != null)) {
                    detalles.add(getString(R.string.recorrido_medido,
                        listOf(formatearLitrosResumen(totales.litrosMedidos ?: 0.0),
                            formatearPesos(totales.costoMedidoCop ?: 0.0)).joinToString(" · ")))
                }
                if (totales.cantidad == 0 && totales.emulados > 0) detalles.add(resources.getQuantityString(
                    R.plurals.emulados_sin_contar, totales.emulados, totales.emulados))
                if (respuesta.truncado) detalles.add(getString(R.string.resumen_parcial))
                findViewById<TextView>(R.id.tvResumenDetalle).apply {
                    text = detalles.joinToString("\n")
                    visibility = if (detalles.isEmpty()) View.GONE else View.VISIBLE
                }
                estado.visibility = View.GONE
                lista.adapter = AdaptadorRecorridos(recorridos)
                lista.visibility = if (recorridos.isEmpty()) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                estado.visibility = View.VISIBLE
                estado.text = (e as? ErrorApi)?.detalle ?: getString(R.string.recorridos_error)
            } finally {
                findViewById<View>(R.id.progresoRecorridos).visibility = View.GONE
                actualizar.isEnabled = true
            }
        }
    }

    private fun configurarSelector(guardado: Bundle?) {
        guardado?.getString("desde")?.let {
            rango = rango.copy(desde = LocalDate.parse(it), hasta = LocalDate.parse(guardado.getString("hasta")),
                mes = YearMonth.parse(guardado.getString("mes")),
                modo = ModoFecha.valueOf(requireNotNull(guardado.getString("modo"))),
                horaDesde = Hora12.normalizar(guardado.getString("dh") ?: "12", guardado.getString("dm") ?: "0", guardado.getString("ds") ?: "0", guardado.getBoolean("dp")),
                horaHasta = Hora12.normalizar(guardado.getString("hh") ?: "11", guardado.getString("hm") ?: "59", guardado.getString("hs") ?: "59", guardado.getBoolean("hp")))
        }
        // Acota el panel para reservar espacio a la lista incluso en 360 × 640 dp.
        findViewById<ScrollView>(R.id.selectorRango).layoutParams.height =
            (resources.displayMetrics.heightPixels * 0.42f).toInt()
        findViewById<View>(R.id.barraRango).setOnClickListener { guardarHoras(); desplegar(!desplegado) }
        listOf(R.id.tabDesde to ModoFecha.DESDE, R.id.tabHasta to ModoFecha.HASTA).forEach { (id, modo) ->
            findViewById<View>(id).setOnClickListener {
                rango = rango.copy(modo = modo, mes = YearMonth.from(if (modo == ModoFecha.DESDE) rango.desde else rango.hasta))
                pintarCalendario()
            }
        }
        findViewById<View>(R.id.mesAnterior).setOnClickListener { rango = rango.cambiarMes(-1); pintarCalendario() }
        findViewById<View>(R.id.mesSiguiente).setOnClickListener { rango = rango.cambiarMes(1); pintarCalendario() }
        escribirHoras()
        (camposDesde + camposHasta).forEach { id ->
            findViewById<EditText>(id).setOnFocusChangeListener { _, foco -> if (!foco) guardarHoras() }
        }
        listOf(R.id.desdePeriodo, R.id.hastaPeriodo).forEach { id ->
            findViewById<View>(id).setOnClickListener {
                guardarHoras()
                rango = if (id == R.id.desdePeriodo) rango.copy(horaDesde = rango.horaDesde.copy(pm = !rango.horaDesde.pm))
                    else rango.copy(horaHasta = rango.horaHasta.copy(pm = !rango.horaHasta.pm))
                escribirHoras(); pintarBarra()
            }
        }
        resources.getStringArray(R.array.dias_semana).forEach { texto ->
            agregarCelda(findViewById(R.id.diasSemana), texto, 28)
        }
        pintarCalendario()
        desplegar(guardado?.getBoolean("abierto") ?: false)
    }

    private fun guardarHoras() {
        fun leer(ids: IntArray, pm: Boolean) = Hora12.normalizar(
            findViewById<EditText>(ids[0]).text.toString(), findViewById<EditText>(ids[1]).text.toString(),
            findViewById<EditText>(ids[2]).text.toString(), pm)
        rango = rango.copy(horaDesde = leer(camposDesde, rango.horaDesde.pm), horaHasta = leer(camposHasta, rango.horaHasta.pm))
        escribirHoras()
        pintarBarra()
    }

    private fun escribirHoras() {
        listOf(camposDesde to rango.horaDesde, camposHasta to rango.horaHasta).forEach { (ids, hora) ->
            ids.zip(listOf(hora.hh, hora.mm, hora.ss)).forEach { (id, valor) ->
                val texto = String.format(Locale.ROOT, "%02d", valor)
                val campo = findViewById<EditText>(id)
                if (campo.text.toString() != texto) campo.setText(texto)
            }
        }
        findViewById<Button>(R.id.desdePeriodo).setText(if (rango.horaDesde.pm) R.string.periodo_pm else R.string.periodo_am)
        findViewById<Button>(R.id.hastaPeriodo).setText(if (rango.horaHasta.pm) R.string.periodo_pm else R.string.periodo_am)
    }

    private fun desplegar(abierto: Boolean) {
        desplegado = abierto
        if (!abierto) {
            currentFocus?.let { foco ->
                foco.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(foco.windowToken, 0)
            }
        }
        findViewById<View>(R.id.selectorRango).visibility = if (abierto) View.VISIBLE else View.GONE
        pintarBarra()
    }

    private fun pintarBarra() {
        findViewById<TextView>(R.id.barraRango).apply {
            // dd/MM/yyyy HH:mm:ss (24 h): el AM/PM queda en los campos del selector.
            fun fecha(dia: LocalDate, hora: Hora12): String = dia.atTime(hora.a24()).format(fechaHoraBarra)
            text = getString(R.string.barra_rango, fecha(rango.desde, rango.horaDesde), fecha(rango.hasta, rango.horaHasta))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_calendario, 0,
                if (desplegado) R.drawable.ic_plegar else R.drawable.ic_expandir, 0)
            contentDescription = text.toString() + ". " + getString(if (desplegado) R.string.plegar_rango else R.string.expandir_rango)
        }
    }

    private fun agregarCelda(grid: GridLayout, texto: String, alto: Int): TextView {
        val celda = TextView(this).apply {
            text = texto
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.white))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = (alto * resources.displayMetrics.density).toInt()
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
        }
        grid.addView(celda)
        return celda
    }

    private fun pintarCalendario() {
        listOf(Triple(R.id.tabDesde, R.string.tab_desde, ModoFecha.DESDE),
            Triple(R.id.tabHasta, R.string.tab_hasta, ModoFecha.HASTA)).forEach { (id, texto, modo) ->
            findViewById<Button>(id).apply {
                text = getString(texto, corta(if (modo == ModoFecha.DESDE) rango.desde else rango.hasta))
                backgroundTintList = ColorStateList.valueOf(getColor(if (rango.modo == modo) R.color.uninorte_red else R.color.divider))
            }
        }
        findViewById<TextView>(R.id.tituloMes).text = rango.mes.format(DateTimeFormatter.ofPattern("MMMM yyyy", idioma))
            .replaceFirstChar { it.titlecase(idioma) }
        findViewById<View>(R.id.mesAnterior).apply { isEnabled = rango.puedeRetroceder(); alpha = if (isEnabled) 1f else 0.3f }
        val grid = findViewById<GridLayout>(R.id.diasCalendario)
        grid.removeAllViews()
        repeat(rango.mes.atDay(1).dayOfWeek.value % 7) { agregarCelda(grid, "", 44) }
        val hoy = LocalDate.now()
        for (numero in 1..rango.mes.lengthOfMonth()) {
            val dia = rango.mes.atDay(numero)
            agregarCelda(grid, numero.toString(), 44).apply {
                isEnabled = !rango.esFuturo(dia, hoy)
                alpha = if (isEnabled) 1f else 0.3f
                contentDescription = dia.format(fechaCorta)
                when {
                    dia == rango.desde || dia == rango.hasta -> setBackgroundColor(getColor(R.color.uninorte_red))
                    dia > rango.desde && dia < rango.hasta -> setBackgroundColor(getColor(R.color.uninorte_red_dark))
                }
                setOnClickListener { rango = rango.seleccionar(dia, LocalDate.now()); pintarCalendario() }
            }
        }
        pintarBarra()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        guardarHoras()
        outState.putString("desde", rango.desde.toString()); outState.putString("hasta", rango.hasta.toString())
        outState.putString("mes", rango.mes.toString()); outState.putString("modo", rango.modo.name)
        listOf("d" to rango.horaDesde, "h" to rango.horaHasta).forEach { (prefijo, hora) ->
            outState.putString(prefijo + "h", hora.hh.toString()); outState.putString(prefijo + "m", hora.mm.toString())
            outState.putString(prefijo + "s", hora.ss.toString()); outState.putBoolean(prefijo + "p", hora.pm)
        }
        outState.putBoolean("abierto", desplegado)
        super.onSaveInstanceState(outState)
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
