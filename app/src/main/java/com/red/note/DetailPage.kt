package com.red.note

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal fun detailImageRatio(image: JSONObject?, cover: JSONObject?): Float {
    fun ratio(value: JSONObject?): Double? {
        val width = value?.optDouble("width", 0.0) ?: 0.0
        val height = value?.optDouble("height", 0.0) ?: 0.0
        return if (width.isFinite() && height.isFinite() && width > 0 && height > 0) width / height
        else null
    }
    return (ratio(image)?.coerceIn(.6, 1.8) ?: ratio(cover)?.coerceIn(.65, 1.5) ?: .75).toFloat()
}

internal enum class CommentSort(val label: String) {
    Default("默认"),
    Latest("最新"),
    MostLiked("最多点赞"),
}

internal class DetailMemory(initial: Note) {
    var note by mutableStateOf(initial)
    var comments by mutableStateOf(emptyList<JSONObject>())
    var commentSort by mutableStateOf(CommentSort.Default)
    val children = mutableStateMapOf<String, List<JSONObject>>()
    var cursor by mutableStateOf("")
    var more by mutableStateOf(true)
    var loaded = false
    var commentsLoaded = false
}

@Composable
internal fun DetailPage(
    state: RedState,
    initial: Note,
    entryFlight: GalleryFlight?,
    profile: (Map<String, Any?>) -> Unit,
    back: () -> Unit,
    gallery: (Page.Gallery) -> Unit,
    video: (Note) -> Unit,
    replies: (Note, String) -> Unit,
) {
    val memory =
        remember(initial) {
            state.details.getOrPut(initial) {
                if (state.details.size >= 16) state.details.remove(state.details.keys.first())
                DetailMemory(initial)
            }
        }
    var note by memory::note
    var error by remember { mutableStateOf("") }
    var commentError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var comments by memory::comments
    val children = memory.children
    var cursor by memory::cursor
    var more by memory::more
    var busy by remember { mutableStateOf(false) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var editorRevision by remember { mutableIntStateOf(0) }
    var target by rememberSaveable { mutableStateOf("") }
    var replyAuthor by rememberSaveable { mutableStateOf("") }
    var followed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as MainActivity
    val list = rememberLazyListState()
    val layer = rememberGraphicsLayer()
    val backdrop = remember { GlassBackdrop(layer) }
    fun loadComments(reset: Boolean = false) {
        if (busy) return
        busy = true
        commentError = ""
        scope.launch {
            try {
                val before = if (reset) "" else cursor
                val data = state.call("comments", note.args + ("cursor" to before))
                val rows = data.rows("comments")
                rows.forEach { root ->
                    children[root.optString("id")] = root.rows("sub_comments").take(3)
                }
                val old = if (reset) emptyList() else comments
                val next = (old + rows).distinctBy { it.optString("id") }
                cursor = data.optString("cursor")
                more =
                    data.opt("has_more") != false &&
                        data.opt("has_more") != 0 &&
                        next.size > old.size &&
                        cursor.isNotEmpty() &&
                        cursor != before
                comments = next
                memory.commentsLoaded = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                commentError = e.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }
    fun action(method: String, args: Map<String, Any?>, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                state.call(method, args)
                done()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.message = e.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }
    fun reload() {
        if (loading) return
        loading = true
        error = ""
        scope.launch {
            try {
                state
                    .call("detail", initial.args)
                    .rows("items")
                    .firstOrNull()
                    ?.let { Note.parse(it, initial.source) }
                    ?.let {
                        note = it.copy(token = it.token.ifEmpty { initial.token })
                        memory.loaded = true
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message.orEmpty()
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!memory.loaded) reload()
        if (!memory.commentsLoaded) loadComments()
    }
    val openingMotion =
        LocalGalleryMotion.current?.takeIf {
            it.page is Page.Detail && it.scope == initial && !it.returning
        }
    val returningGallery =
        LocalGalleryMotion.current?.takeIf {
            it.returning && (it.page as? Page.Gallery)?.origin == initial
        }
    val interaction = state.interaction(note)
    val previews =
        note.images.map(::imageUrl).filter(String::isNotEmpty).ifEmpty {
            listOfNotNull(note.cover.takeIf(String::isNotEmpty))
        }
    val urls =
        note.images.map(::originalImageUrl).filter(String::isNotEmpty).ifEmpty {
            listOfNotNull(note.cover.takeIf(String::isNotEmpty))
        }
    val pageCount by rememberUpdatedState(urls.size)
    val targetPage = returningGallery?.index?.coerceIn(0, (urls.size - 1).coerceAtLeast(0))
    // The old saveable pager position predates the gallery's last displayed image.
    val pager =
        remember(initial) {
            PagerState(
                currentPage =
                    targetPage
                        ?: (state.imagePages[initial] ?: 0).coerceIn(
                            0,
                            (urls.size - 1).coerceAtLeast(0),
                        )
            ) {
                pageCount
            }
        }
    val closingPreview = returningGallery?.image?.let { checkNotNull(targetPage) to it }
    var lastGalleryPreview by remember { mutableStateOf(closingPreview) }
    LaunchedEffect(returningGallery) {
        if (targetPage != null) {
            lastGalleryPreview = closingPreview
            if (pager.currentPage != targetPage || pager.currentPageOffsetFraction != 0f)
                pager.requestScrollToPage(targetPage)
        }
        snapshotFlow { targetPage ?: pager.currentPage }
            .collect { index ->
                state.imagePages[initial] = index
                entryFlight?.let {
                    it.index = index
                    if (index != 0) {
                        it.plane = null
                        it.viewport = null
                    }
                }
            }
    }
    val visibleImageUrl =
        urls.getOrNull(targetPage ?: pager.settledPage).takeIf {
            memory.loaded || note.images.isNotEmpty()
        }
    LaunchedEffect(visibleImageUrl) {
        if (visibleImageUrl != null) state.inspectHdr(visibleImageUrl, retry = true)
    }
    val flat by remember(memory, state, note.id) {
        derivedStateOf {
            // shortcut: the Web endpoint has no verified sort parameter, so rank loaded pages.
            val ordered = when (memory.commentSort) {
                CommentSort.Default -> memory.comments
                CommentSort.Latest -> memory.comments.sortedByDescending {
                    commentTimeMillis(it.optLong("create_time"))
                }
                CommentSort.MostLiked -> memory.comments.sortedByDescending { root ->
                    val key = "${note.id}:${root.optString("id")}"
                    (state.commentValues[key] ?: root).optLong("like_count")
                }
            }
            ordered.flatMap { root ->
                val previews = children[root.optString("id")].orEmpty()
                listOf(Triple(root, false, false)) +
                    previews.map { Triple(it, true, false) } +
                    if (root.optInt("sub_comment_count") > previews.size)
                        listOf(Triple(root, false, true))
                    else emptyList()
            }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { backdrop.revision++ }
    }
    var menu by remember { mutableStateOf(false) }
    fun share() {
        if (!note.id.matches(Regex("[A-Za-z0-9]{1,128}"))) {
            state.message = "笔记 ID 无效"
            return
        }
        val url =
            Uri.Builder()
                .scheme("https")
                .authority("www.xiaohongshu.com")
                .appendPath("explore")
                .appendPath(note.id)
                .appendQueryParameter("xsec_token", note.token)
                .appendQueryParameter("xsec_source", "pc_share")
                .build()
        runCatching {
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, url.toString()),
                        "分享笔记",
                    )
                )
            }
            .onFailure { state.message = "无法打开分享面板" }
    }
    val nearEnd by remember {
        derivedStateOf {
            (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                list.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, comments.size) {
        if (
            nearEnd &&
                comments.isNotEmpty() &&
                more &&
                !busy &&
                error.isEmpty() &&
                commentError.isEmpty()
        )
            loadComments()
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphButton("arrow_back", "返回", action = back)
                    Row(
                        Modifier.weight(1f).clickable {
                            profile(
                                mapOf(
                                    "id" to note.userId,
                                    "token" to note.user.optString("xsec_token"),
                                    "source" to note.source,
                                )
                            )
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(note.user, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(note.author.ifEmpty { "作者" }, fontSize = 16.sp, maxLines = 1)
                    }
                    if (note.userId.isNotEmpty() && note.userId != state.userId)
                        OutlinedButton(
                            enabled =
                                !busy &&
                                    !followed &&
                                    note.user.optString("fstatus") !in listOf("both", "follows"),
                            onClick =
                                tapAction {
                                    if (!state.loggedIn) activity.openLogin()
                                    else
                                        action("followUser", mapOf("id" to note.userId)) {
                                            followed = true
                                        }
                                },
                            modifier = Modifier.heightIn(min = 30.dp).widthIn(min = 68.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            border =
                                BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFFF2442)),
                        ) {
                            Text(
                                if (
                                    followed ||
                                        note.user.optString("fstatus") in listOf("both", "follows")
                                )
                                    "已关注"
                                else "关注",
                                fontSize = 13.sp,
                                color = androidx.compose.ui.graphics.Color(0xFFFF2442),
                            )
                        }
                    GlyphButton("ios_share_rounded", "分享笔记") { share() }
                    Box {
                        GlyphButton("more_vert", "更多") { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("刷新笔记") },
                                enabled = !loading,
                                onClick =
                                    tapAction {
                                        menu = false
                                        reload()
                                    },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        modifier =
                            Modifier.onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                                .drawWithContent {
                                    layer.record { this@drawWithContent.drawContent() }
                                    drawLayer(layer)
                                    backdrop.revision++
                                },
                        state = list,
                        contentPadding = PaddingValues(bottom = 100.dp),
                    ) {
                        if (urls.isNotEmpty())
                            item {
                                val video = note.raw.optJSONObject("video")
                                val hasHdrVideo = remember(video) { video?.let(::videoStreams)?.any { it.hdr } == true }
                                val ratio =
                                    detailImageRatio(
                                        note.images.firstOrNull(),
                                        note.raw.optJSONObject("cover"),
                                    )
                                var imageFrame by remember {
                                    mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
                                }
                                LaunchedEffect(imageFrame, pager.currentPage) {
                                    if (pager.currentPage == 0) entryFlight?.viewport = imageFrame
                                }
                                Box(
                                    Modifier.fillMaxWidth()
                                        .aspectRatio(ratio)
                                        .background(androidx.compose.ui.graphics.Color.Black)
                                        .onGloballyPositioned { imageFrame = it.galleryViewport() }
                                ) {
                                    HorizontalPager(
                                        pager,
                                        Modifier.fillMaxSize(),
                                        beyondViewportPageCount = 1,
                                    ) { i ->
                                        var image by
                                            remember(previews.getOrElse(i) { urls[i] }) {
                                                mutableStateOf<
                                                    androidx.compose.ui.graphics.ImageBitmap?
                                                >(
                                                    null
                                                )
                                            }
                                        var viewport by remember {
                                            mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
                                        }
                                        LaunchedEffect(image, viewport, pager.currentPage) {
                                            if (i == 0 && pager.currentPage == 0) {
                                                val bitmap = image
                                                val rect = viewport
                                                if (bitmap != null && rect != null)
                                                    entryFlight?.plane =
                                                        GalleryPlane(
                                                            bitmap,
                                                            imagePlane(
                                                                androidx.compose.ui.geometry.Rect(
                                                                    0f,
                                                                    0f,
                                                                    rect.width,
                                                                    rect.height,
                                                                ),
                                                                androidx.compose.ui.geometry.Size(
                                                                    bitmap.width.toFloat(),
                                                                    bitmap.height.toFloat(),
                                                                ),
                                                                false,
                                                            ),
                                                        )
                                            }
                                        }
                                        Picture(
                                            previews.getOrElse(i) { urls[i] },
                                            "图片 ${i+1}",
                                            Modifier.fillMaxSize()
                                                .onGloballyPositioned {
                                                    viewport = it.galleryViewport()
                                                }
                                                .hideGallerySource(initial, i)
                                                .clickable {
                                                    val anchor =
                                                        imageFrame?.let { GalleryAnchor(it) }
                                                    gallery(
                                                        Page.Gallery(
                                                            urls,
                                                            i,
                                                            note.author,
                                                            initial,
                                                            previews,
                                                            flight =
                                                                GalleryFlight(image, { anchor }, i),
                                                        )
                                                    )
                                                },
                                            androidx.compose.ui.layout.ContentScale.Fit,
                                            background = androidx.compose.ui.graphics.Color.Black,
                                            onImageReady = {
                                                image = it
                                                val retained = lastGalleryPreview
                                                if (
                                                    returningGallery == null &&
                                                        retained?.first == i &&
                                                        it != null &&
                                                        it !== retained.second
                                                )
                                                    lastGalleryPreview = null
                                            },
                                            retained =
                                                (closingPreview ?: lastGalleryPreview)
                                                    ?.takeIf { it.first == i }
                                                    ?.second
                                                    ?: entryFlight?.opening.takeIf { i == 0 },
                                            holdPreview =
                                                openingMotion != null || closingPreview?.first == i,
                                        )
                                    }
                                    if (urls.size > 1)
                                        Text(
                                            "${(targetPage ?: pager.currentPage)+1}/${urls.size}",
                                            Modifier.align(Alignment.BottomStart)
                                                .padding(12.dp)
                                                .background(
                                                    androidx.compose.ui.graphics.Color.Black.copy(
                                                        alpha = .5f
                                                    ),
                                                    RoundedCornerShape(16.dp),
                                                )
                                                .padding(horizontal = 9.dp, vertical = 4.dp),
                                            color = androidx.compose.ui.graphics.Color.White,
                                            fontSize = 12.sp,
                                        )
                                    val hasHdrImage =
                                        urls.getOrNull(targetPage ?: pager.currentPage)?.let {
                                            state.hdrImages[it]
                                        } == true
                                    if (hasHdrVideo || hasHdrImage)
                                        InlineHdrBadge(
                                            Modifier.align(Alignment.BottomEnd).padding(12.dp),
                                            description = if (hasHdrVideo) "HDR 视频" else "HDR 图片",
                                        )
                                }
                            }
                        if (note.raw.has("video") && !loading)
                            item {
                                Box(Modifier.padding(16.dp)) {
                                    if (videoStreams(note.raw.getJSONObject("video")).isEmpty())
                                        Text("此笔记暂未返回可直接播放的视频流")
                                    else
                                        Button(
                                            onClick = tapAction { video(note) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Glyph("play_arrow")
                                            Text("播放视频")
                                        }
                                }
                            }
                        if (error.isNotEmpty())
                            item {
                                Failure(error, "笔记加载失败") {
                                    error = ""
                                    reload()
                                }
                            }
                        item {
                            Column(Modifier.padding(16.dp)) {
                                if (note.title.isNotEmpty())
                                    RichText(
                                        note.title,
                                        Modifier.sharedTitle(initial),
                                        style =
                                            LocalTextStyle.current.copy(
                                                fontSize = 18.sp,
                                                fontWeight =
                                                    androidx.compose.ui.text.font.FontWeight
                                                        .SemiBold,
                                                lineHeight = 27.sp,
                                            ),
                                    )
                                if (note.raw.optString("desc").isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    RichText(
                                        note.raw.optString("desc"),
                                        style =
                                            LocalTextStyle.current.copy(
                                                fontSize = 16.sp,
                                                lineHeight = 25.6.sp,
                                            ),
                                    )
                                }
                                val meta = publicationMeta(note.raw)
                                if (meta.isNotEmpty())
                                    Text(
                                        meta,
                                        Modifier.padding(top = 24.dp, bottom = 4.dp),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                        }
                        item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                        item(key = "comments-header") {
                            var sortMenu by remember { mutableStateOf(false) }
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "共 ${maxOf(interaction.optInt("comment_count"), comments.size)} 条评论",
                                        Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    )
                                    Box(
                                        Modifier.size(48.dp)
                                            .clickable(role = Role.Button) { sortMenu = true }
                                            .padding(start = 6.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Glyph("sort_rounded", "评论排序", 18.dp)
                                    }
                                }
                                DropdownMenu(
                                    expanded = sortMenu,
                                    onDismissRequest = { sortMenu = false },
                                    modifier = Modifier.widthIn(min = 128.dp),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    CommentSort.entries.forEach { sort ->
                                        val active = memory.commentSort == sort
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    Text(sort.label)
                                                    if (active) Glyph(
                                                        "check_rounded",
                                                        size = 18.dp,
                                                        tint = androidx.compose.ui.graphics.Color(0xFFFF2442),
                                                    )
                                                }
                                            },
                                            onClick = tapAction {
                                                memory.commentSort = sort
                                                sortMenu = false
                                            },
                                            modifier = Modifier.semantics { selected = active },
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Row(
                                Modifier.padding(
                                    start = 16.dp,
                                    top = 6.dp,
                                    end = 16.dp,
                                    bottom = 12.dp,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(state.session.optJSONObject("user") ?: JSONObject(), 40.dp)
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    Modifier.weight(1f).heightIn(min = 48.dp).clickable(
                                        enabled = !loading && error.isEmpty()
                                    ) {
                                        target = ""
                                        replyAuthor = ""
                                        editorRevision++
                                        editor = true
                                    },
                                    shape = RoundedCornerShape(28.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "留下你的想法吧",
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Glyph("edit_outlined", null, 22.dp)
                                    }
                                }
                            }
                        }
                        items(
                            flat,
                            key = { it.first.optString("id") + ":" + it.second + ":" + it.third },
                        ) { (comment, child, expand) ->
                            if (expand)
                                CommentExpansion(comment.optInt("sub_comment_count")) {
                                    replies(note, comment.optString("id"))
                                }
                            else
                                CommentRow(
                                    state,
                                    note,
                                    comment,
                                    child,
                                    reply = { id, name ->
                                        target = id
                                        replyAuthor = name
                                        editorRevision++
                                        editor = true
                                    },
                                    gallery = gallery,
                                    deleted = { id ->
                                        state.adjustComments(note, -1)
                                        comments = comments.filter { it.optString("id") != id }
                                        children.keys.toList().forEach { key ->
                                            children[key] =
                                                children[key].orEmpty().filter {
                                                    it.optString("id") != id
                                                }
                                        }
                                    },
                                )
                        }
                        item {
                            if (busy)
                                Box(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            else if (commentError.isNotEmpty())
                                Failure(commentError, "评论加载失败") { loadComments() }
                            else if (!loading && error.isEmpty() && !more)
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) {
                                    Text(if (comments.isEmpty()) "暂无评论" else "评论已全部加载")
                                }
                        }
                    }
                    if (loading)
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f

                    val capsuleHeight =
                        maxOf(
                            44.dp,
                            29.dp +
                                with(androidx.compose.ui.platform.LocalDensity.current) {
                                    appTextSize(9.sp).toDp()
                                },
                        )
                    Row(
                        Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailCapsule(
                            state,
                            Modifier.weight(9f).height(capsuleHeight).clickable {
                                target = ""
                                replyAuthor = ""
                                editorRevision++
                                editor = true
                            },
                        ) {
                            DetailCommentPrompt()
                        }
                        DetailCapsule(state, Modifier.weight(12f).height(capsuleHeight)) {
                            Row(Modifier.fillMaxSize()) {
                                listOf("likeNote", "comments", "collectNote", "share")
                                    .forEachIndexed { i, method ->
                                        val selected =
                                            when (i) {
                                                0 -> interaction.optBoolean("liked")
                                                2 -> interaction.optBoolean("collected")
                                                else -> false
                                            }
                                        val tint =
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        Column(
                                            Modifier.weight(1f)
                                                .fillMaxHeight()
                                                .clickable {
                                                    when (method) {
                                                        "likeNote",
                                                        "collectNote" ->
                                                            if (!state.loggedIn)
                                                                activity.openLogin()
                                                            else
                                                                scope.launch {
                                                                    try {
                                                                        state.toggle(note, method)
                                                                    } catch (e: Exception) {
                                                                        if (
                                                                            e
                                                                                is
                                                                                CancellationException
                                                                        )
                                                                            throw e
                                                                        state.message =
                                                                            e.message.orEmpty()
                                                                    }
                                                                }
                                                        "comments" ->
                                                            scope.launch {
                                                                list.animateScrollToItem(
                                                                    (if (error.isNotEmpty()) 1
                                                                    else 0) +
                                                                        (if (urls.isNotEmpty()) 1
                                                                        else 0) +
                                                                        (if (
                                                                            note.raw.has("video") &&
                                                                                !loading
                                                                        )
                                                                            1
                                                                        else 0) +
                                                                        2
                                                                )
                                                            }
                                                        else -> share()
                                                    }
                                                }
                                                .padding(horizontal = 3.dp, vertical = 2.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Glyph(
                                                listOf(
                                                    if (selected) "favorite" else "favorite_border",
                                                    "chat_bubble_outline_rounded",
                                                    if (selected) "star_rounded"
                                                    else "star_border_rounded",
                                                    "ios_share_rounded",
                                                )[i],
                                                listOf("点赞", "评论", "收藏", "分享")[i],
                                                24.dp,
                                                tint,
                                            )
                                            Spacer(Modifier.height(1.dp))
                                            Text(
                                                if (i == 3) "分享"
                                                else
                                                    interaction.optString(
                                                        listOf(
                                                            "liked_count",
                                                            "comment_count",
                                                            "collected_count",
                                                        )[i],
                                                        "0",
                                                    ),
                                                fontSize = 9.sp,
                                                lineHeight = 10.sp,
                                                fontWeight =
                                                    androidx.compose.ui.text.font.FontWeight
                                                        .SemiBold,
                                                color = tint,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
        if (editor)
            key(editorRevision) {
                CommentEditor(
                    state,
                    note,
                    target,
                    replyAuthor,
                    { editor = false },
                    {
                        state.adjustComments(note, 1)
                        loadComments(true)
                    },
                )
            }
    }
}

@Composable
internal fun DetailCommentPrompt() {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("edit_outlined", "写评论", 16.dp, MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            "说点什么…",
            Modifier.weight(1f),
            fontSize = 13.5.sp,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailCapsule(state: RedState, modifier: Modifier, content: @Composable () -> Unit) {
    val glass = state.preferences.bool("liquidGlass")
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Surface(
        modifier,
        shape = RoundedCornerShape(24.dp),
        color =
            if (glass) androidx.compose.ui.graphics.Color.Transparent
            else if (dark) androidx.compose.ui.graphics.Color(0xFF262626)
            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .92f),
        border =
            if (glass) null
            else
                BorderStroke(
                    .6.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (dark) .2f else .35f),
                ),
        shadowElevation = if (glass) 0.dp else 2.dp,
    ) {
        Box {
            if (glass) GlassDecoration(state, Modifier.matchParentSize())
            content()
        }
    }
}
