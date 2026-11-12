package com.red.note

import java.net.URI
import org.json.JSONObject

internal fun JSONObject.rows(key: String): List<JSONObject> =
    optJSONArray(key)
        ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        .orEmpty()

internal fun mediaUrl(value: String): String {
    val text = if (value.startsWith("//")) "https:$value" else value
    val uri = runCatching { URI(text) }.getOrNull() ?: return ""
    val host = uri.host?.lowercase() ?: return ""
    if (uri.userInfo != null) return ""
    if (uri.scheme == "https") return text
    if (
        uri.scheme == "http" &&
            (host == "xhscdn.com" ||
                host.endsWith(".xhscdn.com") ||
                host == "xiaohongshu.com" ||
                host.endsWith(".xiaohongshu.com"))
    )
        return "https" + text.substring(4)
    return ""
}

internal fun imageUrl(image: JSONObject): String {
    val infos = image.rows("info_list")
    for (scene in listOf("WB_DFT", "FD_WM_WEBP", "FD_PRV_WEBP")) {
        infos
            .firstOrNull { it.optString("image_scene") == scene }
            ?.let {
                mediaUrl(it.optString("url")).takeIf(String::isNotEmpty)?.let { url ->
                    return url
                }
            }
    }
    for (key in
        listOf(
            "url_default",
            "url_pre",
            "url",
            "url_size_large",
            "url_size_original",
            "origin_url",
        )) {
        mediaUrl(image.optString(key)).takeIf(String::isNotEmpty)?.let {
            return it
        }
    }
    return infos
        .firstNotNullOfOrNull { mediaUrl(it.optString("url")).takeIf(String::isNotEmpty) }
        .orEmpty()
}

internal fun originalImageUrl(image: JSONObject): String {
    for (key in listOf("url_size_original", "origin_url", "url_size_large")) mediaUrl(
            image.optString(key)
        )
        .takeIf(String::isNotEmpty)
        ?.let {
            return it
        }
    val preview = imageUrl(image)
    val uri = runCatching { URI(preview) }.getOrNull() ?: return preview
    val host = uri.host.orEmpty()
    val segments = uri.path.orEmpty().trimStart('/').split('/')
    if (
        host.startsWith("sns-webpic-") &&
            host.endsWith(".xhscdn.com") &&
            segments.size >= 3 &&
            segments[0].matches(Regex("\\d{12}(?:\\d{2})?")) &&
            segments[1].matches(Regex("[a-fA-F0-9]{32}"))
    ) {
        val key = segments.drop(2).joinToString("/").substringBefore('!')
        if (key.length <= 512 && key.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")))
            return "https://sns-img-bd.xhscdn.com/$key"
    }
    return preview
}

internal data class VideoStream(
    val url: String,
    val audio: String = "",
    val hdr: Boolean = false,
    val original: Boolean = false,
)

internal fun videoStreams(video: JSONObject): List<VideoStream> {
    val streams = video.optJSONObject("media")?.optJSONObject("stream") ?: JSONObject()
    val raw = video.opt("media_v2") ?: video.opt("mediaV2")
    val v2 =
        when (raw) {
            is JSONObject -> raw
            is String ->
                if (raw.length < 1024 * 1024) runCatching { JSONObject(raw) }.getOrNull() else null
            else -> null
        }
    val audio = v2?.optJSONObject("audio_stream") ?: v2?.optJSONObject("audioStream") ?: JSONObject()
    fun urls(item: JSONObject) =
        buildList {
                add(item.optString("master_url", item.optString("masterUrl")))
                val a = item.optJSONArray("backup_urls") ?: item.optJSONArray("backupUrls")
                if (a != null) for (i in 0 until a.length()) add(a.optString(i))
            }
            .map(::mediaUrl)
            .filter(String::isNotEmpty)
    val audioUrls =
        audio
            .keys()
            .asSequence()
            .flatMap { audio.rows(it) }
            .sortedByDescending { it.optInt("default_stream", it.optInt("defaultStream")) == 1 }
            .flatMap(::urls)
            .distinct()
            .take(2)
            .toList()
    return buildList {
            (listOf("h264", "h265", "av1") + streams.keys().asSequence().toList())
                .distinct()
                .forEach { codec ->
                    streams.rows(codec).forEach { entry ->
                        val hasAudio =
                            entry.optString("audio_codec", entry.optString("audioCodec")).isNotEmpty() ||
                                entry.optInt("audio_bitrate", entry.optInt("audioBitrate")) > 0
                        val candidates =
                            if (hasAudio || audioUrls.isEmpty()) listOf("") else audioUrls
                        urls(entry).forEach { url ->
                            candidates.forEach {
                                add(
                                    VideoStream(
                                        url,
                                        it,
                                        entry.optInt("hdr_type", entry.optInt("hdrType")) > 0,
                                    )
                                )
                            }
                        }
                    }
                }
            mediaUrl(video.optString("url")).takeIf(String::isNotEmpty)?.let {
                add(VideoStream(it))
            }
            val consumer = video.optJSONObject("consumer")
            val key =
                consumer
                    ?.optString("origin_video_key", consumer.optString("originVideoKey"))
                    .orEmpty()
            if (
                key.isNotEmpty() &&
                    key.length <= 512 &&
                    key.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)*")) &&
                    key.split('/').none { it == "." || it == ".." }
            )
                add(VideoStream("https://sns-video-bd.xhscdn.com/$key", original = true))
        }
        .distinctBy { "${it.url}|${it.audio}" }
}

internal data class Note(
    val id: String,
    val token: String,
    val source: String,
    val raw: JSONObject,
) {
    val args
        get() = mapOf("id" to id, "token" to token, "source" to source)

    val title
        get() = raw.optString("display_title", raw.optString("title"))

    val user
        get() = raw.optJSONObject("user") ?: JSONObject()

    val userId
        get() = user.optString("user_id", user.optString("userid"))

    val author
        get() = user.optString("nickname", user.optString("nick_name"))

    val images
        get() = raw.rows("image_list")

    val cover
        get() =
            imageUrl(raw.optJSONObject("cover") ?: JSONObject())
                .ifEmpty { mediaUrl(raw.optString("cover")) }
                .ifEmpty { images.firstOrNull()?.let(::imageUrl).orEmpty() }

    companion object {
        fun parse(item: JSONObject, source: String): Note? {
            val raw =
                item.optJSONObject("note_card")?.let {
                    JSONObject(it.toString()).apply {
                        if (!has("view_count") && item.has("view_count"))
                            put("view_count", item.get("view_count"))
                    }
                } ?: item
            val id =
                item.optString(
                    "id",
                    item.optString("note_id", raw.optString("note_id", raw.optString("id"))),
                )
            if (
                id.isBlank() ||
                    (!item.has("note_card") &&
                        !raw.has("title") &&
                        !raw.has("display_title") &&
                        !raw.has("cover"))
            )
                return null
            return Note(
                id,
                item.optString("xsec_token", raw.optString("xsec_token")),
                item.optString("xsec_source").ifEmpty { source },
                raw,
            )
        }
    }
}

internal fun userImageUrl(user: JSONObject): String {
    fun find(value: Any?, depth: Int = 0): String {
        if (depth > 4) return ""
        return when (value) {
            is String -> mediaUrl(value)
            is JSONObject -> imageUrl(value)
            is org.json.JSONArray ->
                (0 until value.length())
                    .firstNotNullOfOrNull {
                        find(value.opt(it), depth + 1).takeIf(String::isNotEmpty)
                    }
                    .orEmpty()
            else -> ""
        }
    }
    return listOf("imageb", "images", "avatar", "image")
        .firstNotNullOfOrNull { find(user.opt(it)).takeIf(String::isNotEmpty) }
        .orEmpty()
}

internal fun profileBackgroundUrl(profile: JSONObject): String {
    val basic =
        profile.optJSONObject("basic_info") ?: profile.optJSONObject("basicInfo") ?: JSONObject()
    val user = profile.optJSONObject("user") ?: JSONObject()
    return listOf(
            basic.opt("background_image"),
            user.opt("background_image"),
            user.opt("backgroundImage"),
            basic.opt("backgroundImage"),
            profile.opt("background_image"),
            profile.opt("backgroundImage"),
        )
        .firstNotNullOfOrNull {
            userImageUrl(JSONObject().put("images", it)).takeIf(String::isNotEmpty)
        }
        .orEmpty()
}

internal fun notificationTime(value: Any?, now: Long = System.currentTimeMillis()): String {
    val timestamp = value?.toString()?.toDoubleOrNull() ?: return ""
    if (!timestamp.isFinite() || timestamp <= 0) return ""
    val milliseconds = if (timestamp < 100000000000) timestamp * 1000 else timestamp
    if (milliseconds > 8640000000000000) return ""
    val age = now - milliseconds.toLong()
    if (age < 60000) return "刚刚"
    if (age < 3600000) return "${age / 60000}分钟前"
    if (age < 86400000) return "${age / 3600000}小时前"
    if (age < 604800000) return "${age / 86400000}天前"
    val date = java.util.Calendar.getInstance().apply { timeInMillis = milliseconds.toLong() }
    val current = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val md =
        "%02d-%02d"
            .format(
                date.get(java.util.Calendar.MONTH) + 1,
                date.get(java.util.Calendar.DAY_OF_MONTH),
            )
    return if (date.get(java.util.Calendar.YEAR) == current.get(java.util.Calendar.YEAR)) md
    else "${date.get(java.util.Calendar.YEAR)}-$md"
}

internal fun mergeJson(base: JSONObject, patch: JSONObject) =
    JSONObject(base.toString()).apply { patch.keys().forEach { put(it, patch.get(it)) } }

internal fun interactionPatch(before: JSONObject, method: String): JSONObject {
    require(method in listOf("likeNote", "collectNote"))
    val field = if (method == "likeNote") "liked" else "collected"
    val count = if (method == "likeNote") "liked_count" else "collected_count"
    val value = !before.optBoolean(field)
    val old =
        before.optString(count, if (field == "collected") before.optString("collect_count") else "")
    return JSONObject().put(field, value).apply {
        if (old.isEmpty() || old.toLongOrNull() != null)
            put(
                count,
                ((old.toLongOrNull() ?: 0) + (if (value) 1 else -1)).coerceIn(
                    0,
                    Int.MAX_VALUE.toLong(),
                ),
            )
    }
}

internal fun rollbackPatch(current: JSONObject, before: JSONObject, patch: JSONObject) =
    JSONObject(current.toString()).apply {
        patch.keys().forEach { key ->
            if (before.has(key)) put(key, before.get(key)) else remove(key)
        }
    }

internal fun mediaSubdirectory(type: Int, mine: String?, author: String?): String {
    if (type !in 1..2) return "RedNote"
    val fallback = if (type == 1) "MyProfile" else "Unknown"
    val value = if (type == 1) mine else author
    val cleaned =
        value
            .orEmpty()
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .replace(Regex("_+"), "_")
            .trim()
    val safe =
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback
        else cleaned.take(50).trim()
    return "RedNote/$safe"
}
