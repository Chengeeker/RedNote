package com.red.note

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.webkit.*
import android.widget.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/** Read only the metrics the official creator page requests for known own notes. */
class CreatorStatsActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val allowed by lazy { intent.getStringArrayListExtra("ids")?.toSet().orEmpty() }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
    private var closed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, insets ->
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                v.setPadding(0, top, 0, 0); insets
            }
        }
        column.addView(Button(this).apply { text = "返回 RedNote"; setOnClickListener { finish() } })
        status = TextView(this).apply {
            text = "打开笔记管理后自动同步当前已加载笔记的浏览量。需要登录或验证时，请在官方页面完成。"
            setPadding(24, 12, 24, 12)
        }
        column.addView(status)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            userAgentString = DesktopUaUtil.getDesktopUa(this@CreatorStatsActivity)
            useWideViewPort = true; loadWithOverviewMode = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                return request.url.scheme != "https" || !(host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com"))
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.method != "GET" || request.url.scheme != "https" || request.url.host != "creator.xiaohongshu.com" ||
                    request.url.path !in setOf("/api/galaxy/creator/note/user/posted", "/api/galaxy/v2/creator/note/user/posted")) return null
                try {
                    val builder = Request.Builder().url(request.url.toString())
                    request.requestHeaders.forEach { (k, v) ->
                        if (!k.equals("Accept-Encoding", true) && !k.equals("Cookie", true) && !k.equals("Host", true)) builder.header(k, v)
                    }
                    CookieManager.getInstance().getCookie(request.url.toString())?.let { builder.header("Cookie", it) }
                    client.newCall(builder.build()).execute().use { response ->
                        val body = response.body
                        body.source().request(2L * 1024 * 1024 + 1)
                        if (body.source().buffer.size > 2L * 1024 * 1024) return null
                        val bytes = body.bytes()
                        for (cookie in response.headers.values("Set-Cookie")) CookieManager.getInstance().setCookie(request.url.toString(), cookie)
                        if (response.isSuccessful) {
                            try { capture(String(bytes, Charsets.UTF_8)) } catch (_: org.json.JSONException) { /* Keep the original response visible. */ }
                        }
                        val headers = response.headers.toMap().filterKeys { !it.equals("content-encoding", true) && !it.equals("content-length", true) }
                        return WebResourceResponse("application/json", "UTF-8", response.code,
                            response.message.ifBlank { "Response" }, headers, ByteArrayInputStream(bytes))
                    }
                } catch (_: Exception) {
                    runOnUiThread { if (!closed) status.text = "同步未完成，可在官方笔记管理页面刷新后重试，或返回 RedNote。" }
                    return null
                }
            }
        }
        column.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        web.loadUrl("https://creator.xiaohongshu.com/new/note-manager")
    }
    private fun capture(text: String) {
        val response = JSONObject(text)
        if (!response.optBoolean("success", false)) return
        val data = response.optJSONObject("data")
        val notes = data?.optJSONArray("notes") ?: data?.optJSONArray("note_list") ?: return
        val counts = JSONObject()
        for (i in 0 until notes.length()) {
            val note = notes.optJSONObject(i) ?: continue
            val id = note.optString("id")
            val views = note.optString("view_count").toLongOrNull() ?: continue
            if (id in allowed && views >= 0) counts.put(id, views)
        }
        if (counts.length() == 0) return
        runOnUiThread {
            if (!closed) {
                setResult(RESULT_OK, Intent().putExtra("counts", counts.toString()))
                finish()
            }
        }
    }
    override fun onDestroy() {
        closed = true
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}
