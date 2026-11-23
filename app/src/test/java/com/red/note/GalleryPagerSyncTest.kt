package com.red.note

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GalleryPagerSyncTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun mainPagerOffsetMovesThumbnailsBeforePageSettles() {
        lateinit var main: PagerState
        lateinit var thumbnails: PagerState
        lateinit var scope: CoroutineScope
        compose.setContent {
            main = rememberPagerState { 3 }
            thumbnails = rememberPagerState { 3 }
            scope = rememberCoroutineScope()
            GalleryPagerSync(main, thumbnails, remember { mutableStateOf(false) })
            Column {
                HorizontalPager(main, Modifier.height(120.dp)) { }
                HorizontalPager(thumbnails, Modifier.height(60.dp)) { }
            }
        }
        compose.runOnIdle { scope.launch { main.scrollToPage(0, .3f) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, main.currentPage)
            assertEquals(.3f, thumbnails.currentPageOffsetFraction, .03f)
        }
    }
}
