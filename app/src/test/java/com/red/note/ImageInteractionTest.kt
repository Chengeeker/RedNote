package com.red.note

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageInteractionTest {
    @Test
    fun galleryDoubleTapZoomsTwiceThenResetsAndRespectsHaptics() {
        val context = RuntimeEnvironment.getApplication()
        val looper = shadowOf(Looper.getMainLooper())
        for ((imageWidth, firstScale) in listOf(1000 to 2.5f, 200 to 3.5f)) {
            var plane: GalleryPlane? = null
            var singleTaps = 0
            val view = ZoomImage(context, { singleTaps++ }, {}, { plane = it })
            val bitmap = Bitmap.createBitmap(imageWidth, 1000, Bitmap.Config.ARGB_8888)
            view.layout(0, 0, 400, 400)
            view.setPicture(BitmapDrawable(context.resources, bitmap))
            val fittedWidth = plane!!.rect.width
            val shadow = shadowOf(view)
            fun tap(second: Boolean = false, drag: Boolean = false, cancel: Boolean = false) {
                val downTime = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float = 200f) {
                    org.robolectric.util.ReflectionHelpers.setField(shadow, "hapticFeedbackPerformed", -1)
                    val event = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), action, x, 200f, 0,
                    )
                    try { view.onTouchEvent(event) } finally { event.recycle() }
                    val expected =
                        if (second && !drag && action == MotionEvent.ACTION_UP && view.hapticsEnabled)
                            HapticFeedbackConstants.KEYBOARD_TAP
                        else -1
                    assertEquals(expected, shadow.lastHapticFeedbackPerformed())
                }
                send(MotionEvent.ACTION_DOWN)
                looper.idleFor(Duration.ofMillis(20))
                if (drag) send(MotionEvent.ACTION_MOVE, 300f)
                send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
            }
            fun doubleTap() {
                tap(second = false)
                looper.idleFor(Duration.ofMillis(50))
                tap(second = true)
            }
            fun assertScale(expected: Float) {
                looper.idleFor(Duration.ofMillis(350))
                assertEquals(expected, plane!!.rect.width / fittedWidth, .01f)
            }
            try {
                tap()
                assertScale(1f)
                assertEquals(1, singleTaps)

                tap()
                looper.idleFor(Duration.ofMillis(50))
                tap(second = true, drag = true)
                assertScale(1f)

                tap()
                looper.idleFor(Duration.ofMillis(50))
                tap(second = true, cancel = true)
                assertScale(1f)

                doubleTap()
                assertScale(firstScale)
                doubleTap()
                assertScale(6f)
                doubleTap()
                assertScale(1f)
                view.hapticsEnabled = false
                repeat(3) {
                    doubleTap()
                    looper.idleFor(Duration.ofMillis(30))
                }
                assertScale(1f)
                assertEquals(1, singleTaps)
            } finally {
                view.release()
                bitmap.recycle()
            }
        }
    }

    @Test
    fun galleryWarmupReachesThirdAndFourthImagesWithoutRequestingVisibleOriginalsAgain() {
        assertEquals(
            listOf("third", "fourth"),
            galleryHdrWarmupUrls(listOf("first", "second", "third", "fourth"), 0),
        )
        assertEquals(
            listOf("second", "first"),
            galleryHdrWarmupUrls(listOf("first", "second", "third", "fourth"), 3),
        )
        assertTrue(galleryHdrWarmupUrls(listOf("first", "second"), 0).isEmpty())
        assertEquals(4, galleryHdrWarmupUrls(List(100) { it.toString() }, 50).size)
    }

    @Test
    fun openingFreezesGeometryAfterFirstValidLayout() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).asImageBitmap()
        var origin = Rect(200f, 100f, 380f, 350f)
        val flight = GalleryFlight(bitmap, { GalleryAnchor(origin, true) }, 0)
        val page = Page.Gallery(listOf("fixture"), 0, "", flight = flight)
        val motion = GalleryMotion(page, mutableFloatStateOf(0f), false, 0)
        assertFalse(motion.hasFlight)
        flight.viewport = Rect.Zero
        assertFalse(motion.hasFlight)
        flight.viewport = Rect(0f, 100f, 400f, 500f)
        assertTrue(motion.hasFlight)
        origin = Rect(-400f, 100f, -200f, 350f)
        flight.viewport = Rect(0f, 104f, 400f, 504f)
        assertEquals(Rect(200f, 100f, 380f, 350f), motion.anchor!!.viewport)
        assertEquals(Rect(0f, 100f, 400f, 500f), motion.viewport)
    }

    @Test
    fun imagePanUsesRetainedFlutterSpringSamples() {
        val samples =
            org.json.JSONObject(
                javaClass
                    .getResourceAsStream("/flutter-gallery-pan.json")!!
                    .bufferedReader()
                    .readText()
            )
        samples.keys().forEach { ms ->
            assertEquals(
                samples.getDouble(ms).toFloat(),
                galleryPanProgress(ms.toFloat() / 1000),
                .00001f,
            )
        }
    }

    @Test
    fun inlineHdrRequiresCompleteGainmapAppSegmentBeforeImageData() {
        fun app(marker: Int, text: String): ByteArray {
            val payload = text.toByteArray(Charsets.ISO_8859_1)
            val length = payload.size + 2
            return byteArrayOf(-1, marker.toByte(), (length shr 8).toByte(), length.toByte()) +
                payload
        }
        val start = byteArrayOf(-1, -40)
        val iso = app(226, "urn:iso:std:iso:ts:21496:-1\u0000metadata")
        val xmp =
            app(225, "http://ns.adobe.com/xap/1.0/\u0000http://ns.adobe.com/hdr-gain-map/1.0/")
        fun candidate(bytes: ByteArray) = hasGainMapHeader(bytes.inputStream())
        assertTrue(candidate(start + iso))
        assertTrue(candidate(start + app(224, "JFIF") + xmp))
        for (n in 0 until iso.size) assertFalse(candidate(start + iso.copyOf(n)))
        assertFalse(candidate(start + app(225, "HDR title")))
        assertFalse(candidate(start + app(224, "urn:iso:std:iso:ts:21496:-1\u0000")))
        assertFalse(candidate(start + byteArrayOf(-1, -38, 0, 2) + iso))
        assertFalse(candidate(start + byteArrayOf(-1, -31, 0, 1) + xmp))
        assertFalse(candidate(xmp))
        val stream = (start + iso + ByteArray(96 * 1024)).inputStream()
        assertTrue(hasGainMapHeader(stream))
        assertEquals(96 * 1024, stream.available())
        val outsideLimit = (start + app(224, "x".repeat(65528)) + iso).inputStream()
        assertFalse(hasGainMapHeader(outsideLimit))
    }

    @Test
    fun returnFreezesBothEndsAndDoesNotFlyASecondPhotoIntoTheCover() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).asImageBitmap()
        var target = Rect(200f, 100f, 380f, 350f)
        val flight =
            GalleryFlight(bitmap, { i -> if (i == 0) GalleryAnchor(target, true) else null }, 0)
        flight.viewport = Rect(0f, 100f, 400f, 500f)
        flight.plane = GalleryPlane(bitmap, Rect(0f, 0f, 400f, 400f))
        val note =
            Note.parse(
                org.json.JSONObject("""{"id":"fixture","note_card":{"title":"test"}}"""),
                "pc_feed",
            )!!
        val motion = GalleryMotion(Page.Detail(note, flight), mutableFloatStateOf(1f), true, 0)
        target = Rect(-400f, 100f, -200f, 350f)
        flight.viewport = Rect(-800f, 100f, -400f, 500f)
        assertEquals(200f, motion.anchor!!.viewport.left, 0f)
        assertEquals(0f, motion.viewport!!.left, 0f)
        assertTrue(motion.hasFlight)
        flight.index = 1
        assertFalse(
            GalleryMotion(Page.Detail(note, flight), mutableFloatStateOf(1f), true, 0).hasFlight
        )
    }

    @Test
    fun zoomedImageContinuesAfterReleaseAndNewTouchStopsInertia() {
        val context = RuntimeEnvironment.getApplication()
        var plane: GalleryPlane? = null
        val view = ZoomImage(context, {}, {}, { plane = it })
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        view.layout(0, 0, 400, 400)
        view.setPicture(BitmapDrawable(context.resources, bitmap))
        // Establish zoom without coupling this regression to double-tap recognition timing.
        ZoomImage::class
            .java
            .getDeclaredMethod(
                "setZoom",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .invoke(view, 3f, 200f, 200f)
        val down = android.os.SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, elapsed: Long) {
            val event = MotionEvent.obtain(down, down + elapsed, action, x, 200f, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 100f, 0)
        touch(MotionEvent.ACTION_MOVE, 130f, 20)
        touch(MotionEvent.ACTION_MOVE, 170f, 40)
        touch(MotionEvent.ACTION_UP, 190f, 50)
        val released = plane!!.rect.left
        repeat(5) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            view.computeScroll()
        }
        assertTrue("movement must continue after finger lifts", plane!!.rect.left > released)
        assertTrue(plane!!.rect.left <= 0f)
        touch(MotionEvent.ACTION_DOWN, 200f, 150)
        val stopped = plane!!.rect
        repeat(10) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            view.computeScroll()
        }
        assertEquals(stopped, plane!!.rect)
        view.release()
        bitmap.recycle()
    }
}
