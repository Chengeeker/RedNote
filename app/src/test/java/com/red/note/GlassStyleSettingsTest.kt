package com.red.note

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassStyleSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chooseBothMaterialsAndKeepChoiceWhenGlassIsDisabled() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("compose_preferences", 0).edit().clear().commit()
        val state = RedState(app)
        state.preferences.set("liquidGlass", true)
        state.preferences.set("fontScale", 1.5)
        compose.setContent {
            AppTheme(state) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonalizationContent(state)
                }
            }
        }
        compose.onNodeWithText("底栏玻璃样式").performScrollTo().performClick()
        compose.onNode(hasText("通透磨砂") and hasAnyAncestor(isDialog())).assertIsSelected()
        assertTrue(compose.onNode(hasText("通透磨砂") and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot.top < compose.onNode(hasText("经典玻璃") and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot.top)
        compose.onNode(hasText("通透磨砂") and hasText("底色更厚实，可读性更高")).assertIsDisplayed()
        compose.onNode(hasText("经典玻璃") and hasText("更多背景透出，对比度更低")).assertIsDisplayed()
        compose.onNode(hasText("经典玻璃") and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(Preferences(app).bool("translucentNavigationGlass", false)) }
        compose.onNodeWithText("经典玻璃").assertIsDisplayed()
        compose.runOnUiThread { state.preferences.set("liquidGlass", false) }
        compose.onNodeWithText("底栏玻璃样式").assertDoesNotExist()
        compose.runOnUiThread {
            state.preferences.set("liquidGlass", true)
            state.preferences.set("theme", 2)
        }
        compose.onNodeWithText("底栏玻璃样式").performScrollTo().performClick()
        compose.onNode(hasText("经典玻璃") and hasAnyAncestor(isDialog())).assertIsSelected()
        compose.onNode(hasText("通透磨砂") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertFalse(Preferences(app).bool("translucentNavigationGlass", false)) }
        compose.runOnUiThread { state.preferences.set("floating", false) }
        compose.onNodeWithText("底栏玻璃样式").assertDoesNotExist()
    }
}
