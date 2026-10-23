import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/ui/feed_page.dart';
import 'package:rednote/ui/note_page.dart';
import 'package:rednote/ui/discovery_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => SharedPreferences.setMockInitialValues({}));
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, null);
  });
  final note = Note({
    'title': '测试点赞',
    'interact_info': {'liked': false, 'liked_count': '9', 'collect_count': '0'},
  }, const NoteRef('n', 'token', 'pc_feed'));

  test('optimistic counts share by note id, prevent duplicates and roll back on failure', () async {
    final state = AppState(await SharedPreferences.getInstance());
    var result = Completer<Json>();
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, (_) {
          calls++;
          return result.future;
        });
    final first = state.toggleNoteInteraction(note, 'likeNote');
    expect(state.noteInteraction(note)['liked_count'], 10);
    final duplicate = state.toggleNoteInteraction(note, 'likeNote');
    await duplicate;
    result.complete({});
    await first;
    expect(calls, 1);
    final detail = Note({...note.raw}, note.reference);
    expect(state.noteInteraction(detail)['liked'], true);
    result = Completer<Json>();
    final unlike = state.toggleNoteInteraction(detail, 'likeNote');
    expect(state.noteInteraction(note)['liked_count'], 9);
    result.completeError(PlatformException(code: 'network'));
    await expectLater(unlike, throwsA(isA<PlatformException>()));
    expect(state.noteInteraction(note)['liked_count'], 10);
    expect(state.noteInteractionBusy(note, 'likeNote'), false);
    result = Completer<Json>()..complete({});
    await state.toggleNoteInteraction(note, 'collectNote');
    expect(state.noteInteraction(note)['collected_count'], 1);
    await state.toggleNoteInteraction(note, 'collectNote');
    expect(state.noteInteraction(note)['collected_count'], 0);
    await state.logout();
    expect(state.noteInteraction(note)['liked_count'], '9');
  });

  testWidgets(
    'card heart sends a like without opening note and detail counts synchronize back',
    (tester) async {
      final state = AppState(await SharedPreferences.getInstance())
        ..session = {'loggedIn': true};
      final pending = Completer<Json>();
      var likes = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(RedApi.channel, (call) async {
            if (call.method == 'likeNote') {
              likes++;
              return likes == 1 ? pending.future : {};
            }
            if (call.method == 'detail') {
              return {
                'items': [
                  {'note_card': note.raw},
                ],
              };
            }
            return {};
          });
      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            home: Scaffold(
              body: SizedBox(
                width: 220,
                height: 400,
                child: NoteTile(note: note, app: state),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.byKey(const ValueKey('note-like:n')));
      await tester.pump();
      expect(find.text('10'), findsOneWidget);
      expect(find.byType(NotePage), findsNothing);
      await tester.tap(find.byKey(const ValueKey('note-like:n')));
      await tester.pump();
      expect(find.byType(NotePage), findsNothing);
      expect(likes, 1);
      pending.complete({});
      await tester.pumpAndSettle();
      await tester.tap(find.text('测试点赞'));
      await tester.pumpAndSettle();
      expect(find.text('10'), findsOneWidget);
      await tester.tap(find.byTooltip('点赞'));
      await tester.pumpAndSettle();
      expect(find.text('9'), findsOneWidget);
      Navigator.of(tester.element(find.byType(NotePage))).pop();
      await tester.pumpAndSettle();
      expect(find.text('9'), findsOneWidget);
      expect(likes, 2);
    },
  );

  testWidgets(
    'discovery renders category row while content loads and reuses real cached categories',
    (tester) async {
      final state = AppState(await SharedPreferences.getInstance());
      final categories = Completer<Json>();
      final home = Completer<Json>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            RedApi.channel,
            (call) => call.method == 'homeCategories'
                ? categories.future
                : home.future,
          );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: DiscoveryPage(app: state)),
        ),
      );
      expect(find.text('推荐'), findsOneWidget);
      expect(find.text('穿搭'), findsOneWidget);
      expect(find.text('美食'), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(tester.getTopLeft(find.text('推荐')).dx, lessThan(60));
      categories.complete({
        'categories': [
          {'id': 'homefeed.food_v3', 'name': '美食'},
        ],
      });
      home.complete({'items': []});
      await tester.pumpAndSettle();
      expect(
        state.preferences.getString('discoveryCategories'),
        contains('homefeed.food_v3'),
      );
      await tester.pumpWidget(const SizedBox());
      final refresh = Completer<Json>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            RedApi.channel,
            (call) => call.method == 'homeCategories'
                ? refresh.future
                : Future.value({'items': []}),
          );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: DiscoveryPage(app: state)),
        ),
      );
      expect(find.text('美食'), findsOneWidget);
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '美食'))
            .onPressed,
        isNotNull,
      );
      refresh.complete({
        'categories': [
          {'id': 'homefeed.food_v3', 'name': '美食'},
        ],
      });
      await tester.pumpAndSettle();
    },
  );
}
