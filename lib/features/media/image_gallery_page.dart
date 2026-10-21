// ignore_for_file: deprecated_member_use
import 'dart:async';
import 'dart:ui' as ui;

import 'package:extended_image/extended_image.dart';
import 'package:flutter/foundation.dart' show Factory, visibleForTesting;
import 'package:flutter/gestures.dart'
    show
        OneSequenceGestureRecognizer,
        PointerCancelEvent,
        PointerDownEvent,
        PointerMoveEvent,
        PointerUpEvent,
        kDoubleTapSlop,
        kDoubleTapTimeout,
        kTouchSlop;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart'
    show RenderImage, RenderClipRRect, PlatformViewHitTestBehavior;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../../app_state.dart';
import '../../data/media_support.dart';
import '../../core/settings/app_settings.dart';
import '../../core/storage/storage_path_resolver.dart';
import '../../core/utils/app_toast.dart';
import '../../core/utils/haptic_feedback_util.dart';

@visibleForTesting
Matrix4 hdrImageViewTransform(Rect viewport, Rect? imageRect) {
  if (viewport.isEmpty || imageRect == null || imageRect.isEmpty) {
    return Matrix4.identity();
  }
  final fittedRect = Alignment.center.inscribe(
    applyBoxFit(BoxFit.contain, imageRect.size, viewport.size).destination,
    viewport,
  );
  final scale = imageRect.width / fittedRect.width;
  // ImageView already fits the bitmap inside the viewport; transform that fit.
  return Matrix4.identity()
    ..translate(
      imageRect.left - fittedRect.left * scale,
      imageRect.top - fittedRect.top * scale,
    )
    ..scale(scale, scale);
}

@immutable
class GalleryImage {
  const GalleryImage(
    this.url, {
    this.width = 0,
    this.height = 0,
    bool? isHdr,
    this.raw,
    this.thumbnailUrl,
  }) : overrideIsHdr = isHdr;

  final String url;
  final int width;
  final int height;
  final bool? overrideIsHdr;
  final Map<String, dynamic>? raw;
  final String? thumbnailUrl;

  String get pid => thumbnailUrl ?? url;
  String get previewUrl => normalizeRedImageUrl(thumbnailUrl ?? url);
  String get originalUrl => normalizeRedImageUrl(url);
  String get largeUrl => originalUrl;
  String get bmiddleUrl => originalUrl;

  bool get isLong =>
      (height > 0 && width > 0 && height / width > 2.0) ||
      url.toLowerCase().contains('is_long=1');

  bool get isGif =>
      Uri.tryParse(url)?.path.toLowerCase().endsWith('.gif') == true ||
      url.toLowerCase().contains('.gif');

  bool get isHdr {
    if (overrideIsHdr != null) return overrideIsHdr!;
    final r = raw;
    if (r != null) {
      final val = r['is_hdr'] ?? r['isHdr'] ?? r['hdr'];
      if (val == 1 || val == '1' || val == true) return true;
    }
    final lower = url.toLowerCase();
    if (lower.contains('is_hdr=1') ||
        lower.contains('hdr=1') ||
        lower.contains('ultrahdr') ||
        lower.contains('ultra_hdr') ||
        lower.contains('gainmap')) {
      return true;
    }
    return HdrImageDetector.isKnownHdr(url) ||
        HdrImageDetector.isKnownHdr(previewUrl);
  }

  String get badgeText {
    if (isGif) return 'GIF';
    if (isHdr) return 'HDR';
    if (isLong) return '长图';
    return '';
  }

  bool get isWebpageCard => false;
  bool get isVideo => false;
}

/// Stable matching identity for a thumbnail and its fullscreen gallery image.
/// The scope is unique to the originating gallery widget, so two copies of the
/// same post on one route cannot accidentally claim the same Hero.
@immutable
class ImageGalleryHeroTag {
  const ImageGalleryHeroTag({
    required this.scope,
    required this.index,
    required this.mediaIdentity,
    required this.imageAspectRatio,
    required this.thumbnailUsesCover,
  });

  final Object scope;
  final int index;
  final String mediaIdentity;
  final double imageAspectRatio;
  final bool thumbnailUsesCover;

  factory ImageGalleryHeroTag.forPic({
    required Object scope,
    required int index,
    required GalleryImage pic,
  }) => ImageGalleryHeroTag(
    scope: scope,
    index: index,
    mediaIdentity: '${pic.pid}\u0000${pic.previewUrl}',
    imageAspectRatio: pic.width > 0 && pic.height > 0
        ? pic.width / pic.height
        : 1.0,
    thumbnailUsesCover: !pic.isWebpageCard,
  );

  @override
  bool operator ==(Object other) =>
      other is ImageGalleryHeroTag &&
      identical(scope, other.scope) &&
      index == other.index &&
      mediaIdentity == other.mediaIdentity;

  @override
  int get hashCode =>
      Object.hash(identityHashCode(scope), index, mediaIdentity);
}

/// Lets a destination keep the decoded source image after its Hero flight ends.
class ImageGalleryHeroPreviewHost extends InheritedWidget {
  const ImageGalleryHeroPreviewHost({
    super.key,
    required this.onPreviewCaptured,
    required super.child,
  });

  final ValueChanged<ui.Image> onPreviewCaptured;

  static void retainPreview(BuildContext context, ui.Image image) {
    context
        .getInheritedWidgetOfExactType<ImageGalleryHeroPreviewHost>()
        ?.onPreviewCaptured(image);
  }

  @override
  bool updateShouldNotify(ImageGalleryHeroPreviewHost oldWidget) =>
      onPreviewCaptured != oldWidget.onPreviewCaptured;
}

/// Keeps the image pixels in flight while their fitted geometry changes.
Widget imageGalleryHeroFlightShuttleBuilder(
  BuildContext flightContext,
  Animation<double> animation,
  HeroFlightDirection flightDirection,
  BuildContext fromHeroContext,
  BuildContext toHeroContext,
) {
  final fromHero = fromHeroContext.widget as Hero;
  final tag = fromHero.tag;
  final targetRenderObject = toHeroContext.findRenderObject();
  if (targetRenderObject is! RenderBox ||
      !targetRenderObject.hasSize ||
      targetRenderObject.size.width <= 0 ||
      targetRenderObject.size.height <= 0) {
    return fromHero.child;
  }

  final source = _HeroImageGeometry.capture(fromHeroContext);
  if (source == null) return fromHero.child;

  final isPop = flightDirection == HeroFlightDirection.pop;
  if (!isPop) {
    toHeroContext
        .findAncestorStateOfType<_ImageGalleryPageState>()
        ?._retainOpeningPreview(source.image);
    ImageGalleryHeroPreviewHost.retainPreview(toHeroContext, source.image);
  }
  if (isPop && (tag is! ImageGalleryHeroTag || !tag.thumbnailUsesCover)) {
    return fromHero.child;
  }
  final target = isPop
      ? _HeroImageGeometry.capture(toHeroContext)
      : _HeroImageGeometry.fitContain(source.image, targetRenderObject);
  if (target == null) return fromHero.child;
  return _HeroImageFlightMorph(
    animation: animation,
    image: source.image.clone(),
    source: source,
    target: target,
    reverse: isPop,
  );
}

/// Snapshot the complete image plane, not just the fitted/cropped viewport.
/// ExtendedImage has its own RenderBox (it is NOT a Flutter RenderImage).
class _HeroImageGeometry {
  const _HeroImageGeometry(this.image, this.rect, this.radius);
  final ui.Image image;
  final Rect rect;
  final BorderRadius radius;

  static _HeroImageGeometry? capture(BuildContext context) {
    final root = context.findRenderObject();
    if (root is! RenderBox || !root.hasSize) return null;
    final direction = Directionality.of(context);
    _HeroImageGeometry? result;
    BorderRadius radius = BorderRadius.zero;
    void visit(RenderObject object) {
      if (result != null) return;
      if (object is RenderClipRRect) {
        radius = object.borderRadius.resolve(direction);
      }
      ui.Image? image;
      BoxFit? fit;
      AlignmentGeometry alignment = Alignment.center;
      Rect? gestureRect;
      if (object is ExtendedRenderImage) {
        image = object.image;
        fit = object.fit;
        alignment = object.alignment;
        // Already painted gesture geometry includes current zoom and pan.
        final gesture = object.gestureDetails;
        // ExtendedImage stores these in the paint canvas coordinate space,
        // including its paint offset. Normalize before mapping to the Hero.
        if (gesture?.destinationRect != null && gesture?.layoutRect != null) {
          gestureRect = gesture!.destinationRect!.shift(
            -gesture.layoutRect!.topLeft,
          );
        }
      } else if (object is RenderImage) {
        image = object.image;
        fit = object.fit;
        alignment = object.alignment;
      }
      if (image != null && object is RenderBox && object.hasSize) {
        final pixels = Size(image.width.toDouble(), image.height.toDouble());
        final align = alignment.resolve(direction);
        final fitted = applyBoxFit(
          fit ?? BoxFit.scaleDown,
          pixels,
          object.size,
        );
        final crop = align.inscribe(fitted.source, Offset.zero & pixels);
        final painted =
            gestureRect ??
            align.inscribe(fitted.destination, Offset.zero & object.size);
        // Expand the cropped source back into the complete pixel plane so
        // interpolation never stretches the image to the viewport aspect ratio.
        final sx = painted.width / crop.width;
        final sy = painted.height / crop.height;
        final full = Rect.fromLTWH(
          painted.left - crop.left * sx,
          painted.top - crop.top * sy,
          pixels.width * sx,
          pixels.height * sy,
        );
        final transform = object.getTransformTo(root);
        result = _HeroImageGeometry(
          image,
          MatrixUtils.transformRect(transform, full),
          radius,
        );
        return;
      }
      object.visitChildren(visit);
    }

    visit(root);
    return result;
  }

  static _HeroImageGeometry? fitContain(ui.Image image, RenderBox target) {
    if (!target.hasSize || target.size.isEmpty) return null;
    final pixels = Size(image.width.toDouble(), image.height.toDouble());
    final fitted = applyBoxFit(BoxFit.contain, pixels, target.size).destination;
    return _HeroImageGeometry(
      image,
      Alignment.center.inscribe(fitted, Offset.zero & target.size),
      BorderRadius.zero,
    );
  }
}

/// Freeze the decoded image, then move its edges and the clipping viewport
/// independently. Re-layout of a gesture image during flight changes its crop.
class _HeroImageFlightMorph extends StatefulWidget {
  const _HeroImageFlightMorph({
    required this.animation,
    required this.image,
    required this.source,
    required this.target,
    required this.reverse,
  });

  final Animation<double> animation;
  final ui.Image image;
  final _HeroImageGeometry source;
  final _HeroImageGeometry target;
  final bool reverse;

  @override
  State<_HeroImageFlightMorph> createState() => _HeroImageFlightMorphState();
}

class _HeroImageFlightMorphState extends State<_HeroImageFlightMorph> {
  @override
  void dispose() {
    widget.image.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CustomPaint(
    key: const ValueKey('gallery-image-flight-plane'),
    painter: _HeroImageFlightPainter(
      widget.image,
      widget.animation,
      widget.source,
      widget.target,
      widget.reverse,
    ),
    child: const SizedBox.expand(),
  );
}

class _HeroImageFlightPainter extends CustomPainter {
  _HeroImageFlightPainter(
    this.image,
    this.animation,
    this.source,
    this.target,
    this.reverse,
  ) : super(repaint: animation);
  final ui.Image image;
  final Animation<double> animation;
  final _HeroImageGeometry source;
  final _HeroImageGeometry target;
  final bool reverse;

  @override
  void paint(Canvas canvas, Size size) {
    final progress = reverse ? 1 - animation.value : animation.value;
    final t = progress.clamp(0.0, 1.0);
    final rect = Rect.lerp(source.rect, target.rect, t)!;
    final radius = BorderRadius.lerp(source.radius, target.radius, t)!;
    canvas.save();
    canvas.clipRRect(radius.toRRect(Offset.zero & size));
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      rect,
      Paint()..filterQuality = FilterQuality.low,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _HeroImageFlightPainter oldDelegate) =>
      oldDelegate.image != image ||
      oldDelegate.source != source ||
      oldDelegate.target != target ||
      oldDelegate.animation != animation ||
      oldDelegate.reverse != reverse;
}

/// Wraps a tapped source thumbnail with the same tag used by [ImageGalleryPage].
class ImageGalleryHeroThumbnail extends StatelessWidget {
  const ImageGalleryHeroThumbnail({
    super.key,
    required this.scope,
    required this.index,
    required this.pic,
    required this.child,
  });

  final Object scope;
  final int index;
  final GalleryImage pic;
  final Widget child;

  @override
  Widget build(BuildContext context) => Hero(
    tag: ImageGalleryHeroTag.forPic(scope: scope, index: index, pic: pic),
    flightShuttleBuilder: imageGalleryHeroFlightShuttleBuilder,
    child: child,
  );
}

/// Reference gallery interactions with RedNote media and native saving.
class ImageGalleryPage extends StatefulWidget {
  final AppState app;
  final List<GalleryImage> pics;
  final int initialIndex;
  final String statusId;
  final bool isDetail;
  final String? authorName;
  final Object? heroScope;
  final bool? useLiquidGlass;

  const ImageGalleryPage({
    required this.app,
    super.key,
    required this.pics,
    required this.initialIndex,
    required this.statusId,
    this.isDetail = false,
    this.authorName,
    this.heroScope,
    this.useLiquidGlass,
  });

  @override
  State<ImageGalleryPage> createState() => _ImageGalleryPageState();
}

class _ImageGalleryPageState extends State<ImageGalleryPage>
    with TickerProviderStateMixin {
  static const MethodChannel _mediaChannel = MethodChannel(
    'com.red.note/media',
  );
  late int _currentIndex;
  late final ValueNotifier<int> _activeMediaIndex;
  late final ExtendedPageController _pageController;
  late final PageController _thumbnailController;
  bool _isSaving = false;
  bool _showChrome = true;
  bool _chromeRouteVisible = false;
  bool _hdrDisplaySupported = false;
  bool _hdrHybridSupported = false;
  bool? _windowHdrEnabled;
  final Set<String> _nativeHdrUrls = <String>{};
  final Map<String, ValueNotifier<Rect?>> _nativeHdrRects = {};
  late final AnimationController _chromeOpacityController;
  Animation<double>? _routeAnimation;
  bool _thumbnailUserScrolling = false;
  bool _thumbnailSettling = false;
  bool _mainUserScrolling = false;
  int _thumbnailSyncEpoch = 0;
  double? _thumbnailScrollBottom;
  final Expando<ExtendedImageGesture> _pagingImages = Expando();
  ui.Image? _openingPreview;
  final Set<int> _galleryPointers = {};
  int? _galleryTapPointer;
  Offset? _galleryTapDownPosition;
  bool _galleryTapMoved = false;
  Timer? _galleryTapTimer;
  Offset? _pendingGalleryTapPosition;

  late final AnimationController _doubleTapAnimationController;
  Animation<double>? _doubleTapAnimation;
  Function()? _doubleTapListener;
  bool _isDoubleTapZooming = false;
  final ValueNotifier<bool> _isMainPageScrolling = ValueNotifier<bool>(false);
  Timer? _mainPageScrollEndTimer;
  int _doubleTapAnimationEpoch = 0;

  @override
  void initState() {
    super.initState();
    _chromeOpacityController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 140),
    );
    _currentIndex = widget.initialIndex;
    _activeMediaIndex = ValueNotifier(widget.initialIndex);
    _pageController = ExtendedPageController(
      initialPage: widget.initialIndex,
      shouldIgnorePointerWhenScrolling: false,
    );
    _thumbnailController = PageController(
      initialPage: widget.initialIndex,
      viewportFraction: 0.16,
    );
    _thumbnailController.addListener(_syncMainPageToThumbnail);
    _doubleTapAnimationController = AnimationController(
      duration: const Duration(milliseconds: 260),
      vsync: this,
    );
    _mediaChannel.setMethodCallHandler(_handleNativeMediaCall);
    _setNativeStatusBarVisible(true);
    unawaited(_checkHdrDisplaySupport());
    HdrImageDetector.notifier.addListener(_onHdrDetected);
    _updateWindowHdr();
  }

  Future<void> _checkHdrDisplaySupport() async {
    try {
      final supported = await _mediaChannel.invokeMethod<bool>(
        'isHdrDisplaySupported',
      );
      var hybridSupported = false;
      if (supported == true) {
        try {
          hybridSupported =
              await HybridAndroidViewController.checkIfSupported();
        } catch (_) {
          // Engines without HCPP keep the legacy HDR composition path.
        }
      }
      if (!mounted) return;
      setState(() {
        _hdrDisplaySupported = supported ?? false;
        _hdrHybridSupported = hybridSupported;
      });
      _updateWindowHdr();
    } catch (_) {
      // Unsupported Android versions keep the normal Flutter image path.
    }
  }

  Future<void> _handleNativeMediaCall(MethodCall call) async {
    if (call.method != 'hdrImageDecoded') return;
    final args = call.arguments;
    if (args is! Map || args['hasGainMap'] != true) return;
    final url = args['url']?.toString() ?? '';
    if (url.isEmpty || !mounted) return;
    HdrImageDetector.markHdr(url);
    setState(() => _nativeHdrUrls.add(normalizeRedImageUrl(url)));
    _updateWindowHdr();
  }

  ValueNotifier<Rect?> _hdrRectFor(String url) => _nativeHdrRects.putIfAbsent(
    normalizeRedImageUrl(url),
    () => ValueNotifier<Rect?>(null),
  );

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    final routeAnimation = route?.animation;
    if (identical(routeAnimation, _routeAnimation)) return;

    _routeAnimation?.removeStatusListener(_handleRouteAnimationStatus);
    _routeAnimation = routeAnimation;
    if (routeAnimation == null) {
      _chromeRouteVisible = true;
      _chromeOpacityController.value = 1;
      return;
    }

    // A pushed route can initially report completed before its forward tick.
    // Only a completed first route should show controls immediately.
    _chromeRouteVisible =
        route?.isFirst == true &&
        routeAnimation.status == AnimationStatus.completed;
    _chromeOpacityController.value = _chromeRouteVisible ? 1 : 0;
    routeAnimation.addStatusListener(_handleRouteAnimationStatus);
  }

  void _handleRouteAnimationStatus(AnimationStatus status) {
    final visible = status == AnimationStatus.completed;
    if (!mounted || visible == _chromeRouteVisible) return;
    setState(() => _chromeRouteVisible = visible);
    if (visible) {
      _chromeOpacityController.forward();
    } else {
      _chromeOpacityController.reverse();
    }
  }

  @override
  void dispose() {
    _openingPreview?.dispose();
    HdrImageDetector.notifier.removeListener(_onHdrDetected);
    _mediaChannel.setMethodCallHandler(null);
    _galleryTapTimer?.cancel();
    _mainPageScrollEndTimer?.cancel();
    for (final rect in _nativeHdrRects.values) {
      rect.dispose();
    }
    _nativeHdrRects.clear();
    unawaited(
      _mediaChannel
          .invokeMethod<void>('setWindowHdr', {'enabled': false})
          .catchError((_) {}),
    );
    _routeAnimation?.removeStatusListener(_handleRouteAnimationStatus);
    _restoreSystemUi();
    _chromeOpacityController.dispose();
    _doubleTapAnimationController.dispose();
    _pageController.dispose();
    _thumbnailController.removeListener(_syncMainPageToThumbnail);
    _thumbnailController.dispose();
    _activeMediaIndex.dispose();
    _isMainPageScrolling.dispose();
    super.dispose();
  }

  void _onHdrDetected() {
    if (!mounted) return;
    setState(() {});
    _updateWindowHdr();
  }

  void _retainOpeningPreview(ui.Image image) {
    if (_openingPreview != null) return;
    // The flight can end before the separately sized original is decoded.
    _openingPreview = image.clone();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) setState(() {});
    });
  }

  void _updateWindowHdr() {
    // Keep the window mode stable across HDR/SDR pages; the bitmap gain map
    // determines HDR output, not the window flag alone.
    final isHdr = _hdrDisplaySupported && widget.pics.any((pic) => pic.isHdr);
    if (_windowHdrEnabled == isHdr) return;
    _windowHdrEnabled = isHdr;
    unawaited(
      _mediaChannel
          .invokeMethod<void>('setWindowHdr', {'enabled': isHdr})
          .catchError((_) {}),
    );
  }

  void _restoreSystemUi() {
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    _setNativeStatusBarVisible(true);
  }

  void _setNativeStatusBarVisible(bool visible) {
    unawaited(
      _mediaChannel
          .invokeMethod<void>('setImageGalleryStatusBarVisible', {
            'visible': visible,
          })
          .catchError((_) {}),
    );
  }

  Future<void> _saveCurrentImage() async {
    if (_isSaving) return;
    setState(() => _isSaving = true);
    try {
      final subDir = StoragePathResolver.resolveSubDir(
        pathType: widget.app.savePath(false),
        myNickname: widget.app.user['nickname']?.toString(),
        authorName: widget.authorName,
        isVideo: false,
      );
      final saved = await _mediaChannel.invokeMethod<String>(
        'saveRemoteMedia',
        {
          'url': widget.pics[_currentIndex].originalUrl,
          'subDir': subDir,
          'video': false,
        },
      );
      HapticFeedbackUtil.medium();
      if (mounted) AppToast.show(context, '图片已保存至相册：$saved');
    } on PlatformException catch (error) {
      if (mounted) AppToast.show(context, error.message ?? '图片保存失败，请重试');
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  void _toggleChrome() {
    _setChromeVisible(!_showChrome);
  }

  void _setChromeVisible(bool visible) {
    if (!mounted || _showChrome == visible) return;
    setState(() => _showChrome = visible);
  }

  double get _thumbnailBottom =>
      _thumbnailScrollBottom ?? MediaQuery.paddingOf(context).bottom + 32;

  void _handleGalleryTap(Offset position) {
    final screenWidth = MediaQuery.sizeOf(context).width;
    final x = position.dx;
    final isMiddleThird = x >= screenWidth / 3 && x < screenWidth * 2 / 3;

    if (isMiddleThird) {
      _toggleChrome();
    } else {
      Navigator.of(context).pop();
    }
  }

  void _handleGalleryPointerDown(PointerDownEvent event) {
    if (_galleryPointers.isEmpty) {
      _galleryTapPointer = event.pointer;
      _galleryTapDownPosition = event.position;
      _galleryTapMoved = false;
    } else {
      _galleryTapMoved = true;
    }
    _galleryPointers.add(event.pointer);
  }

  void _handleGalleryPointerMove(PointerMoveEvent event) {
    final downPosition = _galleryTapDownPosition;
    if (_galleryTapPointer == event.pointer &&
        downPosition != null &&
        (event.position - downPosition).distance > kTouchSlop) {
      _galleryTapMoved = true;
    }
  }

  void _handleGalleryPointerUp(PointerUpEvent event) {
    if (!_galleryPointers.remove(event.pointer)) return;
    if (_galleryTapPointer != event.pointer) {
      if (_galleryPointers.isEmpty) _resetGalleryTapPointer();
      return;
    }

    final isTap = _galleryPointers.isEmpty && !_galleryTapMoved;
    _resetGalleryTapPointer();
    if (isTap) _registerGalleryTap(event.position);
  }

  void _handleGalleryPointerCancel(PointerCancelEvent event) {
    if (!_galleryPointers.remove(event.pointer)) return;
    if (_galleryTapPointer == event.pointer || _galleryPointers.isEmpty) {
      _resetGalleryTapPointer();
    }
  }

  void _resetGalleryTapPointer() {
    _galleryTapPointer = null;
    _galleryTapDownPosition = null;
    _galleryTapMoved = false;
  }

  void _registerGalleryTap(Offset position) {
    final pendingPosition = _pendingGalleryTapPosition;
    if (_galleryTapTimer?.isActive == true &&
        pendingPosition != null &&
        (position - pendingPosition).distance <= kDoubleTapSlop) {
      _galleryTapTimer!.cancel();
      _galleryTapTimer = null;
      _pendingGalleryTapPosition = null;
      return;
    }

    _galleryTapTimer?.cancel();
    if (pendingPosition != null) _handleGalleryTap(pendingPosition);
    _pendingGalleryTapPosition = position;
    _galleryTapTimer = Timer(kDoubleTapTimeout, () {
      final pending = _pendingGalleryTapPosition;
      _galleryTapTimer = null;
      _pendingGalleryTapPosition = null;
      if (pending != null) _handleGalleryTap(pending);
    });
  }

  void _selectThumbnail(int index) {
    if (index == _currentIndex || !_pageController.hasClients) return;
    _pageController.jumpToPage(index);
  }

  void _syncMainPageToThumbnail() {
    if (!_thumbnailUserScrolling ||
        !_thumbnailController.hasClients ||
        !_pageController.hasClients) {
      return;
    }

    final page = (_thumbnailController.page ?? _currentIndex.toDouble()).clamp(
      0.0,
      (widget.pics.length - 1).toDouble(),
    );
    final mainPosition = _pageController.position;
    final targetPixels =
        (mainPosition.minScrollExtent + page * mainPosition.viewportDimension)
            .clamp(mainPosition.minScrollExtent, mainPosition.maxScrollExtent)
            .toDouble();
    if ((mainPosition.pixels - targetPixels).abs() > 0.5) {
      // Mirror the reel's fractional page position directly. A queued series
      // of animateToPage calls falls behind a fast fling by design.
      mainPosition.jumpTo(targetPixels);
    }
  }

  void _activateCurrentMedia() {
    if (!mounted) return;
    _activeMediaIndex.value = _currentIndex;
  }

  void _settleThumbnailScroll() {
    if (_thumbnailSettling ||
        !_thumbnailController.hasClients ||
        !_pageController.hasClients) {
      return;
    }

    final target = (_thumbnailController.page ?? _currentIndex.toDouble())
        .round()
        .clamp(0, widget.pics.length - 1);
    final epoch = ++_thumbnailSyncEpoch;
    _thumbnailUserScrolling = false;
    _thumbnailSettling = true;
    const duration = Duration(milliseconds: 120);
    unawaited(
      Future.wait<void>([
        _thumbnailController.animateToPage(
          target,
          duration: duration,
          curve: Curves.easeOutCubic,
        ),
        _pageController.animateToPage(
          target,
          duration: duration,
          curve: Curves.easeOutCubic,
        ),
      ]).whenComplete(() {
        if (!mounted || epoch != _thumbnailSyncEpoch) return;
        _thumbnailSettling = false;
        setState(() => _thumbnailScrollBottom = null);
        _activateCurrentMedia();
      }),
    );
  }

  Widget _withGalleryHero(int index, GalleryImage pic, Widget child) {
    final scope = widget.heroScope;
    // PageView keeps neighboring pages alive. If each retained page has a
    // Hero, popping the route can launch multiple flights back to the grid.
    // Disable neighboring flights without replacing the image gesture subtree.
    if (scope == null || pic.isVideo) return child;
    return HeroMode(
      enabled: index == _currentIndex,
      child: Hero(
        tag: ImageGalleryHeroTag.forPic(scope: scope, index: index, pic: pic),
        flightShuttleBuilder: imageGalleryHeroFlightShuttleBuilder,
        child: child,
      ),
    );
  }

  bool _handleThumbnailScroll(ScrollNotification notification) {
    if (notification.depth != 0) return false;
    if (notification is ScrollStartNotification &&
        notification.dragDetails != null) {
      // The reel must stay under the finger while paging unloads the player.
      _thumbnailScrollBottom = _thumbnailBottom;
      _thumbnailSyncEpoch++;
      _thumbnailSettling = false;
      _thumbnailUserScrolling = true;
      _mainUserScrolling = false;
      _activeMediaIndex.value = -1;
      setState(() {});
    } else if (notification is ScrollEndNotification &&
        _thumbnailUserScrolling) {
      final epoch = _thumbnailSyncEpoch;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted &&
            epoch == _thumbnailSyncEpoch &&
            _thumbnailUserScrolling) {
          _settleThumbnailScroll();
        }
      });
    }
    return false;
  }

  Widget _buildThumbnailStrip() {
    return SizedBox(
      key: const ValueKey('gallery-thumbnail-strip'),
      height: 64,
      child: NotificationListener<ScrollNotification>(
        onNotification: _handleThumbnailScroll,
        child: PageView.builder(
          controller: _thumbnailController,
          physics: const ClampingScrollPhysics(),
          pageSnapping: false,
          itemCount: widget.pics.length,
          itemBuilder: (context, index) {
            final selected = index == _currentIndex;
            return Semantics(
              label: '第 ${index + 1} 张图片',
              selected: selected,
              button: true,
              child: GestureDetector(
                key: ValueKey('gallery-thumbnail-$index'),
                onTap: () {
                  HapticFeedbackUtil.light();
                  _thumbnailSyncEpoch++;
                  _thumbnailScrollBottom = null;
                  _thumbnailUserScrolling = false;
                  _thumbnailSettling = false;
                  _thumbnailController.jumpToPage(index);
                  _selectThumbnail(index);
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 3,
                    vertical: 2,
                  ),
                  child: Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: selected ? Colors.white : Colors.transparent,
                        width: 2,
                      ),
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          ExtendedImage.network(
                            widget.pics[index].previewUrl,
                            headers: redImageHeaders(
                              widget.pics[index].previewUrl,
                              api: widget.app,
                            ),
                            fit: BoxFit.cover,
                            cacheWidth: 160,
                            cache: true,
                          ),
                          if (widget.pics[index].badgeText.isNotEmpty)
                            Positioned(
                              bottom: 2,
                              right: 2,
                              child: _buildThumbnailBadge(
                                widget.pics[index].badgeText,
                              ),
                            ),
                          if (!selected)
                            ColoredBox(
                              color: Colors.white.withValues(alpha: 0.5),
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildThumbnailBadge(String text) {
    final isHdr = text == 'HDR';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 3, vertical: 1),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.75),
        borderRadius: BorderRadius.circular(3),
        border: isHdr
            ? Border.all(color: Colors.white.withValues(alpha: 0.5), width: 0.5)
            : null,
      ),
      child: Text(
        text,
        style: TextStyle(
          color: Colors.white,
          fontSize: 7.5,
          fontWeight: isHdr ? FontWeight.w900 : FontWeight.bold,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final hasScope =
        context
            .getElementForInheritedWidgetOfExactType<
              UncontrolledProviderScope
            >() !=
        null;
    if (hasScope) {
      return Consumer(
        builder: (context, ref, _) {
          final useLiquidGlass =
              (widget.useLiquidGlass ??
                  ref.watch(
                    appSettingsProvider.select(
                      (AppSettingsState s) => s.useLiquidGlassNavBar,
                    ),
                  )) ==
              true;
          return _buildGallery(context, useLiquidGlass);
        },
      );
    }
    final useLiquidGlass = widget.useLiquidGlass ?? false;
    return _buildGallery(context, useLiquidGlass);
  }

  Widget _buildGallery(BuildContext context, bool useLiquidGlass) {
    final galleryPage = Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // Gesture PageView with Physics-based Spring Morphing
          GlassBackgroundSource(
            enabled: useLiquidGlass,
            child: Listener(
              onPointerDown: _handleGalleryPointerDown,
              onPointerMove: _handleGalleryPointerMove,
              onPointerUp: _handleGalleryPointerUp,
              onPointerCancel: _handleGalleryPointerCancel,
              child: NotificationListener<ScrollNotification>(
                onNotification: (notification) {
                  if (notification.depth == 0) {
                    if (notification is ScrollStartNotification) {
                      _mainPageScrollEndTimer?.cancel();
                      if (notification.dragDetails != null) {
                        if (_thumbnailScrollBottom != null) {
                          setState(() => _thumbnailScrollBottom = null);
                        }
                        _thumbnailSyncEpoch++;
                        _thumbnailUserScrolling = false;
                        _thumbnailSettling = false;
                        _mainUserScrolling = true;
                        _activeMediaIndex.value = -1;
                        _isMainPageScrolling.value = true;
                      }
                    } else if (notification is ScrollUpdateNotification) {
                      if (notification.dragDetails != null &&
                          !_isMainPageScrolling.value) {
                        _mainPageScrollEndTimer?.cancel();
                        _mainUserScrolling = true;
                        _activeMediaIndex.value = -1;
                        _isMainPageScrolling.value = true;
                      }
                    } else if (notification is ScrollEndNotification) {
                      _mainPageScrollEndTimer?.cancel();
                      _mainPageScrollEndTimer = Timer(
                        const Duration(milliseconds: 140),
                        () {
                          if (mounted &&
                              _pageController.hasClients &&
                              !_pageController
                                  .position
                                  .isScrollingNotifier
                                  .value) {
                            _isMainPageScrolling.value = false;
                            if (_mainUserScrolling) {
                              _mainUserScrolling = false;
                              _activeMediaIndex.value = _currentIndex;
                            }
                          }
                        },
                      );
                    }
                  }
                  return false;
                },
                child: ExtendedImageGesturePageView.builder(
                  controller: _pageController,
                  physics: const BouncingScrollPhysics(),
                  canScrollPage: (gestureDetails) =>
                      (gestureDetails?.totalScale ?? 1.0) <= 1.0,
                  shouldAccpetHorizontalOrVerticalDrag: (trackers) =>
                      trackers.length == 1,
                  pageSnapping: !_thumbnailUserScrolling && !_thumbnailSettling,
                  itemCount: widget.pics.length,
                  onPageChanged: (index) {
                    final didChange = index != _currentIndex;
                    if (didChange && _thumbnailUserScrolling) {
                      HapticFeedbackUtil.selectionTick();
                    } else if (!_thumbnailUserScrolling &&
                        !_thumbnailSettling &&
                        !_mainUserScrolling) {
                      _activeMediaIndex.value = index;
                    }
                    setState(() {
                      _currentIndex = index;
                    });
                    _updateWindowHdr();
                    if (!_thumbnailUserScrolling &&
                        !_thumbnailSettling &&
                        _thumbnailController.hasClients) {
                      // A main-image gesture takes ownership, cancelling any
                      // old thumbnail inertia without feeding selection back.
                      _thumbnailController.jumpToPage(index);
                    }
                  },
                  itemBuilder: (context, index) {
                    final pic = widget.pics[index];
                    final isLongPic =
                        pic.isLong ||
                        (pic.height > 0 &&
                            pic.width > 0 &&
                            pic.height / pic.width > 2.0);

                    final foregroundImage = ExtendedImage.network(
                      pic.originalUrl.isNotEmpty
                          ? pic.originalUrl
                          : (pic.largeUrl.isNotEmpty
                                ? pic.largeUrl
                                : pic.bmiddleUrl),
                      width: double.infinity,
                      height: double.infinity,
                      headers: redImageHeaders(
                        pic.originalUrl,
                        api: widget.app,
                      ),
                      cache: true,
                      fit: BoxFit.contain,
                      mode: ExtendedImageMode.gesture,
                      enableSlideOutPage: false,
                      loadStateChanged: (state) {
                        if (state.extendedImageLoadState == LoadState.loading &&
                            index == widget.initialIndex &&
                            _openingPreview != null) {
                          return RawImage(
                            key: const ValueKey('gallery-opening-preview'),
                            image: _openingPreview,
                            fit: BoxFit.contain,
                          );
                        }
                        if (state.extendedImageLoadState !=
                            LoadState.completed) {
                          return null;
                        }
                        HdrImageDetector.detectFromCache(
                          pic.originalUrl.isNotEmpty
                              ? pic.originalUrl
                              : pic.previewUrl,
                          headers: redImageHeaders(
                            pic.originalUrl.isNotEmpty
                                ? pic.originalUrl
                                : pic.previewUrl,
                            api: widget.app,
                          ),
                        );
                        return _pagingImages[state
                            .extendedImageInfo!] ??= ExtendedImageGesture(
                          state,
                          imageBuilder: (image, {imageGestureState}) {
                            final gestureState = imageGestureState!;
                            final pager = gestureState.context
                                .findAncestorStateOfType<
                                  ExtendedImageGesturePageViewState
                                >();
                            // Hero flights paint in the Navigator overlay, outside the pager.
                            if (pager == null) return image;
                            return Listener(
                              onPointerDown: (_) {
                                pager.extendedImageGestureStates.remove(
                                  gestureState,
                                );
                                pager.extendedImageGestureState = gestureState;
                              },
                              child: RawGestureDetector(
                                gestures: {
                                  ExtendedHorizontalDragGestureRecognizer:
                                      GestureRecognizerFactoryWithHandlers<
                                        ExtendedHorizontalDragGestureRecognizer
                                      >(
                                        () => ExtendedHorizontalDragGestureRecognizer(
                                          canHorizontalOrVerticalDrag: () =>
                                              (gestureState
                                                      .gestureDetails
                                                      ?.totalScale ??
                                                  1) <=
                                              1,
                                          shouldAccpetHorizontalOrVerticalDrag:
                                              (trackers) =>
                                                  trackers.length == 1,
                                        ),
                                        (recognizer) => recognizer
                                          ..onDown = pager.onDragDown
                                          ..onStart = pager.onDragStart
                                          ..onUpdate = pager.onDragUpdate
                                          ..onEnd = pager.onDragEnd
                                          ..onCancel = pager.onDragCancel
                                          ..minFlingDistance = pager
                                              .widget
                                              .physics
                                              .minFlingDistance
                                          ..minFlingVelocity = pager
                                              .widget
                                              .physics
                                              .minFlingVelocity
                                          ..maxFlingVelocity = pager
                                              .widget
                                              .physics
                                              .maxFlingVelocity
                                          ..gestureSettings =
                                              MediaQuery.gestureSettingsOf(
                                                context,
                                              ),
                                      ),
                                },
                                child: image,
                              ),
                            );
                          },
                        );
                      },
                      onDoubleTap: (ExtendedImageGestureState state) {
                        final pointerDownPosition = state.pointerDownPosition;
                        final begin = state.gestureDetails!.totalScale ?? 1.0;
                        double end = 1.0;
                        if (begin <= 1.05) {
                          end = isLongPic ? 3.5 : 2.5;
                        } else if (begin <= 3.6) {
                          end = 6.0;
                        } else {
                          end = 1.0;
                        }

                        HapticFeedbackUtil.medium();
                        _doubleTapAnimationController.stop();
                        _doubleTapAnimationController.reset();
                        final animationEpoch = ++_doubleTapAnimationEpoch;
                        if (!_isDoubleTapZooming) {
                          setState(() => _isDoubleTapZooming = true);
                        }

                        if (_doubleTapListener != null) {
                          _doubleTapAnimation?.removeListener(
                            _doubleTapListener!,
                          );
                        }

                        _doubleTapAnimation =
                            Tween<double>(begin: begin, end: end).animate(
                              CurvedAnimation(
                                parent: _doubleTapAnimationController,
                                curve: Curves.easeOutCubic,
                              ),
                            );

                        _doubleTapListener = () {
                          state.handleDoubleTap(
                            scale: _doubleTapAnimation!.value,
                            doubleTapPosition: pointerDownPosition,
                          );
                        };
                        _doubleTapAnimation!.addListener(_doubleTapListener!);

                        _doubleTapAnimationController
                            .forward()
                            .orCancel
                            .then((_) {
                              if (!mounted ||
                                  animationEpoch != _doubleTapAnimationEpoch) {
                                return;
                              }
                              final rect =
                                  state.gestureDetails?.destinationRect;
                              if (rect != null) {
                                _hdrRectFor(pic.originalUrl).value = rect;
                              }
                              setState(() => _isDoubleTapZooming = false);
                            })
                            .catchError((Object _) {});
                      },
                      initGestureConfigHandler: (state) {
                        return GestureConfig(
                          // Do not let the image shrink below its fitted
                          // size: ExtendedImage recenters sub-1.0 scales,
                          // which otherwise makes pinch focus jump away.
                          minScale: 1.0,
                          animationMinScale: 1.0,
                          maxScale: 8.0,
                          animationMaxScale: 9.0,
                          speed: 1.0,
                          inertialSpeed: 120.0,
                          initialScale: 1.0,
                          inPageView: true,
                          initialAlignment: InitialAlignment.center,
                          gestureDetailsIsChanged: pic.isHdr
                              ? (details) {
                                  _hdrRectFor(pic.originalUrl).value =
                                      details?.destinationRect;
                                }
                              : null,
                        );
                      },
                    );
                    final heroImage = _withGalleryHero(
                      index,
                      pic,
                      foregroundImage,
                    );
                    final normalizedUrl = normalizeRedImageUrl(pic.originalUrl);
                    final isCurrentPage = index == _currentIndex;
                    final image = ValueListenableBuilder<bool>(
                      valueListenable: _isMainPageScrolling,
                      builder: (context, isMainScrolling, _) {
                        final isScrolling =
                            isMainScrolling ||
                            _thumbnailUserScrolling ||
                            _thumbnailSettling;
                        final useNativeHdr =
                            (_hdrHybridSupported ||
                                (isCurrentPage &&
                                    !isScrolling &&
                                    !_isDoubleTapZooming)) &&
                            _hdrDisplaySupported &&
                            pic.isHdr &&
                            _nativeHdrUrls.contains(normalizedUrl) &&
                            _chromeRouteVisible;
                        return LayoutBuilder(
                          builder: (context, constraints) {
                            final viewport = Rect.fromLTWH(
                              0,
                              0,
                              constraints.maxWidth,
                              constraints.maxHeight,
                            );
                            return Stack(
                              fit: StackFit.expand,
                              children: [
                                heroImage,
                                if (_hdrDisplaySupported &&
                                    pic.isHdr &&
                                    (_hdrHybridSupported ||
                                        (isCurrentPage && !isScrolling)))
                                  ValueListenableBuilder<Rect?>(
                                    valueListenable: _hdrRectFor(
                                      pic.originalUrl,
                                    ),
                                    child: PlatformViewLink(
                                      key: ValueKey(
                                        'native-hdr-$normalizedUrl',
                                      ),
                                      viewType: 'com.red.note/hdrImage',
                                      surfaceFactory: (context, controller) =>
                                          AndroidViewSurface(
                                            controller:
                                                controller
                                                    as AndroidViewController,
                                            gestureRecognizers:
                                                const <
                                                  Factory<
                                                    OneSequenceGestureRecognizer
                                                  >
                                                >{},
                                            hitTestBehavior:
                                                PlatformViewHitTestBehavior
                                                    .transparent,
                                          ),
                                      onCreatePlatformView: (params) {
                                        // HCPP is selected by the engine when supported;
                                        // legacy devices must keep window HDR, not TLHC.
                                        return PlatformViewsService.initExpensiveAndroidView(
                                            id: params.id,
                                            viewType: 'com.red.note/hdrImage',
                                            layoutDirection: Directionality.of(
                                              context,
                                            ),
                                            creationParams: {
                                              'url': pic.originalUrl,
                                              'headers': redImageHeaders(
                                                pic.originalUrl,
                                                api: widget.app,
                                              ),
                                            },
                                            creationParamsCodec:
                                                const StandardMessageCodec(),
                                            onFocus: () =>
                                                params.onFocusChanged(true),
                                          )
                                          ..addOnPlatformViewCreatedListener(
                                            params.onPlatformViewCreated,
                                          )
                                          ..create();
                                      },
                                    ),
                                    builder: (context, rect, nativeView) {
                                      final transform = hdrImageViewTransform(
                                        viewport,
                                        rect,
                                      );
                                      return Positioned.fill(
                                        child: IgnorePointer(
                                          child: Transform(
                                            alignment: Alignment.topLeft,
                                            transform: transform,
                                            child: Opacity(
                                              opacity: useNativeHdr ? 1 : 0,
                                              child: nativeView,
                                            ),
                                          ),
                                        ),
                                      );
                                    },
                                  ),
                              ],
                            );
                          },
                        );
                      },
                    );
                    if (pic.badgeText.isEmpty) return image;

                    final bottomOffset = widget.pics.length > 1
                        ? (_thumbnailBottom + 64 + 14)
                        : (MediaQuery.paddingOf(context).bottom + 24);
                    return Stack(
                      children: [
                        image,
                        Positioned(
                          right: 16,
                          bottom: bottomOffset,
                          child: IgnorePointer(
                            child: _GalleryMediaBadge(text: pic.badgeText),
                          ),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ),
          ),

          if (widget.pics.length > 1)
            Positioned(
              left: 12,
              right: 12,
              bottom: _thumbnailBottom,
              child: Visibility(
                visible: _showChrome,
                maintainState: true,
                child: IgnorePointer(
                  ignoring: !_chromeRouteVisible,
                  child: AnimatedOpacity(
                    key: const ValueKey('gallery-thumbnail-strip-fade'),
                    opacity: _chromeRouteVisible ? 1 : 0,
                    duration: const Duration(milliseconds: 140),
                    curve: Curves.easeOutCubic,
                    child: _buildThumbnailStrip(),
                  ),
                ),
              ),
            ),

          // Top Bar: Back, Counter, Live Pill, Save
          if (_showChrome)
            IgnorePointer(
              key: const ValueKey('gallery-top-controls-pointer-gate'),
              ignoring: !_chromeRouteVisible,
              child: AnimatedBuilder(
                animation: _chromeOpacityController,
                builder: (context, _) {
                  final controlsOpacity = _chromeOpacityController.value;
                  return SafeArea(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 10,
                      ),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          _GalleryGlassIconButton(
                            tooltip: '返回',
                            opacity: controlsOpacity,
                            opacityKey: const ValueKey(
                              'gallery-back-button-opacity',
                            ),
                            icon: const Icon(
                              Icons.arrow_back_rounded,
                              color: Colors.white,
                            ),
                            useLiquidGlass: useLiquidGlass,
                            // 返回动作不再依赖水波纹触感。
                            onPressed: () => Navigator.of(context).pop(),
                          ),

                          // Counter or Live Photo Switcher Pill
                          Opacity(
                            key: const ValueKey('gallery-counter-opacity'),
                            opacity: controlsOpacity,
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 14,
                                vertical: 6,
                              ),
                              decoration: BoxDecoration(
                                color: Colors.black45,
                                borderRadius: BorderRadius.circular(16),
                              ),
                              child: Text(
                                '${_currentIndex + 1} / ${widget.pics.length}',
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 13,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ),

                          _GalleryGlassIconButton(
                            tooltip: '保存高清大图到相册',
                            opacity: controlsOpacity,
                            opacityKey: const ValueKey(
                              'gallery-download-button-opacity',
                            ),
                            icon: _isSaving
                                ? const SizedBox(
                                    width: 18,
                                    height: 18,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      color: Colors.white,
                                    ),
                                  )
                                : const Icon(
                                    Icons.download_rounded,
                                    color: Colors.white,
                                  ),
                            useLiquidGlass: useLiquidGlass,
                            onPressed: _isSaving ? null : _saveCurrentImage,
                          ),
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
        ],
      ),
    );

    return useLiquidGlass
        ? GlassAdaptiveScope(
            minQuality: GlassQuality.standard,
            child: GlassPage(
              enableBackgroundSampling: true,
              child: galleryPage,
            ),
          )
        : galleryPage;
  }
}

class _GalleryGlassIconButton extends StatefulWidget {
  const _GalleryGlassIconButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    required this.opacity,
    required this.opacityKey,
    required this.useLiquidGlass,
  });

  final Widget icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final double opacity;
  final Key opacityKey;
  final bool useLiquidGlass;

  @override
  State<_GalleryGlassIconButton> createState() =>
      _GalleryGlassIconButtonState();
}

class _GalleryGlassIconButtonState extends State<_GalleryGlassIconButton> {
  static Future<ui.FragmentProgram>? _programFuture;
  ui.FragmentShader? _shader;

  @override
  void initState() {
    super.initState();
    if (!widget.useLiquidGlass && ui.ImageFilter.isShaderFilterSupported) {
      _loadShader();
    }
  }

  @override
  void didUpdateWidget(covariant _GalleryGlassIconButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.useLiquidGlass == widget.useLiquidGlass) return;
    if (widget.useLiquidGlass) {
      _shader?.dispose();
      _shader = null;
    } else if (ui.ImageFilter.isShaderFilterSupported) {
      _loadShader();
    }
  }

  Future<void> _loadShader() async {
    try {
      final program = await (_programFuture ??= ui.FragmentProgram.fromAsset(
        'shaders/gallery_glass_button.frag',
      ));
      final shader = program.fragmentShader();
      if (!mounted || widget.useLiquidGlass) {
        shader.dispose();
        return;
      }
      setState(() => _shader = shader);
    } catch (_) {
      // Blur and the highlight surface remain usable if shader loading fails.
    }
  }

  @override
  void dispose() {
    _shader?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final opacity = widget.opacity;
    if (widget.useLiquidGlass) {
      return GestureDetector(
        excludeFromSemantics: true,
        // Long holds are reserved for exploring the liquid effect.
        onLongPress: () {},
        child: GlassButton(
          label: widget.tooltip,
          width: 48,
          height: 48,
          icon: widget.icon,
          iconSize: 22,
          iconColor: Colors.white,
          onTap: () => widget.onPressed?.call(),
          enabled: widget.onPressed != null,
          useOwnLayer: true,
          stretch: 0.8,
          anchorStretchSettings: const AnchorStretchSettings(
            intensity: 0.8,
            squashFactor: 0.3,
            translationDamping: 0.15,
            bounciness: 0.2,
          ),
          glowColor: const Color(0x20FFFFFF),
          glowOpacity: 1,
          quality: GlassQuality.premium,
          settings: LiquidGlassSettings(
            visibility: opacity,
            glassColor: const Color(0x66000000),
            bodyMode: GlassBodyMode.clear,
            thickness: 32,
            blur: 2,
            lightIntensity: 0.25,
            ambientStrength: 0,
            refractiveIndex: 1.24,
            chromaticAberration: 0.025,
            shadowElevation: 0,
          ),
        ),
      );
    }
    final blur = ui.ImageFilter.blur(
      sigmaX: 2.5 * opacity,
      sigmaY: 2.5 * opacity,
    );
    final shader = _shader;
    final filter = shader == null || opacity < 1
        ? blur
        : ui.ImageFilter.compose(
            outer: ui.ImageFilter.shader(shader),
            inner: blur,
          );
    return SizedBox(
      width: 48,
      height: 48,
      child: Stack(
        alignment: Alignment.center,
        children: [
          SizedBox(
            width: 44,
            height: 44,
            child: Container(
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.20 * opacity),
                    blurRadius: 7,
                    offset: const Offset(0, 2),
                  ),
                  BoxShadow(
                    color: Colors.white.withValues(alpha: 0.08 * opacity),
                    blurRadius: 5,
                    offset: const Offset(0, -1),
                  ),
                ],
              ),
              child: ClipOval(
                clipBehavior: Clip.antiAlias,
                child: BackdropFilter(
                  filter: filter,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Colors.grey.shade200.withValues(
                            alpha: 0.30 * opacity,
                          ),
                          Colors.grey.shade500.withValues(
                            alpha: 0.20 * opacity,
                          ),
                          Colors.grey.shade800.withValues(
                            alpha: 0.36 * opacity,
                          ),
                        ],
                      ),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: 0.34 * opacity),
                        width: 1,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
          IconButton(
            tooltip: widget.tooltip,
            style: IconButton.styleFrom(
              fixedSize: const Size.square(48),
              padding: EdgeInsets.zero,
              foregroundColor: Colors.white,
              backgroundColor: Colors.transparent,
              shape: const CircleBorder(),
            ),
            onPressed: widget.onPressed,
            icon: Opacity(
              key: widget.opacityKey,
              opacity: opacity,
              child: widget.icon,
            ),
          ),
        ],
      ),
    );
  }
}

class _GalleryMediaBadge extends StatelessWidget {
  const _GalleryMediaBadge({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final isHdr = text == 'HDR';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
          color: isHdr
              ? Colors.white.withValues(alpha: 0.55)
              : Colors.white.withValues(alpha: 0.25),
          width: isHdr ? 1.0 : 0.6,
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.35),
            blurRadius: 4,
            offset: const Offset(0, 1.5),
          ),
        ],
      ),
      child: Text(
        text,
        style: TextStyle(
          color: Colors.white,
          fontSize: 11,
          fontWeight: isHdr ? FontWeight.w900 : FontWeight.bold,
          letterSpacing: 0.6,
        ),
      ),
    );
  }
}
