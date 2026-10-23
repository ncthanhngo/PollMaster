package com.ncthanhngo.pollmaster.core.export

/** Escapes text for XML and drops characters that are illegal in XML 1.0. */
fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c == '\t' || c == '\n' || c == '\r' || c.code >= 0x20 && c.code != 0xFFFE && c.code != 0xFFFF -> sb.append(c)
        }
    }
    return sb.toString()
}

const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
