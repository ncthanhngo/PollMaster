package com.ncthanhngo.pollmaster.core.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocImageData(val bytes: ByteArray, val widthPx: Int, val heightPx: Int, val extension: String)

fun interface DocImageLoader {
    /** Loads the image at [path] (relative to the record folder), or null when missing/unreadable. */
    fun load(path: String): DocImageData?
}

/** Hand-written WordprocessingML package (A4 portrait, bordered tables, embedded images, page-number footer). */
class DocxWriter(private val loader: DocImageLoader) {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val pageTextTwips = 9638
    private val images = mutableListOf<Pair<String, DocImageData>>() // media name -> data
    private val body = StringBuilder()

    fun write(doc: ExportDoc, out: OutputStream) {
        doc.blocks.forEach(::render)
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes())
            zip.put("_rels/.rels", XML_DECL + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$r/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
            zip.put("word/document.xml", document())
            zip.put("word/_rels/document.xml.rels", documentRels())
            zip.put("word/styles.xml", styles())
            zip.put("word/footer1.xml", footer())
            images.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry("word/media/$name"))
                zip.write(data.bytes)
                zip.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // ---- blocks ----

    private fun render(b: DocBlock) {
        when (b) {
            is DocBlock.Title -> {
                body.append(para(b.text, bold = true, size = 36, align = "center"))
                b.subtitle?.let { body.append(para(it, size = 22, align = "center")) }
            }
            is DocBlock.SectionTitle -> body.append(para(b.text, bold = true, size = 28, before = 240))
            is DocBlock.Heading -> body.append(para(b.text, bold = true, size = 24, before = 160))
            is DocBlock.Paragraph -> body.append(para(b.text, size = 20, italic = true))
            is DocBlock.Grid -> body.append(grid(b)).append(para("", size = 8))
            is DocBlock.Images -> body.append(imageTable(b.items))
            is DocBlock.Signature -> body.append(signature(b))
            DocBlock.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
    }

    private fun runs(text: String, bold: Boolean, italic: Boolean, size: Int): String {
        val props = (if (bold) "<w:b/>" else "") + (if (italic) "<w:i/>" else "") + "<w:sz w:val=\"$size\"/>"
        return text.split('\n').mapIndexed { i, line ->
            "<w:r><w:rPr>$props</w:rPr>" + (if (i > 0) "<w:br/>" else "") +
                "<w:t xml:space=\"preserve\">${xmlEscape(line)}</w:t></w:r>"
        }.joinToString("")
    }

    private fun para(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        size: Int = 20,
        align: String? = null,
        before: Int = 0,
    ): String {
        val ppr = (if (before > 0) "<w:spacing w:before=\"$before\" w:after=\"80\"/>" else "") +
            (if (align != null) "<w:jc w:val=\"$align\"/>" else "")
        return "<w:p>" + (if (ppr.isNotEmpty()) "<w:pPr>$ppr</w:pPr>" else "") + runs(text, bold, italic, size) + "</w:p>"
    }

    private fun cell(width: Int, content: String, shade: Boolean = false) =
        "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>" +
            (if (shade) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"D9D9D9\"/>" else "") + "</w:tcPr>$content</w:tc>"

    private fun grid(g: DocBlock.Grid): String {
        val widths = g.weights.map { (it * pageTextTwips).toInt() }
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"$pageTextTwips\" w:type=\"dxa\"/>")
        sb.append("<w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
        }
        sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>")
        widths.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
        sb.append("</w:tblGrid><w:tr><w:trPr><w:tblHeader/></w:trPr>")
        g.headers.forEachIndexed { i, h -> sb.append(cell(widths[i], para(h, bold = true, size = 18), shade = true)) }
        sb.append("</w:tr>")
        for (row in g.rows) {
            sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            widths.forEachIndexed { i, wd -> sb.append(cell(wd, para(row.getOrElse(i) { "" }, size = 18))) }
            sb.append("</w:tr>")
        }
        return sb.append("</w:tbl>").toString()
    }

    private fun imageTable(items: List<DocImage>): String {
        val half = pageTextTwips / 2
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"$pageTextTwips\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/></w:tblPr>")
        sb.append("<w:tblGrid><w:gridCol w:w=\"$half\"/><w:gridCol w:w=\"$half\"/></w:tblGrid>")
        items.chunked(2).forEach { pair ->
            sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            for (i in 0 until 2) {
                val item = pair.getOrNull(i)
                sb.append(cell(half, if (item == null) para("") else imageParagraph(item.path, 3_000_000, 2_700_000) + para(item.caption, size = 16)))
            }
            sb.append("</w:tr>")
        }
        return sb.append("</w:tbl>").append(para("", size = 8)).toString()
    }

    private fun imageParagraph(path: String, maxCx: Long, maxCy: Long): String {
        val data = loader.load(path) ?: return para("[${path.substringAfterLast('/')}]", size = 16)
        val name = "image${images.size + 1}.${data.extension}"
        images += name to data
        val rid = "rIdImg${images.size}"
        val scale = minOf(maxCx.toDouble() / data.widthPx, maxCy.toDouble() / data.heightPx)
        val cx = (data.widthPx * scale).toLong().coerceAtLeast(1)
        val cy = (data.heightPx * scale).toLong().coerceAtLeast(1)
        val id = images.size
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Picture $id\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    private fun signature(s: DocBlock.Signature): String {
        val who = listOfNotNull(s.name, s.signedAt).joinToString(" – ")
        val image = s.imagePath?.let { imageParagraph(it, 2_400_000, 1_000_000) } ?: para("(chưa ký)", italic = true)
        return para(s.label, bold = true, size = 22, before = 200) + image + para(who, size = 20)
    }

    // ---- package parts ----

    private fun document(): String = XML_DECL +
        "<w:document xmlns:w=\"$w\" xmlns:r=\"$r\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>" + body +
        "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/>" +
        "<w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/>" +
        "</w:sectPr></w:body></w:document>"

    private fun documentRels() = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rIdStyles\" Type=\"$r/styles\" Target=\"styles.xml\"/>" +
        "<Relationship Id=\"rIdFooter\" Type=\"$r/footer\" Target=\"footer1.xml\"/>" +
        images.mapIndexed { i, (name, _) ->
            "<Relationship Id=\"rIdImg${i + 1}\" Type=\"$r/image\" Target=\"media/$name\"/>"
        }.joinToString("") + "</Relationships>"

    private fun contentTypes() = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
        "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" +
        "</Types>"

    private fun styles() = XML_DECL + "<w:styles xmlns:w=\"$w\"><w:docDefaults><w:rPrDefault><w:rPr>" +
        "<w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\" w:eastAsia=\"Arial\"/><w:sz w:val=\"20\"/><w:lang w:val=\"vi-VN\"/>" +
        "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>" +
        "</w:docDefaults></w:styles>"

    private fun field(instruction: String, cached: String) =
        "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>" +
            "<w:r><w:instrText xml:space=\"preserve\"> $instruction </w:instrText></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>$cached</w:t></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"

    /** Footer "Trang n/N" using PAGE and NUMPAGES fields. */
    private fun footer() = XML_DECL + "<w:ftr xmlns:w=\"$w\"><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>" +
        "<w:r><w:t xml:space=\"preserve\">Trang </w:t></w:r>" + field("PAGE", "1") +
        "<w:r><w:t>/</w:t></w:r>" + field("NUMPAGES", "1") + "</w:p></w:ftr>"
}
