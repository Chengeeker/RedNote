@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.red.note

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedTitleStartupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun feedTitleRendersWithRealSharedScopesAndCanEnterDetail() {
        val note = Note("startup-note", "", "pc_feed", JSONObject())
        var detail by mutableStateOf(false)
        compose.setContent {
            SharedTransitionLayout {
                val transition = this
                AnimatedContent(detail, label = "startup-title") { expanded ->
                    CompositionLocalProvider(
                        LocalSharedTransition provides transition,
                        LocalPageVisibility provides this,
                    ) {
                        Text(if (expanded) "详情标题" else "首页标题", Modifier.sharedTitle(note))
                    }
                }
            }
        }
        compose.onNodeWithText("首页标题").assertIsDisplayed()
        compose.runOnUiThread { detail = true }
        compose.onNodeWithText("详情标题").assertIsDisplayed()
        compose.runOnUiThread { detail = false }
        compose.onNodeWithText("首页标题").assertIsDisplayed()
    }
}
