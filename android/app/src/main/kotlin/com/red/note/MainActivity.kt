package com.red.note

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity: FlutterActivity() {
    private var loginResult: MethodChannel.Result? = null
    private var statsResult: MethodChannel.Result? = null
    private var mediaBridge: MediaBridge? = null
    private val runtime by lazy { ProtocolRuntime.get(this) }

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
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
                loginResult = result
                startActivityForResult(
                    Intent(this, LoginActivity::class.java)
                        .putExtra("manual_verification", call.argument<Boolean>("manualVerification") == true),
                    42
                )
            } else if (call.method == "syncCreatorStats") {
                val ids = call.argument<List<String>>("ids").orEmpty().filter { it.matches(Regex("[A-Za-z0-9]{1,128}")) }.take(500)
                if (statsResult != null) result.error("busy", "同步页面已打开", null)
                else if (ids.isEmpty()) result.error("reference", "请先加载自己发布的笔记", null)
                else {
                    statsResult = result
                    startActivityForResult(Intent(this, CreatorStatsActivity::class.java).putStringArrayListExtra("ids", ArrayList(ids)), 43)
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
        if (requestCode == 43) {
            val result = statsResult
            statsResult = null
            val counts = org.json.JSONObject(data?.getStringExtra("counts") ?: "{}")
            result?.success(counts.keys().asSequence().associateWith { counts.getLong(it) })
        }
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
        statsResult?.error("cancelled", "同步页面已关闭", null)
        statsResult = null
        super.onDestroy()
    }
}
