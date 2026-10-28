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
  final _noteInteractions = <String, Json>{};
  final _noteMutations = <String>{};

  Json noteInteraction(Note note) => {
    ...objectOf(note.raw['interact_info']),
    ...?_noteInteractions[note.reference.id],
  };

  bool noteInteractionBusy(Note note, String method) =>
      _noteMutations.contains('${note.reference.id}:$method');

  Future<void> toggleNoteInteraction(Note note, String method) async {
    final id = note.reference.id;
    final request = '$id:$method';
    if (!_noteMutations.add(request)) return;
    final key = method == 'likeNote' ? 'liked' : 'collected';
    final countKey = method == 'likeNote' ? 'liked_count' : 'collected_count';
    final previous = _noteInteractions[id]?[key];
    final previousCount = _noteInteractions[id]?[countKey];
    final interaction = noteInteraction(note);
    final value = interaction[key] != true;
    final count = textOf(
      interaction[countKey] ??
          (key == 'collected' ? interaction['collect_count'] : null),
    );
    final exact = int.tryParse(count);
    final changes = _noteInteractions.putIfAbsent(id, () => {});
    changes[key] = value;
    if (exact != null || count.isEmpty) {
      changes[countKey] = ((exact ?? 0) + (value ? 1 : -1)).clamp(
        0,
        0x7fffffff,
      );
    }
    final currentEpoch = epoch;
    notifyListeners();
    try {
      await api.call(method, {...note.reference.args, 'value': value});
    } catch (_) {
      if (currentEpoch == epoch) {
        previous == null ? changes.remove(key) : changes[key] = previous;
        previousCount == null
            ? changes.remove(countKey)
            : changes[countKey] = previousCount;
      }
      rethrow;
    } finally {
      _noteMutations.remove(request);
      notifyListeners();
    }
  }

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

  Future<void> login({
    bool manualVerification = false,
    String? verificationUrl,
  }) async {
    if (loginBusy) return;
    loginBusy = true;
    error = null;
    notifyListeners();
    try {
      final before = textOf(user['user_id']);
      session = await api.call('login', {
        'manualVerification': manualVerification,
        'verificationUrl': ?verificationUrl,
      });
      if (loggedIn &&
          (!manualVerification || textOf(user['user_id']) != before) &&
          (textOf(user['user_id']) != before || session['blocked'] == null)) {
        epoch++;
        _noteInteractions.clear();
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
    _noteInteractions.clear();
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
