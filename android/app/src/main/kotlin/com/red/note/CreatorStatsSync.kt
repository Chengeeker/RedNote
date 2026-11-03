package com.red.note

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/** Observe the official page's read-only metrics request without opening a screen. */
internal class CreatorStatsSync(
    private val context: Context,
    private val allowed: Set<String>,
    private val isCurrentSession: () -> Boolean,
    private val onComplete: (Map<String, Long>?, ApiFailure?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var closed = false
    private val timeout = Runnable {
        complete(error = ApiFailure("timeout", "个人资料已刷新，浏览量同步超时，已保留原数据"))
    }

    fun start() {
        main.postDelayed(timeout, 30_000)
        try {
            val page = WebView(context)
            web = page
            page.apply {
                settings.apply {
                    javaScriptEnabled = true; domStorageEnabled = true
                    userAgentString = DesktopUaUtil.getDesktopUa(context)
                    useWideViewPort = true; loadWithOverviewMode = true
                    allowFileAccess = false; allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                webViewClient = pageClient
                // An unattached WebView still needs a desktop viewport for this page.
                measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1280, 800)
                loadUrl("https://creator.xiaohongshu.com/new/note-manager")
            }
        } catch (_: Exception) {
            complete(error = ApiFailure("client", "个人资料已刷新，浏览量暂未同步，已保留原数据"))
        }
    }

    private val pageClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host.orEmpty()
            return closed || request.url.scheme != "https" ||
                !(host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com"))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val path = android.net.Uri.parse(url).path.orEmpty()
            if (path.contains("login", true) || path.contains("captcha", true)) needsVerification()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) complete(error = ApiFailure("network", "个人资料已刷新，浏览量暂未同步，已保留原数据"))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame) return
            if (response.statusCode in setOf(401, 403, 461, 471)) needsVerification()
            else complete(error = ApiFailure("network", "个人资料已刷新，浏览量暂未同步，已保留原数据"))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            complete(error = ApiFailure("client", "浏览量同步已停止，已保留原数据"))
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (closed || !isCurrentSession()) return emptyResponse()
            if (request.method != "GET" || request.url.scheme != "https" || request.url.host != "creator.xiaohongshu.com" ||
                request.url.path !in setOf("/api/galaxy/creator/note/user/posted", "/api/galaxy/v2/creator/note/user/posted")) return null
            try {
                val builder = Request.Builder().url(request.url.toString())
                request.requestHeaders.forEach { (k, v) ->
                    if (!k.equals("Accept-Encoding", true) && !k.equals("Cookie", true) && !k.equals("Host", true)) builder.header(k, v)
                }
                CookieManager.getInstance().getCookie(request.url.toString())?.let { builder.header("Cookie", it) }
                client.newCall(builder.build()).execute().use { response ->
                    if (closed || !isCurrentSession()) return emptyResponse()
                    val body = response.body
                    body.source().request(2L * 1024 * 1024 + 1)
                    if (body.source().buffer.size > 2L * 1024 * 1024) {
                        complete(error = ApiFailure("response", "浏览量数据暂时无法读取，已保留原数据"))
                        return emptyResponse()
                    }
                    val bytes = body.bytes()
                    if (closed || !isCurrentSession()) return emptyResponse()
                    val cookies = response.headers.values("Set-Cookie")
                    main.post {
                        if (!closed && isCurrentSession()) {
                            for (cookie in cookies) CookieManager.getInstance().setCookie(request.url.toString(), cookie)
                        }
                    }
                    if (response.isSuccessful) capture(String(bytes, Charsets.UTF_8))
                    else if (response.code in setOf(401, 403, 461, 471)) needsVerification()
                    else complete(error = ApiFailure("network", "个人资料已刷新，浏览量暂未同步，已保留原数据"))
                    if (response.code in 300..399) return emptyResponse()
                    val headers = response.headers.toMap().filterKeys { !it.equals("content-encoding", true) && !it.equals("content-length", true) }
                    return WebResourceResponse("application/json", "UTF-8", response.code,
                        response.message.ifBlank { "Response" }, headers, ByteArrayInputStream(bytes))
                }
            } catch (_: Exception) {
                complete(error = ApiFailure("network", "个人资料已刷新，浏览量暂未同步，已保留原数据"))
                // Do not send the same signed request again through WebView on failure.
                return emptyResponse()
            }
        }
    }

    private fun emptyResponse() = WebResourceResponse("application/json", "UTF-8",
        ByteArrayInputStream("{}".toByteArray()))

    private fun needsVerification() = complete(error = ApiFailure("creator_verification",
        "浏览量需要在官方创作者中心登录或验证，已保留原数据"))

    private fun capture(text: String) {
        val response = JSONObject(text)
        if (!response.optBoolean("success", false)) {
            complete(error = ApiFailure("response", "个人资料已刷新，浏览量暂未同步，已保留原数据"))
            return
        }
        val data = response.optJSONObject("data")
        val notes = data?.optJSONArray("notes") ?: data?.optJSONArray("note_list")
        if (notes == null) {
            complete(error = ApiFailure("response", "浏览量数据暂时无法读取，已保留原数据"))
            return
        }
        val counts = linkedMapOf<String, Long>()
        for (i in 0 until notes.length()) {
            val note = notes.optJSONObject(i) ?: continue
            val id = note.optString("id")
            val views = note.optString("view_count").toLongOrNull() ?: continue
            if (id in allowed && views >= 0) counts[id] = views
        }
        if (counts.isEmpty()) complete(error = ApiFailure("response", "暂未读到这些笔记的浏览量，已保留原数据"))
        else complete(counts)
    }

    private fun complete(counts: Map<String, Long>? = null, error: ApiFailure? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { complete(counts, error) }
            return
        }
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        client.dispatcher.executorService.execute {
            check(Looper.myLooper() != Looper.getMainLooper())
            // Closing a TLS socket can write close_notify, so cleanup is network I/O.
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
        try { web?.apply { stopLoading(); destroy() } } catch (_: RuntimeException) { /* Renderer may already be gone. */ }
        web = null
        onComplete(counts, error)
    }

    fun cancel() = complete(error = ApiFailure("cancelled", "浏览量同步已取消"))
}
