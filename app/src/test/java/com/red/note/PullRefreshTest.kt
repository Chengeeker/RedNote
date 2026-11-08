package com.red.note

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PullRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun feedFollowsPullAndSpringsBackAfterRefreshFailure() = checkPull(grid = true)
    @Test fun messageListFollowsPullAndSpringsBackAfterRefreshFailure() = checkPull(grid = false)

    private fun checkPull(grid: Boolean) {
        val state = RedState(RuntimeEnvironment.getApplication())
        val args = mapOf("id" to "")
        state.feed("userPosts:${JSONObject(args)}").apply {
            notes = listOf(Note("card", "", "pc_user", JSONObject().put("title", "已有内容")))
            more = false
        }
        state.jsonLists["subComments:${JSONObject(args)}"] = JsonMemory(1).apply {
            rows = listOf(JSONObject().put("id", "row"))
            loaded = true
            more = false
        }
        state.preferences.set("liquidGlass", false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        state.runtime.worker.execute {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.setContent {
                AppTheme(state) {
                    if (grid)
                        NoteFeed(state, "userPosts", args, "pc_user", {})
                    else
                        JsonList(state, "subComments", args, "comments") {
                            Text("已有内容", Modifier.fillMaxWidth().height(120.dp))
                        }
                }
            }
            val content = compose.onNodeWithText("已有内容")
            val initial = content.getUnclippedBoundsInRoot().top
            val list = compose.onNode(hasScrollAction())
            compose.mainClock.autoAdvance = false
            list.performTouchInput {
                down(Offset(center.x, 100f))
                moveBy(Offset(0f, 40f))
                moveBy(Offset(0f, 50f))
            }
            compose.mainClock.advanceTimeByFrame()
            val pulled = content.getUnclippedBoundsInRoot().top
            assertTrue("content must follow a pull below the refresh threshold", pulled > initial)
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(600)
            assertEquals(initial.value, content.getUnclippedBoundsInRoot().top.value, 1f)
            assertNull(state.lastFailure)

            list.performTouchInput {
                down(Offset(center.x, 100f))
                repeat(6) { moveBy(Offset(0f, 50f)) }
                up()
            }
            compose.mainClock.advanceTimeBy(600)
            assertEquals(initial.value + 80f, content.getUnclippedBoundsInRoot().top.value, 2f)
            release.countDown()
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { state.lastFailure != null }
            compose.waitForIdle()
            assertEquals(initial.value, content.getUnclippedBoundsInRoot().top.value, 1f)
            content.assertIsDisplayed()
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            release.countDown()
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun loadingNextFeedPageDoesNotMoveExistingCards() {
        val state = RedState(RuntimeEnvironment.getApplication())
        val args = mapOf("id" to "")
        val memory = state.feed("userPosts:${JSONObject(args)}").apply {
            notes = listOf(Note("card", "", "pc_user", JSONObject().put("title", "已有内容")))
            more = false
        }
        val paginate = androidx.compose.runtime.mutableStateOf(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        state.runtime.worker.execute {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.setContent {
                AppTheme(state) {
                    key(paginate.value) { NoteFeed(state, "userPosts", args, "pc_user", {}) }
                }
            }
            val card = compose.onNodeWithText("已有内容")
            val top = card.getUnclippedBoundsInRoot().top
            compose.runOnIdle {
                memory.more = true
                paginate.value = true
            }
            compose.mainClock.advanceTimeBy(600)
            assertEquals(top, card.getUnclippedBoundsInRoot().top)
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            release.countDown()
        }
    }
}
