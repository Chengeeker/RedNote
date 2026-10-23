import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rednote/data/media_support.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/features/media/image_gallery_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.red.note/media');

  setUp(HdrImageDetector.reset);
  test('auto HDR probes the original and selects it only after real color confirmation', () async {
    final probes = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          probes.add(call);
          return true;
        });
    const sdr = VideoSource('https://cdn.test/sdr', 'h264');
    const original = VideoSource(
      'https://sns-video-bd.xhscdn.com/hdr-fixture',
      'original',
      isOriginal: true,
    );
    final selected = await resolveVideoSources(
      [sdr, original],
      hdrSupported: true,
      referer: 'https://www.xiaohongshu.com/',
    );
    expect(selected.first.url, original.url);
    expect(selected.first.isHdr, isTrue);
    expect(selected.last, same(sdr));
    expect(probes.single.method, 'probeHdrVideo');
    final compatible = await resolveVideoSources(
      [sdr, original],
      hdrSupported: true,
      referer: 'https://www.xiaohongshu.com/',
      preferSdr: true,
    );
    expect(compatible.single, same(sdr));
    expect(probes.length, 1);
  });

  test(
    'unknown original color stays SDR and metadata failures can be retried',
    () async {
      var probes = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (_) async => ++probes == 1 ? null : false,
          );
      const sdr = VideoSource('https://cdn.test/sdr', 'h264');
      const original = VideoSource(
        'https://sns-video-bd.xhscdn.com/unknown-fixture',
        'original',
        isOriginal: true,
      );
      for (var i = 0; i < 2; i++) {
        final sources = await resolveVideoSources(
          [sdr, original],
          hdrSupported: true,
          referer: 'https://www.xiaohongshu.com/',
        );
        expect(sources.single, same(sdr));
      }
      expect(probes, 2);
      await resolveVideoSources(
        [sdr, original],
        hdrSupported: false,
        referer: 'https://www.xiaohongshu.com/',
      );
      expect(probes, 2);
    },
  );
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    HdrImageDetector.reset();
  });

  test('inline preflight reads JPEG metadata segments, not text in pixels or filenames', () {
    final payload = latin1.encode(
      'urn:iso:std:iso:ts:21496:-1\u0000\u0000\u0000\u0000\u0000',
    );
    final length = payload.length + 2;
    final jpeg = [255, 216, 255, 226, length >> 8, length & 255, ...payload];
    expect(HdrImageDetector.hasGainMapHeader(jpeg), isTrue);
    expect(
      HdrImageDetector.hasGainMapHeader(jpeg.sublist(0, jpeg.length - 1)),
      isFalse,
    );
    expect(
      HdrImageDetector.hasGainMapHeader([255, 216, 255, 218, 0, 2, ...payload]),
      isFalse,
    );
    expect(
      HdrImageDetector.hasGainMapHeader([255, 216, 255, 226, 0, 1, ...payload]),
      isFalse,
    );
    expect(HdrImageDetector.hasGainMapHeader(payload), isFalse);
  });

  test('native gainmap confirmation works without URL or metadata hints and shares pending work', () async {
    var calls = 0;
    final result = Completer<bool>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'decodeCachedHdrImage');
          expect(call.arguments, {
            'url': 'https://sns-img.xhscdn.com/photo.jpg',
          });
          calls++;
          return result.future;
        });
    const pic = GalleryImage('https://sns-img.xhscdn.com/photo.jpg');
    final first = HdrImageDetector.detectFromCache(pic.url);
    final second = HdrImageDetector.detectFromCache(pic.url);
    expect(identical(first, second), isTrue);
    await Future<void>.delayed(Duration.zero);
    expect(calls, 1);
    expect(pic.badgeText, isEmpty);
    result.complete(true);
    await Future.wait([first, second]);
    expect(pic.badgeText, 'HDR');
    await HdrImageDetector.detectFromCache(pic.url);
    expect(calls, 1);
  });

  test('HDR hints alone never show a badge and SDR is checked once', () async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async {
          calls++;
          return false;
        });
    const pic = GalleryImage(
      'https://cdn.test/ultrahdr.jpg',
      raw: {'is_hdr': 1},
    );
    expect(pic.badgeText, isEmpty);
    await HdrImageDetector.detectFromCache(pic.url);
    await HdrImageDetector.detectFromCache(pic.url);
    expect(pic.badgeText, isEmpty);
    expect(calls, 1);
  });

  test(
    'a missing native cache is retried instead of permanently marked SDR',
    () async {
      var calls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            channel,
            (_) async => ++calls == 1 ? null : true,
          );
      const url = 'https://cdn.test/photo.jpg';
      await HdrImageDetector.detectFromCache(url);
      expect(HdrImageDetector.isChecked(url), isFalse);
      await HdrImageDetector.detectFromCache(url);
      expect(HdrImageDetector.isKnownHdr(url), isTrue);
    },
  );

  test('original images use only returned safe URLs and preserve gainmap negotiation', () {
    final image = {
      'url_size_original':
          'http://sns-img.xhscdn.com/original.jpg?sign=fixture',
      'url_default': 'https://sns-img.xhscdn.com/preview.webp',
    };
    expect(
      Note.originalImageUrl(image),
      'https://sns-img.xhscdn.com/original.jpg?sign=fixture',
    );
    image['url_size_original'] = 'file:///private/photo.jpg';
    expect(Note.originalImageUrl(image), image['url_default']);
    expect(
      redImageHeaders(image['url_default']!, preserveHdr: true)['Accept'],
      startsWith('image/jpeg'),
    );
  });

  test('only recognized signed XHS web images resolve to the same original object', () {
    const path =
        '/202610221200/0123456789abcdef0123456789abcdef/spectrum/1040_fixture!nd_dft_webp_3';
    expect(
      Note.originalImageUrl({
        'url_default': 'https://sns-webpic-qc.xhscdn.com$path',
      }),
      'https://sns-img-bd.xhscdn.com/spectrum/1040_fixture',
    );
    for (final url in [
      'https://sns-webpic-qc.xhscdn.com.evil.test$path',
      'https://cdn.test$path',
      'https://sns-webpic-qc.xhscdn.com/unknown/path',
    ]) {
      expect(Note.originalImageUrl({'url_default': url}), url);
    }
    expect(
      Note.originalImageUrl({
        'origin_url': 'https://cdn.test/returned.jpg',
        'url_default': 'https://sns-webpic-qc.xhscdn.com$path',
      }),
      'https://cdn.test/returned.jpg',
    );
  });

  test(
    'original video is opt-in and never inferred to be HDR from its key',
    () {
      final sources = videoSources({
        'video': {
          'url': 'https://cdn.test/sdr',
          'consumer': {'origin_video_key': 'spectrum/fixture_original.mkv'},
        },
      });
      expect(sources.last.isOriginal, isTrue);
      expect(sources.last.isHdr, isFalse);
      expect(preferredVideoSources(sources, hdrSupported: true).length, 1);
      expect(
        preferredVideoSources(
          sources,
          hdrSupported: true,
          original: true,
        ).first,
        same(sources.last),
      );
      for (final key in [
        '../private',
        'https://evil.test/a',
        'a?token=x',
        'a/../b',
      ]) {
        expect(
          videoSources({
            'video': {
              'consumer': {'originVideoKey': key},
            },
          }),
          isEmpty,
        );
      }
    },
  );

  test('HDR stream metadata survives CDN and separate-audio alternatives', () {
    final sources = videoSources({
      'video': {
        'media_v2': {
          'audio_stream': {
            'aac': [
              {'master_url': 'https://cdn.test/audio'},
            ],
          },
        },
        'media': {
          'stream': {
            'h264': [
              {'master_url': 'https://cdn.test/sdr', 'hdr_type': 0},
            ],
            'h265': [
              {
                'master_url': 'https://cdn.test/hdr',
                'hdr_type': 1,
                'backup_urls': ['https://cdn.test/hdr-backup'],
              },
            ],
          },
        },
      },
    });
    expect(sources.where((source) => source.isHdr).length, 2);
    expect(
      preferredVideoSources(sources, hdrSupported: true).first.url,
      'https://cdn.test/hdr',
    );
    expect(
      preferredVideoSources(sources, hdrSupported: false).first.url,
      'https://cdn.test/sdr',
    );
    expect(
      sources.every((source) => source.audioUrl == 'https://cdn.test/audio'),
      isTrue,
    );
  });

  test(
    'many HDR variants cannot exhaust all attempts before an SDR fallback',
    () {
      final hdr = List.generate(
        12,
        (i) => VideoSource('https://cdn.test/hdr$i', 'h265', isHdr: true),
      );
      const sdr = VideoSource('https://cdn.test/sdr', 'h264');
      final attempts = preferredVideoSources([...hdr, sdr], hdrSupported: true);
      expect(attempts.length, 8);
      expect(attempts.last, same(sdr));
      expect(hdr.length, 12);
      const original = VideoSource(
        'https://cdn.test/original',
        'original',
        isOriginal: true,
      );
      final originalAttempts = preferredVideoSources(
        [original, ...hdr, sdr],
        hdrSupported: true,
        original: true,
      );
      expect(originalAttempts.length, 8);
      expect(originalAttempts.first, same(original));
      expect(originalAttempts.last, same(sdr));
    },
  );
}
