import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/core/theme/app_theme.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/ui/settings_page.dart';

void main() {
  final calls = <MethodCall>[];
  var failOpen = false;
  setUp(() {
    calls.clear();
    failOpen = false;
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, (call) async {
          calls.add(call);
          if (failOpen) {
            throw PlatformException(code: 'external_app', message: '没有可用浏览器');
          }
          return null;
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, null);
  });

  for (final config in [
    (dark: false, scale: 1.0, size: const Size(390, 844)),
    (dark: true, scale: 1.0, size: const Size(390, 844)),
    (dark: false, scale: 2.0, size: const Size(320, 640)),
  ]) {
    testWidgets('about identity, project link and closing work at $config', (
      tester,
    ) async {
      tester.view.physicalSize = config.size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final app = AppState(await SharedPreferences.getInstance());
      await tester.pumpWidget(
        MaterialApp(
          theme: config.dark ? AppTheme.darkTheme() : AppTheme.lightTheme(),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(config.scale)),
            child: child!,
          ),
          home: SettingsHubPage(app: app),
        ),
      );
      await tester.scrollUntilVisible(find.text('关于 RedNote'), 200);
      await tester.tap(find.text('关于 RedNote'));
      await tester.pumpAndSettle();
      await tester.runAsync(
        () => precacheImage(
          tester.widget<Image>(find.byType(Image)).image,
          tester.element(find.byType(Image)),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('RedNote'), findsOneWidget);
      expect(find.text('v1.0.6'), findsOneWidget);
      expect(find.textContaining('复用自'), findsNothing);
      expect(find.byType(Divider), findsNothing);
      expect(
        tester.renderObject<RenderImage>(find.byType(RawImage)).image,
        isNotNull,
      );
      if (config.scale == 1) {
        final name = tester.getRect(find.text('RedNote'));
        final version = tester.getRect(
          find
              .ancestor(
                of: find.text('v1.0.6'),
                matching: find.byType(Container),
              )
              .first,
        );
        expect(name.center.dy, closeTo(version.center.dy, 0.5));
        expect(
          name.expandToInclude(version).center.dx,
          closeTo(tester.getCenter(find.byType(Image)).dx, 0.5),
        );
      }
      await tester.ensureVisible(find.text('GitHub 项目地址'));
      await tester.tap(find.text('GitHub 项目地址'));
      await tester.pumpAndSettle();
      expect(calls.single.method, 'openProject');
      expect(find.byType(AlertDialog), findsOneWidget);
      failOpen = true;
      await tester.tap(find.text('github.com/Chengeeker/RedNote'));
      await tester.pumpAndSettle();
      expect(calls, hasLength(2));
      expect(find.text('没有可用浏览器'), findsOneWidget);
      await tester.tap(find.text('我知道了'));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
      expect(tester.takeException(), isNull);
      app.dispose();
    });
  }
}
