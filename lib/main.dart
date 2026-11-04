import 'dart:async';

import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app_state.dart';
import 'core/settings/app_settings.dart';
import 'core/theme/app_theme.dart';
import 'ui/app_shell.dart';
import 'ui/profile_page.dart';
import 'data/red_api.dart';

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

class RedApp extends StatefulWidget {
  const RedApp({super.key, required this.app});
  final AppState app;
  @override
  State<RedApp> createState() => _RedAppState();
}

class _RedAppState extends State<RedApp> {
  static const _navigation = OptionalMethodChannel('com.red.note/navigation');
  final _navigator = GlobalKey<NavigatorState>();
  AppState get app => widget.app;

  @override
  void initState() {
    super.initState();
    _navigation.setMethodCallHandler((call) async {
      if (call.method != 'openProfile') throw MissingPluginException();
      final args = objectOf(call.arguments);
      final id = textOf(args['id']);
      final token = textOf(args['token']);
      final source = textOf(args['source']);
      if (!RegExp(r'^[A-Za-z0-9_-]{1,128}$').hasMatch(id) ||
          token.length > 4096 ||
          (source.isNotEmpty &&
              !RegExp(r'^[A-Za-z0-9_-]{1,128}$').hasMatch(source))) {
        throw PlatformException(code: 'reference', message: '用户主页地址无效');
      }
      final navigator = _navigator.currentState;
      if (!mounted || navigator == null) {
        throw PlatformException(code: 'navigation', message: '页面尚未就绪');
      }
      await navigator.push<void>(
        MaterialPageRoute(
          builder: (_) => ProfilePage(
            app: app,
            user: {'user_id': id},
            token: token,
            source: source.isEmpty ? 'pc_note' : source,
          ),
        ),
      );
      return null;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_navigation.invokeMethod<void>('ready'));
    });
  }

  @override
  void dispose() {
    _navigation.setMethodCallHandler(null);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) {
      Widget material(ColorScheme? light, ColorScheme? dark) => MaterialApp(
        navigatorKey: _navigator,
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
