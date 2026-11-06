package com.red.note

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
internal fun GlassDecoration(
    state: RedState,
    modifier: Modifier = Modifier,
    kind: Int = 3,
    headerTint: Color = Color.Transparent,
    touch: Float = 0f,
) {
    val backdrop = LocalGlassBackdrop.current ?: return
    var gpuFailed by remember { mutableStateOf(false) }
    if (android.os.Build.VERSION.SDK_INT >= 33 && kind in listOf(1, 3) && !gpuFailed) {
        GlassDecorationGpu(
            backdrop,
            modifier,
            kind,
            androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < .5f,
            if (kind == 1) Color.Black else LocalScaffoldColor.current,
            touch,
            onFailure = { gpuFailed = true },
        )
        return
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { GlassView(context) }
    val crop = rememberGraphicsLayer()
    val latestTouch by rememberUpdatedState(touch)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var visible by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var failed by remember { mutableStateOf(false) }
    val sceneColor = if (kind == 1 || kind == 2) Color.Black else LocalScaffoldColor.current
    val dark = androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < .5f
    DisposableEffect(view) {
        view.failure = {
            failed = true
            state.message = "玻璃渲染失败：$it"
        }
        onDispose { view.close() }
    }
    LaunchedEffect(view, size, coords, dark, kind, failed, sceneColor, headerTint, backdrop) {
        var previous = -1L
        var lastRead = 0L
        var lastPosition = Offset.Unspecified
        var lastExtent = Offset.Unspecified
        var generation = -1
        var previousTouch = -1f
        while (isActive && !failed) {
            val frame = withFrameNanos { it }
            val coordinates = coords
            if (
                !view.ready ||
                    size.width == 0 ||
                    coordinates?.isAttached != true ||
                    !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            )
                continue
            val position = coordinates.positionInRoot()
            val end = coordinates.localToRoot(Offset(size.width.toFloat(), size.height.toFloat()))
            val extent = end - position
            if (extent.x <= 0f || extent.y <= 0f) continue
            val currentGeneration = view.surfaceGeneration
            val read =
                generation != currentGeneration ||
                    frame - lastRead >= 32_000_000L &&
                        (previous != backdrop.revision ||
                            lastPosition != position ||
                            lastExtent != extent)
            val currentTouch = latestTouch
            if (!read && previousTouch == currentTouch) continue
            val revision = backdrop.revision
            if (read) {
                val divisor = if (kind == 4) 3 else 2
                val sampled =
                    IntSize(
                        (size.width + divisor - 1) / divisor,
                        (size.height + divisor - 1) / divisor,
                    )
                val tintHeight =
                    (size.height - with(density) { 60.dp.roundToPx() }) * sampled.height.toFloat() /
                        size.height
                crop.record(density, direction, sampled) {
                    drawRect(sceneColor)
                    scale(sampled.width / extent.x, sampled.height / extent.y, Offset.Zero) {
                        translate(
                            -position.x + backdrop.origin.x,
                            -position.y + backdrop.origin.y,
                        ) {
                            drawLayer(backdrop.layer)
                        }
                    }
                    if (kind == 4)
                        drawRect(headerTint, size = Size(sampled.width.toFloat(), tintHeight))
                }
                lastRead = frame
            }
            var bitmap: Bitmap? = null
            try {
                if (read) bitmap = crop.readGlassBitmap()
                val done = CompletableDeferred<Boolean>()
                view.render(
                    bitmap,
                    null,
                    0f,
                    0f,
                    dark,
                    density.density,
                    kind,
                    generation = currentGeneration,
                    outputSize = size,
                    touch = currentTouch,
                ) {
                    done.complete(it)
                }
                bitmap = null
                if (done.await()) {
                    visible = true
                    generation = currentGeneration
                    previousTouch = currentTouch
                    if (read) {
                        previous = revision
                        lastPosition = position
                        lastExtent = extent
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed = true
                state.message = "玻璃背景读取失败"
            } finally {
                bitmap?.recycle()
            }
        }
    }
    BoxWithConstraints(modifier) {
        val layout =
            if (kind == 4)
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(maxWidth, maxHeight + 60.dp)
            else Modifier.requiredSize(maxWidth + 40.dp, maxHeight + 30.dp).align(Alignment.Center)
        AndroidView(
            factory = { view },
            modifier =
                layout.onGloballyPositioned {
                    size = it.size
                    coords = it
                },
        )
        if (kind in 1..2 && (!visible || failed)) {
            Box(
                Modifier.align(Alignment.Center)
                    .size(if (kind == 1) 48.dp else 44.dp)
                    .background(Color.Black.copy(alpha = .4f), CircleShape)
                    .border(.5.dp, Color.White.copy(alpha = .24f), CircleShape)
            )
        }
    }
}
