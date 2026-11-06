package com.red.note

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal object GuestSessionManager {
    private const val TAG = "GuestSessionManager"
    private const val PREF_NAME = "rednote_guest_pref"
    private const val KEY_SESSION = "guest_session"
    private val initializing = AtomicBoolean(false)
    private var headlessWeb: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentGuestSession: JSONObject? = null
        private set

    fun load(context: Context): JSONObject? {
        if (currentGuestSession != null) return currentGuestSession
        val pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val str = pref.getString(KEY_SESSION, null) ?: return null
        return try {
            JSONObject(str).also { currentGuestSession = it }
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, session: JSONObject) {
        currentGuestSession = session
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSION, session.toString())
            .apply()
    }

    fun clear(context: Context) {
        currentGuestSession = null
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION)
            .apply()
    }

    fun ensure(context: Context, onReady: ((JSONObject) -> Unit)? = null) {
        load(context)?.let {
            onReady?.invoke(it)
            return
        }
        if (!initializing.compareAndSet(false, true)) {
            // Already initializing; poll until ready
            pollForReady(context, onReady, 10)
            return
        }

        mainHandler.post {
            try {
                startHeadlessCapture(context.applicationContext, onReady)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start headless webview for guest", e)
                initializing.set(false)
            }
        }
    }

    private fun pollForReady(context: Context, onReady: ((JSONObject) -> Unit)?, remainingAttempts: Int) {
        if (remainingAttempts <= 0) return
        mainHandler.postDelayed({
            val s = load(context)
            if (s != null) {
                onReady?.invoke(s)
            } else {
                pollForReady(context, onReady, remainingAttempts - 1)
            }
        }, 1000)
    }

    private fun startHeadlessCapture(appContext: Context, onReady: ((JSONObject) -> Unit)?) {
        val web = WebView(appContext)
        headlessWeb = web
        val actualUa = DesktopUaUtil.getDesktopUa(appContext)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = actualUa
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(web, true)

        var capturedContext: JSONObject? = null
        var requestedWith = ""

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val headers = request.requestHeaders ?: return null
                fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                val xs = header("x-s")
                val common = header("x-s-common")
                if (xs != null && common != null && capturedContext == null) {
                    try {
                        val ua = header("User-Agent") ?: actualUa
                        val width = 1280
                        val height = 800
                        val pageStart = System.currentTimeMillis() - 500
                        val captured = BrowserContext.capture(xs, common, ua, width, height, pageStart)
                        capturedContext = captured
                        requestedWith = header("X-Requested-With") ?: ""
                        val a1 = captured.optString("a1")
                        val webId = md5Hex(a1)

                        val cookieStr = "a1=$a1; webId=$webId"
                        val snapshots = JSONObject().apply {
                            put("www.xiaohongshu.com", cookieStr)
                            put("edith.xiaohongshu.com", cookieStr)
                            put("so.xiaohongshu.com", cookieStr)
                        }

                        val candidate = JSONObject().apply {
                            put("context", captured)
                            put("ua", ua)
                            put("requestedWith", requestedWith)
                            put("region", "xhs")
                            put("cookies", snapshots)
                        }

                        save(appContext, candidate)
                        Log.i(TAG, "Guest session captured and saved successfully! a1=$a1")
                        onReady?.let { cb -> mainHandler.post { cb(candidate) } }
                        destroyHeadless()
                    } catch (e: Exception) {
                        Log.w(TAG, "Headless capture parsing error: ${e.message}")
                    }
                }
                return null
            }
        }

        // Timeout cleanup after 15 seconds
        mainHandler.postDelayed({
            if (initializing.get()) {
                destroyHeadless()
            }
        }, 15000)

        web.loadUrl("https://www.xiaohongshu.com/explore")
    }

    private fun destroyHeadless() {
        mainHandler.post {
            try {
                headlessWeb?.stopLoading()
                headlessWeb?.removeAllViews()
                headlessWeb?.destroy()
            } catch (_: Exception) {}
            headlessWeb = null
            initializing.set(false)
        }
    }

    private fun md5Hex(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
