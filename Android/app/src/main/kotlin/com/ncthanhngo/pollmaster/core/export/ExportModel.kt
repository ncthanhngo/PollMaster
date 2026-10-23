package com.ncthanhngo.pollmaster.core.export

/** A spreadsheet cell: text or a plain number. */
sealed interface Cell {
    data class Text(val value: String) : Cell
    data class Num(val value: Double) : Cell
}

/** [headerRows] = number of leading rows (the headers, plus that many minus 1 of [rows]) shown bold and frozen. */
data class SheetData(val name: String, val headers: List<String>, val rows: List<List<Cell>>, val headerRows: Int = 1)

data class DocImage(val path: String, val caption: String)

/** Format-neutral document layout rendered by both the PDF and the Word exporter. */
sealed interface DocBlock {
    data class Title(val text: String, val subtitle: String?) : DocBlock
    data class SectionTitle(val text: String) : DocBlock
    data class Heading(val text: String) : DocBlock
    data class Paragraph(val text: String) : DocBlock
    data class Grid(val headers: List<String>, val rows: List<List<String>>, val weights: List<Float>) : DocBlock
    data class Images(val items: List<DocImage>) : DocBlock
    data class Signature(val label: String, val name: String?, val signedAt: String?, val imagePath: String?) : DocBlock
    data object PageBreak : DocBlock
}

data class ExportDoc(val blocks: List<DocBlock>)
