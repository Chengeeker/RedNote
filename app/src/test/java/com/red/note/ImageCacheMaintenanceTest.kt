package com.red.note

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageCacheMaintenanceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun thresholdDefaultsAndReviewChoicesMatch() {
        assertEquals(200, ImageCacheMaintenance.defaultThresholdMb)
        assertEquals(
            listOf(0, 50, 100, 200, 500, 1024, 2048),
            ImageCacheMaintenance.thresholdOptions,
        )
        assertEquals("不限制（关闭自动清理）", ImageCacheMaintenance.labelForThreshold(0))
        assertEquals("1 GB", ImageCacheMaintenance.labelForThreshold(1024))
    }

    @Test
    fun thresholdClearsAtOrAboveLimitAndZeroDisablesIt() {
        val limit = 200L * 1024 * 1024
        assertFalse(ImageCacheMaintenance.shouldClear(limit - 1, 200))
        assertTrue(ImageCacheMaintenance.shouldClear(limit, 200))
        assertFalse(ImageCacheMaintenance.shouldClear(limit * 4, 0))
    }

    @Test
    fun onlyStableOriginalImageFilesCountAndRecentFilesSurviveClear() {
        val dir = temporaryFolder.newFolder("image_cache")
        val now = 2_000_000L
        val old = File(dir, "a".repeat(64)).apply { writeBytes(ByteArray(12)) }
        val recent = File(dir, "b".repeat(64)).apply { writeBytes(ByteArray(8)) }
        val coilEntry = File(dir, "coil-entry.0").apply { writeBytes(ByteArray(100)) }
        val staging = File(dir, "image-writing.tmp").apply { writeBytes(ByteArray(50)) }
        old.setLastModified(now - 60_000)
        recent.setLastModified(now - 59_999)

        assertEquals(20L, ImageCacheMaintenance.cachedImageBytes(dir))
        assertEquals(12L, ImageCacheMaintenance.deleteOldCompletedImages(dir, now))
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(coilEntry.exists())
        assertTrue(staging.exists())
    }
}
