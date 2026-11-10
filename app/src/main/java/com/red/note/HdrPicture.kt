package com.red.note

import android.graphics.*
import android.os.Build
import android.view.*
import android.widget.ImageView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.red.note.media.JpegGainmapRepair
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private val imageSlots = Semaphore(2)
private val downloadSlots = Semaphore(2)
private val downloadLocks = Array(32) { Mutex() }

internal suspend fun originalImageFile(context: android.content.Context, url: String): File =
    checkNotNull(loadOriginalImageFile(context, url, inlineHdr = false))

internal suspend fun prepareInlineHdr(context: android.content.Context, url: String): Boolean? {
    if (Build.VERSION.SDK_INT < 34 || !MediaRemuxer.trustedUrl(url)) return null
    val file = loadOriginalImageFile(context, url, inlineHdr = true) ?: return false
    val bitmap = originalBitmap(file)
    return try {
        bitmap.hasGainmap()
    } finally {
        bitmap.recycle()
    }
}

private suspend fun loadOriginalImageFile(
    context: android.content.Context,
    url: String,
    inlineHdr: Boolean,
): File? =
    withContext(Dispatchers.IO) {
        val maxBytes = (if (inlineHdr) 16L else 64L) * 1024 * 1024
        downloadLocks[(url.hashCode() and Int.MAX_VALUE) % downloadLocks.size].withLock {
            downloadSlots.withPermit {
                require(MediaRemuxer.trustedUrl(url)) { "图片地址无效" }
                val folder =
                    File(context.cacheDir, "image_cache").apply { check(isDirectory || mkdirs()) }
                val hash =
                    MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(
                        ""
                    ) {
                        "%02x".format(it)
                    }
                val file = File(folder, hash)
                if (!file.isFile) {
                    var temp: File? = null
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = if (inlineHdr) 4000 else 15000
                        conn.readTimeout = if (inlineHdr) 4000 else 30000
                        conn.instanceFollowRedirects = false
                        mediaHeaders(context, url, true).forEach { (key, value) ->
                            conn.setRequestProperty(key, value)
                        }
                        require(conn.responseCode == 200) { "图片下载失败" }
                        if (inlineHdr && conn.contentLengthLong > maxBytes) return@withPermit null
                        require(conn.contentLengthLong <= maxBytes) { "图片过大" }
                        conn.inputStream.buffered().use { input ->
                            if (inlineHdr) {
                                input.mark(INLINE_HDR_HEADER_BYTES)
                                if (!hasGainMapHeader(input)) return@withPermit null
                                input.reset()
                                currentCoroutineContext().ensureActive()
                            }
                            val staging = File.createTempFile("image-", ".tmp", folder)
                            temp = staging
                            staging.outputStream().use { output ->
                                val buf = ByteArray(65536)
                                var count = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    count += n
                                    require(count <= maxBytes) { "图片过大" }
                                    output.write(buf, 0, n)
                                }
                                require(count > 0) { "图片为空" }
                            }
                            check(staging.renameTo(file)) { "图片缓存写入失败" }
                        }
                    } finally {
                        conn.disconnect()
                        temp?.delete()
                    }
                }
                if (inlineHdr && file.length() !in 1..maxBytes) return@withPermit null
                require(file.length() in 1..maxBytes) { "图片过大" }
                file.setLastModified(System.currentTimeMillis())
                file
            }
        }
    }

private suspend fun originalBitmap(file: File): Bitmap =
    withContext(Dispatchers.IO) {
        imageSlots.withPermit {
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    fun decode(source: ImageDecoder.Source) =
                        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                            val budget =
                                min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8) / 18.0
                            val pixels = info.size.width.toDouble() * info.size.height
                            if (pixels > budget) {
                                val ratio = sqrt(budget / pixels)
                                decoder.setTargetSize(
                                    max(1, (info.size.width * ratio).toInt()),
                                    max(1, (info.size.height * ratio).toInt()),
                                )
                            }
                        }
                    // The retained Flutter decoder uses a byte source for repairable JPEGs.
                    val bytes = if (file.length() <= 16L * 1024 * 1024) file.readBytes() else null
                    val bitmap =
                        decode(
                            if (bytes != null) ImageDecoder.createSource(bytes)
                            else ImageDecoder.createSource(file)
                        )
                    if (
                        Build.VERSION.SDK_INT >= 34 &&
                            !bitmap.hasGainmap() &&
                            file.length() <= 16L * 1024 * 1024
                    ) {
                        val repaired = JpegGainmapRepair.repair(checkNotNull(bytes))
                        if (repaired != null) {
                            val alt =
                                runCatching { decode(ImageDecoder.createSource(repaired)) }
                                    .getOrNull()
                            if (alt != null && alt.hasGainmap()) {
                                bitmap.recycle()
                                return@withPermit alt
                            } else alt?.recycle()
                        }
                    }
                    bitmap
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, options)
                    options.inSampleSize = 1
                    while (
                        options.outWidth.toLong() * options.outHeight /
                            (options.inSampleSize.toLong() * options.inSampleSize) > 4_000_000
                    ) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeFile(file.path, options) ?: error("图片解码失败")
                }
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("图片过大，内存不足，请关闭其他图片后重试", e)
            } catch (e: Exception) {
                if (e !is CancellationException) file.delete()
                throw e
            }
        }
    }

@Composable
internal fun HdrPicture(
    url: String,
    modifier: Modifier = Modifier,
    onTap: (Float) -> Unit = {},
    onDecoded: (Boolean) -> Unit = {},
    onZoom: (Boolean) -> Unit = {},
    onKind: (String) -> Unit = {},
    onPlane: (GalleryPlane) -> Unit = {},
    loadEnabled: Boolean = true,
    showProgress: Boolean = true,
) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var drawable by remember(url) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    var error by remember(url) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(url, retry, loadEnabled) {
        if (!loadEnabled || drawable != null) return@LaunchedEffect
        try {
            val file = originalImageFile(context, url)
            val animated =
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        val header = ByteArray(32)
                        val length = input.read(header)
                        length >= 6 && String(header, 0, 3) == "GIF" ||
                            length >= 21 &&
                                String(header, 8, 4) == "WEBP" &&
                                (header[20].toInt() and 2) != 0
                    }
                }
            drawable =
                if (animated) {
                    val result =
                        coil.Coil.imageLoader(context)
                            .execute(
                                coil.request.ImageRequest.Builder(context)
                                    .data(file)
                                    .size(2048)
                                    .build()
                            )
                    (result as? coil.request.SuccessResult)?.drawable ?: error("图片解码失败")
                } else
                    android.graphics.drawable.BitmapDrawable(
                        context.resources,
                        originalBitmap(file),
                    )
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            onDecoded(Build.VERSION.SDK_INT >= 34 && bitmap?.hasGainmap() == true)
            onKind(
                if (animated) "GIF"
                else if (drawable!!.intrinsicHeight > drawable!!.intrinsicWidth * 2) "长图" else ""
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message.orEmpty()
        }
    }
    val currentTap by rememberUpdatedState(onTap)
    val currentZoom by rememberUpdatedState(onZoom)
    val currentPlane by rememberUpdatedState(onPlane)
    val hapticsEnabled = LocalHapticsEnabled.current
    DisposableEffect(drawable, owner) {
        val animation = drawable as? android.graphics.drawable.Animatable
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            animation?.start()
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) animation?.start()
                else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) animation?.stop()
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            animation?.stop()
        }
    }
    if (drawable != null)
        AndroidView(
            factory = { ctx ->
                ZoomImage(ctx, { currentTap(it) }, { currentZoom(it) }, { currentPlane(it) })
            },
            update = {
                it.hapticsEnabled = hapticsEnabled
                it.setPicture(drawable!!)
            },
            onRelease = { it.release() },
            modifier = modifier,
        )
    else if (error.isNotEmpty())
        Failure(error) {
            error = ""
            retry++
        }
    else if (showProgress && loadEnabled) androidx.compose.material3.CircularProgressIndicator()
}

internal class ZoomImage(
    context: android.content.Context,
    val tapped: (Float) -> Unit,
    val zoomed: (Boolean) -> Unit,
    val planeChanged: (GalleryPlane) -> Unit,
) : ImageView(context) {
    var hapticsEnabled = true
    private var image: android.graphics.drawable.Drawable? = null
    private var animation: android.animation.ValueAnimator? = null
    private var doubleTapTarget: Float? = null
    private var zoom = 1f
    private var dx = 0f
    private var dy = 0f
    private var flingStarted = -1L
    private var flingX = 0f
    private var flingY = 0f
    private var flingTravelX = 0f
    private var flingTravelY = 0f
    private val scaler =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    flingStarted = -1L
                    animation?.cancel()
                    doubleTapTarget = null
                    setZoom(
                        (zoom * detector.scaleFactor).coerceIn(1f, 8f),
                        detector.focusX,
                        detector.focusY,
                    )
                    return true
                }
            },
        )
    private val taps =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                private var secondTapDown = false

                override fun onDown(e: MotionEvent): Boolean {
                    flingStarted = -1L
                    if (!secondTapDown && hapticsEnabled && image != null) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    secondTapDown = false
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    doubleTapTarget = null
                    tapped(e.x / width.coerceAtLeast(1))
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // GestureDetector calls onDoubleTap before onDown for the second press.
                    secondTapDown = true
                    val picture = image ?: return false
                    val scale = doubleTapTarget ?: zoom
                    val next = when {
                        scale <= 1.01f ->
                            if (picture.intrinsicHeight > picture.intrinsicWidth * 2) 3.5f else 2.5f
                        scale <= 3.6f -> 6f
                        else -> 1f
                    }
                    doubleTapTarget = next
                    val focusX = e.x
                    val focusY = e.y
                    animation?.cancel()
                    animation =
                        android.animation.ValueAnimator.ofFloat(zoom, next).apply {
                            duration = 260
                            interpolator =
                                android.view.animation.PathInterpolator(.215f, .61f, .355f, 1f)
                            addUpdateListener { setZoom(it.animatedValue as Float, focusX, focusY) }
                            start()
                        }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (zoom <= 1f) return false
                    doubleTapTarget = null
                    dx -= distanceX
                    dy -= distanceY
                    applyMatrix()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (image == null) return false
                    if (zoom <= 1f || width == 0 || height == 0) return false
                    val magnitude = hypot(velocityX, velocityY)
                    val density = resources.displayMetrics.density
                    if (magnitude < 400f * density) return false
                    flingX = dx
                    flingY = dy
                    flingTravelX = velocityX / magnitude * 120f * density
                    flingTravelY = velocityY / magnitude * 120f * density
                    flingStarted = android.os.SystemClock.uptimeMillis()
                    postInvalidateOnAnimation()
                    return true
                }
            },
        )

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        scaleType = ScaleType.MATRIX
        contentDescription = "图片，双击或双指缩放"
        isClickable = true
        isFocusable = true
    }

    override fun performClick(): Boolean {
        super.performClick()
        tapped(.5f)
        return true
    }

    private fun setZoom(value: Float, x: Float, y: Float) {
        val factor = value / zoom
        dx = (dx - (x - width / 2f)) * factor + (x - width / 2f)
        dy = (dy - (y - height / 2f)) * factor + (y - height / 2f)
        zoom = value
        zoomed(zoom > 1.001f)
        applyMatrix()
    }

    fun setPicture(value: android.graphics.drawable.Drawable) {
        if (image === value) return
        flingStarted = -1L
        animation?.cancel()
        image = value
        doubleTapTarget = null
        zoom = 1f
        dx = 0f
        dy = 0f
        setImageDrawable(value)
        applyMatrix()
    }

    fun release() {
        flingStarted = -1L
        animation?.cancel()
        doubleTapTarget = null
        (image as? android.graphics.drawable.Animatable)?.stop()
        setImageDrawable(null)
        image = null
    }

    override fun onDetachedFromWindow() {
        flingStarted = -1L
        animation?.cancel()
        doubleTapTarget = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMatrix()
    }

    override fun computeScroll() {
        if (flingStarted >= 0) {
            val progress =
                galleryPanProgress((android.os.SystemClock.uptimeMillis() - flingStarted) / 1000f)
            dx = flingX + flingTravelX * progress
            dy = flingY + flingTravelY * progress
            applyMatrix()
            if (progress >= 1f) flingStarted = -1L else postInvalidateOnAnimation()
        }
    }

    private fun applyMatrix() {
        val b = image ?: return
        if (width == 0 || height == 0 || b.intrinsicWidth <= 0 || b.intrinsicHeight <= 0) return
        val fit = min(width.toFloat() / b.intrinsicWidth, height.toFloat() / b.intrinsicHeight)
        val scaled = fit * zoom
        val maxX = max(0f, (b.intrinsicWidth * scaled - width) / 2)
        val maxY = max(0f, (b.intrinsicHeight * scaled - height) / 2)
        dx = dx.coerceIn(-maxX, maxX)
        dy = dy.coerceIn(-maxY, maxY)
        val left = (width - b.intrinsicWidth * scaled) / 2 + dx
        val top = (height - b.intrinsicHeight * scaled) / 2 + dy
        imageMatrix =
            Matrix().apply {
                postScale(scaled, scaled)
                postTranslate(left, top)
            }
        (b as? android.graphics.drawable.BitmapDrawable)?.let {
            planeChanged(
                GalleryPlane(
                    it.bitmap.asImageBitmap(),
                    androidx.compose.ui.geometry.Rect(
                        left,
                        top,
                        left + b.intrinsicWidth * scaled,
                        top + b.intrinsicHeight * scaled,
                    ),
                )
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) animation?.cancel()
        parent?.requestDisallowInterceptTouchEvent(zoom > 1f || event.pointerCount > 1)
        scaler.onTouchEvent(event)
        taps.onTouchEvent(event)
        return true
    }
}

// ExtendedImage GestureAnimation: AnimationController.fling(.4), m=1, k=500, critical damping.
internal fun galleryPanProgress(seconds: Float): Float {
    val t = seconds.coerceAtLeast(0f).toDouble()
    val rate = sqrt(500.0)
    return (1.01 + (-1.01 + (.4 - rate * 1.01) * t) * exp(-rate * t)).toFloat().coerceIn(0f, 1f)
}
