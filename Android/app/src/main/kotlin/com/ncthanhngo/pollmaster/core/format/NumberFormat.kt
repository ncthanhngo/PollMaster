package com.ncthanhngo.pollmaster.core.format

import java.math.BigDecimal
import java.math.RoundingMode

/** Rounds half away from zero to [decimals] places and strips trailing zeros ("12.50" -> "12.5"). */
fun formatNumber(value: Double, decimals: Int = 2): String {
    if (value.isNaN() || value.isInfinite()) return ""
    val bd = BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
    val s = bd.toPlainString()
    return if (s == "-0") "0" else s
}

/** The stored value as written: shortest decimal representation, no exponent ("0.1234567", "5"). */
fun formatShortest(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return ""
    val s = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    return if (s == "-0") "0" else s
}

/** [value] rounded half away from zero to [decimals] places, as a number. */
fun roundHalfAway(value: Double, decimals: Int): Double {
    if (value.isNaN() || value.isInfinite()) return value
    return BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP).toDouble()
}

/** Seconds as mm:ss, or h:mm:ss from one hour on. */
fun formatElapsed(totalSeconds: Double): String {
    val total = Math.round(totalSeconds).coerceAtLeast(0)
    val locale = java.util.Locale.ROOT
    return if (total >= 3600) String.format(locale, "%d:%02d:%02d", total / 3600, total % 3600 / 60, total % 60)
    else String.format(locale, "%02d:%02d", total / 60, total % 60)
}
