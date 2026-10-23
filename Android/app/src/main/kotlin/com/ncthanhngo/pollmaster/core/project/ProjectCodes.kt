package com.ncthanhngo.pollmaster.core.project

import java.text.Normalizer

/** Project codes: upper case `A-Z 0-9 -`, at most 16 characters, unique on the device (schema 9.1). */
object ProjectCodes {
    const val MAX_LENGTH = 16
    private val valid = Regex("[A-Z0-9]+(-[A-Z0-9]+)*")

    /** Strips diacritics (đ -> d), upper-cases, turns every run of other characters into one `-`, trims to 16 at a `-` boundary. */
    fun fromName(name: String): String {
        val plain = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .uppercase()
        val slug = plain.replace(Regex("[^A-Z0-9]+"), "-").trim('-')
        return trim(slug, MAX_LENGTH)
    }

    /** Cuts [slug] to [max] characters, preferring the last `-` boundary when the cut would split a word. */
    private fun trim(slug: String, max: Int): String {
        if (slug.length <= max) return slug
        val cut = slug.take(max)
        val splitsWord = !cut.endsWith('-') && slug[max] != '-'
        val boundary = cut.lastIndexOf('-')
        return (if (splitsWord && boundary > 0) cut.take(boundary) else cut).trim('-')
    }

    fun isValid(code: String): Boolean = code.length <= MAX_LENGTH && valid.matches(code)

    /** Suggests a code for [name] that is not in [taken] (compared upper-case): `X`, `X-2`, `X-3`... still within 16 characters. */
    fun generate(name: String, taken: Set<String>): String = unique(fromName(name).ifEmpty { "DUAN" }, taken)

    /** [base] if free, otherwise [base] + `-2`, `-3`... (the base is shortened to make room for the suffix). */
    fun unique(base: String, taken: Set<String>): String {
        val used = taken.map { it.uppercase() }.toSet()
        if (base.uppercase() !in used) return base
        var n = 2
        while (true) {
            val suffix = "-$n"
            val candidate = trim(base, MAX_LENGTH - suffix.length).trimEnd('-') + suffix
            if (candidate !in used) return candidate
            n++
        }
    }
}
