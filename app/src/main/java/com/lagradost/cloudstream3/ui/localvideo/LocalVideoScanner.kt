package com.lagradost.cloudstream3.ui.localvideo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import java.security.MessageDigest

/**
 * Scanner video lokal MAcloudPlayer.
 *
 * Hanya membaca video dari:
 *   /storage/emulated/0/Download/
 *
 * Tidak memindahkan, mengubah, atau menghapus file.
 *
 * MediaStore digunakan supaya kompatibel dengan scoped storage
 * pada Android modern.
 */
object LocalVideoScanner {

    private val VIDEO_EXTENSIONS = setOf(
        "mp4",
        "mkv",
        "webm",
        "avi",
        "mov",
        "m4v",
        "3gp",
        "ts",
        "m2ts",
        "mts",
        "flv",
        "wmv",
        "asf",
        "ogv"
    )

    fun scan(context: Context): List<ExtractorUri> {
        val result = mutableListOf<ExtractorUri>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scanMediaStore(context, result)
        } else {
            scanLegacy(context, result)
        }

        return result
            .distinctBy { it.uri.toString() }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Android 10+.
     *
     * MediaStore.Files dipakai karena beberapa format video
     * seperti MKV/TS tidak selalu muncul melalui MediaStore.Video.
     */
    private fun scanMediaStore(
        context: Context,
        result: MutableList<ExtractorUri>
    ) {
        val collection = MediaStore.Files.getContentUri(
            MediaStore.VOLUME_EXTERNAL
        )

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )

        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"

        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            "Download/%"
        )

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->

                val idIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)

                val nameIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)

                val mimeIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                val pathIndex =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = cursor.getString(mimeIndex)
                    val relativePath = cursor.getString(pathIndex)

                    if (!isVideoFile(name, mime)) continue

                    val uri = ContentUris.withAppendedId(
                        collection,
                        id
                    )

                    result += ExtractorUri(
                        uri = uri,
                        name = name,
                        id = stableId(uri.toString()),
                        relativePath = relativePath,
                        displayName = name
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission belum tersedia.
            // Fragment akan menampilkan keadaan kosong.
        } catch (_: Exception) {
            // Jangan biarkan satu query gagal merusak halaman.
        }
    }

    /**
     * Android lama sebelum scoped storage.
     */
    @Suppress("DEPRECATION")
    private fun scanLegacy(
        context: Context,
        result: MutableList<ExtractorUri>
    ) {
        val download =
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )

        scanLegacyDirectory(context, download, result)
    }

    @Suppress("DEPRECATION")
    private fun scanLegacyDirectory(
        context: Context,
        directory: java.io.File,
        result: MutableList<ExtractorUri>
    ) {
        val files = directory.listFiles() ?: return

        for (file in files) {
            try {
                if (file.isDirectory) {
                    scanLegacyDirectory(context, file, result)
                    continue
                }

                if (!file.isFile) continue
                if (!isVideoFile(file.name, null)) continue

                val uri = Uri.fromFile(file)

                result += ExtractorUri(
                    uri = uri,
                    name = file.name,
                    id = stableId(uri.toString()),
                    displayName = file.name
                )
            } catch (_: Exception) {
                // File mungkin hilang ketika scanner berjalan.
            }
        }
    }

    private fun isVideoFile(
        name: String,
        mimeType: String?
    ): Boolean {
        if (mimeType?.startsWith("video/") == true) {
            return true
        }

        val extension = name
            .substringAfterLast('.', "")
            .lowercase()

        return extension in VIDEO_EXTENSIONS
    }

    private fun stableId(value: String): Int {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))

        return (digest[0].toInt() and 0xff) or
            ((digest[1].toInt() and 0xff) shl 8) or
            ((digest[2].toInt() and 0xff) shl 16) or
            ((digest[3].toInt() and 0xff) shl 24)
    }
}
