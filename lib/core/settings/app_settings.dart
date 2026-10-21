import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app_state.dart';

final appStateProvider = ChangeNotifierProvider<AppState>(
  (ref) => throw StateError('AppState must be provided'),
);

class AppSettingsState {
  const AppSettingsState(this.useFloatingNavBar, this.useLiquidGlassNavBar);
  final bool useFloatingNavBar;
  final bool useLiquidGlassNavBar;
}

final appSettingsProvider = Provider<AppSettingsState>((ref) {
  final app = ref.watch(appStateProvider);
  return AppSettingsState(app.floating, app.liquidGlass);
});
