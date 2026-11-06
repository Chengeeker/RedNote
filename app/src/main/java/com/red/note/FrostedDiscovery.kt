package com.red.note

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.*
import kotlin.math.ceil

@Composable
internal fun FrostedDiscoveryLayout(
    state: RedState,
    height: Dp,
    body: @Composable () -> Unit,
    base: Color = LocalScaffoldColor.current,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    header: @Composable () -> Unit,
) {
    val source = rememberGraphicsLayer()
    val blur = rememberGraphicsLayer()
    val parentBackdrop = LocalGlassBackdrop.current
    val backdrop = remember(source, parentBackdrop) { GlassBackdrop(source, parentBackdrop) }
    val legacy = Build.VERSION.SDK_INT < 31
    val sceneColor = LocalScaffoldColor.current
    val tint = base.copy(alpha = .45f)

    Box {
        Box(
            Modifier.onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                .drawWithContent {
                    source.record {
                        drawRect(sceneColor)
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(source)
                    if (legacy) backdrop.revision++
                }
        ) {
            CompositionLocalProvider(
                LocalGlassBackdrop provides if (legacy) backdrop else parentBackdrop
            ) {
                body()
            }
        }

        Box(
            Modifier.fillMaxWidth().height(height).clipToBounds().drawWithCache {
                val sigma = 20.dp.toPx()
                val clipHeight = size.height
                if (!legacy) {
                    // Android accepts blur radius. Flutter's ImageFilter takes sigma.
                    val radius = (sigma - .5f) / .57735f
                    blur.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    val sampleSize =
                        IntSize(size.width.toInt(), ceil(clipHeight + 3 * sigma).toInt())
                    blur.record(this, layoutDirection, sampleSize) {
                        drawLayer(source)
                        drawRect(tint, size = Size(size.width, clipHeight))
                    }
                }
                val borderWidth = .5.dp.toPx()
                onDrawWithContent {
                    if (!legacy) drawLayer(blur)
                    // The tint is part of the blurred input, so its lower edge feathers out.
                    drawContent()
                    drawRect(
                        border.copy(alpha = .24f),
                        topLeft = Offset(0f, size.height - borderWidth),
                        size = Size(size.width, borderWidth),
                    )
                }
            }
        ) {
            if (legacy)
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    GlassDecoration(state, Modifier.matchParentSize(), kind = 4, headerTint = tint)
                }
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val fade =
                        Brush.verticalGradient(
                            listOf(base.copy(alpha = .95f), base.copy(alpha = 0f)),
                            endY = size.height,
                        )
                    onDrawBehind { drawRect(fade) }
                }
            )
            header()
        }
    }
}
