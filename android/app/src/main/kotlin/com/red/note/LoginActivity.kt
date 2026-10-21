package com.red.note

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class LoginActivity: Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private val handler = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)
    @Volatile private var signContext: JSONObject? = null
    @Volatile private var actualUa = ""
    @Volatile private var requestedWith = ""
    @Volatile private var region = "xhs"
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var pageStart = 0L
    private var lastAttemptFingerprint = ""
    private var closed = false
    private var manualVerification = false
    private val runtime by lazy { ProtocolRuntime.get(this) }
    private val poll = object: Runnable {
        override fun run() {
            if (closed) return
            if (!manualVerification && !checking.get() && signContext != null) verify(false)
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manualVerification = intent.getBooleanExtra("manual_verification", false)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                view.setPadding(0, topInset, 0, 0)
                insets
            }
        }
        val bar = LinearLayout(this)
        bar.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))

        bar.addView(Button(this).apply {
            text = "刷新页面"
            setOnClickListener {
                status.text = "正在重新加载网页…"
                web.reload()
            }
        }, LinearLayout.LayoutParams(0, dp(50), 1f))

        confirm = Button(this).apply {
            text = if (manualVerification) "验证完成" else "确认登录"
            setOnClickListener { verify(true) }
        }
        bar.addView(confirm, LinearLayout.LayoutParams(0, dp(50), 1.5f))
        column.addView(bar)

        status = TextView(this).apply {
            text = "正在准备桌面版登录环境，请稍候…"
            setPadding(dp(16), dp(8), dp(16), dp(8))
            textSize = 13f
        }
        column.addView(status)

        web = WebView(this)
        actualUa = DesktopUaUtil.getDesktopUa(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = actualUa
        }

        val dm = resources.displayMetrics
        if (dm.widthPixels > 0) {
            val targetVirtualWidth = 960f
            val scale = ((dm.widthPixels.toFloat() / targetVirtualWidth) * 100).toInt().coerceIn(40, 100)
            web.setInitialScale(scale)
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object: WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !trustedPage(request.url.toString())

            override fun onPageFinished(view: WebView, url: String) {
                if (!trustedPage(url)) return
                view.evaluateJavascript("(function(){return JSON.stringify({w:innerWidth,h:innerHeight,t:Math.round(performance.timeOrigin||(Date.now()-performance.now()))});})()") { raw ->
                    try {
                        val value = JSONObject("{\"value\":$raw}").getString("value")
                        val environment = JSONObject(value)
                        width = environment.getInt("w")
                        height = environment.getInt("h")
                        pageStart = environment.getLong("t")
                        if (signContext != null && !closed) {
                            status.text = if (manualVerification)
                                "请在官方网页完成拖拽验证；完成后点“验证完成”，返回应用会自动重试。"
                            else "网页登录环境已就绪；登录后会自动验证，也可点确认登录重试。"
                        }
                    } catch (_: Exception) {
                        status.text = "网页环境加载中，请等待页面就绪…"
                    }
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!trustedPage(request.url.toString())) return null
                val headers = request.requestHeaders
                fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                val xs = header("x-s")
                val common = header("x-s-common")
                if (xs != null && common != null) {
                    try {
                        val ua = header("User-Agent") ?: actualUa
                        val captured = BrowserContext.capture(xs, common, ua, width.coerceAtLeast(960), height.coerceAtLeast(600), pageStart)
                        actualUa = ua
                        requestedWith = header("X-Requested-With") ?: ""
                        region = if (request.url.host?.endsWith("rednote.com") == true) "rednote" else "xhs"
                        signContext = captured
                        Log.i("RedNote", "Sign context captured successfully")
                        handler.post {
                            if (!closed) status.text = if (manualVerification)
                                "请在官方网页完成拖拽验证；完成后点“验证完成”，返回应用会自动重试。"
                            else "网页登录环境已就绪；登录后会自动验证，也可点确认登录重试。"
                        }
                    } catch (e: Exception) {
                        Log.w("RedNote", "Capture signature context failed: ${e.message}")
                        handler.post {
                            if (!closed && signContext == null) {
                                status.text = "检测到签名信息，正在解析桌面环境… (${e.message ?: "解析中"})"
                            }
                        }
                    }
                }
                return null
            }
        }

        column.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)

        val saved = runtime.active
        if (saved != null) {
            val snapshots = saved.optJSONObject("cookies")
            snapshots?.keys()?.forEach { host ->
                if (host in allowedHosts) snapshots.optString(host).split(';').forEach { piece ->
                    if (piece.contains('=')) cookies.setCookie("https://$host/", piece.trim() + "; Path=/; Secure")
                }
            }
            cookies.flush()
        }

        val host = if (saved?.optString("region") == "rednote") "www.rednote.com" else "www.xiaohongshu.com"
        val initial = "https://$host/${if (manualVerification) "explore" else "login"}"
        web.loadUrl(initial)
        handler.postDelayed(poll, 3000)
    }

    private fun verify(manual: Boolean) {
        if (closed || checking.get()) return
        val captured = signContext
        if (captured == null || width <= 0 || height <= 0 || pageStart <= 0) {
            if (manual) status.text = "等待官方网页签名环境初始化，请先让页面完成加载（或点刷新页面重试）。"
            return
        }
        val hosts = if (region == "rednote") listOf("www.rednote.com", "webapi.rednote.com") else listOf("www.xiaohongshu.com", "edith.xiaohongshu.com", "so.xiaohongshu.com")
        val snapshots = JSONObject()
        hosts.forEach { host -> snapshots.put(host, CookieManager.getInstance().getCookie("https://$host/api/sns/web/v1/homefeed") ?: "") }
        val raw = snapshots.optString(hosts[1]).ifEmpty { snapshots.optString(hosts[0]) }
        val cookieMap = raw.split(';').map { it.trim().split('=', limit = 2) }.filter { it.size == 2 }.associate { it[0] to it[1] }
        if (cookieMap["web_session"].isNullOrBlank() || cookieMap["a1"] != captured.optString("a1")) {
            if (manual) status.text = "尚未检测到有效登录状态，请先在网页中完成扫码登录。"
            return
        }
        val cookieFingerprint = fingerprint("$region|$snapshots")
        if (!manual && cookieFingerprint == lastAttemptFingerprint) return
        if (!checking.compareAndSet(false, true)) return
        lastAttemptFingerprint = cookieFingerprint
        confirm.isEnabled = false
        status.text = if (manual) "正在验证登录状态…" else "已检测到登录状态，正在自动验证…"
        val context = JSONObject(captured.toString()).put("width", width).put("height", height).put("pageStart", pageStart)
        val candidate = JSONObject().put("context", context).put("ua", actualUa).put("requestedWith", requestedWith)
            .put("region", region).put("cookies", snapshots)
        runtime.worker.execute {
            try {
                runtime.accept(candidate)
                handler.post {
                    if (!closed) {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (!closed) {
                        status.text = (e as? ApiFailure)?.message ?: "登录校验未通过，请确认是否已扫码成功；原有会话保持不变。"
                        confirm.isEnabled = true
                    }
                }
            } finally {
                checking.set(false)
            }
        }
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        val allowedHosts = setOf("www.xiaohongshu.com", "edith.xiaohongshu.com", "so.xiaohongshu.com", "www.rednote.com", "webapi.rednote.com")
        fun trustedPage(url: String): Boolean = try {
            val u = URI(url)
            val h = u.host?.lowercase() ?: ""
            u.scheme == "https" && (h == "xiaohongshu.com" || h.endsWith(".xiaohongshu.com") || h == "rednote.com" || h.endsWith(".rednote.com"))
        } catch (_: Exception) {
            false
        }
    }
}
