package com.red.note

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Robolectric #9691 uses Android 12's shader compiler below SDK 35; it cannot evaluate child shaders.
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class NavigationGlassBackendTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun composeBackdropSeparatesDarkMaterialsAndCanSwitchBack() {
        var clear by mutableStateOf(false)
        var dark by mutableStateOf(true)
        var pressed by mutableFloatStateOf(0f)
        compose.setContent {
            val layer = rememberGraphicsLayer()
            val backdrop = remember(layer) { GlassBackdrop(layer) }
            val labels = rememberGraphicsLayer()
            val selected = rememberGraphicsLayer()
            Box(Modifier.size(256.dp, 86.dp).testTag("glass")) {
                Box(Modifier.fillMaxSize().recordGlassBackdrop(backdrop).background(Color.Blue))
                BackdropNavigation(backdrop, labels, selected, clear, 0f, pressed, dark, Color.Blue, Modifier.fillMaxSize())
            }
        }
        fun sample(name: String): Int {
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("glass").fetchSemanticsNode().boundsInRoot
            return compose.runOnUiThread {
                val view = compose.activity.findViewById<View>(android.R.id.content)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(canvas, "setHwFeaturesInSwModeEnabled",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
                view.draw(canvas)
                java.io.File("build/parity/backdrop-$name.png").apply { parentFile.mkdirs() }.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                if (dark && name.contains("dark")) assertTrue("dark capsule must retain its white rim", AndroidColor.red(bitmap.getPixel(bounds.left.toInt() + 128, bounds.top.toInt() + 15)) > 20)
                bitmap.getPixel(bounds.left.toInt() + 128, bounds.top.toInt() + 43).also { bitmap.recycle() }
            }
        }
        sample("warmup")
        val frosted = sample("dark-frosted")
        assertTrue("frosted=$frosted", AndroidColor.blue(frosted) in 55..85)
        compose.runOnUiThread { clear = true }
        val translucent = sample("dark-translucent")
        assertTrue("clear=$translucent frosted=$frosted", AndroidColor.blue(translucent) > AndroidColor.blue(frosted) + 60)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { pressed = 1f }
        compose.mainClock.advanceTimeByFrame()
        sample("held")
        compose.runOnUiThread { pressed = 0f; clear = false }
        compose.mainClock.autoAdvance = true
        assertEquals(frosted, sample("restored"))
        compose.runOnUiThread { dark = false }
        val light = sample("light-frosted")
        assertTrue(AndroidColor.red(light) in 180..205)
        compose.runOnUiThread { clear = true }
        assertTrue(AndroidColor.red(light) > AndroidColor.red(sample("light-translucent")) + 60)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 32], qualifiers = "w360dp-h800dp-mdpi")
class LegacyNavigationGlassTest {
    @Test
    @Config(sdk = [24])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun androidSevenCanAttachTheViewBackend() {
        val controller = org.robolectric.Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val glass = QweaNavigationView(controller.get())
        controller.get().setContentView(glass)
        glass.layout(0, 0, 256, 86)
        assertTrue(glass.ready)
        glass.close()
        controller.pause().stop().destroy()
    }

    @Test
    fun missingNativeLibraryReportsFailureWithoutCrashing() {
        val controller = org.robolectric.Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val glass = QweaNavigationView(activity)
        activity.setContentView(glass)
        glass.layout(0, 0, 256, 86)
        var failure: String? = null
        glass.failure = { failure = it }
        val source = Bitmap.createBitmap(128, 43, Bitmap.Config.ARGB_8888)
        glass.render(source, null, 0f, 0f, true, 1f) { assertTrue(it) }
        val output = Bitmap.createBitmap(256, 86, Bitmap.Config.ARGB_8888)
        glass.draw(Canvas(output))
        assertNotNull(failure)
        assertFalse(glass.ready)
        assertTrue(source.isRecycled)
        output.recycle()
        controller.pause().stop().destroy()
    }

    @Test
    fun viewMaterialsUpdateBackgroundAndReleaseOwnedBitmaps() {
        val controller = org.robolectric.Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val glass = QweaNavigationView(activity)
        // Windows cannot load Android ARM JNI. Exercise the real View adapter with the library's Kotlin blur.
        for (index in 0 until glass.childCount) {
            (glass.getChildAt(index) as com.example.liquidglass.LiquidGlassView).apply {
                blurMethod = com.example.liquidglass.BlurMethod.BOX_BLUR
                enableChromaticAberration = false
            }
        }
        activity.setContentView(glass)
        glass.measure(View.MeasureSpec.makeMeasureSpec(256, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(86, View.MeasureSpec.EXACTLY))
        glass.layout(0, 0, 256, 86)
        assertTrue(glass.ready)
        var error: String? = null
        glass.failure = { error = it }
        val source = Bitmap.createBitmap(128, 43, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.BLUE) }
        val output = Bitmap.createBitmap(256, 86, Bitmap.Config.ARGB_8888)
        fun sample(clear: Boolean, dark: Boolean = true, background: Bitmap? = null, pressed: Float = 0f): Int {
            glass.render(background, null, 0f, pressed, dark, 1f, translucent = clear) { assertTrue(it) }
            output.eraseColor(AndroidColor.TRANSPARENT)
            glass.draw(Canvas(output))
            assertNull(error)
            assertEquals(0, AndroidColor.alpha(output.getPixel(2, 43)))
            if (dark) assertTrue("legacy capsule must retain its white rim", AndroidColor.red(output.getPixel(128, 15)) > 20)
            return output.getPixel(128, 43)
        }
        val frosted = sample(false, background = source)
        assertTrue(AndroidColor.blue(frosted) in 55..85)
        assertTrue(AndroidColor.blue(sample(true)) > AndroidColor.blue(frosted) + 60)
        sample(true, pressed = 1f)
        assertEquals(frosted, sample(false))
        val light = sample(false, dark = false)
        assertTrue("light=${Integer.toHexString(light)} tint=${Integer.toHexString((glass.getChildAt(0) as com.example.liquidglass.LiquidGlassView).glassTint)}", AndroidColor.red(light) in 180..205)
        assertTrue(AndroidColor.red(light) > AndroidColor.red(sample(true, dark = false)) + 60)
        val updated = Bitmap.createBitmap(128, 43, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.RED) }
        assertTrue(AndroidColor.red(sample(true, background = updated)) > 100)
        assertTrue(source.isRecycled)
        glass.close()
        glass.close()
        assertTrue(updated.isRecycled)
        output.recycle()
        controller.pause().stop().destroy()
    }
}
