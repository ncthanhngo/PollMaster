package com.ncthanhngo.pollmaster.core.formula

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.isEmpty
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ln

/**
 * Evaluates computed fields and formulas against a [FormulaSource].
 * Results are Double, Boolean, String, List (samples) or null. Instances memoize
 * computed fields, so create one per record snapshot.
 */
class FormulaEngine(private val src: FormulaSource) {
    private val memo = HashMap<String, Any?>()
    private val visiting = HashSet<String>()

    private class Ctx(val sample: Int?, val row: RowView? = null)

    /** Value of a computed field: Double?, or List<Double?> when the field has samples. */
    fun computeField(field: Field): Any? {
        val formula = field.formula ?: return null
        if (field.samples == null) return memo.getOrPut(field.id) { guarded(field.id) { eval(formula, null) } }
        return memo.getOrPut(field.id) { guarded(field.id) { eval(formula, field.samples) } }
    }

    private fun guarded(id: String, block: () -> Any?): Any? {
        if (!visiting.add(id)) return null
        return try {
            block()
        } finally {
            visiting.remove(id)
        }
    }

    /** Evaluates [formula]; with [samples] = n evaluates n times and returns a list. Errors give null. */
    fun eval(formula: String, samples: Int?): Any? {
        val expr = try {
            FormulaParser.parse(formula)
        } catch (_: FormulaException) {
            return if (samples != null) List(samples) { null } else null
        }
        if (samples == null) return safeEval(expr, Ctx(null))
        return List(samples) { safeEval(expr, Ctx(it)) }
    }

    private fun safeEval(e: Expr, ctx: Ctx): Any? = try {
        eval(e, ctx)
    } catch (_: FormulaException) {
        null
    }

    private fun eval(e: Expr, ctx: Ctx): Any? = when (e) {
        is Expr.Num -> e.value
        is Expr.Str -> e.value
        is Expr.Bool -> e.value
        Expr.Null -> null
        is Expr.Ref -> readRef(e, ctx)
        is Expr.RowRef -> readRowRef(e.column, ctx)
        is Expr.Ident -> throw FormulaException("Tên '${e.name}' chỉ dùng làm tham số bảng")
        is Expr.Unary -> unary(e, ctx)
        is Expr.Binary -> binary(e, ctx)
        is Expr.Call -> call(e, ctx)
    }

    // ---- references ----

    private fun readRef(ref: Expr.Ref, ctx: Ctx): Any? {
        val def = src.field(ref.id)
        if (def?.type == "computed") {
            if (ref.part != null) return null
            return pick(computeField(def), ctx.sample)
        }
        val fv = src.value(ref.id)
        if (fv?.flag == FieldValue.FLAG_NOT_MEASURABLE) return null
        if (ref.part != null) return toValue(fv?.parts?.get(ref.part))
        val raw = fv?.v
        if (raw is JsonArray) {
            val sampled = def == null || def.samples != null
            if (!sampled) return raw.map { toValue(it) }
            return ctx.sample?.let { toValue(raw.getOrNull(it)) }
        }
        return toValue(raw)
    }

    private fun pick(result: Any?, sample: Int?): Any? =
        if (result is List<*>) sample?.let { result.getOrNull(it) } else result

    private fun readRowRef(col: String, ctx: Ctx): Any? {
        val rv = ctx.row ?: throw FormulaException("{@$col} chỉ dùng trong hàm bảng")
        val fv = rv.row.values[col]
        if (fv?.flag == FieldValue.FLAG_NOT_MEASURABLE) return null
        if (fv?.v.isEmptyJson()) return toValue(rv.fixed[col])
        return toValue(fv?.v)
    }

    private fun toValue(e: JsonElement?): Any? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.content.toDoubleOrNull()
        }
        is JsonArray -> e.map { toValue(it) }
        else -> null
    }

    // ---- operators ----

    /** Only boolean `true` is truthy; numbers, strings, "unknown" and null are all false. */
    private fun truthy(x: Any?): Boolean = x == true

    private fun unary(e: Expr.Unary, ctx: Ctx): Any? {
        val v = eval(e.operand, ctx)
        return if (e.op == '!') !truthy(v) else (v as? Double)?.let { -it }
    }

    private fun binary(e: Expr.Binary, ctx: Ctx): Any? {
        when (e.op) {
            "&&" -> return truthy(eval(e.left, ctx)) && truthy(eval(e.right, ctx))
            "||" -> return truthy(eval(e.left, ctx)) || truthy(eval(e.right, ctx))
        }
        val l = eval(e.left, ctx)
        val r = eval(e.right, ctx)
        return when (e.op) {
            "==" -> looseEquals(l, r)
            "!=" -> !looseEquals(l, r)
            "<", "<=", ">", ">=" -> compare(e.op, l, r)
            else -> arithmetic(e.op, l, r)
        }
    }

    private fun looseEquals(l: Any?, r: Any?): Boolean = when {
        l == null || r == null -> l == null && r == null
        else -> l == r
    }

    private fun compare(op: String, l: Any?, r: Any?): Boolean? {
        val c = when {
            l is Double && r is Double -> l.compareTo(r)
            l is String && r is String -> l.compareTo(r)
            else -> return null
        }
        return when (op) {
            "<" -> c < 0
            "<=" -> c <= 0
            ">" -> c > 0
            else -> c >= 0
        }
    }

    private fun arithmetic(op: String, l: Any?, r: Any?): Double? {
        if (l !is Double || r !is Double) return null
        return when (op) {
            "+" -> l + r
            "-" -> l - r
            "*" -> l * r
            else -> if (r == 0.0) null else l / r
        }
    }

    // ---- functions ----

    private fun call(c: Expr.Call, ctx: Ctx): Any? {
        val a = c.args
        return when (c.name) {
            "avg", "sum", "min", "max" -> aggregate(c, ctx)
            "count" -> tableRows(a, ctx, 1).size.toDouble()
            "pct" -> pct(a, ctx)
            "dewpoint" -> dewpoint(num(a, 0, ctx), num(a, 1, ctx))
            "round" -> round(num(a, 0, ctx), (num(a, 1, ctx) ?: 0.0).toInt())
            "abs" -> num(a, 0, ctx)?.let { abs(it) }
            "if" -> if (truthy(eval(arg(a, 0), ctx))) eval(arg(a, 1), ctx) else eval(arg(a, 2), ctx)
            else -> throw FormulaException("Hàm không hỗ trợ: ${c.name}")
        }
    }

    private fun arg(a: List<Expr>, i: Int): Expr = a.getOrNull(i) ?: throw FormulaException("Thiếu tham số ${i + 1}")

    private fun num(a: List<Expr>, i: Int, ctx: Ctx): Double? = eval(arg(a, i), ctx) as? Double

    /** Non-empty rows of the table named by the first arg that pass the filter at [filterIdx]. */
    private fun tableRows(a: List<Expr>, ctx: Ctx, filterIdx: Int): List<RowView> {
        val table = (arg(a, 0) as? Expr.Ident)?.name ?: throw FormulaException("Tham số đầu phải là tên bảng")
        val filter = a.getOrNull(filterIdx)
        return src.rows(table).filter { rv ->
            !rv.row.isEmpty() && (filter == null || truthy(eval(filter, Ctx(ctx.sample, rv))))
        }
    }

    private fun aggregate(c: Expr.Call, ctx: Ctx): Double? {
        val expr = arg(c.args, 1)
        val nums = tableRows(c.args, ctx, 2).mapNotNull { eval(expr, Ctx(ctx.sample, it)) as? Double }
        if (nums.isEmpty()) return null
        return when (c.name) {
            "avg" -> nums.average()
            "sum" -> nums.sum()
            "min" -> nums.min()
            else -> nums.max()
        }
    }

    private fun pct(a: List<Expr>, ctx: Ctx): Double? {
        val cond = arg(a, 1)
        val rows = tableRows(a, ctx, 2)
        if (rows.isEmpty()) return null
        val hits = rows.count { truthy(eval(cond, Ctx(ctx.sample, it))) }
        return 100.0 * hits / rows.size
    }

    private fun dewpoint(t: Double?, rh: Double?): Double? {
        if (t == null || rh == null || rh <= 0.0) return null
        val a = 17.62
        val b = 243.12
        val gamma = ln(rh / 100.0) + a * t / (b + t)
        return b * gamma / (a - gamma)
    }

    private fun round(x: Double?, digits: Int): Double? {
        if (x == null || x.isNaN() || x.isInfinite()) return null
        return BigDecimal(x.toString()).setScale(digits, RoundingMode.HALF_UP).toDouble()
    }
}
