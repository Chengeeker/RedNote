package com.red.note

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun LivePhotoLayer(
    sources: List<VideoStream>, playing: Boolean, onRendered: (Boolean) -> Unit,
    onStopped: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(sources) { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    var attempt by remember(player) { mutableIntStateOf(0) }
    var prepared by remember(player) { mutableStateOf(false) }
    var hasFrame by remember(player) { mutableStateOf(false) }
    val rendered by rememberUpdatedState(onRendered)
    val stopped by rememberUpdatedState(onStopped)
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { hasFrame = true; rendered(true) }
            override fun onPlayerError(error: PlaybackException) {
                hasFrame = false
                rendered(false)
                if (attempt + 1 < minOf(sources.size, 8)) { attempt++; prepared = false }
                else { prepared = false; attempt = 0; stopped(true) }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                player.pause(); rendered(false); stopped(false)
            }
        }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, playing, attempt) {
        if (playing && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (!prepared) {
                val stream = sources[attempt]
                val factory = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
                    .setDefaultRequestProperties(mapOf("Referer" to "https://www.xiaohongshu.com/")))
                val visual = factory.createMediaSource(MediaItem.fromUri(stream.url))
                player.setMediaSource(if (stream.audio.isEmpty()) visual else MergingMediaSource(
                    true, visual, factory.createMediaSource(MediaItem.fromUri(stream.audio))))
                player.prepare()
                prepared = true
            }
            if (hasFrame) rendered(true)
            player.playWhenReady = true
        } else { player.pause(); rendered(false) }
    }
    AndroidView(factory = {
        (android.view.LayoutInflater.from(it).inflate(R.layout.live_photo_player, null, false) as PlayerView).apply {
            useController = false; isClickable = false; isFocusable = false; this.player = player
        }
    }, modifier = modifier, onRelease = { it.player = null })
}
