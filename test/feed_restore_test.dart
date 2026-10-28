import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:extended_image/extended_image.dart'
    show ExtendedNetworkImageProvider;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/ui/feed_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <Map<String, dynamic>>[];
  setUp(() {
    calls.clear();
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, (call) async {
          if (call.method != 'home') return <String, dynamic>{};
          calls.add(Map<String, dynamic>.from(call.arguments as Map));
          return {
            'items': [
              for (var i = 0; i < 30; i++)
                {
                  'id': 'n$i',
                  'note_card': {
                    'title': 'note $i',
                    'cover': {
                      'url_default': 'https://sns-webpic-qc.xhscdn.com/cover$i',
                    },
                  },
                },
            ],
            'cursor': 'next',
            'session_id': 'session',
            'has_more': true,
          };
        });
  });

  testWidgets(
    'restart restores notes and position without fetching; paging and refresh work',
    (tester) async {
      final prefs = await SharedPreferences.getInstance();
      final app = AppState(prefs)
        ..session = {
          'loggedIn': true,
          'user': {'user_id': 'u'},
        };
      var key = GlobalKey<FeedPageState>();
      Widget page() => MaterialApp(
        home: Scaffold(
          body: FeedPage(key: key, app: app),
        ),
      );
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      expect(calls.length, 1);
      key.currentState!.scroll.jumpTo(300);
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      key = GlobalKey<FeedPageState>();
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      expect(calls.length, 1);
      expect(key.currentState!.notes.length, 30);
      expect(key.currentState!.scroll.offset, 300);
      expect(
        key.currentState!.notes.first.coverUrl,
        'https://sns-webpic-qc.xhscdn.com/cover0',
      );
      final cover = tester.widget<Image>(find.byType(Image).first);
      final provider = (cover.image as ResizeImage).imageProvider;
      expect(provider, isA<ExtendedNetworkImageProvider>());
      expect((provider as ExtendedNetworkImageProvider).cache, isTrue);
      await key.currentState!.load();
      await tester.pumpAndSettle();
      expect(calls.last['cursor'], 'next');
      expect(calls.last['page'], 2);
      await key.currentState!.refresh();
      await tester.pumpAndSettle();
      expect(calls.last['cursor'], '');
      expect(calls.last['page'], 1);
    },
  );

  for (final invalid in [false, true]) {
    testWidgets(
      invalid
          ? 'broken cache fetches normally'
          : 'another account does not restore previous account',
      (tester) async {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(
          'homeSnapshot:old',
          invalid
              ? '{broken'
              : jsonEncode({
                  'args': {},
                  'items': [
                    {'id': 'old', 'title': 'old'},
                  ],
                  'page': 2,
                  'more': false,
                }),
        );
        final app = AppState(prefs)
          ..session = {
            'loggedIn': true,
            'user': {'user_id': invalid ? 'old' : 'new'},
          };
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(body: FeedPage(app: app)),
          ),
        );
        await tester.pumpAndSettle();
        expect(calls.length, 1);
        expect(find.text('old'), findsNothing);
      },
    );
  }
}
