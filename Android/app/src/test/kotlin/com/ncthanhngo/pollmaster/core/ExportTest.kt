package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.export.CsvWriter
import com.ncthanhngo.pollmaster.core.export.DocBlock
import com.ncthanhngo.pollmaster.core.export.DocImageData
import com.ncthanhngo.pollmaster.core.export.DocxWriter
import com.ncthanhngo.pollmaster.core.export.ExportBuilder
import com.ncthanhngo.pollmaster.core.export.ExportJson
import com.ncthanhngo.pollmaster.core.export.XlsxWriter
import com.ncthanhngo.pollmaster.core.export.ZipExporter
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.SignatureValue
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportTest {
    private val t = TestData.template

    private fun arr(vararg x: Double?) =
        JsonArray(x.map { if (it == null) kotlinx.serialization.json.JsonNull else JsonPrimitive(it) })

    private fun record(): Record {
        return TestData.emptyRecord().copy(
            values = mapOf(
                "HD-code" to TestData.text("HD 01/A"),
                "B-06" to FieldValue(
                    flag = "estimate",
                    parts = mapOf("rows" to JsonPrimitive(2), "cols" to JsonPrimitive(3), "screen" to JsonPrimitive("trái & phải <x>")),
                ),
                "G-22" to FieldValue(v = arr(30.0, 25.0, null)),
                "G-23" to FieldValue(v = arr(60.0, 80.0, 50.0)),
                "G-02" to FieldValue(flag = "notMeasurable", reason = "không có la bàn"),
                "P01-04" to FieldValue(v = PollJson.parseToJsonElement("""[{"file":"media/HD-01-A_P01-04_01.jpg"}]""")),
            ),
            tables = mapOf(
                "E1" to listOf(Row("r1", mapOf("mode" to TestData.text("O"), "hand" to TestData.text("2T"), "attempts" to FieldValue(v = JsonPrimitive(1))))),
                "E2" to listOf(Row("S04-5", mapOf("entered" to FieldValue(v = JsonPrimitive(true))))),
            ),
            signatures = mapOf("surveyor" to SignatureValue("media/sig_surveyor.png", "Nguyễn Văn A", "2026-01-01T09:00:00+07:00")),
        )
    }

    private fun wellFormed(bytes: ByteArray) {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                m[e.name] = z.readBytes()
            }
        }
        return m
    }

    @Test fun exportJsonHasContractShape() {
        val o = ExportJson.build(t, record())
        assertEquals("BB-TDP-01", o.getValue("template").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("draft", o.getValue("record").jsonObject.getValue("status").jsonPrimitive.content)
        val fields = o.getValue("fields").jsonObject
        assertEquals("HD 01/A", fields.getValue("HD-code").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("Mã biên bản", fields.getValue("HD-code").jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("notMeasurable", fields.getValue("G-02").jsonObject.getValue("flag").jsonPrimitive.content)
        assertEquals("không có la bàn", fields.getValue("G-02").jsonObject.getValue("reason").jsonPrimitive.content)
        assertTrue(fields.getValue("B-06").jsonObject.containsKey("parts"))
        assertEquals("HD-01-A_P01-04_01.jpg", fields.getValue("P01-04").jsonObject.getValue("value").jsonArray.single().jsonPrimitive.content)
        val computed = o.getValue("computed").jsonObject
        assertEquals(3, computed.getValue("G-24").jsonArray.size)
        assertEquals(21.385418967118323, computed.getValue("G-24").jsonArray[0].jsonPrimitive.content.toDouble(), 1e-9)
        assertTrue(computed.getValue("G-24").jsonArray[2] is kotlinx.serialization.json.JsonNull)
        assertEquals(100.0, computed.getValue("E-03").jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(5.0, computed.getValue("E-01").jsonPrimitive.content.toDouble(), 1e-9)
        val e1 = o.getValue("tables").jsonObject.getValue("E1").jsonArray.single().jsonObject
        assertEquals("r1", e1.getValue("id").jsonPrimitive.content)
        assertEquals("O", e1.getValue("mode").jsonPrimitive.content)
        assertEquals(t.sections.flatMap { it.items }.filterIsInstance<com.ncthanhngo.pollmaster.core.model.TableItem>().first { it.id == "E2" }.rows!!.size, o.getValue("tables").jsonObject.getValue("E2").jsonArray.size)
        val e2first = o.getValue("tables").jsonObject.getValue("E2").jsonArray[0].jsonObject
        assertEquals("S04", e2first.getValue("kb").jsonPrimitive.content) // readonly column from fixed
    }

    @Test fun zipContainsRecordExportAndMedia() {
        val dir = TestData.tempDir()
        val store = RecordStore(dir)
        val r = record()
        store.save(r)
        File(store.mediaDir(r.id), "a.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val out = ByteArrayOutputStream()
        ZipExporter.write(t, r, store.dir(r.id), out)
        val entries = zipEntries(out.toByteArray())
        assertEquals(setOf("record.json", "export.json", "media/a.jpg"), entries.keys)
        val back = PollJson.decodeFromString(Record.serializer(), entries.getValue("record.json").decodeToString())
        assertEquals(r, back)
        PollJson.parseToJsonElement(entries.getValue("export.json").decodeToString()).jsonObject
    }

    @Test fun xlsxHasExpectedPartsAndWellFormedXml() {
        val sheets = ExportBuilder(t, record()).allSheets()
        assertEquals("Thông tin", sheets.first().name)
        assertEquals(5, sheets.size) // info + 4 tables
        val out = ByteArrayOutputStream()
        XlsxWriter.write(sheets, out)
        val entries = zipEntries(out.toByteArray())
        for (p in listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet5.xml", "xl/styles.xml")) {
            assertTrue("missing $p", p in entries)
        }
        entries.forEach { (name, bytes) -> if (name.endsWith(".xml") || name.endsWith(".rels")) wellFormed(bytes) }
        val sheet1 = entries.getValue("xl/worksheets/sheet1.xml").decodeToString()
        assertTrue(sheet1.contains("trái &amp; phải &lt;x&gt;"))
        assertTrue(sheet1.contains("KĐĐ (không có la bàn)"))
        assertTrue(sheet1.contains("Số hàng: 2"))
        assertTrue(entries.getValue("xl/workbook.xml").decodeToString().contains("name=\"E1\""))
    }

    @Test fun infoSheetFormatsValues() {
        val rows = ExportBuilder(t, record()).infoSheet().rows
        fun valueOf(id: String) = (rows.first { (it[1] as com.ncthanhngo.pollmaster.core.export.Cell.Text).value == id }[3])
        assertEquals(
            com.ncthanhngo.pollmaster.core.export.Cell.Text("~Số hàng: 2; Số cột: 3; Vị trí màn hình: trái & phải <x>"),
            valueOf("B-06"),
        )
        assertEquals(com.ncthanhngo.pollmaster.core.export.Cell.Text("21.4 | 21.3 | –"), valueOf("G-24"))
        assertEquals(com.ncthanhngo.pollmaster.core.export.Cell.Num(5.0), valueOf("E-01"))
    }

    @Test fun csvHasBomAndQuoting() {
        val out = ByteArrayOutputStream()
        CsvWriter.write(ExportBuilder(t, record()).infoSheet(), out)
        val bytes = out.toByteArray()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        val text = bytes.decodeToString(3)
        assertTrue(text.startsWith("Mục,Mã,Thông số,Giá trị,Đơn vị,Cờ,Lý do,Ghi chú\r\n"))
        assertTrue(text.contains("~Số hàng: 2; Số cột: 3; Vị trí màn hình: trái & phải <x>"))
    }

    @Test fun docxHasExpectedPartsAndWellFormedXml() {
        val doc = ExportBuilder(t, record()).document()
        assertTrue(doc.blocks.any { it is DocBlock.Signature })
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val out = ByteArrayOutputStream()
        DocxWriter { path -> if (path.endsWith(".png") || path.endsWith(".jpg")) DocImageData(png, 400, 300, path.substringAfterLast('.')) else null }
            .write(doc, out)
        val entries = zipEntries(out.toByteArray())
        for (p in listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels", "word/footer1.xml")) {
            assertTrue("missing $p", p in entries)
        }
        assertTrue(entries.keys.any { it.startsWith("word/media/") })
        entries.forEach { (name, bytes) -> if (name.endsWith(".xml") || name.endsWith(".rels")) wellFormed(bytes) }
        val xml = entries.getValue("word/document.xml").decodeToString()
        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains("Biên bản khảo sát thông số kỹ thuật"))
        assertTrue(xml.contains("Nguyễn Văn A"))
    }

    @Test fun documentLayoutPutsImagesAfterSectionAndSignaturesLast() {
        val blocks = ExportBuilder(t, record()).document().blocks
        val imagesAt = blocks.indexOfFirst { it is DocBlock.Images }
        assertTrue(imagesAt > 0)
        assertTrue(blocks.indexOfFirst { it is DocBlock.PageBreak } > imagesAt)
        assertEquals(2, blocks.count { it is DocBlock.Signature })
        val media = (blocks[imagesAt] as DocBlock.Images).items.single()
        assertEquals("media/HD-01-A_P01-04_01.jpg", media.path)
    }
}
