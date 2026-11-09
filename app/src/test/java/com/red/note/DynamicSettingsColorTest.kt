package com.red.note

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DynamicSettingsColorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dynamicCardsStayDistinctInLightDarkAndPureBlack() {
        val state = RedState(RuntimeEnvironment.getApplication())
        val prefs = state.preferences
        prefs.set("dynamicColor", true)
        prefs.set("liquidGlass", false)
        prefs.set("fontScale", 1.0)
        var page = Color.Unspecified
        var card = Color.Unspecified
        compose.setContent {
            AppTheme(state) {
                val colors = MaterialTheme.colorScheme
                val scaffold = LocalScaffoldColor.current
                SideEffect {
                    page = scaffold
                    card = colors.settingsCardColor(scaffold)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    PersonalizationContent(state)
                }
            }
        }
        for ((theme, pureBlack) in listOf(1 to false, 2 to false, 2 to true)) {
            compose.runOnIdle {
                prefs.set("theme", theme)
                prefs.set("pureBlack", pureBlack)
            }
            compose.waitForIdle()
            val background = page.toArgb()
            val foreground = card.toArgb()
            assertTrue(
                "card must be visibly separated from the page",
                listOf(
                    abs(android.graphics.Color.red(background) - android.graphics.Color.red(foreground)),
                    abs(android.graphics.Color.green(background) - android.graphics.Color.green(foreground)),
                    abs(android.graphics.Color.blue(background) - android.graphics.Color.blue(foreground)),
                ).max() >= 12,
            )
            if (pureBlack) assertEquals(Color.Black, page)
            for (section in listOf("明暗模式", "字体粗细")) {
                val bounds = compose.onNodeWithTag("settings-card:$section", useUnmergedTree = true)
                    .performScrollTo().fetchSemanticsNode().boundsInRoot
                compose.runOnUiThread {
                    val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
                    val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(frame))
                        val y = bounds.top.toInt() + 50
                        assertEquals("page $section", background, frame.getPixel(bounds.left.toInt() - 8, y))
                        assertEquals("card $section", foreground, frame.getPixel(bounds.left.toInt() + 4, y))
                    } finally { frame.recycle() }
                }
            }
        }
    }

    @Test
    @Config(sdk = [24])
    fun dynamicPreferenceKeepsPresetCardsOnOlderAndroid() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("theme", 1)
        state.preferences.set("dynamicColor", false)
        var card = Color.Unspecified
        compose.setContent {
            AppTheme(state) {
                val value = MaterialTheme.colorScheme.surfaceContainerLow
                SideEffect { card = value }
            }
        }
        val preset = compose.runOnIdle { card }
        compose.runOnIdle { state.preferences.set("dynamicColor", true) }
        compose.runOnIdle { assertEquals(preset, card) }
    }
}
