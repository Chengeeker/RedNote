package com.red.note

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class GalleryAnchor(val viewport: Rect, val crop: Boolean = false)

internal data class GalleryPlane(val bitmap: ImageBitmap, val rect: Rect)

internal class GalleryFlight(
    val opening: ImageBitmap?,
    val anchor: (Int) -> GalleryAnchor?,
    initialIndex: Int,
) {
    var index by mutableIntStateOf(initialIndex)
    var viewport by mutableStateOf<Rect?>(null)
    var plane by mutableStateOf<GalleryPlane?>(null)
    var galleryPosition: (() -> Pair<Int, GalleryPlane?>)? = null
}

internal data class GalleryMotion(
    val page: Page,
    val progress: State<Float>,
    val returning: Boolean,
    val sourceDepth: Int,
) {
    val flight =
        when (page) {
            is Page.Gallery -> page.flight
            is Page.Detail -> page.flight
            else -> null
        }
    val scope =
        when (page) {
            is Page.Gallery -> page.heroScope
            is Page.Detail -> page.note
            else -> null
        }
    private val openingIndex = if (page is Page.Gallery) page.index else 0
    private val closingPage = if (returning) flight?.galleryPosition?.invoke() else null
    private val closingPlane =
        if (closingPage != null) closingPage.second else if (returning) flight?.plane else null
    private val closingViewport = if (returning) flight?.viewport else null
    val index = if (returning) closingPage?.first ?: flight?.index ?: openingIndex else openingIndex
    // An incoming LazyGrid can be laid out more than once while restoring its scroll.
    // Freeze the destination instead of chasing those temporary positions each frame.
    private val frozenAnchor = flight?.anchor?.invoke(index)
    private var openingViewport: Rect? = null
    val anchor
        get() = frozenAnchor

    val viewport: Rect?
        get() {
            if (returning) return closingViewport
            if (openingViewport == null)
                openingViewport = flight?.viewport?.takeIf { it.width > 0 && it.height > 0 }
            return openingViewport
        }

    val image =
        if (returning) closingPlane?.bitmap ?: flight?.opening?.takeIf { index == openingIndex }
        else flight?.opening
    val hasFlight
        get() = viewport != null && anchor != null && image != null

    val closingRect
        get() = closingPlane?.rect
}

internal class GalleryReversal(val motion: GalleryMotion) {
    val progress = Animatable(motion.progress.value)

    suspend fun settle(open: Boolean) {
        val end = motion.sourceDepth + if (open) 1f else 0f
        val duration = abs(progress.value - end) * if (open) 330 else 200
        progress.animateTo(end, tween(duration.roundToInt(), easing = LinearEasing))
    }
}

internal class ImageOrigin {
    var bitmap: ImageBitmap? = null
    var viewport: Rect? = null
}

internal val LocalGalleryMotion = staticCompositionLocalOf<GalleryMotion?> { null }

internal fun LayoutCoordinates.galleryViewport(): Rect = Rect(positionInRoot(), size.toSize())

internal fun imagePlane(bounds: Rect, image: Size, crop: Boolean): Rect {
    val sx = bounds.width / image.width
    val sy = bounds.height / image.height
    val scale = if (crop) maxOf(sx, sy) else minOf(sx, sy)
    val width = image.width * scale
    val height = image.height * scale
    return Rect(
        bounds.center.x - width / 2,
        bounds.center.y - height / 2,
        bounds.center.x + width / 2,
        bounds.center.y + height / 2,
    )
}

// Hero curves its rectangle independently of the route's linear opacity.
internal fun heroProgress(progress: Float, returning: Boolean): Float {
    val t = progress.coerceIn(0f, 1f)
    return if (returning) 1f - FastOutSlowInEasing.transform(1f - t)
    else FastOutSlowInEasing.transform(t)
}

@Composable
internal fun Modifier.hideGallerySource(scope: Any, index: Int): Modifier {
    val motion = LocalGalleryMotion.current ?: return this
    if (motion.scope != scope) return this
    return drawWithContent { if (!motion.hasFlight || motion.index != index) drawContent() }
}

@Composable
internal fun GalleryFlightOverlay(
    motion: GalleryMotion,
    modifier: Modifier,
    progressState: State<Float> = motion.progress,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.then(Modifier.onGloballyPositioned { origin = it.positionInRoot() })) {
        val start = motion.anchor ?: return@Canvas
        val end = motion.viewport ?: return@Canvas
        val image = motion.image ?: return@Canvas
        val imageSize = Size(image.width.toFloat(), image.height.toFloat())
        val first = imagePlane(start.viewport, imageSize, start.crop)
        val last =
            if (motion.returning)
                motion.closingRect?.translate(end.topLeft) ?: imagePlane(end, imageSize, false)
            else imagePlane(end, imageSize, false)
        val progress = heroProgress(progressState.value - motion.sourceDepth, motion.returning)
        fun interpolate(a: Rect, b: Rect) =
            Rect(
                lerp(a.left, b.left, progress),
                lerp(a.top, b.top, progress),
                lerp(a.right, b.right, progress),
                lerp(a.bottom, b.bottom, progress),
            )
        val clip = interpolate(start.viewport, end).translate(-origin)
        val plane = interpolate(first, last).translate(-origin)
        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
            translate(plane.left, plane.top) {
                scale(plane.width / image.width, plane.height / image.height, Offset.Zero) {
                    drawImage(image)
                }
            }
        }
    }
}

@Composable
internal fun GalleryReturnTarget(viewport: Rect, modifier: Modifier, reopen: () -> Unit) {
    if (viewport.width <= 0f || viewport.height <= 0f) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        Box(
            Modifier.absoluteOffset {
                    IntOffset(
                        (viewport.left - origin.x).roundToInt(),
                        (viewport.top - origin.y).roundToInt(),
                    )
                }
                .size(
                    with(density) { viewport.width.toDp() },
                    with(density) { viewport.height.toDp() },
                )
                .clickable(onClickLabel = "重新打开图片", role = Role.Button, onClick = reopen)
        )
    }
}
