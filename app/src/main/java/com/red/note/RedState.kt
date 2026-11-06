package com.red.note

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class JsonMemory(initialPage: Int) {
    var rows by mutableStateOf(emptyList<JSONObject>())
    var cursor by mutableStateOf("")
    var page by mutableIntStateOf(initialPage)
    var more by mutableStateOf(true)
    var loaded = false
    var index = 0
    var offset = 0
}

internal class FeedMemory {
    val imageOrigins = mutableMapOf<String, ImageOrigin>()
    var initialized = false
    var notes by mutableStateOf(emptyList<Note>())
    var cursor by mutableStateOf("")
    var sessionId by mutableStateOf("")
    var page by mutableIntStateOf(1)
    var more by mutableStateOf(true)
    var index = 0
    var offset = 0
}

internal class AccountProfileMemory {
    var profile by mutableStateOf<JSONObject?>(null)
    var failure by mutableStateOf("")
}

internal class RedState(app: Application) : AndroidViewModel(app) {
    val runtime = ProtocolRuntime.get(app)
    var session by mutableStateOf(JSONObject())
    var epoch by mutableIntStateOf(0)
    var message by mutableStateOf("")
    val preferences = Preferences(app)
    val imagePages = mutableStateMapOf<Note, Int>()
    var accountProfile = AccountProfileMemory()
        private set

    val hdrImages = mutableStateMapOf<String, Boolean>()
    private val hdrProbeLocks = Array(16) { Mutex() }

    suspend fun inspectHdr(url: String, retry: Boolean = false) {
        if (hdrImages.containsKey(url)) return
        hdrProbeLocks[(url.hashCode() and Int.MAX_VALUE) % hdrProbeLocks.size].withLock {
            if (hdrImages.containsKey(url)) return@withLock
            repeat(if (retry) 2 else 1) { attempt ->
                try {
                    prepareInlineHdr(getApplication(), url)?.let {
                        if (hdrImages[url] != true) hdrImages[url] = it
                    }
                    return@withLock
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (retry && attempt == 0) delay(800)
                }
            }
        }
    }

    val feeds = LinkedHashMap<String, FeedMemory>()
    var scrollToTop by mutableIntStateOf(0)
    var sessionReady by mutableStateOf(false)

    fun feed(key: String): FeedMemory =
        feeds.getOrPut(key) {
            if (feeds.size >= 24) feeds.remove(feeds.keys.first())
            FeedMemory()
        }

    val theme
        get() = listOf("system", "light", "dark")[preferences.int("theme").coerceIn(0, 2)]

    fun changeTheme(value: String) {
        preferences.set("theme", listOf("system", "light", "dark").indexOf(value).coerceAtLeast(0))
    }

    val commentValues = mutableStateMapOf<String, JSONObject>()
    val deletedComments = mutableStateListOf<String>()
    var lastFailure by mutableStateOf<ApiFailure?>(null)
    val jsonLists = LinkedHashMap<String, JsonMemory>()
    var openingDetail: Pair<Note, GalleryFlight>? = null
    val details = LinkedHashMap<Note, DetailMemory>()
    val interactions = mutableStateMapOf<String, JSONObject>()
    val mutations = mutableStateListOf<String>()

    fun interaction(note: Note): JSONObject {
        val base = note.raw.optJSONObject("interact_info")
        val patch = interactions[note.id]
        return when {
            patch == null -> base ?: JSONObject()
            base == null -> patch
            else -> mergeJson(base, patch)
        }
    }

    fun adjustComments(note: Note, delta: Int) {
        val count = interaction(note).optString("comment_count").toIntOrNull() ?: 0
        interactions[note.id] =
            mergeJson(
                interactions[note.id] ?: JSONObject(),
                JSONObject().put("comment_count", (count + delta).coerceAtLeast(0)),
            )
    }

    suspend fun toggle(note: Note, method: String) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val request = "$epoch:${note.id}:$method"
            if (request in mutations) return@withContext
            val before = interactions[note.id] ?: JSONObject()
            val patch = interactionPatch(interaction(note), method)
            val field = if (method == "likeNote") "liked" else "collected"
            val generation = epoch
            mutations.add(request)
            interactions[note.id] = mergeJson(before, patch)
            try {
                call(method, note.args + ("value" to patch.getBoolean(field)))
            } catch (e: Exception) {
                if (epoch == generation)
                    interactions[note.id] =
                        rollbackPatch(interactions[note.id] ?: JSONObject(), before, patch)
                throw e
            } finally {
                mutations.remove(request)
            }
        }

    val loggedIn
        get() = session.optBoolean("loggedIn")

    val userId
        get() =
            session
                .optJSONObject("user")
                ?.let { it.optString("user_id", it.optString("userid")) }
                .orEmpty()

    suspend fun call(method: String, args: Map<String, Any?> = emptyMap()): JSONObject {
        val generation = epoch
        val account = runtime.active
        val result =
            try {
                suspendCancellableCoroutine<JSONObject> { continuation ->
                    runtime.worker.execute {
                        if (!continuation.isActive) return@execute
                        if (runtime.active !== account) {
                            continuation.resumeWithException(CancellationException("账号已变化"))
                            return@execute
                        }
                        try {
                            val output = runtime.invoke(method, args)
                            val json =
                                when (output) {
                                    is JSONObject -> output
                                    is Map<*, *> -> JSONObject(output)
                                    else -> JSONObject()
                                }
                            if (continuation.isActive) continuation.resume(json)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            } catch (e: ApiFailure) {
                if (generation == epoch && runtime.active === account) lastFailure = e
                throw e
            }
        if (
            generation != epoch ||
                method !in listOf("session", "logout") && runtime.active !== account
        )
            throw CancellationException("账号已变化")
        return result
    }

    suspend fun refreshSession() {
        val before = userId
        val signedIn = loggedIn
        session = call("session")
        if (before != userId || signedIn != loggedIn) {
            epoch++
            jsonLists.clear()
            lastFailure = null
            interactions.clear()
            feeds.clear()
            accountProfile = AccountProfileMemory()
            imagePages.clear()
            commentValues.clear()
            deletedComments.clear()
            details.clear()
            openingDetail = null
        }
        sessionReady = true
    }
}
