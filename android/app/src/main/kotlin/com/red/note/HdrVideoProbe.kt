package com.red.note

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Metadata only: bounded random access avoids downloading an original movie. */
internal object HdrVideoProbe {
    fun probe(url: String, referer: String): Boolean? {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null &&
            (uri.host == "xhscdn.com" || uri.host?.endsWith(".xhscdn.com") == true))
        val source = RangeSource(url, referer)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                return when (transfer) {
                    MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG -> true
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_TRANSFER_LINEAR -> false
                    else -> null
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
            source.close()
        }
    }

    private class RangeSource(private val url: String, private val referer: String) : MediaDataSource() {
        private val deadline = SystemClock.elapsedRealtime() + 8000
        private val chunks = HashMap<Long, ByteArray>()
        private var total = -1L
        private var transferred = 0
        @Volatile private var closed = false

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (closed || position < 0 || (total >= 0 && position >= total)) return -1
            val start = position / 65536 * 65536
            val bytes = chunks[start] ?: fetch(start).also { chunks[start] = it }
            val relative = (position - start).toInt()
            if (relative >= bytes.size) return -1
            val count = minOf(size, bytes.size - relative)
            bytes.copyInto(buffer, offset, relative, relative + count)
            return count
        }

        private fun fetch(start: Long): ByteArray {
            if (closed || transferred >= 1024 * 1024 || chunks.size >= 16 ||
                SystemClock.elapsedRealtime() >= deadline) throw IOException("Probe budget exceeded")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Range", "bytes=$start-${start + 65535}")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (referer == "https://www.xiaohongshu.com/" || referer == "https://www.rednote.com/") {
                    connection.setRequestProperty("Referer", referer)
                }
                val status = connection.responseCode
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                    .matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                if (status == 206 && range != null && range.groupValues[1].toLong() == start) {
                    total = range.groupValues[3].toLong()
                } else if (status == 200 && start == 0L) {
                    total = connection.contentLengthLong
                } else throw IOException("Range unavailable")
                if (total <= 0) throw IOException("Unknown length")
                val limit = minOf(65536L, total - start).toInt()
                return connection.inputStream.use { input ->
                    val bytes = ByteArray(limit)
                    var count = 0
                    while (count < limit) {
                        if (closed || SystemClock.elapsedRealtime() >= deadline) throw IOException("Probe timeout")
                        val n = input.read(bytes, count, limit - count)
                        if (n < 0) break
                        count += n
                    }
                    transferred += count
                    bytes.copyOf(count)
                }
            } finally {
                connection.disconnect()
            }
        }

        override fun getSize(): Long {
            if (total < 0) readAt(0, ByteArray(1), 0, 1)
            return total
        }

        override fun close() { closed = true }
    }
}
