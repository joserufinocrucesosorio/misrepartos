package online.soymasdigital.jornada.data

/**
 * FASE 1 - PARSER DE CAMPOS (22/09/2026)
 *
 * Convierte el TEXTO de una pantalla de la app de reparto (o de Maps) en datos
 * estructurados del pedido: id, numero, local, direcciones, cliente, pago,
 * importe, numero de productos, retrasos y "estara listo en X min".
 *
 * IMPORTANTE: NO hace OCR ni lee imagenes. El servicio de accesibilidad ya
 * entrega el texto plano de la pantalla (nodos unidos por espacios, en UNA
 * sola linea), y aqui se extraen los campos con expresiones regulares.
 *
 * Todo es "best effort": si un campo no aparece en la pantalla, queda vacio.
 * Ninguna funcion modifica nada de la app de reparto.
 */
object Parser {

    data class Campos(
        val pedido: String,
        val num: String = "",
        val local: String = "",
        val dirRecogida: String = "",
        val cliente: String = "",
        val dirEntrega: String = "",
        val pago: String = "",
        val importe: Double? = null,
        val productos: Int? = null,
        val retrasoMin: Int? = null,
        val listoMin: Int? = null
    ) {
        fun resumen(): String {
            val partes = ArrayList<String>()
            if (num.isNotEmpty()) partes.add("#" + num)
            if (local.isNotEmpty()) partes.add(local)
            if (cliente.isNotEmpty()) partes.add(cliente)
            if (dirEntrega.isNotEmpty()) partes.add(dirEntrega)
            if (pago.isNotEmpty()) partes.add(pago + (importe?.let { " " + String.format(java.util.Locale.US, "%.2f", it) + " EUR" } ?: ""))
            if (productos != null) partes.add(productos.toString() + " productos")
            if (retrasoMin != null) partes.add("+" + retrasoMin + " min retraso")
            return partes.joinToString(" | ")
        }
    }

    // ---------------- expresiones regulares (texto plano, una linea) ----------------

    /** Id de pedido de Glovo (12 digitos: 1017xxxxxxxx). */
    val RX_ID = Regex("\\b(101[0-9]{9})\\b")

    private val RX_NUM = Regex("#([0-9]{3})")

    /** "7 productos" / "1 producto". */
    private val RX_PRODUCTOS = Regex("([0-9]{1,3})\\s+productos?\\b", RegexOption.IGNORE_CASE)

    /** Cobros en efectivo: "Cobra al cliente en efectivo EUR31.17" / "Cobro en efectivo 12,50". */
    private val RX_EFECTIVO = Regex(
        "(?:[Cc]obra al cliente en efectivo|[Cc]obro en efectivo|[Pp]ago en efectivo|COBRA AL CLIENTE EN EFECTIVO)" +
                "[^0-9]{0,8}([0-9]{1,4}[.,][0-9]{2})"
    )

    /** "Cobra al cliente en efectivo EUR31.17" -> tambien con el simbolo delante. */
    private val RX_EFECTIVO2 = Regex("(?:EUR|\\u20AC)\\s?([0-9]{1,4}[.,][0-9]{2})")

    private val RX_PAGO_TOTAL = Regex("[Pp]ago total[^0-9]{0,10}([0-9]{1,4}[.,][0-9]{2})")

    private val RX_RETRASO = Regex("[Cc]on[ ]([0-9]{1,2})[ ]min(?:utos?)?[ ]de[ ]retraso")

    private val RX_LISTO = Regex("[Ee]star[\\u00e1a][ ]listo[ ]en[ ]aproximadamente[ ]([0-9]{1,2})[ ]min")

    private val RX_EMPRESA = Regex("Empresa:[ ]?([^#|]{2,45}?)(?=[ ](?:Listo|Con|Estar|#[0-9]|[0-9]+[ ]productos))")

    private val RX_EMPRESA2 = Regex("Empresa:[ ]?([^#|]{2,45}?)(?=[ ]#)")

    /** Direcciones: via + nombre + numero + codigo postal (5 cifras). */
    private val RX_DIR = Regex(
        "((?:Calle|C/|C\\.|Avenida|Avda\\.|Av\\.|Plaza|Paseo|Ctra\\.|Carretera|Camino|Ronda|Callej[oó]n|C\\.[ ]?[A-ZÁÉÍÓÚÑ])" +
                "[^,;|]{2,45}?,[ ]?[0-9]{1,4}(?:[ ]?,[ ]?[0-9]{5})?)"
    )

    /** Cliente: "101780628881 #874 * Zadith Carrasco Supermercado DIA" / "1017... - #702 -Juan Carlos Molina". */
    private val RX_CLIENTE = Regex("101[0-9]{9}[^#]{0,12}#[0-9]{3}[ ]?(?:[\\u2022\\u00b7\\-][ ]?)?([\\p{L}][\\p{L} .,'-]{2,32})")

    private val MARCAS_CLIENTE = listOf(" Pago", " productos", " Confirmar", " Cliente no", " Listo", " Con ",
            " Estar", " Pedido", " Traducir", " Calle", " Avenida", " Plaza", " Paseo", " C/", " C.", " Pedido dividido")

    private val PALABRAS_LOCAL = listOf("Supermercado", "Carrefour", "Cash", "Alcampo", "Mercadona", "DIA",
            "Asadero", "Açaí", "Padthaiwok", "Kimcakes", "Flowering", "Repsol", "Granada")

    /** El nombre del cliente llega pegado al del local: se corta con marcas, mayusculas y tope de 3 palabras. */
    private fun limpiarCliente(raw: String): String {
        var c = limpiar(raw)
        for (m in MARCAS_CLIENTE) {
            val i = c.indexOf(m, ignoreCase = true)
            if (i > 0) c = c.substring(0, i)
        }
        val out = ArrayList<String>()
        for (w in c.split(" ")) {
            if (w.isEmpty()) continue
            if (out.size >= 3) break
            if (w[0].isDigit()) break
            if (cortar(w)) break
            out.add(w)
        }
        return out.joinToString(" ").trim(' ', ',', '-', '.')
    }

    private fun cortar(w: String): Boolean {
        if (w.length > 2 && w == w.uppercase()) return true
        for (p in PALABRAS_LOCAL) if (w.startsWith(p, ignoreCase = true)) return true
        return false
    }

    // ---------------- utilidades ----------------

    private fun num(s: String?): Double? = s?.replace(',', '.')?.toDoubleOrNull()

    private fun limpiar(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().trim(',', '.', '-', '|').trim()

    /** Normaliza el texto de la pantalla (los nodos llegan unidos por espacios). */
    fun normalizar(texto: String): String = texto.replace('\u202F', ' ').replace('\u00A0', ' ').replace(Regex("\\s+"), " ")

    /**
     * ¿Esta pantalla confirma DE VERDAD la recogida de un pedido?
     * OJO: el boton "Confirmar recogida" sale en la pantalla del pedido abierto
     * desde que se acepta (aun sin recoger), asi que NO vale como senal.
     * La unica senal valida es el dialogo: «¿Confirmas que has recogido el pedido …?».
     */
    fun esRecogida(texto: String): Boolean =
        texto.contains("Confirmas que has recogido", ignoreCase = true)

    /**
     * ¿Esta pantalla confirma DE VERDAD la entrega de un pedido?
     * Solo el dialogo «¿Confirmas la entrega del pedido …?» (no el boton "Confirmar entrega").
     */
    fun esEntrega(texto: String): Boolean =
        texto.contains("Confirmas la entrega", ignoreCase = true)

    /** ¿La pantalla anuncia un pedido aceptado / listo para recoger? */
    fun esAceptacion(texto: String): Boolean =
        texto.contains("Nueva entrega aceptada", ignoreCase = true) ||
                texto.contains("Pedido aceptado correctamente", ignoreCase = true) ||
                texto.contains("Ya se puede recoger", ignoreCase = true)

    /** ¿La pantalla indica cliente no disponible? */
    fun clienteNoDisponible(texto: String): Boolean = texto.contains("Cliente no disponible", ignoreCase = true)

    /** Clasifica el pago con lo que se ve en la pantalla. */
    fun pago(texto: String): Pair<String, Double?> {
        RX_EFECTIVO.find(texto)?.let { return "efectivo" to num(it.groupValues[1]) }
        if (texto.contains("Cobra al cliente en efectivo", ignoreCase = true)) {
            RX_EFECTIVO2.find(texto)?.let { return "efectivo" to num(it.groupValues[1]) }
            return "efectivo" to null
        }
        if (texto.contains("Pagado en línea", ignoreCase = true) || texto.contains("Pagado en linea", ignoreCase = true))
            return "pagado en línea" to RX_PAGO_TOTAL.find(texto)?.groupValues?.get(1)?.let { num(it) }
        if (texto.contains("Sin pago en el punto de recogida", ignoreCase = true)) return "sin pago en el punto de recogida" to null
        if (texto.contains("Pago en el local", ignoreCase = true) || texto.contains("Pagar en el local", ignoreCase = true))
            return "pago en el local" to null
        return "" to null
    }

    /**
     * Extrae los campos de TODOS los pedidos que aparezcan en el texto.
     * Normalmente la pantalla lleva un solo pedido; la lista cubre el caso de lote.
     */
    fun parsear(textoOriginal: String): List<Campos> {
        val texto = normalizar(textoOriginal)
        if (!Regex("\\b101[0-9]{9}\\b").containsMatchIn(texto)) return emptyList()
        val ids = ArrayList<String>()
        for (m in RX_ID.findAll(texto)) if (!ids.contains(m.groupValues[1])) ids.add(m.groupValues[1])
        if (ids.isEmpty()) return emptyList()

        val (pag, imp) = pago(texto)
        val num = RX_NUM.find(texto)?.groupValues?.get(1) ?: ""
        val productos = RX_PRODUCTOS.find(texto)?.groupValues?.get(1)?.toIntOrNull()
        val retraso = RX_RETRASO.find(texto)?.groupValues?.get(1)?.toIntOrNull()
        val listo = RX_LISTO.find(texto)?.groupValues?.get(1)?.toIntOrNull()
        val empresa = (RX_EMPRESA.find(texto) ?: RX_EMPRESA2.find(texto))?.groupValues?.get(1)?.let { limpiar(it) } ?: ""
        val cliente = RX_CLIENTE.find(texto)?.groupValues?.get(1)?.let {
            var c = limpiarCliente(it)
            if (empresa.isNotEmpty() && c.endsWith(empresa)) c = c.substring(0, c.length - empresa.length).trim()
            c
        } ?: ""
        val direcciones = RX_DIR.findAll(texto).map { limpiar(it.groupValues[1]) }.toList()
        // la primera direccion que aparezca antes del nombre del cliente es la de RECOGIDA;
        // la que aparece despues (o la segunda) es la de ENTREGA
        val idxCliente = if (cliente.isEmpty()) -1 else texto.indexOf(cliente)
        var dirRecogida = ""
        var dirEntrega = ""
        for (d in direcciones) {
            val pos = texto.indexOf(d)
            if (idxCliente >= 0 && pos < idxCliente) { if (dirRecogida.isEmpty()) dirRecogida = d }
            else { if (dirEntrega.isEmpty()) dirEntrega = d }
        }
        if (dirEntrega.isEmpty() && direcciones.size > 1) dirEntrega = direcciones.last()

        val localBase = if (empresa.isNotEmpty()) empresa else localDe(texto, num)
        val out = ArrayList<Campos>()
        for (id in ids) out.add(
            Campos(
                pedido = id, num = num, local = localBase, dirRecogida = dirRecogida,
                cliente = cliente, dirEntrega = dirEntrega, pago = pag,
                importe = imp ?: RX_PAGO_TOTAL.find(texto)?.groupValues?.get(1)?.let { num(it) },
                productos = productos, retrasoMin = retraso, listoMin = listo
            )
        )
        return out
    }

    /** Nombre del local a partir de patrones de las pantallas de aceptacion. */
    private fun localDe(texto: String, num: String): String {
        Regex("(?:aceptada|aceptado|Listo)[ ]([A-ZÁÉÍÓÚÑ][\\p{L} .&'-]{2,30}?)[ ]?[0-9]{0,3}[ ]?(?:pedido|pedidos|#|EUR|\\z)")
            .find(texto)?.let { return limpiar(it.groupValues[1]) }
        return ""
    }
}
