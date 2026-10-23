package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.format.formatShortest
import java.io.OutputStream

/** CSV, UTF-8 with BOM, CRLF line ends. */
object CsvWriter {
    fun write(sheet: SheetData, out: OutputStream) {
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        val sb = StringBuilder()
        sb.append(sheet.headers.joinToString(",", transform = ::quote)).append("\r\n")
        for (row in sheet.rows) {
            sb.append(row.joinToString(",") { quote(textOf(it)) }).append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun textOf(c: Cell) = when (c) {
        is Cell.Text -> c.value
        is Cell.Num -> formatShortest(c.value)
    }

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
