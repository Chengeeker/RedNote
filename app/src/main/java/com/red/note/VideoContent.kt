@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.red.note

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

internal fun preferredStreams(
    sources: List<VideoStream>,
    hdr: Boolean,
    original: Boolean,
): List<VideoStream> {
    val renditions = sources.filterNot { it.original }
    val originals =
        if (original || renditions.isEmpty()) sources.filter { it.original }.take(1)
        else emptyList()
    val preferred = renditions.filter { it.hdr == hdr }
    val fallback = renditions.filter { it.hdr != hdr }
    return (originals +
            preferred.take((if (fallback.isEmpty()) 8 else 7) - originals.size) +
            fallback)
        .take(8)
}

private fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600)
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoContent(state: RedState, note: Note, back: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val owner = LocalLifecycleOwner.current
    val window = activity.window
    val insets = remember { WindowCompat.getInsetsController(window, window.decorView) }
    val sourceList =
        remember(note) { videoStreams(note.raw.optJSONObject("video") ?: org.json.JSONObject()) }
    var quality by rememberSaveable { mutableStateOf("auto") }
    var attempts by remember { mutableStateOf(emptyList<VideoStream>()) }
    var attempt by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var position by rememberSaveable { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var resumePosition by remember { mutableLongStateOf(position) }
    var playing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var controls by rememberSaveable { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var brightness by remember { mutableFloatStateOf(.5f) }
    var volume by remember { mutableFloatStateOf(.7f) }
    var hud by remember { mutableStateOf("") }
    var speedPicker by remember { mutableStateOf(false) }
    var qualityPicker by remember { mutableStateOf(false) }
    val player = remember {
        ExoPlayer.Builder(activity).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            repeatMode = Player.REPEAT_MODE_ONE
            volume = .7f
        }
    }
    val hdrSupported = remember {
        if (Build.VERSION.SDK_INT >= 26)
            activity.windowManager.defaultDisplay.hdrCapabilities.supportedHdrTypes.isNotEmpty()
        else false
    }
    val oldBrightness = remember { window.attributes.screenBrightness }
    val oldColorMode = remember { if (Build.VERSION.SDK_INT >= 26) window.colorMode else 0 }
    val oldOrientation = remember { activity.requestedOrientation }
    val oldLightStatus = remember { insets.isAppearanceLightStatusBars }
    val oldLightNavigation = remember { insets.isAppearanceLightNavigationBars }
    val referer =
        if (state.session.optString("region") == "rednote") "https://www.rednote.com/"
        else "https://www.xiaohongshu.com/"
    fun touch() {
        touched++
    }
    fun rotate() {
        landscape = !landscape
        activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        touch()
    }
    fun pausePlay() {
        if (player.isPlaying) player.pause() else player.play()
        touch()
    }
    val currentBack by rememberUpdatedState(back)
    BackHandler {
        when {
            landscape -> rotate()
            !controls -> controls = true
            else -> currentBack()
        }
    }
    DisposableEffect(player, owner) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) {
                    playing = value
                }

                override fun onPlaybackStateChanged(value: Int) {
                    ready = value == Player.STATE_READY
                    if (ready) duration = player.duration.coerceAtLeast(0)
                }

                override fun onPlayerError(failure: PlaybackException) {
                    resumePosition = player.currentPosition
                    if (attempt < attempts.lastIndex) {
                        attempt++
                        state.message = "高画质无法播放，已使用兼容视频流"
                    } else {
                        error = "视频加载失败，请返回刷新笔记后重试"
                        controls = true
                    }
                }
            }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)
                player.pause()
        }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            if (Build.VERSION.SDK_INT >= 26) window.colorMode = oldColorMode
            activity.requestedOrientation = oldOrientation
            insets.show(WindowInsetsCompat.Type.systemBars())
            insets.isAppearanceLightStatusBars = oldLightStatus
            insets.isAppearanceLightNavigationBars = oldLightNavigation
        }
    }
    LaunchedEffect(quality, revision) {
        ready = false
        error = ""
        player.pause()
        var sources = sourceList
        var hdrOriginal = false
        if (
            hdrSupported &&
                quality != "sdr" &&
                (quality == "original" || sources.none { it.hdr && !it.original })
        ) {
            val original = sources.firstOrNull { it.original }
            if (original != null) {
                val hdr =
                    withContext(Dispatchers.IO) {
                        runCatching { HdrVideoProbe.probe(original.url, referer) }.getOrNull()
                    }
                if (hdr == true) {
                    hdrOriginal = true
                    sources = sources.map { if (it == original) it.copy(hdr = true) else it }
                }
            }
        }
        attempts =
            preferredStreams(
                sources,
                hdrSupported && quality != "sdr",
                quality == "original" || hdrOriginal,
            )
        attempt = 0
        if (attempts.isEmpty()) error = "未找到可播放的视频流"
    }
    LaunchedEffect(attempts, attempt, revision) {
        val stream = attempts.getOrNull(attempt) ?: return@LaunchedEffect
        ready = false
        error = ""
        val factory =
            ProgressiveMediaSource.Factory(
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000)
                    .setReadTimeoutMs(20000)
                    .setDefaultRequestProperties(mapOf("Referer" to referer))
            )
        val visual = factory.createMediaSource(MediaItem.fromUri(stream.url))
        player.setMediaSource(
            if (stream.audio.isEmpty()) visual
            else
                MergingMediaSource(
                    true,
                    visual,
                    factory.createMediaSource(MediaItem.fromUri(stream.audio)),
                )
        )
        player.setPlaybackSpeed(speed)
        player.seekTo(resumePosition)
        player.prepare()
        player.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (Build.VERSION.SDK_INT >= 26)
            window.colorMode =
                if (hdrSupported && (stream.hdr || stream.original)) ActivityInfo.COLOR_MODE_HDR
                else oldColorMode
        delay(20000)
        if (!ready && error.isEmpty()) {
            if (attempt < attempts.lastIndex) attempt++ else error = "视频加载超时，请重试加载"
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }
    LaunchedEffect(controls, touched, playing, speedPicker, qualityPicker) {
        if (controls && playing && !speedPicker && !qualityPicker) {
            delay(3500)
            controls = false
        }
    }
    LaunchedEffect(controls) {
        if (controls) insets.show(WindowInsetsCompat.Type.systemBars())
        else insets.hide(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(hud) {
        if (hud.isNotEmpty()) {
            delay(900)
            hud = ""
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    this.player = player
                    useController = false
                    var fast = false
                    var left = false
                    val detector =
                        GestureDetector(
                            context,
                            object : GestureDetector.SimpleOnGestureListener() {
                                override fun onDown(e: MotionEvent): Boolean {
                                    left = e.x < width / 2
                                    return true
                                }

                                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                    controls = !controls
                                    touch()
                                    return true
                                }

                                override fun onDoubleTap(e: MotionEvent): Boolean {
                                    val delta =
                                        when {
                                            e.x < width * .35 -> -10000L
                                            e.x > width * .65 -> 10000L
                                            else -> 0L
                                        }
                                    if (delta == 0L) pausePlay()
                                    else {
                                        player.seekTo(
                                            (player.currentPosition + delta).coerceIn(
                                                0,
                                                player.duration.coerceAtLeast(0),
                                            )
                                        )
                                        position = player.currentPosition
                                        hud =
                                            (if (delta > 0) "+10s" else "-10s") +
                                                " (${durationText(position)})"
                                    }
                                    touch()
                                    return true
                                }

                                override fun onLongPress(e: MotionEvent) {
                                    fast = true
                                    player.setPlaybackSpeed(2f)
                                    hud = "2.0X 快进中"
                                }

                                override fun onScroll(
                                    start: MotionEvent?,
                                    end: MotionEvent,
                                    dx: Float,
                                    dy: Float,
                                ): Boolean {
                                    if (kotlin.math.abs(dy) < kotlin.math.abs(dx)) return false
                                    val delta = dy / resources.displayMetrics.density / 200f
                                    if (left) {
                                        brightness = (brightness + delta).coerceIn(.1f, 1f)
                                        window.attributes =
                                            window.attributes.apply {
                                                screenBrightness = brightness
                                            }
                                        hud = "亮度 ${(brightness * 100).toInt()}%"
                                    } else {
                                        volume = (volume + delta).coerceIn(0f, 1f)
                                        player.volume = volume
                                        hud = "音量 ${(volume * 100).toInt()}%"
                                    }
                                    return true
                                }
                            },
                        )
                    setOnTouchListener { _, event ->
                        detector.onTouchEvent(event)
                        if (
                            event.actionMasked == MotionEvent.ACTION_UP ||
                                event.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            if (fast) {
                                fast = false
                                player.setPlaybackSpeed(speed)
                                hud = ""
                            }
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (error.isNotEmpty())
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Glyph("error_outline_rounded", null, 48.dp, Color.White.copy(alpha = .7f))
                Spacer(Modifier.height(12.dp))
                Text(error, color = Color.White.copy(alpha = .7f))
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(
                    onClick =
                        tapAction {
                            resumePosition = position
                            revision++
                        }
                ) {
                    Glyph("refresh_rounded")
                    Text("重试加载")
                }
            }
        else if (!ready)
            CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = .7f),
            )
        if (hud.isNotEmpty())
            Surface(
                Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = .54f),
                shape = RoundedCornerShape(10.dp),
            ) {
                val level = hud.startsWith("亮度") || hud.startsWith("音量")
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = if (level) 12.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(
                        if (hud.startsWith("亮度")) "brightness_6_rounded"
                        else if (hud.startsWith("音量"))
                            if (volume > 0) "volume_up_rounded" else "volume_off_rounded"
                        else if (hud.startsWith("-")) "fast_rewind_rounded"
                        else "fast_forward_rounded",
                        null,
                        24.dp,
                        Color.White,
                    )
                    Spacer(Modifier.width(if (level) 10.dp else 8.dp))
                    if (level)
                        LinearProgressIndicator(
                            progress = { if (hud.startsWith("亮度")) brightness else volume },
                            modifier = Modifier.width(80.dp),
                            trackColor = Color.White.copy(alpha = .24f),
                        )
                    else
                        Text(
                            hud,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                }
            }
        androidx.compose.animation.AnimatedVisibility(
            controls,
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(
                        220,
                        easing = androidx.compose.animation.core.LinearEasing,
                    )
                ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(
                        220,
                        easing = androidx.compose.animation.core.LinearEasing,
                    )
                ),
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = .87f), Color.Transparent)
                                )
                            )
                            .statusBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphButton("arrow_back_rounded", "返回", action = back)
                        Column(Modifier.weight(1f)) {
                            Text(
                                note.title.ifEmpty { "视频播放" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            if (note.author.isNotEmpty())
                                Text(
                                    note.author,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = .7f),
                                    maxLines = 1,
                                )
                        }
                        if (sourceList.any { it.original })
                            Box {
                                GlyphButton("high_quality_outlined", "画质") { qualityPicker = true }
                                DropdownMenu(qualityPicker, { qualityPicker = false }) {
                                    listOf(
                                            "auto" to "自动（HDR 优先）",
                                            "sdr" to "兼容画质（节省流量）",
                                            "original" to "原画（流量较大）",
                                        )
                                        .forEach { (value, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick =
                                                    tapAction {
                                                        qualityPicker = false
                                                        resumePosition = player.currentPosition
                                                        quality = value
                                                    },
                                            )
                                        }
                                }
                            }
                        GlyphButton(
                            "download_rounded",
                            "保存视频",
                            !activity.saving && attempts.isNotEmpty(),
                        ) {
                            attempts.getOrNull(attempt)?.let {
                                activity.saveMedia(it.url, true, note.author, it.audio)
                            }
                        }
                        GlyphButton("speed_rounded", "播放倍速") { speedPicker = true }
                        GlyphButton(
                            if (landscape) "fullscreen_exit_rounded" else "fullscreen_rounded",
                            "全屏旋转",
                        ) {
                            rotate()
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = .87f))
                                )
                            )
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                    ) {
                        Slider(
                            position.coerceIn(0, duration).toFloat(),
                            {
                                player.seekTo(it.toLong())
                                position = it.toLong()
                                touch()
                            },
                            modifier = Modifier.height(48.dp),
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                            thumb = {
                                Box(Modifier.size(13.dp).background(Color.White, CircleShape))
                            },
                            track = { slider ->
                                val primary = MaterialTheme.colorScheme.primary
                                Canvas(Modifier.fillMaxWidth().height(3.5.dp)) {
                                    val y = size.height / 2
                                    drawLine(
                                        Color.White.copy(alpha = .24f),
                                        androidx.compose.ui.geometry.Offset(0f, y),
                                        androidx.compose.ui.geometry.Offset(size.width, y),
                                        size.height,
                                        androidx.compose.ui.graphics.StrokeCap.Round,
                                    )
                                    drawLine(
                                        primary,
                                        androidx.compose.ui.geometry.Offset(0f, y),
                                        androidx.compose.ui.geometry.Offset(
                                            size.width *
                                                (slider.value / slider.valueRange.endInclusive)
                                                    .coerceIn(0f, 1f),
                                            y,
                                        ),
                                        size.height,
                                        androidx.compose.ui.graphics.StrokeCap.Round,
                                    )
                                }
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = tapAction { pausePlay() }) {
                                Glyph(
                                    if (playing) "pause_rounded" else "play_arrow_rounded",
                                    if (playing) "暂停" else "播放",
                                    30.dp,
                                    Color.White,
                                )
                            }
                            Text(
                                "${durationText(position)} / ${durationText(duration)}",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = .7f),
                                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${speed}x",
                                Modifier.background(
                                        Color.White.copy(alpha = .12f),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .clickable { speedPicker = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = tapAction { rotate() }) {
                                Glyph(
                                    if (landscape) "screen_lock_rotation_rounded"
                                    else "screen_rotation_rounded",
                                    "旋转屏幕",
                                    20.dp,
                                    Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (speedPicker)
        ModalBottomSheet(
            onDismissRequest = { speedPicker = false },
            containerColor = Color.Black.copy(alpha = .87f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("播放倍速", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement =
                        Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { value ->
                        FilterChip(
                            speed == value,
                            {
                                speed = value
                                player.setPlaybackSpeed(value)
                                speedPicker = false
                                touch()
                            },
                            label = {
                                Text(
                                    "${value}x",
                                    fontWeight =
                                        if (speed == value) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    containerColor = Color.White.copy(alpha = .12f),
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = Color.White.copy(alpha = .7f),
                                    selectedLabelColor = Color.White,
                                ),
                        )
                    }
                }
            }
        }
}
