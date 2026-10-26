package online.soymasdigital.jornada.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FACTURA DE COBRO del repartidor (documento de reclamación, sin IVA).
 *
 * No es una factura fiscal: es el documento con el que el repartidor reclama a la
 * plataforma los conceptos que no le paga (km sin pedido, km buscando pedidos,
 * km para recoger pedido y mantenimiento de la moto). Cada linea lleva su detalle
 * para dejar constancia de DE DONDE SALE la cifra.
 */
object Factura {

    data class Linea(val concepto: String, val detalle: String, val importe: Double)

    fun generar(ctx: Context, titulo: String, cabecera: List<String>, lineas: List<Linea>,
                total: Double, pie: List<String>): SalidaPdf.Salida {
        val doc = PdfDocument()
        val pTit = Paint().apply { textSize = 15f; isFakeBoldText = true }
        val pSub = Paint().apply { textSize = 10f; isFakeBoldText = true }
        val pTxt = Paint().apply { textSize = 9.5f }
        val pDer = Paint().apply { textSize = 9.5f; textAlign = Paint.Align.RIGHT }
        val pPie = Paint().apply { textSize = 8f }

        val info = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(info)
        val cv = page.canvas
        var y = 55f

        cv.drawText(titulo, 40f, y, pTit); y += 26f
        for (l in cabecera) {
            for (parte in envolver(l, 96)) { cv.drawText(parte, 40f, y, pTxt); y += 12f }
        }
        y += 8f
        cv.drawLine(40f, y, 555f, y, pTxt); y += 18f
        cv.drawText("Concepto", 40f, y, pSub)
        cv.drawText("Detalle / de donde sale", 235f, y, pSub)
        cv.drawText("Importe", 555f, y, pDer); y += 6f
        cv.drawLine(40f, y, 555f, y, pTxt); y += 16f

        for (l in lineas) {
            val det = envolver(l.detalle, 62)
            cv.drawText(recortar(l.concepto, 34), 40f, y, pTxt)
            cv.drawText(det.firstOrNull() ?: "", 235f, y, pTxt)
            cv.drawText(String.format(Locale.US, "%.2f EUR", l.importe), 555f, y, pDer)
            y += 14f
            for (extra in det.drop(1)) { cv.drawText(extra, 235f, y, pTxt); y += 12f }
            if (y > 730f) break
        }

        y += 6f
        cv.drawLine(40f, y, 555f, y, pSub); y += 20f
        cv.drawText("TOTAL A COBRAR", 40f, y, pSub)
        cv.drawText(String.format(Locale.US, "%.2f EUR", total), 555f, y, pDer)
        y += 26f

        for (l in pie) {
            for (parte in envolver(l, 104)) { cv.drawText(parte, 40f, y, pPie); y += 11f }
        }
        // v1.9.9: firma del autor al pie del documento
        val pFirma = Paint().apply { textSize = 8f; color = 0xFF666666.toInt() }
        cv.drawText("JoseRufino · " + SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date()), 40f, 820f, pFirma)
        doc.finishPage(page)

        return SalidaPdf.guardar(ctx, "facturas", "Factura_" + System.currentTimeMillis()) {
            doc.writeTo(it)
            doc.close()
        }
    }

    private fun recortar(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n - 1) + "."

    private fun envolver(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        var resto = s.trim()
        while (resto.length > max) {
            var corte = resto.lastIndexOf(' ', max)
            if (corte < max / 2) corte = max
            out.add(resto.substring(0, corte).trim())
            resto = resto.substring(corte).trim()
        }
        if (resto.isNotEmpty()) out.add(resto)
        return out
    }
}
