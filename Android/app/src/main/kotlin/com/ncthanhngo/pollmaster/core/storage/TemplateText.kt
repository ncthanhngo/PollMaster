package com.ncthanhngo.pollmaster.core.storage

object TemplateText {
    private val fence = Regex("```[ \\t]*([A-Za-z]*)[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /** Extracts the JSON object from pasted text: prefers the first ```json fence, else the first fence containing `{`, else the raw text. */
    fun extractJson(text: String): String? {
        val fences = fence.findAll(text).toList()
        val block = fences.firstOrNull { it.groupValues[1].equals("json", ignoreCase = true) }
            ?: fences.firstOrNull { '{' in it.groupValues[2] }
        return outermostObject(block?.groupValues?.get(2) ?: text)
    }

    private fun outermostObject(s: String): String? {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start in 0 until end) s.substring(start, end + 1).trim() else null
    }

    /** Message the user can send back to the AI after an import failure. */
    fun errorReport(prefix: String, errors: String): String =
        prefix + "\n" + errors.lines().filter { it.isNotBlank() }.joinToString("\n") { "- ${it.trim()}" }
}
