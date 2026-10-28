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
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appStateProvider.overrideWith((ref) => app)],
          child: MaterialApp(
            theme: theme,
            home: Scaffold(
              bottomNavigationBar: ReviewNavigationBar(
                selectedIndex: 0,
                hasBottomInset: false,
                onSelected: (_) {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

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
        expect(
          find.byKey(const ValueKey('liquid-glass-light-rim')),
          findsOneWidget,
        );
      } else {
        expect(bar.settings?.glassColor, const Color(0x66000000));
        expect(bar.settings?.rimLight, 0);
        expect(bar.settings?.shadowElevation, 0);
        expect(bar.indicatorSettings?.glassColor, const Color(0x00FFFFFF));
        expect(bar.indicatorSettings?.bodyMode, GlassBodyMode.adaptive);
        expect(bar.indicatorSettings?.rimShade, 0);
        expect(bar.indicatorSettings?.rimLight, 0);
        expect(bar.indicatorColor, const Color(0x66000000));
        expect(bar.selectedIconColor, Colors.white);
        expect(bar.unselectedIconColor, Colors.white);
        expect(
          find.byKey(const ValueKey('liquid-glass-light-rim')),
          findsNothing,
        );
      }
      expect(tester.takeException(), isNull);
    });
  }
}
