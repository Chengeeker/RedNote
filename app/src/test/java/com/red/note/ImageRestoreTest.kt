package com.red.note

import android.graphics.Bitmap
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageRestoreTest {
    private val app get() = (RuntimeEnvironment.getApplication() as RedApplication)

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun loader(directory: File, status: (String) -> Int): ImageLoader {
        val bytes = png()
        return app.newImageLoader().newBuilder()
            .memoryCache(null)
            .diskCache { DiskCache.Builder().directory(directory).maxSizeBytes(1024 * 1024).build() }
            .okHttpClient(
                OkHttpClient.Builder().addInterceptor { chain ->
                    val code = status(chain.request().url.toString())
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(code).message("fixture").header("Cache-Control", "no-store")
                        .body(bytes.toResponseBody("image/png".toMediaType())).build()
                }.build()
            ).build()
    }

    private fun request(url: String) = ImageRequest.Builder(app).data(url)
        .memoryCachePolicy(CachePolicy.DISABLED).size(32).build()

    private fun execute(loader: ImageLoader, url: String): ImageResult {
        val result = CompletableFuture.supplyAsync {
            runBlocking { loader.execute(request(url)) }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!result.isDone && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        return result.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun savedPhotoAndVideoCoversDecodeAfterLoaderAndFeedRecreation() {
        val directory = File(app.cacheDir, "restore-${System.nanoTime()}")
        var networkRequests = 0
        var offline = false
        val transport: (String) -> Int = {
            networkRequests++
            if (offline) 503 else 200
        }
        val items = JSONArray()
        for (type in listOf("photo", "video")) {
            val raw = JSONObject().put("title", type)
                .put("cover", JSONObject().put("url_default", "https://a.xhscdn.com/$type.jpg"))
            if (type == "video") raw.put("video", JSONObject())
            items.put(JSONObject().put("id", type).put("note_card", raw))
        }
        val prefs = Preferences(app)
        prefs.set("homeSnapshot:fixture", JSONObject().put("items", items).toString())
        fun restore() = JSONObject(Preferences(app).text("homeSnapshot:fixture"))
            .rows("items").map { Note.parse(it, "pc_feed")!! }

        val first = loader(directory, transport)
        try {
            restore().forEach { assertTrue(execute(first, it.cover) is SuccessResult) }
        } finally {
            first.shutdown()
        }
        offline = true
        val second = first.newBuilder().build()
        try {
            val notes = restore()
            assertTrue(notes.last().raw.has("video"))
            notes.forEach {
                val result = execute(second, it.cover)
                assertTrue(result.toString(), result is SuccessResult)
                assertEquals(DataSource.DISK, (result as SuccessResult).dataSource)
            }
            assertEquals(2, networkRequests)
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun expiredCoverUsesStableImageAndReusesItsCacheUnderTheSavedUrl() {
        val signed = "https://sns-webpic-a.xhscdn.com/123456789012/0123456789abcdef0123456789abcdef/photo!format"
        val visited = mutableListOf<String>()
        val loader = loader(File(app.cacheDir, "expired-${System.nanoTime()}")) {
            visited += it
            if (it == signed) 403 else 200
        }
        try {
            assertTrue(execute(loader, signed) is SuccessResult)
            assertEquals(
                listOf(signed, "https://sns-img-bd.xhscdn.com/photo"),
                visited,
            )
            val cached = execute(loader, signed) as SuccessResult
            assertEquals(DataSource.DISK, cached.dataSource)
            assertEquals(2, visited.size)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun transientCoverFailuresRetryWithinThreeAttempts() {
        var attempts = 0
        val loader = loader(File(app.cacheDir, "retry-${System.nanoTime()}")) {
            if (++attempts < 3) 503 else 200
        }
        try {
            assertTrue(execute(loader, "https://a.xhscdn.com/retry.jpg") is SuccessResult)
            assertEquals(3, attempts)
        } finally {
            loader.shutdown()
        }
    }
}
