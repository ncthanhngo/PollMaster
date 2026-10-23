package com.ncthanhngo.pollmaster.core.storage

import com.ncthanhngo.pollmaster.core.model.Template

/** Compares dotted versions numerically ("1.10" > "1.9"); non-numeric parts compare as text. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.')
    val pb = b.split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { "0" }
        val y = pb.getOrElse(i) { "0" }
        val nx = x.toIntOrNull()
        val ny = y.toIntOrNull()
        val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}

/** Only the newest version of every template id; new records are created from these. */
fun newestVersions(templates: List<Template>): List<Template> =
    templates.groupBy { it.id }.values.map { group -> group.maxWith { x, y -> compareVersions(x.version, y.version) } }
