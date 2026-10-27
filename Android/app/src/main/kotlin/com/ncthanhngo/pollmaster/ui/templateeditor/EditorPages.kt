package com.ncthanhngo.pollmaster.ui.templateeditor

import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.PathKey
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateDoc
import com.ncthanhngo.pollmaster.core.templateeditor.docPath
import com.ncthanhngo.pollmaster.core.templateeditor.parseDocPath

object EditorPages {
    /** Page stack that shows the place [path] ("sections[1].items[3].options[0]") of [doc]: the index, then down to the deepest form that has it. */
    fun stackFor(doc: TemplateDoc, path: String): List<EditorPage> {
        val steps = parseDocPath(path).orEmpty()
        fun key(n: Int) = (steps.getOrNull(n) as? PathKey.Key)?.name
        fun idx(n: Int) = (steps.getOrNull(n) as? PathKey.Index)?.i
        val stack = arrayListOf<EditorPage>(EditorPage.Index)
        var list: DocPath = emptyList()
        var i = 0
        when (key(0)) {
            null -> Unit
            "sections" -> {
                val s = idx(1)
                if (s != null && doc.node(docPath("sections", s)) != null) {
                    list = docPath("sections", s, "items")
                    stack += EditorPage.Items(list)
                    if (key(2) == "items") i = 3 else list = emptyList()
                }
            }
            "header", "signatures" -> { list = docPath(key(0)!!); stack += EditorPage.Items(list); i = 1 }
            else -> stack += EditorPage.Info
        }
        while (list.isNotEmpty()) {
            val k = idx(i) ?: break
            val node = list + PathKey.Index(k)
            if (doc.node(node) == null) break
            stack += EditorPage.Item(list, k)
            val next = key(i + 1)
            if (next != "columns" && next != "rows") break
            list = node + PathKey.Key(next)
            stack += EditorPage.Items(list)
            i += 2
        }
        return stack
    }
}
