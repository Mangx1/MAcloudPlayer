package com.lagradost.cloudstream3.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Metadata dan subtitle persisten milik MAcloudPlayer.
 *
 * Penyimpanan user-visible:
 *
 * Download/
 *   MAcloudPlayer/
 *     <video>_<hash>/
 *       metadata.json
 *       subtitle.srt / subtitle.vtt / ...
 */
object MAcloudMetadata {

    private const val ROOT = "MAcloudPlayer"
    private const val METADATA = "metadata.json"
    private const val SUBTITLE_BASE = "subtitle"
    private const val THROTTLE_MS = 3000L

    private val executor = Executors.newSingleThreadExecutor()

    private val lastWrite =
        ConcurrentHashMap<String, Long>()

    private val uriCache =
        ConcurrentHashMap<String, Uri>()

    data class Restored(
        val subtitle: SubtitleData?,
        val subtitleOffsetMs: Long,
        val position: Long,
        val duration: Long,
    )

    private fun safePart(value: String): String {
        val cleaned = value
            .replace(
                Regex("""[\\/:*?"<>|]"""),
                "_"
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()

        return cleaned
            .take(80)
            .ifBlank { "Video" }
    }

    private fun folderName(
        key: String,
        title: String
    ): String {
        val hash =
            key.hashCode()
                .toUInt()
                .toString(16)

        return "${safePart(title)}_$hash"
    }

    private fun relativeFolder(
        key: String,
        title: String
    ): String {
        return "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/${folderName(key, title)}/"
    }

    private fun findUri(
        context: Context,
        relativePath: String,
        name: String
    ): Uri? {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        val cacheKey = "$relativePath|$name"

        uriCache[cacheKey]?.let {
            return it
        }

        val projection =
            arrayOf(MediaStore.Downloads._ID)

        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(relativePath, name),
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {

                val id =
                    cursor.getLong(0)

                val uri =
                    Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                uriCache[cacheKey] = uri

                return uri
            }
        }

        return null
    }

    private fun openOrCreate(
        context: Context,
        relativePath: String,
        name: String,
        mimeType: String
    ): Uri? {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        findUri(
            context,
            relativePath,
            name
        )?.let {
            return it
        }

        val values =
            ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    name
                )

                put(
                    MediaStore.Downloads.MIME_TYPE,
                    mimeType
                )

                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    relativePath
                )

                put(
                    MediaStore.Downloads.IS_PENDING,
                    0
                )
            }

        val uri =
            context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: return null

        uriCache[
            "$relativePath|$name"
        ] = uri

        return uri
    }

    private fun writeBytes(
        context: Context,
        relativePath: String,
        name: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri? {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val uri =
                openOrCreate(
                    context,
                    relativePath,
                    name,
                    mimeType
                ) ?: return null

            return try {

                context.contentResolver
                    .openOutputStream(uri, "wt")
                    ?.use {
                        it.write(bytes)
                    }

                uri

            } catch (_: Exception) {

                uriCache.remove(
                    "$relativePath|$name"
                )

                null
            }
        }

        return try {

            val root =
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )

            val folder =
                File(
                    root,
                    relativePath
                        .removePrefix(
                            "${Environment.DIRECTORY_DOWNLOADS}/"
                        )
                        .removeSuffix("/")
                )

            folder.mkdirs()

            val file =
                File(folder, name)

            file.outputStream().use {
                it.write(bytes)
            }

            Uri.fromFile(file)

        } catch (_: Exception) {
            null
        }
    }

    private fun readBytes(
        context: Context,
        relativePath: String,
        name: String
    ): ByteArray? {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val uri =
                findUri(
                    context,
                    relativePath,
                    name
                ) ?: return null

            return runCatching {
                context.contentResolver
                    .openInputStream(uri)
                    ?.use { it.readBytes() }
            }.getOrNull()
        }

        return runCatching {

            val root =
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )

            val file =
                File(
                    root,
                    "${relativePath.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/")}$name"
                )

            if (file.exists()) {
                file.readBytes()
            } else {
                null
            }

        }.getOrNull()
    }

    private fun readMetadata(
        context: Context,
        key: String,
        title: String
    ): JSONObject {

        val bytes =
            readBytes(
                context,
                relativeFolder(key, title),
                METADATA
            )

        return bytes
            ?.toString(Charsets.UTF_8)
            ?.let {
                runCatching {
                    JSONObject(it)
                }.getOrNull()
            }
            ?: JSONObject()
    }

    private fun saveMetadata(
        context: Context,
        key: String,
        title: String,
        json: JSONObject
    ) {

        writeBytes(
            context,
            relativeFolder(key, title),
            METADATA,
            "application/json",
            json
                .toString(2)
                .toByteArray(Charsets.UTF_8)
        )
    }

    private fun shouldWrite(
        key: String,
        force: Boolean
    ): Boolean {

        val now =
            System.currentTimeMillis()

        if (
            !force &&
            now - (lastWrite[key] ?: 0L) <
            THROTTLE_MS
        ) {
            return false
        }

        lastWrite[key] = now

        return true
    }

    fun savePlayback(
        context: Context,
        key: String,
        title: String,
        position: Long,
        duration: Long,
        subtitle: SubtitleData?,
        subtitleOffsetMs: Long,
        force: Boolean = false
    ) {

        if (!shouldWrite(key, force)) {
            return
        }

        executor.execute {

            runCatching {

                val json =
                    readMetadata(
                        context,
                        key,
                        title
                    ).apply {

                        put(
                            "schemaVersion",
                            1
                        )

                        put(
                            "videoKey",
                            key
                        )

                        put(
                            "title",
                            title
                        )

                        put(
                            "updatedAt",
                            System.currentTimeMillis()
                        )

                        put(
                            "positionMs",
                            position.coerceAtLeast(0L)
                        )

                        put(
                            "durationMs",
                            duration.coerceAtLeast(0L)
                        )

                        put(
                            "subtitleOffsetMs",
                            subtitleOffsetMs
                        )

                        subtitle?.let {

                            put(
                                "subtitleName",
                                it.name
                            )

                            put(
                                "subtitleLanguage",
                                it.getIETF_tag() ?: ""
                            )

                            put(
                                "subtitleFile",
                                SUBTITLE_BASE +
                                    extensionFor(
                                        it.name,
                                        it.url
                                    )
                            )
                        }
                    }

                saveMetadata(
                    context,
                    key,
                    title,
                    json
                )
            }
        }
    }

    fun savePlaybackNow(
        context: Context,
        key: String,
        title: String,
        position: Long,
        duration: Long,
        subtitle: SubtitleData?,
        subtitleOffsetMs: Long
    ) {

        shouldWrite(
            key,
            true
        )

        executor.execute {

            runCatching {

                val json =
                    readMetadata(
                        context,
                        key,
                        title
                    ).apply {

                        put(
                            "schemaVersion",
                            1
                        )

                        put(
                            "videoKey",
                            key
                        )

                        put(
                            "title",
                            title
                        )

                        put(
                            "updatedAt",
                            System.currentTimeMillis()
                        )

                        put(
                            "positionMs",
                            position.coerceAtLeast(0L)
                        )

                        put(
                            "durationMs",
                            duration.coerceAtLeast(0L)
                        )

                        put(
                            "subtitleOffsetMs",
                            subtitleOffsetMs
                        )

                        subtitle?.let {

                            put(
                                "subtitleName",
                                it.name
                            )

                            put(
                                "subtitleLanguage",
                                it.getIETF_tag() ?: ""
                            )

                            put(
                                "subtitleFile",
                                SUBTITLE_BASE +
                                    extensionFor(
                                        it.name,
                                        it.url
                                    )
                            )
                        }
                    }

                saveMetadata(
                    context,
                    key,
                    title,
                    json
                )
            }
        }
    }

    fun saveSubtitle(
        context: Context,
        key: String,
        title: String,
        subtitle: SubtitleData
    ) {

        executor.execute {

            runCatching {

                val extension =
                    extensionFor(
                        subtitle.name,
                        subtitle.url
                    )

                val fileName =
                    SUBTITLE_BASE + extension

                val bytes =
                    readSubtitleBytes(
                        context,
                        subtitle.url,
                        subtitle.headers
                    ) ?: return@runCatching

                writeBytes(
                    context,
                    relativeFolder(key, title),
                    fileName,
                    mimeForExtension(extension),
                    bytes
                )

                val json =
                    readMetadata(
                        context,
                        key,
                        title
                    ).apply {

                        put(
                            "schemaVersion",
                            1
                        )

                        put(
                            "videoKey",
                            key
                        )

                        put(
                            "title",
                            title
                        )

                        put(
                            "updatedAt",
                            System.currentTimeMillis()
                        )

                        put(
                            "subtitleName",
                            subtitle.name
                        )

                        put(
                            "subtitleLanguage",
                            subtitle.getIETF_tag() ?: ""
                        )

                        put(
                            "subtitleFile",
                            fileName
                        )
                    }

                saveMetadata(
                    context,
                    key,
                    title,
                    json
                )
            }
        }
    }

    fun restoreForPlayback(
        context: Context,
        key: String,
        title: String,
        videoId: Int?
    ): Restored? {

        val json =
            runCatching {

                val bytes =
                    readBytes(
                        context,
                        relativeFolder(key, title),
                        METADATA
                    ) ?: return null

                JSONObject(
                    String(
                        bytes,
                        Charsets.UTF_8
                    )
                )

            }.getOrNull()
                ?: return null

        val position =
            json.optLong(
                "positionMs",
                0L
            )

        val duration =
            json.optLong(
                "durationMs",
                0L
            )

        if (
            videoId != null &&
            position > 0L &&
            duration > 0L
        ) {

            DataStoreHelper.setViewPos(
                videoId,
                position,
                duration
            )
        }

        val fileName =
            json.optString(
                "subtitleFile",
                ""
            )

        val subtitleUri =
            if (fileName.isNotBlank()) {

                findUri(
                    context,
                    relativeFolder(key, title),
                    fileName
                )

            } else {
                null
            }

        val subtitle =
            subtitleUri?.let { uri ->

                val name =
                    json.optString(
                        "subtitleName",
                        fileName
                    )

                val language =
                    json.optString(
                        "subtitleLanguage",
                        ""
                    )

                SubtitleData(
                    originalName = name,
                    nameSuffix = "",
                    url = uri.toString(),
                    origin = SubtitleOrigin.DOWNLOADED_FILE,
                    mimeType = mimeForExtension(
                        extensionFor(
                            name,
                            fileName
                        )
                    ),
                    headers = emptyMap(),
                    languageCode =
                        language.ifBlank {
                            null
                        }
                )
            }

        return Restored(
            subtitle = subtitle,
            subtitleOffsetMs =
                json.optLong(
                    "subtitleOffsetMs",
                    0L
                ),
            position = position,
            duration = duration
        )
    }

    private fun extensionFor(
        name: String?,
        url: String?
    ): String {

        val candidate =
            name
                .orEmpty()
                .ifBlank {
                    url.orEmpty()
                }

        val clean =
            candidate
                .substringBefore("?")
                .substringBefore("#")

        return when (
            clean
                .substringAfterLast(
                    '.',
                    ""
                )
                .lowercase()
        ) {

            "srt" -> ".srt"
            "vtt" -> ".vtt"
            "ass" -> ".ass"
            "ssa" -> ".ssa"
            "ttml",
            "xml" -> ".ttml"

            else -> ".srt"
        }
    }

    private fun mimeForExtension(
        extension: String
    ): String {

        return when (extension) {

            ".vtt" ->
                "text/vtt"

            ".ass",
            ".ssa" ->
                "text/x-ssa"

            ".ttml" ->
                "application/ttml+xml"

            else ->
                "application/x-subrip"
        }
    }

    private fun readSubtitleBytes(
        context: Context,
        source: String,
        headers: Map<String, String>
    ): ByteArray? {

        val uri =
            runCatching {
                Uri.parse(source)
            }.getOrNull()
                ?: return null

        return when (
            uri.scheme?.lowercase()
        ) {

            "content" ->
                runCatching {
                    context.contentResolver
                        .openInputStream(uri)
                        ?.use {
                            it.readBytes()
                        }
                }.getOrNull()

            "file" ->
                runCatching {
                    File(
                        uri.path
                            ?: return null
                    ).readBytes()
                }.getOrNull()

            "http",
            "https" ->
                runCatching {

                    val connection =
                        URL(source)
                            .openConnection()
                            as HttpURLConnection

                    connection.connectTimeout =
                        15_000

                    connection.readTimeout =
                        30_000

                    connection.instanceFollowRedirects =
                        true

                    headers.forEach {
                        (name, value) ->

                        if (
                            name.isNotBlank() &&
                            value.isNotBlank()
                        ) {
                            connection.setRequestProperty(
                                name,
                                value
                            )
                        }
                    }

                    connection.connect()

                    if (
                        connection.responseCode !in
                        200..299
                    ) {
                        connection.disconnect()
                        return null
                    }

                    connection.inputStream
                        .use {
                            it.readBytes()
                        }
                        .also {
                            connection.disconnect()
                        }

                }.getOrNull()

            else -> null
        }
    }
}
