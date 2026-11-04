package com.red.note

import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.webkit.CookieManager
import com.red.note.protocol.VerificationPageUrl
import com.red.note.protocol.ProfilePageUrl
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private var loginResult: MethodChannel.Result? = null
    private var creatorStats: CreatorStatsSync? = null
    private var mediaBridge: MediaBridge? = null
    private val runtime by lazy { ProtocolRuntime.get(this) }
    private lateinit var navigation: MethodChannel
    private var navigationReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPendingProfile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPendingProfile()
    }

    private fun openPendingProfile() {
        if (!navigationReady) return
        val url = intent.getStringExtra("private_chat_profile") ?: return
        intent.removeExtra("private_chat_profile")
        val profile = ProfilePageUrl.parse(url) ?: return
        val chatId = intent.getStringExtra("return_chat_id").orEmpty()
        if (!chatId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return
        val chatName = intent.getStringExtra("return_chat_name").orEmpty().take(80)
        val session = runtime.active
        fun returnToChat(message: String? = null) {
            if (isFinishing || isDestroyed || runtime.active !== session) return
            if (message != null) android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, PrivateChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("id", chatId).putExtra("name", chatName))
        }
        navigation.invokeMethod("openProfile", profile, object : MethodChannel.Result {
            override fun success(result: Any?) = returnToChat()
            override fun error(code: String, message: String?, details: Any?) = returnToChat("无法打开个人主页，请稍后重试")
            override fun notImplemented() = returnToChat("个人主页暂时未就绪，请稍后重试")
        })
    }

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        navigation = MethodChannel(engine.dartExecutor.binaryMessenger, "com.red.note/navigation")
        navigation.setMethodCallHandler { call, result ->
            if (call.method == "ready") {
                navigationReady = true
                result.success(null)
                openPendingProfile()
            } else result.notImplemented()
        }
        mediaBridge = MediaBridge(this, engine)

        // 尽早触发准备（如果已有登录态则不影响，如果无登录态则在后台准备游客环境）
        runtime.worker.execute {
            runtime.load()
        }

        MethodChannel(engine.dartExecutor.binaryMessenger, "com.red.note/api").setMethodCallHandler { call, result ->
            if (call.method == "login") {
                if (loginResult != null) {
                    result.error("busy", "登录页面已打开", null)
                    return@setMethodCallHandler
                }
                val manual = call.argument<Boolean>("manualVerification") == true
                val verificationUrl = call.argument<String>("verificationUrl") ?: runtime.verificationPageUrl()
                if (manual && !VerificationPageUrl.isTrusted(verificationUrl)) {
                    result.error("verification", "接口未提供可用的官方验证入口，请重试原请求取得新的验证信息；普通发现页无法代替本次验证", null)
                    return@setMethodCallHandler
                }
                creatorStats?.cancel()
                loginResult = result
                startActivityForResult(
                    Intent(this, LoginActivity::class.java)
                        .putExtra("manual_verification", manual)
                        .putExtra("verification_url", if (manual) verificationUrl else ""),
                    42
                )
            } else if (call.method == "syncCreatorStats") {
                val ids = call.argument<List<String>>("ids").orEmpty().filter { it.matches(Regex("[A-Za-z0-9]{1,128}")) }.take(500)
                val session = runtime.active
                if (creatorStats != null) result.error("busy", "正在同步浏览量", null)
                else if (session == null) result.error("login", "请先登录后再同步浏览量", null)
                else if (ids.isEmpty()) result.error("reference", "请先加载自己发布的笔记", null)
                else {
                    creatorStats = CreatorStatsSync(this, ids.toSet(), { runtime.active === session }) { counts, error ->
                        creatorStats = null
                        if (runtime.active !== session) result.error("cancelled", "账号已变化，已取消浏览量同步", null)
                        else if (error != null) result.error(error.kind, error.message, error.details)
                        else result.success(counts)
                    }
                    creatorStats?.start()
                }
            } else if (call.method == "openProject") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Chengeeker/RedNote")))
                    result.success(null)
                } catch (_: ActivityNotFoundException) {
                    result.error("external_app", "无法打开项目地址，请安装或启用浏览器", null)
                }
            } else if (call.method == "shareNote") {
                val id = call.argument<String>("id").orEmpty()
                val token = call.argument<String>("token").orEmpty()
                if (!id.matches(Regex("[A-Za-z0-9]{1,128}"))) { result.error("reference", "笔记 ID 无效", null) }
                else {
                    val url = android.net.Uri.Builder().scheme("https").authority("www.xiaohongshu.com")
                        .appendPath("explore").appendPath(id).appendQueryParameter("xsec_token", token)
                        .appendQueryParameter("xsec_source", "pc_share").build().toString()
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "分享笔记"))
                    result.success(null)
                }
            } else if (call.method == "openPrivateChat") {
                val id = call.argument<String>("id").orEmpty()
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
                    result.error("reference", "会话用户 ID 无效", null)
                } else {
                    startActivity(Intent(this, PrivateChatActivity::class.java)
                        .putExtra("id", id)
                        .putExtra("name", call.argument<String>("name").orEmpty().take(80)))
                    result.success(null)
                }
            } else {
                if (call.method == "logout") creatorStats?.cancel()
                runtime.worker.execute {
                    try {
                        val output = runtime.invoke(call.method, call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())
                        runOnUiThread {
                            if (call.method == "logout") CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().flush()
                                result.success(output)
                            } else result.success(output)
                        }
                    } catch (e: ApiFailure) {
                        runOnUiThread { result.error(e.kind, e.message, e.details) }
                    } catch (e: Exception) {
                        runOnUiThread { result.error("client", e.message ?: "本机访问环境未就绪，请重试", null) }
                    }
                }
            }
        }
    }

    @Deprecated("Android activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            val result = loginResult
            loginResult = null
            runtime.worker.execute {
                val status = runtime.status()
                runOnUiThread { result?.success(status) }
            }
        }
    }

    override fun onDestroy() {
        mediaBridge?.close()
        loginResult?.error("cancelled", "登录页面已关闭", null)
        loginResult = null
        creatorStats?.cancel()
        super.onDestroy()
    }
}
