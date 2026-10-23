import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';

import '../../data/media_support.dart';

/// Shares the disk-cache download with native HDR preparation, even if Flutter decoding fails.
class GalleryOriginalProvider extends ImageProvider<GalleryOriginalProvider> {
  const GalleryOriginalProvider(this.url, this.headers);
  final String url;
  final Map<String, String> headers;

  @override
  Future<GalleryOriginalProvider> obtainKey(ImageConfiguration configuration) =>
      SynchronousFuture(this);

  @override
  ImageStreamCompleter loadImage(
    GalleryOriginalProvider key,
    ImageDecoderCallback decode,
  ) => MultiFrameImageStreamCompleter(codec: _decode(decode), scale: 1);

  Future<ui.Codec> _decode(ImageDecoderCallback decode) async {
    final bytes = await GalleryOriginalLoader.load(url, headers);
    unawaited(HdrImageDetector.detectFromCache(url));
    final hdrCandidate =
        HdrImageDetector.isKnownHdr(url) ||
        HdrImageDetector.hasGainMapHeader(
          bytes.sublist(0, math.min(bytes.length, 65536)),
        );
    return decode(
      await ui.ImmutableBuffer.fromUint8List(bytes),
      getTargetSize: hdrCandidate ? galleryHdrPreviewSize : null,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is GalleryOriginalProvider && other.url == url;
  @override
  int get hashCode => Object.hash(GalleryOriginalProvider, url);
}

@visibleForTesting
ui.TargetImageSize galleryHdrPreviewSize(int width, int height) {
  // HDR is drawn by the native layer; its Flutter fallback must not also allocate a 20MP bitmap.
  final scale = math.min(1.0, math.sqrt(2 * 1024 * 1024 / (width * height)));
  return ui.TargetImageSize(
    width: math.max(1, (width * scale).round()),
    height: math.max(1, (height * scale).round()),
  );
}
