@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.red.note

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

internal val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
internal val LocalTitleDuration = staticCompositionLocalOf { 220 }
internal val LocalPageVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
internal fun Modifier.sharedTitle(scope: Note): Modifier {
    val transition = LocalSharedTransition.current ?: return this
    val visibility = LocalPageVisibility.current ?: return this
    val duration = LocalTitleDuration.current
    return with(transition) {
        this@sharedTitle.sharedBounds(
            rememberSharedContentState(scope to "title"),
            visibility,
            enter = fadeIn(tween(duration, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(duration, easing = FastOutSlowInEasing)),
            resizeMode =
                SharedTransitionScope.ResizeMode.scaleToBounds(
                    ContentScale.Fit,
                    Alignment.TopStart,
                ),
            boundsTransform = { _, _ -> tween(duration, easing = FastOutSlowInEasing) },
        )
    }
}
