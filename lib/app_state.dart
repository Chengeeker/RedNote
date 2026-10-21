import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'core/utils/haptic_feedback_util.dart';
import 'data/red_api.dart';

class AppState extends ChangeNotifier {
  AppState(this.preferences, {this.api = const RedApi()});
  final SharedPreferences preferences;
  final RedApi api;
  Json session = {};
  bool loading = true;
  bool loginBusy = false;
  String? error;
  int epoch = 0;

  bool get loggedIn => session['loggedIn'] == true;
  Json get user => objectOf(session['user']);
  ThemeMode get themeMode =>
      ThemeMode.values[(preferences.getInt('theme') ?? 0).clamp(0, 2)];
  bool get pureBlack => preferences.getBool('pureBlack') ?? false;
  bool get dynamicColor => preferences.getBool('dynamicColor') ?? false;
  bool get floating => preferences.getBool('floating') ?? true;
  bool get liquidGlass => preferences.getBool('liquidGlass') ?? false;
  bool get haptics => preferences.getBool('haptics') ?? true;
  bool get customWeight => preferences.getBool('customWeight') ?? false;
  int get weightDelta =>
      (preferences.getInt('weightDelta') ?? 0).clamp(-300, 300);
  int get effectiveWeight =>
      customWeight ? weightDelta : (session['systemFontWeight'] as int? ?? 0);
  double get fontScale =>
      (preferences.getDouble('fontScale') ?? 1.0).clamp(0.85, 1.5);
  int savePath(bool video) =>
      (preferences.getInt(video ? 'videoPath' : 'imagePath') ?? 0).clamp(0, 2);
  int get colorIndex => (preferences.getInt('color') ?? 0).clamp(0, 7);

  Future<void> initialize() async {
    HapticFeedbackUtil.isEnabled = haptics;
    try {
      session = await api.call('session');
    } catch (e) {
      error = errorText(e);
    }
    loading = false;
    notifyListeners();
  }

  Future<void> login({bool manualVerification = false}) async {
    if (loginBusy) return;
    loginBusy = true;
    error = null;
    notifyListeners();
    try {
      final before = textOf(user['user_id']);
      session = await api.call('login', {
        'manualVerification': manualVerification,
      });
      if (loggedIn &&
          (textOf(user['user_id']) != before || session['blocked'] == null)) {
        epoch++;
      }
    } catch (e) {
      error = errorText(e);
    } finally {
      loginBusy = false;
      notifyListeners();
    }
  }

  Future<void> logout() async {
    await api.call('logout');
    session = {};
    epoch++;
    error = null;
    notifyListeners();
  }

  Future<void> refreshSession() async {
    session = await api.call('session');
    notifyListeners();
  }

  Future<void> setSetting(String key, Object value) async {
    final success = switch (value) {
      bool() => await preferences.setBool(key, value),
      int() => await preferences.setInt(key, value),
      double() => await preferences.setDouble(key, value),
      _ => false,
    };
    if (!success) throw StateError('设置未保存');
    HapticFeedbackUtil.isEnabled = haptics;
    notifyListeners();
  }
}
