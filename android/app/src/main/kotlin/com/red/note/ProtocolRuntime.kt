package com.red.note

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.red.note.protocol.RapSigner
import com.red.note.protocol.WebSigner
import com.red.note.protocol.LoginCookieSnapshot
import com.red.note.protocol.VerificationPageUrl
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ApiFailure(val kind: String, override val message: String, val details: Map<String, Any?> = emptyMap()): Exception(message)

internal class ProtocolRuntime(context: Context) {
    private val appContext = context.applicationContext
    val worker = Executors.newSingleThreadExecutor()
    private val vault = SessionVault(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private var loaded = false
    @Volatile var active: JSONObject? = null
        private set
    @Volatile var guestSession: JSONObject? = null
        private set
    private var storageWarning: String? = null
    private var lastRequest: Map<String, Any?>? = null
    private var blocked: ApiFailure? = null
    private var blockedPath: String? = null
    @Volatile private var verificationPage = ""

    fun verificationPageUrl(): String = verificationPage

    fun load() {
        if (loaded) return
        loaded = true
        try {
            active = vault.load()
        } catch (_: Exception) {
            storageWarning = "保存的会话无法读取，请重新登录"
        }
        if (active == null) {
            guestSession = GuestSessionManager.load(appContext)
            if (guestSession == null) {
                GuestSessionManager.ensure(appContext) { s ->
                    guestSession = s
                }
            }
        }
    }

    fun status(): Map<String, Any?> {
        load()
        val isLoggedIn = active != null
        val state = active ?: guestSession
        return mapOf(
            "loggedIn" to isLoggedIn,
            "guestReady" to (state != null),
            "user" to active?.optJSONObject("user")?.toMap(),
            "region" to (state?.optString("region") ?: "xhs"),
            "mediaUserAgent" to state?.optString("ua"),
            "sdk" to state?.optJSONObject("context")?.optString("sdk"),
            "webBuild" to state?.optJSONObject("context")?.optString("webBuild"),
            "warning" to storageWarning,
            "blocked" to blocked?.kind,
            "lastRequest" to lastRequest,
            "systemFontWeight" to if (android.os.Build.VERSION.SDK_INT >= 31)
                appContext.resources.configuration.fontWeightAdjustment.takeIf { it != Int.MAX_VALUE } ?: 0 else 0
        )
    }

    fun accept(candidate: JSONObject, requireCurrentAccount: Boolean = false): Map<String, Any?> {
        load()
        val user = request(candidate, "/api/sns/web/v2/user/me", false, rap = false, commonMns = true, syncToBrowser = false)
        if (user.optBoolean("guest", true) || user.optString("user_id", user.optString("userid")).isBlank())
            throw ApiFailure("login", "网页登录尚未完成，请扫码后再确认")
        val previous = active?.optJSONObject("user")
        if (requireCurrentAccount && previous != null &&
            previous.optString("user_id", previous.optString("userid")) != user.optString("user_id", user.optString("userid")))
            throw ApiFailure("login", "验证页面账号与应用账号不一致；请使用原账号完成验证")
        val apiHost = if (candidate.optString("region") == "rednote") "webapi.rednote.com" else "edith.xiaohongshu.com"
        val verifiedCookies = candidate.optJSONObject("cookies")?.optString(apiHost).orEmpty()
        if (LoginCookieSnapshot.select(candidate.getJSONObject("context").getString("a1"), verifiedCookies).isEmpty())
            throw ApiFailure("login", "登录校验后未取得有效凭据；请在网页完成登录后重试")
        candidate.put("user", user)
        vault.save(candidate)
        active = candidate
        blocked = null
        blockedPath = null
        verificationPage = ""
        storageWarning = null
        return status()
    }

    fun logout() {
        vault.clear()
        active = null
        blocked = null
        blockedPath = null
        verificationPage = ""
        storageWarning = null
        lastRequest = null
        GuestSessionManager.ensure(appContext) { s -> guestSession = s }
    }

    fun invoke(method: String, args: Map<*, *>): Any? {
        load()
        if (method == "session") return status()
        if (method == "logout") { logout(); return null }

        val guestAllowed = method in setOf(
            "home", "homeCategories", "search", "detail", "comments", "subComments",
            "suggestions", "searchFilters", "ask"
        )
        if (!guestAllowed && active == null) {
            throw ApiFailure("login", "该操作需要登录小红书账号")
        }

        var session = active ?: guestSession
        if (session == null) {
            session = GuestSessionManager.load(appContext)
            if (session != null) {
                guestSession = session
            } else {
                GuestSessionManager.ensure(appContext) { s -> guestSession = s }
                throw ApiFailure("guest_initializing", "正在初始化小红书访问环境，请稍候再试…")
            }
        }

        val a = JSONObject(args)
        val formats = JSONArray(listOf("jpg", "webp", "avif"))
        fun required(key: String): String = a.optString(key).also { require(it.isNotBlank() && it.length <= 8192) { "缺少有效的$key" } }
        val result = when(method) {
            "homeCategories" -> request(session, "/api/sns/web/v1/homefeed/category", false, rap = false)
            "home" -> request(session, "/api/sns/web/v1/homefeed", true, JSONObject()
                .put("cursor_score", a.optString("cursor")).put("num", 43).put("refresh_type", 1).put("note_index", 0)
                .put("unread_begin_note_id", "").put("unread_end_note_id", "").put("unread_note_count", 0)
                .put("category", a.optString("category", "homefeed_recommend").also {
                    require(it.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "推荐分类无效" }
                }).put("search_key", "").put("need_num", 18)
                .put("image_formats", formats).put("need_filter_image", false))
            "search" -> {
                val keyword = required("keyword")
                require(keyword.length <= 200) { "搜索词过长" }
                val sort = a.optString("sort", "general")
                val noteType = a.optInt("noteType", 0).coerceIn(0, 2)
                require(sort in setOf("general", "time_descending", "popularity_descending")) { "排序方式无效" }
                val filters = JSONArray()
                listOf("sort_type" to sort, "filter_note_type" to when(noteType) { 1 -> "视频笔记"; 2 -> "普通笔记"; else -> "不限" },
                    "filter_note_time" to "不限", "filter_note_range" to "不限", "filter_pos_distance" to "不限").forEach { (type, value) ->
                    filters.put(JSONObject().put("tags", JSONArray(listOf(value))).put("type", type))
                }
                request(session, "/api/sns/web/v2/search/notes", true, JSONObject().put("keyword", keyword)
                    .put("page", a.optInt("page", 1).coerceIn(1, 1000)).put("page_size", 20).put("search_id", required("searchId"))
                    .put("sort", sort).put("note_type", a.optInt("noteType", 0).coerceIn(0, 2)).put("ext_flags", JSONArray()).put("filters", filters)
                    .put("geo", "").put("image_formats", formats).put("message_id", "sending").put("session_id", a.optString("sessionId")),
                    search = true, commonMns = false)
            }
            "detail" -> request(session, "/api/sns/web/v1/feed", true, JSONObject().put("source_note_id", required("id"))
                .put("image_formats", formats).put("extra", JSONObject().put("need_body_topic", "1"))
                .put("xsec_source", a.optString("source", "pc_feed")).put("xsec_token", required("token")))
            "comments" -> request(session, "/api/sns/web/v2/comment/page", false, query = linkedMapOf(
                "note_id" to required("id"), "cursor" to a.optString("cursor"), "top_comment_id" to "",
                "image_formats" to "jpg,webp,avif", "xsec_token" to required("token")), rap = false, commonMns = true)
            "subComments" -> request(session, "/api/sns/web/v2/comment/sub/page", false, query = linkedMapOf(
                "note_id" to required("id"), "root_comment_id" to required("rootId"), "num" to "10",
                "cursor" to a.optString("cursor"), "image_formats" to "jpg,webp,avif", "top_comment_id" to "",
                "xsec_token" to required("token")), rap = false, commonMns = true)
            "userPosts", "collections", "likedNotes" -> {
                val user = session.getJSONObject("user")
                val uid = if (method != "userPosts") user.optString("user_id", user.optString("userid")) else required("id")
                val path = when(method) { "collections" -> "/api/sns/web/v2/note/collect/page"; "likedNotes" -> "/api/sns/web/v1/note/like/page"; else -> "/api/sns/web/v1/user_posted" }
                request(session, path, false, query = linkedMapOf("num" to "30", "cursor" to a.optString("cursor"), "user_id" to uid,
                    "image_formats" to "jpg,webp,avif", "xsec_token" to a.optString("token"), "xsec_source" to a.optString("source")),
                    rap = method == "userPosts", commonMns = true)
            }
            "profile" -> request(session, "/api/sns/web/v1/user/otherinfo", false, query = linkedMapOf(
                "target_user_id" to required("id"), "xsec_token" to a.optString("token"), "xsec_source" to a.optString("source", "pc_note")),
                rap = false, commonMns = true)
            "selfInfo" -> request(session, "/api/sns/web/v1/user/selfinfo", false, rap = false)
            "hover" -> request(session, "/api/sns/web/v1/user/hover_card", false, query = linkedMapOf("target_user_id" to required("id"), "image_formats" to "jpg,webp,avif", "xsec_source" to "pc_note", "xsec_token" to a.optString("token")), rap = false)
            "unread" -> request(session, "/api/sns/web/unread_count", false, rap = false)
            "mentions", "likes", "connections" -> request(session, when (method) {
                "mentions" -> "/api/sns/web/v1/you/mentions"
                "connections" -> "/api/sns/web/v1/you/connections"
                else -> "/api/sns/web/v1/you/likes"
            }, false, query = linkedMapOf("num" to "20", "cursor" to a.optString("cursor")), rap = false)
            "privateChats" -> {
                val page = a.optInt("page", 0)
                require(page in 0..10000) { "私信页码无效" }
                request(session, "/api/im/web/v3/chats", false, query = linkedMapOf(
                    "limit" to "100", "complete" to "true", "page" to page.toString(), "source" to "pc"
                ), rap = false, commonMns = false)
            }
            "followUser" -> request(session, "/api/sns/web/v1/user/follow", true,
                JSONObject().put("target_user_id", required("id")), rap = false)
            "readMessages" -> {
                val type = required("type")
                require(type in setOf("mentions", "likes", "comment", "like", "at")) { "消息类型无效" }
                request(session, "/api/sns/web/v1/message/read", true, JSONObject().put("type", type), rap = false)
            }
            "likeNote" -> request(session, if (a.optBoolean("value")) "/api/sns/web/v1/note/like" else "/api/sns/web/v1/note/dislike", true, JSONObject().put("note_oid", required("id")), rap = false)
            "collectNote" -> {
                val collect = a.optBoolean("value")
                request(session, if (collect) "/api/sns/web/v1/note/collect" else "/api/sns/web/v1/note/uncollect", true,
                    JSONObject().put(if (collect) "note_id" else "note_ids", required("id")), rap = false)
            }
            "postComment" -> {
                val content = required("content")
                require(content.length <= 2000) { "评论内容过长" }
                val body = JSONObject().put("note_id", required("id")).put("content", content).put("at_users", JSONArray())
                if (a.optString("targetId").isNotEmpty()) body.put("target_comment_id", a.optString("targetId"))
                request(session, "/api/sns/web/v1/comment/post", true, body)
            }
            "likeComment", "deleteComment" -> request(session, when {
                method == "deleteComment" -> "/api/sns/web/v1/comment/delete"
                a.optBoolean("value") -> "/api/sns/web/v1/comment/like"
                else -> "/api/sns/web/v1/comment/dislike"
            }, true, JSONObject().put("note_id", required("id")).put("comment_id", required("commentId")), rap = false)
            "suggestions" -> request(session, "/api/sns/web/v1/search/recommend", false, query = mapOf("keyword" to required("keyword")), rap = false)
            "searchFilters" -> request(session, "/api/sns/web/v1/search/filter", false, query = linkedMapOf("keyword" to required("keyword"), "search_id" to required("searchId")), rap = false)
            "searchUsers" -> request(session, "/api/sns/web/v1/search/usersearch", true, JSONObject().put("search_user_request", JSONObject()
                .put("keyword", required("keyword")).put("search_id", required("searchId")).put("page", a.optInt("page", 1).coerceIn(1, 1000))
                .put("page_size", 15).put("biz_type", "web_search_user").put("request_id", a.optString("requestId"))), rap = false, commonMns = false)
            "searchHistory" -> request(session, "/api/sns/web/search/history/sync", true, JSONObject().put("client_time", System.currentTimeMillis()).put("ops", JSONArray()), rap = false, search = true, commonMns = false)
            "ask" -> {
                val searchRequest = JSONObject().put("keyword", required("keyword")).put("page", 1).put("page_size", 20)
                    .put("search_id", required("searchId")).put("sort", "general").put("note_type", 0)
                    .put("ext_flags", JSONArray()).put("geo", "").put("image_formats", formats).put("message_id", "sending")
                request(session, "/api/sns/web/v1/dqa/instant", true, JSONObject().put("search_request", searchRequest), rap = false, search = true, commonMns = false)
            }
            else -> throw ApiFailure("unsupported", "不支持的操作")
        }

        if (session === active) {
            try { vault.save(session); storageWarning = null }
            catch (_: Exception) { storageWarning = "请求已完成，但会话更新未保存；退出应用后可能需要重新登录" }
        }
        return result.toMap()
    }

    private fun request(session: JSONObject, path: String, post: Boolean, body: JSONObject? = null,
        query: Map<String, String> = emptyMap(), rap: Boolean = true, search: Boolean = false, commonMns: Boolean = true, syncToBrowser: Boolean = true): JSONObject {
        val abroad = session.optString("region") == "rednote"
        val origin = if (abroad) "https://www.rednote.com" else "https://www.xiaohongshu.com"
        val host = if (abroad) "https://webapi.rednote.com" else if (search) "https://so.xiaohongshu.com" else "https://edith.xiaohongshu.com"
        val queryString = query.entries.joinToString("&") { "${it.key}=${WebSigner.queryEncode(it.value)}" }
        val url = (host + path + (if (queryString.isEmpty()) "" else "?$queryString")).toHttpUrl()
        val serialized = body?.toString() ?: ""
        val content = if (post) path + serialized else url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val ctx = BrowserContext.signer(session.getJSONObject("context"))
        val ua = session.getString("ua")
        check(BrowserContext.compatible(ua, ctx.platform)) { "登录环境已变化，请重新登录" }

        val cookiesObj = session.optJSONObject("cookies")
        var cookie = cookiesObj?.optString(url.host).orEmpty()
        if (cookie.isBlank() && cookiesObj != null) {
            cookie = cookiesObj.optString(if (abroad) "www.rednote.com" else "edith.xiaohongshu.com")
                .ifBlank { if (abroad) "" else cookiesObj.optString("www.xiaohongshu.com") }
        }
        val a1 = cookie.split(';').map { it.trim().split('=', limit = 2) }.firstOrNull { it.size == 2 && it[0] == "a1" }?.get(1)
        check(a1 == ctx.a1) { "Cookie 与签名环境不一致，请重新初始化" }

        val time = System.currentTimeMillis()
        val uid = session.optJSONObject("user")?.let { it.optString("user_id", it.optString("userid")) }.orEmpty()
        val direction = if (uid.isNotBlank() && path !in setOf(
                "/api/sns/web/v2/comment/page", "/api/sns/web/v2/comment/sub/page", "/api/sns/web/v1/user/otherinfo",
                "/api/sns/web/v1/user/hover_card", "/api/sns/web/v1/search/filter", "/api/sns/web/v1/search/recommend",
                "/api/sns/web/v1/user/selfinfo"
            )) WebSigner.direction(uid) else (random.nextInt(91) + 10).toString()

        val headers = WebSigner.headers(ctx, path, content, !post, commonMns, time, random.nextInt(), direction)
        headers["x-b3-traceid"] = randomHex(8)
        headers["x-xray-traceid"] = randomHex(16)
        if (rap) headers["x-rap-param"] = RapSigner.sign(host + content, time, direction, ctx, ByteArray(37).also(random::nextBytes))

        val builder = Request.Builder().url(url).header("User-Agent", ua).header("Accept", "application/json, text/plain, */*")
            .header("Origin", origin).header("Referer", "$origin/").header("Cookie", cookie)
        session.optString("requestedWith").takeIf { it.isNotBlank() }?.let { builder.header("X-Requested-With", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (post) builder.post(serialized.toRequestBody("application/json;charset=UTF-8".toMediaType()))

        val start = System.nanoTime()
        try {
            client.newCall(builder.build()).execute().use { response ->
                lastRequest = mapOf("path" to path, "http" to response.code, "elapsedMs" to (System.nanoTime() - start) / 1000000,
                    "time" to time)
                val setCookies = response.headers.values("Set-Cookie")
                if (setCookies.isNotEmpty()) updateCookies(session, url.toString(), setCookies, syncToBrowser)

                if (response.code == 406 || response.code == 461 || response.code == 471) {
                    if (session === guestSession) {
                        // 游客会话过期，清除并重新获取
                        GuestSessionManager.clear(appContext)
                        GuestSessionManager.ensure(appContext) { s -> guestSession = s }
                    }
                    val verificationUrl = VerificationPageUrl.build(origin, response.code,
                        response.header("verifyuuid").orEmpty(), response.header("verifytype").orEmpty(),
                        response.header("x-biz").orEmpty(), response.header("verifymsg").orEmpty())
                    val verifyType = response.header("verifytype").orEmpty()
                    val failure = ApiFailure(
                        if (response.code == 406 || verifyType == "302") "signature"
                        else if (verifyType == "301") "login" else "verification",
                        if (response.code == 406) "请求签名被拒绝，请重新初始化访问环境"
                        else if (verifyType == "301") "官方要求重新登录后再访问此接口"
                        else if (verifyType == "302") "官方要求刷新网页访问环境；此响应不是滑块验证入口"
                        else if (verificationUrl.isEmpty()) "接口触发安全检查（HTTP ${response.code}），未提供可用验证入口；网页可浏览不代表此接口已通过检查"
                        else "此接口触发官方安全验证，请打开对应验证页面后重试",
                        mapOf("http" to response.code, "verifytype" to response.header("verifytype"),
                            "verifyuuid" to response.header("verifyuuid"), "verificationUrl" to verificationUrl)
                    )
                    if (session === active || session === guestSession) {
                        blocked = failure
                        blockedPath = path
                        verificationPage = verificationUrl
                    }
                    throw failure
                }
                if (response.code == 401) {
                    if (session === active) throw ApiFailure("login", "登录已过期，请重新登录", mapOf("http" to 401))
                }
                if (!response.isSuccessful) throw ApiFailure("http", "请求失败（HTTP ${response.code}）", mapOf("http" to response.code))

                val buffer = ByteArrayOutputStream()
                response.body.byteStream().use { stream ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = stream.read(chunk)
                        if (count < 0) break
                        if (buffer.size() + count > 4 * 1024 * 1024) throw ApiFailure("response", "响应内容过大")
                        buffer.write(chunk, 0, count)
                    }
                }
                val bytes = buffer.toByteArray()
                val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { throw ApiFailure("response", "服务器没有返回有效的 JSON") }
                val code = json.optInt("code", -1)
                if (!json.optBoolean("success", code == 0)) throw ApiFailure("business", json.optString("msg", "服务暂不可用"), mapOf("businessCode" to code))
                if ((session === active || session === guestSession) && blockedPath == path) {
                    blocked = null
                    blockedPath = null
                    verificationPage = ""
                }
                return json.optJSONObject("data") ?: JSONObject()
            }
        } catch (e: IOException) {
            throw ApiFailure("network", "网络请求失败，请检查网络后重试")
        }
    }

    private fun updateCookies(session: JSONObject, urlText: String, values: List<String>, syncToBrowser: Boolean) {
        val url = urlText.toHttpUrl()
        val snapshots = session.optJSONObject("cookies") ?: JSONObject().also { session.put("cookies", it) }
        val parsed = values.mapNotNull { Cookie.parse(url, it) }
        snapshots.keys().asSequence().toList().forEach { host ->
            val target = "https://$host/api/sns/web/v1/homefeed".toHttpUrl()
            val map = linkedMapOf<String, String>()
            snapshots.optString(host).split(';').forEach { piece ->
                val pair = piece.trim().split('=', limit = 2)
                if (pair.size == 2) map[pair[0]] = pair[1]
            }
            parsed.forEach { cookie ->
                val domainMatches = if (cookie.hostOnly) host == cookie.domain else host == cookie.domain || host.endsWith(".${cookie.domain}")
                if (domainMatches && (cookie.expiresAt <= System.currentTimeMillis() || cookie.matches(target))) {
                    if (cookie.expiresAt <= System.currentTimeMillis()) map.remove(cookie.name) else map[cookie.name] = cookie.value
                }
            }
            snapshots.put(host, map.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        if (session === active) {
            try { vault.save(session); storageWarning = null }
            catch (_: Exception) { storageWarning = "会话更新未保存；退出应用后可能需要重新登录" }
        }
        // Candidate verification never writes into the live login browser.
        if (!syncToBrowser) return
        main.post {
            if (session !== active && session !== guestSession) return@post
            val manager = CookieManager.getInstance()
            var remaining = values.size
            values.forEach { manager.setCookie(urlText, it) {
                if (--remaining == 0) manager.flush()
            } }
        }
    }

    private fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        @Volatile private var instance: ProtocolRuntime? = null
        fun get(context: Context): ProtocolRuntime = instance ?: synchronized(this) { instance ?: ProtocolRuntime(context).also { instance = it } }
    }
}

internal fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key -> jsonValue(get(key)) }
private fun jsonValue(value: Any?): Any? = when(value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
    else -> value
}
