package com.red.note

import android.app.Activity
import android.content.Intent
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
import com.red.note.protocol.ProfilePageUrl

class PrivateChatActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var openingProfile = false
    private var browserSession: org.json.JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= 26) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                androidx.core.view.WindowInsetsCompat.CONSUMED
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
        }, LinearLayout.LayoutParams(dp(56), -1))
        bar.addView(TextView(this).apply {
            text = intent.getStringExtra("name").orEmpty().take(80).ifBlank { "私聊" }
            textSize = 17f
            gravity = Gravity.CENTER
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
                ): Boolean {
                    val url = request.url.toString()
                    if (request.isForMainFrame && openNativeProfile(view, url)) return true
                    return !LoginActivity.trustedPage(url)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (openNativeProfile(view, url, restoreHistory = true)) return
                    status.text = "正在打开小红书会话…"
                    status.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    status.visibility = View.GONE
                    updateChatLayout(view, url)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (openNativeProfile(view, url, restoreHistory = true)) return
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
        browserSession = session
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

    private fun openNativeProfile(view: WebView, url: String, restoreHistory: Boolean = false): Boolean {
        if (ProfilePageUrl.parse(url) == null) return false
        if (openingProfile) return true
        if (ProtocolRuntime.get(this).active !== browserSession) {
            finish()
            return true
        }
        openingProfile = true
        if (restoreHistory) {
            view.stopLoading()
            view.post {
                if (!isFinishing && ProfilePageUrl.parse(view.url) != null && view.canGoBack()) view.goBack()
            }
        }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("private_chat_profile", url)
            .putExtra("return_chat_id", intent.getStringExtra("id"))
            .putExtra("return_chat_name", intent.getStringExtra("name")))
        return true
    }

    override fun onResume() {
        super.onResume()
        openingProfile = false
        if (::web.isInitialized && ProtocolRuntime.get(this).active !== browserSession) finish()
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
                  if (!window.__rednoteProfileLinks) {
                    window.__rednoteProfileLinks = true;
                    document.addEventListener('click', function(event) {
                      var link = event.target.closest ? event.target.closest('a[href]') : null;
                      if (!link) return;
                      var target = new URL(link.href, location.href);
                      if (target.origin !== location.origin || !/^\/user\/profile\/[A-Za-z0-9_-]+\/?$/.test(target.pathname)) return;
                      event.preventDefault();
                      event.stopImmediatePropagation();
                      location.href = target.href;
                    }, true);
                  }
                })();
            """.trimIndent()
        } else {
            "var style=document.getElementById('rednote-native-chat-layout');if(style)style.remove();"
        }
        val tapStyle = if (LoginActivity.trustedPage(url)) LoginActivity.noTapHighlightScript else ""
        view.evaluateJavascript(tapStyle + script, null)
    }
}
