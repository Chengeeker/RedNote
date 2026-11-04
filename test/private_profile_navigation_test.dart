import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/core/settings/app_settings.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/main.dart';
import 'package:rednote/ui/app_shell.dart';
import 'package:rednote/ui/profile_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const codec = StandardMethodCodec();
  final calls = <MethodCall>[];
  setUp(() {
    SharedPreferences.setMockInitialValues({'dynamicColor': false});
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'homeCategories' => {'categories': []},
            'home' => {'items': [], 'has_more': false},
            'profile' => {
              'basic_info': {'nickname': '会话用户'},
            },
            'userPosts' => {'notes': [], 'has_more': false},
            _ => <String, dynamic>{},
          };
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, null);
  });

  Future<void> mount(WidgetTester tester) async {
    final app = AppState(await SharedPreferences.getInstance())
      ..loading = false
      ..session = {
        'loggedIn': true,
        'user': {'user_id': 'viewer'},
      };
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appStateProvider.overrideWith((ref) => app)],
        child: RedApp(app: app),
      ),
    );
    await tester.pumpAndSettle();
  }

  Completer<ByteData?> openProfile(WidgetTester tester, Json args) {
    final reply = Completer<ByteData?>();
    tester.binding.channelBuffers.push(
      'com.red.note/navigation',
      codec.encodeMethodCall(MethodCall('openProfile', args)),
      reply.complete,
    );
    return reply;
  }

  testWidgets(
    'private chat profiles preserve references and nested return order',
    (tester) async {
      await mount(tester);
      final first = openProfile(tester, {
        'id': 'peer_a',
        'token': 'a+b/==',
        'source': 'pc_im',
      });
      await tester.pumpAndSettle();
      expect(find.byType(ProfilePage), findsOneWidget);
      expect(calls.lastWhere((call) => call.method == 'profile').arguments, {
        'id': 'peer_a',
        'token': 'a+b/==',
        'source': 'pc_im',
      });
      expect(first.isCompleted, false);
      final second = openProfile(tester, {'id': 'peer_b'});
      await tester.pumpAndSettle();
      expect(find.byType(ProfilePage, skipOffstage: false), findsNWidgets(2));
      expect(calls.lastWhere((call) => call.method == 'profile').arguments, {
        'id': 'peer_b',
        'token': '',
        'source': 'pc_note',
      });
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(codec.decodeEnvelope((await second.future)!), isNull);
      expect(first.isCompleted, false);
      await tester.tap(find.byType(BackButton));
      await tester.pumpAndSettle();
      expect(codec.decodeEnvelope((await first.future)!), isNull);
      expect(find.byType(AppShell), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('invalid profile navigation never requests account data', (
    tester,
  ) async {
    await mount(tester);
    final reply = openProfile(tester, {'id': '../invalid'});
    await tester.pumpAndSettle();
    final envelope = await reply.future;
    expect(
      () => codec.decodeEnvelope(envelope!),
      throwsA(isA<PlatformException>()),
    );
    expect(find.byType(ProfilePage), findsNothing);
    expect(calls.where((call) => call.method == 'profile'), isEmpty);
    expect(tester.takeException(), isNull);
  });
}
