package com.pocketmechanic.spike

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Writes an export to `Downloads/Pacenote/` through MediaStore.
 *
 * MediaStore rather than a raw path: minSdk is 29, so scoped storage is in force,
 * and a Downloads entry is visible to the user and to any file manager without
 * asking for a storage permission. The returned content:// URI is what the share
 * sheet gets, so the same file is both saved and shareable.
 */
object Exports {

    private const val FOLDER = "Pacenote"

    fun writeCsv(context: Context, name: String, content: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused the insert")
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            ?: throw IllegalStateException("no output stream for $uri")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
