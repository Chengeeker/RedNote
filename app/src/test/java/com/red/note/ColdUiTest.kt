package com.red.note

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColdUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun weightSliderPreviewsBeforeSavingAndResetsToSystem() {
        val app = RuntimeEnvironment.getApplication()
        val state = RedState(app)
        val prefs = state.preferences
        prefs.set("fontScale", 1.0)
        prefs.set("customWeight", true)
        prefs.set("weightDelta", 100)
        state.session = org.json.JSONObject().put("systemFontWeight", 150)
        compose.setContent {
            AppTheme(state) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonalizationContent(state)
                }
            }
        }
        val slider = compose.onNodeWithContentDescription("字体粗细").performScrollTo()
        val preview = compose.onNode(hasText("这是一段用于预览字体粗细的示例文本", substring = true))
        fun previewWeight(): Int {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            preview.performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) { it(results) }
            return results.single().layoutInput.style.fontWeight!!.weight
        }
        Assert.assertEquals(500, previewWeight())
        slider.performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * 2f / 3f, center.y))
            moveTo(androidx.compose.ui.geometry.Offset(width - 1f, center.y), 100)
        }
        Assert.assertEquals(100, prefs.int("weightDelta"))
        Assert.assertEquals(700, previewWeight())
        slider.performTouchInput { up() }
        compose.runOnIdle {
            val restored = Preferences(app)
            Assert.assertEquals(300, restored.int("weightDelta"))
            Assert.assertTrue(restored.bool("customWeight"))
        }
        compose.onNodeWithText("选择字体粗细").assertDoesNotExist()
        compose.onNodeWithText("重置").performClick()
        Assert.assertFalse(prefs.bool("customWeight"))
        Assert.assertEquals(300, prefs.int("weightDelta"))
        Assert.assertEquals(550, previewWeight())
        slider.performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) { it(-300f) }
        Assert.assertEquals(-300, Preferences(app).int("weightDelta"))
        Assert.assertTrue(prefs.bool("customWeight"))
        Assert.assertEquals(100, previewWeight())
    }

    @Test
    fun releasingWaterBeginsContractionImmediatelyAndFadesToRest() {
        val touching = mutableStateOf(true)
        var pressure = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val value = navigationGlassMotion(1, touching.value, true, 1f, longPressed = true)
            SideEffect { pressure = value.second }
        }
        compose.runOnIdle { Assert.assertEquals(1f, pressure, .001f) }
        compose.runOnUiThread { touching.value = false }
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        var previous =
            compose.runOnIdle {
                Assert.assertTrue(
                    "release must not hold full pressure for 250 ms (pressure=$pressure)",
                    pressure in .001f..0.999f,
                )
                pressure
            }
        repeat(4) {
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle {
                Assert.assertTrue(pressure <= previous)
                previous = pressure
            }
        }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { Assert.assertEquals(0f, pressure, .001f) }
        Assert.assertArrayEquals(floatArrayOf(1f, 1f), jellyScale(100f, 0f), .001f)
    }

    @Test
    fun onlyLongPressActivatesWaterWhileTapsAndQuickDragsMovePlate() {
        val selected = mutableIntStateOf(0)
        val dragging = mutableStateOf(false)
        val longPressed = mutableStateOf(false)
        var lens = 0f
        var press = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val value =
                navigationGlassMotion(
                    selected.intValue,
                    dragging.value,
                    dragging.value,
                    2f,
                    longPressed.value,
                )
            SideEffect {
                lens = value.first
                press = value.second
            }
        }
        compose.runOnUiThread { selected.intValue = 2 }
        repeat(12) {
            compose.mainClock.advanceTimeBy(16)
            compose.runOnIdle { Assert.assertEquals(0f, press, 0f) }
        }
        compose.runOnIdle { Assert.assertTrue(lens > 0f) }
        compose.runOnUiThread { selected.intValue = 1 }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { Assert.assertEquals(0f, press, 0f) }
        compose.runOnUiThread { dragging.value = true }
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { Assert.assertEquals(0f, press, 0f) }
        }
        compose.runOnIdle { Assert.assertTrue(lens > 1.5f) }
        compose.runOnUiThread { longPressed.value = true }
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle { Assert.assertTrue("long press pressure=$press", press > .1f) }
        compose.runOnUiThread {
            dragging.value = false
            longPressed.value = false
        }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle { Assert.assertEquals(0f, press, .001f) }
    }

    @Test
    fun personalizationHeadingsStayOutsideCardsWithConsistentTextRoles() {
        val state = RedState(RuntimeEnvironment.getApplication())
        state.preferences.set("fontScale", 1.0)
        state.preferences.set("customWeight", true)
        state.preferences.set("weightDelta", 100)
        compose.setContent {
            AppTheme(state) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonalizationContent(state)
                }
            }
        }
        listOf("明暗模式", "色彩方案", "字体粗细", "字体大小", "底部导航", "显示效果", "触感与震动").forEach { title ->
            compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo()
            val heading =
                compose
                    .onNodeWithText(title, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .layoutInfo
                    .coordinates
            val card =
                compose
                    .onNodeWithTag("settings-card:$title", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .layoutInfo
                    .coordinates
            Assert.assertTrue(
                "$title must be outside card",
                heading.positionInRoot().y + heading.size.height < card.positionInRoot().y,
            )
        }
        compose.onNodeWithText("应用字体大小").assertDoesNotExist()
        compose.onNodeWithText("自动匹配系统深色/浅色设置").assertDoesNotExist()
        compose.onNodeWithText("动态取色", useUnmergedTree = true).assertExists()
        val firstColor = compose.onNodeWithText("经典红", useUnmergedTree = true).performScrollTo()
        val colorTitle = compose.onNodeWithText("Material 3 预置主题色", useUnmergedTree = true)
        Assert.assertTrue(
            firstColor.fetchSemanticsNode().boundsInRoot.top -
                colorTitle.fetchSemanticsNode().boundsInRoot.bottom < 20f
        )
        fun style(text: String): androidx.compose.ui.text.TextStyle {
            compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) {
                it(results)
            }
            return results.single().layoutInput.style
        }
        val radio = style("浅色模式")
        val toggle = style("纯黑深色模式")
        val weightValue = style("中等")
        Assert.assertEquals(16f, radio.fontSize.value, .01f)
        Assert.assertEquals(radio.fontSize, toggle.fontSize)
        Assert.assertEquals(radio.fontWeight, toggle.fontWeight)
        Assert.assertEquals(radio.fontWeight, weightValue.fontWeight)
        Assert.assertEquals(700, radio.fontWeight!!.weight)
        compose.onNodeWithText("明暗模式", useUnmergedTree = true).performScrollTo()
        compose.runOnUiThread {
            val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val frame =
                android.graphics.Bitmap.createBitmap(
                    view.width,
                    view.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            view.draw(android.graphics.Canvas(frame))
            val file =
                java.io.File("build/parity/personalization.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use {
                frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            frame.recycle()
        }
    }

    @Test
    fun coldDecodeKeepsOpeningPreviewUntilFlightFinishes() {
        val app = RuntimeEnvironment.getApplication()
        val oldLoader = coil.Coil.imageLoader(app)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val decoded = java.util.concurrent.atomic.AtomicBoolean(false)
        val red =
            android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.ARGB_8888)
        red.eraseColor(android.graphics.Color.RED)
        val blue =
            android.graphics.Bitmap.createBitmap(20, 40, android.graphics.Bitmap.Config.ARGB_8888)
        blue.eraseColor(android.graphics.Color.BLUE)
        val preview = blue.asImageBitmap()
        val loader =
            coil.ImageLoader.Builder(app)
                .eventListener(
                    object : coil.EventListener {
                        override fun onSuccess(
                            request: coil.request.ImageRequest,
                            result: coil.request.SuccessResult,
                        ) {
                            decoded.set(true)
                        }
                    }
                )
                .memoryCache(null)
                .diskCache(null)
                .components {
                    add(
                        object : coil.intercept.Interceptor {
                            override suspend fun intercept(
                                chain: coil.intercept.Interceptor.Chain
                            ): coil.request.ImageResult {
                                gate.await()
                                return coil.request.SuccessResult(
                                    android.graphics.drawable.BitmapDrawable(app.resources, red),
                                    chain.request,
                                    coil.decode.DataSource.NETWORK,
                                )
                            }
                        }
                    )
                }
                .build()
        coil.Coil.setImageLoader(loader)
        val held = mutableStateOf(true)
        var delivered: androidx.compose.ui.graphics.ImageBitmap? = null
        try {
            compose.setContent {
                Picture(
                    "https://fixture.invalid/cold.jpg",
                    null,
                    Modifier.size(100.dp),
                    scale = androidx.compose.ui.layout.ContentScale.Fit,
                    background = androidx.compose.ui.graphics.Color.Transparent,
                    retained = preview,
                    holdPreview = held.value,
                    onImageReady = { delivered = it },
                )
            }
            fun assertCenter(color: Int) {
                compose.waitForIdle()
                compose.runOnUiThread {
                    val view =
                        compose.activity.findViewById<android.view.View>(android.R.id.content)
                    val frame =
                        android.graphics.Bitmap.createBitmap(
                            view.width,
                            view.height,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    view.draw(android.graphics.Canvas(frame))
                    Assert.assertEquals(color, frame.getPixel(50, 50))
                    Assert.assertEquals(
                        if (color == android.graphics.Color.BLUE) android.graphics.Color.BLACK
                        else android.graphics.Color.RED,
                        frame.getPixel(10, 50),
                    )
                    frame.recycle()
                }
            }
            assertCenter(android.graphics.Color.BLUE)
            gate.complete(Unit)
            compose.waitUntil(5000) { decoded.get() }
            compose.waitForIdle()
            assertCenter(android.graphics.Color.BLUE)
            compose.runOnUiThread {
                Assert.assertSame(preview, delivered)
                held.value = false
            }
            assertCenter(android.graphics.Color.RED)
            compose.runOnIdle { Assert.assertNotSame(preview, delivered) }
        } finally {
            coil.Coil.setImageLoader(oldLoader)
            loader.shutdown()
        }
    }
}
