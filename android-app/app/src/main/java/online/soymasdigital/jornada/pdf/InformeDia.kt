package online.soymasdigital.jornada.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import online.soymasdigital.jornada.data.Repo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * INFORME DIARIO EN PDF (v1.9.9), generado en el propio movil con android.graphics.pdf.PdfDocument.
 *
 * Una jornada = un informe A4: cabecera, resumen, tabla de pedidos, tiempos por pedido,
 * km por tramo, incidencias/notas y caja. Los pedidos se agrupan en LOTES con la misma
 * regla que la app: mismo local y RECIBO_PEDIDO a 60 s o menos.
 *
 * v2.0.0: se guarda en la carpeta que el usuario elige con el selector del sistema
 * (Storage Access Framework) y, si no hay ninguna elegida, en la carpeta privada
 * /sdcard/Android/data/<paquete>/files/informes/. NUNCA se sobrescribe: si el nombre
 * ya existe se anade _2, _3...
 */
object InformeDia {

    private const val ANCHO = 515f
    private const val IZQ = 40f
    private const val DER = 555f

    private val FECHA_DB = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val FECHA_VER = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private val HORA = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---------------- API ----------------

    /** Genera el informe de la jornada [jid] y devuelve donde quedo el PDF. No sobrescribe. */
    fun generar(ctx: Context, jid: Long): SalidaPdf.Salida {
        val repo = Repo(ctx)
        val j = repo.jornada(jid) ?: throw IllegalStateException("Jornada " + jid + " no encontrada")

        val estado = repo.estadoJornada(jid)
        val pedidos = repo.pedidos(jid)
        val datos = LinkedHashMap<String, Map<String, String>>()
        for (p in pedidos) datos[p.id] = repo.datosPedido(p.id)
        val fases = LinkedHashMap<String, Map<String, Pair<Long, String>>>()
        for (p in pedidos) {
            val m = LinkedHashMap<String, Pair<Long, String>>()
            for (f in repo.fasesEfectivas(jid, p.id)) m[f.first] = f.second to f.third
            fases[p.id] = m
        }

        fun ts(id: String, paso: String): Long? = fases[id]?.get(paso)?.first
        fun nota(id: String, paso: String): String = fases[id]?.get(paso)?.second ?: ""
        fun local(id: String): String {
            val p = pedidos.first { it.id == id }
            return datos[id]?.get("local")?.takeIf { it.isNotEmpty() } ?: p.local
        }

        // ---- Lotes: mismo local y RECIBO_PEDIDO a 60 s o menos (regla de la app) ----
        val reciboDe = HashMap<String, Long>()
        for (p in pedidos) reciboDe[p.id] = ts(p.id, "RECIBO_PEDIDO") ?: p.primera
        val ordenados = pedidos.sortedBy { reciboDe[it.id] ?: it.primera }
        val asignado = HashSet<String>()
        val loteDe = HashMap<String, List<String>>()
        for (p in ordenados) {
            if (p.id in asignado) continue
            val loc = local(p.id)
            val grupo = ArrayList<String>()
            grupo.add(p.id)
            if (loc.isNotEmpty()) {
                for (o in ordenados) {
                    if (o.id == p.id || o.id in asignado) continue
                    if (local(o.id) != loc) continue
                    val d = Math.abs((reciboDe[o.id] ?: 0L) - (reciboDe[p.id] ?: 0L))
                    if (d <= 60_000L) grupo.add(o.id)
                }
            }
            grupo.sortBy { reciboDe[it] ?: 0L }
            for (g in grupo) { asignado.add(g); loteDe[g] = grupo }
        }
        val numLotes = loteDe.values.map { it.joinToString(",") }.toSet().count { it.contains(",") }

        // ---- Cadena de odometro: km al local por pedido ----
        var refOdom: Double? = run {
            repo.eventosJornada(jid).firstOrNull { it.first == "INICIO_ZONA" }?.third?.let { odom(it) }
                ?: repo.kmDe(jid, "INICIO")
        }
        val kmAlLocal = HashMap<String, Double?>()
        val kmAlCliente = HashMap<String, Double?>()
        for (p in ordenados) {
            val grupo = loteDe[p.id] ?: listOf(p.id)
            val idx = grupo.indexOf(p.id)
            val park = odom(nota(p.id, "LLEGADA_PARKING"))
            val cli = odom(nota(p.id, "LLEGADA_CLIENTE"))
            val salida = odom(nota(p.id, "SALIDA_CLIENTE"))

            kmAlLocal[p.id] = if (idx > 0) kmAlLocal[grupo[0]] else {
                if (park != null && refOdom != null && park > refOdom!!) red1(park - refOdom!!) else null
            }
            kmAlCliente[p.id] = if (idx > 0) {
                val prev = odom(nota(grupo[idx - 1], "LLEGADA_CLIENTE"))
                if (cli != null && prev != null && cli > prev) red1(cli - prev) else null
            } else {
                if (salida != null && cli != null && cli > salida) red1(cli - salida) else null
            }
            val candidatos = listOfNotNull(cli, park).filter { refOdom == null || it > refOdom!! }
            if (candidatos.isNotEmpty()) refOdom = candidatos.max()
        }

        // ---- Caja (efectivo cobrado / pagado en locales / neto) ----
        var efectivo = repo.efectivoDelDia(jid)
        if (efectivo <= 0.0) efectivo = repo.efectivoCobrado(jid)
        // "Pagado en locales" = lo que el repartidor paga EN el establecimiento (pedido_dato.pago_local,
        // v1.5). Si un pedido de pago en local no trae ese campo, se usa su importe como respaldo.
        var pagadoLocales = 0.0
        var hayPagoLocal = false
        var pagadoRespaldo = 0.0
        for (p in pedidos) {
            val pl = datos[p.id]?.get("pago_local")?.replace(',', '.')?.toDoubleOrNull()
            if (pl != null) {
                pagadoLocales += pl
                hayPagoLocal = true
                continue
            }
            val pago = (datos[p.id]?.get("pago") ?: "").lowercase(Locale.US)
            if (!pago.contains("pago en el local") && !pago.contains("pagado en el local")) continue
            pagadoRespaldo += datos[p.id]?.get("importe")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        }
        if (!hayPagoLocal) pagadoLocales = pagadoRespaldo
        pagadoLocales = red2(pagadoLocales)
        val neto = red2(efectivo - pagadoLocales)
        val kmTotal = repo.kmRecorridos(jid)

        // ---- Documento ----
        val doc = Lienzo("Informe de jornada", "JoseRufino · " + FECHA_VER.format(Date()))
        val turno = repo.turnoDe(jid)
        val mismoDia = repo.jornadasDelDia(j.first).size

        doc.tabla(
            arrayOf("Campo", "Valor"),
            floatArrayOf(150f, 365f),
            listOf(
                arrayOf("Fecha", FECHA_VER.format(FECHA_DB.parse(j.first) ?: Date()) + "  (jornada #" + jid + ")"),
                arrayOf("Turno", if (mismoDia > 1) "Turno " + turno + " del dia" else "Unico turno del dia"),
                arrayOf("Horario real", HORA.format(Date(j.second)) + "  ->  " +
                        (if (j.third != null) HORA.format(Date(j.third!!)) else "en curso")),
                arrayOf("Estado", estadoLegible(estado))
            ),
            tam = 8.5f
        )

        doc.seccion("1. Resumen")
        doc.tabla(
            arrayOf("Concepto", "Valor"),
            floatArrayOf(300f, 215f),
            listOf(
                arrayOf("Pedidos", pedidos.size.toString() + (if (numLotes > 0) "  (incluye " + numLotes + " lote/s)" else "")),
                arrayOf("Lotes", numLotes.toString()),
                arrayOf("Km totales (odometro)", if (kmTotal != null) km(kmTotal) else "s/d"),
                arrayOf("Efectivo cobrado", eur(efectivo)),
                arrayOf("Pagado en locales", eur(pagadoLocales)),
                arrayOf("Neto", eur(neto))
            ),
            der = setOf(1),
            tam = 8.5f
        )

        doc.seccion("2. Pedidos")
        if (pedidos.isEmpty()) {
            doc.parrafo("No hay pedidos registrados en esta jornada.")
        } else {
            val filas = ArrayList<Array<String>>()
            for ((i, p) in ordenados.withIndex()) {
                val d = datos[p.id] ?: emptyMap()
                val cliente = listOf(d["cliente"] ?: "", d["dir_entrega"] ?: "")
                    .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "s/d" }
                val pago = (d["pago"] ?: "").ifEmpty { "s/d" } +
                        (d["importe"]?.replace(',', '.')?.toDoubleOrNull()?.let { " " + eur(it) } ?: "")
                filas.add(arrayOf(
                    (i + 1).toString(),
                    p.id + (if (p.num.isNotEmpty()) " (" + p.num + ")" else ""),
                    local(p.id).ifEmpty { "s/d" },
                    cliente,
                    ts(p.id, "RECOGIDA")?.let { HORA.format(Date(it)) } ?: "s/d",
                    ts(p.id, "ENTREGA")?.let { HORA.format(Date(it)) } ?: "s/d",
                    pago
                ))
            }
            doc.tabla(
                arrayOf("#", "Pedido", "Local", "Cliente / direccion", "Recogida", "Entrega", "Pago"),
                floatArrayOf(16f, 86f, 92f, 166f, 45f, 45f, 65f),
                filas,
                tam = 7.5f
            )
        }

        doc.seccion("3. Tiempos por pedido")
        if (pedidos.isEmpty()) {
            doc.parrafo("Sin pedidos: no hay tiempos que medir.")
        } else {
            val filas = ArrayList<Array<String>>()
            for ((i, p) in ordenados.withIndex()) {
                val grupo = loteDe[p.id] ?: listOf(p.id)
                val idx = grupo.indexOf(p.id)
                val recibo = ts(p.id, "RECIBO_PEDIDO") ?: p.primera
                val recogida = ts(p.id, "RECOGIDA")
                val entrega = ts(p.id, "ENTREGA")
                val vuelta = ts(p.id, "LLEGADA_MOTO_FINAL")
                var base = recogida
                if (idx > 0) {
                    val prev = grupo.subList(0, idx).lastOrNull { ts(it, "ENTREGA") != null }
                    if (prev != null) base = ts(prev, "ENTREGA")
                }
                val tLocal = if (recogida != null && recogida >= recibo) dur(recogida - recibo) else "s/d"
                val tEntrega = if (entrega != null && base != null && entrega >= base) dur(entrega - base) else "s/d"
                val tVuelta = if (vuelta != null && entrega != null && vuelta >= entrega) dur(vuelta - entrega) else "s/d"
                val etqLote = if (grupo.size > 1) "LOTE " + (idx + 1) + "/" + grupo.size else "—"
                filas.add(arrayOf(
                    (i + 1).toString(),
                    p.id + (if (p.num.isNotEmpty()) " (" + p.num + ")" else ""),
                    etqLote, tLocal, tEntrega, tVuelta
                ))
            }
            doc.parrafo("Lote: todos comparten la recogida; la entrega del 2.º/3.º se mide desde la entrega anterior.", doc.pie())
            doc.tabla(
                arrayOf("#", "Pedido", "Lote", "Recogida al local", "Entrega al cliente", "Vuelta a la moto"),
                floatArrayOf(16f, 92f, 45f, 120f, 120f, 122f),
                filas,
                tam = 8f
            )
        }

        doc.seccion("4. Km por tramo (odometro)")
        if (pedidos.isEmpty()) {
            doc.parrafo("Sin pedidos: no hay tramos que medir.")
        } else {
            val filas = ArrayList<Array<String>>()
            for ((i, p) in ordenados.withIndex()) {
                val grupo = loteDe[p.id] ?: listOf(p.id)
                val idx = grupo.indexOf(p.id)
                val etqLote = if (grupo.size > 1) "LOTE " + (idx + 1) + "/" + grupo.size else "—"
                filas.add(arrayOf(
                    (i + 1).toString(),
                    p.id + (if (p.num.isNotEmpty()) " (" + p.num + ")" else ""),
                    etqLote,
                    kmAlLocal[p.id]?.let { km(it) } ?: "s/d",
                    kmAlCliente[p.id]?.let { km(it) } ?: "s/d"
                ))
            }
            doc.tabla(
                arrayOf("#", "Pedido", "Lote", "Al local", "Al cliente"),
                floatArrayOf(16f, 92f, 45f, 181f, 181f),
                filas,
                tam = 8f
            )
        }

        doc.seccion("5. Incidencias y notas")
        val inc = repo.incidencias(jid)
        if (inc.isNotEmpty()) {
            doc.parrafo("Incidencias registradas:", doc.negrita())
            val filas = ArrayList<Array<String>>()
            for (x in inc) {
                filas.add(arrayOf(
                    HORA.format(Date(x.momento)),
                    if (x.importe > 0.0) eur(x.importe) else "s/d",
                    x.pago.ifEmpty { "s/d" },
                    x.cobrado.ifEmpty { "s/d" },
                    x.estado.ifEmpty { "s/d" } + (if (x.cierre.isNotEmpty()) " (" + x.cierre + ")" else "")
                ))
            }
            doc.tabla(
                arrayOf("Hora", "Importe", "Pago", "Cobrado", "Estado"),
                floatArrayOf(60f, 70f, 90f, 90f, 205f),
                filas,
                tam = 8f
            )
        }
        val notas = ArrayList<Array<String>>()
        for (p in pedidos) {
            val idEtq = p.id + (if (p.num.isNotEmpty()) " #" + p.num else "")
            for (f in repo.fases(jid, p.id)) {
                val n = f.third.trim()
                if (n.isEmpty() || n.startsWith("auto")) continue
                notas.add(arrayOf(HORA.format(Date(f.second)), idEtq, f.first + ": " + n))
            }
        }
        for (f in repo.eventosJornada(jid)) {
            val n = f.third.trim()
            if (n.isEmpty()) continue
            notas.add(arrayOf(HORA.format(Date(f.second)), "JORNADA", f.first + ": " + n))
        }
        notas.sortBy { it[0] }
        if (notas.isEmpty()) {
            doc.parrafo("Sin notas manuales en esta jornada.")
        } else {
            doc.parrafo("Notas del flujo (incluye la nota de LLEGADA_MOTO_FINAL de cada pedido):", doc.negrita())
            doc.tabla(
                arrayOf("Hora", "Pedido", "Nota"),
                floatArrayOf(55f, 105f, 355f),
                notas,
                tam = 8f
            )
        }

        doc.seccion("6. Caja")
        doc.tabla(
            arrayOf("Concepto", "Importe"),
            floatArrayOf(300f, 215f),
            listOf(
                arrayOf("Cobrado en efectivo", "+" + eur(efectivo)),
                arrayOf("Pagado en locales", "-" + eur(pagadoLocales)),
                arrayOf("Neto", eur(neto))
            ),
            der = setOf(1),
            tam = 8.5f
        )

        // ---- Guardado sin sobrescribir (carpeta del usuario o, si no hay, la privada) ----
        val base = "Informe_" + j.first + (if (mismoDia > 1) "_turno" + turno else "")
        return SalidaPdf.guardar(ctx, "informes", base) { doc.escribir(it) }
    }

    // ---------------- formato ----------------

    private fun estadoLegible(e: String): String = when (e) {
        "CERRADA_POR_EL_REPARTIDOR" -> "CERRADA (por el repartidor)"
        "CERRADA" -> "CERRADA"
        "EN_CURSO" -> "EN_CURSO (abierta)"
        "" -> "s/d"
        else -> e
    }

    private fun eur(v: Double): String = String.format(Locale.US, "%.2f", v).replace('.', ',') + " EUR"

    private fun km(v: Double): String = String.format(Locale.US, "%.1f", v).replace('.', ',') + " km"

    private fun red1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun red2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private fun dur(ms: Long): String {
        if (ms < 0) return "s/d"
        val s = ms / 1000L
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        val seg = s % 60L
        return when {
            h > 0 -> String.format(Locale.US, "%d h %02d min", h, m)
            m > 0 -> String.format(Locale.US, "%d min %02d s", m, seg)
            else -> String.format(Locale.US, "%d s", seg)
        }
    }

    /** Lectura de odometro anotada en una nota (clave km= u odom=, 4-6 digitos). */
    private fun odom(nota: String?): Double? {
        if (nota.isNullOrEmpty()) return null
        val m = Regex("(?:km|odom)=([0-9]{4,6}(?:[.,][0-9]+)?)").find(nota) ?: return null
        return m.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    // ---------------- lienzo A4 ----------------

    /**
     * Paginacion sencilla: A4 595x842, margen 40, pie con numero de pagina.
     * Las tablas repiten la cabecera al cambiar de pagina.
     */
    private class Lienzo(private val titulo: String, private val firma: String) {
        private val doc = PdfDocument()
        private var page: PdfDocument.Page? = null
        private var num = 0
        private var y = 0f

        val pTit = Paint().apply { textSize = 15f; isFakeBoldText = true }
        val pSub = Paint().apply { textSize = 8.5f; color = 0xFF555555.toInt() }
        val pSec = Paint().apply { textSize = 10.5f; isFakeBoldText = true }
        val pTxt = Paint().apply { textSize = 8.5f }
        val pPie = Paint().apply { textSize = 7.5f; color = 0xFF666666.toInt() }
        private val pFirma = Paint().apply { textSize = 7.5f; color = 0xFF666666.toInt(); textAlign = Paint.Align.RIGHT }
        private val pLin = Paint().apply { strokeWidth = 0.6f; color = 0xFFAAAAAA.toInt() }
        private val pFondo = Paint().apply { color = 0xFFF2F2F2.toInt() }

        init { abrir() }

        fun pie(): Paint = pPie
        fun negrita(): Paint = Paint().apply { textSize = 8.5f; isFakeBoldText = true }

        private fun abrir() {
            if (page != null) cerrar()
            num++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, num).create())
            y = 46f
            page!!.canvas.drawText(titulo, IZQ, y, pTit)
            y += 13f
            page!!.canvas.drawText("Documento generado en el movil por el repartidor · MisRepartos", IZQ, y, pSub)
            y += 8f
            page!!.canvas.drawLine(IZQ, y, DER, y, pLin)
            y += 18f
        }

        private fun cerrar() {
            val p = page ?: return
            p.canvas.drawLine(IZQ, 806f, DER, 806f, pLin)
            p.canvas.drawText("Pagina " + num, IZQ, 818f, pPie)
            p.canvas.drawText("MisRepartos - datos propios del repartidor", 210f, 818f, pPie)
            p.canvas.drawText(firma, DER, 818f, pFirma)
            doc.finishPage(p)
            page = null
        }

        fun seccion(t: String) {
            if (y + 30f > 795f) abrir()
            y += 6f
            page!!.canvas.drawText(t, IZQ, y, pSec)
            y += 4f
            page!!.canvas.drawLine(IZQ, y, DER, y, pLin)
            y += 13f
        }

        fun parrafo(t: String, paint: Paint = pTxt) {
            for (l in envolver(t, ANCHO, paint)) {
                if (y + 12f > 798f) abrir()
                page!!.canvas.drawText(l, IZQ, y, paint)
                y += 11f
            }
            y += 2f
        }

        fun tabla(cabeceras: Array<String>, anchos: FloatArray, filas: List<Array<String>>,
                  der: Set<Int> = emptySet(), tam: Float = 8f) {
            val alto = tam + 2.6f
            val pC = Paint().apply { textSize = tam; isFakeBoldText = true }
            val pCD = Paint().apply { textSize = tam; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
            val pN = Paint().apply { textSize = tam }
            val pD = Paint().apply { textSize = tam; textAlign = Paint.Align.RIGHT }

            fun paintCol(i: Int): Paint = if (i in der) pD else pN
            fun paintCab(i: Int): Paint = if (i in der) pCD else pC
            fun xCol(i: Int): Float {
                var x = IZQ
                for (k in 0 until i) x += anchos[k]
                return if (i in der) x + anchos[i] - 4f else x
            }

            fun cabecera() {
                if (y + alto * 2 > 795f) abrir()
                page!!.canvas.drawRect(IZQ, y - tam, DER, y + 3.5f, pFondo)
                for (i in cabeceras.indices) page!!.canvas.drawText(cabeceras[i], xCol(i), y, paintCab(i))
                y += alto + 1.5f
                page!!.canvas.drawLine(IZQ, y - 7f, DER, y - 7f, pLin)
                y += 2f
            }

            cabecera()
            for (fila in filas) {
                val celdas = ArrayList<List<String>>()
                var maxL = 1
                for (i in fila.indices) {
                    val ls = envolver(fila[i], anchos[i] - 5f, paintCol(i))
                    celdas.add(ls)
                    if (ls.size > maxL) maxL = ls.size
                }
                val altoFila = maxL * alto + 2.5f
                if (y + altoFila > 798f) {
                    abrir()
                    cabecera()
                }
                var yy = y
                for (l in 0 until maxL) {
                    for (i in celdas.indices) {
                        val txt = celdas[i].getOrNull(l) ?: continue
                        page!!.canvas.drawText(txt, xCol(i), yy, paintCol(i))
                    }
                    yy += alto
                }
                y += altoFila
            }
            y += 5f
        }

        fun escribir(out: java.io.OutputStream) {
            cerrar()
            doc.writeTo(out)
            doc.close()
        }

        /** Parte un texto en lineas que caben en [ancho] (sin romper palabras si se puede). */
        private fun envolver(t: String, ancho: Float, paint: Paint): List<String> {
            val s = t.replace("\n", " ").trim()
            if (s.isEmpty()) return listOf("")
            if (paint.measureText(s) <= ancho) return listOf(s)
            val out = ArrayList<String>()
            var resto = s
            while (resto.isNotEmpty()) {
                if (paint.measureText(resto) <= ancho) { out.add(resto); break }
                var corte = resto.length
                while (corte > 1 && paint.measureText(resto.substring(0, corte)) > ancho) corte--
                if (corte <= 1) { out.add(resto); break }
                val sp = resto.lastIndexOf(' ', corte)
                if (sp > corte / 2) corte = sp
                out.add(resto.substring(0, corte).trim())
                resto = resto.substring(corte).trim()
            }
            return out
        }
    }
}
