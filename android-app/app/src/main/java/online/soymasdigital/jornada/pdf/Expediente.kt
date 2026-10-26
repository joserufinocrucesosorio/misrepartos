package online.soymasdigital.jornada.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Genera el expediente en PDF con las APIs del sistema (sin dependencias externas). */
object Expediente {

    fun generar(ctx: Context, titulo: String, lineas: List<String>): SalidaPdf.Salida {
        val doc = PdfDocument()
        val pTitulo = Paint().apply { textSize = 14f; isFakeBoldText = true }
        val pTexto = Paint().apply { textSize = 10f }
        val pPie = Paint().apply { textSize = 8f }
        val firma = "JoseRufino · " + SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())

        var paginas = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun nuevaPagina() {
            val info = PdfDocument.PageInfo.Builder(595, 842, ++paginas).create()
            page = doc.startPage(info)
            y = 50f
        }

        fun pie() {
            page!!.canvas.drawText("Documento generado por el repartidor (datos propios de su actividad) · " + firma,
                    40f, 820f, pPie)
        }

        nuevaPagina()
        page!!.canvas.drawText(titulo, 40f, y, pTitulo)
        y += 24f

        for (l in lineas) {
            for (parte in envolver(l, 104)) {
                if (y > 800f) {
                    pie()
                    doc.finishPage(page!!)
                    nuevaPagina()
                }
                page!!.canvas.drawText(parte, 40f, y, pTexto)
                y += 13f
            }
        }
        pie()
        doc.finishPage(page!!)
        return SalidaPdf.guardar(ctx, "expedientes", "Expediente_" + System.currentTimeMillis()) { doc.writeTo(it); doc.close() }
    }

    /**
     * Parte una linea larga en varias SIN cortar palabras (antes se cortaba a 104
     * caracteres y quedaban frases incompletas y palabras partidas por la mitad).
     */
    private fun envolver(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        var resto = s
        var primera = true
        while (resto.length > max) {
            var corte = resto.lastIndexOf(' ', max)
            if (corte < max / 2) corte = max
            out.add((if (primera) "" else "        ") + resto.substring(0, corte).trim())
            resto = resto.substring(corte).trim()
            primera = false
        }
        if (resto.isNotEmpty()) out.add((if (primera) "" else "        ") + resto)
        return out
    }
}
