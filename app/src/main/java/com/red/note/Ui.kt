@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.red.note

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.json.JSONObject

internal val LocalScaffoldColor = staticCompositionLocalOf { Color.White }
internal val LocalCardColor = staticCompositionLocalOf { Color.White }
internal val LocalSystemDensity = staticCompositionLocalOf<Density?> { null }
internal val LocalAppFontScale = staticCompositionLocalOf { 1f }
internal val LocalHapticsEnabled = staticCompositionLocalOf { true }
internal val LocalTopic = staticCompositionLocalOf<(String) -> Unit> { {} }
private var iconCodes: JSONObject? = null
private var emojiCodes: JSONObject? = null
private val iconFont = FontFamily(Font(R.font.material_icons))

@Composable
internal fun rememberEmojiCodes(): JSONObject {
    val context = LocalContext.current
    return remember {
        emojiCodes
            ?: JSONObject(context.assets.open("emoji.json").bufferedReader().use { it.readText() })
                .also { emojiCodes = it }
    }
}

@Composable
internal fun Glyph(
    name: String,
    description: String? = null,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    val context = LocalContext.current
    val codes = remember {
        iconCodes
            ?: JSONObject(context.assets.open("icons.json").bufferedReader().use { it.readText() })
                .also { iconCodes = it }
    }
    Text(
        String(Character.toChars(codes.getInt(name))),
        fontFamily = iconFont,
        scaleFont = false,
        fontSize = with(LocalDensity.current) { size.toSp() },
        lineHeight = with(LocalDensity.current) { size.toSp() },
        color = tint,
        modifier =
            Modifier.size(size).clearAndSetSemantics {
                if (description != null) contentDescription = description
            },
    )
}

@Composable
internal fun GlyphButton(
    name: String,
    description: String,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val view = LocalView.current
    val haptics = LocalHapticsEnabled.current
    IconButton(
        onClick = {
            if (haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            action()
        },
        enabled = enabled,
    ) {
        Glyph(name, description)
    }
}

@Composable
internal fun Avatar(user: JSONObject, size: Dp = 32.dp) {
    val url = userImageUrl(user)
    Picture(url, null, Modifier.size(size).clip(CircleShape))
}

@Composable
internal fun Picture(
    url: String,
    description: String?,
    modifier: Modifier = Modifier,
    scale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    onImageReady: ((ImageBitmap?) -> Unit)? = null,
    retained: ImageBitmap? = null,
    holdPreview: Boolean = false,
) {
    val backdrop = LocalGlassBackdrop.current
    val context = LocalContext.current
    var loaded by remember(url) { mutableStateOf(false) }
    var decoded by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val currentReady by rememberUpdatedState(onImageReady)
    val currentRetained by rememberUpdatedState(retained)
    val currentHold by rememberUpdatedState(holdPreview)
    fun displayed() =
        if (currentHold && currentRetained != null) currentRetained else decoded ?: currentRetained
    SideEffect { currentReady?.invoke(displayed()) }
    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        val request =
            remember(context, url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(false)
                    .apply {
                        mediaHeaders(context, url).forEach { (name, value) ->
                            addHeader(name, value)
                        }
                    }
                    .build()
            }
        AsyncImage(
            request,
            description,
            Modifier.matchParentSize(),
            contentScale = scale,
            onSuccess = {
                loaded = true
                decoded =
                    (it.result.drawable as? android.graphics.drawable.BitmapDrawable)
                        ?.bitmap
                        ?.asImageBitmap()
                currentReady?.invoke(displayed())
                backdrop?.let { it.revision++ }
            },
            onError = {
                loaded = false
                decoded = null
            },
        )
        if ((!loaded || holdPreview) && retained != null)
            Image(
                retained,
                null,
                Modifier.matchParentSize()
                    .background(if (background == Color.Transparent) Color.Black else background),
                contentScale = scale,
            )
        else if (!loaded && background != Color.Transparent)
            Glyph("image_outlined", null, 24.dp, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RichText(
    value: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    topic: ((String) -> Unit)? = LocalTopic.current,
) {
    val inherited = LocalTextStyle.current
    val resolvedStyle =
        if (
            style.fontSize.isSp &&
                inherited.fontSize.isSp &&
                style.fontSize != inherited.fontSize &&
                style.lineHeight == inherited.lineHeight &&
                inherited.lineHeight.isSp
        )
            style.copy(
                lineHeight =
                    (style.fontSize.value * inherited.lineHeight.value / inherited.fontSize.value)
                        .sp
            )
        else style
    val emojis = rememberEmojiCodes()
    val normalized =
        remember(value) { value.replace(Regex("#([^#\\r\\n]+?)\\[(?:话题|标签)\\]#"), "#$1#") }
    val pattern = remember { Regex("\\[[^\\[\\]\\r\\n]{1,32}\\]|#[^#\\r\\n]+#") }
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        var index = 0
        pattern.findAll(normalized).forEach { match ->
            append(normalized.substring(index, match.range.first))
            val token = match.value
            val url = mediaUrl(emojis.optString(token))
            if (url.isNotBlank()) {
                appendInlineContent(token, token)
                inline[token] =
                    InlineTextContent(
                        Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)
                    ) {
                        Picture(
                            url,
                            token,
                            Modifier.fillMaxSize(),
                            androidx.compose.ui.layout.ContentScale.Fit,
                        )
                    }
            } else if (token.startsWith("#") && topic != null) {
                withLink(
                    LinkAnnotation.Clickable(
                        token,
                        TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary)),
                    ) {
                        topic(token.trim('#'))
                    }
                ) {
                    append(token)
                }
            } else append(token)
            index = match.range.last + 1
        }
        append(normalized.substring(index))
    }
    Text(
        text,
        modifier,
        style = resolvedStyle,
        inlineContent = inline,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PageBar(
    title: String,
    back: (() -> Unit)? = null,
    center: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().height(52.dp).background(LocalScaffoldColor.current)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (back != null)
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    GlyphButton("arrow_back", "返回", action = back)
                }
            if (!center)
                Text(
                    title,
                    Modifier.weight(1f).padding(horizontal = 16.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            else Spacer(Modifier.weight(1f))
            actions()
        }
        if (center)
            Text(
                title,
                Modifier.align(Alignment.Center),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        if (!center)
            HorizontalDivider(
                Modifier.align(Alignment.BottomCenter),
                thickness = .5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .24f),
            )
    }
}

@Composable
internal fun AppTheme(state: RedState, content: @Composable () -> Unit) {
    val p = state.preferences
    val dark =
        when (p.int("theme")) {
            1 -> false
            2 -> true
            else -> isSystemInDarkTheme()
        }
    val context = LocalContext.current
    val palettes = remember {
        JSONObject(context.assets.open("palettes.json").bufferedReader().use { it.readText() })
    }
    val data =
        palettes.getJSONObject("${p.int("color").coerceIn(0, 7)}:${if (dark) "dark" else "light"}")
    fun c(name: String) = Color(data.getLong(name).toInt())
    var colors = if (dark) darkColorScheme() else lightColorScheme()
    colors =
        colors.copy(
            primary = c("primary"),
            onPrimary = c("onPrimary"),
            primaryContainer = c("primaryContainer"),
            onPrimaryContainer = c("onPrimaryContainer"),
            secondary = c("secondary"),
            onSecondary = c("onSecondary"),
            secondaryContainer = c("secondaryContainer"),
            onSecondaryContainer = c("onSecondaryContainer"),
            tertiary = c("tertiary"),
            onTertiary = c("onTertiary"),
            tertiaryContainer = c("tertiaryContainer"),
            onTertiaryContainer = c("onTertiaryContainer"),
            surface = c("surface"),
            onSurface = c("onSurface"),
            onSurfaceVariant = c("onSurfaceVariant"),
            outline = c("outline"),
            outlineVariant = c("outlineVariant"),
            surfaceContainer = c("surfaceContainer"),
            surfaceContainerLow = c("surfaceContainerLow"),
            surfaceContainerLowest = c("surfaceContainerLowest"),
            surfaceContainerHigh = c("surfaceContainerHigh"),
            surfaceContainerHighest = c("surfaceContainerHighest"),
            error = c("error"),
            onError = c("onError"),
        )
    if (p.bool("dynamicColor") && Build.VERSION.SDK_INT >= 31) {
        colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // Settings cards need a visible tonal step above the wallpaper-derived page surface.
        colors = colors.copy(surfaceContainerLow = colors.surfaceContainerHigh)
    }
    if (dark && p.bool("pureBlack"))
        colors =
            colors.copy(
                surface = Color.Black,
                surfaceContainer = Color(0xFF121212),
                surfaceContainerHigh = Color(0xFF181818),
                surfaceContainerHighest = Color(0xFF222222),
            )
    val delta =
        if (p.bool("customWeight")) p.int("weightDelta").coerceIn(-300, 300)
        else state.session.optInt("systemFontWeight")
    val metrics = remember {
        JSONObject(context.assets.open("typography.json").bufferedReader().use { it.readText() })
    }
    fun text(name: String): TextStyle {
        val value = metrics.getJSONObject(name)
        val size = value.getDouble("size")
        return TextStyle(
            fontSize = size.sp,
            lineHeight = (size * value.optDouble("height", 1.4)).sp,
            fontWeight = FontWeight((value.getInt("weight") + delta).coerceIn(100, 900)),
            letterSpacing = value.optDouble("spacing", 0.0).sp,
        )
    }
    val typography =
        Typography(
            displayLarge = text("displayLarge"),
            displayMedium = text("displayMedium"),
            displaySmall = text("displaySmall"),
            headlineLarge = text("headlineLarge"),
            headlineMedium = text("headlineMedium"),
            headlineSmall = text("headlineSmall"),
            titleLarge = text("titleLarge"),
            titleMedium = text("titleMedium"),
            titleSmall = text("titleSmall"),
            bodyLarge = text("bodyLarge"),
            bodyMedium = text("bodyMedium"),
            bodySmall = text("bodySmall"),
            labelLarge = text("labelLarge"),
            labelMedium = text("labelMedium"),
            labelSmall = text("labelSmall"),
        )
    val fontContext =
        remember(context) {
            if (Build.VERSION.SDK_INT >= 31)
                context.createConfigurationContext(
                    android.content.res.Configuration(context.resources.configuration).apply {
                        fontWeightAdjustment = 0
                    }
                )
            else context
        }
    val fontResolver = remember(fontContext) { createFontFamilyResolver(fontContext) }
    val density = LocalDensity.current
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(
            LocalScaffoldColor provides
                (if (dark) if (p.bool("pureBlack")) Color.Black else Color(0xFF111215)
                else colors.surface),
            LocalCardColor provides
                (if (dark) if (p.bool("pureBlack")) Color(0xFF121212) else Color(0xFF1B1C20)
                else colors.surfaceContainerLowest),
            LocalFontFamilyResolver provides fontResolver,
            LocalRippleConfiguration provides null,
            LocalHapticsEnabled provides p.bool("haptics", true),
            LocalSystemDensity provides density,
            LocalAppFontScale provides p.number("fontScale").toFloat().coerceIn(.85f, 1.5f),
            LocalDensity provides Density(density.density, 1f),
        ) {
            Surface(
                Modifier.fillMaxSize(),
                color =
                    if (dark) if (p.bool("pureBlack")) Color.Black else Color(0xFF111215)
                    else colors.surface,
                contentColor = colors.onSurface,
                content = { ProvideTextStyle(typography.bodyMedium) { content() } },
            )
        }
    }
}

// Flutter inherits a height multiplier when a child overrides only its font size.
@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
    scaleFont: Boolean = true,
) {
    val height =
        if (lineHeight != TextUnit.Unspecified) lineHeight
        else if (
            fontSize != TextUnit.Unspecified &&
                style.fontSize != TextUnit.Unspecified &&
                style.lineHeight != TextUnit.Unspecified
        )
            (fontSize.value * style.lineHeight.value / style.fontSize.value).sp
        else TextUnit.Unspecified
    val resolvedSize = if (fontSize.isSpecified) fontSize else style.fontSize
    val resolvedHeight = if (height.isSpecified) height else style.lineHeight
    val scaled =
        if (scaleFont)
            appTextStyle(style.copy(fontSize = resolvedSize, lineHeight = resolvedHeight))
        else style.copy(fontSize = resolvedSize, lineHeight = resolvedHeight)
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = scaled.fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = scaled.lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}

@Composable
internal fun OriginalListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent),
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (supportingContent == null) 48.dp else 64.dp)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            Box(Modifier.widthIn(min = 24.dp), contentAlignment = Alignment.Center) {
                leadingContent()
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            ProvideTextStyle(
                MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 20.25.sp,
                    fontWeight = MaterialTheme.typography.titleMedium.fontWeight,
                )
            ) {
                headlineContent()
            }
            if (supportingContent != null)
                ProvideTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 17.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                ) {
                    supportingContent()
                }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(16.dp))
            trailingContent()
        }
    }
}

@Composable
internal fun tapAction(action: () -> Unit): () -> Unit {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return {
        if (enabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        action()
    }
}

internal fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    this.foundationClickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = tapAction(onClick),
    )
}

internal fun Modifier.floatingShadow(dark: Boolean): Modifier =
    this.drawWithCache {
        val margin = 20.dp.roundToPx()
        val image =
            android.graphics.Bitmap.createBitmap(
                size.width.toInt() + margin * 2,
                size.height.toInt() + margin * 2,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                setShadowLayer(
                    (16 * .57735f + .5f).dp.toPx(),
                    0f,
                    4.dp.toPx(),
                    android.graphics.Color.argb(if (dark) 89 else 31, 0, 0, 0),
                )
            }
        android.graphics
            .Canvas(image)
            .drawRoundRect(
                margin.toFloat(),
                margin.toFloat(),
                margin + size.width,
                margin + size.height,
                32.dp.toPx(),
                32.dp.toPx(),
                paint,
            )
        val bitmap = image.asImageBitmap()
        onDrawBehind {
            drawImage(
                bitmap,
                topLeft = androidx.compose.ui.geometry.Offset(-margin.toFloat(), -margin.toFloat()),
            )
        }
    }

// Compose measurement scopes reconstruct Density; resolve Flutter's system-then-app scale before
// layout.
@Composable
internal fun appTextSize(size: TextUnit): TextUnit {
    val system = LocalSystemDensity.current ?: return size
    return with(system) { size.toDp().value * LocalAppFontScale.current }.sp
}

@Composable
internal fun appTextStyle(style: TextStyle): TextStyle {
    if (!style.fontSize.isSp) return style
    val size = appTextSize(style.fontSize)
    return style.copy(
        fontSize = size,
        lineHeight =
            if (style.lineHeight.isSp)
                (size.value * style.lineHeight.value / style.fontSize.value).sp
            else style.lineHeight,
    )
}

@Composable
internal fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    androidx.compose.material3.Text(
        text,
        modifier,
        style = appTextStyle(style),
        inlineContent = inlineContent,
        maxLines = maxLines,
        overflow = overflow,
    )
}
