import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app_state.dart';
import 'core/settings/app_settings.dart';
import 'core/theme/app_theme.dart';
import 'ui/app_shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final app = AppState(await SharedPreferences.getInstance());
  runApp(
    ProviderScope(
      overrides: [appStateProvider.overrideWith((ref) => app)],
      child: RedApp(app: app),
    ),
  );
  await app.initialize();
}

class RedApp extends StatelessWidget {
  const RedApp({super.key, required this.app});
  final AppState app;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) {
      Widget material(ColorScheme? light, ColorScheme? dark) => MaterialApp(
        title: 'RedNote',
        debugShowCheckedModeBanner: false,
        locale: const Locale('zh', 'CN'),
        supportedLocales: const [Locale('zh', 'CN')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        theme: AppTheme.lightTheme(
          colorIndex: app.colorIndex,
          dynamicColorScheme: light,
          fontWeightAdjustment: app.effectiveWeight,
        ),
        darkTheme: AppTheme.darkTheme(
          colorIndex: app.colorIndex,
          dynamicColorScheme: dark,
          isPureBlack: app.pureBlack,
          fontWeightAdjustment: app.effectiveWeight,
        ),
        themeMode: app.themeMode,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: _AppTextScaler(
              MediaQuery.textScalerOf(context),
              app.fontScale,
            ),
          ),
          child: child!,
        ),
        home: app.loading
            ? const Scaffold(body: Center(child: CircularProgressIndicator()))
            : AppShell(key: ValueKey(app.epoch), app: app),
      );
      return app.dynamicColor
          ? DynamicColorBuilder(builder: material)
          : material(null, null);
    },
  );
}

class _AppTextScaler extends TextScaler {
  const _AppTextScaler(this.system, this.factor);
  final TextScaler system;
  final double factor;
  @override
  double scale(double fontSize) => system.scale(fontSize) * factor;
  @override
  double get textScaleFactor => scale(14) / 14;
}
