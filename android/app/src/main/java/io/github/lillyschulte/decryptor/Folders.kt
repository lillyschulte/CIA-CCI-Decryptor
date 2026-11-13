package io.github.lillyschulte.decryptor

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** Converts between folders picked in the system file picker, real paths and display names. */
object Folders {
    /** Converts a folder picked with ACTION_OPEN_DOCUMENT_TREE to a real filesystem path. */
    fun treeUriToPath(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
        if (uri.authority != "com.android.externalstorage.documents") return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val root = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().path
            volume.equals("home", ignoreCase = true) ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path
            else -> "/storage/$volume" // SD card / USB drive
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /** "/storage/emulated/0/Roms" -> "Internal storage/Roms", "/storage/9C33-6BBD/Roms" -> "SD card/Roms" */
    fun display(context: Context, folder: File): String {
        val internal = Environment.getExternalStorageDirectory().path
        val path = folder.path
        return when {
            path == internal || path.startsWith("$internal/") ->
                context.getString(R.string.internal_storage) + path.removePrefix(internal)
            path.startsWith("/storage/") ->
                context.getString(R.string.sd_card) + "/" + path.removePrefix("/storage/").substringAfter('/', "")
            else -> path
        }.removeSuffix("/")
    }
}
