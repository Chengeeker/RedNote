@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
)

package com.red.note

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal sealed interface Route {
    data class Content(val page: Page) : Route

    data class Replies(val note: Note, val rootId: String) : Route

    data class Topic(val keyword: String) : Route

    data class Settings(val section: String = "设置") : Route

    data class Messages(val type: String) : Route

    data class Albums(val album: JSONObject? = null) : Route
}

private data class Screen(val route: Route?, val depth: Int)

private data class GalleryRedirect(
    val base: Screen,
    val gallery: Screen,
    val reversal: GalleryReversal,
)

@Composable
internal fun RedApp(state: RedState, activity: MainActivity) {
    AppTheme(state) {
        val stack = remember { mutableStateListOf<Route>() }
        fun push(route: Route) {
            if (stack.lastOrNull() != route) stack.add(route)
        }
        val saved = rememberSaveableStateHolder()
        var owner by rememberSaveable { mutableStateOf("") }
        var ownerReady by remember { mutableStateOf(false) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val captureBottom =
            tab != 3 &&
                state.preferences.bool("floating", true) &&
                state.preferences.bool("liquidGlass")
        val backgroundLayer = rememberGraphicsLayer()
        val backdrop = remember { GlassBackdrop(backgroundLayer) }
        val snack = remember { SnackbarHostState() }
        val viewsNotice = remember { SnackbarHostState() }
        val viewsNoticeDuration =
            LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
                2_000L,
                containsText = true,
            ) ?: 2_000L
        var bottomBarHeight by remember { mutableIntStateOf(0) }
        val measureBottomBar = Modifier.onSizeChanged { bottomBarHeight = it.height }
        val scope = rememberCoroutineScope()
        fun showViewsNotice(count: Int) {
            scope.launch {
                viewsNotice.currentSnackbarData?.dismiss()
                withTimeoutOrNull(viewsNoticeDuration) {
                    viewsNotice.showSnackbar(
                        "已同步 $count 条笔记的浏览量",
                        duration = SnackbarDuration.Indefinite,
                    )
                }
            }
        }
        LaunchedEffect(Unit) {
            try {
                state.refreshSession()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.message = e.message.orEmpty()
                state.sessionReady = true
            }
        }
        LaunchedEffect(state.message) {
            if (state.message.isNotBlank()) {
                snack.showSnackbar(state.message)
                state.message = ""
            }
        }
        LaunchedEffect(state.epoch, state.sessionReady) {
            if (state.sessionReady) {
                if (ownerReady && owner != state.userId) {
                    stack.clear()
                    tab = 0
                    activity.clearProfileNavigation()
                }
                owner = state.userId
                ownerReady = true
            }
        }
        LaunchedEffect(activity.pendingProfiles.size, state.sessionReady) {
            if (state.sessionReady)
                while (activity.pendingProfiles.isNotEmpty()) {
                    val request = activity.pendingProfiles.removeAt(0)
                    push(Route.Content(Page.Profile(request.args, request.id)))
                }
        }
        fun back() {
            if (stack.isNotEmpty()) {
                val popped = stack.removeAt(stack.lastIndex)
                ((popped as? Route.Content)?.page as? Page.Profile)
                    ?.chatReturn
                    ?.let(activity::finishProfile)
            } else if (tab == 3) tab = 0
        }
        val currentScreen = Screen(stack.lastOrNull(), stack.size)
        val immersiveBars =
            ((currentScreen.route as? Route.Content)?.page).let {
                it is Page.Gallery || it is Page.Video
            }
        val lightBars = LocalScaffoldColor.current.luminance() > .5f
        LaunchedEffect(immersiveBars, lightBars) {
            if (!immersiveBars) {
                val bars =
                    androidx.core.view.WindowCompat.getInsetsController(
                        activity.window,
                        activity.window.decorView,
                    )
                bars.isAppearanceLightStatusBars = lightBars
                bars.isAppearanceLightNavigationBars = lightBars
            }
        }
        val pageState = remember(state.epoch) { SeekableTransitionState(currentScreen) }
        val pageTransition = rememberTransition(pageState, label = "page")
        val galleryProgress =
            pageTransition.animateFloat(
                transitionSpec = {
                    val forward = targetState.depth > initialState.depth
                    val page =
                        ((if (forward) targetState else initialState).route as? Route.Content)?.page
                    when (page) {
                        is Page.Gallery -> tween(if (forward) 330 else 200, easing = LinearEasing)
                        is Page.Detail ->
                            if (page.flight != null)
                                tween(if (forward) 220 else 200, easing = LinearEasing)
                            else snap()
                        else -> snap()
                    }
                },
                label = "media-image-plane",
            ) {
                it.depth.toFloat()
            }
        val sourceScreen = pageTransition.currentState
        val targetScreen = pageTransition.targetState
        val routeMotion =
            remember(sourceScreen, targetScreen, galleryProgress) {
                val returning = targetScreen.depth < sourceScreen.depth
                val media =
                    ((if (returning) sourceScreen else targetScreen).route as? Route.Content)?.page
                if (
                    kotlin.math.abs(sourceScreen.depth - targetScreen.depth) != 1 ||
                        media !is Page.Gallery && (media !is Page.Detail || media.flight == null)
                )
                    null
                else
                    GalleryMotion(
                        media,
                        galleryProgress,
                        returning,
                        minOf(sourceScreen.depth, targetScreen.depth),
                    )
            }
        var redirect by remember(state.epoch) { mutableStateOf<GalleryRedirect?>(null) }
        val activeRedirect =
            redirect?.takeIf { currentScreen == it.base || currentScreen == it.gallery }
        val galleryMotion = activeRedirect?.reversal?.motion ?: routeMotion
        var seeking by remember { mutableStateOf(false) }
        LaunchedEffect(currentScreen, seeking, redirect) {
            if (!seeking) {
                coroutineScope {
                    if (activeRedirect != null)
                        launch {
                            activeRedirect.reversal.settle(currentScreen == activeRedirect.gallery)
                        }
                    pageState.animateTo(currentScreen)
                }
                redirect = null
            }
        }
        BackHandler(stack.isEmpty() && tab == 3) { back() }
        PredictiveBackHandler(stack.isNotEmpty()) { progress ->
            val previous = Screen(stack.getOrNull(stack.lastIndex - 1), stack.size - 1)
            val reversal = activeRedirect?.takeIf { it.gallery == currentScreen }?.reversal
            val start = reversal?.progress?.value
            seeking = true
            try {
                progress.collect {
                    if (reversal != null && start != null)
                        reversal.progress.snapTo(
                            androidx.compose.ui.util.lerp(
                                start,
                                reversal.motion.sourceDepth.toFloat(),
                                it.progress,
                            )
                        )
                    pageState.seekTo(it.progress, previous)
                }
                back()
            } catch (e: CancellationException) {
                if (reversal == null) scope.launch { pageState.animateTo(currentScreen) }
            } finally {
                seeking = false
            }
        }
        val note: (Note) -> Unit = { note ->
            if (((stack.lastOrNull() as? Route.Content)?.page as? Page.Detail)?.note != note) {
                push(
                    Route.Content(
                        Page.Detail(note, state.openingDetail?.takeIf { it.first == note }?.second)
                    )
                )
            }
            state.openingDetail = null
        }
        val profile: (Map<String, Any?>) -> Unit = { args ->
            val id = args["id"]?.toString().orEmpty()
            if (id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) push(Route.Content(Page.Profile(args)))
            else state.message = "用户主页地址无效"
        }
        val gallery: (Page.Gallery) -> Unit = { push(Route.Content(it)) }
        CompositionLocalProvider(
            LocalHapticsEnabled provides state.preferences.bool("haptics", true),
            LocalGlassBackdrop provides backdrop.takeIf { captureBottom },
            LocalGalleryMotion provides galleryMotion,
            LocalTopic provides { keyword -> push(Route.Topic(keyword)) },
        ) {
            Box(Modifier.fillMaxSize().background(LocalScaffoldColor.current)) {
                if (!state.sessionReady || ownerReady && owner != state.userId)
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else
                    key(state.epoch) {
                        androidx.compose.animation.SharedTransitionLayout {
                            CompositionLocalProvider(LocalSharedTransition provides this) {
                                pageTransition.AnimatedContent(
                                    transitionSpec = {
                                        val forward = targetState.depth > initialState.depth
                                        val media =
                                            (if (forward) targetState else initialState)
                                                .route
                                                .let { (it as? Route.Content)?.page }
                                                .let {
                                                    it is Page.Detail && it.flight != null ||
                                                        it is Page.Gallery
                                                }
                                        val galleryPage =
                                            ((if (forward) targetState else initialState).route
                                                    as? Route.Content)
                                                ?.page is Page.Gallery
                                        val duration =
                                            if (media)
                                                if (forward) if (galleryPage) 330 else 220 else 200
                                            else if (forward) 240 else 190
                                        if (media) {
                                            if (forward)
                                                (androidx.compose.animation.fadeIn(
                                                        tween(duration, easing = LinearEasing)
                                                    ) togetherWith
                                                        androidx.compose.animation.ExitTransition
                                                            .None)
                                                    .using(null)
                                            else
                                                (androidx.compose.animation.EnterTransition
                                                        .None togetherWith
                                                        androidx.compose.animation.fadeOut(
                                                            tween(duration, easing = LinearEasing)
                                                        ))
                                                    .apply { targetContentZIndex = -1f }
                                                    .using(null)
                                        } else if (forward)
                                            (androidx.compose.animation.slideInHorizontally(
                                                    tween(
                                                        duration,
                                                        easing =
                                                            CubicBezierEasing(
                                                                .215f,
                                                                .61f,
                                                                .355f,
                                                                1f,
                                                            ),
                                                    )
                                                ) {
                                                    it
                                                } togetherWith
                                                    androidx.compose.animation.ExitTransition.None)
                                                .using(null)
                                        else
                                            (androidx.compose.animation.EnterTransition
                                                    .None togetherWith
                                                    androidx.compose.animation.slideOutHorizontally(
                                                        tween(
                                                            duration,
                                                            easing =
                                                                CubicBezierEasing(
                                                                    .55f,
                                                                    .055f,
                                                                    .675f,
                                                                    .19f,
                                                                ),
                                                        )
                                                    ) {
                                                        it
                                                    })
                                                .using(null)
                                    }
                                ) { screen ->
                                    CompositionLocalProvider(
                                        LocalPageVisibility provides this,
                                        LocalTitleDuration provides
                                            (if (
                                                pageTransition.targetState.depth <
                                                    pageTransition.currentState.depth
                                            )
                                                200
                                            else 220),
                                    ) {
                                        val route = screen.route
                                        val immersive =
                                            (route as? Route.Content)?.page.let {
                                                it is Page.Video || it is Page.Gallery
                                            }
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(
                                                    if (immersive) Color.Black
                                                    else LocalScaffoldColor.current
                                                )
                                                .then(
                                                    if (immersive || route == null && tab == 0)
                                                        Modifier
                                                    else Modifier.statusBarsPadding()
                                                )
                                        ) {
                                            saved.SaveableStateProvider(
                                                if (route == null) "root"
                                                else "route:${screen.depth}:${route.hashCode()}"
                                            ) {
                                                if (route == null) {
                                                    Column(
                                                        Modifier.fillMaxSize()
                                                            .then(
                                                                if (captureBottom)
                                                                    Modifier.recordGlassBackdrop(
                                                                        backdrop
                                                                    )
                                                                else Modifier
                                                            )
                                                    ) {
                                                        androidx.compose.animation.AnimatedContent(
                                                            tab,
                                                            modifier = Modifier.weight(1f),
                                                            transitionSpec = {
                                                                val direction =
                                                                    if (targetState > initialState)
                                                                        1
                                                                    else -1
                                                                (androidx.compose.animation
                                                                        .slideInHorizontally(
                                                                            tween(
                                                                                260,
                                                                                easing =
                                                                                    CubicBezierEasing(
                                                                                        .215f,
                                                                                        .61f,
                                                                                        .355f,
                                                                                        1f,
                                                                                    ),
                                                                            )
                                                                        ) {
                                                                            it * direction
                                                                        } togetherWith
                                                                        androidx.compose.animation
                                                                            .slideOutHorizontally(
                                                                                tween(
                                                                                    260,
                                                                                    easing =
                                                                                        CubicBezierEasing(
                                                                                            .215f,
                                                                                            .61f,
                                                                                            .355f,
                                                                                            1f,
                                                                                        ),
                                                                                )
                                                                            ) {
                                                                                -it * direction
                                                                            })
                                                                    .using(null)
                                                            },
                                                            label = "tabs",
                                                        ) { currentTab ->
                                                            saved.SaveableStateProvider(
                                                                "tab:$currentTab:${state.epoch}"
                                                            ) {
                                                                when (currentTab) {
                                                                    0 ->
                                                                        DiscoveryPage(
                                                                            state,
                                                                            note,
                                                                            profile,
                                                                        ) {
                                                                            DiscoveryToolbar(
                                                                                state,
                                                                                activity,
                                                                            ) {
                                                                                tab = 3
                                                                            }
                                                                        }
                                                                    1 ->
                                                                        MessageHome(
                                                                            state,
                                                                            activity,
                                                                        ) {
                                                                            push(Route.Messages(it))
                                                                        }
                                                                    2 ->
                                                                        AccountPage(
                                                                            state,
                                                                            activity,
                                                                            note,
                                                                            profile,
                                                                            {
                                                                                push(
                                                                                    Route.Settings()
                                                                                )
                                                                            },
                                                                            {
                                                                                push(Route.Albums())
                                                                            },
                                                                            ::showViewsNotice,
                                                                        )
                                                                    else ->
                                                                        SearchPage(
                                                                            state,
                                                                            note,
                                                                            profile,
                                                                            onBack = {
                                                                                if (
                                                                                    stack
                                                                                        .lastOrNull() ==
                                                                                        route
                                                                                )
                                                                                    back()
                                                                            },
                                                                        )
                                                                }
                                                            }
                                                        }
                                                        if (
                                                            tab != 3 &&
                                                                !state.preferences.bool(
                                                                    "floating",
                                                                    true,
                                                                )
                                                        )
                                                            BottomBar(
                                                                state,
                                                                tab,
                                                                measureBottomBar,
                                                            ) {
                                                                if (tab == it) state.scrollToTop++
                                                                else tab = it
                                                            }
                                                    }
                                                    if (
                                                        tab != 3 &&
                                                            state.preferences.bool("floating", true)
                                                    )
                                                        Box(
                                                            Modifier.align(Alignment.BottomCenter)
                                                        ) {
                                                            BottomBar(
                                                                state,
                                                                tab,
                                                                measureBottomBar,
                                                            ) {
                                                                if (tab == it) state.scrollToTop++
                                                                else tab = it
                                                            }
                                                        }
                                                } else
                                                    Column {
                                                        when (route) {
                                                            is Route.Replies ->
                                                                RepliesPage(
                                                                    state,
                                                                    route.note,
                                                                    route.rootId,
                                                                    {
                                                                        if (
                                                                            stack.lastOrNull() ==
                                                                                route
                                                                        )
                                                                            back()
                                                                    },
                                                                    gallery,
                                                                    { n, root ->
                                                                        push(Route.Replies(n, root))
                                                                    },
                                                                )
                                                            is Route.Topic ->
                                                                TopicPage(
                                                                    state,
                                                                    note,
                                                                    profile,
                                                                    {
                                                                        if (
                                                                            stack.lastOrNull() ==
                                                                                route
                                                                        )
                                                                            back()
                                                                    },
                                                                    route.keyword,
                                                                )
                                                            is Route.Settings -> {
                                                                PageBar(
                                                                    route.section,
                                                                    {
                                                                        if (
                                                                            stack.lastOrNull() ==
                                                                                route
                                                                        )
                                                                            back()
                                                                    },
                                                                    actions = {
                                                                        if (
                                                                            route.section == "账号与连接"
                                                                        )
                                                                            GlyphButton(
                                                                                "refresh",
                                                                                "刷新状态",
                                                                            ) {
                                                                                scope.launch {
                                                                                    runCatching {
                                                                                            state
                                                                                                .refreshSession()
                                                                                        }
                                                                                        .onFailure {
                                                                                            state
                                                                                                .message =
                                                                                                it
                                                                                                    .message
                                                                                                    .orEmpty()
                                                                                        }
                                                                                }
                                                                            }
                                                                    },
                                                                )
                                                                SettingsContent(
                                                                    state,
                                                                    activity,
                                                                    route.section,
                                                                ) {
                                                                    push(Route.Settings(it))
                                                                }
                                                            }
                                                            is Route.Albums ->
                                                                AlbumsContent(
                                                                    state,
                                                                    route.album,
                                                                    note,
                                                                    { push(Route.Albums(it)) },
                                                                    {
                                                                        if (
                                                                            stack.lastOrNull() ==
                                                                                route
                                                                        )
                                                                            back()
                                                                    },
                                                                )
                                                            is Route.Messages ->
                                                                MessageDetail(
                                                                    state,
                                                                    activity,
                                                                    route.type,
                                                                    note,
                                                                    profile,
                                                                    {
                                                                        if (
                                                                            stack.lastOrNull() ==
                                                                                route
                                                                        )
                                                                            back()
                                                                    },
                                                                )
                                                            is Route.Content ->
                                                                when (val page = route.page) {
                                                                    is Page.Detail ->
                                                                        DetailPage(
                                                                            state,
                                                                            page.note,
                                                                            page.flight,
                                                                            profile,
                                                                            {
                                                                                if (
                                                                                    stack
                                                                                        .lastOrNull() ==
                                                                                        route
                                                                                )
                                                                                    back()
                                                                            },
                                                                            gallery,
                                                                            {
                                                                                push(
                                                                                    Route.Content(
                                                                                        Page.Video(
                                                                                            it
                                                                                        )
                                                                                    )
                                                                                )
                                                                            },
                                                                            { n, id ->
                                                                                push(
                                                                                    Route.Replies(
                                                                                        n,
                                                                                        id,
                                                                                    )
                                                                                )
                                                                            },
                                                                        )
                                                                    is Page.Profile ->
                                                                        ProfilePage(
                                                                            state,
                                                                            page.args,
                                                                            activity,
                                                                            note,
                                                                            profile,
                                                                            {
                                                                                if (
                                                                                    stack
                                                                                        .lastOrNull() ==
                                                                                        route
                                                                                )
                                                                                    back()
                                                                            },
                                                                        )
                                                                    is Page.Gallery ->
                                                                        GalleryPage(
                                                                            state,
                                                                            page,
                                                                            {
                                                                                if (
                                                                                    stack
                                                                                        .lastOrNull() ==
                                                                                        route
                                                                                )
                                                                                    back()
                                                                            },
                                                                        )
                                                                    is Page.Video ->
                                                                        VideoContent(
                                                                            state,
                                                                            page.note,
                                                                            {
                                                                                if (
                                                                                    stack
                                                                                        .lastOrNull() ==
                                                                                        route
                                                                                )
                                                                                    back()
                                                                            },
                                                                        )
                                                                }
                                                        }
                                                    }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                galleryMotion?.let { motion ->
                    GalleryFlightOverlay(
                        motion,
                        Modifier.matchParentSize(),
                        activeRedirect?.reversal?.progress?.asState() ?: motion.progress,
                    )
                    val galleryPage = motion.page as? Page.Gallery
                    val base = activeRedirect?.base ?: targetScreen
                    if (
                        !seeking && motion.returning && galleryPage != null && currentScreen == base
                    ) {
                        val viewport =
                            motion.anchor?.viewport
                                ?: motion.flight?.anchor?.invoke(galleryPage.index)?.viewport
                        if (viewport != null)
                            GalleryReturnTarget(viewport, Modifier.matchParentSize()) {
                                if (stack.size == base.depth && stack.lastOrNull() == base.route) {
                                    val galleryScreen = activeRedirect?.gallery ?: sourceScreen
                                    redirect =
                                        activeRedirect
                                            ?: GalleryRedirect(
                                                base,
                                                galleryScreen,
                                                GalleryReversal(motion),
                                            )
                                    galleryScreen.route?.let(::push)
                                }
                            }
                    }
                }
                if (currentScreen.route == null && tab == 2) {
                    val bottomOffset = with(LocalDensity.current) { bottomBarHeight.toDp() }
                    SnackbarHost(
                        viewsNotice,
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = bottomOffset + 12.dp),
                    ) { data ->
                        Snackbar { Text(data.visuals.message) }
                    }
                }
                SnackbarHost(
                    snack,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                ) { data ->
                    Snackbar { Text(data.visuals.message) }
                }
            }
        }
    }
}

@Composable
internal fun BottomBar(
    state: RedState,
    selected: Int,
    modifier: Modifier = Modifier,
    choose: (Int) -> Unit,
) {
    val floating = state.preferences.bool("floating", true)
    if (floating && state.preferences.bool("liquidGlass", false)) {
        Box(modifier) { GlassNavigation(state, selected, choose) }
        return
    }
    val labels = listOf("发现", "消息", "我的")
    val icons = listOf("home_outlined", "chat_bubble_outline_rounded", "person_outline")
    val active = listOf("home_rounded", "chat_bubble_rounded", "person_rounded")
    if (!floating)
        NavigationBar(
            modifier.height(
                68.dp +
                    with(androidx.compose.ui.platform.LocalDensity.current) {
                        WindowInsets.navigationBars.getBottom(this).toDp()
                    }
            ),
            windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
            tonalElevation = 0.dp,
            containerColor =
                if (LocalScaffoldColor.current.luminance() < .5f) {
                    if (state.preferences.bool("pureBlack")) Color.Black else Color(0xFF16171B)
                } else MaterialTheme.colorScheme.surfaceContainer,
        ) {
            labels.forEachIndexed { i, label ->
                NavigationBarItem(
                    selected == i,
                    { choose(i) },
                    icon = { Glyph(if (i == selected) active[i] else icons[i], label) },
                    label = {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight =
                                if (i == selected) androidx.compose.ui.text.font.FontWeight.Bold
                                else androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                    },
                    colors =
                        NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
        }
    else
        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    bottom =
                        if (
                            WindowInsets.navigationBars.getBottom(
                                androidx.compose.ui.platform.LocalDensity.current
                            ) > 0
                        )
                            6.dp
                        else 14.dp
                ),
            contentAlignment = Alignment.Center,
        ) {
            val width =
                maxOf(216.dp, minOf(440.dp, maxWidth - 32.dp) * .6f).coerceAtMost(maxWidth - 32.dp)
            Surface(
                Modifier.width(width)
                    .height(64.dp)
                    .floatingShadow(LocalScaffoldColor.current.luminance() < .5f),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(Modifier.padding(4.dp)) {
                    val fraction by
                        animateFloatAsState(
                            selected.toFloat(),
                            tween(220, easing = CubicBezierEasing(.215f, .61f, .355f, 1f)),
                            label = "navigation",
                        )
                    Box(
                        Modifier.offset(x = (width - 8.dp) / 3 * fraction)
                            .width((width - 8.dp) / 3)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                    )
                    Row {
                        labels.forEachIndexed { i, label ->
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clickable { choose(i) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Glyph(
                                    if (i == selected) active[i] else icons[i],
                                    label,
                                    22.dp,
                                    if (i == selected)
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    label,
                                    fontSize = 11.5.sp,
                                    fontWeight =
                                        if (i == selected)
                                            androidx.compose.ui.text.font.FontWeight.SemiBold
                                        else androidx.compose.ui.text.font.FontWeight.Normal,
                                    color =
                                        if (i == selected)
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
}
