package com.red.note

import android.app.Activity
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.red.note.media.MediaImageFormat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal class MediaSaver(private val activity: Activity) {
    fun save(url: String, subDir: String, video: Boolean, audioUrl: String): String {
        require(MediaRemuxer.trustedUrl(url)) { "此媒体地址暂不支持保存" }
        require(audioUrl.isEmpty() || video) { "图片不能包含音轨" }
        val segments = subDir.split('/').filter { it.isNotBlank() }
        require(
            segments.isNotEmpty() &&
                segments.size <= 2 &&
                segments[0] == "RedNote" &&
                segments.all {
                    it != "." &&
                        it != ".." &&
                        it.length <= 50 &&
                        !it.contains(Regex("[\\\\:*?\"<>|\\r\\n]"))
                }
        ) {
            "保存路径无效"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = false
        mediaHeaders(activity, url, !video).forEach { (name, value) ->
            conn.setRequestProperty(name, value)
        }
        var pending: android.net.Uri? = null
        var legacy: File? = null
        var merged: File? = null
        var imageInput: java.io.BufferedInputStream? = null
        try {
            require(conn.responseCode in 200..299) { "媒体下载失败，请刷新笔记后重试" }
            val mime =
                if (video) conn.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                else {
                    imageInput = conn.inputStream.buffered()
                    val input = imageInput!!
                    input.mark(64)
                    val header = ByteArray(64)
                    var count = 0
                    while (count < header.size) {
                        val n = input.read(header, count, header.size - count)
                        if (n < 0) break
                        count += n
                    }
                    input.reset()
                    MediaImageFormat.mime(header, count).also {
                        require(it.isNotEmpty()) { "下载内容不是支持的图片，请刷新笔记后重试" }
                    }
                }
            val ext =
                when (mime) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "image/avif" -> "avif"
                    "image/heic" -> "heic"
                    "image/heif" -> "heif"
                    "video/mp4" -> "mp4"
                    "video/webm" -> "webm"
                    else -> throw IllegalArgumentException("此媒体格式暂不支持保存，可继续在线查看")
                }
            require(if (video) mime.startsWith("video/") else mime.startsWith("image/")) {
                "媒体格式与保存类型不一致"
            }
            val limit = if (video) 512L * 1024 * 1024 else 40L * 1024 * 1024
            require(conn.contentLengthLong <= limit) { "媒体文件过大" }
            if (audioUrl.isNotEmpty())
                merged = MediaRemuxer.merge(activity.cacheDir, conn, audioUrl)
            val name = "rn_${System.currentTimeMillis()}.${if(audioUrl.isNotEmpty())"mp4" else ext}"
            val base = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val relative = "$base/${segments.joinToString("/") }"
            val output =
                if (Build.VERSION.SDK_INT >= 29) {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(
                                MediaStore.MediaColumns.MIME_TYPE,
                                if (audioUrl.isNotEmpty()) "video/mp4" else mime,
                            )
                            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    val collection =
                        if (video)
                            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else
                            MediaStore.Images.Media.getContentUri(
                                MediaStore.VOLUME_EXTERNAL_PRIMARY
                            )
                    pending =
                        activity.contentResolver.insert(collection, values) ?: error("media insert")
                    activity.contentResolver.openOutputStream(pending!!) ?: error("media output")
                } else {
                    val root = Environment.getExternalStoragePublicDirectory(base).canonicalFile
                    val dir = File(root, segments.joinToString("/")).canonicalFile
                    require(dir.path.startsWith(root.path + File.separator)) { "保存路径无效" }
                    check(dir.isDirectory || dir.mkdirs())
                    legacy = File(dir, name)
                    legacy!!.outputStream()
                }
            output.use { target ->
                (merged?.inputStream() ?: imageInput ?: conn.inputStream).use { input ->
                    val bytes = ByteArray(65536)
                    var written = 0L
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        if (Thread.currentThread().isInterrupted) error("cancelled")
                        written += n
                        require(written <= limit) { "媒体文件过大" }
                        target.write(bytes, 0, n)
                    }
                    require(written > 0) { "媒体内容为空" }
                }
            }
            if (Build.VERSION.SDK_INT >= 29)
                activity.contentResolver.update(
                    pending!!,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            legacy?.let {
                MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(it.path),
                    arrayOf(if (audioUrl.isNotEmpty()) "video/mp4" else mime),
                    null,
                )
            }
            return relative
        } catch (e: Exception) {
            pending?.let { activity.contentResolver.delete(it, null, null) }
            legacy?.delete()
            throw e
        } finally {
            try {
                imageInput?.close()
            } finally {
                merged?.delete()
                conn.disconnect()
            }
        }
    }
}
