package online.soymasdigital.jornada.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import online.soymasdigital.jornada.MainActivity
import online.soymasdigital.jornada.data.Repo

/**
 * Boton FLOTANTE para marcar las fases del pedido SIN salir de la plataforma.
 *
 * Se dibuja como ventana de tipo ACCESSIBILITY_OVERLAY (no necesita permisos extra).
 * - Burbuja pequena y arrastrable (esquina izquierda).
 * - Al pulsarla: panel minimo con el pedido activo y SOLO la fase siguiente (v1.9.7).
 * - Las fases que no necesitan datos se registran al instante (un toque).
 * - Las que piden km/min, tipo de entrega, lote, incidencia, clima o llegada a casa
 *   abren la app directamente en ese dialogo.
 * IMPORTANTE: todas las vistas se marcan con importantForAccessibility=NO para que
 * NO entren en el arbol de accesibilidad y no contaminen las evidencias.
 */
class FlujoOverlay(private val svc: AccessibilityService, private val repo: Repo) {

    private val ORDEN = listOf(
        "RECIBO_PEDIDO", "RUTA_LOCAL", "LLEGADA_PARKING", "LLEGADA_LOCAL", "RECOGIDA",
        "LLEGADA_MOTO", "SALIDA_CLIENTE", "LLEGADA_CLIENTE", "ENTREGA", "LLEGADA_MOTO_FINAL",
        "ESPERA_SIGUIENTE"
    )

    private val CORTO = mapOf(
        "RECIBO_PEDIDO" to "1 Recibo del pedido",
        "RUTA_LOCAL" to "2 Ruta al local (Maps)",
        "LLEGADA_PARKING" to "3 Llegada al parking (km/min)",
        "LLEGADA_LOCAL" to "4 Llegada al local",
        "RECOGIDA" to "5 Recibo en el local",
        "LLEGADA_MOTO" to "6 Llegada a la moto",
        "SALIDA_CLIENTE" to "7 Salida al cliente (Maps)",
        "LLEGADA_CLIENTE" to "8 Llegada al cliente (km/min)",
        "ENTREGA" to "9 Entrega del pedido",
        "LLEGADA_MOTO_FINAL" to "10 Vuelta a la moto (cierra)",
        "ESPERA_SIGUIENTE" to "11 Espera sgte. pedido"
    )

    /** Fases que se pueden marcar de un solo toque desde el flotante. */
    private val DIRECTAS = setOf(
        "RECIBO_PEDIDO", "RUTA_LOCAL", "LLEGADA_LOCAL", "RECOGIDA", "LLEGADA_MOTO",
        "SALIDA_CLIENTE", "ENTREGA", "ESPERA_SIGUIENTE"
    )

    private var wm: WindowManager? = null
    private var burbuja: View? = null
    private var panel: View? = null
    private var pedidoSel: String? = null

    private fun prefs() = svc.getSharedPreferences("jornada_prefs", Context.MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radio: Float, borde: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = radio
        g.setStroke(dp(1), borde)
        return g
    }

    fun instalar() {
        if (burbuja != null) return
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val b = TextView(svc)
        b.text = "FLUJO"
        b.setTextColor(Color.parseColor("#FFD400"))
        b.typeface = Typeface.DEFAULT_BOLD
        b.textSize = 13f
        b.setPadding(dp(16), dp(11), dp(16), dp(11))
        b.background = rounded(Color.parseColor("#0F172A"), dp(26).toFloat(), Color.parseColor("#FFD400"))
        b.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(4)
        lp.y = dp(300)

        var downX = 0f; var downY = 0f; var iniX = 0; var iniY = 0; var movido = false
        b.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; iniX = lp.x; iniY = lp.y; movido = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (Math.abs(dx) > 14 || Math.abs(dy) > 14) movido = true
                    lp.x = iniX + dx; lp.y = iniY + dy
                    try { w.updateViewLayout(b, lp) } catch (e: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!movido) alternarPanel(); true }
                else -> false
            }
        }
        try {
            w.addView(b, lp)
            burbuja = b
        } catch (e: Exception) {
            Log.w("JornadaAcc", "no se pudo poner la burbuja: " + e.message)
        }
    }

    fun desinstalar() {
        cerrarPanel()
        val w = wm ?: return
        burbuja?.let { try { w.removeView(it) } catch (e: Exception) { } }
        burbuja = null
    }

    /** Oculta burbuja y panel (para que no salgan en una captura de pantalla). */
    fun ocultar() {
        burbuja?.visibility = View.INVISIBLE
        panel?.visibility = View.INVISIBLE
    }

    fun mostrar() {
        burbuja?.visibility = View.VISIBLE
        panel?.visibility = View.VISIBLE
    }

    private fun alternarPanel() {
        if (panel != null) { cerrarPanel(); return }
        abrirPanel()
    }

    private fun cerrarPanel() {
        val w = wm ?: return
        panel?.let { try { w.removeView(it) } catch (e: Exception) { } }
        panel = null
    }

    private fun fila(t: String, size: Float, bold: Boolean, color: Int): TextView {
        val v = TextView(svc)
        v.text = t
        v.textSize = size
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(color)
        v.setPadding(dp(2), dp(3), dp(2), dp(3))
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return v
    }

    private fun abrirPanel() {
        val w = wm ?: return
        val jid = try { repo.jornadaAbierta() } catch (e: Exception) { null }
        val cont = LinearLayout(svc)
        cont.orientation = LinearLayout.VERTICAL
        cont.setPadding(dp(10), dp(8), dp(10), dp(8))
        cont.background = rounded(Color.parseColor("#F8FAFC"), dp(12).toFloat(), Color.parseColor("#0F172A"))
        cont.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        if (jid == null) {
            cont.addView(fila("Sin jornada abierta", 13f, true, Color.parseColor("#7F1D1D")))
            cont.addView(fila("Abre la jornada con el boton 1 de la app.", 11.5f, false, Color.parseColor("#334155")))
        } else {
            val abiertos = try { repo.pedidosSinCerrar(jid) } catch (e: Exception) { emptyList() }
            if (abiertos.isEmpty()) {
                cont.addView(fila("Sin pedidos abiertos", 13f, true, Color.parseColor("#7F1D1D")))
                cont.addView(fila("La app aun no ha leido ningun pedido de la pantalla.", 11.5f, false, Color.parseColor("#334155")))
            } else {
                val act = try { prefs().getString("activo_" + jid, null) } catch (e: Exception) { null }
                if (pedidoSel == null || abiertos.none { it.id == pedidoSel })
                    pedidoSel = if (act != null && abiertos.any { it.id == act }) act else abiertos.last().id
                val ped = abiertos.first { it.id == pedidoSel }
                cont.addView(fila((if (abiertos.size > 1) "ENTREGA DOBLE - " else "") + "PEDIDO ACTIVO " + ped.id +
                        (if (ped.num.isNotEmpty()) " #" + ped.num else "") +
                        "   (" + repo.estadoPedido(ped) + ")", 12f, true, Color.parseColor("#7C2D12")))
                if (abiertos.size > 1) {
                    val sw = LinearLayout(svc)
                    sw.orientation = LinearLayout.HORIZONTAL
                    for (p in abiertos) {
                        val lbl = (if (p.id == ped.id) "[ACTIVO] " else "[ver] ") +
                                (if (p.num.isNotEmpty()) "#" + p.num else p.id.takeLast(4))
                        val b = fila(lbl, 12f, p.id == ped.id, Color.parseColor("#0F172A"))
                        b.background = rounded(
                                if (p.id == ped.id) Color.parseColor("#FDE68A") else Color.parseColor("#E2E8F0"),
                                dp(8).toFloat(), Color.parseColor("#94A3B8"))
                        b.setPadding(dp(6), dp(8), dp(6), dp(8))
                        b.setOnClickListener {
                            pedidoSel = p.id
                            try { prefs().edit().putString("activo_" + jid, p.id).apply() } catch (e: Exception) { }
                            refrescarPanel()
                        }
                        sw.addView(b)
                    }
                    cont.addView(sw)
                }
                // v1.8 - modo LOTE (pedidos dobles/triples), agrupado POR LOCAL
                val mismoLocal = try { repo.pedidosMismoLocal(jid, ped.id) } catch (e: Exception) { emptyList() }
                val loteSinRecogida = mismoLocal.filter { o ->
                    try { repo.fases(jid, o.id).none { f2 -> f2.first == "RECOGIDA" } } catch (e: Exception) { false }
                }
                val faltaActual = try { repo.fases(jid, ped.id).none { f2 -> f2.first == "RECOGIDA" } } catch (e: Exception) { false }
                val pendRecogida = (if (faltaActual) 1 else 0) + loteSinRecogida.size
                if (pendRecogida >= 2) {
                    val bl = fila("RECOGIDA DEL LOTE (" + pendRecogida + " pedidos mismo local)", 12f, true, Color.parseColor("#7C2D12"))
                    bl.background = rounded(Color.parseColor("#FEE2E2"), dp(8).toFloat(), Color.parseColor("#FCA5A5"))
                    bl.setPadding(dp(6), dp(8), dp(6), dp(8))
                    bl.setOnClickListener {
                        var n = 0
                        if (faltaActual) { if (repo.registrarFase(jid, ped.id, "RECOGIDA", "auto lote (mismo local)")) n++ }
                        for (o in loteSinRecogida) { if (repo.registrarFase(jid, o.id, "RECOGIDA", "auto lote (mismo local)")) n++ }
                        try { Toast.makeText(svc, "RECOGIDA del lote: " + n + " pedido(s), sin repetir viaje", Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
                        refrescarPanel()
                    }
                    cont.addView(bl)
                }
                if (abiertos.size > 1) {
                    val bs = fila("SIGUIENTE CLIENTE DEL LOTE (cambiar)", 12f, true, Color.parseColor("#1E3A8A"))
                    bs.background = rounded(Color.parseColor("#DBEAFE"), dp(8).toFloat(), Color.parseColor("#93C5FD"))
                    bs.setPadding(dp(6), dp(8), dp(6), dp(8))
                    bs.setOnClickListener {
                        val i = abiertos.indexOfFirst { it.id == ped.id }
                        val nxt = abiertos[(i + 1) % abiertos.size]
                        pedidoSel = nxt.id
                        try { prefs().edit().putString("activo_" + jid, nxt.id).apply() } catch (e: Exception) { }
                        refrescarPanel()
                    }
                    cont.addView(bs)
                }
                val f = try { repo.fasesEfectivas(jid, ped.id) } catch (e: Exception) { emptyList() }
                val hechas = f.associateBy({ it.first }, { it.second })
                val sig = ORDEN.firstOrNull { !hechas.containsKey(it) && repo.motivoBloqueo(jid, ped.id, it) == null }
                // v1.9.7: SOLO el paso siguiente, en UN boton grande (Rufino: el desplegable completo
                // tapaba la pantalla). Al pulsarlo se registra la fase y aparece el paso siguiente.
                val paso = TextView(svc)
                paso.text = if (sig != null) "SIGUIENTE: " + (CORTO[sig] ?: sig) else "Pedido completo"
                paso.textSize = 16f
                paso.typeface = Typeface.DEFAULT_BOLD
                paso.setTextColor(Color.parseColor(if (sig != null) "#14532D" else "#334155"))
                paso.setPadding(dp(10), dp(16), dp(10), dp(16))
                paso.background = rounded(
                        Color.parseColor(if (sig != null) "#BBF7D0" else "#E2E8F0"),
                        dp(10).toFloat(), Color.parseColor(if (sig != null) "#16A34A" else "#94A3B8"))
                paso.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (sig != null) paso.setOnClickListener { pulsarFase(sig) }
                cont.addView(paso)
            }
        }

        val cerrar = fila("Cerrar", 12.5f, true, Color.parseColor("#7F1D1D"))
        cerrar.setPadding(dp(8), dp(10), dp(8), dp(10))
        cerrar.setOnClickListener { cerrarPanel() }
        cont.addView(cerrar)

        val sc = ScrollView(svc)
        sc.addView(cont)
        sc.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        val lp = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(4)
        lp.y = dp(90)
        try {
            w.addView(sc, lp)
            panel = sc
        } catch (e: Exception) {
            Log.w("JornadaAcc", "no se pudo abrir el panel: " + e.message)
        }
    }

    private fun refrescarPanel() {
        cerrarPanel()
        abrirPanel()
    }

    /** Al detectar un pedido nuevo, el flotante se pone en el mas reciente. */
    fun pedidoActivoAlFrente() {
        pedidoSel = null
        if (panel != null) refrescarPanel()
    }

    /** Registra una fase directa (con la nota de salto, igual que en la app). */
    private fun pulsarFase(cod: String) {
        val jid = try { repo.jornadaAbierta() } catch (e: Exception) { null }
        val pid = pedidoSel
        if (jid == null || pid == null) { cerrarPanel(); return }
        // v1.9.2: fuerza el ORDEN del flujo (no reabrir ruta/parking/local/recogida si ya hay RECOGIDA)
        val motivo = try { repo.motivoBloqueo(jid, pid, cod) } catch (e: Exception) { null }
        if (motivo != null) {
            // v1.9.5: AVISA pero NO bloquea (Jose: "no bloquees el flujo 6"): se registra igual y queda constancia
            try { Toast.makeText(svc, motivo + " Se registra igual.", Toast.LENGTH_LONG).show() } catch (e: Exception) { }
        }
        // Si la fase ya estaba marcada a mano, no se duplica: se avisa y se refresca
        val ya = try { repo.fases(jid, pid).firstOrNull { it.first == cod } } catch (e: Exception) { null }
        if (ya != null) {
            val h = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ya.second))
            try { Toast.makeText(svc, "Ya estaba marcada a las " + h + ". No se duplica.", Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
            refrescarPanel()
            return
        }
        if (cod !in DIRECTAS) {
            cerrarPanel()
            val i = Intent(svc, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("accion", "fase")
                .putExtra("pedido", pid)
                .putExtra("fase", cod)
            svc.startActivity(i)
            return
        }
        var nota = ""
        if (cod == "RUTA_LOCAL" || cod == "SALIDA_CLIENTE") {
            val est = try { repo.estimacionMaps(jid, pid, cod == "RUTA_LOCAL") } catch (e: Exception) { null }
            // v1.9.5: si no hay estimacion de Maps NO se escribe un km= con la lectura del odometro
            // (dejaba el km del tramo a 0). La lectura va en odom= y el tramo se calcula al llegar.
            val kmCont = try { repo.ultimoCuentakm(jid)?.let { textoKm(it) } } catch (e: Exception) { null }
            nota = if (est != null) "km=" + textoKm(est.third) + " min=" + est.second + " (estimado Maps)"
                   else if (kmCont != null) "odom=" + kmCont + " (continuidad odometro, sin km de tramo)"
                   else "(sin estimacion de Maps)"
        }
        val salto = notaSalto(jid, pid, cod)
        val n = when {
            nota.isEmpty() && salto.isEmpty() -> ""
            nota.isEmpty() -> salto
            salto.isEmpty() -> nota
            else -> nota + " | " + salto
        }
        // v1.8: la app rechaza el mismo paso con el mismo km en menos de 30 s
        val ok = try { repo.registrarFase(jid, pid, cod, n) } catch (e: Exception) { false }
        if (!ok) {
            try { Toast.makeText(svc, "Paso duplicado: " + (CORTO[cod] ?: cod) + " con el mismo km. No se repite.", Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
            refrescarPanel()
            return
        }
        try { Toast.makeText(svc, "Fase guardada: " + (CORTO[cod] ?: cod), Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
        // Captura automatica del hito (con la burbuja oculta) y despues se reabre el panel
        cerrarPanel()
        val s = svc as? GlovoAccessibilityService
        var hecho = false
        val reabrir: () -> Unit = { if (!hecho) { hecho = true; refrescarPanel() } }
        if (s != null && android.os.Build.VERSION.SDK_INT >= 30)
            s.capturar("fase " + (CORTO[cod] ?: cod), reabrir)
        else reabrir()
    }

    private fun textoKm(v: Double): String = String.format(java.util.Locale.US, "%.1f", v).replace('.', ',')

    private fun notaSalto(jid: Long, pedido: String, codigo: String): String {
        val i = ORDEN.indexOf(codigo)
        if (i <= 0) return ""
        val ya = try { repo.fasesEfectivas(jid, pedido).map { it.first }.toSet() } catch (e: Exception) { return "" }
        val faltan = ORDEN.take(i).filter { it !in ya }
        return if (faltan.isEmpty()) "" else "sin registrar: " + faltan.joinToString(", ")
    }
}
