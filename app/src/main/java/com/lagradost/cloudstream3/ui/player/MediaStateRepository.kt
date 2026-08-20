package com.lagradost.cloudstream3.ui.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Persistent player state stored in the user-visible Download/MAcloudPlayer folder.
 *
 * Metadata is keyed by media identity, never by the current streaming URL.
 * Local and online external subtitles are copied into Download/MAcloudPlayer/subtitles/.
 */
object MediaStateRepository {
    const val METADATA_RELATIVE_PATH = "Download/MAcloudPlayer/metadata/"
    const val SUBTITLE_RELATIVE_PATH = "Download/MAcloudPlayer/subtitles/"
    private const val VERSION = 1

    data class MediaState(
        val key: String,
        val title: String?,
        val positionMs: Long,
        val durationMs: Long,
        val subtitleType: String,
        val subtitleFileUri: String?,
        val subtitleLanguage: String?,
        val subtitleName: String?,
        val subtitleMimeType: String?,
        val subtitleSource: String?,
        val subtitleDelayMs: Long,
    )

    data class StoredSubtitle(
        val uri: String,
        val mimeType: String,
        val name: String,
        val language: String?,
    )

    fun buildKey(
        stateId: Int?,
        title: String?,
        year: Int?,
        imdbId: String?,
        tmdbId: String?,
        malId: String?,
        aniListId: String?,
        season: Int?,
        episode: Int?,
    ): String {
        val normalizedTitle = title.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

        return when {
            !tmdbId.isNullOrBlank() -> "tmdb:$tmdbId:${season ?: 0}:${episode ?: 0}"
            !imdbId.isNullOrBlank() -> "imdb:$imdbId:${season ?: 0}:${episode ?: 0}"
            !malId.isNullOrBlank() -> "mal:$malId:${season ?: 0}:${episode ?: 0}"
            !aniListId.isNullOrBlank() -> "anilist:$aniListId:${season ?: 0}:${episode ?: 0}"
            stateId != null -> "csid:$stateId:$normalizedTitle:${year ?: 0}:${season ?: 0}:${episode ?: 0}"
            else -> "title:$normalizedTitle:${year ?: 0}:${season ?: 0}:${episode ?: 0}"
        }
    }

    fun load(context: Context, key: String): MediaState? {
        return try {
            val text = readFile(context, metadataName(key), METADATA_RELATIVE_PATH) ?: return null
            val json = JSONObject(text)

            MediaState(
                key = json.optString("key", key),
                title = json.optString("title", null),
                positionMs = json.optLong("positionMs", 0L),
                durationMs = json.optLong("durationMs", 0L),
                subtitleType = json.optString("subtitleType", "none"),
                subtitleFileUri = json.optString("subtitleFileUri", null),
                subtitleLanguage = json.optString("subtitleLanguage", null),
                subtitleName = json.optString("subtitleName", null),
                subtitleMimeType = json.optString("subtitleMimeType", null),
                subtitleSource = json.optString("subtitleSource", null),
                subtitleDelayMs = json.optLong("subtitleDelayMs", 0L),
            )
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun save(context: Context, state: MediaState) {
        val json = JSONObject().apply {
            put("version", VERSION)
            put("key", state.key)
            put("title", state.title ?: JSONObject.NULL)
            put("positionMs", state.positionMs)
            put("durationMs", state.durationMs)
            put("subtitleType", state.subtitleType)
            put("subtitleFileUri", state.subtitleFileUri ?: JSONObject.NULL)
            put("subtitleLanguage", state.subtitleLanguage ?: JSONObject.NULL)
            put("subtitleName", state.subtitleName ?: JSONObject.NULL)
            put("subtitleMimeType", state.subtitleMimeType ?: JSONObject.NULL)
            put("subtitleSource", state.subtitleSource ?: JSONObject.NULL)
            put("subtitleDelayMs", state.subtitleDelayMs)
        }

        writeFile(
            context,
            metadataName(state.key),
            METADATA_RELATIVE_PATH,
            "application/json",
            json.toString(2).toByteArray()
        )
    }

    fun restoreExternalSubtitle(context: Context, state: MediaState): SubtitleData? {
        if (state.subtitleType != "external") return null

        val uriString = state.subtitleFileUri ?: return null
        val uri = Uri.parse(uriString)

        return try {
            context.contentResolver.openInputStream(uri)?.use { } ?: return null

            SubtitleData(
                originalName = state.subtitleName ?: "Saved subtitle",
                nameSuffix = "",
                url = uri.toString(),
                origin = SubtitleOrigin.DOWNLOADED_FILE,
                mimeType = state.subtitleMimeType ?: "application/x-subrip",
                headers = emptyMap(),
                languageCode = state.subtitleLanguage,
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun storeSubtitle(
        context: Context,
        subtitle: SubtitleData,
        mediaKey: String,
    ): StoredSubtitle? {
        return try {
            val bytes = readSubtitleBytes(context, subtitle)
            val name = subtitleFileName(subtitle, mediaKey)
            val mime = subtitle.mimeType.ifBlank { "application/x-subrip" }

            val uri = writeFile(
                context,
                name,
                SUBTITLE_RELATIVE_PATH,
                mime,
                bytes,
            ) ?: return null

            StoredSubtitle(
                uri = uri.toString(),
                mimeType = mime,
                name = subtitle.originalName,
                language = subtitle.languageCode ?: subtitle.getIETF_tag(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun metadataName(key: String): String =
        "media_${shortHash(key)}.json"

    private fun subtitleFileName(
        subtitle: SubtitleData,
        mediaKey: String,
    ): String {
        val raw = subtitle.originalName.ifBlank { "subtitle" }

        val safe = raw.substringBeforeLast('.', raw)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .take(70)
            .ifBlank { "subtitle" }

        val extension = raw.substringAfterLast('.', "srt")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
            ?: "srt"

        return "${safe}_${shortHash(mediaKey + subtitle.url)}.$extension"
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())

        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun readSubtitleBytes(
        context: Context,
        subtitle: SubtitleData,
    ): ByteArray {
        val source = subtitle.url

        if (source.startsWith("http://") || source.startsWith("https://")) {
            val connection = URL(source).openConnection() as HttpURLConnection

            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true

                subtitle.headers.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }

                if (connection.responseCode !in 200..299) {
                    throw IOException(
                        "Subtitle download failed: HTTP ${connection.responseCode}"
                    )
                }

                return connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

        return context.contentResolver
            .openInputStream(Uri.parse(source))
            ?.use { it.readBytes() }
            ?: throw IOException("Unable to read subtitle $source")
    }

    private fun collection(context: Context): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
        } else {
            MediaStore.Files.getContentUri("external")
        }
    }

    private fun findUri(
        context: Context,
        displayName: String,
        relativePath: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val base = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )

            val file = File(
                base,
                relativePath.removePrefix("Download/") + displayName
            )

            return if (file.exists()) Uri.fromFile(file) else null
        }

        val resolver = context.contentResolver

        resolver.query(
            collection(context),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(displayName, relativePath),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(
                    collection(context),
                    cursor.getLong(0).toString()
                )
            }
        }

        return null
    }

    private fun readFile(
        context: Context,
        displayName: String,
        relativePath: String,
    ): String? {
        val uri = findUri(context, displayName, relativePath)
            ?: return null

        return try {
            context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader()
                .use { it?.readText() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeFile(
        context: Context,
        displayName: String,
        relativePath: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val base = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )

            val file = File(
                base,
                relativePath.removePrefix("Download/") + displayName
            )

            file.parentFile?.mkdirs()
            file.outputStream().use { it.write(bytes) }

            return Uri.fromFile(file)
        }

        val resolver = context.contentResolver

        findUri(context, displayName, relativePath)?.let {
            resolver.delete(it, null, null)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(
            collection(context),
            values
        ) ?: return null

        try {
            resolver.openOutputStream(uri, "w")?.use {
                it.write(bytes)
            } ?: throw IOException("Unable to open output stream")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
