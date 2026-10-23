package com.ncthanhngo.pollmaster.core.formula

class FormulaException(message: String) : Exception(message)

sealed interface Expr {
    data class Num(val value: Double) : Expr
    data class Str(val value: String) : Expr
    data class Bool(val value: Boolean) : Expr
    data object Null : Expr
    data class Ref(val id: String, val part: String?) : Expr
    data class RowRef(val column: String) : Expr
    data class Ident(val name: String) : Expr
    data class Call(val name: String, val args: List<Expr>) : Expr
    data class Unary(val op: Char, val operand: Expr) : Expr
    data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
}

/** Recursive-descent parser for the grammar in the template schema (section 3). */
class FormulaParser private constructor(private val src: String) {
    private var pos = 0

    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Expr>()

        fun parse(source: String): Expr = cache[source] ?: FormulaParser(source).parseAll().also { cache[source] = it }
    }

    private fun parseAll(): Expr {
        val e = parseOr()
        skipSpaces()
        if (pos < src.length) fail("Ký tự thừa '${src[pos]}'")
        return e
    }

    private fun fail(msg: String): Nothing = throw FormulaException("$msg (vị trí $pos): $src")

    private fun skipSpaces() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun accept(token: String): Boolean {
        skipSpaces()
        if (src.startsWith(token, pos)) {
            pos += token.length
            return true
        }
        return false
    }

    private fun parseOr(): Expr {
        var left = parseAnd()
        while (accept("||")) left = Expr.Binary("||", left, parseAnd())
        return left
    }

    private fun parseAnd(): Expr {
        var left = parseCmp()
        while (accept("&&")) left = Expr.Binary("&&", left, parseCmp())
        return left
    }

    private fun parseCmp(): Expr {
        val left = parseAdd()
        for (op in listOf("==", "!=", "<=", ">=", "<", ">")) {
            if (accept(op)) return Expr.Binary(op, left, parseAdd())
        }
        return left
    }

    private fun parseAdd(): Expr {
        var left = parseMul()
        while (true) {
            left = when {
                accept("+") -> Expr.Binary("+", left, parseMul())
                accept("-") -> Expr.Binary("-", left, parseMul())
                else -> return left
            }
        }
    }

    private fun parseMul(): Expr {
        var left = parseUnary()
        while (true) {
            left = when {
                accept("*") -> Expr.Binary("*", left, parseUnary())
                accept("/") -> Expr.Binary("/", left, parseUnary())
                else -> return left
            }
        }
    }

    private fun parseUnary(): Expr {
        skipSpaces()
        if (pos < src.length && src[pos] == '!' && !src.startsWith("!=", pos)) {
            pos++
            return Expr.Unary('!', parseUnary())
        }
        if (accept("-")) return Expr.Unary('-', parseUnary())
        return parsePrimary()
    }

    private fun parsePrimary(): Expr {
        skipSpaces()
        if (pos >= src.length) fail("Biểu thức kết thúc đột ngột")
        val c = src[pos]
        return when {
            c.isDigit() || c == '.' -> parseNumber()
            c == '"' -> parseString()
            c == '{' -> parseRef()
            c == '(' -> {
                pos++
                val e = parseOr()
                if (!accept(")")) fail("Thiếu ')'")
                e
            }
            c.isLetter() || c == '_' -> parseIdent()
            else -> fail("Ký tự không hợp lệ '$c'")
        }
    }

    private fun parseNumber(): Expr {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        val value = src.substring(start, pos).toDoubleOrNull() ?: fail("Số không hợp lệ")
        return Expr.Num(value)
    }

    private fun parseString(): Expr {
        pos++
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != '"') {
            if (src[pos] == '\\' && pos + 1 < src.length) pos++
            sb.append(src[pos++])
        }
        if (pos >= src.length) fail("Chuỗi chưa đóng")
        pos++
        return Expr.Str(sb.toString())
    }

    private fun parseRef(): Expr {
        val end = src.indexOf('}', pos)
        if (end < 0) fail("Thiếu '}'")
        val body = src.substring(pos + 1, end).trim()
        pos = end + 1
        if (body.isEmpty()) fail("Tham chiếu rỗng")
        if (body.startsWith("@")) return Expr.RowRef(body.substring(1))
        val dot = body.indexOf('.')
        return if (dot < 0) Expr.Ref(body, null) else Expr.Ref(body.substring(0, dot), body.substring(dot + 1))
    }

    private fun parseIdent(): Expr {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        val name = src.substring(start, pos)
        when (name) {
            "true" -> return Expr.Bool(true)
            "false" -> return Expr.Bool(false)
            "null" -> return Expr.Null
        }
        skipSpaces()
        if (pos < src.length && src[pos] == '(') {
            pos++
            val args = mutableListOf<Expr>()
            if (!accept(")")) {
                do {
                    args.add(parseOr())
                } while (accept(","))
                if (!accept(")")) fail("Thiếu ')' sau tham số hàm $name")
            }
            return Expr.Call(name, args)
        }
        return Expr.Ident(name)
    }
}
