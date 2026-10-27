package com.ncthanhngo.pollmaster.core.project

/** Which texts of an ERP error body are fit to show to the user. */
object ErpMessages {
    private val MACHINE_CODE = Regex("^[A-Z0-9_]+$")
    private const val VIETNAMESE_MARKS = "ăâđêôơưàáạảãằắặẳẵầấậẩẫèéẹẻẽềếệểễìíịỉĩòóọỏõồốộổỗờớợởỡùúụủũừứựửữỳýỵỷỹ"

    /** A real Vietnamese sentence: several words with Vietnamese letters; not a SCREAMING_SNAKE code and not English ("Invalid credentials", "Key: ..."). */
    fun isUserSentence(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || MACHINE_CODE.matches(t) || ' ' !in t) return false
        return t.lowercase().any { it in VIETNAMESE_MARKS }
    }
}
