package online.soymasdigital.jornada.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * v2.0.0 - DESTINO DE LOS PDF (informe diario, expediente y factura).
 *
 * El usuario elige la carpeta con el selector del sistema (Storage Access Framework:
 * ACTION_OPEN_DOCUMENT_TREE) y el permiso se conserva entre reinicios con
 * takePersistableUriPermission. La URI queda guardada en las preferencias.
 *
 * Si no hay carpeta elegida (o el permiso se perdio) se usa la carpeta privada de la
 * app (/sdcard/Android/data/<paquete>/files/...), que no necesita permisos, y se avisa.
 *
 * La app NO pide android.permission.INTERNET: solo escribe en almacenamiento local.
 */
object SalidaPdf {

    const val PREFS = "jornada_prefs"
    private const val CLAVE_URI = "carpeta_informes_uri"
    private const val CLAVE_NOMBRE = "carpeta_informes_nombre"
    const val MIME_PDF = "application/pdf"

    /** Resultado de guardar un PDF. */
    data class Salida(
        val uri: Uri,
        val nombre: String,
        val bytes: Long,
        val destino: String,
        val esCarpetaUsuario: Boolean,
        val archivo: File?
    )

    // ---------------- carpeta elegida ----------------

    fun carpeta(ctx: Context): Uri? = try {
        prefs(ctx).getString(CLAVE_URI, null)?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
    } catch (e: Exception) {
        null
    }

    fun nombreCarpeta(ctx: Context): String? =
        prefs(ctx).getString(CLAVE_NOMBRE, null)?.takeIf { it.isNotEmpty() }

    /** Guarda la carpeta elegida y se queda con el permiso de lectura/escritura. */
    fun guardarCarpeta(ctx: Context, uri: Uri): String {
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val nombre = try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            val cola = id.substringAfterLast(':')
            if (cola.isNotEmpty()) cola else (uri.lastPathSegment ?: "carpeta")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "carpeta"
        }
        prefs(ctx).edit().putString(CLAVE_URI, uri.toString()).putString(CLAVE_NOMBRE, nombre).apply()
        return nombre
    }

    fun olvidarCarpeta(ctx: Context) {
        prefs(ctx).edit().remove(CLAVE_URI).remove(CLAVE_NOMBRE).apply()
    }

    fun tienePermisoEscritura(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    } catch (e: Exception) {
        false
    }

    fun disponible(ctx: Context): Boolean {
        val c = carpeta(ctx) ?: return false
        return tienePermisoEscritura(ctx, c)
    }

    // ---------------- guardado ----------------

    /**
     * Escribe el PDF y devuelve a donde fue. [subcarpeta] solo se usa en el respaldo privado
     * ("informes", "expedientes", "facturas"). [nombreBase] va sin extension.
     */
    fun guardar(ctx: Context, subcarpeta: String, nombreBase: String,
                escribir: (OutputStream) -> Unit): Salida {
        val buf = ByteArrayOutputStream()
        escribir(buf)
        val datos = buf.toByteArray()
        if (datos.isEmpty()) throw IllegalStateException("El PDF quedo vacio")

        val tree = carpeta(ctx)
        if (tree != null && tienePermisoEscritura(ctx, tree)) {
            try {
                return enCarpetaUsuario(ctx, tree, nombreBase, datos)
            } catch (e: Exception) {
                // Permiso revocado o carpeta borrada: no rompemos la generacion, se usa la privada.
                return enCarpetaPrivada(ctx, subcarpeta, nombreBase, datos)
            }
        }
        return enCarpetaPrivada(ctx, subcarpeta, nombreBase, datos)
    }

    private fun enCarpetaUsuario(ctx: Context, tree: Uri, nombreBase: String, datos: ByteArray): Salida {
        val res = ctx.contentResolver
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val padre = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val ocupados = nombresHijos(ctx, tree, docId)
        var nombre = nombreBase + ".pdf"
        var k = 2
        while (ocupados.contains(nombre.lowercase(Locale.US))) {
            nombre = nombreBase + "_" + k + ".pdf"
            k++
        }
        val docUri = DocumentsContract.createDocument(res, padre, MIME_PDF, nombre)
            ?: throw IllegalStateException("La carpeta elegida no admite crear archivos")
        try {
            res.openOutputStream(docUri, "w")?.use { it.write(datos) }
                ?: throw IllegalStateException("No se pudo escribir en la carpeta elegida")
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(res, docUri) } catch (e2: Exception) { }
            throw e
        }
        val carpeta = nombreCarpeta(ctx) ?: "carpeta elegida"
        return Salida(docUri, nombre, datos.size.toLong(), "Carpeta elegida: " + carpeta, true, null)
    }

    private fun enCarpetaPrivada(ctx: Context, subcarpeta: String, nombreBase: String,
                                 datos: ByteArray): Salida {
        val dir = File(ctx.getExternalFilesDir(null), subcarpeta).apply { mkdirs() }
        var f = File(dir, nombreBase + ".pdf")
        var k = 2
        while (f.exists()) {
            f = File(dir, nombreBase + "_" + k + ".pdf")
            k++
        }
        FileOutputStream(f).use { it.write(datos) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        return Salida(uri, f.name, f.length(), f.absolutePath, false, f)
    }

    /** Nombres ya usados en la carpeta elegida (en minusculas) para no sobrescribir nada. */
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
            // Algunos proveedores no permiten listar: se intenta crear igualmente.
        }
        return out
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
