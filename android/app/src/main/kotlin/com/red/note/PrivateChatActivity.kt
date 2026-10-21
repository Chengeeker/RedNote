package com.red.note

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

class PrivateChatActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra("id").orEmpty()
        if (!chatId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            finish()
            return
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val background = Color.rgb(25, 24, 29)
        val foreground = Color.rgb(240, 238, 242)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setOnApplyWindowInsetsListener { view, insets ->
                val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
                val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(0, top, 0, bottom)
                insets
            }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), -1))
        bar.addView(TextView(this).apply {
            text = intent.getStringExtra("name").orEmpty().take(80).ifBlank { "私聊" }
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(foreground)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(TextView(this).apply {
            text = "刷新"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            setOnClickListener { web.reload() }
        }, LinearLayout.LayoutParams(dp(56), -1))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(52)))

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        web = WebView(this).apply {
            setBackgroundColor(background)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = !LoginActivity.trustedPage(request.url.toString())

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    status.text = "正在打开小红书会话…"
                    status.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    status.visibility = View.GONE
                    updateChatLayout(view, url)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    updateChatLayout(view, url)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        status.text = "会话页面加载失败，请检查网络后刷新。"
                        status.visibility = View.VISIBLE
                    }
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val runtime = ProtocolRuntime.get(this)
        runtime.load()
        val session = runtime.active
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        session?.optJSONObject("cookies")?.keys()?.forEach { host ->
            if (host in LoginActivity.allowedHosts) {
                session.optJSONObject("cookies")?.optString(host).orEmpty().split(';').forEach { piece ->
                    if (piece.contains('=')) {
                        cookies.setCookie("https://$host/", piece.trim() + "; Path=/; Secure")
                    }
                }
            }
        }
        cookies.flush()
        val host = if (session?.optString("region") == "rednote") "www.rednote.com" else "www.xiaohongshu.com"
        session?.optString("ua")?.takeIf { it.isNotBlank() }?.let { web.settings.userAgentString = it }
        val scale = ((resources.displayMetrics.widthPixels.toFloat() / 960f) * 100).toInt().coerceIn(40, 100)
        web.setInitialScale(scale)
        web.loadUrl(android.net.Uri.Builder().scheme("https").authority(host).appendPath("chat").appendPath(chatId).build().toString())
    }

    @Deprecated("WebView history is handled before closing the activity")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.removeAllViews()
            web.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // shortcut: relies on official web chat DOM classes; update selectors when its markup changes.
    private fun updateChatLayout(view: WebView, url: String) {
        val uri = android.net.Uri.parse(url)
        val isChatPage = LoginActivity.trustedPage(url) &&
            uri.host in setOf("www.xiaohongshu.com", "www.rednote.com") &&
            uri.pathSegments.firstOrNull() == "chat" && uri.pathSegments.size >= 2
        val script = if (isChatPage) {
            """
                (function() {
                  var id = 'rednote-native-chat-layout';
                  var style = document.getElementById(id);
                  if (!style) {
                    style = document.createElement('style');
                    style.id = id;
                    (document.head || document.documentElement).appendChild(style);
                  }
                  style.textContent = '.xhs-im-chat-window__header,.bottom-menu{display:none!important}.xhs-im-page{height:100vh!important}';
                })();
            """.trimIndent()
        } else {
            "var style=document.getElementById('rednote-native-chat-layout');if(style)style.remove();"
        }
        view.evaluateJavascript(script, null)
    }
}
