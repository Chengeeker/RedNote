package com.red.note

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaReturnTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun commentSortMenuSitsBesideCountAndReordersLoadedPages() {
        val activity = compose.activity
        val note = Note("sort-test", "", "pc_feed", JSONObject().put("title", "排序测试"))
        val memory = DetailMemory(note)
        fun comment(id: String, content: String, time: Long, likes: String) =
            JSONObject().put("id", id).put("content", content)
                .put("create_time", time).put("like_count", likes)

        val first = comment("a", "默认第一条", 1_750_000_000_000L, "2")
        val popular = comment("b", "点赞最高的评论", 1_740_000_000L, "12")
        val newest = comment("c", "最近发布的评论", 1_760_000_000L, "1")
        compose.runOnUiThread {
            val state = RedState(activity.application)
            state.preferences.set("liquidGlass", false)
            memory.loaded = true
            memory.commentsLoaded = true
            memory.comments = listOf(first, popular, newest)
            memory.more = false
            state.details[note] = memory
            activity.setContent {
                AppTheme(state) {
                    DetailPage(state, note, null, {}, {}, {}, {}, { _, _ -> })
                }
            }
        }
        val heading = compose.onNodeWithText("共 3 条评论").getUnclippedBoundsInRoot()
        val button = compose.onNodeWithContentDescription("评论排序")
        val buttonLeft = button.getUnclippedBoundsInRoot().left
        assertTrue(buttonLeft >= heading.right && buttonLeft <= heading.right + 8.dp)
        fun assertOrder(vararg contents: String) {
            val positions = contents.map { compose.onNodeWithText(it).getUnclippedBoundsInRoot().top }
            assertTrue(positions.zipWithNext().all { (a, b) -> a < b })
        }
        assertOrder("默认第一条", "点赞最高的评论", "最近发布的评论")
        button.performClick()
        compose.onNodeWithText("默认").assertIsSelected()
        compose.onNodeWithText("最新").performClick()
        assertOrder("最近发布的评论", "默认第一条", "点赞最高的评论")
        button.performClick()
        compose.onNodeWithText("最新").assertIsSelected()
        compose.onNodeWithText("最多点赞").performClick()
        assertOrder("点赞最高的评论", "默认第一条", "最近发布的评论")
        compose.runOnUiThread {
            memory.comments += comment("d", "后续页的热门评论", 1_730_000_000L, "20")
        }
        assertOrder("后续页的热门评论", "点赞最高的评论", "默认第一条")
        button.performClick()
        compose.onNodeWithText("最多点赞").assertIsSelected()
        compose.onNodeWithText("默认").performClick()
        assertOrder("默认第一条", "点赞最高的评论", "最近发布的评论")
    }

    @Test
    fun commentEditorAppearsAndClosesImmediately() {
        val activity = compose.activity
        val oldLoader = Coil.imageLoader(activity)
        val fixtureLoader =
            ImageLoader.Builder(activity)
                .components {
                    add(
                        object : Interceptor {
                            override suspend fun intercept(
                                chain: Interceptor.Chain
                            ): coil.request.ImageResult =
                                coil.request.ErrorResult(
                                    null,
                                    chain.request,
                                    IllegalStateException("fixture"),
                                )
                        }
                    )
                }
                .build()
        Coil.setImageLoader(fixtureLoader)
        try {
            val editor = mutableStateOf(0)
            var opens = 0
            compose.runOnUiThread {
                val state = RedState(activity.application)
                activity.setContent {
                    AppTheme(state) {
                        Box(Modifier.fillMaxSize()) {
                            Button(
                                modifier = Modifier.align(Alignment.BottomStart),
                                onClick = { editor.value = ++opens },
                            ) {
                                Text("原评论列表")
                            }
                            if (editor.value != 0)
                                key(editor.value) {
                                    CommentEditor(
                                        state,
                                        Note("fixture", "token", "pc_feed", JSONObject()),
                                        "comment",
                                        "样本用户",
                                        { editor.value = 0 },
                                        { editor.value = 0 },
                                    )
                                }
                        }
                    }
                }
            }
            compose.onNodeWithText("原评论列表").performClick()
            val emojiButton = compose.onNodeWithContentDescription("更多表情")
            emojiButton.assertIsDisplayed()
            compose.onNodeWithContentDescription("[笑哭R]").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("[笑哭R]")
            emojiButton.performClick()
            compose.onNodeWithContentDescription("返回键盘").assertExists()
            compose.onNode(hasSetTextAction()).assertTextContains("[笑哭R]")

            val scrim = compose.onNodeWithTag("comment-scrim")
            scrim.performClick()
            scrim.assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, editor.value) }
            compose.onNodeWithText("原评论列表").performTouchInput { click() }
            compose.runOnIdle { assertEquals(2, opens) }
            emojiButton.assertIsDisplayed()
            scrim.performClick()
            scrim.assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, editor.value) }
        } finally {
            Coil.setImageLoader(oldLoader)
            fixtureLoader.shutdown()
        }
    }

    @Test
    fun messagesUseReferenceGeometryForRealConversations() {
        val activity = compose.activity
        var selected: String? = null
        compose.runOnUiThread {
            val state = RedState(activity.application)
            state.preferences.set("fontScale", 1.0)
            state.session = JSONObject("""{"loggedIn":true,"user":{"user_id":"fixture"}}""")
            state.jsonLists["privateChats:{}"] =
                JsonMemory(0).apply {
                    loaded = true
                    more = false
                    rows =
                        listOf(
                            JSONObject(
                                """{"chat_user_id":"sample","info":{"nickname":"会话样本"},"last_msg_content":"预览"}"""
                            ),
                            JSONObject(
                                """{"chat_user_id":"sample2","info":{"nickname":"另一个会话"},"last_msg_content":"另一条预览"}"""
                            ),
                        )
                }
            activity.setContent {
                AppTheme(state) { MessageHome(state, activity) { selected = it } }
            }
        }
        val category = compose.onNodeWithTag("message-category:likes", useUnmergedTree = true)
        category.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        val tile = category.fetchSemanticsNode().boundsInRoot
        assertEquals(60f, tile.top, 1f)
        val avatars = compose.onAllNodesWithTag("message-avatar", useUnmergedTree = true)
        avatars.assertCountEquals(2)
        avatars[0].assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        assertEquals(176f, avatars[0].fetchSemanticsNode().boundsInRoot.top, 2f)
        assertEquals(
            72f,
            avatars[1].fetchSemanticsNode().boundsInRoot.top -
                avatars[0].fetchSemanticsNode().boundsInRoot.top,
            2f,
        )
        compose.onNodeWithText("赞和收藏").performClick()
        compose.onNodeWithText("会话样本").assertIsDisplayed()
        compose.onNodeWithText("点点").assertDoesNotExist()
        compose.runOnIdle { assertEquals("likes", selected) }
    }

    @Test
    fun galleryReturnsFourthImageToRecreatedDetailPager() {
        val activity = compose.activity
        val note =
            Note.parse(
                JSONObject(
                    """{"id":"note","note_card":{"title":"样本笔记","image_list":[
                {"url":"https://fixture.invalid/1.jpg","width":100,"height":100},
                {"url":"https://fixture.invalid/2.jpg"},
                {"url":"https://fixture.invalid/3.jpg"},
                {"url":"https://fixture.invalid/4.jpg"}]}}"""
                ),
                "pc_feed",
            )!!
        val gallery = mutableStateOf<Page.Gallery?>(null)
        val returning = mutableStateOf<GalleryMotion?>(null)
        val images =
            (1..4).associate { index ->
                "https://fixture.invalid/$index.jpg" to
                    Bitmap.createBitmap(index * 20, 20, Bitmap.Config.ARGB_8888)
            }
        val previous = Coil.imageLoader(activity)
        val loader =
            ImageLoader.Builder(activity)
                .components {
                    add(
                        object : Interceptor {
                            override suspend fun intercept(
                                chain: Interceptor.Chain
                            ): coil.request.ImageResult =
                                SuccessResult(
                                    BitmapDrawable(
                                        activity.resources,
                                        images.getValue(chain.request.data.toString()),
                                    ),
                                    chain.request,
                                    DataSource.MEMORY,
                                )
                        }
                    )
                }
                .build()
        try {
            Coil.setImageLoader(loader)
            compose.runOnUiThread {
                val state = RedState(activity.application)
                state.preferences.set("liquidGlass", false)
                state.details[note] =
                    DetailMemory(note).apply {
                        loaded = true
                        commentsLoaded = true
                    }
                activity.setContent {
                    AppTheme(state) {
                        val saved = rememberSaveableStateHolder()
                        val current = gallery.value
                        if (current != null) {
                            Button(
                                onClick = {
                                    val image = images.getValue(current.urls[3]).asImageBitmap()
                                    current.flight!!.apply {
                                        viewport = Rect(0f, 0f, 412f, 915f)
                                        galleryPosition = {
                                            3 to GalleryPlane(image, Rect(0f, 406f, 412f, 509f))
                                        }
                                    }
                                    returning.value =
                                        GalleryMotion(current, mutableFloatStateOf(1f), true, 0)
                                    gallery.value = null
                                }
                            ) {
                                Text("从图库第四张返回")
                            }
                        } else
                            saved.SaveableStateProvider("detail") {
                                CompositionLocalProvider(
                                    LocalGalleryMotion provides returning.value
                                ) {
                                    DetailPage(
                                        state,
                                        note,
                                        null,
                                        {},
                                        {},
                                        { gallery.value = it },
                                        {},
                                        { _, _ -> },
                                    )
                                }
                            }
                    }
                }
            }
            compose.onNodeWithText("1/4").assertIsDisplayed()
            compose.onNodeWithContentDescription("图片 1").performClick()
            compose.onNodeWithText("从图库第四张返回").performClick()
            compose.onNodeWithText("4/4").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(3, returning.value!!.index)
                assertTrue(returning.value!!.hasFlight)
                assertEquals(80, returning.value!!.image!!.width)
                returning.value = null
            }
            compose.onNodeWithText("4/4").assertIsDisplayed()
            compose.onNodeWithContentDescription("图片 4").assertIsDisplayed()
        } finally {
            Coil.setImageLoader(previous)
            loader.shutdown()
        }
    }

    @Test
    fun profileKeepsHeaderTabAndCardPositionWhenReturningFromSavedAndLikedPosts() {
        val activity = compose.activity
        val opened = mutableStateOf(false)
        compose.runOnUiThread {
            val state = RedState(activity.application)
            state.session =
                JSONObject("""{"loggedIn":true,"user":{"user_id":"owner","nickname":"登录快照"}}""")
            state.accountProfile.profile = JSONObject("""{"nickname":"已加载的主页","desc":"主页资料"}""")
            for ((method, title) in
                listOf("userPosts" to "本人帖子", "collections" to "收藏帖子", "likedNotes" to "点赞帖子")) {
                state.feed("$method:${JSONObject(mapOf("id" to state.userId))}").apply {
                    notes = listOf(Note(title, "", "pc_user", JSONObject().put("title", title)))
                    more = false
                }
            }
            activity.setContent {
                AppTheme(state) {
                    val saved = rememberSaveableStateHolder()
                    if (opened.value) Button(onClick = { opened.value = false }) { Text("关闭帖子") }
                    else
                        saved.SaveableStateProvider("account") {
                            AccountPage(state, activity, { opened.value = true }, {}, {}, {}, {})
                        }
                }
            }
        }
        for ((tab, title) in listOf("收藏" to "收藏帖子", "赞过" to "点赞帖子")) {
            compose.onNodeWithText(tab).performClick()
            val card = compose.onNodeWithText(title)
            val top = card.fetchSemanticsNode().boundsInRoot.top
            card.performClick()
            compose.onNodeWithText("关闭帖子").performClick()
            compose.onNodeWithText("已加载的主页").assertIsDisplayed()
            assertEquals(top, card.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
    }
}
