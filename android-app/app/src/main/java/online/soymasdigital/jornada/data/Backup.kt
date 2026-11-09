package online.soymasdigital.jornada.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import online.soymasdigital.jornada.pdf.SalidaPdf
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * v2.1.2 - COPIA DE SEGURIDAD (exportar / importar).
 *
 * Exporta un ZIP con:
 *   laboral.db          base de datos completa
 *   capturas_keep/...   copia reducida propia de las capturas (interna de la app)
 *   INFO.txt            version, fecha, numero de jornadas y capturas
 *
 * Se escribe en la carpeta que el usuario eligio con el selector del sistema (SAF, el mismo
 * de los PDF). Si no hay carpeta elegida cae a la carpeta privada de la app y se comparte.
 *
 * Importar valida el ZIP, extrae en cache, comprueba que laboral.db es SQLite y solo despues
 * sustituye la base de datos y capturas_keep. El llamador debe cerrar la DB y hacer antes la
 * copia de seguridad del estado actual.
 *
 * Sin root y sin permisos nuevos. La app no declara INTERNET.
 */
object Backup {

    const val MIME_ZIP = "application/zip"
    const val NOMBRE_DB = "laboral.db"
    const val NOMBRE_INFO = "INFO.txt"
    const val CARPETA_CAPTURAS = "capturas_keep"

    private const val CABECERA_SQLITE = "SQLite format 3\u0000"
    private const val MAX_DB_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_KEEP_BYTES = 8L * 1024L * 1024L * 1024L

    /** Resultado de escribir un ZIP. */
    data class Salida(
        val uri: Uri,
        val nombre: String,
        val destino: String,
        val esCarpetaUsuario: Boolean,
        val bytes: Long,
        val archivo: File?
    )

    /** Datos que van en INFO.txt y se muestran al usuario. */
    data class Info(val version: String, val jornadas: Int, val capturas: Int)

    /** Resultado de validar / importar un ZIP. */
    data class Revision(
        val valido: Boolean,
        val motivo: String,
        val jornadas: Int,
        val capturas: Int,
        val versionOrigen: String
    )

    // ---------------- nombres ----------------

    fun nombreExport(): String =
        "MisRepartos_backup_" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date()) + ".zip"

    fun nombreCopiaPrevia(): String = "backup_antes_de_importar"

    /** Cuenta las capturas guardadas en capturas_keep (para INFO.txt). */
    fun contarCapturas(ctx: Context): Int {
        val raiz = File(ctx.filesDir, CARPETA_CAPTURAS)
        if (!raiz.exists()) return 0
        var n = 0
        raiz.walkTopDown().forEach { if (it.isFile) n++ }
        return n
    }

    fun textoInfo(ctx: Context, jornadas: Int, capturas: Int): String {
        val sb = StringBuilder()
        sb.append("MisRepartos - copia de seguridad\n")
        sb.append("Version: ").append(versionApp(ctx)).append('\n')
        sb.append("Fecha: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("Jornadas: ").append(jornadas).append('\n')
        sb.append("Capturas: ").append(capturas).append('\n')
        sb.append("Base de datos: ").append(NOMBRE_DB).append('\n')
        sb.append("Carpeta de capturas: ").append(CARPETA_CAPTURAS).append("/\n")
        return sb.toString()
    }

    private fun versionApp(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        (pi.versionName ?: "?") + " (vc" + (if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()) + ")"
    } catch (e: Exception) {
        "?"
    }

    // ---------------- escritura del ZIP ----------------

    /**
     * Escribe el ZIP completo en [out]. Debe llamarse con la base de datos YA CERRADA
     * (para que el fichero laboral.db este completo y sin journal pendiente).
     */
    fun escribirCopia(ctx: Context, dbFile: File, keepDir: File, info: Info, out: OutputStream): Long {
        if (!dbFile.exists() || dbFile.length() == 0L) {
            throw IllegalStateException("No se encontro la base de datos laboral.db")
        }
        val contador = Contador(out)
        ZipOutputStream(BufferedOutputStream(contador, 64 * 1024)).use { z ->
            z.setLevel(6)
            // base de datos
            entry(z, NOMBRE_DB, dbFile, comprimir = true)
            // capturas_keep completa (los .jpg ya van comprimidos: se guardan sin recomprimir)
            if (keepDir.exists()) {
                val raiz = keepDir.canonicalFile
                keepDir.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        val rel = f.canonicalFile.relativeTo(raiz).path.replace(File.separatorChar, '/')
                        val comprimir = !f.name.lowercase(Locale.US).endsWith(".jpg") &&
                                !f.name.lowercase(Locale.US).endsWith(".jpeg") &&
                                !f.name.lowercase(Locale.US).endsWith(".png")
                        entry(z, CARPETA_CAPTURAS + "/" + rel, f, comprimir)
                    }
                }
            }
            // INFO.txt
            val infoBytes = textoInfo(ctx, info.jornadas, info.capturas).toByteArray(Charsets.UTF_8)
            z.putNextEntry(ZipEntry(NOMBRE_INFO))
            z.write(infoBytes)
            z.closeEntry()
        }
        return contador.contador
    }

    private fun entry(z: ZipOutputStream, nombre: String, f: File, comprimir: Boolean) {
        val e = ZipEntry(nombre)
        e.time = f.lastModified()
        e.method = if (comprimir) ZipEntry.DEFLATED else ZipEntry.STORED
        if (!comprimir) {
            e.size = f.length()
            e.compressedSize = f.length()
            val crc = java.util.zip.CRC32()
            FileInputStream(f).use { inp ->
                val buf = ByteArray(64 * 1024)
                var n = inp.read(buf)
                while (n > 0) { crc.update(buf, 0, n); n = inp.read(buf) }
            }
            e.crc = crc.value
        }
        z.putNextEntry(e)
        FileInputStream(f).use { inp -> inp.copyTo(z, 64 * 1024) }
        z.closeEntry()
    }

    /** Stream que cuenta los bytes escritos. */
    private class Contador(private val base: OutputStream) : OutputStream() {
        var contador: Long = 0
        override fun write(b: Int) { base.write(b); contador++ }
        override fun write(b: ByteArray, off: Int, len: Int) { base.write(b, off, len); contador += len }
        override fun flush() = base.flush()
        override fun close() = base.close()
    }

    // ---------------- destino (SAF o carpeta privada) ----------------

    /**
     * Guarda un ZIP en la carpeta elegida por el usuario (si hay permiso) o, si no, en la
     * carpeta privada externa [subcarpeta]. Nunca sobrescribe.
     */
    fun guardar(ctx: Context, subcarpeta: String, nombreBase: String, escribir: (OutputStream) -> Unit): Salida {
        val tree = SalidaPdf.carpeta(ctx)
        if (tree != null && SalidaPdf.tienePermisoEscritura(ctx, tree)) {
            try {
                return enCarpetaUsuario(ctx, tree, nombreBase, escribir)
            } catch (e: Exception) {
                // Permiso revocado o carpeta sin escritura: se intenta la privada.
            }
        }
        return enCarpetaPrivada(ctx, subcarpeta, nombreBase, escribir)
    }

    private fun enCarpetaUsuario(ctx: Context, tree: Uri, nombreBase: String, escribir: (OutputStream) -> Unit): Salida {
        val res = ctx.contentResolver
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val padre = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val ocupados = nombresHijos(ctx, tree, docId)
        var nombre = nombreBase + ".zip"
        var k = 2
        while (ocupados.contains(nombre.lowercase(Locale.US))) {
            nombre = nombreBase + "_" + k + ".zip"
            k++
        }
        val docUri = DocumentsContract.createDocument(res, padre, MIME_ZIP, nombre)
            ?: throw IllegalStateException("La carpeta elegida no admite crear archivos")
        try {
            var bytes = 0L
            res.openOutputStream(docUri, "w")?.use { os ->
                val c = Contador(os)
                escribir(c)
                bytes = c.contador
            } ?: throw IllegalStateException("No se pudo escribir en la carpeta elegida")
            val carpeta = SalidaPdf.nombreCarpeta(ctx) ?: "carpeta elegida"
            return Salida(docUri, nombre, "Carpeta elegida: " + carpeta, true, bytes, null)
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(res, docUri) } catch (e2: Exception) { }
            throw e
        }
    }

    private fun enCarpetaPrivada(ctx: Context, subcarpeta: String, nombreBase: String,
                                 escribir: (OutputStream) -> Unit): Salida {
        val dir = File(ctx.getExternalFilesDir(null), subcarpeta).apply { mkdirs() }
        var f = File(dir, nombreBase + ".zip")
        var k = 2
        while (f.exists()) {
            f = File(dir, nombreBase + "_" + k + ".zip")
            k++
        }
        FileOutputStream(f).use { os ->
            escribir(Contador(os))
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        return Salida(uri, f.name, f.absolutePath, false, f.length(), f)
    }

    private fun nombresHijos(ctx: Context, tree: Uri, docId: String): Set<String> {
        val out = HashSet<String>()
        val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        try {
            ctx.contentResolver.query(
                hijos,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let { out.add(it.lowercase(Locale.US)) }
                }
            }
        } catch (e: Exception) {
            // Algunos proveedores no permiten listar: se crea igualmente con nombre unico por fecha.
        }
        return out
    }

    // ---------------- lectura / validacion ----------------

    /** Primera pasada: comprueba que el ZIP trae un laboral.db SQLite valido y cuenta capturas. */
    fun revisar(ctx: Context, uri: Uri): Revision {
        var tieneDb = false
        var dbOk = false
        var capturas = 0
        var info = ""
        var version = ""
        val cabecera = ByteArray(16)
        var leidosCabecera = 0
        try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw, 64 * 1024)).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val nombre = e.name ?: ""
                        if (!e.isDirectory) {
                            when {
                                nombre == NOMBRE_DB -> {
                                    tieneDb = true
                                    while (leidosCabecera < 16) {
                                        val n = z.read(cabecera, leidosCabecera, 16 - leidosCabecera)
                                        if (n <= 0) break
                                        leidosCabecera += n
                                    }
                                    dbOk = leidosCabecera >= 16 &&
                                            String(cabecera, 0, 16, Charsets.US_ASCII) == CABECERA_SQLITE
                                }
                                nombre == NOMBRE_INFO -> {
                                    info = leerTexto(z, 8192)
                                }
                                nombre.startsWith(CARPETA_CAPTURAS + "/") -> capturas++
                            }
                        }
                        e = z.nextEntry
                    }
                }
            } ?: return Revision(false, "No se pudo abrir el fichero elegido", 0, 0, "")
        } catch (e: Exception) {
            return Revision(false, "El fichero no es un ZIP valido: " + e.message, 0, 0, "")
        }
        if (!tieneDb) return Revision(false, "El ZIP no contiene laboral.db", capturas, 0, version)
        if (!dbOk) return Revision(false, "laboral.db no parece una base de datos SQLite", capturas, 0, version)
        var jornadasInfo = 0
        for (linea in info.lines()) {
            val l = linea.trim()
            if (l.startsWith("Jornadas:", true)) jornadasInfo = l.substringAfter(':').trim().toIntOrNull() ?: 0
            if (l.startsWith("Version:", true)) version = l.substringAfter(':').trim()
        }
        return Revision(true, "", jornadasInfo, capturas, version)
    }

    /** Lee hasta [max] bytes de una entrada de texto del ZIP (INFO.txt). */
    private fun leerTexto(z: ZipInputStream, max: Int): String {
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(1024)
        var total = 0
        while (total < max) {
            val n = z.read(b, 0, minOf(b.size, max - total))
            if (n <= 0) break
            buf.write(b, 0, n)
            total += n
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    // ---------------- importacion ----------------

    /**
     * Extrae y SUSTITUYE laboral.db y capturas_keep. El llamador debe haber cerrado la DB y
     * haber guardado antes la copia del estado actual.
     *
     * Todo se extrae primero en cache y se valida; solo al final se toca la base de datos real.
     */
    fun importar(ctx: Context, uri: Uri, dbDest: File, keepDest: File): Revision {
        val tmp = File(ctx.cacheDir, "import_backup").apply { deleteRecursively(); mkdirs() }
        val tmpDb = File(tmp, NOMBRE_DB)
        val tmpKeep = File(tmp, CARPETA_CAPTURAS)
        var capturas = 0
        var bytesDb = 0L
        var bytesKeep = 0L
        try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw, 64 * 1024)).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val nombre = e.name ?: ""
                        if (!e.isDirectory) {
                            if (nombre == NOMBRE_DB) {
                                FileOutputStream(tmpDb).use { os ->
                                    bytesDb = copiarLimitado(z, os, MAX_DB_BYTES)
                                }
                            } else if (nombre.startsWith(CARPETA_CAPTURAS + "/")) {
                                val dest = destinoSeguro(tmpKeep, nombre)
                                if (dest != null) {
                                    dest.parentFile?.mkdirs()
                                    FileOutputStream(dest).use { os ->
                                        bytesKeep += copiarLimitado(z, os, MAX_KEEP_BYTES)
                                    }
                                    capturas++
                                }
                            }
                        }
                        e = z.nextEntry
                    }
                }
            } ?: throw IllegalStateException("No se pudo abrir el fichero elegido")
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw IllegalStateException("Error al extraer el ZIP: " + e.message)
        }

        if (!tmpDb.exists() || bytesDb < 1024) {
            tmp.deleteRecursively()
            throw IllegalStateException("El ZIP no contiene una base de datos valida")
        }
        val cabecera = ByteArray(16)
        FileInputStream(tmpDb).use { it.read(cabecera) }
        if (String(cabecera, Charsets.US_ASCII) != CABECERA_SQLITE) {
            tmp.deleteRecursively()
            throw IllegalStateException("laboral.db no es una base de datos SQLite")
        }

        // Validacion real: abrir en solo lectura y contar jornadas.
        var jornadas = 0
        try {
            val db = SQLiteDatabase.openDatabase(tmpDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT COUNT(*) FROM jornada", null).use { c ->
                    if (c.moveToFirst()) jornadas = c.getInt(0)
                }
                db.rawQuery("PRAGMA quick_check", null).use { c ->
                    if (c.moveToFirst()) {
                        val r = c.getString(0)
                        if (r != null && !r.equals("ok", true)) throw IllegalStateException("la base de datos esta danada")
                    }
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw IllegalStateException("La base de datos del ZIP no se puede abrir: " + e.message)
        }

        // ---- sustitucion (solo ahora se toca lo real) ----
        dbDest.parentFile?.mkdirs()
        borrarDbYDiarios(dbDest)
        var ok = tmpDb.renameTo(dbDest)
        if (!ok) {
            FileInputStream(tmpDb).use { inp -> FileOutputStream(dbDest).use { out -> inp.copyTo(out) } }
            ok = dbDest.exists() && dbDest.length() > 0
            tmpDb.delete()
        }
        if (!ok) {
            tmp.deleteRecursively()
            throw IllegalStateException("No se pudo escribir la nueva base de datos")
        }

        try {
            keepDest.deleteRecursively()
            if (tmpKeep.exists()) {
                if (!tmpKeep.renameTo(keepDest)) {
                    copiarArbol(tmpKeep, keepDest)
                    tmpKeep.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            // La base de datos ya esta puesta; un fallo en las capturas no invalida la importacion.
        }

        tmp.deleteRecursively()
        return Revision(true, "", jornadas, capturas, "")
    }

    private fun borrarDbYDiarios(db: File) {
        for (sufijo in arrayOf("", "-journal", "-wal", "-shm")) {
            val f = File(db.path + sufijo)
            if (f.exists()) f.delete()
        }
    }

    /** Copia hasta [max] bytes; devuelve los bytes copiados. */
    private fun copiarLimitado(inp: InputStream, out: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var n = inp.read(buf)
        while (n > 0) {
            total += n
            if (total > max) throw IllegalStateException("El ZIP es demasiado grande")
            out.write(buf, 0, n)
            n = inp.read(buf)
        }
        return total
    }

    private fun copiarArbol(origen: File, destino: File) {
        destino.mkdirs()
        origen.listFiles()?.forEach { f ->
            val d = File(destino, f.name)
            if (f.isDirectory) copiarArbol(f, d) else {
                d.parentFile?.mkdirs()
                FileInputStream(f).use { inp -> FileOutputStream(d).use { out -> inp.copyTo(out) } }
            }
        }
    }

    /** Ruta segura dentro de [base] (evita Zip Slip). */
    private fun destinoSeguro(base: File, nombre: String): File? {
        val rel = nombre.removePrefix(CARPETA_CAPTURAS + "/").trimStart('/')
        if (rel.isEmpty()) return null
        val f = File(base, rel)
        return try {
            val canon = f.canonicalFile
            val raiz = base.canonicalFile
            if (canon.path == raiz.path || canon.path.startsWith(raiz.path + File.separator)) canon else null
        } catch (e: Exception) {
            null
        }
    }

    /** Intent para compartir el ZIP guardado. */
    fun intentCompartir(ctx: Context, s: Salida): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_ZIP
        putExtra(Intent.EXTRA_STREAM, s.uri)
        putExtra(Intent.EXTRA_SUBJECT, s.nombre.removeSuffix(".zip"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(s.nombre, s.uri)
    }
}
