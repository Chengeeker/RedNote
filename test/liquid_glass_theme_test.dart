import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:rednote/app_state.dart';
import 'package:rednote/core/settings/app_settings.dart';
import 'package:rednote/core/theme/app_theme.dart';
import 'package:rednote/core/design_system/components/review_navigation_bar.dart';

void main() {
  for (final brightness in Brightness.values) {
    testWidgets('liquid glass navigation follows $brightness theme', (
      tester,
    ) async {
      SharedPreferences.setMockInitialValues({'liquidGlass': true});
      final app = AppState(await SharedPreferences.getInstance());
      final theme = brightness == Brightness.light
          ? AppTheme.lightTheme()
          : AppTheme.darkTheme();
      final selections = <int>[];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appStateProvider.overrideWith((ref) => app)],
          child: MaterialApp(
            theme: theme,
            home: Scaffold(
              bottomNavigationBar: ReviewNavigationBar(
                selectedIndex: 0,
                hasBottomInset: false,
                onSelected: selections.add,
              ),
            ),
          ),
        ),
      );
      final startup = tester.widget<AnimatedOpacity>(
        find.byKey(const ValueKey('navigation-startup-shell')),
      );
      expect(startup.opacity, 1);
      expect(
        tester.widget<GlassTabBar>(find.byType(GlassTabBar)).backgroundQuality,
        GlassQuality.minimal,
      );
      final shell = startup.child as DecoratedBox;
      expect(
        (shell.decoration as BoxDecoration).color,
        theme.colorScheme.surfaceContainerHigh,
      );
      expect(
        (shell.decoration as BoxDecoration).borderRadius,
        BorderRadius.circular(9999),
      );
      await tester.tap(find.text('消息').first);
      await tester.pump();
      expect(selections, contains(1));
      await tester.pumpAndSettle();

      expect(
        tester
            .widget<AnimatedOpacity>(
              find.byKey(const ValueKey('navigation-startup-shell')),
            )
            .opacity,
        0,
      );
      expect(
        tester.widget<GlassTabBar>(find.byType(GlassTabBar)).backgroundQuality,
        GlassQuality.premium,
      );

      final bar = tester.widget<GlassTabBar>(find.byType(GlassTabBar));
      if (brightness == Brightness.light) {
        expect(bar.settings?.glassColor, const Color(0xF0FFFFFF));
        expect(bar.settings?.rimLight, 1);
        expect(bar.settings?.shadowElevation, 0.55);
        expect(bar.indicatorSettings?.glassColor, const Color(0x18000000));
        expect(bar.indicatorSettings?.bodyMode, GlassBodyMode.clear);
        expect(bar.indicatorSettings?.rimShade, 0.12);
        expect(bar.indicatorSettings?.rimLight, 0.35);
        expect(bar.indicatorColor, const Color(0x26000000));
        expect(bar.selectedIconColor, theme.colorScheme.primary);
        expect(bar.unselectedIconColor, theme.colorScheme.onSurfaceVariant);
      } else {
        expect(bar.settings?.glassColor, const Color(0x66000000));
        expect(bar.settings?.rimLight, 0.12);
        expect(ReviewNavigationBar.liquidGlassSettings.rimLight, 0);
        expect(bar.settings?.shadowElevation, 0);
        expect(bar.indicatorSettings?.glassColor, const Color(0x26FFFFFF));
        expect(bar.indicatorSettings?.bodyMode, GlassBodyMode.clear);
        expect(bar.indicatorSettings?.rimShade, 0);
        expect(bar.indicatorSettings?.rimLight, 0);
        expect(bar.indicatorColor, const Color(0x26FFFFFF));
        expect(bar.selectedIconColor, theme.colorScheme.primary);
        expect(bar.selectedLabelColor, theme.colorScheme.primary);
        expect(bar.unselectedIconColor, Colors.white);
      }
      final shellFinder = find.byKey(
        const ValueKey('navigation-startup-shell'),
      );
      final navigation = find.byType(ReviewNavigationBar);
      void expectNoFixedRim() {
        int decorations(Finder parent) => find
            .descendant(of: parent, matching: find.byType(DecoratedBox))
            .evaluate()
            .length;
        expect(
          decorations(navigation),
          decorations(find.byType(GlassTabBar)) + decorations(shellFinder),
        );
      }

      expectNoFixedRim();
      final hold = await tester.startGesture(
        tester.getCenter(find.text('消息').first),
      );
      await tester.pump(const Duration(milliseconds: 400));
      final pressed = tester.widget<GlassTabBar>(find.byType(GlassTabBar));
      expect(pressed.indicatorPinchStrength, 0.7);
      expect(pressed.pressScale, 1.06);
      expectNoFixedRim();
      await hold.up();
      await tester.pumpAndSettle();
      expectNoFixedRim();
      expect(tester.takeException(), isNull);
    });
  }
}
