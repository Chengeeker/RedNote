package com.red.note

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
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
@Config(sdk = [24, 35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun plainGalleryButtonsKeepTheirShapeAndOnlyChangeOpacity() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("liquidGlass", false)
        state.preferences.set("theme", 2)
        val enabled = mutableStateOf(true)
        var returned = 0
        var saved = 0
        compose.setContent {
            AppTheme(state) {
                val backdrop = GlassBackdrop(rememberGraphicsLayer())
                Box(Modifier.size(112.dp, 48.dp)) {
                    Canvas(Modifier.fillMaxSize().recordGlassBackdrop(backdrop)) {
                        repeat(28) { stripe ->
                            drawRect(
                                if (stripe % 2 == 0) Color.Red else Color.Blue,
                                topLeft = Offset(stripe * 4f, 0f),
                                size = Size(4f, size.height),
                            )
                        }
                    }
                    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            GalleryControl(state, "arrow_back_rounded", "返回", enabled.value) { returned++ }
                            GalleryControl(state, "download_rounded", "保存图片", enabled.value) { saved++ }
                        }
                    }
                }
            }
        }
        fun assertStripes(label: String, brightness: Int) {
            val node = compose.onNodeWithContentDescription(label)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertEquals(48f, bounds.width, .01f)
            assertEquals(48f, bounds.height, .01f)
            compose.runOnUiThread {
                val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
                val root = view as ViewGroup
                assertFalse(root.descendants.any { it is GlassView || it is android.view.TextureView })
                // Host native Canvas cannot render Compose layers correctly on API 24.
                if (android.os.Build.VERSION.SDK_INT < 26) return@runOnUiThread
                val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(AndroidCanvas(frame))
                    java.io.File("build/parity/gallery-plain-${android.os.Build.VERSION.SDK_INT}-$label-$brightness.png")
                        .apply { parentFile.mkdirs() }
                        .outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    for (x in 12..35) {
                        val color = frame.getPixel(bounds.left.toInt() + x, bounds.top.toInt() + 8)
                        val expected = if (x / 4 % 2 == 0) android.graphics.Color.RED else android.graphics.Color.BLUE
                        val red = if (expected == android.graphics.Color.RED) brightness else 0
                        val blue = if (expected == android.graphics.Color.BLUE) brightness else 0
                        assertTrue("$label x=$x red=$red actual=${android.graphics.Color.red(color)}", abs(red - android.graphics.Color.red(color)) <= 2)
                        assertEquals(0, android.graphics.Color.green(color))
                        assertTrue("$label x=$x blue=$blue actual=${android.graphics.Color.blue(color)}", abs(blue - android.graphics.Color.blue(color)) <= 2)
                    }
                } finally {
                    frame.recycle()
                }
            }
        }
        for (label in listOf("返回", "保存图片")) {
            val button = compose.onNodeWithContentDescription(label)
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertStripes(label, 153)
            compose.mainClock.autoAdvance = false
            button.performTouchInput { down(center) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(120)
            assertStripes(label, 184)
            assertEquals(bounds, button.fetchSemanticsNode().boundsInRoot)
            button.performTouchInput { up() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(120)
            assertStripes(label, 153)
            compose.mainClock.autoAdvance = true
        }
        assertEquals(1, returned)
        assertEquals(1, saved)
        compose.runOnIdle {
            state.preferences.set("theme", 1)
            enabled.value = false
        }
        compose.onNodeWithContentDescription("返回").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithContentDescription("保存图片").assertIsNotEnabled().performTouchInput { click() }
        assertStripes("返回", 153)
        assertStripes("保存图片", 153)
        assertEquals(1, returned)
        assertEquals(1, saved)
    }
}
