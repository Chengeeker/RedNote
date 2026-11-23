@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.red.note

import android.os.Build
import android.view.MotionEvent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable as thumbnailClick
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.*

@Composable
internal fun GalleryPage(state: RedState, page: Page.Gallery, back: () -> Unit) {
    if (page.urls.isEmpty()) return
    val motion = LocalGalleryMotion.current
    val liquid = state.preferences.bool("liquidGlass")
    val activity = LocalContext.current as MainActivity
    val pager =
        rememberPagerState(initialPage = page.index.coerceIn(page.urls.indices)) { page.urls.size }
    val thumbnails = rememberPagerState(initialPage = pager.currentPage) { page.urls.size }
    val scope = rememberCoroutineScope()
    val kinds = remember { mutableStateMapOf<Int, String>() }
    var chrome by rememberSaveable { mutableStateOf(true) }
    val chromeOpacity by
        animateFloatAsState(
            if (motion == null) 1f else 0f,
            tween(140, easing = LinearEasing),
            label = "gallery-controls",
        )
    val thumbnailOpacity by
        animateFloatAsState(
            if (motion == null) 1f else 0f,
            tween(140, easing = CubicBezierEasing(.215f, .61f, .355f, 1f)),
            label = "gallery-thumbnails",
        )
    val planes = remember(page) { mutableMapOf<Int, GalleryPlane>() }
    DisposableEffect(page, pager) {
        val flight = page.flight
        flight?.galleryPosition = { pager.currentPage.let { it to planes[it] } }
        onDispose { flight?.galleryPosition = null }
    }
    val originalPlanes = remember(page) { mutableSetOf<Int>() }
    var zoomed by remember { mutableStateOf(false) }
    var playingLive by remember(page) { mutableStateOf(false) }
    var liveRendered by remember(page) { mutableStateOf(false) }
    var liveError by remember(page) { mutableStateOf(false) }
    var holdingLive by remember(page) { mutableStateOf(false) }
    val liveSources = page.livePhotos.getOrNull(pager.currentPage).orEmpty()
    LaunchedEffect(pager.currentPage) {
        playingLive = false; liveRendered = false; holdingLive = false; liveError = false
    }
    LaunchedEffect(pager.isScrollInProgress, motion) {
        if (pager.isScrollInProgress || motion != null) {
            playingLive = false; liveRendered = false; holdingLive = false
        }
    }
    androidx.activity.compose.BackHandler(enabled = playingLive) { playingLive = false }
    val reelDriving = remember { mutableStateOf(false) }
    val reelDragged = GalleryPagerSync(pager, thumbnails, reelDriving)
    val mainDragged by pager.interactionSource.collectIsDraggedAsState()
    val old = remember { if (Build.VERSION.SDK_INT >= 26) activity.window.colorMode else 0 }
    val insets = remember {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    }
    val oldStatus = remember { insets.isAppearanceLightStatusBars }
    val oldNav = remember { insets.isAppearanceLightNavigationBars }
    val imageLayer = rememberGraphicsLayer()
    val backdrop = remember { GlassBackdrop(imageLayer) }
    val hdrDisplay = remember {
        if (Build.VERSION.SDK_INT < 26) false
        else {
            val capabilities: android.view.Display.HdrCapabilities? =
                activity.windowManager.defaultDisplay.hdrCapabilities
            capabilities?.supportedHdrTypes?.isNotEmpty() == true
        }
    }
    val hasHdr = hdrDisplay && page.urls.any { state.hdrImages[it] == true }
    LaunchedEffect(hasHdr) {
        if (Build.VERSION.SDK_INT >= 26)
            activity.window.colorMode =
                if (hasHdr) android.content.pm.ActivityInfo.COLOR_MODE_HDR else old
    }
    DisposableEffect(Unit) {
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false
        onDispose {
            if (Build.VERSION.SDK_INT >= 26) activity.window.colorMode = old
            insets.isAppearanceLightStatusBars = oldStatus
            insets.isAppearanceLightNavigationBars = oldNav
        }
    }
    LaunchedEffect(pager, reelDriving) {
        var previous = pager.currentPage
        var lastTick = -12L
        snapshotFlow { Triple(reelDriving.value, mainDragged, pager.currentPage) }
            .collect { (fromReel, fromMainDrag, index) ->
                val now = android.os.SystemClock.uptimeMillis()
                if (
                    fromReel &&
                        !fromMainDrag &&
                    index != previous &&
                        now - lastTick >= 12 &&
                        state.preferences.bool("haptics", true)
                ) {
                    activity.window.decorView.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP
                    )
                    lastTick = now
                }
                previous = index
                page.origin?.let { state.imagePages[it] = index }
                zoomed = false
            }
    }
    val canWarmHdr = motion?.page !== page && Build.VERSION.SDK_INT >= 34
    LaunchedEffect(page.urls, pager.settledPage, canWarmHdr) {
        if (!canWarmHdr) return@LaunchedEffect
        // Let the large pager request its active and adjacent originals before background checks.
        delay(80)
        for (url in galleryHdrWarmupUrls(page.urls, pager.settledPage)) {
            launch { state.inspectHdr(url) }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage to pager.currentPageOffsetFraction }
            .collect { backdrop.revision++ }
    }
    CompositionLocalProvider(
        LocalContentColor provides Color.White,
        LocalGlassBackdrop provides backdrop,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val width = maxWidth
            HorizontalPager(
                pager,
                Modifier.fillMaxSize().recordGlassBackdrop(backdrop).onGloballyPositioned {
                    page.flight?.viewport = it.galleryViewport()
                },
                beyondViewportPageCount = 1,
                userScrollEnabled = motion == null && !zoomed && !reelDragged,
            ) { index ->
                var preview by remember { mutableStateOf<ImageBitmap?>(null) }
                var viewport by remember {
                    mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
                }
                LaunchedEffect(preview, viewport) {
                    val image = preview
                    val bounds = viewport
                    if (image != null && bounds != null && index !in originalPlanes) {
                        val plane =
                            GalleryPlane(
                                image,
                                imagePlane(
                                    androidx.compose.ui.geometry.Rect(
                                        0f,
                                        0f,
                                        bounds.width,
                                        bounds.height,
                                    ),
                                    androidx.compose.ui.geometry.Size(
                                        image.width.toFloat(),
                                        image.height.toFloat(),
                                    ),
                                    false,
                                ),
                            )
                        planes[index] = plane
                    }
                }
                DisposableEffect(index) {
                    onDispose {
                        planes.remove(index)
                        originalPlanes.remove(index)
                    }
                }
                Box(
                    Modifier.fillMaxSize()
                        .onGloballyPositioned { viewport = it.galleryViewport() }
                        .drawWithContent {
                            if (
                                motion?.page !== page ||
                                    !motion.hasFlight ||
                                    index != pager.currentPage
                            )
                                drawContent()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Picture(
                        page.previews.getOrElse(index) { page.urls[index] },
                        null,
                        Modifier.fillMaxSize().graphicsLayer { alpha = if (index == pager.currentPage && playingLive && liveRendered) 0f else 1f },
                        ContentScale.Fit,
                        background = Color.Transparent,
                        onImageReady = { preview = it },
                        retained = page.flight?.opening.takeIf { index == page.index },
                        holdPreview = motion?.page === page,
                    )
                    if (index == pager.currentPage && liveSources.isNotEmpty() && motion == null)
                        LivePhotoLayer(liveSources, playingLive,
                            onRendered = { liveRendered = it },
                            onStopped = { failed -> playingLive = false; liveError = failed },
                            modifier = Modifier.fillMaxSize())
                    HdrPicture(
                        page.urls[index],
                        Modifier.fillMaxSize().graphicsLayer { alpha = if (index == pager.currentPage && playingLive && liveRendered) 0f else 1f },
                        onLongPress = {
                            if (index == pager.currentPage && liveSources.isNotEmpty() && motion == null && !zoomed) {
                                if (state.preferences.bool("haptics", true))
                                    activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                holdingLive = true; liveError = false; playingLive = true
                            }
                        },
                        onPressEnd = { if (holdingLive) { holdingLive = false; playingLive = false } },
                        loadEnabled = motion?.page !== page,
                        showHdr = hdrDisplay && index == pager.settledPage && motion?.page !== page,
                        showProgress =
                            preview == null &&
                                (index != page.index || page.flight?.opening == null),
                        onTap = { x ->
                            if (motion == null) {
                                if (x >= 1f / 3 && x < 2f / 3) chrome = !chrome else back()
                            }
                        },
                        onDecoded = { hdr ->
                            val url = page.urls[index]
                            if (hdr || state.hdrImages[url] != true) state.hdrImages[url] = hdr
                            backdrop.revision++
                        },
                        onZoom = { if (index == pager.currentPage) { zoomed = it; if (it) playingLive = false } },
                        onKind = { kinds[index] = it },
                        onPlane = { plane ->
                            originalPlanes.add(index)
                            planes[index] = plane
                            if (index == pager.currentPage) backdrop.revision++
                        },
                    )
                }
            }
            if (liveError && chrome)
                Text("动态照片播放失败", Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = .7f), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White)
            if (
                chrome &&
                    (chromeOpacity > 0f || thumbnailOpacity > 0f) &&
                    !(liquid && motion?.returning == true)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .graphicsLayer { alpha = chromeOpacity },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryControl(state, "arrow_back_rounded", "返回", motion == null, action = back)
                    if (liveSources.isNotEmpty()) {
                        val shape = RoundedCornerShape(20.dp)
                        Row(
                            Modifier.clip(shape)
                                .background(if (playingLive) Color.White else Color.Black.copy(alpha = .54f))
                                .border(1.dp, if (playingLive) Color.White else Color.White.copy(alpha = .24f), shape)
                                .clickable(enabled = motion == null && !zoomed) { liveError = false; playingLive = !playingLive }
                                .padding(horizontal = 15.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (playingLive) "暂停 Live 图" else "播放 Live 图",
                                color = if (playingLive) Color.Black else Color.White,
                                fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    } else Text(
                        "${pager.currentPage+1} / ${page.urls.size}",
                        Modifier.background(
                                Color.Black.copy(alpha = .45f),
                                RoundedCornerShape(16.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    GalleryControl(
                        state,
                        "download_rounded",
                        "保存高清大图到相册",
                        !activity.saving && motion == null,
                    ) {
                        activity.saveMedia(page.urls[pager.currentPage], false, page.author)
                    }
                }
                if (page.urls.size > 1)
                    HorizontalPager(
                        thumbnails,
                        Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 32.dp).fillMaxWidth()
                            .height(64.dp)
                            .graphicsLayer { alpha = thumbnailOpacity },
                        flingBehavior = rememberGalleryThumbnailFling(thumbnails),
                        overscrollEffect = null,
                        pageSize = PageSize.Fixed(width * .16f),
                        contentPadding = PaddingValues(horizontal = width * .42f),
                        beyondViewportPageCount = 3,
                    ) { index ->
                        Box(
                            Modifier.fillMaxSize()
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    2.dp,
                                    if (index == pager.currentPage) Color.White
                                    else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .thumbnailClick {
                                    if (state.preferences.bool("haptics", true))
                                        activity.window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                    scope.launch {
                                        reelDriving.value = false
                                        thumbnails.stopScroll()
                                        pager.stopScroll()
                                        thumbnails.scrollToPage(index)
                                        pager.scrollToPage(index)
                                    }
                                }
                                .semantics {
                                    contentDescription = "第 ${index+1} 张图片"
                                    selected = index == pager.currentPage
                                }
                        ) {
                            Picture(
                                page.previews.getOrElse(index) { page.urls[index] },
                                null,
                                Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(6.dp)),
                            )
                            if (index != pager.currentPage)
                                Box(
                                    Modifier.matchParentSize().padding(2.dp).clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = .5f))
                                )
                            if (state.hdrImages[page.urls[index]] == true)
                                InlineHdrBadge(
                                    Modifier.align(Alignment.BottomEnd).padding(2.dp),
                                    description = "第 ${index + 1} 张 HDR 图片",
                                    compact = true,
                                )
                        }
                    }
                val badge =
                    if (state.hdrImages[page.urls[pager.currentPage]] == true) "HDR"
                    else kinds[pager.currentPage].orEmpty()
                if (badge.isNotEmpty())
                    MediaBadge(
                        badge,
                        Modifier.align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(
                                end = 16.dp,
                                bottom = if (page.urls.size > 1) 110.dp else 24.dp,
                            ),
                    )
            }
        }
    }
}

internal fun galleryHdrWarmupUrls(urls: List<String>, current: Int): List<String> =
    urls.indices
        .filter { kotlin.math.abs(it - current) in 2..4 }
        .sortedBy { kotlin.math.abs(it - current) }
        .map(urls::get)
        .distinct()
        .take(4)

// Keep the driving pager until the user takes control of the other one; drag-to-fling has idle
// gaps.
@Composable
internal fun GalleryPagerSync(
    pager: PagerState,
    thumbnails: PagerState,
    reelDriving: MutableState<Boolean>,
): Boolean {
    val reelDragged by thumbnails.interactionSource.collectIsDraggedAsState()
    val mainDragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(reelDragged, mainDragged) {
        if (reelDragged) {
            reelDriving.value = true
            pager.stopScroll()
        } else if (mainDragged) {
            reelDriving.value = false
            thumbnails.stopScroll()
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(reelDriving.value, pager.currentPage, pager.currentPageOffsetFraction) }
            .collect { (driving, index, fraction) ->
                if (!driving) {
                    val position = (index + fraction).coerceIn(0f, (thumbnails.pageCount - 1).coerceAtLeast(0).toFloat())
                    val nearest = position.roundToInt()
                    thumbnails.requestScrollToPage(nearest, position - nearest)
                }
            }
    }
    LaunchedEffect(thumbnails) {
        snapshotFlow {
                Triple(
                    reelDriving.value,
                    thumbnails.currentPage,
                    thumbnails.currentPageOffsetFraction,
                )
            }
            .collect { (driving, index, fraction) ->
                if (driving) {
                    // A fast small-page fling can briefly report an offset outside +/- 0.5.
                    val position =
                        (index + fraction).coerceIn(
                            0f,
                            (pager.pageCount - 1).coerceAtLeast(0).toFloat(),
                        )
                    val nearest = position.roundToInt()
                    pager.requestScrollToPage(nearest, position - nearest)
                }
            }
    }
    return reelDragged
}

@Composable
internal fun MediaBadge(text: String, modifier: Modifier = Modifier) {
    if (text == "HDR") {
        InlineHdrBadge(modifier)
        return
    }
    Text(
        text,
        modifier
            .semantics { contentDescription = text }
            .shadow(2.dp, RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(6.dp))
            .border(1.dp, Color.White.copy(alpha = .55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = .6.sp,
    )
}

@Composable
internal fun GalleryControl(
    state: RedState,
    icon: String,
    label: String,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val liquid = state.preferences.bool("liquidGlass")
    val density = LocalDensity.current
    val view = LocalView.current
    if (!liquid) {
        PlainGalleryControl(label, enabled, {
            if (state.preferences.bool("haptics", true))
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }) {
            val tint = Color.White.copy(alpha = if (enabled) 1f else .38f)
            when (icon) {
                "arrow_back_rounded" -> Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(22.dp), tint = tint)
                "download_rounded" -> Icon(painterResource(R.drawable.review_download_rounded), null, Modifier.size(22.dp), tint = tint)
                else -> Glyph(icon, null, 22.dp, tint)
            }
        }
        return
    }
    var down by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var started by remember { mutableLongStateOf(0) }
    val press by
        animateFloatAsState(
            if (down && liquid) 1f else 0f,
            if (down) glassSpring(250, .25f) else glassSpring(280, .45f),
            label = "gallery-press",
        )
    val x by
        animateFloatAsState(
            if (down && liquid) drag.x else 0f,
            if (down) glassSpring(150, .2f) else glassSpring(500, .5f),
            label = "gallery-drag-x",
        )
    val y by
        animateFloatAsState(
            if (down && liquid) drag.y else 0f,
            if (down) glassSpring(150, .2f) else glassSpring(500, .5f),
            label = "gallery-drag-y",
        )
    val stretch = anchoredStretch(x, y)
    val perform = {
        if (enabled) {
            if (state.preferences.bool("haptics", true))
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }
    Box(
        Modifier.size(48.dp)
            .pointerInteropFilter { event ->
                if (!enabled) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        down = true
                        drag = androidx.compose.ui.geometry.Offset.Zero
                        origin = androidx.compose.ui.geometry.Offset(event.rawX, event.rawY)
                        started = event.eventTime
                    }
                    MotionEvent.ACTION_MOVE ->
                        drag =
                            androidx.compose.ui.geometry.Offset(
                                (event.rawX - origin.x) / density.density,
                                (event.rawY - origin.y) / density.density,
                            )
                    MotionEvent.ACTION_UP -> {
                        down = false
                        if (
                            event.eventTime - started < 500 &&
                                drag.getDistance() < 18 &&
                                event.x in 0f..48 * density.density &&
                                event.y in 0f..48 * density.density
                        )
                            perform()
                    }
                    MotionEvent.ACTION_CANCEL -> down = false
                }
                true
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled)
                    onClick {
                        perform()
                        true
                    }
                else disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = (1 + press * 17 / 48) * stretch[0]
                scaleY = (1 + press * 17 / 48) * stretch[1]
                transformOrigin = TransformOrigin(stretch[2], stretch[3])
                translationX = stretch[4] * density.density
                translationY = stretch[5] * density.density
            },
            contentAlignment = Alignment.Center,
        ) {
            GlassDecoration(
                state,
                Modifier.fillMaxSize(),
                1,
                touch = press.coerceIn(0f, 1f),
            )
            Glyph(icon, null, 22.dp, Color.White.copy(alpha = if (enabled) 1f else .38f))
        }
    }
}
