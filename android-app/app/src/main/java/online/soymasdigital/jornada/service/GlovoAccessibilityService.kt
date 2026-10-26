package online.soymasdigital.jornada.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import online.soymasdigital.jornada.data.Repo
import java.io.File
import java.io.FileOutputStream

/**
 * Servicio de accesibilidad: SOLO observa. Detecta la pantalla de la app de reparto/mapas
 * y registra una evidencia (tipo + texto) con la hora real. No pulsa nada.
 *
 * v0.1.6: (a) lista blanca de aplicaciones (solo reparto y mapas) y (b) solo registra
 * mientras hay una jornada abierta.
 */
class GlovoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JornadaAcc"
        private const val VENTANA_MS = 90_000L
        // v1.8: antirrebote de hitos. Antes era permanente (vistos) y una misma pantalla
        // reabierta luego NO se volvia a registrar: de ahi que solo saliera 1 CARTERA_BALANCE.
        private const val VENTANA_HITO_MS = 60_000L

        /** Instancia viva del servicio (para pedirle una captura desde la app o el flotante). */
        @Volatile
        var instancia: GlovoAccessibilityService? = null
            private set

        /** Captura automatica de pantalla en cada hito (se puede apagar desde la app). */
        @Volatile
        var capturaAuto: Boolean = true

        /** Tipos de evidencia que se consideran HITO y por tanto llevan foto automatica. */
        private val CAPTURA_TIPOS = setOf(
            "RECOGIDA", "ENTREGA", "PEDIDO_ACEPTADO", "POSIBLE_CLIENTE_AUSENTE",
            "CARTERA_BALANCE", "SESION", "HORARIO", "PAGO_LOCAL", "PRODUCTOS", "PEDIDO_DETALLE",
            // v1.8: incidencias automaticas con pantallazo y hora
            "PEDIDO_REASIGNADO", "PEDIDO_CANCELADO",
            // v1.9.4: pantallazo automatico de Google Maps (plan de ruta y llegada al destino)
            "MAPS_PLAN", "MAPS_LLEGADA"
        )

        // Solo se registra lo que ocurre en estas apps (R6: el movil es solo para repartir)
        private val PAQUETES_OK = setOf(
            "com.logistics.rider.glovo",
            "com.google.android.apps.maps"
        )
    }

    private val ultimoHito = HashMap<String, Long>()
    private val ultimaVentana = HashMap<String, Long>()
    private val repo by lazy { Repo(this) }
    private val overlay by lazy { FlujoOverlay(this, repo) }
    @Volatile private var capturando = false
    @Volatile private var jornadaAbierta = false
    @Volatile private var ultimoChequeo = 0L
    @Volatile private var jornadaId: Long? = null

    private fun hayJornada(): Boolean {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoChequeo > 15_000L) {
            ultimoChequeo = ahora
            jornadaId = try { repo.jornadaAbierta() } catch (e: Exception) { null }
            jornadaAbierta = jornadaId != null
        }
        return jornadaAbierta
    }

    /**
     * FASE 1 + FASE 2: con el TEXTO de la pantalla (sin OCR y sin tocar nada), extrae
     * los datos del pedido y crea/actualiza su flujo automaticamente.
     * Solo se ejecuta si el texto contiene un id de pedido (1017xxxxxxxx).
     */
    private fun autoFlujo(texto: String, ts: Long) {
        if (!Regex("\\b101[0-9]{9}\\b").containsMatchIn(texto)) return
        val jid = jornadaId ?: return
        try {
            val n = repo.autoFlujo(jid, texto, ts)
            if (n > 0) Log.i(TAG, "autoFlujo: $n novedades registradas")
        } catch (e: Exception) {
            Log.w(TAG, "autoFlujo: " + e.message)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in PAQUETES_OK) return          // fuera de reparto/mapas no se registra nada
        if (!hayJornada()) return                // fuera de jornada, tampoco

        val texto = recolectarTexto(e)
        val clase = e.className?.toString() ?: ""

        // 1) evidencia de hito (si la pantalla coincide con un tipo conocido)
        val tipo = clasificar(texto)
        if (tipo != null) {
            val ahora = System.currentTimeMillis()
            val clave = tipo + "|" + texto.take(160)
            val previo = ultimoHito[clave]
            if (previo == null || ahora - previo >= VENTANA_HITO_MS) {
                if (ultimoHito.size > 800) ultimoHito.clear()
                ultimoHito[clave] = ahora
                Log.i(TAG, "evidencia $tipo de $pkg")
                repo.registrarEvidencia(pkg, tipo, texto.take(4000), clase)
                autoFlujo(texto, System.currentTimeMillis())
                if (tipo == "PAGO_LOCAL") guardarPagoLocal(texto)
                if (tipo in CAPTURA_TIPOS) capturarHito(tipo)
                if (tipo == "PEDIDO_ACEPTADO") {
                    val t2 = texto.replace('\u202F', ' ').replace('\u00A0', ' ')
                    val n = Regex("(\\d+)\\s+nuevas? entregas? aceptadas?").find(t2)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("(\\d+)\\s+pedidos?").find(t2)?.groupValues?.get(1)?.toIntOrNull()
                    if (n != null && n >= 2) avisarLote(n)
                }
            }
            return
        }

        // 2) evidencia generica de "pantalla vista" (solo al cambiar de ventana, con antirrebote)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val clave = pkg + "|" + clase
            val ahora = System.currentTimeMillis()
            val previo = ultimaVentana[clave]
            if (previo == null || ahora - previo > VENTANA_MS) {
                ultimaVentana[clave] = ahora
                Log.i(TAG, "pantalla vista $pkg / $clase")
                repo.registrarEvidencia(pkg, "PANTALLA", texto.take(4000), clase)
                autoFlujo(texto, System.currentTimeMillis())
            }
        }
    }

    /**
     * v1.5: cuando la pantalla dice "Pagar en efectivo en el establecimiento X,XX EUR", guarda el importe
     * en el pedido para poder calcular el EUR/km de la factura de cobro.
     */
    private fun guardarPagoLocal(texto: String) {
        try {
            val t = texto.replace('\u202F', ' ').replace('\u00A0', ' ')
            val pid = Regex("(101\\d{9})").find(t)?.groupValues?.get(1) ?: return
            val imp = Regex("establecimiento\\s+([0-9]{1,3}[.,][0-9]{2})\\s*\u20AC").find(t)?.groupValues?.get(1)?.replace(',', '.')
                ?: return
            repo.guardarDatoPedido(pid, "pago_local", imp, System.currentTimeMillis())
            Log.i(TAG, "pago en el local: " + pid + " -> " + imp)
        } catch (e: Exception) { }
    }

    /** Aviso de pedido nuevo en lote: avisa y pone el pedido mas reciente al frente del flotante. */
    private fun avisarLote(n: Int) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    android.widget.Toast.makeText(this,
                        "Pedido nuevo en el lote (total " + n + " pedidos). Cambia el pedido ACTIVO en la burbuja FLUJO.",
                        android.widget.Toast.LENGTH_LONG).show()
                } catch (e: Exception) { }
            }
            overlay.pedidoActivoAlFrente()
        } catch (e: Exception) { }
    }

    override fun onInterrupt() { }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "servicio de accesibilidad conectado (API " + Build.VERSION.SDK_INT + ")")
        instancia = this
        try { overlay.instalar() } catch (e: Exception) { Log.w(TAG, "overlay: " + e.message) }
    }

    override fun onDestroy() {
        if (instancia === this) instancia = null
        try { overlay.desinstalar() } catch (e: Exception) { }
        super.onDestroy()
    }

    /**
     * v1.9.3: al abrir una jornada nueva / pulsar INICIO GUIADO se fuerza a releer el estado de
     * jornada YA (sin esperar la ventana de 15 s) y se limpia el antirrebote de hitos, para que el
     * arranque vuelva a registrar HORARIO/CARTERA_BALANCE/SESION aunque la pantalla se repita.
     */
    fun reiniciarParaInicio() {
        try {
            jornadaId = repo.jornadaAbierta()
            jornadaAbierta = jornadaId != null
        } catch (e: Exception) {
            jornadaId = null
            jornadaAbierta = false
        }
        ultimoChequeo = System.currentTimeMillis()
        ultimoHito.clear()
        ultimaVentana.clear()
    }

    /** Hitos que interesan al arranque/cierre de la jornada (los guarda observarPantalla). */
    private val TIPOS_CLAVE = listOf("HORARIO", "CARTERA_BALANCE", "SESION")

    /**
     * v1.9.3: observa la pantalla AHORA mismo (sin depender de eventos de ventana) y guarda la
     * evidencia de los hitos clave presentes (HORARIO, CARTERA_BALANCE, SESION) + el pantallazo.
     *
     * Por que: el arranque guiado solo sacaba el pantallazo; HORARIO/CARTERA_BALANCE/SESION
     * dependian de que llegara un evento de ventana, del cache de jornada (15 s) y del antirrebote
     * (60 s). Si el evento no llegaba (pestana ya abierta, cache, servicio reconectado), el inicio
     * no guardaba NADA y "leerHorarioDePantalla()"/"leerCarteraDePantalla()" no encontraban la
     * evidencia. Al observar en el momento del paso guiado se garantiza el registro.
     */
    fun observarPantalla(etiqueta: String, alTerminar: (() -> Unit)? = null): List<String> {
        val guardados = ArrayList<String>()
        try {
            ultimoChequeo = 0L                                  // fuerza a releer la jornada ya
            val jid = try { repo.jornadaAbierta() } catch (e: Exception) { null }
            jornadaId = jid
            jornadaAbierta = jid != null
            if (jid != null) {
                val p = textoPantallaActual()
                if (p != null && p.third.isNotBlank()) {
                    val (pkg, clase, texto) = p
                    val t = sin(texto)
                    for (tipo in TIPOS_CLAVE) {
                        if (coincideClave(tipo, t)) {
                            repo.registrarEvidencia(pkg, tipo, texto.take(4000), clase)
                            // que la via automatica no repita el mismo hito en su ventana
                            ultimoHito[tipo + "|" + texto.take(160)] = System.currentTimeMillis()
                            guardados.add(tipo)
                            Log.i(TAG, "observarPantalla: " + tipo + " de " + pkg)
                        }
                    }
                    if (guardados.isNotEmpty()) autoFlujo(texto, System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "observarPantalla: " + e.message)
        }
        capturar(etiqueta, alTerminar)
        return guardados
    }

    /**
     * Texto de la ventana de reparto/mapas AHORA. No usa solo rootInActiveWindow: si el flotante
     * (nuestra app) tiene el foco, se busca entre todas las ventanas la de un paquete permitido.
     */
    private fun textoPantallaActual(): Triple<String, String, String>? {
        try {
            val activa = rootInActiveWindow
            if (activa != null) {
                val p = activa.packageName?.toString() ?: ""
                if (p in PAQUETES_OK) {
                    val sb = StringBuilder()
                    recolectar(activa, sb)
                    if (sb.isNotBlank()) return Triple(p, activa.className?.toString() ?: "", sb.toString().trim())
                }
            }
            for (w in windows) {
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: ""
                if (p !in PAQUETES_OK) continue
                val sb = StringBuilder()
                recolectar(r, sb)
                if (sb.isNotBlank()) return Triple(p, r.className?.toString() ?: "", sb.toString().trim())
            }
        } catch (e: Exception) {
            Log.w(TAG, "textoPantallaActual: " + e.message)
        }
        return null
    }

    /** Palabras clave de los hitos del arranque/cierre (las mismas que usa clasificar()). */
    private fun coincideClave(tipo: String, t: String): Boolean = when (tipo) {
        "CARTERA_BALANCE" -> t.contains("saldo actual") || t.contains("transacciones recientes") ||
            t.contains("saldo disponible") || t.contains("saldo de la cartera") ||
            t.contains("saldo pendiente de liquidar") || t.contains("mi cartera")
        "HORARIO" -> t.contains("mis sesiones") || t.contains("sesiones disponibles") || t.contains("sesion reservada")
        "SESION" -> t.contains("buscando pedidos") || t.contains("sesion ha empezado") || t.contains("en linea") ||
            t.contains("fuera de linea") || t.contains("sesion finalizara") || t.contains("sesion activa") ||
            t.contains("sesion finalizada") || t.contains("estas en linea") || t.contains("te has puesto en linea")
        else -> false
    }

    /**
     * CAPTURA de pantalla (API 30+). Guarda un JPEG con la hora y deja una evidencia
     * tipo CAPTURA. Es una accion MANUAL: solo se hace cuando el repartidor la pide.
     */
    /** Captura automatica cuando se detecta un hito (si esta activada). */
    fun capturarHito(etiqueta: String) {
        if (capturaAuto) capturar(etiqueta)
    }

    /**
     * v1.9.2: captura BLINDADA. Envuelve todo en try/catch, garantiza que el overlay SIEMPRE se vuelve
     * a mostrar y que el callback se invoca siempre (antes, si takeScreenshot fallaba o el servicio no
     * podia capturar, el flotante se quedaba oculto y el aviso "no puedo leer cartera/sesiones" salia
     * sin pantallazo). Ademas reintenta cuando Android responde 3 (dos capturas demasiado seguidas).
     */
    fun capturar(etiqueta: String, alTerminar: (() -> Unit)? = null, intentos: Int = 0) {
        if (Build.VERSION.SDK_INT < 30) {
            Log.w(TAG, "captura: requiere Android 11 o superior")
            try { android.widget.Toast.makeText(this, "La captura necesita Android 11 o superior.", android.widget.Toast.LENGTH_LONG).show() } catch (e: Exception) { }
            alTerminar?.invoke()
            return
        }
        if (capturando) {
            if (intentos >= 4) {
                Log.w(TAG, "captura: ocupado, se abandona (" + etiqueta + ")")
                alTerminar?.invoke()
                return
            }
            android.os.Handler(mainLooper).postDelayed({ capturar(etiqueta, alTerminar, intentos + 1) }, 600L)
            return
        }
        capturando = true
        try { overlay.ocultar() } catch (e: Exception) { }
        // Pequena espera para que la burbuja y los dialogos desaparezcan antes de capturar el fotograma
        try {
            android.os.Handler(mainLooper).postDelayed({ lanzarCaptura(etiqueta, alTerminar) }, 450L)
        } catch (e: Exception) {
            Log.w(TAG, "captura: no se pudo programar (" + e.message + ")")
            capturando = false
            try { overlay.mostrar() } catch (e2: Exception) { }
            alTerminar?.invoke()
        }
    }

    private fun lanzarCaptura(etiqueta: String, alTerminar: (() -> Unit)?) {
        var terminado = false
        val finalizar: () -> Unit = {
            if (!terminado) {
                terminado = true
                capturando = false
                try { overlay.mostrar() } catch (e: Exception) { }
                try { alTerminar?.invoke() } catch (e: Exception) { }
            }
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val duras = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        val blando = duras?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (blando != null) {
                            val dir = File(getExternalFilesDir(null), "capturas").apply { mkdirs() }
                            val f = File(dir, "captura_" + screenshot.timestamp + ".jpg")
                            FileOutputStream(f).use { blando.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                            repo.registrarEvidencia("captura", "CAPTURA",
                                    "Captura de pantalla ($etiqueta) | archivo: " + f.name, f.absolutePath)
                            Log.i(TAG, "captura guardada: " + f.name)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "captura fallida: " + e.message)
                    }
                    finalizar()
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "captura: error " + errorCode)
                    // 3 = ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT: dos capturas demasiado seguidas.
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !terminado) {
                        capturando = false
                        try { overlay.mostrar() } catch (e: Exception) { }
                        android.os.Handler(mainLooper).postDelayed({ capturar(etiqueta, alTerminar) }, 900L)
                        return
                    }
                    try { android.widget.Toast.makeText(this@GlovoAccessibilityService, "No se pudo capturar la pantalla (error " + errorCode + "). Revisa la accesibilidad.", android.widget.Toast.LENGTH_LONG).show() } catch (e: Exception) { }
                    finalizar()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "captura: takeScreenshot lanzo excepcion: " + e.message)
            finalizar()
        }
    }

    private fun recolectarTexto(e: AccessibilityEvent): String {
        val txt = StringBuilder()
        val raiz = rootInActiveWindow
        if (raiz != null) recolectar(raiz, txt)
        if (txt.isBlank()) {
            for (s in e.text ?: emptyList<CharSequence>()) txt.append(s).append(' ')
        }
        return txt.toString().trim()
    }

    private fun recolectar(nodo: android.view.accessibility.AccessibilityNodeInfo?, sb: StringBuilder) {
        if (nodo == null || sb.length > 6000) return
        val t = nodo.text?.toString()
        if (!t.isNullOrBlank()) sb.append(t).append(' ')
        for (i in 0 until nodo.childCount) recolectar(nodo.getChild(i), sb)
    }

    private fun sin(t: String): String {
        val sb = StringBuilder()
        for (c in t.lowercase()) {
            when (c) {
                'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
                'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', ' ' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun clasificar(textoOriginal: String): String? {
        val t = sin(textoOriginal)
        return when {
            t.contains("marcado el pedido como reenviado") || t.contains("se ha reasignado") ||
                t.contains("pedido reasignado") -> "PEDIDO_REASIGNADO"
            // v1.8: cancelacion de pedido (aviso "Se ha cancelado la entrega...")
            t.contains("se ha cancelado la entrega") || t.contains("se ha cancelado el pedido") ||
                t.contains("pedido cancelado") || t.contains("ha cancelado el pedido") -> "PEDIDO_CANCELADO"
            // OJO: el boton/opcion "Cliente no disponible" aparece EN LA PANTALLA DEL PEDIDO abierto
            // (junto a "Confirmar entrega"), asi que NO vale como senal por si solo (falso positivo).
            (t.contains("el cliente no esta disponible") || t.contains("cliente esta ausente") || t.contains("cliente ausente") ||
                t.contains("cliente no disponible")) &&
                !t.contains("confirmar entrega") && !t.contains("confirmar recogida") -> "POSIBLE_CLIENTE_AUSENTE"
            // v1.8: etiquetas ampliadas de la Cartera (antes solo "saldo actual"/"transacciones recientes")
            t.contains("saldo actual") || t.contains("transacciones recientes") || t.contains("saldo disponible") ||
                t.contains("saldo de la cartera") || t.contains("saldo pendiente de liquidar") || t.contains("mi cartera") -> "CARTERA_BALANCE"
            // Confirmaciones REALES: solo el dialogo (no el boton de la pantalla del pedido)
            t.contains("confirmas que has recogido") -> "RECOGIDA"
            t.contains("confirmas la entrega") -> "ENTREGA"
            // Pago en el local (deduce de cartera) y detalle de productos/precios: pantallas con pedido
            t.contains("pago en el local") || t.contains("pagado en el local") || t.contains("pagar en el local") ||
                t.contains("pago en tienda") || t.contains("pagar en tienda") ||
                t.contains("pagar en efectivo en el establecimiento") || t.contains("pago en el establecimiento") -> "PAGO_LOCAL"
            t.contains("productos del pedido") || t.contains("precio del producto") || t.contains("detalle del pedido") -> "PRODUCTOS"
            t.contains("nueva entrega aceptada") || t.contains("nuevas entregas aceptadas") || t.contains("pedido aceptado") -> "PEDIDO_ACEPTADO"
            // Pantalla de un pedido abierto con su boton pendiente ("Confirmar recogida"/"Confirmar entrega")
            // o aviso "Ya se puede recoger": NO es un hito, solo evidencia neutra (sin captura).
            t.contains("confirmar recogida") || t.contains("confirmar entrega") ||
                t.contains("ya se puede recoger") -> "PEDIDO_DETALLE"
            t.contains("buscando pedidos") || t.contains("sesion ha empezado") || t.contains("en linea") || t.contains("fuera de linea") ||
                t.contains("sesion finalizara") || t.contains("sesion activa") || t.contains("sesion finalizada") ||
                t.contains("estas en linea") || t.contains("te has puesto en linea") -> "SESION"
            t.contains("mis sesiones") || t.contains("sesiones disponibles") || t.contains("sesion reservada") -> "HORARIO"
            t.contains("oportunidades") || t.contains("reto") -> "RETOS"
            t.contains("ganancias") -> "GANANCIAS"
            t.contains("estara listo") || t.contains("retraso") -> "PEDIDO_ESTADO"
            t.contains("llegando a") -> "MAPS_LLEGADA"
            t.contains("a iniciar") || t.contains("la ruta mas rapida") -> "MAPS_PLAN"
            t.contains("gira") || t.contains("continua") || t.contains("despues") -> "MAPS_NAV"
            else -> null
        }
    }
}
