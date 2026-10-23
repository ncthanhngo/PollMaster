package com.ncthanhngo.pollmaster.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.ncthanhngo.pollmaster.core.export.DocBlock
import com.ncthanhngo.pollmaster.core.export.ExportDoc
import java.io.File
import java.io.OutputStream

/** Renders an [ExportDoc] to an A4 portrait PDF with the platform text engine (Vietnamese-capable fonts). */
class PdfExporter(private val recordDir: File) {

    fun write(doc: ExportDoc, out: OutputStream) {
        val total = PdfDocument().let { probe -> Renderer(probe, 0).render(doc).also { probe.close() } }
        val pdf = PdfDocument()
        try {
            Renderer(pdf, total).render(doc)
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private inner class Renderer(private val pdf: PdfDocument, private val totalPages: Int) {
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = 0f
        private var pageNo = 0

        private val body = text(9f, false)
        private val bold = text(9f, true)
        private val small = text(8f, false)
        private val line = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.6f }
        private val fill = Paint().apply { color = Color.rgb(224, 224, 224); style = Paint.Style.FILL }

        fun render(doc: ExportDoc): Int {
            newPage()
            doc.blocks.forEach(::block)
            finishPage()
            return pageNo
        }

        private fun text(size: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.BLACK
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun newPage() {
            finishPage()
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            y = MARGIN
        }

        private fun finishPage() {
            val p = page ?: return
            val footer = "Trang $pageNo/$totalPages"
            val c = canvas!!
            c.drawText(footer, (PAGE_W - small.measureText(footer)) / 2f, PAGE_H - 20f, small)
            pdf.finishPage(p)
            page = null
        }

        private fun layout(textValue: String, paint: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
            StaticLayout.Builder.obtain(textValue, 0, textValue.length, paint, width.coerceAtLeast(1))
                .setAlignment(align).setMaxLines(MAX_LINES).setEllipsize(android.text.TextUtils.TruncateAt.END).build()

        private fun ensure(height: Float) {
            if (y + height > PAGE_H - MARGIN - 12f) newPage()
        }

        private fun paragraph(textValue: String, paint: TextPaint, gapBefore: Float = 0f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            val l = layout(textValue, paint, CONTENT_W, align)
            ensure(l.height + gapBefore)
            y += gapBefore
            canvas!!.save(); canvas!!.translate(MARGIN, y); l.draw(canvas); canvas!!.restore()
            y += l.height + 3f
        }

        private fun block(b: DocBlock) {
            when (b) {
                is DocBlock.Title -> {
                    paragraph(b.text, text(18f, true), 0f, Layout.Alignment.ALIGN_CENTER)
                    b.subtitle?.let { paragraph(it, text(10f, false), 0f, Layout.Alignment.ALIGN_CENTER) }
                }
                is DocBlock.SectionTitle -> paragraph(b.text, text(13f, true), 10f)
                is DocBlock.Heading -> paragraph(b.text, text(10.5f, true), 6f)
                is DocBlock.Paragraph -> paragraph(b.text, text(8.5f, false).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) })
                is DocBlock.Grid -> grid(b)
                is DocBlock.Images -> images(b)
                is DocBlock.Signature -> signature(b)
                DocBlock.PageBreak -> newPage()
            }
        }

        private fun grid(g: DocBlock.Grid) {
            val widths = g.weights.map { it * CONTENT_W }
            fun drawRow(cells: List<String>, header: Boolean) {
                val paint = if (header) bold else body
                val layouts = widths.mapIndexed { i, w -> layout(cells.getOrElse(i) { "" }, paint, (w - 2 * PAD).toInt()) }
                val h = (layouts.maxOfOrNull { it.height } ?: 0) + 2 * PAD
                if (y + h > PAGE_H - MARGIN - 12f) {
                    newPage()
                    if (!header) drawRow(g.headers, true)
                }
                var x = MARGIN
                val c = canvas!!
                layouts.forEachIndexed { i, l ->
                    if (header) c.drawRect(x, y, x + widths[i], y + h, fill)
                    c.drawRect(x, y, x + widths[i], y + h, line)
                    c.save(); c.translate(x + PAD, y + PAD); l.draw(c); c.restore()
                    x += widths[i]
                }
                y += h
            }
            drawRow(g.headers, true)
            g.rows.forEach { drawRow(it, false) }
            y += 6f
        }

        private fun images(b: DocBlock.Images) {
            val w = (CONTENT_W - 10f) / 2f
            val maxH = w * 0.75f
            b.items.chunked(2).forEach { pair ->
                val prepared = pair.map { item ->
                    val bmp = decodeScaled(File(recordDir, item.path), 900)
                    Triple(item, bmp, layout(item.caption, small, w.toInt()))
                }
                val h = (prepared.maxOf { (_, bmp, cap) -> (bmp?.let { minOf(maxH, w * it.height / it.width) } ?: 12f) + cap.height }) + 6f
                ensure(h)
                prepared.forEachIndexed { i, (_, bmp, cap) ->
                    val x = MARGIN + i * (w + 10f)
                    var imgH = 0f
                    if (bmp != null) {
                        val scale = minOf(w / bmp.width, maxH / bmp.height)
                        val dw = bmp.width * scale
                        imgH = bmp.height * scale
                        canvas!!.drawBitmap(bmp, null, android.graphics.RectF(x, y, x + dw, y + imgH), null)
                    }
                    canvas!!.save(); canvas!!.translate(x, y + imgH + 2f); cap.draw(canvas); canvas!!.restore()
                }
                y += h
            }
            y += 4f
        }

        private fun signature(s: DocBlock.Signature) {
            val bmp = s.imagePath?.let { decodeScaled(File(recordDir, it), 600) }
            val imgH = bmp?.let { minOf(90f, 240f * it.height / it.width) } ?: 24f
            val who = listOfNotNull(s.name, s.signedAt).joinToString(" – ")
            ensure(imgH + 50f)
            paragraph(s.label, text(11f, true), 10f)
            if (bmp != null) {
                val scale = imgH / bmp.height
                canvas!!.drawBitmap(bmp, null, android.graphics.RectF(MARGIN, y, MARGIN + bmp.width * scale, y + imgH), null)
            } else {
                canvas!!.drawText("(chưa ký)", MARGIN, y + 14f, body)
            }
            y += imgH + 4f
            if (who.isNotEmpty()) paragraph(who, body)
        }
    }

    private companion object {
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 36f
        const val CONTENT_W = 595 - 72
        const val PAD = 3
        const val MAX_LINES = 40
    }
}
