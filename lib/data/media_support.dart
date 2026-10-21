import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:extended_image/extended_image.dart';

import '../app_state.dart';
import 'red_api.dart';

String normalizeRedImageUrl(String value) => mediaUrl(value);
Map<String, String> redImageHeaders(String url, {AppState? api}) {
  final host =
      Uri.tryParse(normalizeRedImageUrl(url))?.host.toLowerCase() ?? '';
  final trustedMediaHost = isOfficialRedMediaHost(host);
  final userAgent = textOf(api?.session['mediaUserAgent']);
  return {
    'Referer': api?.session['region'] == 'rednote'
        ? 'https://www.rednote.com/'
        : 'https://www.xiaohongshu.com/',
    'Accept': 'image/avif,image/webp,image/*,*/*;q=0.8',
    if (trustedMediaHost && userAgent.isNotEmpty) 'User-Agent': userAgent,
  };
}

class HdrImageDetector {
  static final Set<String> _knownHdrUrls = <String>{};
  static final Set<String> _checkingHdrUrls = <String>{};
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

  static bool checkBytes(List<int> bytes) {
    if (bytes.length < 16) return false;
    final checkLen = math.min(bytes.length, 131072);
    const patterns = [
      [104, 100, 114, 103, 109], // 'hdrgm'
      [103, 97, 105, 110, 109, 97, 112], // 'gainmap'
      [50, 49, 52, 57, 54, 58, 49], // '21496:1'
      [50, 49, 52, 57, 54, 58, 45, 49], // '21496:-1'
      [
        104,
        100,
        114,
        45,
        103,
        97,
        105,
        110,
        45,
        109,
        97,
        112,
      ], // 'hdr-gain-map'
      [117, 108, 116, 114, 97, 104, 100, 114], // 'ultrahdr'
    ];
    for (var i = 0; i < checkLen; i++) {
      for (final p in patterns) {
        if (i + p.length <= checkLen) {
          var match = true;
          for (var j = 0; j < p.length; j++) {
            var b = bytes[i + j];
            if (b >= 65 && b <= 90) b += 32; // case-insensitive
            if (b != p[j]) {
              match = false;
              break;
            }
          }
          if (match) return true;
        }
      }
    }
    return false;
  }

  static Future<void> detectFromCache(
    String url, {
    Map<String, String>? headers,
  }) async {
    if (url.isEmpty || isKnownHdr(url) || isChecked(url)) return;
    final normalized = normalizeRedImageUrl(url);
    if (normalized.isEmpty || isKnownHdr(normalized) || isChecked(normalized)) {
      return;
    }
    if (!_checkingHdrUrls.add(normalized)) return;
    try {
      Uint8List? data;
      try {
        data = await ExtendedNetworkImageProvider(
          url,
          headers: headers,
          cache: true,
          printError: false,
        ).getNetworkImageData();
      } catch (_) {}
      if (data == null && normalized != url) {
        try {
          data = await ExtendedNetworkImageProvider(
            normalized,
            headers: headers,
            cache: true,
            printError: false,
          ).getNetworkImageData();
        } catch (_) {}
      }
      if (data != null && checkBytes(data)) {
        markHdr(url);
      }
    } catch (_) {
    } finally {
      _checkingHdrUrls.remove(normalized);
      _checkedUrls.add(url);
      _checkedUrls.add(normalized);
    }
  }

  @visibleForTesting
  static void reset() {
    _knownHdrUrls.clear();
    _checkingHdrUrls.clear();
    _checkedUrls.clear();
    notifier.value = 0;
  }
}
