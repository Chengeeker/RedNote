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

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final statusBarVisibility = <bool>[];
  setUp(() {
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
