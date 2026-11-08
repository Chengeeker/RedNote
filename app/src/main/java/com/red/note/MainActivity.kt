package com.red.note

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import com.red.note.protocol.ProfilePageUrl
import com.red.note.protocol.VerificationPageUrl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {
    internal val state by viewModels<RedState>()

    internal data class ProfileRequest(val id: Int, val args: Map<String, Any?>)

    internal val pendingProfiles = mutableStateListOf<ProfileRequest>()
    private val profileReturns = mutableMapOf<Int, Pair<Intent, Any?>>()
    private var profileSerial = 0
    private var creatorStats: CreatorStatsSync? = null
    private var pendingSave: (() -> Unit)? = null
    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            val save = pendingSave
            pendingSave = null
            if (allowed) save?.invoke() else state.message = "需要存储权限才能保存媒体"
        }
    internal var saving by mutableStateOf(false)

    internal fun saveMedia(url: String, video: Boolean, author: String = "", audio: String = "") {
        if (saving || pendingSave != null) return
        val save: () -> Unit = {
            saving = true
            lifecycleScope.launch {
                try {
                    val p = state.preferences
                    val type = p.int(if (video) "videoPath" else "imagePath").coerceIn(0, 2)
                    val path =
                        mediaSubdirectory(
                            type,
                            state.session.optJSONObject("user")?.optString("nickname"),
                            author,
                        )
                    val result =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            MediaSaver(this@MainActivity).save(url, path, video, audio)
                        }
                    state.message = "已保存到 $result"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    state.message = e.message.orEmpty()
                } finally {
                    saving = false
                }
            }
        }
        if (
            android.os.Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = save
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else save()
    }

    internal suspend fun syncStats(ids: Set<String>): Map<String, Long> =
        suspendCancellableCoroutine { next ->
            val session = state.runtime.active
            if (session == null || ids.isEmpty() || creatorStats != null) {
                next.resumeWithException(IllegalStateException("暂无可同步的笔记或已有同步任务"))
                return@suspendCancellableCoroutine
            }
            lateinit var task: CreatorStatsSync
            task =
                CreatorStatsSync(
                    this,
                    ids.filter { it.matches(Regex("[A-Za-z0-9]{1,128}")) }.take(500).toSet(),
                    { state.runtime.active === session },
                ) { counts, error ->
                    if (creatorStats === task) creatorStats = null
                    if (next.isActive) {
                        if (error != null) next.resumeWithException(error)
                        else if (state.runtime.active !== session)
                            next.resumeWithException(IllegalStateException("账号已变化"))
                        else next.resume(counts.orEmpty())
                    }
                }
            creatorStats = task
            next.invokeOnCancellation {
                runOnUiThread {
                    task.cancel()
                    if (creatorStats === task) creatorStats = null
                }
            }
            task.start()
        }

    internal var loginBusy by mutableStateOf(false)
    private val login =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch {
                try {
                    state.refreshSession()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    state.message = e.message.orEmpty()
                } finally {
                    loginBusy = false
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        readProfile(intent)
        setContent { RedApp(state, this) }
        lifecycleScope.launch {
            ImageCacheMaintenance.trimOnStartup(
                applicationContext,
                imageLoader,
                ImageCacheMaintenance.threshold(state.preferences),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readProfile(intent)
    }

    private fun readProfile(intent: Intent) {
        val profile =
            ProfilePageUrl.parse(intent.getStringExtra("private_chat_profile") ?: return) ?: return
        intent.removeExtra("private_chat_profile")
        val id = intent.getStringExtra("return_chat_id").orEmpty()
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return
        val returnChat =
            Intent(this, PrivateChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("id", id)
                .putExtra("name", intent.getStringExtra("return_chat_name").orEmpty().take(80))
        val request = ++profileSerial
        profileReturns[request] = returnChat to state.runtime.active
        pendingProfiles.add(ProfileRequest(request, profile))
    }

    internal fun finishProfile(id: Int) {
        val destination = profileReturns.remove(id) ?: return
        if (state.runtime.active === destination.second) startActivity(destination.first)
    }

    internal fun clearProfileNavigation() {
        pendingProfiles.clear()
        profileReturns.clear()
    }

    internal fun openLogin(verify: Boolean = false) {
        if (loginBusy) return
        creatorStats?.cancel()
        val url = state.runtime.verificationPageUrl()
        if (verify && !VerificationPageUrl.isTrusted(url)) {
            state.message = "暂无可用验证入口，请重试原请求"
            return
        }
        loginBusy = true
        try {
            login.launch(
                Intent(this, LoginActivity::class.java)
                    .putExtra("manual_verification", verify)
                    .putExtra("verification_url", if (verify) url else "")
            )
        } catch (e: Exception) {
            loginBusy = false
            state.message = e.message.orEmpty()
        }
    }

    internal fun chat(id: String, name: String) {
        if (id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            startActivity(
                Intent(this, PrivateChatActivity::class.java)
                    .putExtra("id", id)
                    .putExtra("name", name.take(80))
            )
    }

    internal fun logout() {
        lifecycleScope.launch {
            creatorStats?.cancel()
            runCatching { state.call("logout") }
                .onSuccess {
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                    state.refreshSession()
                }
                .onFailure { state.message = it.message.orEmpty() }
        }
    }

    override fun onDestroy() {
        creatorStats?.cancel()
        super.onDestroy()
    }
}
