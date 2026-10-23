package com.ncthanhngo.pollmaster.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {
    /** Opens the share sheet with all [files] at once. */
    fun share(context: Context, files: List<File>, title: String) {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        }
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
