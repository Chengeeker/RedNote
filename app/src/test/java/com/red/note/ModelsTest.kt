package com.red.note

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test
    fun separatesAudioAndKeepsBackups() {
        val video =
            JSONObject(
                """{"media":{"stream":{"h265":[{"master_url":"https://a.xhscdn.com/v","backup_urls":["https://a.xhscdn.com/b"],"hdr_type":1}]}},"media_v2":{"audio_stream":{"aac":[{"master_url":"https://a.xhscdn.com/a","default_stream":1}]}}}"""
            )
        val result = videoStreams(video)
        assertEquals(2, result.size)
        assertTrue(result.all { it.hdr && it.audio == "https://a.xhscdn.com/a" })
        video
            .getJSONObject("media")
            .getJSONObject("stream")
            .getJSONArray("h265")
            .getJSONObject(0)
            .put("audio_codec", "aac")
        assertTrue(videoStreams(video).all { it.audio.isEmpty() })
    }

    @Test
    fun doesNotConstructUntrustedOriginalPaths() {
        assertTrue(
            videoStreams(JSONObject("""{"consumer":{"origin_video_key":"a/../secret"}}"""))
                .isEmpty()
        )
        assertEquals(
            "https://sns-video-bd.xhscdn.com/a/file.mp4",
            videoStreams(JSONObject("""{"consumer":{"origin_video_key":"a/file.mp4"}}"""))
                .single()
                .url,
        )
    }

    @Test
    fun originalImageConversionRequiresExactRenditionShape() {
        val image =
            JSONObject()
                .put(
                    "url",
                    "https://sns-webpic-a.xhscdn.com/123456789012/0123456789abcdef0123456789abcdef/a/key!format",
                )
        assertEquals("https://sns-img-bd.xhscdn.com/a/key", originalImageUrl(image))
        image.put(
            "url",
            "https://sns-webpic-a.xhscdn.com.evil.test/123456789012/0123456789abcdef0123456789abcdef/a/key!format",
        )
        assertTrue(originalImageUrl(image).contains("evil.test"))
    }

    @Test
    fun preservesReferencesAndRejectsNonNotes() {
        val note =
            Note.parse(
                JSONObject(
                    """{"id":"abc","xsec_token":"secret","xsec_source":"pc_search","note_card":{"title":"test","user":{"user_id":"author"}}}"""
                ),
                "pc_feed",
            )!!
        assertEquals("secret", note.args["token"])
        assertEquals("pc_search", note.source)
        assertEquals("author", note.userId)
        assertNull(Note.parse(JSONObject("""{"id":"ad"}"""), "pc_feed"))
    }

    @Test
    fun validatesMediaUrls() {
        assertEquals("https://sns-img.xhscdn.com/a", mediaUrl("http://sns-img.xhscdn.com/a"))
        assertEquals("", mediaUrl("http://example.com/a"))
        assertEquals("", mediaUrl("https://user:pass@example.com/a"))
        assertEquals("", mediaUrl("file:///etc/passwd"))
        assertEquals("", mediaUrl("http://xhscdn.com.evil.test/a"))
    }

    @Test
    fun prefersDisplayImageScene() {
        assertEquals(
            "https://example.com/display",
            imageUrl(
                JSONObject(
                    """{"url":"https://example.com/fallback","info_list":[{"image_scene":"WB_DFT","url":"https://example.com/display"}]}"""
                )
            ),
        )
    }
}
