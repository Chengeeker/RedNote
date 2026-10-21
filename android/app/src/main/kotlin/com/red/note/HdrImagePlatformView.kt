package com.red.note

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal class HdrImageViewFactory(
    private val channel: MethodChannel,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    // Shared by this Activity's gallery views, never persisted to disk.
    private val bitmaps = object : LruCache<Pair<String, Map<String, String>>, Bitmap>(
        minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt(),
    ) {
        override fun sizeOf(key: Pair<String, Map<String, String>>, value: Bitmap): Int {
            val gainMapBytes = if (Build.VERSION.SDK_INT >= 34 && value.hasGainmap()) {
                value.gainmap?.gainmapContents?.allocationByteCount ?: 0
            } else 0
            return value.allocationByteCount + gainMapBytes
        }
    }

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *> ?: emptyMap<Any, Any>()
        val url = params["url"] as? String ?: ""
        val headers = (params["headers"] as? Map<*, *>)
            ?.mapNotNull { (key, value) ->
                if (key is String && value is String) key to value else null
            }
            ?.toMap()
            .orEmpty()
        return HdrImagePlatformView(context, url, headers, channel, bitmaps)
    }
}

private class HdrImagePlatformView(
    context: Context,
    private val url: String,
    private val headers: Map<String, String>,
    private val channel: MethodChannel,
    private val bitmaps: LruCache<Pair<String, Map<String, String>>, Bitmap>,
) : PlatformView {
    private var decodedGainMap = false
    private var reportedDraw = false
    private var reportedHdrOutput = false
    private val imageView = object : ImageView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (Build.VERSION.SDK_INT < 34 || !decodedGainMap) return
            val currentDisplay = display
            val ratio = if (currentDisplay?.isHdrSdrRatioAvailable == true) {
                currentDisplay.hdrSdrRatio
            } else 1.0f
            if (!reportedDraw || (!reportedHdrOutput && ratio > 1.0f)) {
                reportedDraw = true
                reportedHdrOutput = ratio > 1.0f
                Log.i("GreennoteHDR", "gainMap=true hardwareCanvas=${canvas.isHardwareAccelerated} hdrSdrRatio=$ratio")
            }
        }
    }.apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
    @Volatile private var disposed = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val loadTask = loader.submit { loadImage() }

    override fun getView(): View = imageView

    private fun loadImage() {
        var connection: HttpURLConnection? = null
        try {
            if (disposed) return
            val cacheKey = url to headers
            bitmaps.get(cacheKey)?.let {
                showBitmap(it)
                return
            }
            val uri = Uri.parse(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            connection = URL(url).openConnection() as HttpURLConnection
            activeConnection = connection
            if (disposed) return
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "image/*")
            headers.forEach { (key, value) ->
                if ('\r' !in key && '\n' !in key && '\r' !in value && '\n' !in value) {
                    connection.setRequestProperty(key, value)
                }
            }
            // Keep JPEG Ultra HDR gain maps; shared image headers prefer AVIF/WebP variants.
            connection.setRequestProperty("Accept", "image/jpeg,image/*;q=0.8,*/*;q=0.5")
            connection.connect()
            check(connection.responseCode in 200..299) { "Image request failed" }
            check(connection.contentLengthLong <= maxImageBytes) { "Image is too large" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    if (disposed || Thread.currentThread().isInterrupted) return
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= maxImageBytes) { "Image is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                    decoder.isMutableRequired = false
                }
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("Image decode failed")
            }
            if (disposed) return
            // Do not recycle on eviction: a visible ImageView may still own it.
            bitmaps.put(cacheKey, bitmap)
            showBitmap(bitmap)
        } catch (_: Exception) {
            mainHandler.post {
                if (!disposed) {
                    channel.invokeMethod(
                        "hdrImageDecodeFailed",
                        mapOf("url" to url),
                    )
                }
            }
        } finally {
            connection?.disconnect()
            activeConnection = null
        }
    }

    private fun showBitmap(bitmap: Bitmap) {
        val hasGainMap = Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()
        mainHandler.post {
            if (disposed) return@post
            decodedGainMap = hasGainMap
            Log.i("GreennoteHDR", "decoded gainMap=$hasGainMap size=${bitmap.width}x${bitmap.height}")
            imageView.setImageBitmap(bitmap)
            channel.invokeMethod(
                "hdrImageDecoded",
                mapOf("url" to url, "hasGainMap" to hasGainMap),
            )
        }
    }

    override fun dispose() {
        disposed = true
        loadTask.cancel(true)
        activeConnection?.disconnect()
        imageView.setImageDrawable(null)
    }

    companion object {
        private const val maxImageBytes = 64L * 1024 * 1024
        private val mainHandler = Handler(Looper.getMainLooper())
        private val loader = Executors.newFixedThreadPool(2)
    }
}
