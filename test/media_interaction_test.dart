import 'package:extended_image/extended_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/core/settings/app_settings.dart';
import 'package:rednote/core/design_system/components/review_navigation_bar.dart';
import 'package:rednote/features/media/image_gallery_page.dart';
import 'package:rednote/data/media_support.dart';
import 'package:rednote/features/media/inline_hdr_badge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final statusBarVisibility = <bool>[];
  setUp(() {
    HdrImageDetector.reset();
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('com.red.note/media'), (
          call,
        ) async {
          if (call.method == 'setImageGalleryStatusBarVisible') {
            statusBarVisibility.add((call.arguments as Map)['visible'] as bool);
          }
          return call.method == 'isHdrDisplaySupported' ? false : null;
        });
    statusBarVisibility.clear();
  });
  testWidgets(
    'note preview identifies HDR before opening gallery and clears on a different image',
    (tester) async {
      const original = 'https://cdn.test/original.jpg';
      Widget preview(String url) => MaterialApp(
        home: Scaffold(
          body: Stack(
            children: [
              const Positioned(left: 12, bottom: 12, child: Text('1/2')),
              Positioned(
                right: 12,
                bottom: 12,
                child: InlineHdrBadge(url: url, headers: const {}),
              ),
            ],
          ),
        ),
      );
      await tester.pumpWidget(preview(original));
      expect(find.text('HDR'), findsNothing);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('com.red.note/media'),
            (_) async => true,
          );
      await HdrImageDetector.detectFromCache(original);
      await tester.pump();
      expect(find.text('HDR'), findsOneWidget);
      expect(find.byType(ImageGalleryPage), findsNothing);
      expect(tester.getRect(find.text('1/2')).left, 12);
      final right = tester.getRect(find.byType(InlineHdrBadge)).right;
      expect(
        right,
        tester.view.physicalSize.width / tester.view.devicePixelRatio - 12,
      );
      await tester.pumpWidget(preview('https://cdn.test/sdr.jpg'));
      expect(find.text('HDR'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'confirmed HDR badge matches Review lower-right placement and hides with controls',
    (tester) async {
      const url = 'https://cdn.test/photo.jpg';
      HdrImageDetector.markHdr(url);
      final app = AppState(await SharedPreferences.getInstance());
      await tester.pumpWidget(
        MaterialApp(
          home: ImageGalleryPage(
            app: app,
            pics: const [GalleryImage(url)],
            initialIndex: 0,
            statusId: 'hdr-fixture',
            useLiquidGlass: false,
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 400));
      final badge = find.byWidgetPredicate(
        (widget) => widget is Semantics && widget.properties.label == 'HDR 图片',
      );
      expect(badge, findsOneWidget);
      final rect = tester.getRect(badge);
      final size = tester.view.physicalSize / tester.view.devicePixelRatio;
      expect(rect.right, closeTo(size.width - 16, 0.1));
      expect(rect.bottom, closeTo(size.height - 24, 0.1));
      await tester.tapAt(
        tester.getCenter(find.byType(ExtendedImageGesturePageView)),
      );
      await tester.pump(const Duration(milliseconds: 350));
      expect(badge, findsNothing);
      await tester.pumpWidget(const SizedBox());
      HdrImageDetector.reset();
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('gallery thumbnail selection and main swipe stay synchronized', (
    tester,
  ) async {
    final app = AppState(await SharedPreferences.getInstance());
    await tester.pumpWidget(
      MaterialApp(
        home: ImageGalleryPage(
          app: app,
          pics: const [
            GalleryImage('https://cdn.test/one.jpg'),
            GalleryImage('https://cdn.test/two.jpg'),
          ],
          initialIndex: 0,
          statusId: 'fixture',
          useLiquidGlass: false,
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.byKey(const ValueKey('gallery-thumbnail-1')));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('2 / 2'), findsOneWidget);
    await tester.tapAt(
      tester.getCenter(find.byType(ExtendedImageGesturePageView)),
    );
    await tester.pump(const Duration(milliseconds: 350));
    expect(statusBarVisibility, isNot(contains(false)));
    await tester.drag(
      find.byType(ExtendedImageGesturePageView),
      const Offset(600, 0),
    );
    await tester.pump(const Duration(milliseconds: 600));
    await tester.tapAt(
      tester.getCenter(find.byType(ExtendedImageGesturePageView)),
    );
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.text('1 / 2'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    expect(statusBarVisibility, isNot(contains(false)));
    expect(tester.takeException(), isNull);
  });
  for (final glass in [false, true]) {
    testWidgets(
      'thumbnail drag survives HDR badge changes and advances continuously glass=$glass',
      (tester) async {
        final app = AppState(await SharedPreferences.getInstance());
        final pics = List.generate(
          10,
          (i) => GalleryImage('https://cdn.test/image$i.jpg'),
        );
        for (var i = 0; i < pics.length; i += 2) {
          HdrImageDetector.markHdr(pics[i].originalUrl);
        }
        await tester.pumpWidget(
          MaterialApp(
            home: ImageGalleryPage(
              app: app,
              pics: pics,
              initialIndex: 0,
              statusId: 'drag',
              useLiquidGlass: glass,
            ),
          ),
        );
        await tester.pump(const Duration(milliseconds: 450));
        final strip = find.byKey(const ValueKey('gallery-thumbnail-strip'));
        final gesture = await tester.startGesture(tester.getCenter(strip));
        for (var i = 0; i < 12; i++) {
          await gesture.moveBy(const Offset(-30, 0));
          await tester.pump(const Duration(milliseconds: 30));
        }
        await gesture.up();
        await tester.pump(const Duration(milliseconds: 700));
        final counter = tester
            .widgetList<Text>(find.byType(Text))
            .map((text) => text.data ?? '')
            .where((text) => text.endsWith(' / 10'))
            .single;
        expect(int.parse(counter.split(' / ').first), greaterThan(2));
        final forward = int.parse(counter.split(' / ').first);
        await tester.drag(strip, const Offset(240, 0));
        await tester.pump(const Duration(milliseconds: 700));
        int current() => int.parse(
          tester
              .widgetList<Text>(find.byType(Text))
              .map((text) => text.data ?? '')
              .singleWhere((text) => text.endsWith(' / 10'))
              .split(' / ')
              .first,
        );
        expect(current(), lessThan(forward));
        final reverse = current();
        await tester.fling(strip, const Offset(-240, 0), 1600);
        await tester.pump(const Duration(milliseconds: 1000));
        expect(current(), greaterThan(reverse));
        await tester.pumpWidget(const SizedBox());
        expect(tester.takeException(), isNull);
      },
    );
  }
  testWidgets(
    'glass long press and drag back do not refresh; release over another tab selects it',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      SharedPreferences.setMockInitialValues({'liquidGlass': true});
      final app = AppState(await SharedPreferences.getInstance());
      final selections = <int>[];
      var selected = 0;
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appStateProvider.overrideWith((ref) => app)],
          child: MaterialApp(
            home: StatefulBuilder(
              builder: (context, setState) => Scaffold(
                bottomNavigationBar: ReviewNavigationBar(
                  selectedIndex: selected,
                  hasBottomInset: false,
                  onSelected: (index) {
                    selections.add(index);
                    setState(() => selected = index);
                  },
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final rect = tester.getRect(find.byType(GlassTabBar));
      final home = Offset(rect.left + rect.width / 6, rect.center.dy);
      final profile = Offset(rect.right - rect.width / 6, rect.center.dy);
      final hold = await tester.startGesture(home);
      await tester.pump(const Duration(milliseconds: 400));
      await hold.up();
      await tester.pumpAndSettle();
      expect(selections, isEmpty);
      final back = await tester.startGesture(home);
      await tester.pump(const Duration(milliseconds: 300));
      await back.moveTo(profile);
      await tester.pump(const Duration(milliseconds: 200));
      await back.moveTo(home);
      await tester.pump(const Duration(milliseconds: 200));
      await back.up();
      await tester.pumpAndSettle();
      expect(selections, isEmpty);
      final drag = await tester.startGesture(home);
      await tester.pump(const Duration(milliseconds: 300));
      await drag.moveTo(profile);
      await tester.pump(const Duration(milliseconds: 300));
      await drag.up();
      await tester.pumpAndSettle();
      expect(selections, [2]);
      expect(tester.takeException(), isNull);
    },
  );
}
