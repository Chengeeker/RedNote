package com.red.note

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.red.note.media.JpegGainmapRepair
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.File
import java.util.concurrent.Executors
import java.security.MessageDigest
import kotlin.math.sqrt

private val hdrImageLoader = Executors.newFixedThreadPool(2)
private val hdrImageMain = Handler(Looper.getMainLooper())
private val hdrCacheBudget = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()

private fun cachedImage(context: Context, url: String): File {
    val key = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return File(context.cacheDir, "cacheimage/$key")
}

private fun decodeHdrBitmap(source: ImageDecoder.Source): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        Log.i("RedNoteHDR", "source mime=${info.mimeType} size=${info.size.width}x${info.size.height}")
        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        val pixels = info.size.width.toDouble() * info.size.height
        // Leave row-stride headroom so the current and next base/gain-map pairs fit together.
        val budget = hdrCacheBudget / 18.0
        if (pixels > budget) {
            val scale = sqrt(budget / pixels)
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
        }
    }

private fun decodeHdrBytes(bytes: ByteArray): Bitmap {
    val bitmap = decodeHdrBitmap(ImageDecoder.createSource(bytes))
    if (Build.VERSION.SDK_INT < 34 || bitmap.hasGainmap()) return bitmap
    val repaired = JpegGainmapRepair.repair(bytes) ?: return bitmap
    val alternate = try { decodeHdrBitmap(ImageDecoder.createSource(repaired)) }
        catch (_: Exception) { return bitmap }
    Log.i("RedNoteHDR", "repaired MPF gainMap=${alternate.hasGainmap()}")
    if (alternate.hasGainmap()) {
        bitmap.recycle()
        return alternate
    }
    alternate.recycle()
    return bitmap
}

private fun decodeHdrFile(file: File): Bitmap =
    if (file.length() <= 16L * 1024 * 1024) decodeHdrBytes(file.readBytes())
    else decodeHdrBitmap(ImageDecoder.createSource(file))

internal class HdrImageViewFactory(
    private val channel: MethodChannel,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    // Shared by this Activity's gallery views, never persisted to disk.
    private val bitmaps = object : LruCache<String, Bitmap>(hdrCacheBudget) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            val gainMapBytes = if (Build.VERSION.SDK_INT >= 34 && value.hasGainmap()) {
                value.gainmap?.gainmapContents?.allocationByteCount ?: 0
            } else 0
            return value.allocationByteCount + gainMapBytes
        }
    }

    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    private fun loadCached(context: Context, url: String, complete: (Bitmap?) -> Unit) {
        bitmaps.get(url)?.let { bitmap -> hdrImageMain.post { complete(bitmap) }; return }
        synchronized(pending) {
            pending[url]?.let { it.add(complete); return }
            pending[url] = mutableListOf(complete)
        }
        hdrImageLoader.execute {
            val bitmap = try {
                val file = cachedImage(context, url)
                if (!file.isFile || file.length() !in 1..64L * 1024 * 1024) null
                else decodeHdrFile(file).also { decoded ->
                    if (Build.VERSION.SDK_INT >= 34 && decoded.hasGainmap()) bitmaps.put(url, decoded)
                    Log.i("RedNoteHDR", "cached gainMap=${decoded.hasGainmap()} size=${decoded.width}x${decoded.height}")
                }
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            val callbacks = synchronized(pending) { pending.remove(url).orEmpty() }
            hdrImageMain.post { callbacks.forEach { it(bitmap) } }
        }
    }

    fun probeCachedImage(context: Context, url: String, complete: (Boolean?) -> Unit) {
        if (Build.VERSION.SDK_INT < 34) { complete(false); return }
        loadCached(context, url) { bitmap -> complete(bitmap?.hasGainmap()) }
    }

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *> ?: emptyMap<Any, Any>()
        val url = params["url"] as? String ?: ""
        return HdrImagePlatformView(context, url, channel) { complete -> loadCached(context, url, complete) }
    }
}

private class HdrImagePlatformView(
    context: Context,
    private val url: String,
    private val channel: MethodChannel,
    loadBitmap: ((Bitmap?) -> Unit) -> Unit,
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
                Log.i("RedNoteHDR", "gainMap=true hardwareCanvas=${canvas.isHardwareAccelerated} hdrSdrRatio=$ratio")
            }
        }
    }.apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }
    @Volatile private var disposed = false
    private var reveal: ValueAnimator? = null
    init {
        loadBitmap { bitmap ->
            if (!disposed) {
                if (bitmap == null) reportFailure() else showBitmap(bitmap)
            }
        }
    }

    override fun getView(): View = imageView

    private fun reportFailure() {
        hdrImageMain.post {
            if (!disposed) {
                channel.invokeMethod(
                    "hdrImageDecodeFailed",
                    mapOf("url" to url),
                )
            }
        }
    }

    private fun showBitmap(bitmap: Bitmap) {
        val hasGainMap = Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()
        hdrImageMain.post {
            if (disposed) return@post
            decodedGainMap = hasGainMap
            Log.i("RedNoteHDR", "decoded gainMap=$hasGainMap size=${bitmap.width}x${bitmap.height}")
            reveal?.cancel()
            imageView.imageAlpha = if (hasGainMap) 0 else 255
            imageView.setImageBitmap(bitmap)
            channel.invokeMethod(
                "hdrImageDecoded",
                mapOf("url" to url, "hasGainMap" to hasGainMap),
            )
            if (hasGainMap) {
                // Blend on the HDR-capable Canvas, not a Flutter SDR opacity layer.
                reveal = ValueAnimator.ofInt(0, 255).apply {
                    duration = 500
                    interpolator = LinearInterpolator()
                    addUpdateListener { imageView.imageAlpha = it.animatedValue as Int }
                    start()
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
        reveal?.cancel()
        imageView.setImageDrawable(null)
    }

}
