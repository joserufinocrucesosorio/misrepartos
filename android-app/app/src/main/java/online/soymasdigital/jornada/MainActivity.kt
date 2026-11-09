package online.soymasdigital.jornada

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import online.soymasdigital.jornada.data.CapturasKeep
import online.soymasdigital.jornada.data.Repo
import online.soymasdigital.jornada.pdf.Expediente
import online.soymasdigital.jornada.pdf.Factura
import online.soymasdigital.jornada.pdf.InformeDia
import online.soymasdigital.jornada.pdf.Justificacion
import online.soymasdigital.jornada.pdf.SalidaPdf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var repo: Repo
    private lateinit var tvEstado: TextView
    private lateinit var tvAlerta: TextView
    private lateinit var tvChecklist: TextView
    private lateinit var tvTiempo: TextView
    private var jid: Long = 0
    private var visorMax = 150
    private var kmPendienteId = -1L
    private var gastoPendienteId = -1L
    private var pendiente: (() -> Unit)? = null
    private var pendienteFase: Pair<String, String>? = null
    private var bCaptura: Button? = null
    private val REQ_FOTO = 71
    private val REQ_FOTO_GASTO = 72
    private val REQ_FOTO_FLUJO = 73
    private val REQ_FOTO_CONSIG = 74
    private val REQ_PERM_FOTOS = 75
    private val REQ_CARPETA = 76
    private var accionTrasCarpeta: (() -> Unit)? = null
    private var fotoConsigPendiente = ""
    private var consigPendienteId = -1L
    private var fotoFlujoPendiente = ""
    private var pedidoFoto = ""
    private var fotoTipo = ""
    private var pedidoFotoPendiente: String? = null
    private val bloques = HashMap<String, LinearLayout>()
    private val TIPOS_FOTO = arrayOf("Trancon / trafico", "Lluvia / clima",
        "Cartel del local (estara listo en N min)", "Portal / acceso", "Estado del pedido", "Otra prueba")
    private val horaFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val diaFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repo(this)
        setContentView(construirUI())
        pedirPermisos()
        // v2.1.1: retencion (30 dias) y tope (400 MB) de las copias propias de capturas.
        try { CapturasKeep.limpiar(this) } catch (e: Exception) { }
        try {
            val i = Intent(this, online.soymasdigital.jornada.service.JornadaService::class.java)
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            toast("No se pudo arrancar el servicio: " + e.message)
        }
        manejarAccion(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        manejarAccion(intent)
    }

    override fun onResume() {
        super.onResume()
        refrescar()
        comprobarJornadaPendiente()
        avisoAlmacenamiento(false)
        val pf = pendienteFase
        if (pf != null) {
            pendienteFase = null
            if (jid != 0L) marcarFase(pf.first, pf.second)
        }
        val pfp = pedidoFotoPendiente
        if (pfp != null) {
            pedidoFotoPendiente = null
            if (jid != 0L) fotoFlujo(pfp)
        }
    }

    private fun manejarAccion(intent: Intent?) {
        when (intent?.getStringExtra("accion")) {
            "snapshot" -> pedirSnapshot()
            "incidencia" -> flujoIncidencia()
            "km" -> pedirKm()
            "fase" -> {
                val p = intent.getStringExtra("pedido")
                val f = intent.getStringExtra("fase")
                if (p != null && f != null) pendienteFase = p to f
            }
            "foto" -> {
                val p = intent.getStringExtra("pedido")
                if (p != null) pedidoFotoPendiente = p
            }
        }
    }

    // ---------------- UI ----------------

    private fun construirUI(): ScrollView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(40, 40, 40, 40)

        val titulo = TextView(this)
        titulo.text = "MisRepartos"
        titulo.textSize = 22f
        titulo.setTypeface(null, android.graphics.Typeface.BOLD)
        titulo.setTextColor(Color.parseColor("#0F172A"))
        titulo.setPadding(0, dp(4), 0, dp(8))
        col.addView(titulo)

        tvAlerta = TextView(this)
        tvAlerta.textSize = 16f
        tvAlerta.setPadding(18, 18, 18, 18)
        tvAlerta.setTypeface(null, android.graphics.Typeface.BOLD)
        tvAlerta.gravity = Gravity.CENTER
        col.addView(tvAlerta)

        val sub = TextView(this)
        sub.text = "Evidencia propia del repartidor: jornada como bloque unico, km de la moto y cartera (inicio/fin), " +
                "pedidos, FLUJO por pedido (fases con hora real), incidencias de cliente ausente y clima adverso. " +
                "La app NO modifica ni pulsa nada en la app de reparto."
        sub.textSize = 11f
        col.addView(sub)

        // v2.1.0: acceso directo a la justificacion de un aviso (visible nada mas abrir)
        val bJust = boton("\uD83D\uDCC4  JUSTIFICACIÓN de un aviso o sanción (PDF)") { formularioJustificacion() }
        bJust.setTextColor(Color.WHITE)
        bJust.background = fondo("#B91C1C", 12)
        col.addView(bJust)

        // ---------- BLOQUE A: PREPARAR JORNADA ----------
        montarBloque(col, "A", "BLOQUE A - PREPARAR JORNADA")
        item("A", boton("INICIO GUIADO: horario + cartera + odometro (en casa)") { inicioGuiado() })
        item("A", boton("LLEGADA AL AREA DE TRABAJO (km + foto odometro)") { llegadaZona() })
        val manA = subBloque(bloques["A"]!!, "man_A", "MANUALES  (si algo falla)")
        manA.addView(boton("Preparar jornada a mano (horario + km + cartera)") { prepararJornada() })
        manA.addView(boton("Horario programado (inicio / fin)") { pedirHorario { refrescar() } })
        manA.addView(boton("KM de la moto al INICIO + foto") { pedirKmMomento("INICIO") { refrescar() } })
        manA.addView(boton("CARTERA de INICIO: pantallazo + saldo") { capturarCarteraGuiado("INICIO") { refrescar() } })

        // ---------- BLOQUE B: DURANTE LA JORNADA ----------
        montarBloque(col, "B", "BLOQUE B - DURANTE LA JORNADA")
        item("B", boton("FLUJO DEL PEDIDO (marcar fases: parking, local, entrega...)") { flujoPedido() })
        val incB = subBloque(bloques["B"]!!, "inc_B", "INCIDENCIAS")
        incB.addView(boton("Registrar CLIENTE AUSENTE") { flujoIncidencia() })
        incB.addView(boton("Marcar LLUVIA / clima adverso") { marcarMeteo() })
        incB.addView(boton("Marcar REASIGNACION de pedido") { marcarReasignacion() })
        incB.addView(boton("FOTO de prueba (trancon / lluvia / cartel / portal)") { fotoFlujoPedidoInicio() })
        item("B", boton("GASTOS (gasolina / mantenimiento) + foto de factura") { menuGastos() })
        item("B", boton("CONSIGNAR A GLOVO (efectivo, recibo BBVA) + foto") { menuConsignaciones() })
        item("B", boton("Reposicionar a PUNTOS ROJOS (km + tiempo)") { reposicionar() })
        bCaptura = boton(textoCaptura()) { alternarCaptura() }
        item("B", bCaptura!!)
        val manB = subBloque(bloques["B"]!!, "man_B", "MANUALES  (si algo falla)")
        manB.addView(boton("Capturar PANTALLA (evidencia con hora real)") { capturarPantalla() })

        // ---------- BLOQUE C: FINALIZAR JORNADA ----------
        montarBloque(col, "C", "BLOQUE C - FINALIZAR JORNADA")
        tvTiempo = TextView(this)
        tvTiempo.setPadding(0, 6, 0, 6)
        tvTiempo.textSize = 12f
        tvTiempo.setTypeface(null, android.graphics.Typeface.BOLD)
        item("C", tvTiempo)
        item("C", boton("CIERRE GUIADO: sesiones + cartera + odometro + casa y cierra") { cierreGuiado() })
        val manC = subBloque(bloques["C"]!!, "man_C", "MANUALES  (si algo falla)")
        manC.addView(boton("KM de la moto al FIN + foto") { pedirKmMomento("FIN") { refrescar() } })
        manC.addView(boton("CARTERA de FIN: pantallazo + saldo") { capturarCarteraGuiado("FIN") { refrescar() } })
        manC.addView(boton("SALIDA hacia CASA (fin de pedidos): km salida + pantallazo Maps") { pasoVueltaCasa() })
        manC.addView(boton("LLEGADA a CASA (fin de jornada): km llegada + pantallazo Maps") { pasoLlegadaCasa() })
        manC.addView(boton("FINALIZAR JORNADA a mano (sin guia)") { finalizarJornada() })

        // ---------- BLOQUE D: CIERRE Y EXPEDIENTE ----------
        montarBloque(col, "D", "BLOQUE D - CIERRE Y EXPEDIENTE")
        item("D", boton("Generar EXPEDIENTE PDF (jornada + flujo + tabla comparativa)") { generarExpediente() })
        item("D", boton("INFORME DEL DÍA (PDF): jornada de hoy + compartir") { generarInformeDelDia() })
        item("D", boton("ELEGIR CARPETA DE INFORMES (PDF)") { elegirCarpeta() })
        item("D", boton("AVISO DE ALMACENAMIENTO (donde se guardan los PDF)") { avisoAlmacenamiento(true) })
        item("D", boton("MIS DATOS: nombre del repartidor y zona") { pedirDatosRepartidor() })
        item("D", boton("Ver EVIDENCIAS registradas") { verEvidencias() })
        item("D", boton("Activar servicio de accesibilidad") { abrirAccesibilidad() })
        item("D", boton("FACTURA DE COBRO (km + mantenimiento, EUR/km)") { generarFactura() })
        item("D", boton("JUSTIFICACIÓN de aviso/sanción (PDF, formato Rufino)") { formularioJustificacion() })

        tvChecklist = TextView(this)
        tvChecklist.setPadding(0, 26, 0, 0)
        tvChecklist.textSize = 12f
        col.addView(tvChecklist)

        tvEstado = TextView(this)
        tvEstado.setPadding(0, 16, 0, 0)
        tvEstado.textSize = 12f
        col.addView(tvEstado)

        // v1.9.9: firma del autor (creditos discretos al pie de la pantalla principal)
        val acerca = TextView(this)
        acerca.text = "MisRepartos — por " + getString(R.string.app_author) + " · v" + versionNombre()
        acerca.textSize = 10f
        acerca.setTextColor(Color.parseColor("#64748B"))
        acerca.gravity = Gravity.CENTER
        acerca.setPadding(0, dp(22), 0, 0)
        col.addView(acerca)

        val firma = TextView(this)
        firma.text = getString(R.string.app_signature)
        firma.textSize = 10f
        firma.setTextColor(Color.parseColor("#94A3B8"))
        firma.gravity = Gravity.CENTER
        firma.setPadding(0, dp(2), 0, dp(4))
        col.addView(firma)

        val sc = ScrollView(this)
        sc.addView(col)
        val extra = (20 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(sc) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top + extra, 0, bars.bottom + extra)
            insets
        }
        sc.isFillViewport = true
        return sc
    }

    // ---------- estilos (cosmetica v1.9) ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Fondo redondeado opcionalmente con borde. */
    private fun fondo(color: String, radio: Int, borde: String? = null): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.parseColor(color))
        g.cornerRadius = dp(radio).toFloat()
        if (borde != null) g.setStroke(dp(1), Color.parseColor(borde))
        return g
    }

    private fun boton(texto: String, accion: () -> Unit): Button {
        val b = Button(this)
        b.text = texto
        b.isAllCaps = false
        b.textSize = 14f
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setTextColor(Color.parseColor("#0F172A"))
        b.background = fondo("#FFFFFF", 12, "#CBD5E1")
        b.setPadding(dp(16), dp(12), dp(16), dp(12))
        b.minHeight = dp(48)
        b.elevation = dp(2).toFloat()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        b.setOnClickListener { accion() }
        return b
    }

    private fun seccion(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 14f
        tv.setTypeface(null, android.graphics.Typeface.BOLD)
        // Barra oscura con letra amarilla: legible tanto en modo claro como en modo oscuro
        tv.setTextColor(Color.parseColor("#334155"))
        tv.setPadding(dp(4), dp(14), dp(4), dp(6))
        return tv
    }

    // ---------- bloques desplegables (no saturar la pantalla) ----------
    private fun bloqueAbierto(id: String): Boolean = prefs().getBoolean("bloque_" + id, id == "B")

    // Colores de bloque: dos tonos INTERCALADOS (A/C tono 1, B/D tono 2)
    private val COLOR_BLOQUE = mapOf(
        "A" to Pair("#1D4ED8", "#EDF2FF"),
        "B" to Pair("#047857", "#E9F7F0"),
        "C" to Pair("#1D4ED8", "#EDF2FF"),
        "D" to Pair("#047857", "#E9F7F0")
    )

    private fun montarBloque(col: LinearLayout, id: String, titulo: String) {
        val (fuerte, suave) = COLOR_BLOQUE[id] ?: Pair("#334155", "#F1F5F9")
        val h = TextView(this)
        h.text = (if (bloqueAbierto(id)) "\u25BC  " else "\u25B6  ") + titulo
        h.textSize = 14f
        h.setTypeface(null, android.graphics.Typeface.BOLD)
        h.setTextColor(Color.WHITE)
        h.background = fondo(fuerte, 12)
        h.setPadding(dp(16), dp(16), dp(16), dp(16))
        h.elevation = dp(2).toFloat()
        h.setOnClickListener {
            val abrir = !bloqueAbierto(id)
            prefs().edit().putBoolean("bloque_" + id, abrir).apply()
            h.text = (if (abrir) "\u25BC  " else "\u25B6  ") + titulo
            bloques[id]?.visibility = if (abrir) android.view.View.VISIBLE else android.view.View.GONE
        }
        val lph = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lph.topMargin = dp(12)
        col.addView(h, lph)
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = fondo(suave, 12)
        c.setPadding(dp(10), dp(10), dp(10), dp(10))
        c.visibility = if (bloqueAbierto(id)) android.view.View.VISIBLE else android.view.View.GONE
        bloques[id] = c
        col.addView(c)
    }

    /** Sub-bloque plegable dentro de un bloque (p. ej. "INCIDENCIAS" o "MANUALES"). */
    private fun subBloque(padre: LinearLayout, clave: String, titulo: String): LinearLayout {
        val h = TextView(this)
        var abierto = prefs().getBoolean(clave, false)
        h.text = (if (abierto) "\u25BC  " else "\u25B6  ") + titulo
        h.textSize = 13f
        h.setTypeface(null, android.graphics.Typeface.BOLD)
        h.setTextColor(Color.parseColor("#475569"))
        h.background = fondo("#E2E8F0", 10)
        h.setPadding(dp(12), dp(12), dp(12), dp(12))
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.visibility = if (abierto) android.view.View.VISIBLE else android.view.View.GONE
        val lph = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lph.topMargin = dp(6)
        padre.addView(h, lph)
        padre.addView(c)
        h.setOnClickListener {
            abierto = !prefs().getBoolean(clave, false)
            prefs().edit().putBoolean(clave, abierto).apply()
            h.text = (if (abierto) "\u25BC  " else "\u25B6  ") + titulo
            c.visibility = if (abierto) android.view.View.VISIBLE else android.view.View.GONE
        }
        return c
    }

    private fun item(id: String, v: android.view.View) { bloques[id]?.addView(v) }

    private fun textoCaptura(): String =
            "Captura automática en cada hito: " +
            (if (online.soymasdigital.jornada.service.GlovoAccessibilityService.capturaAuto) "ON" else "OFF")

    private fun alternarCaptura() {
        val s = online.soymasdigital.jornada.service.GlovoAccessibilityService
        s.capturaAuto = !s.capturaAuto
        bCaptura?.text = textoCaptura()
        toast("Captura automática en cada hito: " + (if (s.capturaAuto) "ACTIVADA" else "desactivada"))
    }

    private fun refrescar() {
        val abierta = repo.jornadaAbierta()
        jid = abierta ?: 0L
        if (abierta == null) {
            tvAlerta.text = "JORNADA: OFF  (sin jornada abierta)"
            tvAlerta.background = fondo("#E2E8F0", 12)
            tvAlerta.setTextColor(Color.parseColor("#0F172A"))
            tvEstado.text = "Pulsa 1. PREPARAR JORNADA para abrir la jornada con su horario, km y cartera."
            tvTiempo.text = ""
            tvChecklist.text = "Checklist: --"
        } else {
            val j = repo.jornada(abierta)
            val desde = if (j != null) horaFmt.format(Date(j.second)) else "?"
            tvAlerta.text = "JORNADA: ON  (abierta desde las $desde)"
            tvAlerta.background = fondo("#DCFCE7", 12)
            tvAlerta.setTextColor(Color.parseColor("#14532D"))
            tvEstado.text = repo.resumen(abierta)
            tvChecklist.text = checklist()
            tvTiempo.text = tiempoTexto()
        }
    }

    private fun checklist(): String {
        if (jid == 0L) return "Checklist: --"
        val (pi, pf) = repo.horario(jid)
        val marcas = listOf(
            "horario" to (pi != null && pf != null),
            "km inicio" to (repo.kmDe(jid, "INICIO") != null),
            "cartera inicio" to (repo.saldoDe(jid, "INICIO") != null),
            "km fin" to (repo.kmDe(jid, "FIN") != null),
            "cartera fin" to (repo.saldoDe(jid, "FIN") != null)
        )
        return "Checklist: " + marcas.joinToString("  |  ") { (n, ok) -> if (ok) "[OK] $n" else "[FALTA] $n" }
    }

    private fun tiempoTexto(): String {
        if (jid == 0L) return ""
        val tc = repo.tiempoComplementario(jid)
        val ue = repo.ultimaEntrega(jid)
        return when {
            ue == null -> "Tiempo complementario: aun no hay pedido entregado registrado."
            tc == null -> "Tiempo complementario: falta el horario programado (pulsa 'Horario programado')."
            tc >= 0 -> "TIEMPO COMPLEMENTARIO: +" + repo.duracion(tc) + " (ultimo pedido " +
                    horaFmt.format(Date(ue)) + " vs fin programado)."
            else -> "Tiempo complementario: " + repo.duracion(tc) + " (ultimo pedido " +
                    horaFmt.format(Date(ue)) + ", antes del fin programado)."
        }
    }

    /** Aviso de continuidad: NUNCA se rota de jornada en silencio. */
    private fun comprobarJornadaPendiente() {
        val id = repo.jornadaAbierta() ?: return
        val j = repo.jornada(id) ?: return
        val hoy = diaFmt.format(Date())
        // 1.1: ademas del cambio de dia, avisa si el turno programado acabo hace mas de 90 min
        val (_, pf) = repo.horario(id)
        val turnoAcabado = pf != null && System.currentTimeMillis() - pf > 90 * 60 * 1000L
        if ((j.first == hoy && !turnoAcabado) || avisoContinuidadMostrado) return
        avisoContinuidadMostrado = true
        val motivo = if (j.first != hoy)
            "La jornada #" + id + " sigue abierta desde el " + j.first + " a las " + horaFmt.format(Date(j.second))
        else
            "La jornada #" + id + " sigue abierta desde las " + horaFmt.format(Date(j.second)) +
                    " y el turno programado acabo a las " + horaFmt.format(Date(pf!!))
        AlertDialog.Builder(this)
            .setTitle("Tienes una jornada abierta")
            .setMessage(motivo + " y tiene " + repo.totalEvidenciasJornada(id) +
                    " evidencias.\n\n¿Sigue en curso esa jornada " +
                    (if (j.first != hoy) "(aunque el reloj haya pasado de medianoche) " else "") +
                    "o la cierras ya?")
            .setPositiveButton("Sigue en curso") { _, _ -> refrescar() }
            .setNeutralButton("Cerrarla ahora") { _, _ -> cerrarGuiada(id) }
            .setNegativeButton("Ver evidencias") { _, _ -> verEvidencias() }
            .show()
    }

    private var avisoContinuidadMostrado = false

    /** Cierra una jornada guiando el cierre: si faltan km/cartera FIN los pide ANTES de cerrar.
     *  Antes se cerraba en silencio y la jornada quedaba sin km FIN ni cartera FIN. */
    private fun cerrarGuiada(id: Long) {
        val faltaKm = repo.kmDe(id, "FIN") == null
        val faltaCar = repo.saldoDe(id, "FIN") == null
        if (!faltaKm && !faltaCar) {
            repo.cerrarJornada(id, "CERRADA_POR_EL_REPARTIDOR")
            try { CapturasKeep.limpiar(this) } catch (e: Exception) { }
            generarInformeAuto(id)
            toast("Jornada #" + id + " cerrada")
            refrescar()
            return
        }
        jid = id
        finalizarJornada()
    }

    // ---------------- pedido ACTIVO (modo entrega doble) ----------------

    private fun prefs() = getSharedPreferences("jornada_prefs", MODE_PRIVATE)
    private fun pedidoActivo(jid2: Long): String? = prefs().getString("activo_" + jid2, null)
    private fun setPedidoActivo(jid2: Long, id: String) { prefs().edit().putString("activo_" + jid2, id).apply() }

    // ---------------- BLOQUE A: preparar jornada ----------------

    private fun prepararJornada() {
        val abierta = repo.jornadaAbierta()
        if (abierta == null) {
            val id = repo.abrirJornada()
            toast("Jornada #" + id + " iniciada")
            refrescar()
            // v1.9.3: al abrir la jornada, reinicia observacion/antirrebote del servicio.
            try { online.soymasdigital.jornada.service.GlovoAccessibilityService.instancia?.reiniciarParaInicio() } catch (e: Exception) { }
        } else if (repo.horario(abierta).first != null && repo.kmDe(abierta, "INICIO") != null &&
            repo.saldoDe(abierta, "INICIO") != null) {
            toast("La jornada #" + abierta + " ya esta preparada")
            refrescar()
            return
        }
        pedirHorario {
            pedirKmMomento("INICIO") {
                pedirCarteraMomento("INICIO") {
                    AlertDialog.Builder(this)
                        .setTitle("Jornada preparada")
                        .setMessage("Jornada, horario, km de inicio y cartera de INICIO registrados.\n\n" +
                                "Durante la jornada solo registra incidencias (cliente ausente / lluvia).\n" +
                                "Al terminar pulsa FINALIZAR JORNADA: te pedira km final y cartera final, y te mostrara " +
                                "el TIEMPO COMPLEMENTARIO (lo trabajado por fuera del horario programado).")
                        .setPositiveButton("OK") { _, _ -> refrescar() }
                        .show()
                }
            }
        }
    }

    private fun parseHm(s: String): Int? {
        val t = s.trim().replace(".", ":").replace(",", ":")
        val p = t.split(":")
        if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun pedirHorario(siguiente: () -> Unit) {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val (pi, pf) = repo.horario(jid)
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        val etI = EditText(this)
        etI.hint = "Hora INICIO programada (HH:MM)"
        etI.setText(pi?.let { horaFmt.format(Date(it)).take(5) } ?: "12:00")
        val etF = EditText(this)
        etF.hint = "Hora FIN programada (HH:MM)"
        etF.setText(pf?.let { horaFmt.format(Date(it)).take(5) } ?: "16:00")
        cont.addView(etI)
        cont.addView(etF)
        AlertDialog.Builder(this)
            .setTitle("Horario programado de la jornada")
            .setMessage("El horario que te asigna Glovo. Sirve para calcular el TIEMPO COMPLEMENTARIO: " +
                    "la diferencia entre la hora final programada y la hora en que entregas el ultimo pedido. " +
                    "Si haces turno partido, introduce el tramo que empiezas ahora.")
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                val mi = parseHm(etI.text.toString())
                val mf = parseHm(etF.text.toString())
                if (mi == null || mf == null) { toast("Formato no valido (usa HH:MM, ej. 12:00)"); return@setPositiveButton }
                val j = repo.jornada(jid)
                if (j != null) {
                    val base = diaFmt.parse(j.first).time
                    val ini = base + mi * 60000L
                    var fin = base + mf * 60000L
                    if (fin <= ini) fin += 24L * 3600L * 1000L
                    repo.guardarHorario(jid, ini, fin)
                    toast("Horario guardado: " + horaFmt.format(Date(ini)).take(5) + " - " + horaFmt.format(Date(fin)).take(5))
                }
                refrescar()
                siguiente()
            }
            .setNegativeButton("Cancelar") { _, _ -> }
            .show()
    }

    // ---------------- BLOQUE B: finalizar jornada ----------------

    private fun finalizarJornada() {
        if (jid == 0L) { toast("No hay jornada abierta"); return }
        // Guia INTELIGENTE: si un dato ya esta registrado, NO se vuelve a pedir (antes se pedia el km FIN
        // otra vez y, al cancelar, se abortaba todo el cierre).
        val faltaKm = repo.kmDe(jid, "FIN") == null
        val faltaCar = repo.saldoDe(jid, "FIN") == null
        if (!faltaKm && !faltaCar) { mostrarCierre(); return }
        if (faltaKm && faltaCar) pedirKmMomento("FIN") { pedirCarteraMomento("FIN") { mostrarCierre() } }
        else if (faltaKm) pedirKmMomento("FIN") { mostrarCierre() }
        else pedirCarteraMomento("FIN") { mostrarCierre() }
    }

    private fun mostrarCierre() {
        if (jid == 0L) { toast("No hay jornada abierta"); return }
        // v1.9.5: aviso de descuadre entre el EFECTIVO de los pedidos y el movimiento de la cartera
        val dc = repo.descuadreCartera(jid)
        if (dc != null && dc.first > 0.0 && Math.abs(dc.third) > 0.5) {
            AlertDialog.Builder(this)
                .setTitle("Cartera: posible descuadre")
                .setMessage("Pedidos en EFECTIVO declarados: " + fEur(dc.first) +
                        "\n" + "Movimiento de la cartera (FIN - INICIO): " + fEur(dc.second) +
                        "\n" + "Descuadre: " + fEur(dc.third) + "." + "\n" + "\n" +
                        "Revisa la cartera: si el saldo FIN no refleja el efectivo, vuelve a capturarlo antes de cerrar.")
                .setPositiveButton("Recapturar cartera FIN") { _, _ -> capturarCarteraGuiado("FIN") { mostrarCierreFinal() } }
                .setNeutralButton("Sigo igual") { _, _ -> mostrarCierreFinal() }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        mostrarCierreFinal()
    }

    private fun mostrarCierreFinal() {
        if (jid == 0L) { toast("No hay jornada abierta"); return }
        val (ok, msg) = repo.puedeTerminar(jid)
        if (!ok) {
            AlertDialog.Builder(this)
                .setTitle("BLOQUEO DURO: faltan pruebas")
                .setMessage(msg + "\n\nSin estos datos no hay prueba para un reclamo. Puedes completarlos ahora.")
                .setPositiveButton("Cartera") { _, _ -> pedirSnapshot() }
                .setNeutralButton("Km moto") { _, _ -> pedirKm() }
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }
        val tc = repo.tiempoComplementario(jid)
        val ue = repo.ultimaEntrega(jid)
        val txt = when {
            ue == null -> "TIEMPO COMPLEMENTARIO: no calculable (no hay ninguna entrega detectada; define tambien el horario)."
            tc == null -> "TIEMPO COMPLEMENTARIO: no calculable (falta el horario programado)."
            tc >= 0 -> "TIEMPO COMPLEMENTARIO: +" + repo.duracion(tc) + " por fuera del horario programado " +
                    "(ultimo pedido a las " + horaFmt.format(Date(ue)) + ")."
            else -> "Tiempo complementario: " + repo.duracion(tc) + " (el ultimo pedido se entrego antes del fin programado)."
        }
        AlertDialog.Builder(this)
            .setTitle("Finalizar jornada #" + jid)
            .setMessage(txt + "\n\nResumen: " + repo.resumenCorto(jid) + "\n\n¿Cierras la jornada?")
            .setPositiveButton("Cerrar jornada") { _, _ ->
                val (ok2, msg2) = repo.terminar(jid)
                toast(msg2)
                if (ok2) {
                    // v2.1.1: mantenimiento de las copias propias al cerrar la jornada.
                    try { CapturasKeep.limpiar(this) } catch (e: Exception) { }
                    refrescar(); generarInformeAuto(jid); ofrecerFactura()
                }
            }
            .setNegativeButton("Todavia no", null)
            .show()
    }

    // ---------------- datos: km y cartera ----------------

    private fun pedirSnapshot() {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val opciones = arrayOf("INICIO (al empezar la jornada)", "FIN (al terminar = movimiento del dia)")
        AlertDialog.Builder(this)
            .setTitle("Snapshot de cartera")
            .setItems(opciones) { _, w ->
                val momento = if (w == 0) "INICIO" else "FIN"
                pedirCarteraMomento(momento) { refrescar() }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun pedirCarteraMomento(momento: String, siguiente: () -> Unit) {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.hint = "Saldo actual de la Cartera (ej. 452,32)"
        AlertDialog.Builder(this)
            .setTitle("Snapshot " + momento)
            .setMessage("Abre la Cartera de la app de reparto, mira 'Saldo actual' y confirmalo aqui. " +
                    "Se guarda con hora real y tu confirmacion (dato critico para reclamar).")
            .setView(et)
            .setPositiveButton("Confirmar") { _, _ ->
                val v = et.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (v == null) { toast("Importe no valido"); return@setPositiveButton }
                repo.snapshot(jid, momento, v, "manual_confirmado")
                toast("Cartera " + momento + " guardada: " + v)
                refrescar()
                siguiente()
            }
            .setNegativeButton("Cancelar") { _, _ -> }
            .show()
    }

    /** Cartera con PANTALLAZO (botones manuales de INICIO/FIN): captura la pestana CARTERA,
     *  lee el 'Saldo actual' y lo guarda; si no lo lee, deja el pantallazo y ofrece teclearlo. */
    private fun capturarCarteraGuiado(momento: String, siguiente: () -> Unit) {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val etiqueta = if (momento == "INICIO") "cartera_inicio" else "cartera_fin"
        AlertDialog.Builder(this)
            .setTitle("Cartera de " + momento + " - pantallazo")
            .setMessage("Abre la pestana CARTERA de la app de Glovo.\n\n" +
                    "Pulsa 'Capturar': guardo el pantallazo con hora real y leo el 'Saldo actual' sin que teclees nada.")
            .setPositiveButton("Capturar") { _, _ ->
                capturarPaso(etiqueta)
                toast("Capturando...")
                android.os.Handler(mainLooper).postDelayed({
                    val s = leerCarteraDePantalla()
                    if (s != null) {
                        repo.snapshot(jid, momento, s, "app_confirmado")
                        toast("Cartera " + momento + " leida: " + s + " EUR")
                        refrescar()
                        siguiente()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("No he podido leer la cartera")
                            .setMessage("El pantallazo ya queda guardado, pero no veo el 'Saldo actual'. " +
                                    "Repite la captura con la Cartera abierta o escribe el saldo a mano.\n" +
                                    "(Si el pantallazo no llego a guardarse, activa la accesibilidad con el boton 9 y repite.)")
                            .setPositiveButton("Escribirlo a mano") { _, _ -> pedirCarteraMomento(momento, siguiente) }
                            .setNeutralButton("Repetir captura") { _, _ -> capturarCarteraGuiado(momento, siguiente) }
                            .setNegativeButton("Saltar") { _, _ -> siguiente() }
                            .show()
                    }
                }, 1800L)
            }
            .setNeutralButton("Escribirlo a mano") { _, _ -> pedirCarteraMomento(momento, siguiente) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirKm() {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val opciones = arrayOf("INICIO (km al salir de casa)", "FIN (km al llegar a casa)", "Corregir el ultimo registro (cambiar INICIO <-> FIN)")
        AlertDialog.Builder(this)
            .setTitle("Kilometros de la moto")
            .setItems(opciones) { _, w ->
                when (w) {
                    0 -> pedirKmMomento("INICIO") { refrescar() }
                    1 -> pedirKmMomento("FIN") { refrescar() }
                    else -> corregirUltimoKm()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun corregirUltimoKm() {
        if (jid == 0L) { toast("Primero abre la jornada"); return }
        val k = repo.ultimoKm(jid)
        if (k == null) { toast("No hay registros de km en esta jornada"); return }
        val nuevo = if (k.second == "INICIO") "FIN" else "INICIO"
        AlertDialog.Builder(this)
            .setTitle("Corregir el ultimo km")
            .setMessage("El ultimo registro es " + k.second + " = " + k.third + " km.\n\n¿Lo cambio a " + nuevo + "?")
            .setPositiveButton("Cambiar a " + nuevo) { _, _ ->
                repo.cambiarMomentoKm(k.first, nuevo)
                toast("Corregido: ahora es " + nuevo)
                refrescar()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun pedirKmMomento(momento: String, siguiente: () -> Unit) {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val previo = repo.kmDe(jid, momento)
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.hint = "Km del cuentakilometros (ej. 24531,4)"
        AlertDialog.Builder(this)
            .setTitle("Km " + momento)
            .setMessage("Mira el cuentakilometros de la moto y teclea los km. Despues podras hacerle una foto " +
                    "(prueba con hora real). Sirve para documentar los km que no se pagan." +
                    (if (previo != null) "\n\nOJO: ya tienes km " + momento + " = " + previo + " km. Si guardas ahora, se REEMPLAZA ese valor." else ""))
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val v = et.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (v == null) { toast("Numero no valido"); return@setPositiveButton }
                // 1.1: valida la coherencia INICIO/FIN antes de guardar
                val otro = if (momento == "INICIO") repo.kmDe(jid, "FIN") else repo.kmDe(jid, "INICIO")
                val problema = when {
                    momento == "FIN" && otro != null && v <= otro ->
                        "El km de FIN (" + fmtKm(v) + ") no puede ser menor o igual que el de INICIO (" + fmtKm(otro) + ")."
                    momento == "INICIO" && otro != null && v == otro ->
                        "El km de INICIO es igual al de FIN (" + fmtKm(otro) + "): parece el mismo numero copiado."
                    else -> null
                }
                if (problema == null) guardarKmValor(momento, v, siguiente)
                else AlertDialog.Builder(this)
                    .setTitle("Ese km no cuadra")
                    .setMessage(problema + "\n\n¿Que haces?")
                    .setPositiveButton("Corregir") { _, _ -> pedirKmMomento(momento, siguiente) }
                    .setNegativeButton("Guardar igual") { _, _ -> guardarKmValor(momento, v, siguiente) }
                    .show()
            }
            .setNegativeButton("Cancelar") { _, _ -> }
            .show()
    }

    /** Guarda el valor de km y ofrece la foto (separado de pedirKmMomento para validar antes). */
    private fun guardarKmValor(momento: String, v: Double, siguiente: () -> Unit) {
        val previo = repo.kmDe(jid, momento)
        val id: Long
        if (previo != null) {
            val ex = repo.idUltimoKm(jid, momento)
            if (ex != null) { repo.actualizarValorKm(ex, v); id = ex } else { id = repo.guardarKm(jid, momento, v, "") }
        } else {
            id = repo.guardarKm(jid, momento, v, "")
        }
        kmPendienteId = id
        refrescar()
        AlertDialog.Builder(this)
            .setTitle("Foto del cuentakilometros")
            .setMessage("¿Quieres hacer la foto del cuentakilometros ahora? (recomendado: es prueba con hora)")
            .setPositiveButton("Hacer foto") { _, _ ->
                pendiente = siguiente
                hacerFotoKm(momento)
            }
            .setNegativeButton("No") { _, _ ->
                toast("Km " + momento + " guardado: " + v)
                ofrecerFotoCamara(id) { siguiente() }
            }
            .show()
    }

    private fun hacerFotoKm(momento: String) {
        val dir = File(getExternalFilesDir(null), "fotos").apply { mkdirs() }
        val f = File(dir, "km_" + momento + "_" + System.currentTimeMillis() + ".jpg")
        val uri: Uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (i.resolveActivity(packageManager) == null) {
            toast("No hay app de camara en el movil")
            return
        }
        startActivityForResult(i, REQ_FOTO)
        fotoPendiente = f.absolutePath
    }

    private var fotoPendiente: String = ""
    private var fotoGastoPendiente: String = ""

    /**
     * Ofrece vincular la ULTIMA foto hecha con la camara del movil (habito real: se fotografia el
     * cuadro con la app de camara). Solo vincula la foto a esta lectura de km; no toca nada mas.
     */
    private fun ofrecerFotoCamara(kmId: Long, luego: () -> Unit) {
        if (kmId <= 0) { luego(); return }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            kmPendienteId = kmId
            pendiente = luego
            requestPermissions(arrayOf(perm), REQ_PERM_FOTOS)
            return
        }
        val fotos = fotosRecientes()
        if (fotos.isEmpty()) { toast("Km guardado (sin foto reciente de camara)"); luego(); return }
        val etiquetas = fotos.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vincular foto del cuentakilometros")
            .setMessage("Elige la foto que acabas de hacer con la camara (se vincula a esta lectura de km).")
            .setItems(etiquetas) { _, w ->
                val ok = copiarFotoApp(fotos[w].second)
                if (ok != null) { repo.ponerFotoKm(kmId, ok); toast("Foto vinculada") } else toast("No se pudo vincular la foto")
                luego()
            }
            .setNegativeButton("Sin foto") { _, _ -> luego() }
            .show()
    }

    /** Ultimas fotos de la camara (ultimas 3 h), de la mas nueva a la mas antigua. */
    private fun fotosRecientes(): List<Pair<String, Uri>> {
        val out = ArrayList<Pair<String, Uri>>()
        try {
            val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
            val desde = (System.currentTimeMillis() / 1000L) - 3L * 3600L
            val cur = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.Images.Media.DATE_ADDED + " > ?", arrayOf(desde.toString()),
                    MediaStore.Images.Media.DATE_ADDED + " DESC")
            cur?.use { c ->
                val ci = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val cn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val ct = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext() && out.size < 6) {
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(ci).toString())
                    val hora = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(c.getLong(ct) * 1000L))
                    val nom = c.getString(cn) ?: "foto"
                    out.add((hora + "  -  " + nom.take(30)) to uri)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Copia la foto elegida a la carpeta privada de la app y devuelve la ruta final. */
    private fun copiarFotoApp(uri: Uri): String? {
        return try {
            val dir = File(getExternalFilesDir(null), "fotos").apply { mkdirs() }
            val dest = File(dir, "km_" + System.currentTimeMillis() + ".jpg")
            contentResolver.openInputStream(uri)?.use { inp -> dest.outputStream().use { o -> inp.copyTo(o) } }
            if (dest.exists() && dest.length() > 0) dest.absolutePath else null
        } catch (e: Exception) { null }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM_FOTOS) {
            val id = kmPendienteId
            val luego = pendiente
            kmPendienteId = -1L
            pendiente = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ofrecerFotoCamara(id, luego ?: {})
            } else {
                toast("Sin permiso de fotos: el km queda sin foto")
                luego?.invoke()
            }
        }
    }

    @Deprecated("Compatibilidad con Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CARPETA) {
            val accion = accionTrasCarpeta
            accionTrasCarpeta = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    val n = SalidaPdf.guardarCarpeta(this, uri)
                    toast("Carpeta de informes: " + n)
                    accion?.invoke()
                } catch (e: Exception) {
                    toast("No se pudo usar esa carpeta: " + e.message + ". Se usara la carpeta privada de la app")
                    accion?.invoke()
                }
            } else {
                // El usuario cerro el selector sin elegir: respaldo privado, avisando.
                toast("Carpeta no elegida: el PDF se guardara en la carpeta privada de la app")
                accion?.invoke()
            }
            return
        }
        if (requestCode == REQ_FOTO_FLUJO) {
            if (fotoFlujoPendiente.isNotEmpty() && File(fotoFlujoPendiente).exists() && File(fotoFlujoPendiente).length() > 0) {
                repo.registrarEvidencia(pedidoFoto, "FOTO", fotoTipo, fotoFlujoPendiente)
                toast("Foto guardada: " + fotoTipo)
            } else toast("No se guardo la foto")
            fotoFlujoPendiente = ""
            val pp = pedidoFoto
            pedidoFoto = ""
            refrescar()
            if (pp.isNotEmpty()) dialogFases(pp)
            return
        }
        if (requestCode == REQ_FOTO_CONSIG) {
            if (fotoConsigPendiente.isNotEmpty() && File(fotoConsigPendiente).exists() && File(fotoConsigPendiente).length() > 0) {
                if (consigPendienteId > 0) repo.ponerFotoConsignacion(consigPendienteId, fotoConsigPendiente)
                toast("Foto del recibo guardada")
            } else toast("No se guardo la foto")
            fotoConsigPendiente = ""; consigPendienteId = -1L
            refrescar()
            return
        }
        if (requestCode == REQ_FOTO_GASTO) {
            if (fotoGastoPendiente.isNotEmpty() && File(fotoGastoPendiente).exists() && File(fotoGastoPendiente).length() > 0) {
                if (gastoPendienteId > 0) repo.ponerFotoGasto(gastoPendienteId, fotoGastoPendiente)
                toast("Foto de la factura guardada")
            } else {
                toast("No se guardo la foto")
            }
            fotoGastoPendiente = ""
            gastoPendienteId = -1L
            refrescar()
            return
        }
        if (requestCode == REQ_FOTO) {
            if (fotoPendiente.isNotEmpty() && File(fotoPendiente).exists() && File(fotoPendiente).length() > 0) {
                if (kmPendienteId > 0) repo.ponerFotoKm(kmPendienteId, fotoPendiente)
                toast("Foto guardada")
            } else {
                toast("No se guardo la foto")
            }
            fotoPendiente = ""
            kmPendienteId = -1L
            refrescar()
            val p = pendiente
            pendiente = null
            p?.invoke()
        }
    }

    // ---------------- bloque C ----------------

    private fun marcarMeteo() {
        if (jid == 0L) { toast("Primero abre la jornada"); return }
        val ops = arrayOf("Lluvia debil", "Lluvia fuerte", "Viento fuerte", "Calor extremo", "Frio extremo", "Niebla", "Otra incidencia")
        AlertDialog.Builder(this)
            .setTitle("Condiciones adversas")
            .setItems(ops) { _, w ->
                val et = EditText(this)
                et.hint = "Nota opcional (ej. 'lluvia en la entrega del pedido 101770...')"
                AlertDialog.Builder(this)
                    .setTitle(ops[w])
                    .setMessage("Se registra con la hora real de tu movil y queda en el expediente para justificar retrasos.")
                    .setView(et)
                    .setPositiveButton("Registrar") { _, _ ->
                        repo.registrarMeteo(jid, ops[w], et.text.toString().trim())
                        toast("Registrado: " + ops[w])
                        refrescar()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun marcarReasignacion() {
        if (jid == 0L) { toast("Primero abre la jornada"); return }
        AlertDialog.Builder(this)
            .setTitle("Pedido reasignado")
            .setMessage("La app detecta sola el aviso 'Se ha marcado el pedido como reenviado'. Usa este boton si quieres " +
                    "dejar constancia manual de una reasignacion (se guarda con la hora real).")
            .setPositiveButton("Registrar ahora") { _, _ ->
                repo.registrarEvidencia("manual", "PEDIDO_REASIGNADO", "marcada por el repartidor", "")
                toast("Reasignacion registrada")
                refrescar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- gasolina, zona de trabajo, reposicionamiento ----------------

    // ---------------- consignaciones a GLOVO ----------------

    private fun menuConsignaciones() {
        if (jid == 0L) { toast("Primero prepara la jornada"); return }
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        val etImp = EditText(this)
        etImp.hint = "Importe (EUR)"
        etImp.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val etOfi = EditText(this)
        etOfi.hint = "Oficina (ej. 0520)"
        val etClave = EditText(this)
        etClave.hint = "Clave de operacion (ej. 9708131...)"
        val etIdIn = EditText(this)
        etIdIn.hint = "Id ingreso efectivo (opcional)"
        val etNota = EditText(this)
        etNota.hint = "Nota opcional"
        cont.addView(etImp); cont.addView(etOfi); cont.addView(etClave); cont.addView(etIdIn); cont.addView(etNota)
        val totalPrev = repo.totalConsignaciones(jid)
        AlertDialog.Builder(this)
            .setTitle("Consignar efectivo a GLOVO")
            .setMessage("Recibo BBVA 'Pago de recibos' (descripcion GLOVO). Ya consignado en la jornada: " + totalPrev +
                    " EUR. Se guarda con la hora real; despues la foto del recibo (el OCR autorrellena en la Fase 4).")
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                val imp = etImp.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (imp == null) { toast("Importe no valido"); return@setPositiveButton }
                val id = repo.registrarConsignacion(jid, imp, 0.0, etOfi.text.toString().trim(), "",
                        etClave.text.toString().trim(), etIdIn.text.toString().trim(), etNota.text.toString().trim())
                consigPendienteId = id
                toast("Consignacion guardada: " + imp + " EUR (total: " + repo.totalConsignaciones(jid) + " EUR)")
                refrescar()
                AlertDialog.Builder(this)
                    .setTitle("Foto del recibo")
                    .setMessage("¿Haces ahora la foto del recibo BBVA?")
                    .setPositiveButton("Hacer foto") { _, _ -> hacerFotoConsig() }
                    .setNegativeButton("No", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun hacerFotoConsig() {
        val dir = File(getExternalFilesDir(null), "fotos").apply { mkdirs() }
        val f = File(dir, "consignacion_" + System.currentTimeMillis() + ".jpg")
        val uri: Uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (i.resolveActivity(packageManager) == null) { toast("No hay app de camara"); return }
        fotoConsigPendiente = f.absolutePath
        startActivityForResult(i, REQ_FOTO_CONSIG)
    }

    private fun menuGastos() {
        if (jid == 0L) { toast("Primero prepara la jornada"); return }
        val ops = arrayOf("Gasolina (repostaje)", "Mantenimiento / reparacion", "Otro gasto")
        AlertDialog.Builder(this)
            .setTitle("Registrar gasto")
            .setItems(ops) { _, w ->
                when (w) {
                    0 -> repostarGasolina()
                    1 -> gastoConDescripcion("MANTENIMIENTO", "Gasto de mantenimiento", "Descripcion (ej. cambio de aceite, neumatico, revision...)")
                    else -> gastoConDescripcion("OTRO", "Otro gasto", "Descripcion del gasto")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun gastoConDescripcion(tipo: String, titulo: String, hint: String) {
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        val etImp = EditText(this)
        etImp.hint = "Importe (EUR)"
        etImp.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val etDesc = EditText(this)
        etDesc.hint = hint
        val etKm2 = EditText(this)
        etKm2.hint = "Km del cuentakilometros (opcional)"
        etKm2.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        cont.addView(etImp); cont.addView(etDesc); cont.addView(etKm2)
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage("Se guarda con la hora real y despues podras hacer la foto de la factura.")
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                // v1.8: no bloquear el guardado si falta el importe (se avisa y se guarda igual).
                val imp = etImp.text.toString().replace(',', '.').trim().toDoubleOrNull()
                val kmv = etKm2.text.toString().replace(',', '.').trim().toDoubleOrNull() ?: 0.0
                val id = repo.registrarGasto(jid, tipo, imp ?: 0.0, 0.0, kmv, "", etDesc.text.toString().trim())
                gastoPendienteId = id
                if (imp == null) toast("Gasto guardado SIN importe (podras completarlo despues)")
                refrescar()
                AlertDialog.Builder(this)
                    .setTitle("Foto de la factura")
                    .setMessage("¿Haces ahora la foto de la factura?")
                    .setPositiveButton("Hacer foto") { _, _ -> hacerFotoGasto() }
                    .setNegativeButton("No") { _, _ -> toast("Gasto guardado: " + imp + " EUR") }
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun repostarGasolina() {
        if (jid == 0L) { toast("Primero prepara la jornada"); return }
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        val etImp = EditText(this)
        etImp.hint = "Importe total de la factura (EUR)"
        etImp.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val etLit = EditText(this)
        etLit.hint = "Litros (opcional)"
        etLit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val etKm2 = EditText(this)
        etKm2.hint = "Km del cuentakilometros al repostar (opcional)"
        etKm2.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        cont.addView(etImp); cont.addView(etLit); cont.addView(etKm2)
        AlertDialog.Builder(this)
            .setTitle("Repostar gasolina")
            .setMessage("Se guarda con la hora real. Despues podras hacer la foto de la factura (prueba del gasto).")
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                // v1.8: NO bloquear el guardado si faltan km/litros/importe (el 01/10 no dejo
                // registrar el repostaje de la tarde). Se avisa y se guarda igual.
                val imp = etImp.text.toString().replace(',', '.').trim().toDoubleOrNull()
                val lit = etLit.text.toString().replace(',', '.').trim().toDoubleOrNull() ?: 0.0
                val kmv = etKm2.text.toString().replace(',', '.').trim().toDoubleOrNull() ?: 0.0
                val faltan = ArrayList<String>()
                if (imp == null) faltan.add("importe")
                if (lit <= 0.0) faltan.add("litros")
                if (kmv <= 0.0) faltan.add("km")
                val id = repo.registrarGasto(jid, "GASOLINA", imp ?: 0.0, lit, kmv, "", "")
                gastoPendienteId = id
                if (faltan.isNotEmpty()) toast("Repostaje guardado sin " + faltan.joinToString(", ") + " (podras completarlo despues)")
                refrescar()
                AlertDialog.Builder(this)
                    .setTitle("Foto de la factura")
                    .setMessage("¿Haces ahora la foto de la factura de la gasolina?")
                    .setPositiveButton("Hacer foto") { _, _ -> hacerFotoGasto() }
                    .setNegativeButton("No") { _, _ -> toast("Gasolina guardada: " + imp + " EUR") }
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun hacerFotoGasto() {
        val dir = File(getExternalFilesDir(null), "fotos").apply { mkdirs() }
        val f = File(dir, "gasolina_" + System.currentTimeMillis() + ".jpg")
        val uri: Uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (i.resolveActivity(packageManager) == null) { toast("No hay app de camara"); return }
        fotoGastoPendiente = f.absolutePath
        startActivityForResult(i, REQ_FOTO_GASTO)
    }

    private fun llegadaZona() {
        if (jid == 0L) { toast("Primero prepara la jornada"); return }
        val j = repo.jornada(jid)
        val previa = repo.eventosJornada(jid).firstOrNull { it.first == "INICIO_ZONA" }
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        val etKm2 = EditText(this)
        etKm2.hint = "Km del cuentakilometros al llegar a la zona"
        etKm2.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val etT = EditText(this)
        etT.hint = "Tiempo (hh:mm) desde casa hasta la zona"
        if (j != null) {
            val min = ((System.currentTimeMillis() - j.second) / 60000L).coerceAtLeast(0)
            etT.setText(String.format(Locale.US, "%02d:%02d", min / 60, min % 60))
        }
        cont.addView(etKm2); cont.addView(etT)
        AlertDialog.Builder(this)
            .setTitle("Llegada a la zona de trabajo")
            .setMessage("Documenta el desplazamiento de casa a la zona de reparto (tiempo y km que no se pagan). " +
                    "La app ya registra sola el momento en que te pones EN LINEA en la plataforma." +
                    (if (previa != null) "\n\nATENCION: ya hay una llegada a la zona registrada a las " +
                            horaFmt.format(Date(previa.second)) + ". Si guardas otra, quedaran dos." else ""))
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                val nota = "km=" + etKm2.text.toString().trim() + " tiempo=" + etT.text.toString().trim()
                if (!repo.registrarEventoJornada(jid, "INICIO_ZONA", nota)) { toast("INICIO_ZONA ya registrado hace menos de 30 s"); return@setPositiveButton }
                toast("Zona de trabajo registrada")
                refrescar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun reposicionar() {
        if (jid == 0L) { toast("Primero prepara la jornada"); return }
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        val etKm2 = EditText(this)
        etKm2.hint = "Km recorridos hasta la zona de mas pedidos"
        etKm2.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val etMin = EditText(this)
        etMin.hint = "Minutos empleados"
        etMin.inputType = InputType.TYPE_CLASS_NUMBER
        val etN = EditText(this)
        etN.hint = "Nota (ej. 'me muevo a los puntos rojos del centro')"
        cont.addView(etKm2); cont.addView(etMin); cont.addView(etN)
        AlertDialog.Builder(this)
            .setTitle("Reposicionamiento a puntos rojos")
            .setMessage("Desplazamiento sin pedido para buscar mas trabajo: tiempo y km que no se pagan. Se puede repetir varias veces.")
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                val nota = "km=" + etKm2.text.toString().trim() + " min=" + etMin.text.toString().trim() +
                        (if (etN.text.toString().trim().isNotEmpty()) " " + etN.text.toString().trim() else "")
                if (!repo.registrarEventoJornada(jid, "REPOSICIONAMIENTO", nota)) { toast("Reposicionamiento identico hace menos de 30 s: no se repite"); return@setPositiveButton }
                toast("Reposicionamiento guardado")
                refrescar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- flujo por pedido (fases con hora real) ----------------

    private val FASES = listOf(
        "LOTE" to "- Lote: nº de pedidos que llevas a la vez (1/2/3)",
        "RECIBO_PEDIDO" to "1. Recibo del pedido",
        "RUTA_LOCAL" to "2. Ruta al local (km + min ESTIMADOS de Maps)",
        "LLEGADA_PARKING" to "3. Llegada al parking (km + min REALIZADOS + metros a pie)",
        "LLEGADA_LOCAL" to "4. Llegada al local",
        "RETRASO_LOCAL" to "- Espera declarada por el local (estara listo en N min)",
        "RECOGIDA" to "5. Recibo del pedido en el local",
        "LLEGADA_MOTO" to "6. Llegada a la moto (parking)",
        "SALIDA_CLIENTE" to "7. Salida hacia el cliente (km + min ESTIMADOS de Maps)",
        "LLEGADA_CLIENTE" to "8. Llegada al cliente (km + min REALIZADOS)",
        "ENTREGA" to "9. Entrega del pedido",
        "INCIDENCIA_ENTREGA" to "- Incidencia en la entrega (portal, timbre, direccion...)",
        "LLEGADA_MOTO_FINAL" to "10. Vuelta a la moto (cierre del pedido)",
        "ESPERA_SIGUIENTE" to "11. Tiempo esperado para el siguiente pedido",
        "CLIMA" to "- Clima adverso en este pedido",
        "LLEGADA_CASA" to "- Llegada a casa (fin de la jornada)"
    )

    private val MOTIVOS_INCIDENCIA = arrayOf(
        "Portal cerrado / sin acceso",
        "Timbre no funciona",
        "Cliente no contesta",
        "Direccion erronea / no localizada",
        "Parking cerrado o sin sitio",
        "Espera larga en el local",
        "Otra incidencia"
    )

    private val TIPOS_CLIMA = arrayOf(
        "Lluvia debil", "Lluvia fuerte", "Viento fuerte", "Calor extremo", "Frio extremo", "Niebla", "Otra incidencia"
    )

    private val TIPOS_ENTREGA = arrayOf(
        "Casa - cliente espera en la calle",
        "Casa - hay que esperar a que abran",
        "Piso con ascensor",
        "Piso sin ascensor (gradas)",
        "Residencia/urbanizacion a pie (hasta 200 m)",
        "Zona alta/historica sin acceso para moto (ej. Albaicin)",
        "Zona peligrosa"
    )

    private fun flujoPedido() {
        if (jid == 0L) { toast("Primero prepara la jornada"); return }
        val opciones = repo.pedidosSinCerrar(jid)
        if (opciones.isEmpty()) { toast("No hay pedidos con flujo abierto en esta jornada"); return }
        val etiquetas = opciones.map { p ->
            "Pedido " + p.id + (if (p.num.isNotEmpty()) " #" + p.num else "") +
                    "  (" + repo.estadoPedido(p) + ")"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Flujo del pedido - elige el pedido")
            .setMessage("La HORA de cada hito es la de PULSAR. Si pulsas tarde, usa 'Corregir la HORA'.")
            .setItems(etiquetas) { _, w -> setPedidoActivo(jid, opciones[w].id); dialogFases(opciones[w].id) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Selector rapido de pedido para el modo entrega doble. */
    private fun elegirPedidoActivo(pedidoActual: String) {
        val opciones = repo.pedidosSinCerrar(jid)
        if (opciones.size <= 1) { dialogFases(pedidoActual); return }
        val etiquetas = opciones.map { p ->
            (if (p.id == pedidoActual) "(ACTIVO)  " else "          ") + "Pedido " + p.id +
                    (if (p.num.isNotEmpty()) " #" + p.num else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("¿Que pedido atiendes ahora?")
            .setItems(etiquetas) { _, w -> setPedidoActivo(jid, opciones[w].id); dialogFases(opciones[w].id) }
            .setNegativeButton("Volver", null)
            .show()
    }

    /** Orden real del flujo (las fases numeradas). Sirve para guiar y para anotar los saltos. */
    private val ORDEN_FLUJO = listOf(
        "RECIBO_PEDIDO", "RUTA_LOCAL", "LLEGADA_PARKING", "LLEGADA_LOCAL", "RECOGIDA",
        "LLEGADA_MOTO", "SALIDA_CLIENTE", "LLEGADA_CLIENTE", "ENTREGA", "LLEGADA_MOTO_FINAL",
        "ESPERA_SIGUIENTE"
    )

    private val EXTRAS_FLUJO = listOf("LOTE", "RETRASO_LOCAL", "INCIDENCIA_ENTREGA", "CLIMA", "LLEGADA_CASA")

    private fun etiquetaFase(cod: String): String = FASES.firstOrNull { it.first == cod }?.second ?: cod

    /** Fases previas del flujo que NO se han registrado (para dejar constancia del salto). */
    private fun notaSalto(pedido: String, codigo: String): String {
        val i = ORDEN_FLUJO.indexOf(codigo)
        if (i <= 0) return ""
        val ya = repo.fasesEfectivas(jid, pedido).map { it.first }.toSet()
        val faltan = ORDEN_FLUJO.take(i).filter { it !in ya }
        return if (faltan.isEmpty()) "" else "sin registrar: " + faltan.joinToString(", ")
    }

    /** Registra una fase anadiendo, si procede, la nota automatica de salto de fases.
     *  v1.9.2: fuerza el ORDEN del flujo (bloquea ruta/parking/local/recogida si el pedido o su local
     *  ya tienen RECOGIDA) y exige confirmar la ENTREGA sin LLEGADA_CLIENTE. */
    private fun marcar(pedido: String, codigo: String, nota: String, forzar: Boolean = false) {
        if (!forzar) {
            if (codigo == "ENTREGA") {
                val aviso = repo.avisoEntregaSinLlegada(jid, pedido)
                if (aviso != null) {
                    AlertDialog.Builder(this)
                        .setTitle("ENTREGA sin LLEGADA_CLIENTE")
                        .setMessage(aviso + "\n\n¿Seguro que quieres registrarla igual? Quedara constancia del salto.")
                        .setPositiveButton("Registrar igual") { _, _ -> marcar(pedido, codigo, nota, true) }
                        .setNegativeButton("Cancelar") { _, _ -> dialogFases(pedido) }
                        .show()
                    return
                }
            }
            val bloqueo = repo.motivoBloqueo(jid, pedido, codigo)
            if (bloqueo != null) {
                // v1.9.5: AVISA pero ya NO bloquea (Jose: "no bloquees el flujo 6")
                AlertDialog.Builder(this)
                    .setTitle("Fase fuera de orden")
                    .setMessage(bloqueo + "\n\n¿La registro igual? Quedara constancia del orden real.")
                    .setPositiveButton("Registrar igual") { _, _ -> marcar(pedido, codigo, nota, true) }
                    .setNegativeButton("Cancelar") { _, _ -> dialogFases(pedido) }
                    .show()
                return
            }
        }
        val s = notaSalto(pedido, codigo)
        val n = when {
            nota.isEmpty() && s.isEmpty() -> ""
            nota.isEmpty() -> s
            s.isEmpty() -> nota
            else -> nota + " | " + s
        }
        // v1.9.2: ademas de los 30 s, se rechaza el mismo paso con el mismo km dentro de 15 min
        if (!repo.registrarFase(jid, pedido, codigo, n)) {
            toast("Paso duplicado: " + etiquetaFase(codigo) + " (mismo km en 15 min o repetido en 30 s). No se repite.")
            return
        }
        try { online.soymasdigital.jornada.service.GlovoAccessibilityService.instancia?.capturarHito("fase " + codigo) } catch (e: Exception) { }
    }

    private fun dialogFases(pedido: String) {
        if (jid == 0L) return
        setPedidoActivo(jid, pedido)
        val f = repo.fasesEfectivas(jid, pedido)
        val marcadas = f.associateBy({ it.first }, { it.second })
        val p = repo.pedidos(jid).firstOrNull { it.id == pedido }
        val est = if (p != null) repo.estadoPedido(p) else ""
        val siguiente = ORDEN_FLUJO.firstOrNull { !marcadas.containsKey(it) && repo.motivoBloqueo(jid, pedido, it) == null }
        val varios = repo.pedidosSinCerrar(jid).size > 1
        val etiquetas = ArrayList<String>()
        val codigos = ArrayList<String>()
        fun fila(cod: String, prefijo: String) {
            val t = marcadas[cod]
            val marca = if (t != null) "  [OK " + horaFmt.format(Date(t)) + "]" else ""
            val bloq = if (t == null && repo.motivoBloqueo(jid, pedido, cod) != null) "  [FUERA DE ORDEN]" else ""
            etiquetas.add(prefijo + etiquetaFase(cod) + marca + bloq)
            codigos.add(cod)
        }
        if (varios) {
            etiquetas.add(">> PEDIDO ACTIVO: " + pedido + "   (CAMBIAR de pedido)")
            codigos.add("__CAMBIAR__")
        }
        if (siguiente != null) fila(siguiente, ">> SIGUIENTE:  ")
        for (cod in ORDEN_FLUJO) if (cod != siguiente) fila(cod, "      ")
        etiquetas.add("   -  Mas opciones: lote / espera local / incidencia / clima")
        codigos.add("__EXTRAS__")
        etiquetas.add("   -  FOTO de prueba (trancon / lluvia / cartel del local)")
        codigos.add("__FOTO__")
        // v1.8 - el lote se agrupa POR LOCAL: un ciclo de recogida por cada local distinto.
        val pActual = repo.pedidos(jid).firstOrNull { it.id == pedido }
        val loteLocal = mismoLocalSinRecogida(pedido)
        if (loteLocal.size > 1) {
            etiquetas.add(">> RECOGIDA DEL LOTE: marcar en los " + loteLocal.size + " pedidos de este local (sin repetir viaje)")
            codigos.add("__LOTE_RECOGIDA__")
        }
        val otrosLocales = repo.pedidosSinCerrar(jid).filter {
            it.id != pedido && it.local.isNotEmpty() && it.local != (pActual?.local ?: "")
        }
        if (otrosLocales.isNotEmpty()) {
            etiquetas.add(">> Abrir CICLO DE RECOGIDA en OTRO local (" + otrosLocales.size + " pedido(s))")
            codigos.add("__OTRO_LOCAL__")
        }
        etiquetas.add("Ver los tiempos ya calculados")
        codigos.add("__DERIV__")
        etiquetas.add("Corregir la HORA de un hito de este pedido (la app guarda la de pulsar)")
        codigos.add("__HORA__")
        AlertDialog.Builder(this)
            .setTitle("Flujo del pedido " + pedido + "   " + est)
            .setItems(etiquetas.toTypedArray()) { _, w ->
                val c = codigos[w]
                if (c == "__CAMBIAR__") {
                    elegirPedidoActivo(pedido)
                } else if (c == "__EXTRAS__") {
                    dialogExtras(pedido)
                } else if (c == "__FOTO__") {
                    fotoFlujo(pedido)
                } else if (c == "__LOTE_RECOGIDA__") {
                    val ids = mismoLocalSinRecogida(pedido)
                    var marcados = 0
                    for (o in ids) if (repo.registrarFase(jid, o, "RECOGIDA", "auto lote (mismo local)")) marcados++
                    toast("RECOGIDA del lote: marcada en " + marcados + " pedido(s); no se repite el viaje")
                    dialogFases(pedido)
                } else if (c == "__OTRO_LOCAL__") {
                    val otros = repo.pedidosSinCerrar(jid).filter {
                        it.id != pedido && it.local.isNotEmpty() && it.local != (pActual?.local ?: "")
                    }
                    if (otros.isEmpty()) { toast("No hay pedidos en otros locales"); dialogFases(pedido) }
                    else AlertDialog.Builder(this)
                        .setTitle("Abrir ciclo de recogida en otro local")
                        .setItems(otros.map { "Pedido " + it.id + (if (it.num.isNotEmpty()) " #" + it.num else "") + "  (" + it.local + ")" }.toTypedArray()) { _, w ->
                            setPedidoActivo(jid, otros[w].id)
                            dialogFases(otros[w].id)
                        }
                        .setNegativeButton("Volver") { _, _ -> dialogFases(pedido) }
                        .show()
                } else if (c == "__DERIV__") {
                    mostrarDerivados(pedido)
                } else if (c == "__HORA__") {
                    corregirHoraFlujo(pedido)
                } else {
                    val ya = repo.fases(jid, pedido).firstOrNull { it.first == c }
                    if (ya != null) {
                        AlertDialog.Builder(this)
                            .setTitle("Esa fase ya esta marcada")
                            .setMessage("La marcaste a las " + horaFmt.format(Date(ya.second)) +
                                    ". ¿La actualizo con la hora de ahora?")
                            .setPositiveButton("Actualizar") { _, _ -> marcarFase(pedido, c) }
                            .setNegativeButton("Dejar la primera") { _, _ -> dialogFases(pedido) }
                            .show()
                    } else {
                        marcarFase(pedido, c)
                    }
                }
            }
            .setPositiveButton("Cerrar") { _, _ -> refrescar() }
            .show()
    }

    /** v1.9.5: la app guarda la HORA DE PULSAR; aqui se corrige la hora REAL de un hito ya marcado. */
    private fun corregirHoraFlujo(pedido: String) {
        val f = repo.fases(jid, pedido)
        if (f.isEmpty()) { toast("Este pedido no tiene hitos que corregir"); dialogFases(pedido); return }
        val etiquetas = f.map { horaFmt.format(Date(it.second)) + "  " + etiquetaFase(it.first) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("¿Que hito corriges? (hora real)")
            .setItems(etiquetas) { _, w -> pedirHoraHito(pedido, f[w].first, f[w].second) }
            .setNegativeButton("Volver") { _, _ -> dialogFases(pedido) }
            .show()
    }

    private fun pedirHoraHito(pedido: String, paso: String, tsActual: Long) {
        val et = EditText(this)
        et.hint = "Hora real (HH:MM)"
        AlertDialog.Builder(this)
            .setTitle("Hora real de " + etiquetaFase(paso))
            .setMessage("La app guardo la hora de PULSAR. Escribe la hora REAL del hito (HH:MM).")
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val hm = parseHm(et.text.toString())
                if (hm == null) { toast("Formato no valido (HH:MM)"); return@setPositiveButton }
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = tsActual
                cal.set(java.util.Calendar.HOUR_OF_DAY, hm / 60)
                cal.set(java.util.Calendar.MINUTE, hm % 60)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                if (repo.corregirHoraFase(jid, pedido, paso, cal.timeInMillis))
                    toast("Hora corregida: " + etiquetaFase(paso))
                else toast("No se pudo corregir la hora")
                dialogFases(pedido)
            }
            .setNegativeButton("Cancelar") { _, _ -> dialogFases(pedido) }
            .show()
    }

    private fun dialogExtras(pedido: String) {
        AlertDialog.Builder(this)
            .setTitle("Mas opciones del pedido " + pedido)
            .setItems(EXTRAS_FLUJO.map { etiquetaFase(it) }.toTypedArray()) { _, w ->
                marcarFase(pedido, EXTRAS_FLUJO[w])
            }
            .setNegativeButton("Volver al flujo") { _, _ -> dialogFases(pedido) }
            .show()
    }

    /** v1.8: pedidos abiertos del MISMO local pendientes de RECOGIDA (incluido el propio). */
    private fun mismoLocalSinRecogida(pedido: String): List<String> {
        val p = repo.pedidos(jid).firstOrNull { it.id == pedido } ?: return emptyList()
        val local = p.local ?: return emptyList()
        if (local.isEmpty()) return emptyList()
        return repo.pedidosSinCerrar(jid)
            .filter { it.local == local && repo.fases(jid, it.id).none { f -> f.first == "RECOGIDA" } }
            .map { it.id }
    }

    /** Foto de prueba desde el menu B (elige el pedido si hay varios). */
    private fun fotoFlujoPedidoInicio() {
        if (jid == 0L) { toast("Primero prepara la jornada"); return }
        val ops = repo.pedidosSinCerrar(jid)
        if (ops.isEmpty()) { toast("No hay pedidos abiertos"); return }
        if (ops.size == 1) { fotoFlujo(ops[0].id); return }
        AlertDialog.Builder(this)
            .setTitle("Foto de prueba - elige el pedido")
            .setItems(ops.map { "Pedido " + it.id + (if (it.num.isNotEmpty()) " #" + it.num else "") }.toTypedArray()) { _, w ->
                fotoFlujo(ops[w].id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Foto de prueba: elige el motivo, abre la camara y la guarda como evidencia del pedido. */
    private fun fotoFlujo(pedido: String) {
        if (jid == 0L) { toast("Primero abre la jornada"); return }
        AlertDialog.Builder(this)
            .setTitle("Foto de prueba")
            .setItems(TIPOS_FOTO) { _, w ->
                pedidoFoto = pedido
                fotoTipo = TIPOS_FOTO[w]
                val dir = File(getExternalFilesDir(null), "fotos").apply { mkdirs() }
                val f = File(dir, "prueba_" + pedido + "_" + System.currentTimeMillis() + ".jpg")
                val uri: Uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
                val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (i.resolveActivity(packageManager) == null) { toast("No hay app de camara"); return@setItems }
                fotoFlujoPendiente = f.absolutePath
                startActivityForResult(i, REQ_FOTO_FLUJO)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDerivados(pedido: String) {
        val deriv = repo.flujoDerivados(jid, pedido)
        AlertDialog.Builder(this)
            .setTitle("Tiempos del pedido " + pedido)
            .setMessage(if (deriv.isEmpty()) "Aun no hay fases suficientes para calcular tiempos."
                        else deriv.joinToString("\n") { "- " + it })
            .setPositiveButton("Volver al flujo") { _, _ -> dialogFases(pedido) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** v1.9.5: el orden de las fases AVISA pero NO bloquea (Jose: "no bloquees el flujo 6"). */
    private fun marcarFase(pedido: String, codigo: String) {
        val aviso = repo.motivoBloqueo(jid, pedido, codigo)
        if (aviso != null) {
            AlertDialog.Builder(this)
                .setTitle("Fase fuera de orden")
                .setMessage(aviso + "\n\n¿La registro igual? Quedara constancia del orden real.")
                .setPositiveButton("Registrar igual") { _, _ -> marcarFaseEjecutar(pedido, codigo) }
                .setNegativeButton("Cancelar") { _, _ -> dialogFases(pedido) }
                .show()
            return
        }
        marcarFaseEjecutar(pedido, codigo)
    }

    private fun marcarFaseEjecutar(pedido: String, codigo: String) {
        when (codigo) {
            "LOTE" -> {
                val ops = arrayOf("1 pedido", "2 pedidos", "3 o mas")
                AlertDialog.Builder(this)
                    .setTitle("Pedidos que llevas a la vez")
                    .setMessage("Si el pedido va en lote (doble o triple), los tiempos no son comparables con un pedido suelto.")
                    .setItems(ops) { _, w ->
                        marcar(pedido, codigo, ops[w])
                        toast("Lote guardado")
                        dialogFases(pedido)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            "RETRASO_LOCAL" -> {
                val et = EditText(this)
                et.inputType = InputType.TYPE_CLASS_NUMBER
                et.hint = "Minutos que indicaba la app del local"
                AlertDialog.Builder(this)
                    .setTitle("Espera declarada por el local")
                    .setMessage("Los minutos que anunciaba la app/pantalla del local (\"estara listo en N min\"). Es prueba directa de una espera ajena a ti.")
                    .setView(et)
                    .setPositiveButton("Guardar") { _, _ ->
                        marcar(pedido, codigo, "min=" + et.text.toString().trim())
                        toast("Espera guardada")
                        dialogFases(pedido)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            "INCIDENCIA_ENTREGA" -> {
                AlertDialog.Builder(this)
                    .setTitle("Incidencia en la entrega")
                    .setItems(MOTIVOS_INCIDENCIA) { _, w ->
                        val et = EditText(this)
                        et.hint = "Nota opcional"
                        AlertDialog.Builder(this)
                            .setTitle(MOTIVOS_INCIDENCIA[w])
                            .setView(et)
                            .setPositiveButton("Guardar") { _, _ ->
                                val n = MOTIVOS_INCIDENCIA[w] + (if (et.text.toString().trim().isNotEmpty()) " | " + et.text.toString().trim() else "")
                                marcar(pedido, codigo, n)
                                toast("Incidencia guardada")
                                dialogFases(pedido)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            "CLIMA" -> {
                AlertDialog.Builder(this)
                    .setTitle("Clima adverso en este pedido")
                    .setItems(TIPOS_CLIMA) { _, w ->
                        marcar(pedido, codigo, TIPOS_CLIMA[w])
                        repo.registrarMeteo(jid, TIPOS_CLIMA[w], "pedido " + pedido)
                        toast("Clima registrado en el pedido")
                        dialogFases(pedido)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            "LLEGADA_CASA" -> {
                val cont = LinearLayout(this)
                cont.orientation = LinearLayout.VERTICAL
                val etKm = EditText(this)
                etKm.hint = "Km desde el ultimo pedido hasta casa"
                etKm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                val etT = EditText(this)
                etT.hint = "Tiempo (hh:mm) desde el ultimo pedido hasta casa"
                val ult = repo.ultimoHito(jid)
                if (ult != null) {
                    val min = ((System.currentTimeMillis() - ult) / 60000L).coerceAtLeast(0)
                    etT.setText(String.format(Locale.US, "%02d:%02d", min / 60, min % 60))
                }
                cont.addView(etKm); cont.addView(etT)
                AlertDialog.Builder(this)
                    .setTitle("Llegada a casa (fin de la jornada)")
                    .setMessage("Registra los km y el tiempo (hh:mm) de la vuelta desde el ultimo pedido. " +
                            "Ese desplazamiento no se paga y queda documentado. El tiempo viene ya calculado desde el ultimo hito; ajustalo si quieres.")
                    .setView(cont)
                    .setPositiveButton("Guardar") { _, _ ->
                        val nota = "km=" + etKm.text.toString().trim() + " tiempo=" + etT.text.toString().trim()
                        marcar(pedido, codigo, nota)
                        toast("Llegada a casa registrada")
                        refrescar()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            "RUTA_LOCAL" -> {
                val e = repo.estimacionMaps(jid, pedido, true)
                pedirKmMin(pedido, codigo, "Ruta al local (estimacion de Maps)",
                    "Km que marca Maps hasta el local", "Minutos estimados por Maps", "estimado Maps", false,
                    e?.third?.let { fmtKm(it) } ?: "", e?.second?.toString() ?: "")
            }
            "SALIDA_CLIENTE" -> {
                val e = repo.estimacionMaps(jid, pedido, false)
                // v1.9.5: la continuidad del odometro NO se guarda como km del tramo (lo dejaba a 0).
                // Solo se rellena con el km de Maps; si no hay Maps el campo va vacio (sin dato) y la
                // ultima lectura se muestra solo como referencia (odom=), nunca como km= enganoso.
                val kmCont = repo.ultimoCuentakm(jid)?.let { fmtKm(it) } ?: ""
                pedirKmMin(pedido, codigo, "Ruta hasta el cliente (estimacion de Maps)",
                    "Km que marca Maps hasta el cliente", "Minutos estimados por Maps", "estimado Maps", false,
                    e?.third?.let { fmtKm(it) } ?: "", e?.second?.toString() ?: "", kmCont)
            }
            "LLEGADA_PARKING" -> pedirKmMin(pedido, codigo, "Llegada al parking (datos REALES)",
                    "Km realmente recorridos", "Minutos realmente empleados", "realizado", true)
            "LLEGADA_CLIENTE" -> pedirKmMin(pedido, codigo, "Llegada al cliente (datos REALES)",
                    "Km realmente recorridos", "Minutos realmente empleados", "realizado", false)
            "LLEGADA_MOTO_FINAL" -> cerrarPedidoConTipo(pedido, codigo)
            "ESPERA_SIGUIENTE" -> {
                val et = EditText(this)
                et.hint = "Nota opcional (ej. 'esperando en zona sin pedidos')"
                AlertDialog.Builder(this)
                    .setTitle("Tiempo esperado para el siguiente pedido")
                    .setMessage("Se guarda con la hora real. Ademas, la app calcula sola la espera desde el cierre del pedido " +
                            "hasta el recibo del siguiente.")
                    .setView(et)
                    .setPositiveButton("Registrar") { _, _ ->
                        marcar(pedido, codigo, et.text.toString().trim())
                        toast("Fase guardada")
                        dialogFases(pedido)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            else -> {
                marcar(pedido, codigo, "")
                toast("Fase guardada")
                dialogFases(pedido)
            }
        }
    }

    private fun fmtKm(v: Double): String = String.format(Locale.US, "%.1f", v).replace('.', ',')

    private fun pedirKmMin(pedido: String, codigo: String, titulo: String, etKg: String, etMn: String, marca: String,
                           pideMetros: Boolean = false, kmDef: String = "", minDef: String = "", odomCont: String = "") {
        // Los minutos se calculan solos: desde la ultima fase marcada de este pedido
        val prev = repo.fasesEfectivas(jid, pedido).filter { it.first != codigo }.maxByOrNull { it.second }
        val minAuto = if (prev != null)
            (((System.currentTimeMillis() - prev.second) / 60000L).toInt()).takeIf { it in 0..120 } ?: -1 else -1
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        val etKm = EditText(this)
        etKm.hint = etKg
        etKm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (kmDef.isNotEmpty()) etKm.setText(kmDef)
        val prefillMin = if (minDef.isNotEmpty()) minDef else if (minAuto >= 0) minAuto.toString() else ""
        val etMin = EditText(this)
        etMin.hint = if (minAuto >= 0)
            "Minutos REALES (calculados: " + minAuto + "); puedes poner la hora, ej. 14:06" else etMn
        // Texto (no solo numero) para poder teclear la HORA con dos puntos: 14:06
        etMin.inputType = InputType.TYPE_CLASS_TEXT
        if (prefillMin.isNotEmpty()) etMin.setText(prefillMin)
        cont.addView(etKm); cont.addView(etMin)
        val etM = EditText(this)
        if (pideMetros) {
            etM.hint = "Metros a pie desde la moto hasta el local (opcional)"
            etM.inputType = InputType.TYPE_CLASS_NUMBER
            cont.addView(etM)
        }
        val refTxt = if (prev != null)
            "\nLos minutos vienen calculados desde " + prev.first + " (" + horaFmt.format(Date(prev.second)) +
                    "): si te sale otra cosa, escribela tu (puedes poner la hora, ej. 14:06)." else ""
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage("Queda como prueba de la distancia y del tiempo (" + marca + ") de este tramo." + refTxt +
                    (if (kmDef.isNotEmpty()) " Los km vienen ya rellenados de la pantalla de Maps."
                     else if (odomCont.isNotEmpty()) " Sin km de Maps: el km del tramo se calcula con el odometro al llegar (ultima lectura anotada: " + odomCont + ")."
                     else "") +
                    "\n" + "La HORA se guarda al PULSAR: si pulsas tarde, corrigela en 'Corregir la HORA'.")
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                val kmTxt = etKm.text.toString().trim()
                val kmv = kmTxt.replace(',', '.').toDoubleOrNull()
                val minTxt = etMin.text.toString().trim()
                // Acepta "14:06", "1406" (hora) o "6" (minutos): se guarda SIEMPRE el valor tecleado en la nota
                val (minFin, aviso) = interpretarMinutos(minTxt, prev?.let { minutosDelDia(it.second) }, prefillMin)
                // v1.9.5: NO se escribe "km=" cuando el campo va vacio (un km= vacio se leia como tramo = 0).
                val partes = ArrayList<String>()
                if (kmTxt.isNotEmpty()) partes.add("km=" + kmTxt)
                partes.add("min=" + (minFin?.toString() ?: ""))
                partes.add("(" + marca + ")")
                var nota = partes.joinToString(" ") + aviso
                // v1.9.5: km REAL del tramo = lectura de llegada - lectura anotada al salir (odometro)
                if (kmv != null) {
                    val pasoIni = if (codigo == "LLEGADA_PARKING") "RUTA_LOCAL"
                                  else if (codigo == "LLEGADA_CLIENTE") "SALIDA_CLIENTE" else ""
                    if (pasoIni.isNotEmpty()) repo.odometroDe(jid, pedido, pasoIni)?.let { ini ->
                        if (kmv > ini) nota += " tramo=" + fmtKm(kmv - ini)
                    }
                }
                if (pideMetros && etM.text.toString().trim().isNotEmpty()) nota += " mpie=" + etM.text.toString().trim()
                val ult = repo.ultimoCuentakm(jid) ?: repo.kmDe(jid, "FIN")
                if (kmv != null && ult != null && (kmv < ult - 1 || kmv > ult + 60)) {
                    val notaF = nota
                    AlertDialog.Builder(this)
                        .setTitle("Ese km no cuadra")
                        .setMessage("El ultimo cuentakilometros anotado es " + fmtKm(ult) + " km y has escrito " +
                                fmtKm(kmv) + " km.\n\n¿Que haces?")
                        .setPositiveButton("Corregir") { _, _ ->
                            pedirKmMin(pedido, codigo, titulo, etKg, etMn, marca, pideMetros, kmTxt, minTxt)
                        }
                        .setNeutralButton("Usar " + fmtKm(ult)) { _, _ ->
                            marcar(pedido, codigo, "km=" + fmtKm(ult) + " min=" + (minFin?.toString() ?: "") +
                                    " (" + marca + ")" + aviso)
                            toast("Fase guardada con el km anterior"); dialogFases(pedido)
                        }
                        .setNegativeButton("Es correcto") { _, _ ->
                            marcar(pedido, codigo, notaF); toast("Fase guardada"); dialogFases(pedido)
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    marcar(pedido, codigo, nota)
                    toast("Fase guardada")
                    dialogFases(pedido)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Interpreta el texto de minutos: acepta "14:06", "1406" (hora) o "6" (minutos).
     *  Devuelve (minutos desde la fase previa, aviso a anexar a la nota). */
    private fun interpretarMinutos(txt: String, prevMin: Int?, prefill: String): Pair<Int?, String> {
        val t = txt.trim()
        if (t.isEmpty()) return Pair(null, "")
        if (t == prefill.trim()) return Pair(t.toIntOrNull(), "")
        if (t.contains(":")) {
            val p = t.split(":")
            val h = p.getOrNull(0)?.trim()?.toIntOrNull()
            val m = p.getOrNull(1)?.trim()?.toIntOrNull()
            if (h != null && m != null && h in 0..23 && m in 0..59) return aMinutosDesdeHora(h, m, prevMin)
        }
        // 1.1: "16.01" / "16,01" tambien es una HORA (el punto se teclea en vez de ":")
        val pd = t.replace(',', '.')
        if (pd.count { it == '.' } == 1) {
            val p = pd.split('.')
            val hh = p[0].trim().toIntOrNull()
            val mm = p[1].trim().toIntOrNull()
            if (hh != null && mm != null && p[1].trim().length == 2 && hh in 0..23 && mm in 0..59)
                return aMinutosDesdeHora(hh, mm, prevMin)
        }
        val n = t.toIntOrNull() ?: return Pair(null, " [no entendido: " + t + "]")
        // >99 y forma HHMM (1:12 = 112, 14:06 = 1406) => es una HORA
        if (n > 99 && n / 100 <= 23 && n % 100 < 60) return aMinutosDesdeHora(n / 100, n % 100, prevMin)
        return Pair(n, "")
    }

    private fun aMinutosDesdeHora(h: Int, m: Int, prevMin: Int?): Pair<Int?, String> {
        val aviso = " [hora " + String.format(Locale.US, "%02d:%02d", h, m) + "]"
        if (prevMin == null) return Pair(null, aviso)
        var e = (h * 60 + m) - prevMin
        if (e < 0) e += 1440
        // 1.1: un salto de mas de 4 h dentro de una fase no es creible (hora tecleada mal):
        // no se guarda como minutos y se avisa en la nota.
        if (e > 240) return Pair(null, aviso + " [revisar: la hora es anterior a la del paso previo]")
        return Pair(e, aviso)
    }

    private fun minutosDelDia(ms: Long): Int {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    private fun cerrarPedidoConTipo(pedido: String, codigo: String) {
        val oblig = listOf("RECOGIDA", "LLEGADA_CLIENTE", "ENTREGA")
        val hechas = repo.fasesEfectivas(jid, pedido).map { it.first }.toSet()
        val faltan = oblig.filter { it !in hechas }
        if (faltan.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Faltan fases en el pedido " + pedido)
                .setMessage("Vas a cerrar el pedido pero NO estan registradas: " + faltan.joinToString(", ") +
                        ".\n\nCerrarlo asi deja el registro incompleto (y debil como prueba).")
                .setPositiveButton("Marcar las que faltan") { _, _ -> dialogFases(pedido) }
                .setNeutralButton("Cerrar igual") { _, _ -> elegirTipoEntrega(pedido, codigo) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        elegirTipoEntrega(pedido, codigo)
    }

    private fun elegirTipoEntrega(pedido: String, codigo: String) {
        AlertDialog.Builder(this)
            .setTitle("Tipo de entrega (explica el tiempo)")
            .setItems(TIPOS_ENTREGA) { _, w ->
                val et = EditText(this)
                et.hint = "Nota opcional (piso, gradas, metros a pie...)"
                AlertDialog.Builder(this)
                    .setTitle(TIPOS_ENTREGA[w])
                    .setMessage("Cierra el pedido: vuelta a la moto. Se guarda el tipo de entrega para justificar los tiempos.")
                    .setView(et)
                    .setPositiveButton("Guardar y cerrar pedido") { _, _ ->
                        // v1.8: guarda el km actual del odometro para la CONTINUIDAD del lote
                        // (el proximo SALIDA_CLIENTE lo toma de aqui, sin teclear).
                        val ult = repo.ultimoCuentakm(jid) ?: repo.kmDe(jid, "FIN")
                        val nota = TIPOS_ENTREGA[w] + (if (et.text.toString().trim().isNotEmpty()) " | " + et.text.toString().trim() else "") +
                                (if (ult != null) " | km=" + fmtKm(ult) + " (continuidad odometro)" else "")
                        marcar(pedido, codigo, nota)
                        toast("Pedido cerrado con su tipo de entrega")
                        refrescar()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun capturarPantalla() {
        if (jid == 0L) { toast("Primero abre la jornada"); return }
        val s = online.soymasdigital.jornada.service.GlovoAccessibilityService.instancia
        if (s == null) { toast("Activa el servicio de accesibilidad (boton 9)"); return }
        s.capturar("app")
        toast("Capturando la pantalla...")
    }

    // ---------------- INICIO GUIADO (v1.2) ----------------
    //
    // Boton unico de arranque: pantallazo del HORARIO, pantallazo de la CARTERA y
    // odometro de salida / llegada a la zona. La app NO pulsa nada en la app de Glovo:
    // solo pide al repartidor que abra la pestana y hace el pantallazo cuando la tiene delante.

    private fun inicioGuiado() {
        // Un solo boton: si no hay jornada abierta, la abre el mismo.
        if (jid == 0L) {
            val id = repo.abrirJornada()
            toast("Jornada #" + id + " iniciada")
            refrescar()
        }
        // v1.9.3: fuerza al servicio a releer la jornada y limpia el antirrebote: el arranque
        // vuelve a registrar HORARIO/CARTERA_BALANCE/SESION aunque la pantalla se repita.
        try { online.soymasdigital.jornada.service.GlovoAccessibilityService.instancia?.reiniciarParaInicio() } catch (e: Exception) { }
        pasoHorario()
    }

    private fun capturarPaso(etiqueta: String, alTerminar: (() -> Unit)? = null) {
        val s = online.soymasdigital.jornada.service.GlovoAccessibilityService.instancia
        if (s == null) { toast("Activa el servicio de accesibilidad (boton 9)"); alTerminar?.invoke(); return }
        // v1.9.3: ademas del pantallazo, observa la pantalla AHORA y guarda HORARIO/CARTERA_BALANCE/
        // SESION. El arranque guiado ya no depende de que llegue un evento de ventana.
        s.observarPantalla(etiqueta, alTerminar)
    }

    private fun hoyA(h: Int, m: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, h); c.set(java.util.Calendar.MINUTE, m)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun ultimoTexto(tipo: String): String? =
        repo.evidenciasJornada(jid).filter { it.second == tipo }.maxByOrNull { it.first }?.third

    /** Lee el horario de la pestana de sesiones: "Hoy 10:00 - 10:30". */
    private fun leerHorarioDePantalla(): Pair<Long, Long>? {
        val t = (ultimoTexto("HORARIO") ?: return null).replace('\u202F', ' ').replace('\u00A0', ' ')
        val m = Regex("Hoy\\s+(\\d{1,2}):(\\d{2})\\s*[-\\u2013]\\s*(\\d{1,2}):(\\d{2})").find(t) ?: return null
        val ini = hoyA(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        var fin = hoyA(m.groupValues[3].toInt(), m.groupValues[4].toInt())
        if (fin <= ini) fin += 24L * 60L * 60L * 1000L   // sesion que cruza medianoche
        return ini to fin
    }

    /** Lee el saldo de la Cartera: "Saldo actual 226,70 EUR". */
    private fun leerCarteraDePantalla(): Double? {
        val t = (ultimoTexto("CARTERA_BALANCE") ?: return null).replace('\u202F', ' ')
        val m = Regex("Saldo actual\\s*([0-9]{1,6}[.,][0-9]{2})").find(t) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    private fun pasoHorario() {
        AlertDialog.Builder(this)
            .setTitle("Inicio guiado 1/4 - HORARIO")
            .setMessage("Abre en la app de Glovo la pestana de SESIONES (donde sale tu sesion reservada).\n\n" +
                    "Pulsa 'Capturar': guardo el pantallazo (prueba con hora real) y leo tu horario sin que teclees nada.")
            .setPositiveButton("Capturar") { _, _ ->
                capturarPaso("1_horario")
                toast("Capturando...")
                android.os.Handler(mainLooper).postDelayed({ pasoHorarioLeido() }, 1800L)
            }
            .setNeutralButton("Escribirlo a mano") { _, _ -> pedirHorario { pasoCartera() } }
            .setNegativeButton("Saltar", null)
            .show()
    }

    private fun pasoHorarioLeido() {
        val h = leerHorarioDePantalla()
        if (h != null) {
            repo.guardarHorario(jid, h.first, h.second)
            toast("Horario leido: " + horaFmt.format(Date(h.first)) + " - " + horaFmt.format(Date(h.second)))
            refrescar()
            pasoCartera()
        } else {
            AlertDialog.Builder(this)
                .setTitle("No he podido leer el horario")
                .setMessage("No veo la pestana de sesiones en el pantallazo. Repite la captura con esa pestana abierta o escribelo a mano.\n\n(Si el pantallazo no llego a guardarse, activa la accesibilidad con el boton 9 y repite.)")
                .setPositiveButton("Escribirlo a mano") { _, _ -> pedirHorario { pasoCartera() } }
                .setNeutralButton("Repetir captura") { _, _ -> pasoHorario() }
                .setNegativeButton("Saltar", null)
                .show()
        }
    }

    private fun pasoCartera() {
        AlertDialog.Builder(this)
            .setTitle("Inicio guiado 2/4 - CARTERA")
            .setMessage("Abre la pestana CARTERA de la app de Glovo.\n\n" +
                    "Pulsa 'Capturar': guardo el pantallazo y leo el 'Saldo actual' sin que teclees nada.")
            .setPositiveButton("Capturar") { _, _ ->
                capturarPaso("2_cartera_inicio")
                toast("Capturando...")
                android.os.Handler(mainLooper).postDelayed({ pasoCarteraLeida() }, 1800L)
            }
            .setNeutralButton("Escribirlo a mano") { _, _ -> pedirCarteraMomento("INICIO") { pasoOdoSalida() } }
            .setNegativeButton("Saltar", null)
            .show()
    }

    private fun pasoCarteraLeida() {
        val s = leerCarteraDePantalla()
        if (s != null) {
            repo.snapshot(jid, "INICIO", s, "app_confirmado")
            toast("Cartera INICIO leida: " + s + " EUR")
            refrescar()
            pasoOdoSalida()
        } else {
            AlertDialog.Builder(this)
                .setTitle("No he podido leer la cartera")
                .setMessage("No veo el 'Saldo actual' en el pantallazo. Repite la captura con la Cartera abierta o escribelo a mano.\n\n(Si el pantallazo no llego a guardarse, activa la accesibilidad con el boton 9 y repite.)")
                .setPositiveButton("Escribirlo a mano") { _, _ -> pedirCarteraMomento("INICIO") { pasoOdoSalida() } }
                .setNeutralButton("Repetir captura") { _, _ -> pasoCartera() }
                .setNegativeButton("Saltar", null)
                .show()
        }
    }

    private fun pasoOdoSalida() {
        AlertDialog.Builder(this)
            .setTitle("Inicio guiado 3/4 - ODOMETRO (salida de casa)")
            .setMessage("Mira el cuentakilometros de la moto ANTES de arrancar.\n\n" +
                    "Pulsa el boton: se guarda el km de INICIO y te pido la foto del odometro " +
                    "(prueba con hora, guardada como 'odometro - salida de casa').")
            .setPositiveButton("Km INICIO + foto") { _, _ -> pedirKmMomento("INICIO") { pasoLlegadaZona() } }
            .setNegativeButton("Saltar", null)
            .show()
    }

    private fun pasoLlegadaZona() {
        AlertDialog.Builder(this)
            .setTitle("Inicio guiado 4/4 - ODOMETRO (llegada a la zona)")
            .setMessage("Cuando LLEGUES a la zona de trabajo, vuelve aqui: se registra la llegada y se pide " +
                    "la foto del odometro ('odometro - llegada a la zona').\n\n" +
                    "Con esto termina el arranque guiado.")
            .setPositiveButton("Ya estoy en la zona") { _, _ ->
                capturarPaso("4_odo_llegada_zona")
                llegadaZona()
            }
            .setNegativeButton("Lo hago luego (boton 'Llegada a la ZONA DE TRABAJO')", null)
            .show()
    }

    // ---------------- CIERRE GUIADO: vuelta a casa (v1.3) ----------------
    //
    // Con el ultimo pedido entregado: km + foto del odometro al salir hacia casa, hora de la foto y
    // pantallazo del recorrido de Maps (para comparar el tiempo de Google con el real). Al llegar a
    // casa se vuelve a tomar el odometro (km FIN) y se cierra la jornada del dia.

    /** Cierre guiado: pantallazos de SESIONES y CARTERA (con lectura del saldo) + odometro de vuelta + cierre. */
    private fun cierreGuiado() {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        AlertDialog.Builder(this)
            .setTitle("Cierre guiado 1/4 - SESIONES")
            .setMessage("Abre en la app de Glovo la pestana de SESIONES (donde se ve que tu sesion ha terminado).\n\n" +
                    "Pulsa 'Capturar': queda el pantallazo con hora real como prueba del cierre de sesion.")
            .setPositiveButton("Capturar") { _, _ -> capturarPaso("cierre_1_sesiones"); toast("Capturando..."); cierreCartera() }
            .setNegativeButton("Saltar") { _, _ -> cierreCartera() }
            .show()
    }

    private fun cierreCartera() {
        AlertDialog.Builder(this)
            .setTitle("Cierre guiado 2/4 - CARTERA")
            .setMessage("Abre la pestana CARTERA de la app de Glovo.\n\n" +
                    "Pulsa 'Capturar': guardo el pantallazo y leo el 'Saldo actual' como cartera de FIN, sin que teclees.")
            .setPositiveButton("Capturar") { _, _ ->
                capturarPaso("cierre_2_cartera_fin")
                toast("Capturando...")
                android.os.Handler(mainLooper).postDelayed({ cierreCarteraLeida() }, 1800L)
            }
            .setNeutralButton("Escribirlo a mano") { _, _ -> pedirCarteraMomento("FIN") { pasoVueltaCasa() } }
            .setNegativeButton("Saltar") { _, _ -> pasoVueltaCasa() }
            .show()
    }

    private fun cierreCarteraLeida() {
        val s = leerCarteraDePantalla()
        if (s != null) {
            repo.snapshot(jid, "FIN", s, "app_confirmado")
            toast("Cartera FIN leida: " + s + " EUR")
            refrescar()
            pasoVueltaCasa()
        } else {
            AlertDialog.Builder(this)
                .setTitle("No he podido leer la cartera")
                .setMessage("No veo el 'Saldo actual' en el pantallazo. Repite la captura con la Cartera abierta o escribelo a mano.\n\n(Si el pantallazo no llego a guardarse, activa la accesibilidad con el boton 9 y repite.)")
                .setPositiveButton("Escribirlo a mano") { _, _ -> pedirCarteraMomento("FIN") { pasoVueltaCasa() } }
                .setNeutralButton("Repetir captura") { _, _ -> cierreCartera() }
                .setNegativeButton("Saltar") { _, _ -> pasoVueltaCasa() }
                .show()
        }
    }

    private fun pasoVueltaCasa() {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val ya = repo.eventosJornada(jid).lastOrNull { it.first == "VUELTA_SALIDA" }
        if (ya != null) {
            AlertDialog.Builder(this)
                .setTitle("Ya hay una SALIDA hacia casa")
                .setMessage("Registraste la SALIDA hacia casa a las " + horaFmt.format(Date(ya.second)) + ".\n\n" +
                        "Si ya has LLEGADO a casa, esto NO es una salida: usa LLEGADA A CASA (paso 4/4).\n" +
                        "Solo registra otra salida si de verdad has vuelto a salir (segundo trayecto).")
                .setPositiveButton("Ir a LLEGADA A CASA") { _, _ -> pasoLlegadaCasa() }
                .setNeutralButton("Registrar otra SALIDA") { _, _ -> pedirKmVuelta() }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Cierre guiado 3/4 - SALIDA HACIA CASA")
            .setMessage("Esto es la SALIDA: cuando YA has entregado el ultimo pedido y VAS a arrancar hacia casa.\n\n" +
                    "Al pulsar: guardo el km del cuentakilometros con la hora (VUELTA_SALIDA) y te pido el " +
                    "pantallazo del recorrido de Maps.\n\nLa LLEGADA a casa es el paso siguiente (4/4), con su " +
                    "propio km y su propio pantallazo: no se confunden.")
            .setPositiveButton("Km de SALIDA + foto") { _, _ -> pedirKmVuelta() }
            .setNegativeButton("Saltar", null)
            .show()
    }

    /** Km del odometro al salir del ultimo pedido hacia casa + hora de la foto + ETA de Maps. */
    private fun pedirKmVuelta() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.hint = "Km del cuentakilometros al salir hacia casa"
        val ult = repo.kmDe(jid, "FIN") ?: repo.ultimoCuentakm(jid)
        if (ult != null) et.setText(fmtKm(ult))
        AlertDialog.Builder(this)
            .setTitle("Vuelta a casa - salida")
            .setMessage("Km que marca la moto AHORA. Hora de la foto: " + horaFmt.format(Date()) + ".")
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val v = et.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (v == null) { toast("Numero no valido"); return@setPositiveButton }
                val ahora = System.currentTimeMillis()
                val g = etaDeMaps()
                val ok = repo.registrarEventoJornada(jid, "VUELTA_SALIDA",
                        "km=" + fmtKm(v) + " hora=" + horaFmt.format(Date(ahora)) +
                                " foto=odometro - salida hacia casa" +
                                (if (g != null) " google=" + g.toInt() else ""))
                if (!ok) { toast("VUELTA_SALIDA ya registrada con ese km hace menos de 30 s: no se repite."); return@setPositiveButton }
                toast("Salida hacia casa: " + fmtKm(v) + " km" + (if (g != null) " (Maps: " + g.toInt() + " min)" else ""))
                refrescar()
                ofrecerCapturaRecorrido()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Minutos estimados por Google en el ultimo plan de ruta que se vio en Maps. */
    private fun etaDeMaps(): Double? {
        val t = (ultimoTexto("MAPS_PLAN") ?: return null).replace('\u202F', ' ')
        return Regex("([0-9]{1,3})\\s*min").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun ofrecerCapturaRecorrido() {
        AlertDialog.Builder(this)
            .setTitle("Pantallazo Maps - SALIDA hacia casa")
            .setMessage("Pon en Maps la ruta hasta casa (que se vea el tiempo estimado) y pulsa 'Capturar'.\n\n" +
                    "Queda en el expediente para comparar el tiempo de Google con el real. Despues seguimos con " +
                    "la LLEGADA a casa (paso 4/4).")
            .setPositiveButton("Capturar y seguir a LLEGADA") { _, _ ->
                toast("Capturando...")
                capturarPaso("5_vuelta_maps") { pasoLlegadaCasa() }
            }
            .setNeutralButton("Ir a LLEGADA A CASA") { _, _ -> pasoLlegadaCasa() }
            .setNegativeButton("Saltar", null)
            .show()
    }

    private fun pasoLlegadaCasa() {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val sal = repo.eventosJornada(jid).lastOrNull { it.first == "VUELTA_SALIDA" }
        if (sal == null) {
            AlertDialog.Builder(this)
                .setTitle("Falta la SALIDA hacia casa")
                .setMessage("Primero se registra la SALIDA hacia casa (paso 3/4: km al salir del ultimo pedido). " +
                        "Ahora vas a registrar la LLEGADA (LLEGADA_CASA), que es un paso distinto y nunca se " +
                        "guarda como salida.")
                .setPositiveButton("Ir a SALIDA hacia casa") { _, _ -> pasoVueltaCasa() }
                .setNegativeButton("Registrar LLEGADA igual") { _, _ -> pedirKmLlegadaCasa() }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Cierre guiado 4/4 - LLEGADA A CASA (fin)")
            .setMessage("Esto es la LLEGADA: ya estas en casa y miras el cuentakilometros.\n\n" +
                    "Guardo LLEGADA_CASA con el km de FIN, calculo los km y el tiempo reales de la vuelta, " +
                    "te pido la foto del odometro y el pantallazo del recorrido en Maps. Despues se cierra la jornada.")
            .setPositiveButton("Km de LLEGADA + pantallazo Maps") { _, _ -> pedirKmLlegadaCasa() }
            .setNegativeButton("Saltar", null)
            .show()
    }

    private fun pedirKmLlegadaCasa() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val salida = repo.eventosJornada(jid).lastOrNull { it.first == "VUELTA_SALIDA" }
        val kmSal = salida?.third?.let {
            Regex("km=([0-9]+(?:[.,][0-9]+)?)").find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        }
        // v1.9.2: NO se precarga el km de salida (evita guardar por error la misma cifra = km de vuelta 0)
        et.hint = if (kmSal != null) "Km al llegar a casa (mayor que " + fmtKm(kmSal) + ")" else "Km del cuentakilometros al llegar a casa"
        AlertDialog.Builder(this)
            .setTitle("LLEGADA A CASA - km de llegada")
            .setMessage("Km que marca la moto AHORA. Hora: " + horaFmt.format(Date()) + "." +
                    (if (salida != null) "\n\nSalida hacia casa a las " + horaFmt.format(Date(salida.second)) +
                            (if (kmSal != null) " con " + fmtKm(kmSal) + " km" else "") else ""))
            .setView(et)
            .setPositiveButton("Guardar y cerrar") { _, _ ->
                val v = et.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (v == null) { toast("Numero no valido"); return@setPositiveButton }
                if (kmSal != null && v <= kmSal) { toast("El km de LLEGADA debe ser mayor que el de SALIDA (" + fmtKm(kmSal) + ")"); return@setPositiveButton }
                val kmRec = if (kmSal != null) v - kmSal else 0.0
                val minutos = if (salida != null)
                    ((System.currentTimeMillis() - salida.second) / 60000L).coerceAtLeast(0L) else 0L
                // v1.9.3: guarda el KM REAL DEL ODOMETRO (lo que marca la moto) y la distancia de la
                // vuelta por separado (vuelta=). Antes se guardaba solo el recorrido de la vuelta en
                // km= y el evento parecia un odometro de 4,0 km.
                val ok = repo.registrarEventoJornada(jid, "LLEGADA_CASA",
                        "km=" + fmtKm(v) + " vuelta=" + fmtKm(kmRec) +
                                " tiempo=" + String.format(Locale.US, "%02d:%02d", minutos / 60, minutos % 60) +
                                " foto=odometro - llegada a casa")
                if (!ok) { toast("LLEGADA_CASA ya registrada hace menos de 30 s: no se repite."); return@setPositiveButton }
                toast("Llegada a casa: " + fmtKm(v) + " km (" + fmtKm(kmRec) + " km de vuelta)")
                refrescar()
                guardarKmValor("FIN", v) { ofrecerCapturaLlegada() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Pantallazo del recorrido en Maps al llegar a casa (Google vs tiempo real). Antes de cerrar. */
    private fun ofrecerCapturaLlegada() {
        AlertDialog.Builder(this)
            .setTitle("Pantallazo del recorrido (llegada a casa)")
            .setMessage("Pon en Maps la ruta del ultimo pedido hasta casa (que se vea el tiempo total) y pulsa 'Capturar'.\n\n" +
                    "Queda en el expediente junto al km y el tiempo reales de la vuelta.")
            .setPositiveButton("Capturar") { _, _ ->
                capturarPaso("6_llegada_maps")
                toast("Capturando...")
                android.os.Handler(mainLooper).postDelayed({ finalizarJornada() }, 1800L)
            }
            .setNeutralButton("Cerrar jornada sin pantallazo") { _, _ -> finalizarJornada() }
            .setNegativeButton("Saltar", null)
            .show()
    }

    // ---------------- FACTURA DE COBRO (v1.5) ----------------
    //
    // Conceptos: km casa->zona, km zona->casa, km para recoger pedido (incluye reasignados),
    // km buscando pedidos (reposicionamientos) y mantenimiento de la moto. Tarifa editable.

    private fun fRed(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun fEur(v: Double): String = String.format(Locale.US, "%.2f EUR", v)
    private fun fTar(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fKm(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun ofrecerFactura() {
        if (jid == 0L) return
        AlertDialog.Builder(this)
            .setTitle("Factura de cobro")
            .setMessage("Jornada cerrada. \u00BFGenero la factura de cobro del dia con los conceptos de km y el mantenimiento?")
            .setPositiveButton("Generar factura") { _, _ -> generarFactura() }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun pedirTarifa(siguiente: () -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.setText(fTar(repo.tarifaKm()).replace('.', ','))
        AlertDialog.Builder(this)
            .setTitle("Tarifa EUR/km")
            .setMessage("Precio por km de la factura. Por defecto 1,27 EUR/km, deducido de tu pedido #393 " +
                    "(pagaste 23,20 EUR en el local, cobraste 27,01 EUR al cliente, 3 km). Cambialo si quieres otro valor.")
            .setView(et)
            .setPositiveButton("Guardar") { _, _ ->
                val v = et.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (v == null || v <= 0.0) { toast("Valor no valido"); return@setPositiveButton }
                repo.setTarifaKm(v)
                toast("Tarifa: " + fTar(v) + " EUR/km")
                siguiente()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun generarFactura() {
        if (jid == 0L) { toast("Primero abre la jornada (1. PREPARAR JORNADA)"); return }
        val tarifa = repo.tarifaKm()
        val k1 = repo.kmCasaZona(jid) ?: 0.0
        val k2 = repo.kmZonaCasa(jid) ?: 0.0
        val k3 = repo.kmParaRecoger(jid)
        val k4 = repo.kmBuscandoPedidos(jid)
        val mant = repo.mantenimientoJornada(jid)
        val i1 = fRed(k1 * tarifa); val i2 = fRed(k2 * tarifa)
        val i3 = fRed(k3 * tarifa); val i4 = fRed(k4 * tarifa)
        val total = fRed(i1 + i2 + i3 + i4 + mant)
        val msg = "Tarifa: " + fTar(tarifa) + " EUR/km\n\n" +
                "Km casa -> area de trabajo:  " + fKm(k1) + " km = " + fEur(i1) + "\n" +
                "Km area de trabajo -> casa:  " + fKm(k2) + " km = " + fEur(i2) + "\n" +
                "Km para recoger pedido:  " + fKm(k3) + " km = " + fEur(i3) + "\n" +
                "Km buscando pedidos:  " + fKm(k4) + " km = " + fEur(i4) + "\n" +
                "Mantenimiento de la moto:  " + fEur(mant) + "\n\n" +
                "TOTAL: " + fEur(total)
        AlertDialog.Builder(this)
            .setTitle("Factura de cobro - jornada #" + jid)
            .setMessage(msg)
            .setPositiveButton("Generar PDF") { _, _ ->
                conCarpeta { escribirFactura(tarifa, k1, k2, k3, k4, mant, i1, i2, i3, i4, total) }
            }
            .setNeutralButton("Cambiar EUR/km") { _, _ -> pedirTarifa { generarFactura() } }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun escribirFactura(tarifa: Double, k1: Double, k2: Double, k3: Double, k4: Double, mant: Double,
                                i1: Double, i2: Double, i3: Double, i4: Double, total: Double) {
        try {
            val lineas = listOf(
                Factura.Linea("Km casa - area de trabajo", fKm(k1) + " km x " + fTar(tarifa) + " EUR/km (ida al tajo)", i1),
                Factura.Linea("Km area de trabajo - casa", fKm(k2) + " km x " + fTar(tarifa) + " EUR/km (vuelta a casa)", i2),
                Factura.Linea("Km para recoger pedido", fKm(k3) + " km x " + fTar(tarifa) + " EUR/km (incluye pedidos reasignados)", i3),
                Factura.Linea("Km buscando pedidos", fKm(k4) + " km x " + fTar(tarifa) + " EUR/km (reposicionamientos declarados)", i4),
                Factura.Linea("Mantenimiento de la moto", "gastos de mantenimiento registrados en la jornada", mant)
            )
            val cab = listOf(
                lineaRepartidor(),
                "Jornada #" + jid + (repo.jornada(jid)?.let { " del " + it.first } ?: ""),
                "Documento de reclamacion de la actividad del repartidor. Sin IVA.",
                "Tarifa aplicada: " + fTar(tarifa) + " EUR/km, deducida de un pedido propio pagado en efectivo en el establecimiento:",
                "pedido #393 del 24/09/2026: 23,20 EUR pagados en el local, 27,01 EUR cobrados al cliente, 3 km de recorrido."
            )
            val pie = listOf(
                "Los km salen del cuentakilometros de la moto (lecturas con foto y hora) y de los hitos que registra la app.",
                "Las capturas de pantalla que justifican cada dato quedan archivadas en el expediente del dia.",
                "Documento generado por el propio repartidor con datos propios de su actividad."
            )
            val s = Factura.generar(this, "FACTURA DE COBRO", cab, lineas, total, pie)
            AlertDialog.Builder(this)
                .setTitle("Factura generada")
                .setMessage("Guardada en:\n" + s.destino + "\n" + s.nombre + "\n\nTOTAL: " + fEur(total) + "\n" + (s.bytes / 1024) + " KB")
                .setPositiveButton("Compartir") { _, _ -> compartirPdf(s) }
                .setNeutralButton("Abrir") { _, _ -> abrirPdf(s) }
                .setNegativeButton("Vale", null)
                .show()
        } catch (e: Exception) {
            toast("No se pudo generar la factura: " + e.message)
        }
    }

    private fun flujoIncidencia() {
        if (jid == 0L) { toast("Primero abre la jornada"); return }
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.hint = "Importe TOTAL del pedido (EUR)"
        AlertDialog.Builder(this)
            .setTitle("Cliente ausente - 1/3")
            .setMessage("Registra el evento completo. El cierre sera por ACCION HUMANA (nunca automatico).")
            .setView(et)
            .setPositiveButton("Siguiente") { _, _ ->
                val imp = et.text.toString().replace(',', '.').trim().toDoubleOrNull()
                if (imp == null) toast("Importe no valido") else elegirPago(imp)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun elegirPago(imp: Double) {
        val ops = arrayOf("Pagado en linea", "Efectivo")
        AlertDialog.Builder(this)
            .setTitle("Cliente ausente - 2/3: tipo de pago")
            .setItems(ops) { _, w ->
                val pago = if (w == 0) "online" else "efectivo"
                if (pago == "efectivo") elegirCobrado(imp, pago) else crearIncidencia(imp, pago, "no_aplica")
            }
            .show()
    }

    private fun elegirCobrado(imp: Double, pago: String) {
        AlertDialog.Builder(this)
            .setTitle("Cliente ausente - 3/3")
            .setMessage("En pedido de efectivo con cliente ausente: NO se cobra nada, y aun asi cargan el importe total. " +
                    "Registra si llegaste a cobrar.")
            .setPositiveButton("NO he cobrado nada") { _, _ -> crearIncidencia(imp, pago, "no") }
            .setNegativeButton("Si cobre") { _, _ -> crearIncidencia(imp, pago, "si") }
            .show()
    }

    private fun crearIncidencia(imp: Double, pago: String, cobrado: String) {
        val id = repo.nuevaIncidencia(jid, "", imp, pago, cobrado)
        val canales = arrayOf("Cerre yo el pedido", "La cerro la app sola", "La cerro soporte tecnico")
        AlertDialog.Builder(this)
            .setTitle("Cierre por accion humana")
            .setMessage("Registro abierto (#" + id + "). Para cerrarlo necesito saber como termino el pedido:")
            .setItems(canales) { _, w ->
                repo.cerrarIncidencia(id, canales[w])
                toast("Incidencia #" + id + " registrada y cerrada (" + canales[w] + ")")
                refrescar()
            }
            .setNegativeButton("Dejar abierta (pendiente de cierre)", null)
            .show()
    }

    // ---------------- bloque D ----------------

    // ---------------- v2.0.0: CARPETA DE INFORMES / DATOS DEL REPARTIDOR ----------------

    /** Ejecuta [accion]; si no hay carpeta elegida la pide antes (o cae a la privada avisando). */
    private fun conCarpeta(accion: () -> Unit) {
        if (SalidaPdf.disponible(this)) { accion(); return }
        AlertDialog.Builder(this)
            .setTitle("Carpeta de informes")
            .setMessage("Todavia no has elegido la carpeta donde guardar los PDF.\n\n" +
                    "Elige una carpeta con el selector del sistema (mejor una tarjeta SD o una carpeta compartida con el PC). " +
                    "Si lo prefieres, se guardaran en la carpeta privada de la app.")
            .setPositiveButton("Elegir carpeta") { _, _ ->
                accionTrasCarpeta = accion
                elegirCarpeta()
            }
            .setNegativeButton("Usar carpeta privada") { _, _ ->
                toast("Aviso: el PDF se guardara en la carpeta privada de la app")
                accion()
            }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    /** Selector del sistema (Storage Access Framework) para elegir la carpeta de informes. */
    private fun elegirCarpeta() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        try {
            startActivityForResult(i, REQ_CARPETA)
        } catch (e: Exception) {
            toast("No se pudo abrir el selector de carpetas: " + e.message)
            accionTrasCarpeta = null
        }
    }

    /** Aviso de almacenamiento: la primera vez solo, y luego desde su boton. */
    private fun avisoAlmacenamiento(forzar: Boolean) {
        if (!forzar && prefs().getBoolean("aviso_almacenamiento", false)) return
        prefs().edit().putBoolean("aviso_almacenamiento", true).apply()
        val actual = SalidaPdf.nombreCarpeta(this)
        AlertDialog.Builder(this)
            .setTitle("Donde se guardan los PDF")
            .setMessage("Los PDF (informe del dia, expediente y factura) se guardan en la carpeta que elijas tu con el selector del sistema.\n\n" +
                    "Puedes borrar los que no necesites desde el gestor de archivos del movil.\n\n" +
                    "Se recomienda usar almacenamiento externo (tarjeta SD o una carpeta compartida con el PC) para conservar la informacion y no saturar el movil." +
                    (if (actual != null) "\n\nCarpeta actual: " + actual else "\n\nTodavia no has elegido carpeta."))
            .setPositiveButton("Elegir carpeta") { _, _ -> elegirCarpeta() }
            .setNegativeButton("Vale", null)
            .show()
    }

    private fun datoRepartidor(): String = prefs().getString("repartidor_nombre", "") ?: ""
    private fun datoZona(): String = prefs().getString("repartidor_zona", "") ?: ""

    /** Linea de identificacion para la factura: sale de lo que el usuario escriba (vacio por defecto). */
    private fun lineaRepartidor(): String {
        val n = datoRepartidor()
        val z = datoZona()
        return "Repartidor: " + (if (n.isEmpty()) "(sin configurar)" else n) +
                "  -  Centro/zona: " + (if (z.isEmpty()) "(sin configurar)" else z)
    }

    private fun pedirDatosRepartidor() {
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        cont.setPadding(dp(20), dp(8), dp(20), 0)
        val en = EditText(this)
        en.hint = "Nombre del repartidor"
        en.setText(datoRepartidor())
        en.inputType = InputType.TYPE_CLASS_TEXT
        val ez = EditText(this)
        ez.hint = "Centro / zona (ej. Centro - ciudad)"
        ez.setText(datoZona())
        ez.inputType = InputType.TYPE_CLASS_TEXT
        cont.addView(en)
        cont.addView(ez)
        AlertDialog.Builder(this)
            .setTitle("Mis datos")
            .setMessage("Tu nombre y tu zona salen en la factura. Se guardan solo en este movil y puedes cambiarlos cuando quieras. No son obligatorios.")
            .setView(cont)
            .setPositiveButton("Guardar") { _, _ ->
                prefs().edit()
                    .putString("repartidor_nombre", en.text.toString().trim())
                    .putString("repartidor_zona", ez.text.toString().trim())
                    .apply()
                toast("Datos guardados")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- v1.9.9: INFORME DIARIO EN PDF ----------------

    // ---------------- v2.1.0: JUSTIFICACION DE AVISO / SANCION ----------------

    /** Formulario: tipo, fecha/hora del aviso, pedidos afectados y datos del trabajador. */
    private fun formularioJustificacion() {
        val cont = LinearLayout(this)
        cont.orientation = LinearLayout.VERTICAL
        cont.setPadding(dp(20), dp(8), dp(20), dp(8))

        fun etiqueta(t: String): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = 12f
            tv.setTypeface(null, android.graphics.Typeface.BOLD)
            tv.setPadding(0, dp(10), 0, dp(2))
            return tv
        }

        val tipo = Spinner(this)
        ArrayAdapter(this, android.R.layout.simple_spinner_item,
                arrayOf("Llamada de atención", "Sanción", "Suspensión"))
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); tipo.adapter = it }

        val calFecha = Calendar.getInstance()
        val calHora = Calendar.getInstance()

        val bFecha = Button(this)
        bFecha.isAllCaps = false
        fun pintarFecha() {
            bFecha.text = "Fecha del aviso: " + String.format(Locale.US, "%02d/%02d/%04d",
                    calFecha.get(Calendar.DAY_OF_MONTH), calFecha.get(Calendar.MONTH) + 1, calFecha.get(Calendar.YEAR))
        }
        pintarFecha()
        bFecha.setOnClickListener {
            DatePickerDialog(this, { _, a, m, d ->
                calFecha.set(a, m, d)
                pintarFecha()
            }, calFecha.get(Calendar.YEAR), calFecha.get(Calendar.MONTH), calFecha.get(Calendar.DAY_OF_MONTH)).show()
        }

        val bHora = Button(this)
        bHora.isAllCaps = false
        fun pintarHora() {
            bHora.text = "Hora del aviso: " + String.format(Locale.US, "%02d:%02d",
                    calHora.get(Calendar.HOUR_OF_DAY), calHora.get(Calendar.MINUTE))
        }
        pintarHora()
        bHora.setOnClickListener {
            TimePickerDialog(this, { _, hh, mm ->
                calHora.set(Calendar.HOUR_OF_DAY, hh)
                calHora.set(Calendar.MINUTE, mm)
                pintarHora()
            }, calHora.get(Calendar.HOUR_OF_DAY), calHora.get(Calendar.MINUTE), true).show()
        }

        val ePedidos = EditText(this)
        ePedidos.hint = "Order ID (uno o varios, separados por coma, espacio o salto de línea)"
        ePedidos.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        ePedidos.minLines = 3
        ePedidos.gravity = Gravity.TOP

        val eRef = EditText(this)
        eRef.hint = "Referencia o expediente del aviso (opcional)"
        eRef.inputType = InputType.TYPE_CLASS_TEXT

        val eNombre = EditText(this)
        eNombre.hint = "Nombre y apellidos del trabajador"
        eNombre.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        eNombre.setText(prefs().getString("just_trabajador", "").orEmpty().ifEmpty { datoRepartidor() })

        val eDni = EditText(this)
        eDni.hint = "DNI / NIE"
        eDni.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        eDni.setText(prefs().getString("just_dni", "").orEmpty())

        val eEmpresa = EditText(this)
        eEmpresa.hint = "Empresa (razón social)"
        eEmpresa.inputType = InputType.TYPE_CLASS_TEXT
        eEmpresa.setText(prefs().getString("just_empresa", "").orEmpty())

        cont.addView(etiqueta("Tipo de aviso"))
        cont.addView(tipo)
        cont.addView(etiqueta("Fecha y hora del aviso"))
        cont.addView(bFecha)
        cont.addView(bHora)
        cont.addView(etiqueta("Pedidos afectados"))
        cont.addView(ePedidos)
        cont.addView(etiqueta("Referencia / expediente"))
        cont.addView(eRef)
        cont.addView(etiqueta("Datos del trabajador (se guardan en el móvil)"))
        cont.addView(eNombre)
        cont.addView(eDni)
        cont.addView(eEmpresa)

        val aviso = TextView(this)
        aviso.text = "La justificación se redacta con los hitos reales de esos pedidos en la base de datos " +
                "de la app (recibo, ruta, llegada, recogida, entrega y kilómetros) y con las capturas guardadas " +
                "en el tramo de cada pedido. Si falta un dato se escribe «s/d»."
        aviso.textSize = 11f
        aviso.setTextColor(Color.parseColor("#475569"))
        aviso.setPadding(0, dp(12), 0, 0)
        cont.addView(aviso)

        val sv = ScrollView(this)
        sv.addView(cont)

        val dlg = AlertDialog.Builder(this)
                .setTitle("Justificación de aviso o sanción")
                .setView(sv)
                .setPositiveButton("Generar justificación", null)
                .setNegativeButton("Cancelar", null)
                .create()
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val ids = extraerPedidos(ePedidos.text.toString())
                if (ids.isEmpty()) {
                    toast("Escribe al menos un número de pedido (Order ID)")
                    return@setOnClickListener
                }
                val nombre = eNombre.text.toString().trim()
                val dni = eDni.text.toString().trim()
                val empresa = eEmpresa.text.toString().trim()
                prefs().edit()
                        .putString("just_trabajador", nombre)
                        .putString("just_dni", dni)
                        .putString("just_empresa", empresa)
                        .apply()
                val cal = Calendar.getInstance()
                cal.set(calFecha.get(Calendar.YEAR), calFecha.get(Calendar.MONTH), calFecha.get(Calendar.DAY_OF_MONTH),
                        calHora.get(Calendar.HOUR_OF_DAY), calHora.get(Calendar.MINUTE), 0)
                cal.set(Calendar.MILLISECOND, 0)
                val datos = Justificacion.Datos(
                        tipo.selectedItem?.toString() ?: "Llamada de atención",
                        cal.timeInMillis, ids, eRef.text.toString().trim(), nombre, dni, empresa)
                dlg.dismiss()
                conCarpeta {
                    toast("Generando justificación PDF...")
                    Thread {
                        try {
                            val s = Justificacion.generar(applicationContext, datos)
                            runOnUiThread { mostrarJustificacion(s) }
                        } catch (e: Exception) {
                            runOnUiThread { toast("No se pudo generar la justificación: " + e.message) }
                        }
                    }.start()
                }
            }
        }
        dlg.show()
    }

    /** Saca los Order ID de un texto libre: uno o varios, con coma, espacio o salto de linea. */
    private fun extraerPedidos(texto: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in Regex("[0-9]{6,}").findAll(texto)) out.add(m.value)
        return out.toList()
    }

    private fun mostrarJustificacion(s: SalidaPdf.Salida) {
        AlertDialog.Builder(this)
                .setTitle("Justificación generada")
                .setMessage("Guardada en:\n" + s.destino + "\n" + s.nombre + "\n\n" + (s.bytes / 1024) + " KB")
                .setPositiveButton("Compartir") { _, _ -> compartirPdf(s) }
                .setNeutralButton("Abrir") { _, _ -> abrirPdf(s) }
                .setNegativeButton("Cerrar", null)
                .show()
    }

    /** Genera el informe de la jornada de hoy (o de la ultima con datos) y ofrece compartirlo. */
    private fun generarInformeDelDia() {
        val objetivo = repo.jornadaDeHoy() ?: repo.jornadaAbierta() ?: repo.ultimaJornadaUtil()
        if (objetivo == null) { toast("No hay jornadas con datos todavia"); return }
        conCarpeta {
            toast("Generando informe PDF...")
            Thread {
                try {
                    val s = InformeDia.generar(applicationContext, objetivo)
                    runOnUiThread { mostrarInforme(s, objetivo) }
                } catch (e: Exception) {
                    runOnUiThread { toast("No se pudo generar el informe: " + e.message) }
                }
            }.start()
        }
    }

    private fun mostrarInforme(s: SalidaPdf.Salida, id: Long) {
        AlertDialog.Builder(this)
            .setTitle("Informe de jornada #" + id)
            .setMessage("Guardado en:\n" + s.destino + "\n" + s.nombre + "\n\n" + (s.bytes / 1024) + " KB")
            .setPositiveButton("Compartir") { _, _ -> compartirPdf(s) }
            .setNeutralButton("Abrir") { _, _ -> abrirPdf(s) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun compartirPdf(s: SalidaPdf.Salida) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, s.uri)
                putExtra(Intent.EXTRA_SUBJECT, s.nombre.removeSuffix(".pdf"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri(s.nombre, s.uri)
            }
            startActivity(Intent.createChooser(i, "Compartir informe PDF"))
        } catch (e: Exception) {
            toast("No se pudo compartir: " + e.message)
        }
    }

    private fun abrirPdf(s: SalidaPdf.Salida) {
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(s.uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("No hay visor de PDF en el movil: " + e.message)
        }
    }

    /**
     * v1.9.9: al cerrar la jornada se genera el PDF en segundo plano. Se ejecuta en un hilo
     * aparte para no bloquear la UI y cualquier fallo se ignora: el cierre NUNCA se rompe.
     */
    private fun generarInformeAuto(id: Long) {
        Thread {
            try { InformeDia.generar(applicationContext, id) } catch (e: Exception) { }
        }.start()
    }

    private fun generarExpediente() {
        val objetivo = repo.jornadaAbierta() ?: repo.ultimaJornadaUtil()
        if (objetivo == null) { toast("No hay jornadas con datos todavia"); return }
        conCarpeta {
            try {
                val s = Expediente.generar(this, "Expediente de jornada Glovo", repo.lineasExpediente(objetivo))
                AlertDialog.Builder(this)
                    .setTitle("Expediente generado (jornada #" + objetivo + ")")
                    .setMessage("Guardado en:\n" + s.destino + "\n" + s.nombre + "\n\n" + (s.bytes / 1024) + " KB\n" +
                            "Incluye km de moto, cartera, pedidos, incidencias, meteo, el registro COMPLETO de pantallas, " +
                            "el TIEMPO COMPLEMENTARIO y la TABLA COMPARATIVA DIARIA.")
                    .setPositiveButton("Compartir") { _, _ -> compartirPdf(s) }
                    .setNeutralButton("Abrir") { _, _ -> abrirPdf(s) }
                    .setNegativeButton("Cerrar", null)
                    .show()
            } catch (e: Exception) {
                toast("No se pudo generar el expediente: " + e.message)
            }
        }
    }

    private fun verEvidencias() {
        val total = repo.totalEvidencias()
        val lista = repo.evidenciasDetalle(visorMax, 0)
        val texto = if (lista.isEmpty())
            "Aun no hay evidencias registradas.\n\n" +
                "1) Comprueba arriba que la accesibilidad este ACTIVADA (si no, boton 9).\n" +
                "2) Con la jornada ON, abre la app de Glovo unos segundos y vuelve aqui."
        else
            "Mostrando las ultimas " + lista.size + " de " + total + " pantallas registradas.\n\n" +
                lista.joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle("Evidencias registradas")
            .setMessage(texto)
            .setPositiveButton("Ver 150 mas antiguas") { _, _ ->
                visorMax += 150
                verEvidencias()
            }
            .setNegativeButton("Cerrar") { _, _ -> visorMax = 150 }
            .show()
    }

    private fun abrirAccesibilidad() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            toast("Abre Ajustes > Accesibilidad > Registro de Jornada")
        }
    }

    private fun pedirPermisos() {
        val peds = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            peds.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (peds.isNotEmpty()) ActivityCompat.requestPermissions(this, peds.toTypedArray(), 10)
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }

    private fun versionNombre(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
