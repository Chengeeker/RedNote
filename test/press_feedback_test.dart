import 'dart:ui' as ui;

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rednote/core/theme/app_theme.dart';
import 'package:rednote/core/utils/haptic_feedback_util.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final brightness in Brightness.values) {
    testWidgets(
      'Material controls paint no tap or long-press overlay in $brightness',
      (tester) async {
        final theme = brightness == Brightness.light
            ? AppTheme.lightTheme()
            : AppTheme.darkTheme();
        const target = ValueKey('press-target');
        final samples = <Widget>[
          InkWell(
            key: target,
            onTap: () {},
            onLongPress: () {},
            child: const Text('目标'),
          ),
          IconButton(
            key: target,
            onPressed: () {},
            icon: const Icon(Icons.add),
          ),
          TextButton(key: target, onPressed: () {}, child: const Text('目标')),
          FilledButton(key: target, onPressed: () {}, child: const Text('目标')),
          OutlinedButton(
            key: target,
            onPressed: () {},
            child: const Text('目标'),
          ),
          TextButton(
            key: target,
            style: AppTheme.withoutPressOverlay(
              TextButton.styleFrom(foregroundColor: Colors.red),
            ),
            onPressed: () {},
            child: const Text('自定义颜色'),
          ),
          FilledButton(
            key: target,
            style: AppTheme.withoutPressOverlay(
              FilledButton.styleFrom(foregroundColor: Colors.red),
            ),
            onPressed: () {},
            child: const Text('自定义颜色'),
          ),
          OutlinedButton(
            key: target,
            style: AppTheme.withoutPressOverlay(
              OutlinedButton.styleFrom(foregroundColor: Colors.red),
            ),
            onPressed: () {},
            child: const Text('自定义颜色'),
          ),
          IconButton(
            key: target,
            style: AppTheme.withoutPressOverlay(
              IconButton.styleFrom(foregroundColor: Colors.red),
            ),
            onPressed: () {},
            icon: const Icon(Icons.add),
          ),
          ListTile(
            key: target,
            onTap: () {},
            onLongPress: () {},
            title: const Text('目标'),
          ),
          ChoiceChip(
            key: target,
            selected: false,
            pressElevation: 0,
            onSelected: (_) {},
            label: const Text('目标'),
          ),
          DefaultTabController(
            length: 2,
            child: const TabBar(
              tabs: [
                Tab(child: Text('目标', key: target)),
                Tab(text: '其他'),
              ],
            ),
          ),
        ];
        for (final sample in samples) {
          final boundary = GlobalKey();
          await tester.pumpWidget(
            MaterialApp(
              theme: theme,
              home: Center(
                child: RepaintBoundary(
                  key: boundary,
                  child: Material(
                    color: theme.colorScheme.surface,
                    child: SizedBox(
                      width: 240,
                      height: 80,
                      child: Center(child: sample),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          Future<List<int>?> pixels() => tester.runAsync(() async {
            final image =
                await (boundary.currentContext!.findRenderObject()
                        as RenderRepaintBoundary)
                    .toImage();
            final data = await image.toByteData(
              format: ui.ImageByteFormat.rawRgba,
            );
            image.dispose();
            return data!.buffer.asUint8List();
          });
          final before = await pixels();
          final gesture = await tester.startGesture(
            tester.getCenter(find.byKey(target)),
          );
          await tester.pump(const Duration(milliseconds: 150));
          expect(
            listEquals(before, await pixels()),
            true,
            reason: '${sample.runtimeType} painted tap feedback',
          );
          await tester.pump(const Duration(milliseconds: 600));
          expect(
            listEquals(before, await pixels()),
            true,
            reason: '${sample.runtimeType} painted long-press feedback',
          );
          await gesture.up();
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
        }
        for (final state in [WidgetState.pressed, WidgetState.dragged]) {
          expect(
            AppTheme.pressOverlayColor.resolve({state}),
            Colors.transparent,
          );
          expect(
            theme.switchTheme.overlayColor?.resolve({state}),
            Colors.transparent,
          );
          expect(
            theme.radioTheme.overlayColor?.resolve({state}),
            Colors.transparent,
          );
          expect(
            theme.navigationBarTheme.overlayColor?.resolve({state}),
            Colors.transparent,
          );
        }
        expect(
          AppTheme.pressOverlayColor.resolve({WidgetState.focused}),
          isNull,
        );
        expect(
          AppTheme.pressOverlayColor.resolve({WidgetState.hovered}),
          isNull,
        );
        final original = TextButton.styleFrom(foregroundColor: Colors.red);
        final adjusted = AppTheme.withoutPressOverlay(original);
        for (final state in [WidgetState.focused, WidgetState.hovered]) {
          expect(
            adjusted.overlayColor?.resolve({state}),
            original.overlayColor?.resolve({state}),
          );
        }
      },
    );

    testWidgets('presses have no ripple and retain haptics in $brightness', (
      tester,
    ) async {
      final haptics = <MethodCall>[];
      HapticFeedbackUtil.isEnabled = true;
      HapticFeedbackUtil.resetForTesting();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, (call) async {
            if (call.method == 'HapticFeedback.vibrate') haptics.add(call);
            return null;
          });
      addTearDown(() {
        HapticFeedbackUtil.isEnabled = true;
        HapticFeedbackUtil.resetForTesting();
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(SystemChannels.platform, null);
      });
      final key = GlobalKey();
      late BuildContext inkContext;
      var taps = 0;
      var holds = 0;
      await tester.pumpWidget(
        MaterialApp(
          theme: brightness == Brightness.light
              ? AppTheme.lightTheme()
              : AppTheme.darkTheme(),
          home: Material(
            child: Builder(
              builder: (context) {
                inkContext = context;
                return Center(
                  child: InkWell(
                    key: key,
                    enableFeedback: false,
                    onTap: () {
                      taps++;
                      HapticFeedbackUtil.light();
                    },
                    onLongPress: () => holds++,
                    child: const SizedBox(width: 100, height: 60),
                  ),
                );
              },
            ),
          ),
        ),
      );
      final feature = Theme.of(inkContext).splashFactory.create(
        controller: Material.of(inkContext),
        referenceBox: tester.renderObject(find.byKey(key)),
        position: Offset.zero,
        color: Colors.red,
        textDirection: TextDirection.ltr,
      );
      expect(feature, isA<NoSplash>());
      Material.of(inkContext).addInkFeature(feature);
      feature.dispose();
      haptics.clear();
      HapticFeedbackUtil.resetForTesting();
      await tester.tap(find.byKey(key));
      await tester.pumpAndSettle();
      expect(taps, 1);
      expect(haptics, hasLength(1));
      HapticFeedbackUtil.resetForTesting();
      await tester.longPress(find.byKey(key));
      await tester.pumpAndSettle();
      expect(holds, 1);
      expect(haptics, hasLength(2));
      HapticFeedbackUtil.isEnabled = false;
      await tester.tap(find.byKey(key));
      await tester.pumpAndSettle();
      expect(taps, 2);
      expect(haptics, hasLength(2));
      expect(tester.takeException(), isNull);
    });
  }
}
