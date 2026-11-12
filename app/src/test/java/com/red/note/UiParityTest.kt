package com.red.note

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun confirmedCommentHdrHasBadgeAndOpensOriginalInSharedGallery() {
        val state = RedState(RuntimeEnvironment.getApplication())
        val note = Note("comment-note", "", "pc_feed", JSONObject())
        val original = "https://sns-img-bd.xhscdn.com/comment-original"
        val preview = "https://fixture.invalid/comment-preview.jpg"
        val comment =
            JSONObject(
                """{"id":"comment","pictures":[{"url":"$preview","origin_url":"$original"}]}"""
            )
        state.hdrImages[original] = true
        var opened: Page.Gallery? = null
        compose.setContent {
            AppTheme(state) {
                CommentRow(state, note, comment, reply = { _, _ -> }, gallery = { opened = it })
            }
        }
        compose.onNodeWithContentDescription("HDR 图片", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("评论图片 1").performClick()
        compose.runOnIdle {
            Assert.assertEquals(listOf(original), opened?.urls)
            Assert.assertEquals(listOf(preview), opened?.previews)
        }
    }

    @Test
    fun unloadedImageRetainsDecodedFlightPreview() {
        val bitmap =
            android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val image = bitmap.asImageBitmap()
        var delivered: androidx.compose.ui.graphics.ImageBitmap? = null
        compose.setContent {
            Picture(
                "",
                null,
                Modifier.size(100.dp),
                retained = image,
                onImageReady = { delivered = it },
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            Assert.assertSame(image, delivered)
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val frame =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(frame))
            Assert.assertEquals(android.graphics.Color.BLUE, frame.getPixel(50, 50))
            frame.recycle()
        }
    }

    @Test
    fun galleryFlightDrawsResizedImageOnOpeningAndReturn() {
        val bitmap =
            android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val image = bitmap.asImageBitmap()
        val flight =
            GalleryFlight(
                image,
                { GalleryAnchor(androidx.compose.ui.geometry.Rect(10f, 100f, 60f, 180f), true) },
                0,
            )
        flight.viewport = androidx.compose.ui.geometry.Rect(50f, 200f, 350f, 500f)
        flight.plane = GalleryPlane(image, androidx.compose.ui.geometry.Rect(0f, 0f, 300f, 300f))
        val page = Page.Gallery(listOf("fixture"), 0, "", flight = flight)
        val progress = androidx.compose.runtime.mutableFloatStateOf(0f)
        val returning = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                GalleryFlightOverlay(
                    GalleryMotion(page, progress, returning.value, 0),
                    Modifier.matchParentSize(),
                )
            }
        }
        fun redWidth(t: Float, reverse: Boolean): Int {
            compose.runOnUiThread {
                progress.floatValue = t
                returning.value = reverse
            }
            compose.waitForIdle()
            return compose.runOnUiThread {
                val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
                val frame =
                    android.graphics.Bitmap.createBitmap(
                        view.width,
                        view.height,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                view.draw(android.graphics.Canvas(frame))
                var left = frame.width
                var right = -1
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    val c = frame.getPixel(x, y)
                    if (
                        android.graphics.Color.red(c) > 240 && android.graphics.Color.green(c) < 10
                    ) {
                        left = minOf(left, x)
                        right = maxOf(right, x)
                    }
                }
                frame.recycle()
                right - left + 1
            }
        }
        Assert.assertEquals(50, redWidth(0f, false))
        Assert.assertEquals(300, redWidth(1f, false))
        Assert.assertEquals(244f, redWidth(.5f, false).toFloat(), 2f)
        Assert.assertEquals(106f, redWidth(.5f, true).toFloat(), 2f)
    }

    @Test
    fun returningImageHitAreaReversesTheFrozenFlightMoreThanOnce() {
        val image =
            android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        val thumbnail = Rect(30f, 110f, 100f, 190f)
        val galleryBounds = Rect(0f, 90f, 412f, 590f)
        val flight =
            GalleryFlight(image, { GalleryAnchor(thumbnail) }, 0).apply {
                viewport = galleryBounds
                index = 1
                plane = GalleryPlane(image, Rect(-15f, 0f, 420f, 480f))
            }
        val page = Page.Gallery(listOf("first", "second"), 0, flight = flight)
        val motion = GalleryMotion(page, mutableFloatStateOf(.46f), true, 0)
        val reversal = GalleryReversal(motion)
        val direction = mutableStateOf<Boolean?>(null)
        var fullPageClicks = 0
        var reopens = 0
        var tappedProgress = 0f
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                LaunchedEffect(direction.value) { direction.value?.let { reversal.settle(it) } }
                Box(Modifier.fillMaxSize().testTag("transition-root")) {
                    Box(Modifier.matchParentSize().clickable { fullPageClicks++ })
                    GalleryFlightOverlay(
                        motion,
                        Modifier.matchParentSize(),
                        reversal.progress.asState(),
                    )
                    GalleryReturnTarget(thumbnail, Modifier.matchParentSize()) {
                        reopens++
                        tappedProgress = reversal.progress.value
                        direction.value = true
                    }
                }
            }
            val root = compose.onNodeWithTag("transition-root")
            root.performTouchInput { click(Offset(200f, 300f)) }
            root.performTouchInput { click(thumbnail.center) }
            compose.runOnIdle {
                Assert.assertEquals(1, fullPageClicks)
                Assert.assertEquals(1, reopens)
                Assert.assertEquals(.46f, reversal.progress.value, .001f)
            }
            repeat(5) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            val opened = compose.runOnIdle { reversal.progress.value }
            Assert.assertTrue(opened > .46f && opened < 1f)
            compose.runOnUiThread { direction.value = false }
            repeat(4) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            val closed = compose.runOnIdle { reversal.progress.value }
            Assert.assertTrue(closed > 0f && closed < opened)
            root.performTouchInput { click(thumbnail.center) }
            compose.runOnIdle {
                Assert.assertEquals(2, reopens)
                Assert.assertTrue(tappedProgress > 0f && tappedProgress <= closed)
                Assert.assertEquals(tappedProgress, reversal.progress.value, .001f)
                Assert.assertEquals(1, reversal.motion.index)
                Assert.assertEquals(galleryBounds, reversal.motion.viewport)
            }
            repeat(4) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            compose.runOnIdle { Assert.assertTrue(reversal.progress.value > tappedProgress) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun profileAndNavigationKeepOriginalGeometry() {
        val app = RuntimeEnvironment.getApplication()
        val state = RedState(app)
        state.preferences.set("theme", 1)
        state.preferences.set("fontScale", 1.0)
        state.preferences.set("floating", true)
        state.preferences.set("liquidGlass", false)
        val profile =
            JSONObject(
                """{"basic_info":{"nickname":"RedNote","red_id":"12345678","desc":"记录生活中的每一刻"},"interactions":[{"count":"125","name":"关注"},{"count":"360","name":"粉丝"},{"count":"1024","name":"获赞与收藏"}]}"""
            )
        compose.setContent {
            AppTheme(state) {
                Surface {
                    Column(Modifier.fillMaxSize()) {
                        ProfileHeader(state, profile, false)
                        Spacer(Modifier.weight(1f))
                        BottomBar(state, 0) {}
                    }
                }
            }
        }
        compose.onNodeWithText("RedNote").assertIsDisplayed()
        compose.onNodeWithText("125 关注").assertIsDisplayed()
        compose.onNodeWithText("我的").assertIsDisplayed()
        val geometry = JSONObject()
        listOf("profile-avatar", "profile-name", "profile-stats", "profile-description").forEach {
            tag ->
            val bounds =
                compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            geometry.put(
                tag,
                org.json.JSONArray(listOf(bounds.left, bounds.top, bounds.width, bounds.height)),
            )
        }
        val reference =
            JSONObject(
                javaClass
                    .getResourceAsStream("/flutter-profile-geometry.json")!!
                    .bufferedReader()
                    .readText()
            )
        reference.keys().forEach { key ->
            val expected = reference.getJSONArray(key)
            val actual = geometry.getJSONArray(key)
            for (i in 0..3) org.junit.Assert.assertEquals(
                "$key coordinate $i",
                expected.getDouble(i),
                actual.getDouble(i),
                2.0,
            )
        }
        java.io.File("build/parity/compose-profile-geometry.json").apply {
            parentFile!!.mkdirs()
            writeText(geometry.toString())
        }
        compose
            .onNodeWithTag("profile-avatar")
            .assertWidthIsEqualTo(64.dp)
            .assertHeightIsEqualTo(64.dp)

        val file =
            java.io.File("build/parity/profile-navigation.png").apply { parentFile!!.mkdirs() }
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun notificationActionUses32VisualAnd48TouchArea() {
        compose.setContent { MaterialTheme { CompactAction("回复", "chat_bubble_outline") {} } }
        compose.onNodeWithText("回复").assertIsDisplayed()
        compose.onNodeWithText("回复").assertHasClickAction().assertHeightIsEqualTo(48.dp)
        val file =
            java.io.File("build/parity/notification-action.png").apply { parentFile!!.mkdirs() }
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun frostedHeaderKeepsFlutterClipHeightAndLeavesTheFeedOutsideUntinted() {
        val state = RedState(RuntimeEnvironment.getApplication())
        val black = androidx.compose.ui.graphics.Color.Black
        val red = androidx.compose.ui.graphics.Color(0xfff44336)
        val white = androidx.compose.ui.graphics.Color.White
        compose.setContent {
            FrostedDiscoveryLayout(
                state,
                112.dp,
                base = white,
                body = {
                    Box(Modifier.fillMaxSize().background(black)) {
                        Box(
                            Modifier.fillMaxHeight()
                                .width(206.dp)
                                .align(androidx.compose.ui.Alignment.TopEnd)
                                .padding(top = 112.dp)
                                .background(red)
                        )
                    }
                },
            ) {}
        }
        compose.waitForIdle()
        val screenshot =
            compose.runOnUiThread {
                val view = compose.activity.window.decorView
                val frame =
                    android.graphics.Bitmap.createBitmap(
                        view.width,
                        view.height,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                val content = view.findViewById<android.view.View>(android.R.id.content)
                content.draw(android.graphics.Canvas(frame))
                try {
                    frame.asImageBitmap().toPixelMap()
                } finally {
                    frame.recycle()
                }
            }
        Assert.assertTrue(screenshot[40, 72].red > .15f)
        Assert.assertEquals(black, screenshot[40, 120])
        Assert.assertEquals(red, screenshot[350, 120])
    }

    @Test
    fun customDarkScaffoldSuppliesLightForeground() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("theme", 2)
        state.preferences.set("pureBlack", false)
        var actual = androidx.compose.ui.graphics.Color.Unspecified
        var expected = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            AppTheme(state) {
                actual = LocalContentColor.current
                expected = MaterialTheme.colorScheme.onSurface
                Text("深色文字")
            }
        }
        compose.onNodeWithText("深色文字").assertIsDisplayed()
        compose.runOnIdle { Assert.assertEquals(expected, actual) }
        compose.runOnUiThread { state.preferences.set("pureBlack", true) }
        compose.waitForIdle()
        compose.runOnIdle { Assert.assertEquals(expected, actual) }
        compose.runOnUiThread { state.preferences.set("theme", 1) }
        compose.waitForIdle()
        compose.runOnIdle { Assert.assertEquals(expected, actual) }
    }

    @Test
    fun applicationFontScaleEnlargesTextWithoutEnlargingIcons() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("fontScale", 1.0)
        compose.setContent {
            AppTheme(state) {
                Column {
                    Text(
                        "字体缩放",
                        fontSize =
                            androidx.compose.ui.unit.TextUnit(
                                20f,
                                androidx.compose.ui.unit.TextUnitType.Sp,
                            ),
                        lineHeight =
                            androidx.compose.ui.unit.TextUnit(
                                24f,
                                androidx.compose.ui.unit.TextUnitType.Sp,
                            ),
                    )
                    Glyph("home_outlined", "固定图标", 24.dp)
                }
            }
        }
        val before = compose.onNodeWithText("字体缩放").fetchSemanticsNode().boundsInRoot.width
        compose.runOnUiThread { state.preferences.set("fontScale", 1.5) }
        compose.waitForIdle()
        val after = compose.onNodeWithText("字体缩放").fetchSemanticsNode().boundsInRoot.width
        Assert.assertEquals(before * 1.5f, after, 2f)
        compose
            .onNodeWithContentDescription("固定图标")
            .assertWidthIsEqualTo(24.dp)
            .assertHeightIsEqualTo(24.dp)
    }

    @Test
    fun hdrBadgesShareRegularAndCompactStylesAtLargeFont() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("fontScale", 1.5)
        compose.setContent {
            AppTheme(state) {
                Column {
                    InlineHdrBadge(Modifier.testTag("inline"))
                    MediaBadge("HDR", Modifier.testTag("gallery"))
                    Box(Modifier.size(64.dp)) {
                        InlineHdrBadge(Modifier.testTag("thumbnail"), description = "第 2 张 HDR 图片", compact = true)
                    }
                    MediaBadge("GIF", Modifier.testTag("gif"))
                }
            }
        }
        for ((tag, size, height) in listOf(Triple("inline", 15f, 18f), Triple("gallery", 15f, 18f), Triple("thumbnail", 12f, 13.5f))) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) { it(layouts) }
            val layout = layouts.single()
            Assert.assertEquals(1, layout.lineCount)
            // Paragraph width can retain parent constraints; check the actual label bounds.
            Assert.assertTrue("$tag label is clipped", layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.size.width + .5f)
            Assert.assertFalse(layout.isLineEllipsized(0))
            Assert.assertEquals(size, layout.layoutInput.style.fontSize.value, .001f)
            Assert.assertEquals(height, layout.layoutInput.style.lineHeight.value, .001f)
            Assert.assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, layout.layoutInput.style.fontWeight)
        }
        compose.onNodeWithContentDescription("第 2 张 HDR 图片").assertIsDisplayed()
        compose.onNodeWithTag("gif").assertTextEquals("GIF")
        compose.runOnUiThread {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File("build/parity/hdr-badges.png").apply { parentFile.mkdirs() }.outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test
    fun detailCommentPromptKeepsOneLineAtNarrowWidthAndLargeFont() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("fontScale", 1.5)
        compose.setContent {
            AppTheme(state) {
                Column {
                    Box(Modifier.width(104.dp).height(44.dp).testTag("prompt-capsule")) {
                        DetailCommentPrompt()
                    }
                    InlineHdrBadge()
                }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("说点什么…").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) {
            it(layouts)
        }
        Assert.assertEquals(1, layouts.single().lineCount)
        Assert.assertTrue(layouts.single().isLineEllipsized(0))
        val icon = compose.onNodeWithContentDescription("写评论").assertWidthIsEqualTo(16.dp)
        val center =
            compose.onNodeWithTag("prompt-capsule").fetchSemanticsNode().boundsInRoot.center.y
        Assert.assertEquals(center, icon.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        Assert.assertEquals(
            center,
            compose.onNodeWithText("说点什么…").fetchSemanticsNode().boundsInRoot.center.y,
            1f,
        )
        layouts.clear()
        compose.onNodeWithText("HDR").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) {
            it(layouts)
        }
        Assert.assertEquals(15f, layouts.single().layoutInput.style.fontSize.value, .001f)
    }

    @Test
    fun detailFitPreviewLeavesBlackLetterboxInDarkTheme() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("theme", 2)
        val bitmap =
            android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        compose.setContent {
            AppTheme(state) {
                Column {
                    Picture(
                        "",
                        null,
                        Modifier.size(100.dp),
                        androidx.compose.ui.layout.ContentScale.Fit,
                        background = androidx.compose.ui.graphics.Color.Black,
                        retained = bitmap.asImageBitmap(),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val frame =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(frame))
            Assert.assertEquals(android.graphics.Color.BLACK, frame.getPixel(50, 10))
            Assert.assertEquals(android.graphics.Color.RED, frame.getPixel(50, 50))
            Assert.assertEquals(android.graphics.Color.BLACK, frame.getPixel(50, 90))
            frame.recycle()
        }
    }

    @Test
    fun thumbnailFlingContinuesDrivingMainImageAfterRelease() {
        lateinit var main: androidx.compose.foundation.pager.PagerState
        lateinit var reel: androidx.compose.foundation.pager.PagerState
        compose.setContent {
            main = androidx.compose.foundation.pager.rememberPagerState { 30 }
            reel = androidx.compose.foundation.pager.rememberPagerState { 30 }
            val driving =
                androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            GalleryPagerSync(main, reel, driving)
            Column {
                androidx.compose.foundation.pager.HorizontalPager(
                    main,
                    Modifier.height(100.dp).fillMaxWidth().testTag("main"),
                ) {
                    Text("image $it")
                }
                androidx.compose.foundation.pager.HorizontalPager(
                    reel,
                    Modifier.height(66.dp).fillMaxWidth().testTag("reel"),
                    pageSize = androidx.compose.foundation.pager.PageSize.Fixed(48.dp),
                    flingBehavior =
                        androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                            reel,
                            androidx.compose.foundation.pager.PagerSnapDistance.atMost(30),
                            snapAnimationSpec =
                                androidx.compose.animation.core.tween(
                                    120,
                                    easing =
                                        androidx.compose.animation.core.CubicBezierEasing(
                                            .215f,
                                            .61f,
                                            .355f,
                                            1f,
                                        ),
                                ),
                        ),
                ) {
                    Text("thumb $it")
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reel").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(370f, 30f))
            moveTo(androidx.compose.ui.geometry.Offset(300f, 30f), 30)
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("reel").performTouchInput {
            moveTo(androidx.compose.ui.geometry.Offset(150f, 30f), 40)
            up()
        }
        compose.mainClock.advanceTimeBy(16)
        val released = compose.runOnUiThread { reel.currentPage + reel.currentPageOffsetFraction }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnUiThread {
            Assert.assertTrue(
                "fling must survive pager synchronization",
                reel.currentPage + reel.currentPageOffsetFraction > released + .1f,
            )
            Assert.assertTrue(
                "main image must continue with the reel",
                main.currentPage + main.currentPageOffsetFraction > released,
            )
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnUiThread { Assert.assertEquals(reel.currentPage, main.currentPage) }
        val beforeMainDrag = compose.runOnUiThread { main.currentPage }
        compose.onNodeWithTag("main").performTouchInput { swipeLeft(durationMillis = 200) }
        compose.waitForIdle()
        compose.runOnUiThread {
            Assert.assertTrue(main.currentPage > beforeMainDrag)
            Assert.assertEquals(main.currentPage, reel.currentPage)
            Assert.assertEquals(0f, reel.currentPageOffsetFraction, .001f)
        }
    }

    @Test
    fun dialogsUseOnlyOriginal60MillisecondFadeAndHalfBlackScrim() {
        var window: android.view.Window? = null
        compose.setContent {
            AppAlertDialog(
                onDismissRequest = {},
                title = {
                    window =
                        (androidx.compose.ui.platform.LocalView.current.parent
                                as androidx.compose.ui.window.DialogWindowProvider)
                            .window
                    Text("确认")
                },
                confirmButton = { Text("完成") },
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            Assert.assertEquals(
                R.style.OriginalDialogAnimation,
                window!!.attributes.windowAnimations,
            )
            Assert.assertEquals(.5f, window!!.attributes.dimAmount, 0f)
            listOf(R.anim.dialog_fade_in, R.anim.dialog_fade_out).forEach {
                val animation =
                    android.view.animation.AnimationUtils.loadAnimation(compose.activity, it)
                Assert.assertTrue(animation is android.view.animation.AlphaAnimation)
                Assert.assertEquals(60L, animation.duration)
            }
        }
    }
}
