import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:video_player/video_player.dart';

import '../../app_state.dart';
import '../../data/red_api.dart';
import '../../data/media_support.dart';
import '../../core/storage/storage_path_resolver.dart';
import '../../core/utils/app_toast.dart';
import '../../core/utils/haptic_feedback_util.dart';

enum _VideoDragMode { none, brightness, volume }

enum _VideoHudType { none, brightness, volume, doubleTapSeek, fastForward }

/// 复用 GreenNote 的沉浸式播放器交互，播放接口返回的真实视频流。
class RedVideoPlayerPage extends StatefulWidget {
  final AppState app;
  final String videoUrl;
  final List<VideoSource> sources;
  final String? coverUrl;
  final String? title;
  final String? authorName;
  final String? feedId;

  const RedVideoPlayerPage({
    super.key,
    required this.app,
    required this.videoUrl,
    required this.sources,
    this.coverUrl,
    this.title,
    this.authorName,
    this.feedId,
  });

  @override
  State<RedVideoPlayerPage> createState() => _RedVideoPlayerPageState();
}

class _RedVideoPlayerPageState extends State<RedVideoPlayerPage>
    with WidgetsBindingObserver {
  int _playEpoch = 0;
  VideoPlayerController? _controller;
  bool _isInitialized = false;
  bool _hasError = false;
  String _errorMessage = '';
  bool _showControls = true;
  Timer? _hideControlsTimer;
  bool _isLandscape = false;

  double _playbackSpeed = 1.0;
  static const List<double> _availableSpeeds = [
    0.5,
    0.75,
    1.0,
    1.25,
    1.5,
    2.0,
    3.0,
  ];

  _VideoDragMode _dragMode = _VideoDragMode.none;
  _VideoHudType _hudType = _VideoHudType.none;
  double _brightnessLevel = 0.5;
  double _volumeLevel = 0.7;
  int _hudSeekDiff = 0;
  Duration _targetSeekPosition = Duration.zero;
  Offset _panStartPos = Offset.zero;
  Timer? _hudDismissTimer;
  String _playableUrl = '';
  String _audioUrl = '';
  bool _isSavingVideo = false;
  String _quality = 'auto';
  bool _playingHdr = false;
  DateTime _lastPositionPaint = DateTime.fromMillisecondsSinceEpoch(0);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initAndPlay();
    _resetControlsTimer();
  }

  @override
  void dispose() {
    _playEpoch++;
    WidgetsBinding.instance.removeObserver(this);
    const MethodChannel('com.red.note/media')
        .invokeMethod<void>('setBrightness', {'value': -1.0});
    const MethodChannel('com.red.note/media')
        .invokeMethod<void>('setWindowHdr', {'enabled': false})
        .catchError((_) {});
    _hideControlsTimer?.cancel();
    _hudDismissTimer?.cancel();
    _controller?.dispose();
    SystemChrome.setPreferredOrientations([]);
    SystemChrome.setEnabledSystemUIMode(
      SystemUiMode.manual,
      overlays: SystemUiOverlay.values,
    );
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _controller?.pause();
  }

  Future<void> _initAndPlay({Duration position = Duration.zero}) async {
    final epoch = ++_playEpoch;
    setState(() {
      _hasError = false;
      _errorMessage = '';
      _isInitialized = false;
    });
    final previous = _controller;
    _controller = null;
    await previous?.dispose();
    if (!mounted || epoch != _playEpoch) return;
    var hdrSupported = false;
    try {
      hdrSupported =
          await const MethodChannel('com.red.note/media')
              .invokeMethod<bool>('isHdrVideoDisplaySupported') ??
          false;
    } catch (_) {
      // Older platform implementations keep the SDR-first order.
    }
    if (!mounted || epoch != _playEpoch) return;
    final referer = widget.app.session['region'] == 'rednote'
        ? 'https://www.rednote.com/'
        : 'https://www.xiaohongshu.com/';
    final attempts = await resolveVideoSources(
      widget.sources,
      hdrSupported: hdrSupported,
      referer: referer,
      original: _quality == 'original',
      preferSdr: _quality == 'sdr',
    );
    if (!mounted || epoch != _playEpoch) return;
    for (final source in attempts) {
      final controller = VideoPlayerController.networkUrl(
        Uri.parse(source.url),
        viewType: defaultTargetPlatform == TargetPlatform.android
            ? VideoViewType.platformView
            : VideoViewType.textureView,
        httpHeaders: {
          if (source.audioUrl.isNotEmpty) 'RedNote-Audio-Url': source.audioUrl,
          'Referer': referer,
        },
      );
      try {
        await controller.initialize().timeout(const Duration(seconds: 20));
        if (!mounted || epoch != _playEpoch) {
          await controller.dispose();
          return;
        }
        _controller = controller;
        _playableUrl = source.url;
        _audioUrl = source.audioUrl;
        controller.addListener(_updatePosition);
        await controller.setLooping(true);
        await controller.setPlaybackSpeed(_playbackSpeed);
        await controller.setVolume(_volumeLevel);
        await const MethodChannel('com.red.note/media')
            .invokeMethod<void>('setWindowHdr', {
              'enabled': hdrSupported && (source.isHdr || source.isOriginal),
            })
            .catchError((_) {});
        if (position > Duration.zero) await controller.seekTo(position);
        if (!mounted || epoch != _playEpoch) return;
        await controller.play();
        if (!mounted || epoch != _playEpoch) return;
        if ((_quality == 'original' || attempts.first.isHdr) &&
            !source.isHdr &&
            !source.isOriginal &&
            mounted) {
          AppToast.show(context, '高画质无法播放，已使用兼容视频流');
        }
        setState(() {
          _isInitialized = true;
          _playingHdr = source.isHdr;
          if (_quality == 'original' && !source.isOriginal) _quality = 'auto';
        });
        return;
      } catch (_) {
        await controller.dispose();
        if (identical(_controller, controller)) {
          _controller = null;
          _isInitialized = false;
        }
      }
      if (!mounted || epoch != _playEpoch) return;
    }
    if (mounted && epoch == _playEpoch) {
      setState(() {
        _hasError = true;
        _errorMessage = '视频加载失败，请返回刷新笔记后重试';
      });
    }
  }

  void _updatePosition() {
    if (mounted && (_controller?.value.hasError ?? false) && !_hasError) {
      setState(() {
        _hasError = true;
        _showControls = true;
        _errorMessage = '播放中断，请返回刷新笔记或重试加载';
      });
      return;
    }
    final now = DateTime.now();
    if (mounted &&
        _showControls &&
        now.difference(_lastPositionPaint).inMilliseconds >= 200) {
      _lastPositionPaint = now;
      setState(() {});
    }
  }

  void _resetControlsTimer() {
    _hideControlsTimer?.cancel();
    if (_showControls) {
      _hideControlsTimer = Timer(const Duration(milliseconds: 3500), () {
        if (mounted && (_controller?.value.isPlaying ?? false)) {
          setState(() => _showControls = false);
        }
      });
    }
  }

  void _toggleControls() {
    setState(() {
      _showControls = !_showControls;
    });
    if (_showControls) {
      _resetControlsTimer();
    } else {
      _hideControlsTimer?.cancel();
    }
  }

  void _togglePlayPause() {
    final c = _controller;
    if (c == null || !c.value.isInitialized) return;
    HapticFeedbackUtil.light();
    if (c.value.isPlaying) {
      c.pause();
      setState(() => _showControls = true);
    } else {
      c.play();
      _resetControlsTimer();
    }
    setState(() {});
  }

  void _toggleOrientation() {
    HapticFeedbackUtil.light();
    final toLandscape = !_isLandscape;
    setState(() => _isLandscape = toLandscape);
    if (toLandscape) {
      SystemChrome.setPreferredOrientations([
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    } else {
      SystemChrome.setPreferredOrientations([
        DeviceOrientation.portraitUp,
        DeviceOrientation.portraitDown,
      ]);
      SystemChrome.setEnabledSystemUIMode(
        SystemUiMode.manual,
        overlays: SystemUiOverlay.values,
      );
    }
  }

  void _seekBy(int seconds) {
    final c = _controller;
    if (c == null || !c.value.isInitialized) return;
    HapticFeedbackUtil.light();
    final current = c.value.position;
    final total = c.value.duration;
    final target = current + Duration(seconds: seconds);
    final clamped = target < Duration.zero
        ? Duration.zero
        : (target > total ? total : target);
    c.seekTo(clamped);

    _showHud(
      _VideoHudType.doubleTapSeek,
      seekDiff: seconds,
      targetPosition: clamped,
    );
  }

  void _showHud(
    _VideoHudType type, {
    int seekDiff = 0,
    Duration targetPosition = Duration.zero,
  }) {
    _hudDismissTimer?.cancel();
    setState(() {
      _hudType = type;
      _hudSeekDiff = seekDiff;
      _targetSeekPosition = targetPosition;
    });
    _hudDismissTimer = Timer(const Duration(milliseconds: 900), () {
      if (mounted) setState(() => _hudType = _VideoHudType.none);
    });
  }

  void _startFastForward() {
    final c = _controller;
    if (c == null || !c.value.isInitialized) return;
    HapticFeedbackUtil.medium();
    c.setPlaybackSpeed(2.0);
    _showHud(_VideoHudType.fastForward);
  }

  void _stopFastForward() {
    final c = _controller;
    if (c == null || !c.value.isInitialized) return;
    c.setPlaybackSpeed(_playbackSpeed);
    if (mounted) setState(() => _hudType = _VideoHudType.none);
  }

  void _showSpeedPicker() {
    HapticFeedbackUtil.light();
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.black87,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                '播放倍速',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 16,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 12,
                runSpacing: 10,
                children: _availableSpeeds.map((speed) {
                  final isSelected = _playbackSpeed == speed;
                  return ChoiceChip(
                    label: Text('${speed}x'),
                    selected: isSelected,
                    selectedColor: Theme.of(context).colorScheme.primary,
                    backgroundColor: Colors.white12,
                    labelStyle: TextStyle(
                      color: isSelected ? Colors.white : Colors.white70,
                      fontWeight: isSelected
                          ? FontWeight.bold
                          : FontWeight.normal,
                    ),
                    onSelected: (_) {
                      HapticFeedbackUtil.selection();
                      setState(() {
                        _playbackSpeed = speed;
                      });
                      _controller?.setPlaybackSpeed(speed);
                      Navigator.pop(ctx);
                    },
                  );
                }).toList(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _formatDuration(Duration d) {
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    if (d.inHours > 0) {
      return '${d.inHours}:$m:$s';
    }
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    final c = _controller;
    final isPlaying = c?.value.isPlaying ?? false;
    final position = c?.value.position ?? Duration.zero;
    final duration = c?.value.duration ?? Duration.zero;

    return PopScope(
      canPop: _showControls && !_isLandscape,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        if (_isLandscape) {
          _toggleOrientation();
        } else if (!_showControls) {
          _toggleControls();
        }
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Stack(
          fit: StackFit.expand,
          children: [
            // 1. 视频渲染核心与手势捕获
            if (_isInitialized && c != null && !_hasError)
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: _toggleControls,
                onDoubleTapDown: (details) {
                  final width = MediaQuery.of(context).size.width;
                  final dx = details.localPosition.dx;
                  if (dx < width * 0.35) {
                    _seekBy(-10);
                  } else if (dx > width * 0.65) {
                    _seekBy(10);
                  } else {
                    _togglePlayPause();
                  }
                },
                onLongPressStart: (_) => _startFastForward(),
                onLongPressEnd: (_) => _stopFastForward(),
                onVerticalDragStart: (details) {
                  _panStartPos = details.localPosition;
                  final width = MediaQuery.of(context).size.width;
                  if (_panStartPos.dx < width * 0.5) {
                    _dragMode = _VideoDragMode.brightness;
                  } else {
                    _dragMode = _VideoDragMode.volume;
                  }
                },
                onVerticalDragUpdate: (details) {
                  final delta = -(details.primaryDelta ?? 0) / 200.0;
                  if (_dragMode == _VideoDragMode.brightness) {
                    final b = (_brightnessLevel + delta).clamp(0.1, 1.0);
                    setState(() => _brightnessLevel = b);
                    const MethodChannel('com.red.note/media')
                        .invokeMethod<void>('setBrightness', {
                          'value': _brightnessLevel,
                        });
                    _showHud(_VideoHudType.brightness);
                  } else if (_dragMode == _VideoDragMode.volume) {
                    final v = (_volumeLevel + delta).clamp(0.0, 1.0);
                    setState(() => _volumeLevel = v);
                    _controller?.setVolume(v);
                    _showHud(_VideoHudType.volume);
                  }
                },
                onVerticalDragEnd: (_) {
                  _dragMode = _VideoDragMode.none;
                },
                child: Center(
                  child: AspectRatio(
                    aspectRatio: c.value.aspectRatio > 0
                        ? c.value.aspectRatio
                        : 16 / 9,
                    child: IgnorePointer(child: VideoPlayer(c)),
                  ),
                ),
              )
            else if (_hasError)
              Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(
                        Icons.error_outline_rounded,
                        color: Colors.white70,
                        size: 48,
                      ),
                      const SizedBox(height: 12),
                      Text(
                        _errorMessage.isNotEmpty ? _errorMessage : '视频加载失败',
                        style: const TextStyle(color: Colors.white70),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 16),
                      FilledButton.tonalIcon(
                        onPressed: _initAndPlay,
                        icon: const Icon(Icons.refresh_rounded),
                        label: const Text('重试加载'),
                      ),
                    ],
                  ),
                ),
              )
            else
              const Center(
                child: CircularProgressIndicator(color: Colors.white70),
              ),

            // 3. 手势与状态 HUD
            IgnorePointer(child: Center(child: _buildHud())),

            // 4. 浮动控制层 (Controls Overlay)
            AnimatedOpacity(
              opacity: _showControls ? 1.0 : 0.0,
              duration: const Duration(milliseconds: 220),
              child: IgnorePointer(
                ignoring: !_showControls,
                child: Column(
                  children: [
                    // 顶部 AppBar
                    _buildTopBar(),
                    const Spacer(),
                    // 底部播放控制条
                    _buildBottomBar(position, duration, isPlaying),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildTopBar() {
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Colors.black87, Colors.transparent],
        ),
      ),
      child: SafeArea(
        bottom: false,
        child: Row(
          children: [
            IconButton(
              tooltip: '返回',
              icon: const Icon(Icons.arrow_back_rounded, color: Colors.white),
              onPressed: () => Navigator.of(context).pop(),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    widget.title?.trim().isNotEmpty == true
                        ? widget.title!
                        : '视频播放',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (widget.authorName?.trim().isNotEmpty == true)
                    Text(
                      widget.authorName!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white70,
                        fontSize: 12,
                      ),
                    ),
                ],
              ),
            ),
            if (widget.sources.any((source) => source.isOriginal))
              PopupMenuButton<String>(
                tooltip: '画质',
                icon: Icon(
                  Icons.high_quality_outlined,
                  color: _playingHdr
                      ? Theme.of(context).colorScheme.primary
                      : Colors.white,
                ),
                initialValue: _quality,
                onSelected: (quality) {
                  if (quality == _quality) return;
                  final position = _controller?.value.position ?? Duration.zero;
                  _quality = quality;
                  _initAndPlay(position: position);
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'auto', child: Text('自动（HDR 优先）')),
                  PopupMenuItem(value: 'sdr', child: Text('兼容画质（节省流量）')),
                  PopupMenuItem(value: 'original', child: Text('原画（流量较大）')),
                ],
              ),
            IconButton(
              icon: _isSavingVideo
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(Icons.download_rounded, color: Colors.white),
              tooltip: '保存视频',
              onPressed: _isSavingVideo ? null : _saveVideo,
            ),
            IconButton(
              icon: const Icon(Icons.speed_rounded, color: Colors.white),
              tooltip: '播放倍速',
              onPressed: _showSpeedPicker,
            ),
            IconButton(
              icon: Icon(
                _isLandscape
                    ? Icons.fullscreen_exit_rounded
                    : Icons.fullscreen_rounded,
                color: Colors.white,
              ),
              tooltip: '全屏旋转',
              onPressed: _toggleOrientation,
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _saveVideo() async {
    if (_isSavingVideo || _playableUrl.isEmpty) return;
    setState(() => _isSavingVideo = true);
    try {
      final dir = StoragePathResolver.resolveSubDir(
        pathType: widget.app.savePath(true),
        myNickname: widget.app.user['nickname']?.toString(),
        authorName: widget.authorName,
        isVideo: true,
      );
      final saved = await const MethodChannel('com.red.note/media')
          .invokeMethod<String>('saveRemoteMedia', {
            'url': _playableUrl,
            'audioUrl': _audioUrl,
            'subDir': dir,
            'video': true,
          });
      if (mounted) AppToast.show(context, '视频已保存至相册：$saved');
    } on PlatformException catch (error) {
      if (mounted) AppToast.show(context, error.message ?? '视频保存失败，请重试');
    } finally {
      if (mounted) setState(() => _isSavingVideo = false);
    }
  }

  Widget _buildBottomBar(Duration position, Duration duration, bool isPlaying) {
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.bottomCenter,
          end: Alignment.topCenter,
          colors: [Colors.black87, Colors.transparent],
        ),
      ),
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // 进度滑块
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                trackHeight: 3.5,
                thumbShape: const RoundSliderThumbShape(
                  enabledThumbRadius: 6.5,
                ),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
                activeTrackColor: Theme.of(context).colorScheme.primary,
                inactiveTrackColor: Colors.white24,
                thumbColor: Colors.white,
              ),
              child: Slider(
                value: duration.inMilliseconds > 0
                    ? position.inMilliseconds
                          .clamp(0, duration.inMilliseconds)
                          .toDouble()
                    : 0.0,
                max: duration.inMilliseconds > 0
                    ? duration.inMilliseconds.toDouble()
                    : 1.0,
                onChanged: (val) {
                  _resetControlsTimer();
                  _controller?.seekTo(Duration(milliseconds: val.toInt()));
                },
              ),
            ),
            Row(
              children: [
                IconButton(
                  icon: Icon(
                    isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
                    color: Colors.white,
                    size: 30,
                  ),
                  onPressed: _togglePlayPause,
                ),
                Text(
                  '${_formatDuration(position)} / ${_formatDuration(duration)}',
                  style: const TextStyle(
                    color: Colors.white70,
                    fontSize: 13,
                    fontFeatures: [FontFeature.tabularFigures()],
                  ),
                ),
                const Spacer(),
                GestureDetector(
                  onTap: _showSpeedPicker,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.white12,
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      '${_playbackSpeed}x',
                      style: const TextStyle(color: Colors.white, fontSize: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  icon: Icon(
                    _isLandscape
                        ? Icons.screen_lock_rotation_rounded
                        : Icons.screen_rotation_rounded,
                    color: Colors.white,
                    size: 20,
                  ),
                  onPressed: _toggleOrientation,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHud() {
    switch (_hudType) {
      case _VideoHudType.fastForward:
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(10),
          ),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.fast_forward_rounded, color: Colors.white, size: 24),
              SizedBox(width: 8),
              Text(
                '2.0X 快进中',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ),
        );
      case _VideoHudType.doubleTapSeek:
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                _hudSeekDiff > 0
                    ? Icons.fast_forward_rounded
                    : Icons.fast_rewind_rounded,
                color: Colors.white,
                size: 24,
              ),
              const SizedBox(width: 8),
              Text(
                '${_hudSeekDiff > 0 ? '+' : ''}${_hudSeekDiff}s (${_formatDuration(_targetSeekPosition)})',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ),
        );
      case _VideoHudType.brightness:
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.brightness_6_rounded,
                color: Colors.white,
                size: 24,
              ),
              const SizedBox(width: 10),
              SizedBox(
                width: 80,
                child: LinearProgressIndicator(
                  value: _brightnessLevel,
                  backgroundColor: Colors.white24,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ],
          ),
        );
      case _VideoHudType.volume:
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                _volumeLevel > 0
                    ? Icons.volume_up_rounded
                    : Icons.volume_off_rounded,
                color: Colors.white,
                size: 24,
              ),
              const SizedBox(width: 10),
              SizedBox(
                width: 80,
                child: LinearProgressIndicator(
                  value: _volumeLevel,
                  backgroundColor: Colors.white24,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ],
          ),
        );
      case _VideoHudType.none:
        return const SizedBox.shrink();
    }
  }
}
