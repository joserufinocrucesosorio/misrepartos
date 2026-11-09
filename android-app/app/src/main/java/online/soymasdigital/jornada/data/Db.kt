package online.soymasdigital.jornada.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "laboral.db", null, 8) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE jornada(id INTEGER PRIMARY KEY AUTOINCREMENT, fecha TEXT, inicio INTEGER, fin INTEGER, estado TEXT, prog_ini INTEGER, prog_fin INTEGER)")
        db.execSQL("CREATE TABLE flujo(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, pedido TEXT, paso TEXT, ts INTEGER, nota TEXT)")
        db.execSQL("CREATE TABLE gasto(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, ts INTEGER, tipo TEXT, importe REAL, litros REAL, km REAL, foto TEXT, nota TEXT)")
        db.execSQL("CREATE TABLE snapshot_cartera(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, momento TEXT, ts INTEGER, saldo REAL, metodo TEXT, estado TEXT, captura TEXT)")
        db.execSQL("CREATE TABLE incidencia(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, id_pedido TEXT, ts_deteccion INTEGER, importe REAL, pago TEXT, cobrado TEXT, estado TEXT, cierre_por TEXT, ts_cierre INTEGER, notas TEXT)")
        db.execSQL("CREATE TABLE llamada(id INTEGER PRIMARY KEY AUTOINCREMENT, incidencia_id INTEGER, ts INTEGER, numero TEXT, duracion INTEGER, resultado TEXT)")
        db.execSQL("CREATE TABLE evidencia(id INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT, ts INTEGER, tipo TEXT, texto TEXT, archivo TEXT)")
        db.execSQL("CREATE TABLE km(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, momento TEXT, ts INTEGER, valor REAL, foto TEXT)")
        db.execSQL("CREATE TABLE meteo(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, ts INTEGER, tipo TEXT, nota TEXT)")
        db.execSQL("CREATE TABLE consignacion(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, ts INTEGER, importe REAL, comision REAL, oficina TEXT, puesto TEXT, clave TEXT, id_ingreso TEXT, telefono TEXT, emisora TEXT, foto TEXT, nota TEXT)")
        db.execSQL("CREATE TABLE pedido_dato(pedido TEXT, campo TEXT, valor TEXT, ts INTEGER, PRIMARY KEY(pedido, campo))")
        db.execSQL("CREATE TABLE ajuste(clave TEXT PRIMARY KEY, valor TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS km(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, momento TEXT, ts INTEGER, valor REAL, foto TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS meteo(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, ts INTEGER, tipo TEXT, nota TEXT)")
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE jornada ADD COLUMN prog_ini INTEGER") } catch (e: Exception) { }
            try { db.execSQL("ALTER TABLE jornada ADD COLUMN prog_fin INTEGER") } catch (e: Exception) { }
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS flujo(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, pedido TEXT, paso TEXT, ts INTEGER, nota TEXT)")
        }
        if (oldVersion < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS gasto(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, ts INTEGER, tipo TEXT, importe REAL, litros REAL, km REAL, foto TEXT, nota TEXT)")
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS consignacion(id INTEGER PRIMARY KEY AUTOINCREMENT, jornada_id INTEGER, ts INTEGER, importe REAL, comision REAL, oficina TEXT, puesto TEXT, clave TEXT, id_ingreso TEXT, telefono TEXT, emisora TEXT, foto TEXT, nota TEXT)")
        }
        if (oldVersion < 7) {
            // FASE 1 (22/09/2026): datos estructurados del pedido extraidos del texto de las pantallas
            db.execSQL("CREATE TABLE IF NOT EXISTS pedido_dato(pedido TEXT, campo TEXT, valor TEXT, ts INTEGER, PRIMARY KEY(pedido, campo))")
        }
        if (oldVersion < 8) {
            // v1.5: ajustes de la app (tarifa EUR/km de la factura de cobro)
            db.execSQL("CREATE TABLE IF NOT EXISTS ajuste(clave TEXT PRIMARY KEY, valor TEXT)")
        }
    }
}

data class Snap(val momento: String, val ts: Long, val saldo: Double, val metodo: String)
data class Inc(val id: Long, val momento: Long, val importe: Double, val pago: String, val cobrado: String, val estado: String, val cierre: String)
data class Km(val momento: String, val ts: Long, val valor: Double, val foto: String)

data class Gasto(val ts: Long, val tipo: String, val importe: Double, val litros: Double, val km: Double, val foto: String, val nota: String)
data class Consig(val id: Long, val ts: Long, val importe: Double, val comision: Double, val oficina: String,
                  val puesto: String, val clave: String, val idIngreso: String, val foto: String, val nota: String)
data class Met(val ts: Long, val tipo: String, val nota: String)
data class Ped(var id: String, var primera: Long, var ultima: Long, var num: String, var local: String,
               var recogida: Long?, var entrega: Long?, var reasignado: Long?) {
    fun totalMin(): Int = (((entrega ?: reasignado ?: recogida ?: ultima) - primera) / 60000L).toInt()
}

class Repo(ctx: Context) {
    private val helper = Db(ctx)
    private val appCtx: Context = ctx.applicationContext
    private val db get() = helper.writableDatabase
    private val dia = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val diaHora = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
    private val PEDIDO = Pattern.compile("\\b(101[0-9]{9})\\b")
    private val PEDIDO_JORNADA = "(jornada)"

    // ---------------- jornada ----------------

    /** Jornada abierta (fin NULL), sea del dia que sea: NUNCA se rota sola en silencio. */
    fun jornadaAbierta(): Long? {
        db.rawQuery("SELECT id FROM jornada WHERE fin IS NULL ORDER BY id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    fun abrirJornada(): Long {
        jornadaAbierta()?.let { return it }
        val cv = ContentValues()
        cv.put("fecha", dia.format(Date()))
        cv.put("inicio", System.currentTimeMillis())
        cv.put("estado", "EN_CURSO")
        return db.insert("jornada", null, cv)
    }

    fun jornadaActiva(): Long = jornadaAbierta() ?: abrirJornada()

    /** Ultima jornada que tiene datos (cartera o evidencias): para el expediente cuando no hay ninguna abierta. */    /** ¿La jornada tiene algun dato? Sirve para cancelar jornadas vacias sin tocar el cierre duro. */
    fun jornadaTieneDatos(jid: Long): Boolean {
        if (totalEvidenciasJornada(jid) > 0) return true
        if (saldoDe(jid, "INICIO") != null || saldoDe(jid, "FIN") != null) return true
        if (kmDe(jid, "INICIO") != null || kmDe(jid, "FIN") != null) return true
        if (kms(jid).isNotEmpty() || meteo(jid).isNotEmpty() || incidencias(jid).isNotEmpty()) return true
        return false
    }

    fun ultimaJornadaUtil(): Long? {
        val lista = ArrayList<Long>()
        db.rawQuery("SELECT id FROM jornada ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) lista.add(c.getLong(0))
        }
        for (id in lista) {
            if (totalEvidenciasJornada(id) > 0 || saldoDe(id, "INICIO") != null || saldoDe(id, "FIN") != null) return id
        }
        return null
    }

    fun jornada(fid: Long): Triple<String, Long, Long?>? {
        db.rawQuery("SELECT fecha, inicio, fin FROM jornada WHERE id=?", arrayOf(fid.toString())).use { c ->
            if (c.moveToFirst()) return Triple(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))
        }
        return null
    }

    /** v1.9.9: estado guardado de la jornada (EN_CURSO / CERRADA / CERRADA_POR_EL_REPARTIDOR). */
    fun estadoJornada(jid: Long): String {
        db.rawQuery("SELECT IFNULL(estado,'') FROM jornada WHERE id=?", arrayOf(jid.toString())).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return ""
    }

    /** v1.9.9: jornadas de una fecha, en orden de inicio (el indice + 1 es el numero de turno). */
    fun jornadasDelDia(fecha: String): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery("SELECT id FROM jornada WHERE fecha=? ORDER BY inicio, id", arrayOf(fecha)).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** v1.9.9: numero de turno de la jornada dentro de su dia (1 = primera del dia). */
    fun turnoDe(jid: Long): Int {
        val j = jornada(jid) ?: return 1
        val i = jornadasDelDia(j.first).indexOf(jid)
        return if (i >= 0) i + 1 else 1
    }

    /** v1.9.9: jornada de HOY (la abierta si la hay; si no, la ultima del dia). */
    fun jornadaDeHoy(): Long? {
        val ids = jornadasDelDia(dia.format(Date()))
        if (ids.isEmpty()) return null
        return ids.lastOrNull { jornada(it)?.third == null } ?: ids.last()
    }

    fun cerrarJornada(jid: Long, estado: String) {
        val cv = ContentValues()
        cv.put("fin", System.currentTimeMillis())
        cv.put("estado", estado)
        db.update("jornada", cv, "id=?", arrayOf(jid.toString()))
    }

    /** Rango temporal de la jornada: [inicio, fin o ahora] — sirve aunque cruce la medianoche. */
    private fun rango(jid: Long): Pair<Long, Long> {
        val j = jornada(jid)
        return if (j == null) 0L to System.currentTimeMillis()
        else j.second to (j.third ?: System.currentTimeMillis())
    }

    // ---------------- horario programado y TIEMPO COMPLEMENTARIO ----------------

    /** Horario programado por la empresa para esa jornada (inicio y fin, en ms). */
    fun guardarHorario(jid: Long, progIni: Long, progFin: Long) {
        val cv = ContentValues()
        cv.put("prog_ini", progIni); cv.put("prog_fin", progFin)
        db.update("jornada", cv, "id=?", arrayOf(jid.toString()))
    }

    fun horario(jid: Long): Pair<Long?, Long?> {
        db.rawQuery("SELECT prog_ini, prog_fin FROM jornada WHERE id=?", arrayOf(jid.toString())).use { c ->
            if (c.moveToFirst()) return (if (c.isNull(0)) null else c.getLong(0)) to (if (c.isNull(1)) null else c.getLong(1))
        }
        return null to null
    }

    /** Hora de entrega del ULTIMO pedido entregado de la jornada. */
    fun ultimaEntrega(jid: Long): Long? = pedidos(jid).mapNotNull { it.entrega }.maxOrNull()

    /** Ultima senal de pantalla registrada (respaldo si aun no hay entregas confirmadas). */
    fun ultimaSenal(jid: Long): Long? {
        val ev = evidenciasJornada(jid)
        return if (ev.isEmpty()) null else ev.maxOf { it.first }
    }

    /**
     * TIEMPO COMPLEMENTARIO = hora de entrega del ultimo pedido - hora final programada.
     * Positivo = se entrego DESPUES del fin programado (tiempo adicional fuera de la jornada).
     */
    fun tiempoComplementario(jid: Long): Long? {
        val (_, pf) = horario(jid)
        if (pf == null) return null
        val u = ultimaEntrega(jid) ?: return null
        return u - pf
    }

    fun jornadasRecientes(limite: Int): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery("SELECT id FROM jornada ORDER BY id DESC LIMIT ?", arrayOf(limite.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out.reversed()
    }

    fun duracion(ms: Long): String {
        val signo = if (ms < 0) "-" else ""
        val m = Math.abs(ms) / 60000L
        return signo + (m / 60) + " h " + String.format(Locale.US, "%02d", m % 60) + " min"
    }

    // ---------------- flujo por pedido (fases marcadas por el repartidor) ----------------

    /**
     * v1.8: registra una fase del flujo. Devuelve FALSE si se rechaza por DUPLICADO
     * (mismo paso con el mismo km en menos de 30 s: VUELTA_SALIDA x3 / LLEGADA_CASA x2 del 01/10).
     */
    fun registrarFase(jid: Long, pedido: String, paso: String, nota: String): Boolean {
        if (pasoDuplicado(jid, pedido, paso, nota)) return false
        // v1.9.8: reglas de LOTE (recogida compartida / entregas encadenadas)
        val (tsLote, notaLote) = ajusteLote(jid, pedido, paso, System.currentTimeMillis(), nota)
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("pedido", pedido); cv.put("paso", paso)
        cv.put("ts", tsLote); cv.put("nota", notaLote)
        db.insert("flujo", null, cv)
        return true
    }

    private fun kmDeNota(nota: String): String? =
        Regex("km=([0-9]+(?:[.,][0-9]+)?)").find(nota)?.groupValues?.get(1)?.replace(',', '.')

    /** v1.9.5: lectura de ODOMETRO (4-6 digitos) anotada en la nota de una fase, con clave km= u odom=. */
    private fun odomDeNota(nota: String): Double? =
        Regex("(?:km|odom)=([0-9]{4,6}(?:[.,][0-9]+)?)").find(nota)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    /** v1.9.5: lectura del odometro anotada en un hito del pedido (clave km= u odom=). */
    fun odometroDe(jid: Long, pedido: String, paso: String): Double? =
        fases(jid, pedido).lastOrNull { it.first == paso }?.third?.let { odomDeNota(it) }

    /**
     * REGLA ANTIDUPLICADO (documentada):
     *  - v1.8: mismo paso + mismo km (o nota identica, sin km) en los ultimos 30 s -> duplicado.
     *  - v1.9.2: ADEMAS, si la nota trae km, el MISMO paso con el MISMO km se rechaza durante los
     *    ultimos 15 min de la jornada. Caso real del 04/10 (jornada 25): VUELTA_SALIDA registrada
     *    dos veces, 16:03 y 16:29, con el mismo km 50780,0; pasaba la ventana de 30 s y confundia
     *    la SALIDA hacia casa con la LLEGADA a casa.
     *  - Salvo casos legitimos: si el km es DISTINTO (avance real) se admite; y los pasos de
     *    INICIO / FIN / INICIO_ZONA no se limitan por esta regla.
     */
    private fun pasoDuplicado(jid: Long, pedido: String, paso: String, nota: String): Boolean {
        val kmNuevo = kmDeNota(nota)
        val exento = paso == "INICIO" || paso == "FIN" || paso == "INICIO_ZONA"
        val ventana = if (kmNuevo != null && !exento) 15L * 60L * 1000L else 30_000L
        val desde = System.currentTimeMillis() - ventana
        db.rawQuery("SELECT nota, ts FROM flujo WHERE jornada_id=? AND pedido=? AND paso=? AND ts>=? ORDER BY ts DESC LIMIT 1",
                arrayOf(jid.toString(), pedido, paso, desde.toString())).use { c ->
            if (c.moveToNext()) {
                val prev = c.getString(0) ?: ""
                val prevTs = c.getLong(1)
                val kmPrev = kmDeNota(prev)
                if (kmNuevo != null && kmNuevo == kmPrev) return true
                // nota identica SIN km: solo se bloquea en los 30 s inmediatos (evita falsos positivos)
                if (kmNuevo == null && kmPrev == null && prev == nota &&
                        System.currentTimeMillis() - prevTs <= 30_000L) return true
            }
        }
        return false
    }

    /** Igual que registrarFase pero con la HORA REAL del evento (la pantalla, no el momento de grabarlo). */
    fun registrarFaseTs(jid: Long, pedido: String, paso: String, ts: Long, nota: String) {
        // v1.9.8: reglas de LOTE (recogida compartida / entregas encadenadas)
        val (tsLote, notaLote) = ajusteLote(jid, pedido, paso, ts, nota)
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("pedido", pedido); cv.put("paso", paso)
        cv.put("ts", tsLote); cv.put("nota", notaLote)
        db.insert("flujo", null, cv)
    }

    // ---------------- v1.9.8: LOTES (pedidos dobles/triples del mismo local) ----------------

    private fun tsFase(jid: Long, pedido: String, paso: String): Long? =
        fases(jid, pedido).lastOrNull { it.first == paso }?.second

    private fun notaFase(jid: Long, pedido: String, paso: String): String =
        fases(jid, pedido).lastOrNull { it.first == paso }?.third ?: ""

    /** Lectura de odometro (4-6 digitos) tal cual se anoto en una nota, para copiarla sin reinterpretar. */
    private fun lecturaOdom(nota: String): String? =
        Regex("(?:km|odom)=[0-9]{4,6}(?:[.,][0-9]+)?").find(nota)?.value

    /**
     * v1.9.8: LOTE = pedidos del MISMO local cuyo RECIBO_PEDIDO cae a 60 s o menos del de este pedido.
     * Devuelve los ids ordenados por hora de recibo (el pedido dado siempre va incluido).
     */
    fun loteDe(jid: Long, pedido: String): List<String> {
        val p = pedidos(jid).firstOrNull { it.id == pedido } ?: return listOf(pedido)
        val local = p.local
        val ts = tsFase(jid, pedido, "RECIBO_PEDIDO") ?: p.primera
        val miembros = ArrayList<Pair<String, Long>>()
        miembros.add(pedido to ts)
        if (local.isNotEmpty()) {
            for (o in pedidos(jid)) {
                if (o.id == pedido || o.local != local) continue
                val tsO = tsFase(jid, o.id, "RECIBO_PEDIDO") ?: continue
                if (Math.abs(tsO - ts) <= 60_000L) miembros.add(o.id to tsO)
            }
        }
        return miembros.sortedBy { it.second }.map { it.first }
    }

    /**
     * v1.9.8: aplica las reglas del LOTE a la fase que se va a guardar.
     *  - RECOGIDA: todos comparten la PRIMERA recogida del lote (misma hora y mismo km/odom).
     *  - ENTREGA del 2.º/3.º: se anota en la nota la entrega anterior y el km del tramo
     *    (odometro de LLEGADA_CLIENTE de ambos pedidos). La hora de la entrega NO se toca.
     */
    private fun ajusteLote(jid: Long, pedido: String, paso: String, ts: Long, nota: String): Pair<Long, String> {
        if (paso != "RECOGIDA" && paso != "ENTREGA") return Pair(ts, nota)
        val lote = loteDe(jid, pedido)
        if (lote.size < 2) return Pair(ts, nota)
        if (paso == "RECOGIDA") {
            // la recogida del lote es la PRIMERA que se registro (hora y km unicos)
            val prev = lote.filter { it != pedido && tieneFase(jid, it, "RECOGIDA") }
                    .minByOrNull { tsFase(jid, it, "RECOGIDA") ?: Long.MAX_VALUE }
            if (prev != null) {
                val tsPrev = tsFase(jid, prev, "RECOGIDA") ?: ts
                val lectura = lecturaOdom(notaFase(jid, prev, "RECOGIDA"))
                val marca = "lote: misma recogida que " + prev + (if (lectura != null) " " + lectura else "")
                return Pair(tsPrev, if (nota.isEmpty()) marca else nota + " | " + marca)
            }
            return Pair(ts, nota)
        }
        // ENTREGA: solo el 2.º/3.º del lote
        val i = lote.indexOf(pedido)
        if (i <= 0) return Pair(ts, nota)
        val prev = lote.subList(0, i).lastOrNull { tieneFase(jid, it, "ENTREGA") } ?: return Pair(ts, nota)
        val tsPrev = tsFase(jid, prev, "ENTREGA") ?: return Pair(ts, nota)
        val odomPrev = odometroDe(jid, prev, "LLEGADA_CLIENTE")
        val odomEste = odometroDe(jid, pedido, "LLEGADA_CLIENTE")
        val tramo = if (odomPrev != null && odomEste != null && odomEste > odomPrev)
            String.format(Locale.US, "%.1f", odomEste - odomPrev) else ""
        val marca = "lote=" + (i + 1) + "/" + lote.size + " desde_entrega=" + prev + "@" + hora.format(Date(tsPrev)) +
                (if (tramo.isNotEmpty()) " tramo=" + tramo else "")
        return Pair(ts, if (nota.isEmpty()) marca else nota + " | " + marca)
    }

    fun tieneFase(jid: Long, pedido: String, paso: String): Boolean {
        db.rawQuery("SELECT 1 FROM flujo WHERE jornada_id=? AND pedido=? AND paso=? LIMIT 1",
                arrayOf(jid.toString(), pedido, paso)).use { c -> return c.moveToFirst() }
    }

    // ---------------- FASE 1: datos estructurados del pedido ----------------

    /** Guarda (o actualiza) un campo del pedido extraido del texto de la pantalla. */
    fun guardarDatoPedido(pedido: String, campo: String, valor: String, ts: Long) {
        if (valor.isEmpty()) return
        val cv = ContentValues()
        cv.put("pedido", pedido); cv.put("campo", campo); cv.put("valor", valor); cv.put("ts", ts)
        db.insertWithOnConflict("pedido_dato", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun datosPedido(pedido: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        db.rawQuery("SELECT campo, valor FROM pedido_dato WHERE pedido=? ORDER BY campo", arrayOf(pedido)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    // ---------------- FASE 2: auto-flujo sin digitar ----------------

    /**
     * Crea/actualiza SOLO con lo que se ve en la pantalla: el pedido, sus fases
     * (RECIBO_PEDIDO / RECOGIDA / ENTREGA), sus datos y el pedido ACTIVO del flotante.
     * Devuelve cuantas novedades ha registrado. No toca nada de la app de reparto.
     */
    fun autoFlujo(jid: Long, texto: String, ts: Long): Int {
        val campos = Parser.parsear(texto)
        if (campos.isEmpty()) return 0
        var n = 0
        val esRec = Parser.esRecogida(texto)
        val esEnt = Parser.esEntrega(texto)
        for (c in campos) {
            // 1) datos estructurados (FASE 1)
            guardarDatoPedido(c.pedido, "num", c.num, ts)
            guardarDatoPedido(c.pedido, "local", c.local, ts)
            guardarDatoPedido(c.pedido, "dir_recogida", c.dirRecogida, ts)
            guardarDatoPedido(c.pedido, "cliente", c.cliente, ts)
            guardarDatoPedido(c.pedido, "dir_entrega", c.dirEntrega, ts)
            guardarDatoPedido(c.pedido, "pago", c.pago, ts)
            c.importe?.let { guardarDatoPedido(c.pedido, "importe", String.format(Locale.US, "%.2f", it), ts) }
            c.productos?.let { guardarDatoPedido(c.pedido, "productos", it.toString(), ts) }
            c.retrasoMin?.let { guardarDatoPedido(c.pedido, "retraso_min", it.toString(), ts) }
            c.listoMin?.let { guardarDatoPedido(c.pedido, "listo_min", it.toString(), ts) }
            // 2) fases automaticas (FASE 2)
            if (!tieneFase(jid, c.pedido, "RECIBO_PEDIDO")) {
                registrarFaseTs(jid, c.pedido, "RECIBO_PEDIDO", ts,
                        "auto pantalla" + (if (c.local.isNotEmpty()) ": " + c.local else ""))
                n++
            }
            if (esRec && !tieneFase(jid, c.pedido, "RECOGIDA")) {
                registrarFaseTs(jid, c.pedido, "RECOGIDA", ts, "auto pantalla (recogida confirmada)")
                n++
            }
            if (esEnt && !tieneFase(jid, c.pedido, "ENTREGA")) {
                registrarFaseTs(jid, c.pedido, "ENTREGA", ts, "auto pantalla (entrega confirmada)")
                n++
            }
            // 3) pedido ACTIVO del flotante: el ultimo aceptado sin entrega
            if (!esEnt) {
                val sinEntrega = pedidos(jid).filter { it.entrega == null && it.reasignado == null }
                sinEntrega.maxByOrNull { it.primera }?.let {
                    appCtx.getSharedPreferences("jornada_prefs", Context.MODE_PRIVATE)
                            .edit().putString("activo_" + jid, it.id).apply()
                }
            }
        }
        return n
    }

    fun fases(jid: Long, pedido: String): List<Triple<String, Long, String>> {
        val out = ArrayList<Triple<String, Long, String>>()
        db.rawQuery("SELECT paso, ts, IFNULL(nota,'') FROM flujo WHERE jornada_id=? AND pedido=? ORDER BY ts",
                arrayOf(jid.toString(), pedido)).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getLong(1), c.getString(2)))
        }
        return out
    }

    /**
     * Ultima lectura de cuentakilometros anotada en el flujo de esta jornada.
     * Sin prefijo fijo: vale para cualquier lectura (4-6 digitos). Los km de Maps
     * ("km=5,1") no cuadran con el patron y se descartan solos.
     */
    fun ultimoCuentakm(jid: Long): Double? {
        db.rawQuery("SELECT nota FROM flujo WHERE jornada_id=? AND (nota LIKE '%km=%' OR nota LIKE '%odom=%') ORDER BY ts DESC LIMIT 30",
                arrayOf(jid.toString())).use { c ->
            while (c.moveToNext()) {
                Regex("(?:km|odom)=([0-9]{4,6}(?:[.,][0-9]+)?)").find(c.getString(0))?.let {
                    it.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { v -> return v }
                }
            }
        }
        return null
    }

    /**
     * Fases EFECTIVAS = las que la app detecta sola de las pantallas (marca "auto") + las marcadas
     * por el repartidor con el boton 5 (la marca manual manda sobre la auto).
     */
    fun fasesEfectivas(jid: Long, pedido: String): List<Triple<String, Long, String>> {
        val ef = LinkedHashMap<String, Triple<String, Long, String>>()
        val p = pedidos(jid).firstOrNull { it.id == pedido }
        // v1.9.5: una fase "auto" solo cuenta si el pedido es REAL (tiene RECIBO_PEDIDO registrado).
        val recibo = tieneFase(jid, pedido, "RECIBO_PEDIDO")
        val recogida = tieneFase(jid, pedido, "RECOGIDA") || (p?.recogida != null)
        if (p != null && recibo) {
            ef["RECIBO_PEDIDO"] = Triple("RECIBO_PEDIDO", p.primera, "auto")
            p.recogida?.let { ef["RECOGIDA"] = Triple("RECOGIDA", it, "auto") }
            p.entrega?.let { ef["ENTREGA"] = Triple("ENTREGA", it, "auto") }
        }
        // Estimaciones de Maps capturadas automaticamente en pantalla (km y min del trayecto).
        // v1.9.5: RUTA_LOCAL exige pedido RECIBIDO y SALIDA_CLIENTE (fase 7) exige RECOGIDA real:
        // antes la fase 7 aparecia "marcada sola" sin haber recibido el pedido (informe 07/10).
        if (p != null && recibo) {
            estimacionMaps(jid, pedido, true)?.let {
                ef["RUTA_LOCAL"] = Triple("RUTA_LOCAL", it.first, "auto Maps " + it.second + " min (" + kmTxt(it.third) + " km)")
            }
        }
        if (recogida) {
            estimacionMaps(jid, pedido, false)?.let {
                ef["SALIDA_CLIENTE"] = Triple("SALIDA_CLIENTE", it.first, "auto Maps " + it.second + " min (" + kmTxt(it.third) + " km)")
            }
        }
        // Lote (pedidos a la vez) detectado en la pantalla de aceptacion ("2 pedidos")
        run {
            var lote = 0
            var tsLote = 0L
            val p = pedidos(jid).firstOrNull { it.id == pedido }
            if (p != null) {
                for ((ts, tipo, texto) in evidenciasJornada(jid)) {
                    if (ts < p.primera || ts > (p.entrega ?: p.ultima)) continue
                    for (m in Regex("(\\d+)\\s+pedidos?\\b").findAll(texto)) {
                        val n = m.groupValues[1].toIntOrNull() ?: 0
                        if (n > lote) { lote = n; tsLote = ts }
                    }
                }
            }
            if (lote >= 2) ef["LOTE"] = Triple("LOTE", tsLote, "auto lote de " + lote + " pedidos")
        }
        for (f in fases(jid, pedido)) ef[f.first] = f
        return ef.values.sortedBy { it.second }
    }

    private val RX_MAPS = Regex("(\\d+)\\s*min\\s*\\(?\\s*(\\d+[.,]?\\d*)\\s*km")
    private fun kmTxt(v: Double): String = String.format(Locale.US, "%.1f", v).replace('.', ',')

    /**
     * Estimacion de Maps capturada sola de la pantalla (tipo MAPS_*): minutos y km del trayecto.
     * haciaLocal=true -> el primer tramo (antes de recoger); false -> el tramo al cliente.
     */
    fun estimacionMaps(jid: Long, pedido: String, haciaLocal: Boolean): Triple<Long, Int, Double>? {
        val p = pedidos(jid).firstOrNull { it.id == pedido } ?: return null
        val desde = if (haciaLocal) p.primera else (p.recogida ?: p.primera)
        val hasta = if (haciaLocal) (p.recogida ?: p.entrega ?: p.ultima) else (p.entrega ?: p.ultima)
        for ((ts, tipo, texto) in evidenciasJornada(jid)) {
            if (ts < desde || ts > hasta) continue
            if (!tipo.startsWith("MAPS")) continue
            val m = RX_MAPS.find(texto) ?: continue
            val mn = m.groupValues[1].toIntOrNull() ?: continue
            val km = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            return Triple(ts, mn, km)
        }
        return null
    }

    /** Pedidos de la jornada con su ultima fase marcada (para el selector del flujo). */
    fun pedidosSinCerrar(jid: Long): List<Ped> {
        return pedidos(jid).filter { p -> fases(jid, p.id).none { it.first == "LLEGADA_MOTO_FINAL" } }
    }

    /** v1.8: pedidos abiertos del MISMO local que el pedido dado (para agrupar la recogida por local). */
    fun pedidosMismoLocal(jid: Long, pedido: String): List<Ped> {
        val p = pedidos(jid).firstOrNull { it.id == pedido } ?: return emptyList()
        val local = p.local
        if (local.isNullOrEmpty()) return emptyList()
        return pedidosSinCerrar(jid).filter { it.id != pedido && it.local == local }
    }

    /**
     * v1.9.2: motivo por el que una fase queda BLOQUEADA por ORDEN del flujo (null = se puede marcar).
     * Regla (04/10): una vez que el pedido -o su local en un LOTE- ya tiene RECOGIDA, no se repiten
     * RUTA_LOCAL / LLEGADA_PARKING / LLEGADA_LOCAL / RECOGIDA (el segundo pedido no puede reabrir la
     * recogida ni volver a pedir ruta/parking/local del mismo viaje).
     */
    fun motivoBloqueo(jid: Long, pedido: String, paso: String): String? {
        val previos = setOf("RUTA_LOCAL", "LLEGADA_PARKING", "LLEGADA_LOCAL", "RECOGIDA")
        if (paso !in previos) return null
        if (tieneFase(jid, pedido, "RECOGIDA"))
            return "Este pedido ya tiene RECOGIDA: no se repite el viaje (ruta / parking / local / recogida)."
        val local = pedidos(jid).firstOrNull { it.id == pedido }?.local ?: ""
        if (local.isNotEmpty()) {
            val otro = pedidosSinCerrar(jid).firstOrNull {
                it.id != pedido && it.local == local && tieneFase(jid, it.id, "RECOGIDA")
            }
            if (otro != null)
                return "RECOGIDA DEL LOTE ya registrada (pedido " + otro.id + ", mismo local '" + local +
                        "'): no se vuelve a pedir ruta/parking/local ni a reabrir la recogida."
        }
        return motivoOrden(jid, pedido, paso)
    }

    /**
     * v1.9.5: refuerza el ORDEN de los hitos. Devuelve el motivo cuando el paso pedido llegaria
     * ANTES que su predecesor obligatorio (inversiones documentadas 05-07/10: ENTREGA antes de
     * LLEGADA_CLIENTE, LLEGADA_LOCAL antes de LLEGADA_PARKING y LLEGADA_MOTO antes de RECOGIDA).
     */
    private fun motivoOrden(jid: Long, pedido: String, paso: String): String? {
        val requerido = when (paso) {
            "LLEGADA_LOCAL" -> "LLEGADA_PARKING"
            "LLEGADA_MOTO" -> "RECOGIDA"
            "SALIDA_CLIENTE" -> "RECOGIDA"
            "ENTREGA" -> "LLEGADA_CLIENTE"
            "LLEGADA_MOTO_FINAL" -> "ENTREGA"
            else -> null
        } ?: return null
        if (tieneFase(jid, pedido, requerido)) return null
        return "ORDEN del flujo: marca primero " + requerido + " antes de " + paso +
                " (si ya lo hiciste, corrige la hora del hito)."
    }

    /** v1.9.2: aviso (no bloqueo) de ENTREGA sin LLEGADA_CLIENTE; exige confirmacion del repartidor. */
    fun avisoEntregaSinLlegada(jid: Long, pedido: String): String? {
        val ya = fasesEfectivas(jid, pedido).map { it.first }.toSet()
        return if ("LLEGADA_CLIENTE" in ya) null
        else "No esta registrada la LLEGADA_CLIENTE: vas a marcar la ENTREGA sin haber llegado al cliente."
    }

    /** Hito de cierre mas reciente de la jornada (para precargar la vuelta a casa). */
    fun ultimoHito(jid: Long): Long? {
        var max: Long? = null
        for (p in pedidos(jid)) {
            for (f in fases(jid, p.id)) {
                if (f.first == "LLEGADA_MOTO_FINAL" || f.first == "ENTREGA") {
                    if (max == null || f.second > max) max = f.second
                }
            }
        }
        return max
    }

    // ---------------- eventos de jornada (fuera de pedido) + gastos ----------------

    fun registrarEventoJornada(jid: Long, paso: String, nota: String): Boolean =
        registrarFase(jid, PEDIDO_JORNADA, paso, nota)

    fun eventosJornada(jid: Long): List<Triple<String, Long, String>> = fases(jid, PEDIDO_JORNADA)

    /**
     * TIEMPO SIN PEDIDOS (aprox.) = duracion de la jornada - suma del tiempo con pedido activo.
     * Incluye la espera inicial (hasta el primer pedido) y todas las esperas intermedias.
     */
    /**
     * Fin EFECTIVO de la jornada: la "llegada a casa" si esta marcada; si no, la primera senal de
     * "Fuera de linea"/"Sesion completada" posterior al ultimo pedido; si no, el cierre del ultimo pedido.
     * (Evita contar como "tiempo sin pedidos" las horas que pasan entre terminar y pulsar cerrar.)
     */
    private fun finEfectivo(jid: Long): Long? {
        var cierreUlt = 0L
        var casa: Long? = null
        for (p in pedidos(jid)) {
            val f = fasesEfectivas(jid, p.id)
            f.firstOrNull { it.first == "LLEGADA_MOTO_FINAL" }?.second?.let { if (it > cierreUlt) cierreUlt = it }
            f.firstOrNull { it.first == "ENTREGA" }?.second?.let { if (it > cierreUlt) cierreUlt = it }
            f.firstOrNull { it.first == "LLEGADA_CASA" }?.second?.let { casa = it }
        }
        eventosJornada(jid).firstOrNull { it.first == "LLEGADA_CASA" }?.let { casa = it.second }
        if (casa != null) return casa
        if (cierreUlt > 0) {
            for ((ts, tipo, texto) in evidenciasJornada(jid)) {
                if (ts > cierreUlt && (texto.contains("Fuera de línea") || texto.contains("Sesión completada"))) return ts
            }
        }
        return if (cierreUlt > 0) cierreUlt else jornada(jid)?.third
    }

    fun tiempoSinPedidos(jid: Long): Long? {
        val j = jornada(jid) ?: return null
        val peds = pedidos(jid)
        if (peds.isEmpty()) return null
        // Inicio efectivo: primera sesion "En linea" de la jornada (si no hay, el inicio de jornada)
        var iniEf = j.second
        for ((ts, tipo, texto) in evidenciasJornada(jid)) {
            if (tipo == "SESION" && ts >= j.second) { iniEf = ts; break }
        }
        // Fin efectivo: el fin de la jornada si esta cerrada; si sigue abierta, el CIERRE DEL ULTIMO PEDIDO
        // (no se extrapola hasta "ahora": si estas en casa, eso no es tiempo sin pedidos)
        var cierreUlt = 0L
        val ivs = ArrayList<Pair<Long, Long>>()
        for (p in peds) {
            val f = fasesEfectivas(jid, p.id)
            val inic = f.firstOrNull { it.first == "RECIBO_PEDIDO" }?.second ?: p.primera
            val cierre = listOfNotNull(
                f.firstOrNull { it.first == "LLEGADA_MOTO_FINAL" }?.second,
                f.firstOrNull { it.first == "ENTREGA" }?.second).maxOrNull() ?: p.ultima
            if (cierre > cierreUlt) cierreUlt = cierre
            if (cierre > inic) ivs.add(inic to cierre)
        }
        val finEf = finEfectivo(jid) ?: return null
        val total = finEf - iniEf
        if (total <= 0) return 0L
        // Se UNEN los intervalos: con lotes dobles/triples no se puede contar el tiempo dos veces
        ivs.sortBy { it.first }
        var activo = 0L
        var a = -1L
        var b = -1L
        for ((x, y) in ivs) {
            if (b < 0) { a = x; b = y }
            else if (x <= b) { if (y > b) b = y }
            else { activo += (b - a); a = x; b = y }
        }
        if (b >= 0) activo += (b - a)
        val muerto = total - activo
        return if (muerto < 0) 0L else muerto
    }

    /** Tramo casa -> zona de trabajo: desde el inicio de jornada hasta ponerse EN LINEA. */
    fun casaAZona(jid: Long): Long? {
        val j = jornada(jid) ?: return null
        for ((ts, tipo, texto) in evidenciasJornada(jid)) {
            if (tipo == "SESION" && ts >= j.second) return ts - j.second
        }
        val ev = eventosJornada(jid).firstOrNull { it.first == "INICIO_ZONA" } ?: return null
        return ev.second - j.second
    }

    /** Km y minutos totales declarados en los reposicionamientos a puntos rojos. */
    fun reposicionamientos(jid: Long): List<Triple<String, Double, Double>> {
        val out = ArrayList<Triple<String, Double, Double>>()
        for ((paso, ts, nota) in eventosJornada(jid)) {
            if (paso != "REPOSICIONAMIENTO") continue
            val km = Regex("km=([0-9]+(?:[.,][0-9]+)?)").find(nota)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
            val mn = Regex("min=([0-9]+)").find(nota)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            out.add(Triple(horaFmt(ts), km, mn))
        }
        return out
    }

    fun registrarGasto(jid: Long, tipo: String, importe: Double, litros: Double, km: Double, foto: String, nota: String): Long {
        // Anti-duplicado (1.1): si en los ultimos 10 min ya hay una fila identica (mismo tipo,
        // importe y litros) se COMPLETA en vez de insertar otra. Evita el gasto repetido.
        val desde = System.currentTimeMillis() - 10 * 60 * 1000L
        db.rawQuery("SELECT id, IFNULL(km,0), IFNULL(foto,'') FROM gasto WHERE jornada_id=? AND tipo=? " +
                "AND ABS(importe-?) < 0.005 AND ABS(litros-?) < 0.005 AND ts>=? ORDER BY id DESC LIMIT 1",
                arrayOf(jid.toString(), tipo, importe.toString(), litros.toString(), desde.toString())).use { c ->
            if (c.moveToNext()) {
                val ex = c.getLong(0)
                val kmPrev = c.getDouble(1)
                val fotoPrev = c.getString(2) ?: ""
                val cv2 = ContentValues()
                if (kmPrev == 0.0 && km > 0.0) cv2.put("km", km)
                if (fotoPrev.isEmpty() && foto.isNotEmpty()) cv2.put("foto", foto)
                if (nota.isNotEmpty()) cv2.put("nota", nota)
                if (cv2.size() > 0) db.update("gasto", cv2, "id=?", arrayOf(ex.toString()))
                return ex
            }
        }
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("ts", System.currentTimeMillis()); cv.put("tipo", tipo)
        cv.put("importe", importe); cv.put("litros", litros); cv.put("km", km); cv.put("foto", foto); cv.put("nota", nota)
        return db.insert("gasto", null, cv)
    }

    fun ponerFotoGasto(id: Long, foto: String) {
        val cv = ContentValues(); cv.put("foto", foto)
        db.update("gasto", cv, "id=?", arrayOf(id.toString()))
    }

    fun gastos(jid: Long): List<Gasto> {
        val out = ArrayList<Gasto>()
        db.rawQuery("SELECT ts, tipo, importe, litros, km, IFNULL(foto,''), IFNULL(nota,'') FROM gasto WHERE jornada_id=? ORDER BY ts",
                arrayOf(jid.toString())).use { c ->
            while (c.moveToNext()) out.add(Gasto(c.getLong(0), c.getString(1) ?: "", c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getString(5), c.getString(6)))
        }
        return out
    }

    fun totalGastos(jid: Long): Double = Math.round(gastos(jid).sumOf { it.importe } * 100.0) / 100.0

    // ---------------- consignaciones a GLOVO (recibos BBVA) ----------------

    fun registrarConsignacion(jid: Long, importe: Double, comision: Double, oficina: String, puesto: String,
                              clave: String, idIngreso: String, nota: String): Long {
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("ts", System.currentTimeMillis()); cv.put("importe", importe)
        cv.put("comision", comision); cv.put("oficina", oficina); cv.put("puesto", puesto); cv.put("clave", clave)
        cv.put("id_ingreso", idIngreso); cv.put("telefono", "643297119"); cv.put("emisora", "67282871")
        cv.put("foto", ""); cv.put("nota", nota)
        return db.insert("consignacion", null, cv)
    }

    fun ponerFotoConsignacion(id: Long, foto: String) {
        val cv = ContentValues(); cv.put("foto", foto)
        db.update("consignacion", cv, "id=?", arrayOf(id.toString()))
    }

    fun consignaciones(jid: Long): List<Consig> {
        val out = ArrayList<Consig>()
        db.rawQuery("SELECT id, ts, importe, IFNULL(comision,0), IFNULL(oficina,''), IFNULL(puesto,''), IFNULL(clave,''), IFNULL(id_ingreso,''), IFNULL(foto,''), IFNULL(nota,'') FROM consignacion WHERE jornada_id=? ORDER BY ts",
                arrayOf(jid.toString())).use { c ->
            while (c.moveToNext()) out.add(Consig(c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9)))
        }
        return out
    }

    fun totalConsignaciones(jid: Long): Double = Math.round(consignaciones(jid).sumOf { it.importe } * 100.0) / 100.0

    /** Efectivo cobrado al cliente en la jornada (avisos "Cobro en efectivo X EUR"), sin contar pantallas repetidas. */
    fun efectivoCobrado(jid: Long): Double {
        val (a, b) = rango(jid)
        val vistos = HashSet<String>()
        var total = 0.0
        val rx = Regex("Cobro en efectivo\\s*([\\d.,]+)")
        db.rawQuery("SELECT ts, IFNULL(texto,'') FROM evidencia WHERE ts>=? AND ts<=?",
                arrayOf(a.toString(), b.toString())).use { c ->
            while (c.moveToNext()) {
                val m = rx.find(c.getString(1)) ?: continue
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
                val clave = (c.getLong(0) / 300000L).toString() + "|" + v
                if (vistos.add(clave)) total += v
            }
        }
        return Math.round(total * 100.0) / 100.0
    }

    /** Fases del pedido en orden cronologico, permitiendo eventos repetidos (reposicionamiento, etc.). */
    fun fasesCronologicas(jid: Long, pedido: String): List<Triple<String, Long, String>> {
        val lista = ArrayList<Triple<String, Long, String>>()
        val auto = fasesEfectivas(jid, pedido).filter { it.third.startsWith("auto") }
        lista.addAll(auto)
        lista.addAll(fases(jid, pedido))
        return lista.sortedBy { it.second }
    }

    /**
     * Tiempos derivados del flujo (explicados por Jose):
     *  - ALISTAMIENTO = salida hacia el cliente - llegada a la moto (carga + casco + revision de ruta)
     *  - ENTREGA AL CLIENTE = entrega del pedido - llegada al cliente
     *  - comparativas ESTIMADO (Maps) vs REALIZADO en la ida al local y en la ida al cliente
     *  - espera hasta el siguiente pedido (desde el cierre de este)
     */
    fun flujoDerivados(jid: Long, pedido: String): List<String> {
        val f = fasesEfectivas(jid, pedido)
        val out = ArrayList<String>()
        fun horaDe(paso: String): Long? = f.firstOrNull { it.first == paso }?.second
        fun notaDe(paso: String): String = f.firstOrNull { it.first == paso }?.third ?: ""
        fun tramo(etq: String, a: String, b: String) {
            val ta = horaDe(a) ?: return
            val tb = horaDe(b) ?: return
            out.add(etq + ": " + duracion(tb - ta))
        }
        fun tramoKm(etq: String, a: String, b: String) {
            val ra = odomDeNota(notaDe(a)) ?: return
            val rb = odomDeNota(notaDe(b)) ?: return
            if (rb > ra) out.add(etq + ": " + kmTxt(rb - ra) + " km")
        }
        fun desorden(antes: String, despues: String) {
            val ta = horaDe(antes) ?: return
            val tb = horaDe(despues) ?: return
            if (tb < ta) out.add("AVISO DE ORDEN: " + despues + " quedo ANTES de " + antes + " (hitos invertidos)")
        }
        fun num(nota: String, clave: String): Double? {
            Regex(clave + "=([0-9]+(?:[.,][0-9]+)?)").find(nota)?.let {
                return it.groupValues[1].replace(',', '.').toDoubleOrNull()
            }
            Regex("([0-9]+) ?" + clave).find(nota)?.let {
                return it.groupValues[1].replace(',', '.').toDoubleOrNull()
            }
            return null
        }
        fun comparaEstimadoReal(etq: String, pasoEst: String, pasoReal: String) {
            val e = num(notaDe(pasoEst), "min")
            val r = num(notaDe(pasoReal), "min")
            if (e != null && r != null) {
                out.add(etq + ": estimado " + e.toInt() + " min vs real " + r.toInt() +
                        " min (desvio " + (r - e).toInt() + " min)")
            }
        }
        tramo("Recibo -> parking", "RECIBO_PEDIDO", "LLEGADA_PARKING")
        tramo("Parking -> local (a pie)", "LLEGADA_PARKING", "LLEGADA_LOCAL")
        tramo("Espera en el local", "LLEGADA_LOCAL", "RECOGIDA")
        tramo("Recogida -> moto", "RECOGIDA", "LLEGADA_MOTO")
        tramo("ALISTAMIENTO (carga + casco + ruta)", "LLEGADA_MOTO", "SALIDA_CLIENTE")
        tramo("Ruta al cliente", "SALIDA_CLIENTE", "LLEGADA_CLIENTE")
        tramo("ENTREGA AL CLIENTE (desde llegada)", "LLEGADA_CLIENTE", "ENTREGA")
        tramo("Entrega -> vuelta a la moto", "ENTREGA", "LLEGADA_MOTO_FINAL")
        // v1.9.5: km REAL del tramo a partir de las lecturas de odometro (nunca un 0 enganoso)
        tramoKm("Km del tramo al local (odometro)", "RUTA_LOCAL", "LLEGADA_PARKING")
        tramoKm("Km del tramo al cliente (odometro)", "SALIDA_CLIENTE", "LLEGADA_CLIENTE")
        // v1.9.5: aviso si un hito quedo ANTES que su predecesor (orden invertido)
        desorden("LLEGADA_CLIENTE", "ENTREGA")
        desorden("LLEGADA_PARKING", "LLEGADA_LOCAL")
        desorden("RECOGIDA", "LLEGADA_MOTO")
        comparaEstimadoReal("Ida al local", "RUTA_LOCAL", "LLEGADA_PARKING")
        comparaEstimadoReal("Ida al cliente", "SALIDA_CLIENTE", "LLEGADA_CLIENTE")
        // espera declarada por el local ("estara listo en N min")
        val decl = num(notaDe("RETRASO_LOCAL"), "min")
        if (decl != null) out.add("Espera declarada por el local: " + decl.toInt() + " min")
        // metros a pie moto -> local
        val mpie = num(notaDe("LLEGADA_PARKING"), "mpie")
        if (mpie != null) out.add("Tramos a pie moto -> local: " + mpie.toInt() + " m")
        // lote (pedidos simultaneos)
        val lote = notaDe("LOTE")
        if (lote.isNotEmpty()) out.add("Pedidos en el lote: " + lote)
        // incidencia en la entrega
        val inc = notaDe("INCIDENCIA_ENTREGA")
        if (inc.isNotEmpty()) out.add("Incidencia en la entrega: " + inc)
        // tiempo de vuelta a casa (no pagado): km y tiempo declarados + hora real
        val casa = horaDe("LLEGADA_CASA")
        if (casa != null) {
            val notaCasa = notaDe("LLEGADA_CASA")
            // v1.9.3: el evento guarda km= con el odometro y vuelta= con la distancia de la vuelta;
            // los registros antiguos guardaban la distancia (pequena) directamente en km=.
            val kmCasa = num(notaCasa, "vuelta")
                ?: num(notaCasa, "km")?.takeIf { it in 0.0..1000.0 }
            val tCasa = Regex("tiempo=([0-9]{1,2}):([0-9]{2})").find(notaCasa)
            val detalle = ArrayList<String>()
            if (kmCasa != null) detalle.add(kmTxt(kmCasa) + " km")
            if (tCasa != null) {
                val min = tCasa.groupValues[1].toLong() * 60 + tCasa.groupValues[2].toLong()
                detalle.add(duracion(min * 60000L))
            }
            if (detalle.isNotEmpty()) out.add("Vuelta a casa (registrado): " + detalle.joinToString(", "))
            val ultima = listOfNotNull(horaDe("LLEGADA_MOTO_FINAL"), horaDe("ENTREGA")).maxOrNull()
            if (ultima != null && casa > ultima) {
                out.add("Ultimo pedido -> llegada a casa (hora real): " + duracion(casa - ultima))
            }
            // v1.3: vuelta a casa medida por el repartidor (salida con odometro) vs Google Maps
            val salidaV = horaDe("VUELTA_SALIDA")
            if (salidaV != null && casa > salidaV) {
                val realMin = (casa - salidaV) / 60000L
                val g = num(notaDe("VUELTA_SALIDA"), "google")
                out.add("Vuelta casa -> casa: real " + realMin + " min" +
                        (if (g != null) " vs Google " + g.toInt() + " min (desvio " + (realMin - g.toInt()) + " min)" else ""))
            }
        }
        val todos = pedidos(jid)
        val idx = todos.indexOfFirst { it.id == pedido }
        val cierre = horaDe("LLEGADA_MOTO_FINAL")
        if (idx >= 0 && idx < todos.size - 1 && cierre != null) {
            val sig = todos[idx + 1]
            val ref = sig.primera
            if (ref > cierre) {
                out.add("Espera hasta el siguiente pedido (#" + (if (sig.num.isNotEmpty()) sig.num else sig.id.takeLast(4)) +
                        "): " + duracion(ref - cierre))
            }
        }
        return out
    }

    /** Tabla comparativa diaria: horario programado vs jornada real, una linea por jornada. */
    fun tablaComparativa(jids: List<Long>): List<String> {
        val out = ArrayList<String>()
        out.add(pad("JORNADA", 12) + pad("PROGRAMADO", 13) + pad("INICIO REAL", 12) + pad("FIN REAL", 11) +
                pad("ULT.ENTREGA", 12) + pad("COMPLEMENTARIO", 15) + pad("KM", 7) + pad("PEDIDOS", 8) + "CARTERA")
        for (jid in jids) {
            val j = jornada(jid) ?: continue
            val (pi, pf) = horario(jid)
            val ue = ultimaEntrega(jid) ?: ultimaSenal(jid)
            val tc = tiempoComplementario(jid)
            val mov = movimientoDelDia(jid)
            val peds = pedidos(jid)
            out.add(pad("#" + jid + " " + j.first.substring(5), 12) +
                    pad(if (pi != null && pf != null) corto(pi) + "-" + corto(pf) else "--", 13) +
                    pad(corto(j.second), 12) +
                    pad(if (j.third != null) corto(j.third!!) else "(abierta)", 11) +
                    pad(if (ue != null) corto(ue) else "--", 12) +
                    pad(if (tc != null) duracion(tc) else "--", 15) +
                    pad(kmRecorridos(jid)?.let { String.format(Locale.US, "%.1f", it) } ?: "--", 7) +
                    pad(peds.size.toString(), 8) +
                    (mov?.let { fmt(it) } ?: "--"))
        }
        return out
    }

    // ---------------- cartera ----------------

    fun snapshot(jid: Long, momento: String, saldo: Double, metodo: String) {
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("momento", momento); cv.put("ts", System.currentTimeMillis())
        cv.put("saldo", saldo); cv.put("metodo", metodo); cv.put("estado", "capturado")
        db.insert("snapshot_cartera", null, cv)
    }

    fun snapshots(jid: Long): List<Snap> {
        val out = ArrayList<Snap>()
        db.rawQuery("SELECT momento, ts, saldo, metodo FROM snapshot_cartera WHERE jornada_id=? ORDER BY id", arrayOf(jid.toString())).use { c ->
            while (c.moveToNext()) out.add(Snap(c.getString(0), c.getLong(1), c.getDouble(2), c.getString(3)))
        }
        return out
    }

    fun saldoDe(jid: Long, momento: String): Double? {
        db.rawQuery("SELECT saldo FROM snapshot_cartera WHERE jornada_id=? AND momento=? ORDER BY id DESC LIMIT 1", arrayOf(jid.toString(), momento)).use { c ->
            if (c.moveToFirst()) return c.getDouble(0)
        }
        return null
    }

    fun movimientoDelDia(jid: Long): Double? {
        val i = saldoDe(jid, "INICIO") ?: return null
        val f = saldoDe(jid, "FIN") ?: return null
        return Math.round((f - i) * 100.0) / 100.0
    }

    /**
     * v1.9.5: EFECTIVO declarado por los pedidos de la jornada (importe de los pedidos cuyo pago
     * es en efectivo). Sirve para avisar del descuadre con la cartera (07/10: 24,07 + 15,57 = 39,64).
     */
    fun efectivoDelDia(jid: Long): Double {
        var t = 0.0
        for (p in pedidos(jid)) {
            val d = datosPedido(p.id)
            val pago = (d["pago"] ?: "").lowercase(Locale.US)
            if (!pago.contains("efectivo")) continue
            val imp = d["importe"]?.replace(',', '.')?.toDoubleOrNull() ?: continue
            t += imp
        }
        return Math.round(t * 100.0) / 100.0
    }

    /** v1.9.5: (efectivo del dia, movimiento de cartera, descuadre = movimiento - efectivo). */
    fun descuadreCartera(jid: Long): Triple<Double, Double, Double>? {
        val mov = movimientoDelDia(jid) ?: return null
        val efe = efectivoDelDia(jid)
        return Triple(efe, mov, Math.round((mov - efe) * 100.0) / 100.0)
    }

    /** v1.9.5: corrige la hora (ts real) de un hito ya marcado en el pedido (lo guardado es la pulsacion). */
    fun corregirHoraFase(jid: Long, pedido: String, paso: String, ts: Long): Boolean {
        var id = -1L
        db.rawQuery("SELECT id FROM flujo WHERE jornada_id=? AND pedido=? AND paso=? ORDER BY ts DESC LIMIT 1",
                arrayOf(jid.toString(), pedido, paso)).use { c ->
            if (c.moveToFirst()) id = c.getLong(0)
        }
        if (id < 0) return false
        val cv = ContentValues(); cv.put("ts", ts)
        return db.update("flujo", cv, "id=?", arrayOf(id.toString())) > 0
    }

    // ---------------- km moto ----------------

    fun guardarKm(jid: Long, momento: String, valor: Double, foto: String): Long {
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("momento", momento); cv.put("ts", System.currentTimeMillis())
        cv.put("valor", valor); cv.put("foto", foto)
        return db.insert("km", null, cv)
    }

    /** id del ultimo registro de km de ese momento (para reemplazarlo o corregir su etiqueta). */
    fun idUltimoKm(jid: Long, momento: String): Long? {
        db.rawQuery("SELECT id FROM km WHERE jornada_id=? AND momento=? ORDER BY id DESC LIMIT 1", arrayOf(jid.toString(), momento)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }

    fun actualizarValorKm(id: Long, valor: Double) {
        val cv = ContentValues(); cv.put("valor", valor)
        db.update("km", cv, "id=?", arrayOf(id.toString()))
    }

    fun cambiarMomentoKm(id: Long, momento: String) {
        val cv = ContentValues(); cv.put("momento", momento)
        db.update("km", cv, "id=?", arrayOf(id.toString()))
    }

    /** Ultimo registro de km con su etiqueta (para el boton de correccion). */
    fun ultimoKm(jid: Long): Triple<Long, String, Double>? {
        db.rawQuery("SELECT id, momento, valor FROM km WHERE jornada_id=? ORDER BY id DESC LIMIT 1", arrayOf(jid.toString())).use { c ->
            if (c.moveToFirst()) return Triple(c.getLong(0), c.getString(1), c.getDouble(2))
        }
        return null
    }

    fun ponerFotoKm(id: Long, foto: String) {
        val cv = ContentValues(); cv.put("foto", foto)
        db.update("km", cv, "id=?", arrayOf(id.toString()))
    }

    fun kms(jid: Long): List<Km> {
        val out = ArrayList<Km>()
        db.rawQuery("SELECT momento, ts, valor, IFNULL(foto,'') FROM km WHERE jornada_id=? ORDER BY id", arrayOf(jid.toString())).use { c ->
            while (c.moveToNext()) out.add(Km(c.getString(0), c.getLong(1), c.getDouble(2), c.getString(3)))
        }
        return out
    }

    fun kmDe(jid: Long, momento: String): Double? {
        // INICIO = el PRIMER registro (si hubo un duplicado posterior no debe falsear el calculo); FIN = el ultimo
        val orden = if (momento == "INICIO") "ORDER BY id ASC" else "ORDER BY id DESC"
        db.rawQuery("SELECT valor FROM km WHERE jornada_id=? AND momento=? $orden LIMIT 1", arrayOf(jid.toString(), momento)).use { c ->
            if (c.moveToFirst()) return c.getDouble(0)
        }
        return null
    }

    fun kmRecorridos(jid: Long): Double? {
        val i = kmDe(jid, "INICIO") ?: return null
        val f = kmDe(jid, "FIN") ?: return null
        return Math.round((f - i) * 10.0) / 10.0
    }

    // ---------------- meteo (lluvia y clima adverso) ----------------

    fun registrarMeteo(jid: Long, tipo: String, nota: String) {
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("ts", System.currentTimeMillis()); cv.put("tipo", tipo); cv.put("nota", nota)
        db.insert("meteo", null, cv)
    }

    fun meteo(jid: Long): List<Met> {
        val out = ArrayList<Met>()
        db.rawQuery("SELECT ts, tipo, IFNULL(nota,'') FROM meteo WHERE jornada_id=? ORDER BY ts", arrayOf(jid.toString())).use { c ->
            while (c.moveToNext()) out.add(Met(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    // ---------------- incidencias ----------------

    fun nuevaIncidencia(jid: Long, idPedido: String, importe: Double, pago: String, cobrado: String): Long {
        val cv = ContentValues()
        cv.put("jornada_id", jid); cv.put("id_pedido", idPedido); cv.put("ts_deteccion", System.currentTimeMillis())
        cv.put("importe", importe); cv.put("pago", pago); cv.put("cobrado", cobrado); cv.put("estado", "ABIERTA")
        return db.insert("incidencia", null, cv)
    }

    fun cerrarIncidencia(id: Long, canal: String) {
        val cv = ContentValues()
        cv.put("estado", "CERRADA_POR_HUMANO"); cv.put("cierre_por", canal); cv.put("ts_cierre", System.currentTimeMillis())
        db.update("incidencia", cv, "id=?", arrayOf(id.toString()))
    }

    fun incidencias(jid: Long? = null): List<Inc> {
        val out = ArrayList<Inc>()
        val sql = if (jid == null) "SELECT id, ts_deteccion, importe, pago, cobrado, estado, IFNULL(cierre_por,'') FROM incidencia ORDER BY id"
        else "SELECT id, ts_deteccion, importe, pago, cobrado, estado, IFNULL(cierre_por,'') FROM incidencia WHERE jornada_id=$jid ORDER BY id"
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) out.add(Inc(c.getLong(0), c.getLong(1), c.getDouble(2), c.getString(3) ?: "", c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6)))
        }
        return out
    }

    // ---------------- evidencias ----------------

    fun registrarEvidencia(ref: String, tipo: String, texto: String, archivo: String) {
        val cv = ContentValues()
        cv.put("ref", ref); cv.put("ts", System.currentTimeMillis()); cv.put("tipo", tipo)
        cv.put("texto", texto); cv.put("archivo", archivo)
        db.insert("evidencia", null, cv)
    }

    fun totalEvidencias(): Int {
        db.rawQuery("SELECT COUNT(*) FROM evidencia", null).use { c -> if (c.moveToFirst()) return c.getInt(0) }
        return 0
    }

    fun totalEvidenciasJornada(jid: Long): Int {
        val (ini, fin) = rango(jid)
        db.rawQuery("SELECT COUNT(*) FROM evidencia WHERE ts>=? AND ts<=?", arrayOf(ini.toString(), fin.toString())).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun evidenciasJornada(jid: Long): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        val (ini, fin) = rango(jid)
        db.rawQuery("SELECT ts, tipo, IFNULL(texto,'') FROM evidencia WHERE ts>=? AND ts<=? ORDER BY ts", arrayOf(ini.toString(), fin.toString())).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        return out
    }

    fun evidencias(limite: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        db.rawQuery("SELECT ts, tipo FROM evidencia ORDER BY id DESC LIMIT ?", arrayOf(limite.toString())).use { c ->
            while (c.moveToNext()) out.add(hora.format(Date(c.getLong(0))) to (c.getString(1) ?: ""))
        }
        return out
    }

    /** Visor paginado: 'mostrar' mas antiguas a partir de 'salto'. */
    fun evidenciasDetalle(mostrar: Int, salto: Int): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT ts, tipo, ref, IFNULL(texto,'') FROM evidencia ORDER BY id DESC LIMIT ? OFFSET ?",
            arrayOf(mostrar.toString(), salto.toString())).use { c ->
            while (c.moveToNext()) {
                val t = hora.format(Date(c.getLong(0)))
                val tipo = c.getString(1) ?: ""
                val ref = c.getString(2) ?: ""
                val txt = (c.getString(3) ?: "").replace("\n", " ")
                out.add(t + "  -  " + tipo + "\n     " + ref + "\n     " + txt.take(140))
            }
        }
        return out
    }

    // ---------------- pedidos ----------------

    fun pedidos(jid: Long): List<Ped> {
        val mapa = LinkedHashMap<String, Ped>()
        for ((ts, tipo, texto) in evidenciasJornada(jid)) {
            val m = PEDIDO.matcher(texto)
            val ids = HashSet<String>()
            while (m.find()) ids.add(m.group(1))
            for (pid in ids) {
                val p = mapa.getOrPut(pid) { Ped(pid, ts, ts, "", "", null, null, null) }
                if (ts < p.primera) p.primera = ts
                if (ts > p.ultima) p.ultima = ts
                val num = Pattern.compile("#(\\d{3})\\D{0,12}?" + pid).matcher(texto)
                if (num.find() && p.num.isEmpty()) p.num = num.group(1)
                else {
                    val n2 = Pattern.compile(pid + "\\D{0,20}?#(\\d{3})").matcher(texto)
                    if (n2.find() && p.num.isEmpty()) p.num = n2.group(1)
                }
                val loc = Pattern.compile("(?:aceptada|aceptado|pedidos?)\\s+(.{3,40}?)\\s+\\d+\\s*pedido").matcher(texto)
                if (loc.find() && p.local.isEmpty() && !loc.group(1).startsWith("Estado")) p.local = loc.group(1).trim()
                if (texto.contains("Confirmas que has recogido") && texto.contains(pid) && p.recogida == null) p.recogida = ts
                if (texto.contains("Confirmas la entrega del pedido " + pid) && p.entrega == null) p.entrega = ts
            }
        }
        // reasignaciones: el aviso no lleva id -> se atribuye al ultimo pedido aceptado sin entrega
        for ((ts, tipo, texto) in evidenciasJornada(jid)) {
            if (texto.contains("marcado el pedido como reenviado") || texto.contains("se ha reasignado")) {
                mapa.values.filter { it.primera < ts && it.entrega == null }
                    .maxByOrNull { it.primera }?.let { if (it.reasignado == null) it.reasignado = ts }
            }
        }
        return mapa.values.sortedBy { it.primera }
    }

    fun estadoPedido(p: Ped): String = when {
        p.entrega != null -> "ENTREGADO " + hora.format(Date(p.entrega!!))
        p.reasignado != null -> "REASIGNADO " + hora.format(Date(p.reasignado!!))
        else -> "SIN CIERRE"
    }

    // ---------------- bloqueo duro ----------------

    /** Bloqueo duro: sin cartera INICIO/FIN y sin km INICIO/FIN no se cierra la jornada. */
    fun puedeTerminar(jid: Long): Pair<Boolean, String> {
        val faltan = ArrayList<String>()
        if (saldoDe(jid, "INICIO") == null) faltan.add("cartera de INICIO")
        if (saldoDe(jid, "FIN") == null) faltan.add("cartera de FIN (movimiento del dia)")
        if (kmDe(jid, "INICIO") == null) faltan.add("km de moto al INICIO (salida de casa)")
        if (kmDe(jid, "FIN") == null) faltan.add("km de moto al FIN (llegada a casa)")
        return if (faltan.isEmpty()) true to "OK" else false to ("Faltan pruebas: " + faltan.joinToString(", ") + ".")
    }

    fun terminar(jid: Long): Pair<Boolean, String> {
        val (ok, msg) = puedeTerminar(jid)
        if (!ok) return false to msg
        cerrarJornada(jid, "CERRADA")
        return true to "Jornada cerrada."
    }

    // ---------------- resumen en pantalla ----------------

    fun resumen(jid: Long): String {
        val sb = StringBuilder()
        val j = jornada(jid)
        val finJ = j?.third
        val abierta = finJ == null
        sb.append(if (abierta) "JORNADA: ON  (abierta)" else "JORNADA: OFF (cerrada)").append("\n")
        if (j != null) {
            sb.append("Inicio: ").append(diaHora.format(Date(j.second)))
            if (finJ != null) sb.append("   Fin: ").append(diaHora.format(Date(finJ)))
            sb.append("\n")
        }
        sb.append("Cartera inicio: ").append(saldoDe(jid, "INICIO")?.let { fmt(it) } ?: "--")
        sb.append("   |   Cartera fin: ").append(saldoDe(jid, "FIN")?.let { fmt(it) } ?: "--").append("\n")
        sb.append("Km inicio: ").append(kmDe(jid, "INICIO")?.let { kmfmt(it) } ?: "--")
        sb.append("   |   Km fin: ").append(kmDe(jid, "FIN")?.let { kmfmt(it) } ?: "--")
        sb.append("   |   Km del dia: ").append(kmRecorridos(jid)?.let { kmfmt(it) } ?: "--").append("\n")
        val (pi, pf) = horario(jid)
        if (pi != null && pf != null) {
            sb.append("Horario programado: ").append(horaFmt(pi)).append(" - ").append(horaFmt(pf)).append("\n")
        } else {
            sb.append("Horario programado: SIN DEFINIR (pulsa Horario en PREPARAR JORNADA)\n")
        }
        val tc = tiempoComplementario(jid)
        sb.append("Tiempo complementario: ").append(if (tc != null) duracion(tc) + " fuera del horario programado" else "--").append("\n")
        sb.append("Tiempo sin pedidos (aprox.): ").append(tiempoSinPedidos(jid)?.let { duracion(it) } ?: "--").append("\n")
        sb.append("Casa -> zona de trabajo: ").append(casaAZona(jid)?.let { duracion(it) } ?: "--").append("\n")
        val gsR = gastos(jid)
        if (gsR.isNotEmpty()) {
            sb.append("Gastos registrados: ").append(gsR.size).append("  (").append(fmt(totalGastos(jid))).append(")\n")
        }
        val acc = try {
            Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (ex: Exception) { "" }
        sb.append("Accesibilidad: ").append(if (acc.contains(appCtx.packageName)) "ACTIVADA" else "DESACTIVADA").append("\n")
        sb.append("Evidencias de esta jornada: ").append(totalEvidenciasJornada(jid)).append("\n")
        val peds = pedidos(jid)
        sb.append("Pedidos: ").append(peds.size).append("  (entregados ").append(peds.count { it.entrega != null })
            .append(", reasignados ").append(peds.count { it.entrega == null && it.reasignado != null }).append(")\n")
        val met = meteo(jid)
        if (met.isNotEmpty()) {
            sb.append("Meteo: ").append(met.joinToString(", ") { it.tipo.lowercase() }).append("\n")
        }
        val (ok, msg) = puedeTerminar(jid)
        sb.append(if (ok) "Cierre: listo (puedes pulsar TERMINAR JORNADA)." else "Cierre: " + msg)
        return sb.toString()
    }

    // ---------------- expediente ----------------

    fun lineasExpediente(jid: Long): List<String> {
        val out = ArrayList<String>()
        val j = jornada(jid)
        out.add("EXPEDIENTE DE JORNADA - Registro de Jornada Glovo")
        out.add("Generado: " + SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date()))
        out.add("Jornada #" + jid + (if (j != null) "  inicio " + diaHora.format(Date(j.second)) +
                (if (j.third != null) "  fin " + diaHora.format(Date(j.third!!)) else "  (ABIERTA)") else ""))
        out.add("")
        out.add("1) KM DE LA MOTO (desplazamiento propio)")
        val kms = kms(jid)
        if (kms.isEmpty()) out.add("   Sin registro de km.")
        for (k in kms) out.add("   " + k.momento + "  " + hora.format(Date(k.ts)) + "  " + kmfmt(k.valor) +
                (if (k.foto.isNotEmpty()) "   foto: " + k.foto.substringAfterLast('/') else ""))
        if (kms.count { it.momento == "INICIO" } > 1) out.add("   (aviso: hay varios km de INICIO; para el calculo se usa el PRIMERO)")
        if (kms.count { it.momento == "FIN" } > 1) out.add("   (aviso: hay varios km de FIN; para el calculo se usa el ULTIMO)")
        out.add("   KM DEL DIA: " + (kmRecorridos(jid)?.let { kmfmt(it) } ?: "--"))
        out.add("")
        out.add("2) CARTERA / EFECTIVO")
        val snaps = snapshots(jid)
        if (snaps.isEmpty()) out.add("   Sin snapshots de cartera.")
        for (s in snaps) out.add("   " + s.momento + "  " + hora.format(Date(s.ts)) + "  " + fmt(s.saldo) + "  (" + s.metodo + ")")
        out.add("   MOVIMIENTO DEL DIA: " + (movimientoDelDia(jid)?.let { fmt(it) } ?: "--"))
        out.add("")
        out.add("3) CONDICIONES METEOROLOGICAS ADVERSAS REGISTRADAS")
        val met = meteo(jid)
        if (met.isEmpty()) out.add("   Sin incidencias meteorologicas registradas.")
        for (m in met) out.add("   " + hora.format(Date(m.ts)) + "  " + m.tipo + (if (m.nota.isNotEmpty()) "  (" + m.nota + ")" else ""))
        out.add("")
        out.add("4) PEDIDOS (señales detectadas en pantalla)")
        val peds = pedidos(jid)
        out.add(pad("HORA 1a", 9) + pad("PEDIDO", 13) + pad("N", 5) + pad("LOCAL", 16) + pad("ULTIMA", 9) + pad("TOTAL", 8) + "ESTADO")
        for (p in peds) {
            out.add(pad(hora.format(Date(p.primera)), 9) + pad(p.id, 13) + pad(if (p.num.isEmpty()) "-" else p.num, 5) +
                    pad(p.local.take(15), 16) + pad(hora.format(Date(p.ultima)), 9) + pad(p.totalMin().toString() + "m", 8) + estadoPedido(p))
            // FASE 1: datos estructurados del pedido (cliente, direcciones, pago, productos)
            val d = datosPedido(p.id)
            if (d.isNotEmpty()) {
                val trozos = ArrayList<String>()
                d["cliente"]?.let { if (it.isNotEmpty()) trozos.add("Cliente: " + it) }
                d["dir_recogida"]?.let { if (it.isNotEmpty()) trozos.add("Recogida en: " + it) }
                d["dir_entrega"]?.let { if (it.isNotEmpty()) trozos.add("Entrega en: " + it) }
                d["pago"]?.let { if (it.isNotEmpty()) trozos.add("Pago: " + it + (d["importe"]?.let { v -> " " + v + " EUR" } ?: "")) }
                d["productos"]?.let { if (it.isNotEmpty()) trozos.add(it + " productos") }
                d["retraso_min"]?.let { if (it.isNotEmpty()) trozos.add("retraso " + it + " min") }
                if (trozos.isNotEmpty()) out.add(pad("", 9) + trozos.joinToString(" | "))
            }
        }
        out.add("   (la columna LOCAL se recorta a 15 caracteres para que la tabla quepa; el nombre completo aparece en las evidencias)")
        out.add("")
        out.add("5) INCIDENCIAS DE CLIENTE AUSENTE")
        val inc = incidencias(jid)
        if (inc.isEmpty()) out.add("   Sin incidencias registradas.")
        for (x in inc) out.add("   #" + x.id + "  " + hora.format(Date(x.momento)) + "  importe " + fmt(x.importe) +
                "  pago " + x.pago + "  cobrado: " + x.cobrado + "  estado " + x.estado + "  cierre: " + x.cierre)
        out.add("")
        out.add("6) REGISTRO COMPLETO DE EVIDENCIAS (pantallas leidas, en orden)")
        val evs = evidenciasJornada(jid)
        for ((ts, tipo, texto) in evs) {
            out.add(hora.format(Date(ts)) + "  " + pad(tipo, 17) + texto.replace("\n", " ").replace(Regex("\\s+"), " ").take(300))
        }
        out.add("")
        out.add("7) TABLA RESUMEN DE LA JORNADA")
        out.add("   Pedidos detectados: " + peds.size)
        out.add("   Entregados: " + peds.count { it.entrega != null } + "   Reasignados: " + peds.count { it.entrega == null && it.reasignado != null })
        val tot = peds.sumOf { it.totalMin() }
        out.add("   Tiempo acumulado en pedidos: " + (tot / 60) + " h " + String.format(Locale.US, "%02d", tot % 60) + " min")
        if (peds.isNotEmpty()) {
            out.add("   Primer pedido: " + hora.format(Date(peds.first().primera)) + "   Ultimo pedido: " + hora.format(Date(peds.last().primera)))
        }
        out.add("   Km del dia: " + (kmRecorridos(jid)?.let { kmfmt(it) } ?: "--"))
        out.add("   Movimiento de cartera: " + (movimientoDelDia(jid)?.let { fmt(it) } ?: "--"))
        out.add("   Evidencias registradas: " + evs.size)
        out.add("   Incidencias: " + inc.size + "   Avisos de meteo adversa: " + met.size)
        out.add("")
        out.add("8) TIEMPO COMPLEMENTARIO TRABAJADO (fuera del horario programado)")
        val (pi, pf) = horario(jid)
        val ue = ultimaEntrega(jid)
        if (pi != null && pf != null) {
            out.add("   Horario programado: " + horaFmt(pi) + " - " + horaFmt(pf) + "  (" + duracion(pf - pi) + ")")
        } else {
            out.add("   Horario programado: SIN DEFINIR")
        }
        out.add("   Ultimo pedido entregado a las: " + (ue?.let { horaFmt(it) } ?: "--"))
        val tc = tiempoComplementario(jid)
        out.add("   TIEMPO COMPLEMENTARIO: " + if (tc == null) "-- (faltan datos)"
                else if (tc >= 0) "+" + duracion(tc) + " por fuera de la jornada programada"
                else duracion(tc) + " (se termino antes del fin programado)")
        out.add("")
        out.add("9) TABLA COMPARATIVA DIARIA (horario programado vs jornada real)")
        for (l in tablaComparativa(jornadasRecientes(12))) out.add("   " + l)
        out.add("")
        out.add("10) FLUJO POR PEDIDO (fases marcadas por el repartidor, con hora real)")
        val pedsF = pedidos(jid)
        if (pedsF.isEmpty()) out.add("   Sin pedidos detectados.")
        for (p in pedsF) {
            val f = fasesEfectivas(jid, p.id)
            out.add("   Pedido " + p.id + (if (p.num.isNotEmpty()) " #" + p.num else "") +
                    "   estado: " + estadoPedido(p))
            if (f.isEmpty()) {
                out.add("      (sin fases marcadas)")
            } else {
                for ((paso, ts, nota) in f) {
                    val sufijo = when {
                        nota == "auto" -> "  [detectado automaticamente]"
                        nota.startsWith("auto Maps") -> "  [" + nota + "]"
                        nota.isNotEmpty() -> "  (" + nota + ")"
                        else -> ""
                    }
                    out.add("      " + horaFmt(ts) + "  " + paso + sufijo)
                }
                for (l in flujoDerivados(jid, p.id)) {
                    out.add("      -> " + l)
                }
            }
        }
        out.add("")
        out.add("11) EVENTOS DE LA JORNADA Y DESPLAZAMIENTOS NO PRODUCTIVOS")
        val evsJ = eventosJornada(jid)
        if (evsJ.isEmpty()) out.add("   Sin eventos de jornada registrados (zona de trabajo, reposicionamiento, sin pedidos...).")
        for ((paso, ts, nota) in evsJ) {
            out.add("   " + horaFmt(ts) + "  " + paso + (if (nota.isNotEmpty()) "  (" + nota + ")" else ""))
        }
        val reps = reposicionamientos(jid)
        if (reps.isNotEmpty()) {
            out.add("   Reposicionamientos a puntos rojos: " + reps.size)
            for ((h, km, mn) in reps) out.add("      " + h + "   " + km + " km   " + mn.toInt() + " min")
            out.add("   TOTAL reposicionamiento: " + (Math.round(reps.sumOf { it.second } * 10.0) / 10.0) + " km, " +
                    reps.sumOf { it.third }.toInt() + " min")
        }
        out.add("   TIEMPO SIN PEDIDOS (aprox.): " + (tiempoSinPedidos(jid)?.let { duracion(it) } ?: "--"))
        out.add("   Tramo casa -> zona de trabajo (hasta ponerse EN LINEA): " + (casaAZona(jid)?.let { duracion(it) } ?: "--"))
        val iz = eventosJornada(jid).firstOrNull { it.first == "INICIO_ZONA" }
        val kmZona = iz?.third?.let { Regex("km=([0-9]+(?:[.,][0-9]+)?)").find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
        val kmFinT = kmDe(jid, "FIN")
        if (kmZona != null && kmFinT != null) {
            out.add("   KM desde la zona de trabajo (sin el tramo casa -> zona): " + kmfmt(kmFinT - kmZona))
        }
        out.add("")
        out.add("12) GASTOS DEL REPARTIDOR (gasolina y otros)")
        val gs = gastos(jid)
        if (gs.isEmpty()) out.add("   Sin gastos registrados.")
        for (g in gs) {
            out.add("   " + horaFmt(g.ts) + "  " + g.tipo + "  " + fmt(g.importe) +
                    (if (g.litros > 0) "   " + g.litros + " L" else "") +
                    (if (g.km > 0) "   cuentakm " + kmfmt(g.km) else "") +
                    (if (g.foto.isNotEmpty()) "   foto: " + g.foto.substringAfterLast('/') else "") +
                    (if (g.nota.isNotEmpty()) "   (" + g.nota + ")" else ""))
        }
        out.add("   TOTAL GASTOS: " + fmt(totalGastos(jid)))
        out.add("")
        out.add("Nota: los datos proceden de la lectura de pantalla del propio movil del repartidor (con hora real)")
        out.add("y de los importes confirmados por el. El cierre de incidencias es siempre por accion humana.")
        return out
    }

    /** Resumen CORTO (para el dialogo de cierre: que los botones siempre queden a la vista). */
    fun resumenCorto(jid: Long): String {
        val sb = StringBuilder()
        sb.append("km ").append(kmRecorridos(jid)?.let { kmfmt(it) } ?: "--")
        sb.append(" | cartera ").append(saldoDe(jid, "INICIO")?.let { fmt(it) } ?: "--")
        sb.append(" -> ").append(saldoDe(jid, "FIN")?.let { fmt(it) } ?: "--")
        val peds = pedidos(jid)
        sb.append(" | pedidos ").append(peds.size)
        sb.append(" | sin pedidos ").append(tiempoSinPedidos(jid)?.let { duracion(it) } ?: "--")
        val dc = descuadreCartera(jid)
        if (dc != null && dc.first > 0.0) {
            sb.append(" | efectivo ").append(fmt(dc.first))
            sb.append(" | descuadre ").append(fmt(dc.third))
        }
        return sb.toString()
    }

    // ---------------- FACTURA DE COBRO (v1.5) ----------------

    fun getAjuste(clave: String): String? {
        db.rawQuery("SELECT valor FROM ajuste WHERE clave=?", arrayOf(clave)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun setAjuste(clave: String, valor: String) {
        val cv = ContentValues(); cv.put("clave", clave); cv.put("valor", valor)
        db.insertWithOnConflict("ajuste", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Tarifa EUR/km de la factura. Por defecto 1,27 EUR/km (deducida del pedido 101782492431). */
    fun tarifaKm(): Double = getAjuste("tarifa_km")?.replace(',', '.')?.toDoubleOrNull() ?: 1.27

    fun setTarifaKm(v: Double) = setAjuste("tarifa_km", String.format(Locale.US, "%.2f", v))

    private fun numNota(nota: String, clave: String): Double? =
        Regex(clave + "=([0-9]+(?:[.,][0-9]+)?)").find(nota)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    private fun red1(v: Double): Double = Math.round(v * 10.0) / 10.0

    /** Odometro de referencia al empezar a repartir: llegada a la zona o, si no, km de INICIO. */
    private fun kmRefZona(jid: Long): Double? {
        eventosJornada(jid).firstOrNull { it.first == "INICIO_ZONA" }?.let {
            numNota(it.third, "km")?.let { k -> if (k > 0) return k }
        }
        return kmDe(jid, "INICIO")
    }

    /** Concepto 1: km casa -> zona de trabajo. */
    fun kmCasaZona(jid: Long): Double? {
        val iz = eventosJornada(jid).firstOrNull { it.first == "INICIO_ZONA" } ?: return null
        val kz = numNota(iz.third, "km") ?: return null
        val ki = kmDe(jid, "INICIO") ?: return null
        val d = kz - ki
        return if (d > 0) red1(d) else null
    }

    /** Concepto 2: km zona de trabajo -> casa. */
    fun kmZonaCasa(jid: Long): Double? {
        eventosJornada(jid).lastOrNull { it.first == "LLEGADA_CASA" }?.let {
            // v1.9.3: km= pasa a ser el odometro; la distancia de la vuelta va en vuelta=.
            // Los registros antiguos guardaban la distancia (pequena) directamente en km=.
            val d = numNota(it.third, "vuelta")
                ?: numNota(it.third, "km")?.takeIf { k -> k in 0.1..1000.0 }
            if (d != null && d > 0) return red1(d)
        }
        val sal = eventosJornada(jid).lastOrNull { it.first == "VUELTA_SALIDA" }?.third?.let { numNota(it, "km") }
        val fin = kmDe(jid, "FIN")
        if (sal != null && fin != null && fin > sal) return red1(fin - sal)
        return null
    }

    /** Concepto 4: km declarados buscando pedidos (reposicionamientos a puntos rojos). */
    fun kmBuscandoPedidos(jid: Long): Double {
        var t = 0.0
        for ((paso, _, nota) in eventosJornada(jid)) {
            if (paso != "REPOSICIONAMIENTO") continue
            t += numNota(nota, "km") ?: 0.0
        }
        return red1(t)
    }

    /** Concepto 3: km para recoger pedido (de la entrega anterior, o de la zona, al parking de cada pedido).
     *  Incluye los pedidos reasignados (no se anulan) y descuenta lo ya declarado como "buscando pedidos". */
    fun kmParaRecoger(jid: Long): Double {
        var total = 0.0
        var ref = kmRefZona(jid)
        for (p in pedidos(jid)) {
            val f = fases(jid, p.id)
            val park = f.firstOrNull { it.first == "LLEGADA_PARKING" }?.third?.let { numNota(it, "km") }
            val cli = f.firstOrNull { it.first == "LLEGADA_CLIENTE" }?.third?.let { numNota(it, "km") }
            if (park != null && ref != null && park > ref) total += (park - ref)
            ref = cli ?: park ?: ref
        }
        total -= kmBuscandoPedidos(jid)
        return if (total > 0) red1(total) else 0.0
    }

    /** Concepto 5: mantenimiento de la moto registrado en la jornada. */
    fun mantenimientoJornada(jid: Long): Double =
        Math.round(gastos(jid).filter { it.tipo == "MANTENIMIENTO" }.sumOf { it.importe } * 100.0) / 100.0

    // ---------------- v2.1.0: consultas para la JUSTIFICACION (solo lectura) ----------------

    /**
     * Jornada a la que pertenece un pedido. Mira primero el flujo y, si el pedido solo aparece
     * en los pantallazos, recorre las jornadas buscando su numero en la evidencia.
     * Devuelve null si la app no tiene ningun dato de ese pedido.
     */
    fun jornadaDePedido(pedido: String): Long? {
        db.rawQuery("SELECT jornada_id FROM flujo WHERE pedido=? ORDER BY ts LIMIT 1", arrayOf(pedido)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val ids = ArrayList<Long>()
        db.rawQuery("SELECT id FROM jornada ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        for (id in ids) {
            val (a, b) = rango(id)
            db.rawQuery("SELECT 1 FROM evidencia WHERE ts>=? AND ts<=? AND texto LIKE ? LIMIT 1",
                    arrayOf(a.toString(), b.toString(), "%" + pedido + "%")).use { c ->
                if (c.moveToFirst()) return id
            }
        }
        return null
    }

    /** Pantallazos (CAPTURA) guardados entre dos horas, con la ruta del archivo. */
    fun capturasEntre(desde: Long, hasta: Long): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT ts, IFNULL(archivo,'') FROM evidencia WHERE ts>=? AND ts<=? AND archivo<>'' " +
                "ORDER BY ts", arrayOf(desde.toString(), hasta.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to (c.getString(1) ?: ""))
        }
        return out
    }

    /** Lecturas de cuentakilometros anotadas en la tabla km dentro de un tramo. */
    fun kmLecturasEntre(desde: Long, hasta: Long): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        db.rawQuery("SELECT ts, valor FROM km WHERE ts>=? AND ts<=? ORDER BY ts",
                arrayOf(desde.toString(), hasta.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getDouble(1))
        }
        return out
    }

    /** Ultima lectura de cuentakilometros anterior (o igual) a una hora. */
    fun kmLecturaAntes(ts: Long): Pair<Long, Double>? {
        db.rawQuery("SELECT ts, valor FROM km WHERE ts<=? ORDER BY ts DESC LIMIT 1",
                arrayOf(ts.toString())).use { c ->
            if (c.moveToFirst()) return c.getLong(0) to c.getDouble(1)
        }
        return null
    }

    /** Primera lectura de cuentakilometros posterior (o igual) a una hora. */
    fun kmLecturaDespues(ts: Long): Pair<Long, Double>? {
        db.rawQuery("SELECT ts, valor FROM km WHERE ts>=? ORDER BY ts ASC LIMIT 1",
                arrayOf(ts.toString())).use { c ->
            if (c.moveToFirst()) return c.getLong(0) to c.getDouble(1)
        }
        return null
    }

    private fun pad(s: String, n: Int): String = if (s.length >= n) s.substring(0, n) else s + " ".repeat(n - s.length)
    private fun horaFmt(ms: Long): String = hora.format(Date(ms))
    private fun corto(ms: Long): String = horaFmt(ms).substring(0, 5)
    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f EUR", v)
    private fun kmfmt(v: Double): String = String.format(Locale.US, "%.1f km", v)
}
