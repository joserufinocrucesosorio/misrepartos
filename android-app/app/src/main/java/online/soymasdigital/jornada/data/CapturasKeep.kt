package online.soymasdigital.jornada.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v2.1.1 - Copia REDUCIDA y PROPIA de las capturas de los dias con jornada.
 *
 * Las capturas normales viven en /sdcard/Android/data/<paquete>/files/capturas y se borran
 * al archivarlas en el PC. Si llega una sancion dias despues, la justificacion salia sin
 * imagenes. Aqui se guarda una segunda copia dentro del almacenamiento INTERNO de la app
 * (filesDir/capturas_keep/AAAA-MM-DD/HHMMSS_<captura>.jpg), que ni la limpieza externa ni
 * el archivero tocan y que solo es accesible con run-as.
 *
 * Limites: 720 px de ancho, JPEG calidad 60, maximo 120 imagenes por dia (se conservan las
 * mas cercanas a los hitos), retencion de 30 dias y tope total de 400 MB.
 *
 * Todo va envuelto en try/catch: un fallo al archivar la copia NUNCA debe romper la captura.
 */
object CapturasKeep {

    private const val TAG = "CapturasKeep"
    private const val CARPETA = "capturas_keep"
    private const val INDICE = ".indice.tsv"

    private const val ANCHO_MAX = 720
    private const val CALIDAD = 60
    private const val MAX_POR_DIA = 120
    private const val DIAS_RETENCION = 30L
    private const val TOPE_BYTES = 400L * 1024L * 1024L

    /** Etiquetas (o trozos) que cuentan como HITO: sus capturas son las ultimas en descartarse. */
    private val HITOS = arrayOf("RECIBO", "RECOGIDA", "ENTREGA", "LLEGADA_CLIENTE")

    private val FECHA = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val HORA = SimpleDateFormat("HHmmss", Locale.US)

    /** Un solo hilo: las copias se hacen fuera del hilo principal y sin carreras con el indice. */
    private val cola = Executors.newSingleThreadExecutor { r ->
        Thread(r, "capturas-keep").apply { isDaemon = true }
    }

    // ---------------- API ----------------

    /** Raiz de las copias (se crea si no existe). */
    fun raiz(ctx: Context): File = File(ctx.filesDir, CARPETA).apply { if (!exists()) mkdirs() }

    /** Carpeta de un dia concreto (se crea si no existe). */
    fun carpetaDia(ctx: Context, ts: Long): File {
        val d = File(raiz(ctx), FECHA.format(Date(ts)))
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * Copia reducida de una captura ya guardada en disco. Se ejecuta en segundo plano y
     * traga cualquier error: la captura original ya esta hecha y registrada.
     */
    fun guardarDesdeArchivo(ctx: Context, original: File, etiqueta: String) {
        try {
            val app = ctx.applicationContext
            cola.execute {
                try { guardarAhora(app, original, etiqueta) }
                catch (e: Exception) { Log.w(TAG, "copia no guardada (" + original.name + "): " + e.message) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo encolar la copia: " + e.message)
        }
    }

    /** Mantenimiento: retencion de 30 dias y tope total de 400 MB. En segundo plano. */
    fun limpiar(ctx: Context) {
        try {
            val app = ctx.applicationContext
            cola.execute {
                try { limpiarAhora(app) } catch (e: Exception) { Log.w(TAG, "limpieza: " + e.message) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo encolar la limpieza: " + e.message)
        }
    }

    /** Imagenes de las copias propias dentro de un tramo, como (hora, ruta). */
    fun listarVentana(ctx: Context, desde: Long, hasta: Long): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        try {
            if (desde <= 0L || hasta < desde) return out
            val zona = ZoneId.systemDefault()
            var dia = java.time.Instant.ofEpochMilli(desde).atZone(zona).toLocalDate()
            val fin = java.time.Instant.ofEpochMilli(hasta).atZone(zona).toLocalDate()
            var guardia = 0
            while (!dia.isAfter(fin) && guardia < 400) {
                guardia++
                val dir = File(raiz(ctx), dia.toString())
                for ((ts, f) in entradasDe(dir)) {
                    if (ts in desde..hasta && f.exists()) out.add(ts to f.absolutePath)
                }
                dia = dia.plusDays(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "listarVentana: " + e.message)
        }
        return out.distinctBy { it.second }.sortedBy { it.first }
    }

    /** Tamano ocupado por las copias, en bytes. */
    fun tamanoBytes(ctx: Context): Long {
        var total = 0L
        try {
            val r = raiz(ctx)
            val hijos = r.listFiles() ?: return 0L
            for (d in hijos) if (d.isDirectory) total += tamanoDir(d)
        } catch (e: Exception) { }
        return total
    }

    // ---------------- copia ----------------

    private fun guardarAhora(ctx: Context, original: File, etiqueta: String) {
        if (!original.isFile || original.length() <= 0L) return
        val ahora = System.currentTimeMillis()
        val dir = carpetaDia(ctx, ahora)
        val nombre = HORA.format(Date(ahora)) + "_" + original.name
        val destino = File(dir, nombre)

        val bm = reducir(original) ?: return
        try {
            FileOutputStream(destino).use { bm.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it) }
            Log.i(TAG, "copia guardada: " + destino.absolutePath + " (" + destino.length() + " B)")
        } finally {
            try { bm.recycle() } catch (e: Exception) { }
        }

        registrarIndice(dir, nombre, ahora, etiqueta)
        recortarDia(dir)
    }

    /** Decodifica con inSampleSize (para no cargar el bitmap entero) y ajusta a 720 px de ancho. */
    private fun reducir(archivo: File): Bitmap? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(archivo.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var muestra = 1
            while (bounds.outWidth / (muestra * 2) >= ANCHO_MAX) muestra *= 2

            var bm = BitmapFactory.decodeFile(archivo.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return null
            if (bm.width > ANCHO_MAX) {
                val alto = Math.max(1, Math.round(bm.height.toFloat() * ANCHO_MAX / bm.width))
                val esc = Bitmap.createScaledBitmap(bm, ANCHO_MAX, alto, true)
                if (esc !== bm) {
                    bm.recycle()
                    bm = esc
                }
            }
            return bm
        } catch (e: Exception) {
            Log.w(TAG, "reducir: " + e.message)
            return null
        }
    }

    // ---------------- indice y recorte por dia ----------------

    private fun registrarIndice(dir: File, nombre: String, ts: Long, etiqueta: String) {
        try {
            val et = etiqueta.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(60)
            File(dir, INDICE).appendText(nombre + "\t" + ts + "\t" + et + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "indice: " + e.message)
        }
    }

    /** Lee el indice del dia y completa con los ficheros que no esten anotados. */
    private fun entradasDe(dir: File): List<Pair<Long, File>> {
        if (!dir.isDirectory) return emptyList()
        val porNombre = LinkedHashMap<String, Pair<Long, String>>()
        try {
            val idx = File(dir, INDICE)
            if (idx.isFile) {
                idx.forEachLine { linea ->
                    val p = linea.split('\t')
                    if (p.size >= 2) {
                        val ts = p[1].toLongOrNull()
                        if (ts != null) porNombre[p[0]] = ts to (if (p.size >= 3) p[2] else "")
                    }
                }
            }
        } catch (e: Exception) { }

        val out = ArrayList<Pair<Long, File>>()
        val hijos = dir.listFiles() ?: return out
        for (f in hijos) {
            if (!f.isFile || f.name == INDICE) continue
            val n = f.name.lowercase(Locale.US)
            if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) continue
            val ts = porNombre[f.name]?.first ?: f.lastModified()
            out.add(ts to f)
        }
        out.sortBy { it.first }
        return out
    }

    private fun etiquetasDe(dir: File): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            val idx = File(dir, INDICE)
            if (idx.isFile) idx.forEachLine { linea ->
                val p = linea.split('\t')
                if (p.size >= 3) m[p[0]] = p[2]
            }
        } catch (e: Exception) { }
        return m
    }

    /** Si el dia pasa de 120 imagenes, conserva las mas cercanas a los hitos y borra el resto. */
    private fun recortarDia(dir: File) {
        try {
            val entradas = entradasDe(dir)
            if (entradas.size <= MAX_POR_DIA) return

            val etiquetas = etiquetasDe(dir)
            fun esHito(f: File): Boolean {
                val et = (etiquetas[f.name] ?: "").uppercase(Locale.US)
                return HITOS.any { et.contains(it) }
            }
            val hitos = entradas.filter { esHito(it.second) }.map { it.first }.sorted()
            fun distancia(ts: Long): Long {
                if (hitos.isEmpty()) return Long.MAX_VALUE / 2
                var mejor = Long.MAX_VALUE / 2
                for (h in hitos) {
                    val d = Math.abs(ts - h)
                    if (d < mejor) mejor = d
                }
                return mejor
            }

            // Primero las capturas que SON hito; luego las mas cercanas a un hito; a igualdad,
            // las mas recientes (es lo que suele interesar de un dia sin hitos anotados).
            val orden = entradas.sortedWith(
                    compareBy<Pair<Long, File>> { if (esHito(it.second)) 0 else 1 }
                            .thenBy { distancia(it.first) }
                            .thenByDescending { it.first })
            val conservar = orden.take(MAX_POR_DIA).map { it.second.name }.toHashSet()
            var borradas = 0
            for ((_, f) in entradas) {
                if (conservar.contains(f.name)) continue
                try { if (f.delete()) borradas++ } catch (e: Exception) { }
            }
            reescribirIndice(dir, conservar, etiquetas)
            Log.i(TAG, "recorte " + dir.name + ": " + borradas + " imagenes descartadas")
        } catch (e: Exception) {
            Log.w(TAG, "recortarDia: " + e.message)
        }
    }

    private fun reescribirIndice(dir: File, conservar: Set<String>, etiquetas: Map<String, String>) {
        try {
            val entradas = entradasDe(dir).filter { conservar.contains(it.second.name) }
            val sb = StringBuilder()
            for ((ts, f) in entradas) {
                sb.append(f.name).append('\t').append(ts).append('\t')
                        .append(etiquetas[f.name] ?: "").append('\n')
            }
            File(dir, INDICE).writeText(sb.toString())
        } catch (e: Exception) {
            Log.w(TAG, "reescribirIndice: " + e.message)
        }
    }

    // ---------------- mantenimiento ----------------

    private fun limpiarAhora(ctx: Context) {
        val raiz = raiz(ctx)
        val hoy = LocalDate.now()
        val corte = hoy.minusDays(DIAS_RETENCION)
        val dias = ArrayList<Pair<LocalDate, File>>()
        val hijos = raiz.listFiles() ?: return
        for (d in hijos) {
            if (!d.isDirectory) continue
            val fecha = try { LocalDate.parse(d.name) } catch (e: Exception) { null } ?: continue
            dias.add(fecha to d)
        }
        dias.sortBy { it.first }

        for ((fecha, d) in dias) {
            if (fecha.isBefore(corte)) {
                try {
                    d.deleteRecursively()
                    Log.i(TAG, "retencion: borrado " + d.name)
                } catch (e: Exception) { Log.w(TAG, "retencion " + d.name + ": " + e.message) }
            }
        }

        var total = 0L
        val vivos = ArrayList<Pair<LocalDate, File>>()
        for ((fecha, d) in dias) {
            if (!d.exists()) continue
            val t = tamanoDir(d)
            total += t
            vivos.add(fecha to d)
        }
        for ((fecha, d) in vivos) {
            if (total <= TOPE_BYTES) break
            if (fecha == hoy) break   // nunca se borra el dia en curso
            val t = tamanoDir(d)
            try {
                if (d.deleteRecursively()) {
                    total -= t
                    Log.i(TAG, "tope 400 MB: borrado " + d.name)
                }
            } catch (e: Exception) { Log.w(TAG, "tope " + d.name + ": " + e.message) }
        }
        Log.i(TAG, "limpieza: " + vivos.size + " dias, " + tamanoBytes(ctx) + " B")
    }

    private fun tamanoDir(dir: File): Long {
        var t = 0L
        try {
            val hijos = dir.listFiles() ?: return 0L
            for (f in hijos) t += if (f.isDirectory) tamanoDir(f) else f.length()
        } catch (e: Exception) { }
        return t
    }
}
