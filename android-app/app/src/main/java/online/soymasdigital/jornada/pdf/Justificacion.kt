package online.soymasdigital.jornada.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import online.soymasdigital.jornada.data.CapturasKeep
import online.soymasdigital.jornada.data.Repo
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.1.0 - JUSTIFICACION de un aviso, sancion o suspension en el formato de Rufino.
 *
 * Cabecera (titulo + episodio + tabla de 4 filas), RESUMEN de un parrafo con los datos duros
 * en orden cronologico, y I. EXPOSICION DE HECHOS con secciones numeradas, frases cortas en
 * primera persona, operaciones aritmeticas en linea aparte y capturas intercaladas en el
 * texto (nunca como anexos al final).
 *
 * Todo sale de la base de datos local (flujo, pedido_dato y km) y de los pantallazos de
 * files/capturas. Sin red, sin librerias nuevas: android.graphics.pdf.PdfDocument.
 */
object Justificacion {

    /** Datos que rellena el formulario de la app. */
    data class Datos(
        val tipo: String,
        val fechaAviso: Long,
        val pedidos: List<String>,
        val referencia: String,
        val trabajador: String,
        val dni: String,
        val empresa: String
    )

    private val HORA = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val DIA = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    private val DIA_ISO = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val MESES = arrayOf("enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    // ---------------- API ----------------

    fun generar(ctx: Context, d: Datos): SalidaPdf.Salida {
        val repo = Repo(ctx)
        val infos = d.pedidos.map { cargar(repo, ctx, it) }
        val episodio = d.referencia.trim().ifEmpty {
            d.pedidos.firstOrNull() ?: "s/d"
        }

        val secciones = ArrayList<Sec>()
        for ((idx, i) in infos.withIndex()) {
            if (idx == 0) secciones.addAll(detalladas(i, d.fechaAviso))
            else secciones.add(resumenPedido(i))
        }

        val hoja = Hoja(episodio)
        hoja.titulo("JUSTIFICACIÓN")
        hoja.subtitulo("Episodio " + episodio + " · " + fechaLarga(d.fechaAviso))
        hoja.tablaDatos(listOf(
            "Trabajador" to d.trabajador.trim().ifEmpty { "s/d" },
            "DNI / NIE" to d.dni.trim().ifEmpty { "s/d" },
            "Empresa" to d.empresa.trim().ifEmpty { "s/d" },
            "Asunto" to ("Respuesta a la comunicación de " + DIA.format(Date(d.fechaAviso)) +
                    " (" + tipoLegible(d.tipo) + " · Episodio " + episodio + ")")
        ))

        hoja.seccion("RESUMEN")
        hoja.parrafo(resumen(infos, d), true)
        hoja.parrafo("Sugerencia: " + sugerencia(infos), true)

        hoja.seccion("I. EXPOSICIÓN DE HECHOS")
        var n = 1
        for (s in secciones) {
            hoja.subseccion(n.toString() + ". " + s.titulo)
            for (f in s.frases) hoja.parrafo(f, true)
            if (!s.aritmetica.isNullOrBlank()) hoja.aritmetica(s.aritmetica!!)
            for (c in s.capturas) hoja.imagen(c.second, pieDeFoto(c.first, s.titulo))
            n++
        }

        val base = "Justificacion_" + DIA_ISO.format(Date(d.fechaAviso)) + "_" +
                (d.pedidos.firstOrNull() ?: "sindato")
        return SalidaPdf.guardar(ctx, "justificaciones", base) { hoja.escribir(it) }
    }

    // ---------------- carga de datos ----------------

    private class Info(val id: String) {
        var jid: Long? = null
        var datos: Map<String, String> = emptyMap()
        var fases: List<Triple<String, Long, String>> = emptyList()
        var inicio: Long? = null
        var fin: Long? = null
        var kmIni: Double? = null
        var kmFin: Double? = null
        var estMinLocal: Int? = null
        var estKmLocal: Double? = null
        var estMinCliente: Int? = null
        var estKmCliente: Double? = null
        var capturas: List<Pair<Long, String>> = emptyList()
        var pedidosJornada = 0
        var kmJornada: Double? = null
        var inicioJornada: Long? = null
        var finJornada: Long? = null
        var kmIniJornada: Double? = null
        var kmFinJornada: Double? = null

        fun ts(paso: String): Long? = fases.lastOrNull { it.first == paso }?.second
        fun nota(paso: String): String = fases.lastOrNull { it.first == paso }?.third ?: ""
        fun recibo(): Long? = ts("RECIBO_PEDIDO") ?: inicio
        fun entrega(): Long? = ts("ENTREGA") ?: fin
        fun local(): String = datos["local"].orEmpty()
        fun num(): String = datos["num"].orEmpty()
    }

    private fun cargar(repo: Repo, ctx: Context, id: String): Info {
        val i = Info(id)
        i.datos = try { repo.datosPedido(id) } catch (e: Exception) { emptyMap() }
        i.jid = try { repo.jornadaDePedido(id) } catch (e: Exception) { null }
        val jid = i.jid ?: return i
        i.fases = try { repo.fasesEfectivas(jid, id) } catch (e: Exception) { emptyList() }
        val p = try { repo.pedidos(jid).firstOrNull { it.id == id } } catch (e: Exception) { null }
        i.inicio = p?.primera ?: i.fases.firstOrNull()?.second
        i.fin = p?.entrega ?: p?.ultima ?: i.fases.lastOrNull()?.second
        i.pedidosJornada = try { repo.pedidos(jid).size } catch (e: Exception) { 0 }
        i.kmJornada = try { repo.kmRecorridos(jid) } catch (e: Exception) { null }
        i.inicioJornada = try { repo.jornada(jid)?.second } catch (e: Exception) { null }
        i.finJornada = try { repo.jornada(jid)?.third } catch (e: Exception) { null }
        i.kmIniJornada = try { repo.kmDe(jid, "INICIO") } catch (e: Exception) { null }
        i.kmFinJornada = try { repo.kmDe(jid, "FIN") } catch (e: Exception) { null }

        val ruta = i.nota("RUTA_LOCAL")
        i.estMinLocal = minutos(ruta) ?: minutos(i.nota("LLEGADA_PARKING"))
        i.estKmLocal = kmRuta(ruta) ?: tramoKm(i.nota("LLEGADA_PARKING"))
        val salida = i.nota("SALIDA_CLIENTE")
        i.estMinCliente = minutos(salida) ?: minutos(i.nota("LLEGADA_CLIENTE"))
        i.estKmCliente = kmRuta(salida) ?: tramoKm(i.nota("LLEGADA_CLIENTE"))

        val ultimo = listOfNotNull(i.fin, i.fases.lastOrNull()?.second).maxOrNull()
        val desde = i.recibo() ?: i.inicio ?: 0L
        val hasta = (i.entrega() ?: ultimo ?: desde) + 10 * 60 * 1000L

        val refIni = i.recibo() ?: desde
        val refFin = i.ts("LLEGADA_CLIENTE") ?: i.ts("ENTREGA") ?: i.ts("LLEGADA_MOTO_FINAL")
                ?: i.ts("LLEGADA_PARKING") ?: i.ts("LLEGADA_LOCAL") ?: i.fin ?: hasta
        val oIni = odom(i.nota("RECIBO_PEDIDO")) ?: odom(i.nota("RUTA_LOCAL"))
                ?: odom(i.nota("LLEGADA_PARKING"))
                ?: repo.kmLecturaAntes(refIni)?.takeIf { refIni - it.first <= 60 * 60 * 1000L }?.second
        val oFin = odom(i.nota("LLEGADA_CLIENTE")) ?: odom(i.nota("ENTREGA"))
                ?: odom(i.nota("LLEGADA_MOTO_FINAL")) ?: odom(i.nota("LLEGADA_PARKING"))
                ?: odom(i.nota("LLEGADA_LOCAL"))
                ?: repo.kmLecturaDespues(refFin)?.takeIf { it.first - refFin <= 45 * 60 * 1000L }?.second
        i.kmIni = oIni
        i.kmFin = oFin

        if (desde > 0 && hasta > desde) {
            val vistos = LinkedHashMap<String, Pair<Long, String>>()
            // v2.1.1: PRIMERO la copia reducida propia (filesDir/capturas_keep), que sobrevive
            // al archivado y borrado de las capturas del movil.
            try {
                for ((ts, rutaF) in CapturasKeep.listarVentana(ctx, desde, hasta)) {
                    if (rutaF.isNotEmpty() && File(rutaF).exists()) vistos[rutaF] = ts to rutaF
                }
            } catch (e: Exception) { }
            // Si no hay copias propias (dias anteriores a 2.1.1), respaldo con la tabla
            // evidencia y con los pantallazos de files/capturas.
            if (vistos.isEmpty()) {
                try {
                    for ((ts, rutaF) in repo.capturasEntre(desde, hasta)) {
                        if (rutaF.isNotEmpty() && File(rutaF).exists()) vistos[rutaF] = ts to rutaF
                    }
                } catch (e: Exception) { }
                try {
                    val dir = File(ctx.getExternalFilesDir(null), "capturas")
                    val hijos = dir.listFiles()
                    if (hijos != null) for (f in hijos) {
                        if (!f.isFile) continue
                        val n = f.name.lowercase(Locale.US)
                        if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) continue
                        val t = f.lastModified()
                        if (t < desde || t > hasta || vistos.containsKey(f.absolutePath)) continue
                        vistos[f.absolutePath] = t to f.absolutePath
                    }
                } catch (e: Exception) { }
            }
            i.capturas = elegir(vistos.values.sortedBy { it.first }, 3)
        }
        return i
    }

    /** Reparte como maximo [max] capturas a lo largo del tramo (primera, intermedias y ultima). */
    private fun elegir(lista: List<Pair<Long, String>>, max: Int): List<Pair<Long, String>> {
        if (lista.size <= max) return lista
        val out = ArrayList<Pair<Long, String>>()
        for (k in 0 until max) {
            val idx = Math.round(k * (lista.size - 1).toFloat() / (max - 1).toFloat())
            out.add(lista[idx.coerceIn(0, lista.size - 1)])
        }
        return out.distinctBy { it.first }
    }

    // ---------------- secciones ----------------

    private class Sec(val titulo: String, val ts: Long = 0L) {
        val frases = ArrayList<String>()
        var aritmetica: String? = null
        val capturas = ArrayList<Pair<Long, String>>()
    }

    private fun detalladas(i: Info, aviso: Long): List<Sec> {
        val out = ArrayList<Sec>()
        val recibo = i.recibo()
        val recogida = i.ts("RECOGIDA")
        val llegadaLocal = i.ts("LLEGADA_LOCAL") ?: i.ts("LLEGADA_PARKING")
        val salidaCliente = i.ts("SALIDA_CLIENTE")
        val llegadaCliente = i.ts("LLEGADA_CLIENTE")
        val entrega = i.ts("ENTREGA")

        // 1. Pedido recibido
        val s1 = Sec("Pedido recibido", recibo ?: Long.MAX_VALUE)
        if (recibo != null) {
            s1.frases.add("A las " + h(recibo) + " recibí el pedido " + i.id +
                    (if (i.num().isNotEmpty()) " (n.º " + i.num() + ")" else "") + ".")
            if (i.local().isNotEmpty()) s1.frases.add("El pedido se recogía en " + i.local() +
                    (if (i.datos["dir_recogida"].orEmpty().isNotEmpty()) ", " + i.datos["dir_recogida"] else "") + ".")
            if (i.datos["cliente"].orEmpty().isNotEmpty() || i.datos["dir_entrega"].orEmpty().isNotEmpty()) {
                s1.frases.add("La entrega era para " + i.datos["cliente"].orEmpty().ifEmpty { "el cliente" } +
                        (if (i.datos["dir_entrega"].orEmpty().isNotEmpty()) " en " + i.datos["dir_entrega"] else "") + ".")
            }
            if (i.estMinLocal != null) s1.frases.add("La aplicación indicaba " + i.estMinLocal + " minutos" +
                    (if (i.estKmLocal != null) " (" + km(i.estKmLocal!!) + ")" else "") + " hasta el local.")
        } else {
            s1.frases.add("No quedó registrada la hora de recepción del pedido " + i.id + " (s/d).")
            s1.frases.add("La app conserva los datos disponibles de ese pedido en el registro del día.")
        }
        out.add(s1)

        // 2. Salida hacia el local
        val tSalida = i.ts("RUTA_LOCAL") ?: i.ts("LLEGADA_PARKING") ?: recibo ?: Long.MAX_VALUE
        val s2 = Sec("Salida hacia el local", tSalida)
        if (i.ts("RUTA_LOCAL") != null) s2.frases.add("Inicié la ruta hacia el local a las " + h(i.ts("RUTA_LOCAL")!!) + ".")
        if (i.ts("LLEGADA_PARKING") != null) s2.frases.add("Llegué al estacionamiento a las " + h(i.ts("LLEGADA_PARKING")!!) + ".")
        if (i.ts("LLEGADA_LOCAL") != null) s2.frases.add("Entré en el establecimiento a las " + h(i.ts("LLEGADA_LOCAL")!!) + ".")
        if (s2.frases.isEmpty()) s2.frases.add("No quedaron registradas las horas de salida ni de llegada al local (s/d).")
        out.add(s2)

        // 3. Espera y recogida
        val s3 = Sec("Espera y recogida", recogida ?: llegadaLocal ?: tSalida)
        if (recogida != null) {
            s3.frases.add("Recogí el pedido a las " + h(recogida) + ".")
            if (llegadaLocal != null && recogida >= llegadaLocal) {
                s3.frases.add("La espera en el local fue de " + minutosTxt(recogida - llegadaLocal) + ".")
            }
        }
        val listo = i.datos["listo_min"].orEmpty().toIntOrNull()
        if (listo != null) s3.frases.add("La aplicación anunciaba el pedido listo en " + listo + " minutos.")
        if (recogida == null) s3.frases.add("No quedó registrada la hora de recogida (s/d).")
        if (s3.frases.isEmpty()) s3.frases.add("No hay recogida registrada para este pedido (s/d).")
        out.add(s3)

        // 4. Trayecto hasta el cliente
        val s4 = Sec("Trayecto hasta el cliente", salidaCliente ?: recogida ?: tSalida)
        if (salidaCliente != null) s4.frases.add("Salí hacia el cliente a las " + h(salidaCliente) + ".")
        if (i.estMinCliente != null) s4.frases.add("La aplicación indicaba " + i.estMinCliente + " minutos" +
                (if (i.estKmCliente != null) " (" + km(i.estKmCliente!!) + ")" else "") + ".")
        if (llegadaCliente != null) s4.frases.add("Llegué a " +
                (if (i.datos["dir_entrega"].orEmpty().isNotEmpty()) i.datos["dir_entrega"] else "destino") +
                " a las " + h(llegadaCliente) + ".")
        if (salidaCliente != null && llegadaCliente != null && llegadaCliente >= salidaCliente) {
            s4.frases.add("El trayecto real duró " + minutosTxt(llegadaCliente - salidaCliente) + ".")
        }
        if (s4.frases.isEmpty()) s4.frases.add("No hay datos del trayecto al cliente (s/d).")
        out.add(s4)

        // 5. Entrega
        val s5 = Sec("Entrega del pedido", entrega ?: llegadaCliente ?: s4.ts)
        if (entrega != null) {
            s5.frases.add("Entregué el pedido a las " + h(entrega) + ".")
            if (recibo != null && entrega >= recibo) {
                s5.frases.add("El servicio completo duró " + minutosTxt(entrega - recibo) + " desde el recibo.")
            }
        }
        val inc = i.nota("INCIDENCIA_ENTREGA")
        if (inc.isNotEmpty()) s5.frases.add("Dejé constancia en la app: " + inc.trim() + ".")
        if (s5.frases.isEmpty()) s5.frases.add("No hay entrega registrada para este pedido (s/d).")
        out.add(s5)

        // 6. Kilometros
        val s6 = Sec("Kilómetros del servicio", entrega ?: i.fin ?: s5.ts)
        if (i.kmIni != null && i.kmFin != null && i.kmFin!! > i.kmIni!!) {
            s6.frases.add("El cuentakilómetros marcaba " + kmNum(i.kmIni!!) + " kms al inicio del servicio y " +
                    kmNum(i.kmFin!!) + " kms al final.")
            s6.aritmetica = kmNum(i.kmFin!!) + " kms – " + kmNum(i.kmIni!!) + " kms = " +
                    kmNum(red1(i.kmFin!! - i.kmIni!!)) + " kms"
        } else {
            val est = listOfNotNull(i.estKmLocal, i.estKmCliente).sum()
            if (est > 0.0) s6.frases.add("La aplicación estimó " + km(red1(est)) + " en los dos tramos del pedido.")
            s6.frases.add("No hay dos lecturas de cuentakilómetros para restar en este tramo (s/d).")
        }
        out.add(s6)

        // 7. El aviso
        val s7 = Sec("El aviso de las " + hhmm(aviso), entrega ?: i.fin ?: s5.ts)
        s7.frases.addAll(estadoAviso(i, aviso))
        out.add(s7)

        // 8. Jornada del dia (contexto del resto del trabajo)
        if (i.inicioJornada != null) {
            val s8 = Sec("Jornada del día", i.fin ?: s7.ts)
            s8.frases.add("Abrí la jornada a las " + h(i.inicioJornada) +
                    (if (i.finJornada != null) " y la cerré a las " + h(i.finJornada) else "") + ".")
            if (i.kmIniJornada != null && i.kmFinJornada != null && i.kmFinJornada!! > i.kmIniJornada!!) {
                s8.frases.add("El cuentakilómetros marcó " + kmNum(i.kmIniJornada!!) + " kms al inicio y " +
                        kmNum(i.kmFinJornada!!) + " kms al cierre: " +
                        kmNum(red1(i.kmFinJornada!! - i.kmIniJornada!!)) + " kms.")
            }
            if (i.pedidosJornada > 0) {
                s8.frases.add("En total registré " + i.pedidosJornada + " pedidos" +
                        (if (i.kmJornada != null) " y " + kmNum(i.kmJornada!!) + " kms" else "") + " ese día.")
            }
            s8.frases.add("El aviso se refiere a un solo pedido de toda la jornada.")
            out.add(s8)

            // 9. Datos registrados del pedido (los que la app tiene del propio encargo)
            val s9 = Sec("Datos registrados del pedido", i.fin ?: s8.ts)
            if (i.num().isNotEmpty()) s9.frases.add("El número interno del pedido era el " + i.num() + ".")
            if (i.datos["productos"].orEmpty().isNotEmpty()) s9.frases.add("El pedido tenía " + i.datos["productos"] + " productos.")
            if (i.datos["cliente"].orEmpty().isNotEmpty()) s9.frases.add("El cliente era " + i.datos["cliente"] + ".")
            if (i.datos["pago"].orEmpty().isNotEmpty()) s9.frases.add("La forma de pago registrada era: " + i.datos["pago"] + ".")
            if (s9.frases.isEmpty()) s9.frases.add("No hay más datos del pedido en la app (s/d).")
            out.add(s9)
        }

        // Capturas: cada una al parrafo del hito mas cercano por detras
        for (c in i.capturas) {
            var idx = out.indexOfLast { it.ts <= c.first }
            if (idx < 0) idx = 0
            out[idx].capturas.add(c)
        }
        return out
    }

    private fun resumenPedido(i: Info): Sec {
        val s = Sec("Pedido " + i.id, i.recibo() ?: Long.MAX_VALUE)
        val recibo = i.recibo()
        val recogida = i.ts("RECOGIDA")
        val entrega = i.ts("ENTREGA")
        if (recibo != null) {
            s.frases.add("El pedido " + i.id + " se recibió a las " + h(recibo) +
                    (if (i.local().isNotEmpty()) " en " + i.local() else "") + ".")
        } else {
            s.frases.add("El pedido " + i.id + " no tiene hora de recepción registrada (s/d).")
        }
        if (recogida != null) s.frases.add("Se recogió a las " + h(recogida) + ".")
        if (entrega != null) s.frases.add("Se entregó a las " + h(entrega) + ".")
        if (recibo != null && entrega != null && entrega >= recibo) {
            s.frases.add("El servicio duró " + minutosTxt(entrega - recibo) + ".")
        }
        for (c in i.capturas) s.capturas.add(c)
        return s
    }

    // ---------------- textos ----------------

    private fun resumen(infos: List<Info>, d: Datos): String {
        val sb = StringBuilder()
        val primero = infos.firstOrNull()
        sb.append("Glovo no incluye el contexto. El ").append(fechaLarga(d.fechaAviso))
        sb.append(" consta un aviso de ").append(tipoLegible(d.tipo))
        if (primero != null) sb.append(" referido al pedido ").append(primero.id)
        sb.append(". ")
        for ((k, i) in infos.withIndex()) {
            val recibo = i.recibo()
            val recogida = i.ts("RECOGIDA")
            val salida = i.ts("SALIDA_CLIENTE")
            val llegadaC = i.ts("LLEGADA_CLIENTE")
            val entrega = i.ts("ENTREGA")
            if (k == 0) {
                if (recibo != null) {
                    sb.append("A las ").append(h(recibo)).append(" recibí el pedido ").append(i.id)
                    if (i.num().isNotEmpty()) sb.append(" (n.º ").append(i.num()).append(")")
                    if (i.local().isNotEmpty()) sb.append(", de ").append(i.local())
                    if (i.datos["dir_recogida"].orEmpty().isNotEmpty()) sb.append(", en ").append(i.datos["dir_recogida"])
                    sb.append(". ")
                }
                if (i.datos["dir_entrega"].orEmpty().isNotEmpty()) {
                    sb.append("La entrega era en ").append(i.datos["dir_entrega"])
                    if (i.datos["cliente"].orEmpty().isNotEmpty()) sb.append(", para ").append(i.datos["cliente"])
                    sb.append(". ")
                }
                if (i.estMinLocal != null || i.estMinCliente != null) {
                    sb.append("La aplicación indicaba ")
                    if (i.estMinLocal != null) {
                        sb.append(i.estMinLocal!!.toString()).append(" minutos")
                        if (i.estKmLocal != null) sb.append(" (").append(km(i.estKmLocal!!)).append(")")
                        sb.append(" hasta el local")
                    }
                    if (i.estMinLocal != null && i.estMinCliente != null) sb.append(" y ")
                    if (i.estMinCliente != null) {
                        sb.append(i.estMinCliente!!.toString()).append(" minutos")
                        if (i.estKmCliente != null) sb.append(" (").append(km(i.estKmCliente!!)).append(")")
                        sb.append(" hasta el cliente")
                    }
                    sb.append(". ")
                } else {
                    sb.append("No hay estimación de trayecto guardada en la app (s/d). ")
                }
                if (i.ts("LLEGADA_LOCAL") != null || i.ts("LLEGADA_PARKING") != null) {
                    sb.append("Llegué al local a las ").append(h(i.ts("LLEGADA_LOCAL") ?: i.ts("LLEGADA_PARKING")))
                    if (recogida != null) sb.append(" y recogí el pedido a las ").append(h(recogida))
                    sb.append(". ")
                } else if (recogida != null) {
                    sb.append("Recogí el pedido a las ").append(h(recogida)).append(". ")
                }
                if (salida != null || llegadaC != null) {
                    if (salida != null) sb.append("Salí hacia el cliente a las ").append(h(salida))
                    if (salida != null && llegadaC != null) sb.append(" y llegué a las ").append(h(llegadaC))
                    else if (llegadaC != null) sb.append("Llegué al cliente a las ").append(h(llegadaC))
                    sb.append(". ")
                }
                if (entrega != null) {
                    sb.append("Entregué el pedido a las ").append(h(entrega)).append(". ")
                }
                if (i.kmIni != null && i.kmFin != null && i.kmFin!! > i.kmIni!!) {
                    sb.append("El cuentakilómetros pasó de ").append(kmNum(i.kmIni!!)).append(" kms a ")
                            .append(kmNum(i.kmFin!!)).append(" kms: ")
                            .append(kmNum(red1(i.kmFin!! - i.kmIni!!))).append(" kms. ")
                } else {
                    sb.append("No hay dos lecturas de cuentakilómetros en el tramo (s/d). ")
                }
                if (i.pedidosJornada > 0) {
                    sb.append("Ese día registré ").append(i.pedidosJornada).append(" pedidos")
                    if (i.kmJornada != null) sb.append(" y ").append(kmNum(i.kmJornada!!)).append(" kms de jornada")
                    sb.append(". ")
                }
            } else {
                sb.append("El pedido ").append(i.id)
                if (i.local().isNotEmpty()) sb.append(" (").append(i.local()).append(")")
                if (recibo != null) sb.append(" se recibió a las ").append(h(recibo))
                if (entrega != null) sb.append(" y se entregó a las ").append(h(entrega))
                sb.append(". ")
            }
        }
        if (infos.isEmpty()) sb.append("No hay pedidos válidos que justificar (s/d). ")
        return sb.toString().replace("  ", " ").trim()
    }

    private fun sugerencia(infos: List<Info>): String {
        val hayEstimacion = infos.any { it.estMinLocal != null || it.estMinCliente != null }
        val hayKm = infos.any { it.kmIni != null && it.kmFin != null }
        return (if (hayEstimacion) {
            "la app de la empresa mide el trayecto de extremo a extremo sobre las vías y no cuantifica los tramos " +
                    "internos (estacionamiento, acceso al local y espera). Eso desvía la estimación y explica la " +
                    "diferencia con el aviso."
        } else {
            "faltan horas o kilómetros en la estimación de la app para ese tramo. El registro propio del " +
                    "repartidor conserva los hitos con hora real y sirve para reconstruirlo."
        }) + (if (hayKm) "" else " No hay dos lecturas de cuentakilómetros para acreditar la distancia (s/d).")
    }

    private fun estadoAviso(i: Info, aviso: Long): List<String> {
        val out = ArrayList<String>()
        out.add("El aviso es de las " + hhmm(aviso) + ".")
        val recibo = i.recibo()
        val recogida = i.ts("RECOGIDA")
        val llegadaP = i.ts("LLEGADA_PARKING") ?: i.ts("LLEGADA_LOCAL")
        val llegadaC = i.ts("LLEGADA_CLIENTE")
        val entrega = i.ts("ENTREGA")
        when {
            recibo == null -> out.add("No hay hitos del pedido anteriores al aviso (s/d).")
            aviso < recibo -> out.add("A esa hora todavía no había recibido el pedido: lo recibí a las " + h(recibo) + ".")
            recogida != null && aviso >= recibo && aviso < recogida ->
                out.add("A esa hora estaba de camino al local o esperando la recogida: recogí el pedido a las " + h(recogida) + ".")
            recogida == null && llegadaP != null && aviso >= recibo && aviso < llegadaP ->
                out.add("A esa hora estaba de camino al local: llegué a las " + h(llegadaP) + ".")
            recogida == null && llegadaP != null && aviso >= llegadaP ->
                out.add("A esa hora ya había llegado al local, a las " + h(llegadaP) + ".")
            llegadaC != null && recogida != null && aviso >= recogida && aviso < llegadaC ->
                out.add("A esa hora estaba en ruta hacia el cliente: llegué a las " + h(llegadaC) + ".")
            entrega != null && llegadaC != null && aviso >= llegadaC && aviso < entrega ->
                out.add("A esa hora estaba en el punto de entrega: entregué el pedido a las " + h(entrega) + ".")
            entrega != null && aviso >= entrega ->
                out.add("A esa hora el pedido ya estaba entregado desde las " + h(entrega) + ".")
            else -> out.add("No hay un hito registrado que sitúe el aviso dentro del servicio (s/d).")
        }
        if (i.fases.isNotEmpty()) out.add("Los hitos de la app cubren ese tramo con hora real, no con estimaciones.")
        return out
    }

    private fun pieDeFoto(ts: Long, etapa: String): String =
        "Captura de las " + h(ts) + " · " + etapa +
                ". Se ve la pantalla que la app registró en ese momento del servicio."

    private fun tipoLegible(t: String): String = when {
        t.startsWith("sanc", true) -> "sanción"
        t.startsWith("susp", true) -> "suspensión"
        else -> "llamada de atención"
    }

    private fun fechaLarga(ts: Long): String {
        if (ts <= 0L) return "s/d"
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(java.util.Calendar.DAY_OF_MONTH).toString() + " de " +
                MESES[c.get(java.util.Calendar.MONTH)] + " de " + c.get(java.util.Calendar.YEAR)
    }

    private fun h(ts: Long?): String = if (ts == null || ts <= 0L) "s/d" else HORA.format(Date(ts))
    private fun hhmm(ts: Long): String = if (ts <= 0L) "s/d" else HORA.format(Date(ts)).substring(0, 5)
    private fun kmNum(v: Double): String =
        if (Math.abs(v - Math.round(v)) < 0.05) Math.round(v).toString()
        else String.format(Locale.US, "%.1f", v).replace('.', ',')
    private fun km(v: Double): String = kmNum(v) + " km"
    private fun red1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun minutosTxt(ms: Long): String {
        if (ms < 0) return "s/d"
        val min = ms / 60000L
        val seg = (ms % 60000L) / 1000L
        return if (min > 0) min.toString() + " minutos" + (if (seg > 0) " " + seg + " s" else "") else seg.toString() + " segundos"
    }

    private fun minutos(nota: String): Int? =
        Regex("min=(\\d{1,3})").find(nota)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?:^|\\s)(\\d{1,3})\\s*min\\b").find(nota)?.groupValues?.get(1)?.toIntOrNull()

    private fun tramoKm(nota: String): Double? =
        Regex("tramo=([0-9]+(?:[.,][0-9]+)?)").find(nota)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull()

    private fun kmRuta(nota: String): Double? {
        Regex("km=([0-9]+(?:[.,][0-9]+)?)").find(nota)?.let {
            val v = it.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (v != null && v < 1000.0) return v
        }
        Regex("\\(([0-9]+[.,][0-9]+)\\s*km\\)").find(nota)?.let {
            return it.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        return null
    }

    private fun odom(nota: String): Double? =
        Regex("(?:km|odom)=([0-9]{4,6}(?:[.,][0-9]+)?)").find(nota)?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull()

    // ---------------- lienzo A4 ----------------

    private class Hoja(private val episodio: String) {
        private val ANCHO = 515f
        private val IZQ = 40f
        private val DER = 555f
        private val TOPE = 798f
        private val doc = PdfDocument()
        private var page: PdfDocument.Page? = null
        private var num = 0
        private var y = 0f

        private val pTit = Paint().apply { textSize = 20f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val pSub = Paint().apply { textSize = 12f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        private val pSec = Paint().apply { textSize = 11.5f; isFakeBoldText = true }
        private val pSub2 = Paint().apply { textSize = 10.5f; isFakeBoldText = true }
        private val pTxt = Paint().apply { textSize = 10f }
        private val pItal = Paint().apply { textSize = 8.5f; textSkewX = -0.18f; textAlign = Paint.Align.CENTER }
        private val pMini = Paint().apply { textSize = 8f; color = Color.rgb(85, 85, 85) }
        private val pPie = Paint().apply { textSize = 7.5f; color = Color.rgb(102, 102, 102) }
        private val pPieDer = Paint().apply { textSize = 7.5f; color = Color.rgb(102, 102, 102); textAlign = Paint.Align.RIGHT }
        private val pLin = Paint().apply { strokeWidth = 0.6f; color = Color.rgb(148, 163, 184) }
        private val pImg = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

        init { abrir() }

        private fun abrir() {
            if (page != null) cerrar()
            num++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, num).create())
            y = 52f
            if (num > 1) {
                page!!.canvas.drawText("JUSTIFICACIÓN · Episodio " + episodio + " (continúa)", IZQ, y, pMini)
                y += 6f
                page!!.canvas.drawLine(IZQ, y, DER, y, pLin)
                y += 20f
            }
        }

        private fun cerrar() {
            val p = page ?: return
            p.canvas.drawLine(IZQ, 806f, DER, 806f, pLin)
            p.canvas.drawText("Justificación · Episodio " + episodio, IZQ, 818f, pPie)
            p.canvas.drawText("Página " + num, DER, 818f, pPieDer)
            doc.finishPage(p)
            page = null
        }

        fun titulo(t: String) {
            page!!.canvas.drawText(t, (IZQ + DER) / 2f, y + 8f, pTit)
            y += 26f
        }

        fun subtitulo(t: String) {
            page!!.canvas.drawText(t, (IZQ + DER) / 2f, y + 6f, pSub)
            y += 34f
        }

        fun seccion(t: String) {
            if (y + 34f > TOPE) abrir()
            y += 10f
            page!!.canvas.drawText(t, IZQ, y, pSec)
            y += 4f
            page!!.canvas.drawLine(IZQ, y, DER, y, pLin)
            y += 14f
        }

        fun subseccion(t: String) {
            if (y + 24f > TOPE) abrir()
            y += 6f
            page!!.canvas.drawText(t, IZQ, y, pSub2)
            y += 6f
        }

        fun aritmetica(t: String) {
            if (y + 18f > TOPE) abrir()
            val p = Paint(pTxt).apply { textAlign = Paint.Align.CENTER }
            page!!.canvas.drawText(t, (IZQ + DER) / 2f, y + 10f, p)
            y += 20f
        }

        fun parrafo(t: String, justificar: Boolean) {
            val lineas = envolver(t, ANCHO, pTxt)
            for ((k, l) in lineas.withIndex()) {
                if (y + 13f > TOPE) abrir()
                val ultima = k == lineas.size - 1
                if (justificar && !ultima) justificada(l, y) else page!!.canvas.drawText(l, IZQ, y, pTxt)
                y += 13.6f
            }
            y += 5f
        }

        /** Reparte el espacio sobrante entre palabras (texto justificado como el original). */
        private fun justificada(linea: String, yy: Float) {
            val palabras = linea.trim().split(" ").filter { it.isNotEmpty() }
            if (palabras.size < 2) { page!!.canvas.drawText(linea, IZQ, yy, pTxt); return }
            var anchoPalabras = 0f
            for (p in palabras) anchoPalabras += pTxt.measureText(p)
            val hueco = (ANCHO - anchoPalabras) / (palabras.size - 1)
            if (hueco > 18f || hueco < 1f) { page!!.canvas.drawText(linea, IZQ, yy, pTxt); return }
            var x = IZQ
            for (p in palabras) {
                page!!.canvas.drawText(p, x, yy, pTxt)
                x += pTxt.measureText(p) + hueco
            }
        }

        fun tablaDatos(filas: List<Pair<String, String>>) {
            val w1 = 118f
            val w2 = ANCHO - w1
            val pB = Paint().apply { textSize = 9.5f; isFakeBoldText = true }
            for ((etq, valor) in filas) {
                val lineas = envolver(valor, w2 - 10f, pTxt)
                val alto = Math.max(22f, lineas.size * 12.5f + 9f)
                if (y + alto > TOPE) abrir()
                val r = RectF(IZQ, y - 11f, DER, y - 11f + alto)
                page!!.canvas.drawRect(r, pLin)
                page!!.canvas.drawLine(IZQ + w1, r.top, IZQ + w1, r.bottom, pLin)
                page!!.canvas.drawText(etq, IZQ + 5f, y + 3f, pB)
                var yy = y + 3f
                for (l in lineas) {
                    page!!.canvas.drawText(l, IZQ + w1 + 5f, yy, pTxt)
                    yy += 12.5f
                }
                y += alto
            }
            y += 12f
        }

        fun imagen(ruta: String, pie: String) {
            val bmp = cargar(ruta) ?: return
            try {
                var w = Math.min(ANCHO, bmp.width.toFloat())
                var h = w * bmp.height.toFloat() / bmp.width.toFloat()
                val maxH = 330f
                if (h > maxH) {
                    h = maxH
                    w = h * bmp.width.toFloat() / bmp.height.toFloat()
                }
                val caps = envolver(pie, ANCHO - 30f, pItal)
                val total = h + caps.size * 10.5f + 14f
                if (y + total > TOPE) abrir()
                val x = IZQ + (ANCHO - w) / 2f
                page!!.canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), pImg)
                y += h + 5f
                for (c in caps) {
                    page!!.canvas.drawText(c, (IZQ + DER) / 2f, y + 8f, pItal)
                    y += 10.5f
                }
                y += 11f
            } finally {
                bmp.recycle()
            }
        }

        fun escribir(out: OutputStream) {
            cerrar()
            doc.writeTo(out)
            doc.close()
        }

        private fun cargar(ruta: String): Bitmap? {
            return try {
                if (ruta.isEmpty() || !File(ruta).exists()) return null
                val b = BitmapFactory.Options()
                b.inJustDecodeBounds = true
                BitmapFactory.decodeFile(ruta, b)
                var esc = 1
                while (b.outWidth / (esc * 2) >= 900) esc *= 2
                val o = BitmapFactory.Options()
                o.inSampleSize = esc
                BitmapFactory.decodeFile(ruta, o)
            } catch (e: Exception) {
                null
            }
        }

        private fun envolver(t: String, ancho: Float, paint: Paint): List<String> {
            val s = t.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
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
