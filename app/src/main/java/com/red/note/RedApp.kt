@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.red.note

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal sealed interface Page {
    data class Detail(val note: Note, val flight: GalleryFlight? = null) : Page

    data class Profile(val args: Map<String, Any?>, val chatReturn: Int? = null) : Page

    data class Gallery(
        val urls: List<String>,
        val index: Int,
        val author: String = "",
        val origin: Note? = null,
        val previews: List<String> = urls,
        val heroScope: Any? = origin,
        val flight: GalleryFlight? = null,
    ) : Page

    data class Video(val note: Note) : Page
}

@Composable
internal fun LoginPrompt(activity: MainActivity, title: String, fill: Boolean = true) {
    Box(
        if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Glyph("auto_stories_outlined", null, 40.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = tapAction { activity.openLogin() }) {
                Glyph("login")
                Spacer(Modifier.width(8.dp))
                Text("网页登录")
            }
        }
    }
}

@Composable
internal fun Failure(error: String, title: String = "加载失败", retry: () -> Unit) {
    val activity = LocalContext.current as? MainActivity
    val failure = activity?.state?.lastFailure?.takeIf { it.message == error }
    val verify = failure?.kind == "verification"
    val login = failure?.kind in listOf("login", "signature")
    val canVerify =
        verify &&
            com.red.note.protocol.VerificationPageUrl.isTrusted(
                activity?.state?.runtime?.verificationPageUrl()
            )
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph("cloud_off_outlined", null, 40.dp, MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        if (login || canVerify)
            Button(onClick = tapAction { activity?.openLogin(canVerify) }) {
                Glyph(if (canVerify) "verified_user_outlined" else "login")
                Spacer(Modifier.width(8.dp))
                Text(if (canVerify) "前往官方安全验证" else "网页登录")
            }
        else
            OutlinedButton(onClick = tapAction(retry)) {
                Glyph("refresh")
                Spacer(Modifier.width(8.dp))
                Text("重试")
            }
        if (canVerify) TextButton(onClick = tapAction(retry)) { Text("重试此请求") }
    }
}

@Composable
internal fun DiscoveryPage(
    state: RedState,
    open: (Note) -> Unit,
    profile: (Map<String, Any?>) -> Unit,
    toolbar: @Composable () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var categories by remember {
        mutableStateOf(
            runCatching {
                    JSONObject()
                        .put(
                            "rows",
                            org.json.JSONArray(state.preferences.text("discoveryCategories", "[]")),
                        )
                        .rows("rows")
                }
                .getOrDefault(emptyList())
        )
    }
    var selected by rememberSaveable {
        mutableStateOf(
            state.preferences.text("discoveryCategory:${state.userId}", "homefeed_recommend")
        )
    }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(state.epoch, retry) {
        try {
            val received =
                state.call("homeCategories").rows("categories").filter {
                    it.optString("id").isNotEmpty() && it.optString("name").isNotEmpty()
                }
            require(received.isNotEmpty())
            categories = received
            failed = false
            state.preferences.set("discoveryCategories", org.json.JSONArray(received).toString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed = true
        }
    }
    val headerHeight = 88.dp + topInset
    FrostedDiscoveryLayout(
        state,
        headerHeight,
        body = {
            key(selected) {
                NoteFeed(
                    state,
                    "home",
                    mapOf("category" to selected),
                    "pc_feed",
                    open,
                    profile,
                    topPadding = headerHeight,
                )
            }
        },
    ) {
        Column(Modifier.statusBarsPadding()) {
            toolbar()
            val choices =
                listOf(JSONObject().put("id", "homefeed_recommend").put("name", "推荐")) +
                    categories.filter { it.optString("id") != "homefeed_recommend" } +
                    if (categories.isEmpty())
                        listOf("穿搭", "美食", "彩妆", "影视", "职场").map { JSONObject().put("name", it) }
                    else emptyList()
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(choices) { c ->
                    TextButton(
                        enabled = c.optString("id").isNotEmpty(),
                        onClick =
                            tapAction {
                                selected = c.optString("id")
                                state.preferences.set("discoveryCategory:${state.userId}", selected)
                            },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(40.dp).widthIn(min = 48.dp),
                    ) {
                        Text(
                            c.optString("name"),
                            fontWeight =
                                if (selected == c.optString("id"))
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                else androidx.compose.ui.text.font.FontWeight.Normal,
                            color =
                                if (selected == c.optString("id"))
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (failed)
                    item {
                        TextButton(onClick = tapAction { retry++ }) {
                            Glyph("refresh", null, 16.dp)
                            Text("分类加载失败，重试")
                        }
                    }
            }
        }
    }
}

@Composable
internal fun NoteFeed(
    state: RedState,
    method: String,
    args: Map<String, Any?>,
    source: String,
    open: (Note) -> Unit,
    profile: (Map<String, Any?>) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    viewCounts: JSONObject = JSONObject(),
    compact: Boolean = false,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    showViews: Boolean = false,
) {
    val activity = LocalContext.current as? MainActivity
    val cacheKey = "homeSnapshot:${state.userId}"
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val columns = if (screenWidth >= 840) 4 else if (screenWidth >= 600) 3 else 2
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaledBodySize = appTextSize(13.sp).value
    val memory =
        remember(method, args, state.epoch) {
            state.feed("$method:${JSONObject(args)}").also { memory ->
                if (method == "home" && !memory.initialized) {
                    val cached =
                        runCatching { JSONObject(state.preferences.text(cacheKey, "{}")) }
                            .getOrDefault(JSONObject())
                    if (cached.optJSONObject("args")?.toString() == JSONObject(args).toString()) {
                        memory.notes = cached.rows("items").mapNotNull { Note.parse(it, source) }
                        memory.cursor = cached.optString("cursor")
                        memory.sessionId = cached.optString("sessionId")
                        memory.page = cached.optInt("page", 1).coerceAtLeast(1)
                        memory.more = cached.optBoolean("more", true)
                        val legacy =
                            state.preferences.number("$cacheKey:offset", 0.0).coerceAtLeast(0.0)
                        val stride =
                            screenWidth.toDouble() / columns * 1.35 + 4 * scaledBodySize + 8
                        memory.index =
                            state.preferences
                                .int("$cacheKey:composeIndex", (legacy / stride).toInt() * columns)
                                .coerceIn(0, (memory.notes.size - 1).coerceAtLeast(0))
                        memory.offset =
                            state.preferences
                                .int(
                                    "$cacheKey:composeOffset",
                                    ((legacy % stride) * density.density).toInt(),
                                )
                                .coerceAtLeast(0)
                    }
                    memory.initialized = true
                }
            }
        }
    var notes by memory::notes
    var cursor by memory::cursor
    var sessionId by memory::sessionId
    var page by memory::page
    var more by memory::more
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun load(reset: Boolean = false) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                val previousCursor = if (reset) "" else cursor
                val data =
                    state.call(
                        method,
                        args +
                            mapOf(
                                "cursor" to previousCursor,
                                "page" to if (reset) 1 else page,
                                "sessionId" to if (reset) "" else sessionId,
                            ),
                    )
                val rows = data.rows(if (data.has("items")) "items" else "notes")
                val received = rows.mapNotNull { Note.parse(it, source) }
                val old = if (reset) emptyList() else notes
                val merged = (old + received).distinctBy { it.id }
                val next = data.optString("cursor_score", data.optString("cursor"))
                more =
                    data.opt("has_more") != false &&
                        data.opt("has_more") != 0 &&
                        merged.size > old.size &&
                        (method == "search" || next.isNotEmpty() && next != previousCursor)
                notes = merged
                cursor = next
                sessionId = data.optString("session_id")
                page = if (reset) 2 else page + 1
                if (method == "home")
                    state.preferences.set(
                        cacheKey,
                        JSONObject()
                            .put("args", JSONObject(args))
                            .put(
                                "items",
                                org.json.JSONArray(
                                    merged.map {
                                        JSONObject()
                                            .put("id", it.id)
                                            .put("xsec_token", it.token)
                                            .put("xsec_source", it.source)
                                            .put("note_card", it.raw)
                                    }
                                ),
                            )
                            .put("cursor", cursor)
                            .put("sessionId", sessionId)
                            .put("page", page)
                            .put("more", more)
                            .toString(),
                    )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "加载失败"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { if (notes.isEmpty()) load() }
    val grid = rememberLazyGridState(memory.index, memory.offset)
    fun savePosition() {
        memory.index = grid.firstVisibleItemIndex
        memory.offset = grid.firstVisibleItemScrollOffset
        if (method == "home" && notes.isNotEmpty()) {
            state.preferences.set("$cacheKey:composeIndex", memory.index)
            state.preferences.set("$cacheKey:composeOffset", memory.offset)
        }
    }
    DisposableEffect(memory, grid) { onDispose { savePosition() } }
    val backdrop = LocalGlassBackdrop.current
    LaunchedEffect(grid, backdrop) {
        if (backdrop == null) {
            snapshotFlow { grid.isScrollInProgress }.collect { if (!it) savePosition() }
        } else {
            snapshotFlow {
                    Triple(
                        grid.isScrollInProgress,
                        grid.firstVisibleItemIndex,
                        grid.firstVisibleItemScrollOffset,
                    )
                }
                .collect { position ->
                    backdrop.revision++
                    if (!position.first) savePosition()
                }
        }
    }
    var lastTopRequest by remember { mutableIntStateOf(state.scrollToTop) }
    LaunchedEffect(state.scrollToTop) {
        if (state.scrollToTop != lastTopRequest) {
            lastTopRequest = state.scrollToTop
            grid.animateScrollToItem(0)
        }
    }
    val nearEnd by remember {
        derivedStateOf {
            notes.isNotEmpty() &&
                (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= notes.size - 4
        }
    }
    LaunchedEffect(nearEnd, notes.size) { if (nearEnd && more && !busy && error.isEmpty()) load() }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = busy && notes.isNotEmpty(),
        onRefresh = { load(true) },
    ) {
        LazyVerticalGrid(
            GridCells.Fixed(columns),
            state = grid,
            contentPadding =
                PaddingValues(
                    top = topPadding + if (header != null) 0.dp else if (compact) 4.dp else 8.dp,
                    start = if (compact) 4.dp else 8.dp,
                    end = if (compact) 4.dp else 8.dp,
                    bottom = 100.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            if (header != null)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.layout { measurable, constraints ->
                            val padding = (if (compact) 4.dp else 8.dp).roundToPx()
                            val placeable =
                                measurable.measure(constraints.offset(horizontal = 2 * padding))
                            layout(constraints.maxWidth, placeable.height) {
                                placeable.placeRelative(-padding, 0)
                            }
                        }
                    ) {
                        header()
                    }
                }
            items(notes, key = { it.id }) { note ->
                val interaction = state.interaction(note)
                val height =
                    (screenWidth.toFloat() / columns * 1.35f +
                            4 *
                                with(androidx.compose.ui.platform.LocalDensity.current) {
                                    appTextSize(13.sp).toDp().value
                                })
                        .dp
                val origin = remember(note.id, note.source) { ImageOrigin() }
                DisposableEffect(memory, note.id, origin) {
                    memory.imageOrigins[note.id] = origin
                    onDispose {
                        if (memory.imageOrigins[note.id] === origin)
                            memory.imageOrigins.remove(note.id)
                    }
                }
                Card(
                    onClick =
                        tapAction {
                            val anchor = origin.viewport?.let { GalleryAnchor(it, crop = true) }
                            state.openingDetail =
                                note to
                                    GalleryFlight(
                                        origin.bitmap,
                                        { index ->
                                            (memory.imageOrigins[note.id]?.viewport?.let {
                                                    GalleryAnchor(it, crop = true)
                                                } ?: anchor)
                                                .takeIf { index == 0 }
                                        },
                                        0,
                                    )
                            open(note)
                        },
                    modifier = Modifier.height(height),
                    shape =
                        androidx.compose.foundation.shape.RoundedCornerShape(
                            if (compact) 8.dp else 20.dp
                        ),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = LocalCardColor.current,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    elevation =
                        CardDefaults.cardElevation(defaultElevation = if (compact) 0.dp else 1.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        Picture(
                            note.cover,
                            note.title,
                            Modifier.fillMaxSize()
                                .onGloballyPositioned { origin.viewport = it.galleryViewport() }
                                .hideGallerySource(note, 0),
                            onImageReady = { origin.bitmap = it },
                        )
                        if (note.raw.has("video"))
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(
                                        androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f),
                                        androidx.compose.foundation.shape.CircleShape,
                                    )
                                    .padding(5.dp)
                            ) {
                                Glyph(
                                    "play_arrow_rounded",
                                    "视频笔记",
                                    20.dp,
                                    androidx.compose.ui.graphics.Color.White,
                                )
                            }
                        if (
                            showViews &&
                                viewCounts
                                    .optString(note.id, note.raw.optString("view_count"))
                                    .isNotEmpty()
                        )
                            Row(
                                Modifier.align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(
                                        androidx.compose.ui.graphics.Color(0x99000000),
                                        androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                    )
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Glyph(
                                    "visibility",
                                    null,
                                    14.dp,
                                    androidx.compose.ui.graphics.Color.White,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    viewCounts.optString(note.id, note.raw.optString("view_count")),
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontSize = 12.sp,
                                )
                            }
                    }
                    Column(
                        if (compact) Modifier.padding(8.dp)
                        else
                            Modifier.padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 10.dp)
                    ) {
                        RichText(
                            note.title.ifEmpty { if (note.raw.has("video")) "视频笔记" else "图文笔记" },
                            Modifier.sharedTitle(note),
                            style =
                                MaterialTheme.typography.titleSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f)
                                    .height(
                                        if (
                                            compact &&
                                                notificationTime(note.raw.opt("time")).isNotEmpty()
                                        )
                                            40.dp
                                        else 32.dp
                                    )
                                    .clickable {
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
                                Avatar(note.user, 22.dp)
                                Spacer(Modifier.width(5.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        note.author,
                                        maxLines = 1,
                                        overflow =
                                            androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (compact) {
                                        val date = notificationTime(note.raw.opt("time"))
                                        if (date.isNotEmpty())
                                            Text(
                                                date,
                                                maxLines = 1,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                    }
                                }
                            }
                            Row(
                                Modifier.clickable {
                                        if (!state.loggedIn) activity?.openLogin()
                                        else
                                            scope.launch {
                                                try {
                                                    state.toggle(note, "likeNote")
                                                } catch (e: Exception) {
                                                    if (e is CancellationException) throw e
                                                    state.message = e.message.orEmpty()
                                                }
                                            }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Glyph(
                                    if (interaction.optBoolean("liked")) "favorite"
                                    else "favorite_border",
                                    "点赞笔记",
                                    16.dp,
                                    if (interaction.optBoolean("liked"))
                                        androidx.compose.ui.graphics.Color(0xFFFF2442)
                                    else LocalContentColor.current,
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    interaction.optString("liked_count", "0"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    error.isNotEmpty() -> Failure(error, "内容加载失败") { load() }
                    notes.isEmpty() -> EmptyContent("暂时没有内容")
                    more -> TextButton(onClick = tapAction { load() }) { Text("加载更多") }
                    else -> Text("没有更多内容", Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun ProfilePage(
    state: RedState,
    args: Map<String, Any?>,
    activity: MainActivity,
    open: (Note) -> Unit,
    profile: (Map<String, Any?>) -> Unit,
    back: () -> Unit,
) {
    var data by remember { mutableStateOf(JSONObject()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var following by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var followingBusy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val self = args["id"] == state.userId
    LaunchedEffect(refresh) {
        busy = true
        error = ""
        try {
            data = state.call("profile", args)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message.orEmpty()
        } finally {
            busy = false
        }
    }
    val basic = data.optJSONObject("basic_info") ?: data
    val relationship = data.optString("fstatus", basic.optString("fstatus"))
    val followed =
        following || data.optBoolean("followed") || relationship in listOf("both", "follows")
    NoteFeed(
        state,
        "userPosts",
        args,
        "pc_user",
        open,
        profile,
        compact = true,
        header = {
            Column {
                ProfileHeader(
                    state,
                    data,
                    true,
                    actions = {
                        GlyphButton("arrow_back", "返回", action = back)
                        Spacer(Modifier.weight(1f))
                        Box {
                            GlyphButton("more_vert", "更多") { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("刷新资料") },
                                    enabled = !busy,
                                    onClick =
                                        tapAction {
                                            menu = false
                                            refresh++
                                        },
                                )
                            }
                        }
                    },
                    footer =
                        if (self) null
                        else {
                            {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        enabled = !busy && !followingBusy && !followed,
                                        onClick =
                                            tapAction {
                                                if (!state.loggedIn) activity.openLogin()
                                                else {
                                                    followingBusy = true
                                                    scope.launch {
                                                        try {
                                                            state.call("followUser", args)
                                                            following = true
                                                        } catch (e: Exception) {
                                                            if (e is CancellationException) throw e
                                                            state.message = e.message.orEmpty()
                                                        } finally {
                                                            followingBusy = false
                                                        }
                                                    }
                                                }
                                            },
                                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                        shape =
                                            androidx.compose.foundation.shape.RoundedCornerShape(
                                                12.dp
                                            ),
                                    ) {
                                        if (followingBusy)
                                            CircularProgressIndicator(
                                                Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        else
                                            Text(
                                                if (followed)
                                                    if (relationship == "both") "互相关注" else "已关注"
                                                else if (relationship == "fans") "回关" else "关注"
                                            )
                                    }
                                    FilledTonalButton(
                                        onClick =
                                            tapAction {
                                                if (!state.loggedIn) activity.openLogin()
                                                else
                                                    activity.chat(
                                                        args["id"].toString(),
                                                        basic.optString("nickname"),
                                                    )
                                            },
                                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                        shape =
                                            androidx.compose.foundation.shape.RoundedCornerShape(
                                                12.dp
                                            ),
                                    ) {
                                        Text("发私信")
                                    }
                                }
                            }
                        },
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Failure(error, "资料加载失败") { refresh++ }
                Text("笔记", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
            }
        },
    )
}

@Composable
internal fun JsonList(
    state: RedState,
    method: String,
    args: Map<String, Any?>,
    key: String,
    embedded: Boolean = false,
    row: @Composable (JSONObject) -> Unit,
) {
    val memory =
        remember(method, args, state.epoch) {
            state.jsonLists.getOrPut("$method:${JSONObject(args)}") {
                if (state.jsonLists.size >= 24) state.jsonLists.remove(state.jsonLists.keys.first())
                JsonMemory(if (method == "privateChats") 0 else 1)
            }
        }
    var rows by memory::rows
    var cursor by memory::cursor
    var page by memory::page
    var more by memory::more
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun load(reset: Boolean = false) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                val before = if (reset) "" else cursor
                val requested = if (reset) if (method == "privateChats") 0 else 1 else page
                val data = state.call(method, args + mapOf("cursor" to before, "page" to requested))
                if (method == "boards")
                    require(data.optJSONArray("boards") != null) { "服务器未返回专辑列表" }
                val received = data.rows(key)
                val old = if (reset) emptyList() else rows
                val merged = (old + received).distinctBy { rowIdentity(it, method) }
                val next = data.optString("strCursor", data.optString("cursor"))
                more =
                    received.isNotEmpty() &&
                        merged.size > old.size &&
                        data.opt("has_more") != false &&
                        data.opt("has_more") != 0 &&
                        ((method == "privateChats" && received.size >= 100) ||
                            (method == "boards" && received.size >= 15) ||
                            method == "searchUsers" ||
                            next.isNotEmpty() && next != before)
                rows = merged
                cursor = next
                page = requested + 1
                memory.loaded = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message.orEmpty()
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { if (!memory.loaded) load() }
    val list = rememberLazyListState(memory.index, memory.offset)
    DisposableEffect(list) {
        onDispose {
            memory.index = list.firstVisibleItemIndex
            memory.offset = list.firstVisibleItemScrollOffset
        }
    }
    val nearEnd by remember {
        derivedStateOf {
            rows.isNotEmpty() &&
                (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= rows.size - 3
        }
    }
    LaunchedEffect(nearEnd, rows.size) {
        if (nearEnd && more && !busy && error.isEmpty() && method != "privateChats") load()
    }
    val footer: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator()
                error.isNotEmpty() ->
                    Failure(
                        error,
                        when (method) {
                            "boards" -> "专辑加载失败"
                            "searchUsers" -> "用户搜索失败"
                            "privateChats" -> if (rows.isEmpty()) "私信加载失败" else "后续私信加载失败"
                            "subComments" -> "回复加载失败"
                            else -> "消息加载失败"
                        },
                    ) {
                        load()
                    }
                more -> TextButton(onClick = tapAction { load() }) { Text("加载更多") }
                rows.isEmpty() ->
                    if (method == "boards") EmptyContent("还没有收藏专辑")
                    else
                        Text(
                            when (method) {
                                "privateChats" -> "暂无私信"
                                "searchUsers" -> "没有找到相关用户"
                                "subComments" -> "暂无回复"
                                else -> "暂无消息"
                            }
                        )
            }
        }
    }
    if (embedded)
        Column {
            rows.forEach { row(it) }
            footer()
        }
    else
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            busy && rows.isNotEmpty(),
            { load(true) },
        ) {
            LazyColumn(
                state = list,
                contentPadding =
                    PaddingValues(
                        start = if (method == "boards") 16.dp else 0.dp,
                        end = if (method == "boards") 16.dp else 0.dp,
                        bottom = if (method == "searchUsers") 110.dp else 24.dp,
                    ),
            ) {
                items(rows, key = { rowIdentity(it, method) }) { row(it) }
                item { footer() }
            }
        }
}

internal fun rowIdentity(row: JSONObject, method: String = ""): String {
    val keys =
        when (method) {
            "privateChats" -> listOf("chat_user_id", "user_id")
            "searchUsers" -> listOf("user_id", "id")
            else -> listOf("id", "message_id", "comment_id", "board_id")
        }
    val value = if (method == "searchUsers") row.optJSONObject("user_info") ?: row else row
    return keys.firstNotNullOfOrNull { value.optString(it).takeIf(String::isNotBlank) }
        ?: row.toString()
}

@Composable
internal fun DiscoveryToolbar(state: RedState, activity: MainActivity, search: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Text(
            "发现",
            Modifier.align(Alignment.Center),
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("search", "搜索笔记", action = search)
            if (!state.loggedIn)
                TextButton(onClick = tapAction { activity.openLogin() }) { Text("登录") }
        }
    }
}

@Composable
private fun EmptyContent(title: String) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph("auto_stories_outlined", null, 40.dp, MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
    }
}
