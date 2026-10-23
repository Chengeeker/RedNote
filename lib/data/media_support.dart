import 'dart:convert';
import 'dart:io';

import 'package:extended_image/extended_image.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../app_state.dart';
import 'red_api.dart';

class GalleryOriginalLoader {
  static final _pending = <String, Future<Uint8List>>{};
  @visibleForTesting
  static Future<Uint8List> Function(String, Map<String, String>)?
  downloadOverride;

  static Future<Uint8List> load(String url, Map<String, String> headers) =>
      _pending.putIfAbsent(
        url,
        () =>
            (() async {
              if (downloadOverride != null) {
                return downloadOverride!(url, headers);
              }
              final bytes = await ExtendedNetworkImageProvider(
                url,
                cache: true,
                headers: headers,
                retries: 0,
                printError: false,
                timeLimit: const Duration(seconds: 18),
              ).getNetworkImageData();
              if (bytes == null || bytes.isEmpty) {
                throw StateError('Image unavailable');
              }
              return bytes;
            })().whenComplete(() {
              _pending.remove(url);
            }),
      );
}

final _videoHdrProbes = <String, Future<bool?>>{};

Future<List<VideoSource>> resolveVideoSources(
  List<VideoSource> sources, {
  required bool hdrSupported,
  required String referer,
  bool original = false,
  bool preferSdr = false,
}) async {
  var resolved = sources;
  var hdrOriginal = false;
  if (hdrSupported &&
      !preferSdr &&
      (original ||
          !sources.any((source) => source.isHdr && !source.isOriginal))) {
    for (final source in sources.where((source) => source.isOriginal).take(1)) {
      final future = _videoHdrProbes.putIfAbsent(
        source.url,
        () => const MethodChannel('com.red.note/media').invokeMethod<bool>(
          'probeHdrVideo',
          {'url': source.url, 'referer': referer},
        ),
      );
      bool? hdr;
      try {
        hdr = await future;
      } catch (_) {
        /* Unknown keeps compatibility streams. */
      }
      if (hdr == null) _videoHdrProbes.remove(source.url);
      if (_videoHdrProbes.length > 64) {
        _videoHdrProbes.remove(_videoHdrProbes.keys.first);
      }
      if (hdr == true) {
        hdrOriginal = true;
        resolved = [
          for (final candidate in sources)
            if (identical(candidate, source))
              VideoSource(
                source.url,
                source.codec,
                audioUrl: source.audioUrl,
                isHdr: true,
                isOriginal: true,
              )
            else
              candidate,
        ];
      }
    }
  }
  return preferredVideoSources(
    resolved,
    hdrSupported: hdrSupported && !preferSdr,
    original: original || hdrOriginal,
  );
}

String normalizeRedImageUrl(String value) => mediaUrl(value);
Map<String, String> redImageHeaders(
  String url, {
  AppState? api,
  bool preserveHdr = false,
}) {
  final host =
      Uri.tryParse(normalizeRedImageUrl(url))?.host.toLowerCase() ?? '';
  final trustedMediaHost = isOfficialRedMediaHost(host);
  final userAgent = textOf(api?.session['mediaUserAgent']);
  return {
    'Referer': api?.session['region'] == 'rednote'
        ? 'https://www.rednote.com/'
        : 'https://www.xiaohongshu.com/',
    'Accept': preserveHdr
        ? 'image/jpeg,image/*;q=0.8,*/*;q=0.5'
        : 'image/avif,image/webp,image/*,*/*;q=0.8',
    if (trustedMediaHost && userAgent.isNotEmpty) 'User-Agent': userAgent,
  };
}

class HdrImageDetector {
  static final Set<String> _knownHdrUrls = <String>{};
  static final Map<String, Future<void>> _checkingHdrUrls = {};
  static final Map<String, Future<void>> _preparing = {};
  static final Map<String, bool> _candidates = {};
  static final HttpClient _probeClient = HttpClient()
    ..connectionTimeout = const Duration(seconds: 4)
    ..maxConnectionsPerHost = 2;
  static final Set<String> _checkedUrls = <String>{};
  static final ValueNotifier<int> notifier = ValueNotifier<int>(0);

  static bool isKnownHdr(String url) {
    if (url.isEmpty) return false;
    final normalized = normalizeRedImageUrl(url);
    return _knownHdrUrls.contains(url) || _knownHdrUrls.contains(normalized);
  }

  static bool isChecked(String url) {
    if (url.isEmpty) return true;
    final normalized = normalizeRedImageUrl(url);
    return _checkedUrls.contains(url) || _checkedUrls.contains(normalized);
  }

  static void markHdr(String url) {
    if (url.isEmpty) return;
    final normalized = normalizeRedImageUrl(url);
    final added1 = _knownHdrUrls.add(url);
    final added2 = _knownHdrUrls.add(normalized);
    if (added1 || added2) {
      notifier.value++;
    }
  }

  static Future<void> detectFromCache(String url, {bool warmup = false}) {
    final normalized = normalizeRedImageUrl(url);
    if (normalized.isEmpty ||
        ((isKnownHdr(normalized) || isChecked(normalized)) &&
            !(warmup && isKnownHdr(normalized)))) {
      return Future.value();
    }
    return _checkingHdrUrls.putIfAbsent(
      normalized,
      () => _decodeCached(normalized),
    );
  }

  static Future<void> _decodeCached(String normalized) async {
    try {
      // Decode the existing cache natively: URL hints and XMP text are not HDR proof.
      final hasGainMap = await const MethodChannel('com.red.note/media')
          .invokeMethod<bool>('decodeCachedHdrImage', {'url': normalized});
      if (hasGainMap == true) markHdr(normalized);
      if (hasGainMap != null) _checkedUrls.add(normalized);
    } catch (_) {
      // Preserve the Flutter SDR image when native decoding is unavailable.
    } finally {
      _checkingHdrUrls.remove(normalized);
    }
  }

  /// Only the visible note image is warmed; list scrolling never downloads originals.
  static Future<void> prepareInline(
    String url,
    Map<String, String> headers, {
    required bool Function() isActive,
  }) {
    final normalized = normalizeRedImageUrl(url);
    if (isKnownHdr(normalized) || isChecked(normalized) || !isActive()) {
      return Future.value();
    }
    final uri = Uri.tryParse(normalized);
    if (kIsWeb ||
        uri == null ||
        uri.scheme != 'https' ||
        !isOfficialRedMediaHost(uri.host)) {
      return Future.value();
    }
    if (_preparing.length >= 2 && !_preparing.containsKey(normalized)) {
      return Future.any(_preparing.values)
          .then((_) => prepareInline(normalized, headers, isActive: isActive));
    }
    return _preparing.putIfAbsent(
      normalized,
      () =>
          (() async {
            try {
              var candidate = _candidates[normalized];
              if (candidate == null) {
                candidate = await _probe(uri, headers);
                _candidates[normalized] = candidate;
                if (_candidates.length > 128) {
                  _candidates.remove(_candidates.keys.first);
                }
              }
              if (!candidate || !isActive()) return;
              await GalleryOriginalLoader.load(normalized, headers);
              if (isActive()) await detectFromCache(normalized);
            } catch (_) {
              // Network failure is retryable; the normal preview remains visible.
            }
          })().whenComplete(() {
            _preparing.remove(normalized);
          }),
    );
  }

  static Future<bool> _probe(Uri uri, Map<String, String> headers) async {
    final request = await _probeClient
        .getUrl(uri)
        .timeout(const Duration(seconds: 4));
    request.followRedirects = false;
    headers.forEach(request.headers.set);
    request.headers.set('Range', 'bytes=0-65535');
    request.headers.set('Accept-Encoding', 'identity');
    try {
      final response = await request.close().timeout(
        const Duration(seconds: 4),
      );
      if (response.statusCode != 200 && response.statusCode != 206) {
        return false;
      }
      final total =
          int.tryParse(
            response.headers.value('content-range')?.split('/').last ?? '',
          ) ??
          response.contentLength;
      // Bound automatic original downloads; larger images still work on explicit opening.
      if (total < 1 || total > 16 * 1024 * 1024) return false;
      final bytes = <int>[];
      await for (final chunk in response.timeout(const Duration(seconds: 4))) {
        bytes.addAll(chunk.take(65536 - bytes.length));
        if (bytes.length >= 65536) break;
      }
      return hasGainMapHeader(bytes);
    } finally {
      // Also closes connections when a server ignores the Range request.
      request.abort();
    }
  }

  static bool hasGainMapHeader(List<int> bytes) {
    if (bytes.length < 4 || bytes[0] != 255 || bytes[1] != 216) return false;
    for (var p = 2; p + 4 <= bytes.length;) {
      if (bytes[p] != 255) return false;
      final marker = bytes[p + 1];
      if (marker == 218 || marker == 217) return false;
      final length = bytes[p + 2] * 256 + bytes[p + 3];
      if (length < 2 || p + 2 + length > bytes.length) return false;
      if (marker == 225 || marker == 226) {
        final metadata = latin1.decode(bytes.sublist(p + 4, p + 2 + length));
        if ((marker == 226 &&
                metadata.startsWith('urn:iso:std:iso:ts:21496:-1\u0000')) ||
            (marker == 225 &&
                metadata.startsWith('http://ns.adobe.com/xap/1.0/\u0000') &&
                metadata.contains('http://ns.adobe.com/hdr-gain-map/1.0/'))) {
          return true;
        }
      }
      p += 2 + length;
    }
    return false;
  }

  @visibleForTesting
  static void reset() {
    _knownHdrUrls.clear();
    _checkingHdrUrls.clear();
    _preparing.clear();
    _candidates.clear();
    _checkedUrls.clear();
    notifier.value = 0;
  }
}
