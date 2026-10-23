import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:extended_image/extended_image.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/data/media_support.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/ui/note_page.dart';
import 'package:rednote/features/media/gallery_original_provider.dart';
import 'package:rednote/features/media/image_gallery_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.red.note/media');
  setUp(() {
    HdrImageDetector.reset();
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async => false);
  });
  tearDown(() {
    GalleryOriginalLoader.downloadOverride = null;
    HdrImageDetector.reset();
  });

  test('visible image and HDR preparation share a download; failures remain retryable', () async {
    final result = Completer<Uint8List>();
    var downloads = 0;
    GalleryOriginalLoader.downloadOverride = (_, _) {
      downloads++;
      return result.future;
    };
    final first = GalleryOriginalLoader.load('https://cdn.test/shared', {});
    final second = GalleryOriginalLoader.load('https://cdn.test/shared', {});
    expect(identical(first, second), isTrue);
    expect(downloads, 1);
    result.completeError(StateError('network'));
    await expectLater(first, throwsStateError);
    GalleryOriginalLoader.downloadOverride = (_, _) async {
      downloads++;
      return Uint8List.fromList([1]);
    };
    expect(await GalleryOriginalLoader.load('https://cdn.test/shared', {}), [
      1,
    ]);
    expect(downloads, 2);
  });

  test(
    'native HDR preparation does not wait for Flutter codec success',
    () async {
      GalleryOriginalLoader.downloadOverride = (_, _) async =>
          Uint8List.fromList([1, 2, 3]);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (_) async => true);
      const url = 'https://cdn.test/codec-failure';
      final codec = Completer<ui.Codec>();
      const provider = GalleryOriginalProvider(url, {});
      final stream = provider.loadImage(
        provider,
        (_, {getTargetSize}) => codec.future,
      );
      final failed = Completer<void>();
      stream.addListener(
        ImageStreamListener(
          (_, _) {},
          onError: (_, _) {
            failed.complete();
          },
        ),
      );
      await Future<void>.delayed(Duration.zero);
      expect(HdrImageDetector.isKnownHdr(url), isTrue);
      codec.completeError(StateError('Flutter codec'));
      await failed.future;
    },
  );

  test('HDR fallback keeps aspect ratio and bounds allocation; Hero follows one direct path', () {
    final size = galleryHdrPreviewSize(3712, 5568);
    expect(size.width! * size.height!, lessThan(2 * 1024 * 1024 + 2500));
    expect(size.width! / size.height!, closeTo(3712 / 5568, 0.001));
    expect(galleryHdrPreviewSize(100, 200).width, 100);
    const begin = Rect.fromLTWH(15, 700, 190, 250);
    const end = Rect.fromLTWH(0, 80, 400, 500);
    final tween = directImageRectTween(begin, end);
    for (final t in [0.0, 0.25, 0.5, 0.75, 1.0]) {
      expect(tween.transform(t), Rect.lerp(begin, end, t));
    }
  });

  testWidgets(
    'returning second image hands flight pixels to note when preview cannot load',
    (tester) async {
      late ui.Image pixels;
      late ByteData? data;
      await tester.runAsync(() async {
        final recorder = ui.PictureRecorder();
        ui.Canvas(recorder)
            .drawColor(const ui.Color(0xff00ff00), ui.BlendMode.src);
        final picture = recorder.endRecording();
        pixels = await picture.toImage(1, 1);
        data = await pixels.toByteData(format: ui.ImageByteFormat.png);
        picture.dispose();
      });
      GalleryOriginalLoader.downloadOverride = (_, _) async =>
          data!.buffer.asUint8List();
      const urls = ['https://cdn.test/return1', 'https://cdn.test/return2'];
      for (final url in urls) {
        PaintingBinding.instance.imageCache.putIfAbsent(
          GalleryOriginalProvider(url, const {}),
          () => OneFrameImageStreamCompleter(
            Future.value(ImageInfo(image: pixels.clone())),
          ),
        );
      }
      PaintingBinding.instance.imageCache.putIfAbsent(
        ExtendedNetworkImageProvider(urls.first, cache: true),
        () => OneFrameImageStreamCompleter(
          Future.value(ImageInfo(image: pixels.clone())),
        ),
      );
      final note = Note({
        'image_list': [
          for (final url in urls) {'url': url, 'width': 400, 'height': 400},
        ],
      }, const NoteRef('return-note', 'token', 'pc_feed'));
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            RedApi.channel,
            (call) async => call.method == 'detail'
                ? {
                    'items': [
                      {'note_card': note.raw},
                    ],
                  }
                : {},
          );
      final app = AppState(await SharedPreferences.getInstance());
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: NotePage(app: app, initial: note),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byType(PageView));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      await tester.tap(find.byKey(const ValueKey('gallery-thumbnail-1')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      Navigator.of(tester.element(find.byType(ImageGalleryPage))).pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(
        find.byKey(const ValueKey('gallery-image-flight-plane')),
        findsOneWidget,
      );
      await tester.pumpAndSettle();
      expect(find.text('2/2'), findsOneWidget);
      final returned = tester.widget<RawImage>(
        find.byKey(const ValueKey('note-opening-preview')),
      );
      expect(returned.image, isNotNull);
      expect(returned.image!.width, 1);
      await tester.pumpWidget(const SizedBox());
      pixels.dispose();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(RedApi.channel, null);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'second gallery page keeps its own preview while its original is slow',
    (tester) async {
      late Uint8List png;
      await tester.runAsync(() async {
        final recorder = ui.PictureRecorder();
        ui.Canvas(recorder)
            .drawColor(const ui.Color(0xffff0000), ui.BlendMode.src);
        final picture = recorder.endRecording();
        final image = await picture.toImage(1, 1);
        png = (await image.toByteData(format: ui.ImageByteFormat.png))!.buffer
            .asUint8List();
        for (final url in [
          'https://cdn.test/preview1',
          'https://cdn.test/preview2',
        ]) {
          for (final width in <int?>[null, 160]) {
            final provider = ExtendedImage.network(
              url,
              cache: true,
              cacheWidth: width,
            ).image;
            final key = await provider.obtainKey(ImageConfiguration.empty);
            PaintingBinding.instance.imageCache.putIfAbsent(
              key,
              () => OneFrameImageStreamCompleter(
                Future.value(ImageInfo(image: image.clone())),
              ),
            );
          }
        }
        image.dispose();
        picture.dispose();
      });
      final downloads = <String, Completer<Uint8List>>{};
      GalleryOriginalLoader.downloadOverride = (url, _) =>
          downloads.putIfAbsent(url, () => Completer()).future;
      final app = AppState(await SharedPreferences.getInstance());
      await tester.pumpWidget(
        MaterialApp(
          home: ImageGalleryPage(
            app: app,
            pics: const [
              GalleryImage(
                'https://cdn.test/full1',
                thumbnailUrl: 'https://cdn.test/preview1',
              ),
              GalleryImage(
                'https://cdn.test/full2',
                thumbnailUrl: 'https://cdn.test/preview2',
              ),
            ],
            initialIndex: 0,
            statusId: 'slow',
            useLiquidGlass: false,
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 450));
      await tester.drag(
        find.byType(ExtendedImageGesturePageView),
        const Offset(-800, 0),
      );
      final second = find.byKey(const ValueKey('gallery-image-1'));
      for (var frame = 0; frame < 30; frame++) {
        await tester.pump(const Duration(milliseconds: 50));
        final state = tester.state(second) as ExtendedImageState;
        expect(
          state.extendedImageInfo,
          isNotNull,
          reason: 'The second preview must survive the switch to a pending original',
        );
      }
      expect(
        tester.widget<ExtendedImage>(second).image,
        isA<GalleryOriginalProvider>(),
      );
      await tester.pumpWidget(const SizedBox());
      await tester.runAsync(() async {
        for (final entry in downloads.entries) {
          entry.value.complete(png);
        }
        await Future<void>.delayed(const Duration(milliseconds: 100));
      });
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );
}
