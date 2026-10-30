import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/ui/albums_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  late AppState app;
  late Completer<Map<String, dynamic>> save;
  const id = '0123456789abcdef01234567';
  setUp(() async {
    calls.clear();
    SharedPreferences.setMockInitialValues({});
    app = AppState(await SharedPreferences.getInstance())
      ..session = {
        'loggedIn': true,
        'user': {'user_id': 'u'},
      };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'boards' => {
              'boards': [
                {'id': id, 'name': '旅行', 'total': 2, 'privacy': 0},
              ],
            },
            'boardNotes' => {'notes': [], 'has_more': false},
            'boardInfo' => {
              'id': id,
              'name': '旅行',
              'desc': '山与海',
              'privacy': 0,
            },
            'saveBoard' => await save.future,
            _ => <String, dynamic>{},
          };
        });
  });

  testWidgets('failed create keeps draft and never repeats an in-flight save', (
    tester,
  ) async {
    save = Completer();
    await tester.pumpWidget(MaterialApp(home: AlbumsPage(app: app)));
    await tester.pumpAndSettle();
    expect(find.text('旅行'), findsOneWidget);
    await tester.tap(find.byTooltip('新建专辑'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).first, '读书');
    await tester.tap(find.text('保存'));
    await tester.pump();
    await tester.tap(find.text('保存'));
    await tester.pump();
    expect(calls.where((c) => c.method == 'saveBoard'), hasLength(1));
    expect(calls.last.arguments['name'], '读书');
    save.completeError(PlatformException(code: 'network', message: '请求失败'));
    await tester.pumpAndSettle();
    expect(find.text('读书'), findsOneWidget);
    expect(calls.where((c) => c.method == 'saveBoard'), hasLength(1));
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
  });

  testWidgets(
    'opens album contents; deleting needs confirmation and refreshes list',
    (tester) async {
      await tester.pumpWidget(MaterialApp(home: AlbumsPage(app: app)));
      await tester.pumpAndSettle();
      await tester.tap(find.text('旅行'));
      await tester.pumpAndSettle();
      expect(
        calls.singleWhere((c) => c.method == 'boardNotes').arguments['id'],
        id,
      );
      await tester.tap(find.byTooltip('编辑专辑'));
      await tester.pumpAndSettle();
      expect(find.text('山与海'), findsOneWidget);
      await tester.tap(find.text('删除专辑'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'deleteBoard'), isEmpty);
      await tester.tap(find.text('删除'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'deleteBoard'), hasLength(1));
      expect(calls.where((c) => c.method == 'boards'), hasLength(2));
    },
  );
}
