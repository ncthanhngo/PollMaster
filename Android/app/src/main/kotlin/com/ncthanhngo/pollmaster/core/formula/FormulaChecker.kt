package com.ncthanhngo.pollmaster.core.formula

/** Static check used at template import: unknown functions, argument counts, `{@x}` scope and table names. */
object FormulaChecker {
    private val tableFns = mapOf("avg" to 2..3, "sum" to 2..3, "min" to 2..3, "max" to 2..3, "count" to 1..2, "pct" to 2..3)
    private val plainFns = mapOf("dewpoint" to 2..2, "round" to 2..2, "abs" to 1..1, "if" to 3..3)

    /** Returns the first problem found in [source], or null when it is valid. [tableIds] are the template's tables. */
    fun check(source: String, tableIds: Set<String>): String? = try {
        visit(FormulaParser.parse(source), false, tableIds)
        null
    } catch (e: FormulaException) {
        e.message
    }

    private fun visit(e: Expr, inRow: Boolean, tables: Set<String>) {
        when (e) {
            is Expr.RowRef -> if (!inRow) throw FormulaException("{@${e.column}} chỉ dùng trong hàm bảng")
            is Expr.Ident -> throw FormulaException("Tên '${e.name}' chỉ dùng làm tham số đầu của hàm bảng")
            is Expr.Unary -> visit(e.operand, inRow, tables)
            is Expr.Binary -> {
                visit(e.left, inRow, tables)
                visit(e.right, inRow, tables)
            }
            is Expr.Call -> call(e, inRow, tables)
            else -> Unit
        }
    }

    private fun call(c: Expr.Call, inRow: Boolean, tables: Set<String>) {
        val tableRange = tableFns[c.name]
        val range = tableRange ?: plainFns[c.name] ?: throw FormulaException("Hàm không hỗ trợ: ${c.name}")
        if (c.args.size !in range) throw FormulaException("Hàm ${c.name} cần ${range.first}-${range.last} tham số, có ${c.args.size}")
        if (tableRange == null) {
            c.args.forEach { visit(it, inRow, tables) }
            return
        }
        val table = (c.args[0] as? Expr.Ident)?.name ?: throw FormulaException("Tham số đầu của ${c.name} phải là tên bảng")
        if (table !in tables) throw FormulaException("Bảng không tồn tại: $table")
        c.args.drop(1).forEach { visit(it, true, tables) }
    }
}

/** Field codes a parsed formula reads (`{A-01}`, `{B-06.rows}` -> "B-06"); `{@col}` and table names are not fields. */
fun formulaReferences(e: Expr): List<String> = when (e) {
    is Expr.Ref -> listOf(e.id)
    is Expr.Unary -> formulaReferences(e.operand)
    is Expr.Binary -> formulaReferences(e.left) + formulaReferences(e.right)
    is Expr.Call -> e.args.flatMap(::formulaReferences)
    else -> emptyList()
}
