package com.red.note

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MigrationContractTest {
    @Test
    fun failedLikeDoesNotEraseSuccessfulCollectionOrCommentCount() {
        val base =
            JSONObject(
                """{"liked":false,"liked_count":3,"collected":false,"collect_count":8,"comment_count":2}"""
            )
        val like = interactionPatch(base, "likeNote")
        val collect = interactionPatch(base, "collectNote")
        val current = mergeJson(mergeJson(like, collect), JSONObject().put("comment_count", 3))
        val rolled = rollbackPatch(current, JSONObject(), like)
        val shown = mergeJson(base, rolled)
        assertFalse(shown.getBoolean("liked"))
        assertEquals(3, shown.getInt("liked_count"))
        assertTrue(shown.getBoolean("collected"))
        assertEquals(9, shown.getInt("collected_count"))
        assertEquals(3, shown.getInt("comment_count"))
        assertFalse(base.getBoolean("collected"))
        assertFalse(
            interactionPatch(JSONObject("""{"liked_count":"1万"}"""), "likeNote").has("liked_count")
        )
        assertEquals(1, interactionPatch(JSONObject(), "likeNote").getInt("liked_count"))
    }

    @Test
    fun notificationsFromSameActorRemainDistinctWhileSearchAndChatsDeduplicate() {
        val one = JSONObject("""{"time":1,"title":"赞了笔记","user_info":{"user_id":"same"}}""")
        val two = JSONObject(one.toString()).put("time", 2)
        assertNotEquals(rowIdentity(one, "likes"), rowIdentity(two, "likes"))
        assertEquals(rowIdentity(one, "searchUsers"), rowIdentity(two, "searchUsers"))
        assertEquals(
            rowIdentity(
                JSONObject("""{"chat_user_id":"same","last_msg_content":"a"}"""),
                "privateChats",
            ),
            rowIdentity(
                JSONObject("""{"chat_user_id":"same","last_msg_content":"b"}"""),
                "privateChats",
            ),
        )
    }

    @Test
    fun notificationLinksAndOriginalMetadataKeepUsableReferences() {
        val row =
            JSONObject(
                """{"item_info":{"type":"board_info","attach_item_info":{"link":"https://www.xiaohongshu.com/explore/note123?xsec_token=a%2Bb&xsec_source=notice"}}}"""
            )
        val note = notificationNote(row)!!
        assertEquals("note123", note.id)
        assertEquals("a+b", note.token)
        assertEquals("notice", note.source)
        assertNull(
            notificationNote(
                JSONObject("""{"item_info":{"note_id":"bad/path","xsec_token":"token"}}""")
            )
        )
        val parsed =
            Note.parse(
                JSONObject("""{"id":"note123","view_count":123,"note_card":{"title":"title"}}"""),
                "pc_user",
            )!!
        assertEquals(123, parsed.raw.getInt("view_count"))
        assertEquals(
            "https://a.xhscdn.com/a",
            profileBackgroundUrl(
                JSONObject(
                    """{"basicInfo":{"backgroundImage":[{"url":"https://a.xhscdn.com/a"}]}}"""
                )
            ),
        )
    }

    @Test
    fun timestampsAcceptSecondsAndMillisecondsAndRejectNonFinite() {
        val now = 1800000000000L
        assertEquals("2分钟前", notificationTime(now - 120000, now))
        assertEquals("2分钟前", notificationTime((now - 120000) / 1000, now))
        listOf("NaN", "Infinity", "-1", "0", "90000000000000000").forEach {
            assertEquals("", notificationTime(it, now))
        }
    }

    @Test
    fun hdrBadgeDistinguishesVideoTransferFromUnknownMetadata() {
        assertEquals(true, videoTrackHdr(androidx.media3.common.C.COLOR_TRANSFER_ST2084))
        assertEquals(true, videoTrackHdr(androidx.media3.common.C.COLOR_TRANSFER_HLG))
        assertEquals(false, videoTrackHdr(androidx.media3.common.C.COLOR_TRANSFER_SDR))
        assertNull(videoTrackHdr(androidx.media3.common.Format.NO_VALUE))
        assertNull(videoTrackHdr(null))
    }

    @Test
    fun streamAttemptsKeepCompatibilityWithinEightCandidates() {
        val hdr = (0..10).map { VideoStream("https://a.xhscdn.com/h$it", hdr = true) }
        val normal = VideoStream("https://a.xhscdn.com/sdr")
        val original = VideoStream("https://a.xhscdn.com/original", original = true)
        val attempts = preferredStreams(hdr + normal + original, true, true)
        assertEquals(8, attempts.size)
        assertEquals(original, attempts.first())
        assertEquals(normal, attempts.last())
        assertEquals(normal, preferredStreams(hdr + normal + original, false, false).first())
    }

    @Test
    fun unknownProbeStillPlaysOriginalWithoutInventingHdrMetadata() = kotlinx.coroutines.runBlocking {
        val sdr = VideoStream("https://a.xhscdn.com/transcoded")
        val original = VideoStream("https://a.xhscdn.com/source", original = true)
        val streams = listOf(sdr, original)
        val unknown = videoPlaybackStreams(streams, true, "auto") { null }
        assertEquals(listOf(original, sdr), unknown)
        assertFalse(unknown.first().hdr)
        assertEquals(sdr, videoPlaybackStreams(streams, true, "auto") { false }.first())
        assertEquals(original.copy(hdr = true), videoPlaybackStreams(streams, true, "auto") { true }.first())
        assertEquals(original, videoPlaybackStreams(streams, true, "original") { false }.first())
        assertEquals(listOf(sdr), videoPlaybackStreams(streams, true, "sdr") { error("No probe in compatibility mode") })
        assertEquals(listOf(sdr), videoPlaybackStreams(streams, false, "auto") { error("No probe on SDR display") })
        val hdr = sdr.copy(url = "https://a.xhscdn.com/hdr", hdr = true)
        assertEquals(hdr, videoPlaybackStreams(streams + hdr, true, "auto") { error("Known HDR rendition needs no probe") }.first())
    }

    @Test
    fun glassMotionUsesOriginalSpringsAndRemainsBounded() {
        assertEquals(.85f, glassSpring(350, .15f).dampingRatio, .0001f)
        assertEquals(322.2728f, glassSpring(350, .15f).stiffness, .01f)
        assertArrayEquals(floatArrayOf(1f, 1f, .5f, .5f, 0f, 0f), anchoredStretch(0f, 0f), .0001f)
        val right = anchoredStretch(100000f, 0f)
        val left = anchoredStretch(-100000f, 0f)
        assertEquals(right[0], left[0], .0001f)
        assertTrue(right[0] <= 1.3f)
        assertTrue(right[1] >= .85f)
        assertEquals(0f, right[2], 0f)
        assertEquals(1f, left[2], 0f)
    }

    @Test
    fun saveFoldersUseOriginalFallbackAndSanitization() {
        assertEquals("RedNote", mediaSubdirectory(0, "user", "author"))
        assertEquals("RedNote/MyProfile", mediaSubdirectory(1, "..", null))
        assertEquals("RedNote/Unknown", mediaSubdirectory(2, null, "  "))
        assertEquals("RedNote/a_b", mediaSubdirectory(2, null, " a:/b "))
        assertEquals(58, mediaSubdirectory(1, "a".repeat(100), null).length)
    }

    @org.junit.Test
    fun glassVelocityRetainsOriginalBoundedSquash() {
        org.junit.Assert.assertArrayEquals(floatArrayOf(1f, 1f), jellyScale(Float.NaN), .0001f)
        org.junit.Assert.assertArrayEquals(floatArrayOf(.72f, 1.168f), jellyScale(10f), .0001f)
        org.junit.Assert.assertArrayEquals(jellyScale(10f), jellyScale(-100f), .0001f)
    }

    @org.junit.Test
    fun galleryFitsTheImagePlaneAndKeepsCropOutsideTheViewport() {
        val viewport = androidx.compose.ui.geometry.Rect(16f, 20f, 136f, 120f)
        val image = androidx.compose.ui.geometry.Size(300f, 100f)
        org.junit.Assert.assertEquals(
            androidx.compose.ui.geometry.Rect(16f, 50f, 136f, 90f),
            imagePlane(viewport, image, crop = false),
        )
        org.junit.Assert.assertEquals(
            androidx.compose.ui.geometry.Rect(-74f, 20f, 226f, 120f),
            imagePlane(viewport, image, crop = true),
        )
    }

    @Test
    fun heroUsesFlutterFastOutSlowInInBothDirections() {
        // Flutter Hero's default is Cubic(0.4, 0, 0.2, 1), independent of route opacity.
        assertEquals(0f, heroProgress(0f, false), 0f)
        assertEquals(1f, heroProgress(1f, false), 0f)
        assertEquals(.77556f, heroProgress(.5f, false), .001f)
        assertEquals(.22444f, heroProgress(.5f, true), .001f)
        for (i in 0..20) {
            val t = i / 20f
            assertEquals(1f - heroProgress(1f - t, false), heroProgress(t, true), .0001f)
        }
    }

    @Test
    fun detailAspectRatioFallsBackToCoverWhenImageDimensionsAreMissing() {
        val cover = JSONObject("""{"width":1600,"height":900}""")
        assertEquals(
            1.8f,
            detailImageRatio(JSONObject("""{"width":2400,"height":900}"""), cover),
            .0001f,
        )
        assertEquals(1.5f, detailImageRatio(JSONObject("""{"height":900}"""), cover), .0001f)
        assertEquals(
            .75f,
            detailImageRatio(JSONObject("""{"width":"NaN","height":900}"""), null),
            .0001f,
        )
        assertEquals(
            .6f,
            detailImageRatio(JSONObject("""{"width":100,"height":900}"""), cover),
            .0001f,
        )
    }
}
