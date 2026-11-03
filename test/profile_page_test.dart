import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/core/theme/app_theme.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/ui/profile_page.dart';
import 'package:rednote/ui/feed_page.dart';
import 'package:rednote/core/design_system/components/review_card.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  final profile = <String, dynamic>{
    'basic_info': {
      'nickname': '作者的完整昵称',
      'red_id': '26246404808',
      'ip_location': 'IP属地：河南',
      'desc': '分享求职经验、岗位信息和面试干货\n帮你少走弯路',
    },
    'interactions': [
      {'type': 'follows', 'count': '69'},
      {'type': 'fans', 'count': '202'},
    ],
    'tags': [
      {'name': '浙江杭州'},
    ],
  };
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    calls.clear();
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, null);
  });

  Future<void> mount(
    WidgetTester tester,
    AppState app, {
    bool dark = false,
    double textScale = 1.5,
    Json user = const {'user_id': 'author', 'nickname': '作者'},
  }) async {
    tester.view.physicalSize = const Size(360, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          theme: dark ? AppTheme.darkTheme() : AppTheme.lightTheme(),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: TextScaler.linear(textScale)),
            child: child!,
          ),
          home: ProfilePage(app: app, user: user),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  for (final dark in [false, true]) {
    testWidgets('profile layout and follow retry work in dark=$dark', (
      tester,
    ) async {
      final app = AppState(await SharedPreferences.getInstance())
        ..session = {
          'loggedIn': true,
          'user': {'user_id': 'viewer'},
        };
      var pending = Completer<Json>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(RedApi.channel, (call) async {
            calls.add(call);
            if (call.method == 'profile') return profile;
            if (call.method == 'followUser') return pending.future;
            return <String, dynamic>{};
          });
      await mount(tester, app, dark: dark);
      expect(find.text('作者的完整昵称'), findsOneWidget);
      expect(find.textContaining('小红书号：26246404808'), findsOneWidget);
      expect(find.text('IP：河南'), findsOneWidget);
      expect(find.text('浙江杭州'), findsOneWidget);
      expect(find.byTooltip('复制小红书号'), findsOneWidget);
      final follow = find.byKey(const ValueKey('profile-follow'));
      await tester.ensureVisible(follow);
      expect(tester.getSize(follow).height, greaterThanOrEqualTo(48));
      await tester.tap(follow);
      await tester.pump();
      await tester.tap(follow);
      await tester.pump();
      expect(calls.where((call) => call.method == 'followUser'), hasLength(1));
      pending.completeError(
        PlatformException(code: 'network', message: '关注请求失败'),
      );
      await tester.pumpAndSettle();
      expect(tester.widget<FilledButton>(follow).onPressed, isNotNull);
      pending = Completer<Json>();
      await tester.tap(follow);
      await tester.pump();
      pending.complete({});
      await tester.pumpAndSettle();
      expect(find.text('已关注'), findsOneWidget);
      expect(tester.widget<FilledButton>(follow).onPressed, isNull);
      expect(calls.lastWhere((call) => call.method == 'followUser').arguments, {
        'id': 'author',
      });
      await tester.tap(find.byKey(const ValueKey('profile-message')));
      await tester.pumpAndSettle();
      expect(
        calls.lastWhere((call) => call.method == 'openPrivateChat').arguments,
        {'id': 'author', 'name': '作者的完整昵称'},
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'compact author header aligns identity and keeps copy next to account',
    (tester) async {
      final app = AppState(await SharedPreferences.getInstance())
        ..session = {
          'loggedIn': true,
          'user': {'user_id': 'viewer'},
        };
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            RedApi.channel,
            (call) async => switch (call.method) {
              'profile' => profile,
              'userPosts' => {
                'notes': [
                  {
                    'id': 'profile-post',
                    'xsec_token': 'token',
                    'note_card': {
                      'display_title': '带日期的笔记',
                      'time': 1791504000000,
                    },
                  },
                ],
              },
              _ => {},
            },
          );
      await mount(tester, app, textScale: 1);
      final avatar = tester.getRect(
        find.byKey(const ValueKey('profile-avatar')),
      );
      final name = tester.getRect(find.byKey(const ValueKey('profile-name')));
      final metadata = tester.getRect(
        find.byKey(const ValueKey('profile-metadata')),
      );
      expect(avatar.height, 80);
      expect(name.top, closeTo(avatar.top, 0.1));
      expect(metadata.height, 48);
      expect(metadata.bottom, lessThanOrEqualTo(avatar.bottom));
      expect(tester.widget<NoteTile>(find.byType(NoteTile)).compact, isTrue);
      expect(tester.widget<ReviewCard>(find.byType(ReviewCard)).elevation, 0);
      expect(find.text(notificationTime(1791504000000)), findsOneWidget);
      expect(
        find.ancestor(
          of: find.byIcon(Icons.copy_outlined),
          matching: find.byType(RichText),
        ),
        findsOneWidget,
      );
      final shape =
          tester
                  .widget<FilledButton>(
                    find.byKey(const ValueKey('profile-follow')),
                  )
                  .style!
                  .shape!
                  .resolve({})
              as RoundedRectangleBorder;
      expect(shape.borderRadius, BorderRadius.circular(12));
      expect(tester.takeException(), isNull);
    },
  );

  for (final followed in [false, true]) {
    testWidgets('server followed=$followed overrides stale list relationship', (
      tester,
    ) async {
      final app = AppState(await SharedPreferences.getInstance())
        ..session = {
          'loggedIn': true,
          'user': {'user_id': 'viewer'},
        };
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            RedApi.channel,
            (call) async => call.method == 'profile'
                ? {...profile, 'followed': followed}
                : {},
          );
      await mount(
        tester,
        app,
        user: const {'user_id': 'author', 'fstatus': 'follows'},
      );
      final button = tester.widget<FilledButton>(
        find.byKey(const ValueKey('profile-follow')),
      );
      expect(button.onPressed == null, followed);
    });
  }

  testWidgets('own profile has no follow or private message buttons', (
    tester,
  ) async {
    final app = AppState(await SharedPreferences.getInstance())
      ..session = {
        'loggedIn': true,
        'user': {'user_id': 'author'},
      };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          RedApi.channel,
          (call) async => call.method == 'profile' ? profile : {},
        );
    await mount(tester, app);
    expect(find.byKey(const ValueKey('profile-follow')), findsNothing);
    expect(find.byKey(const ValueKey('profile-message')), findsNothing);
  });

  testWidgets(
    'server following state disables follow and guests retain profile navigation',
    (tester) async {
      final app = AppState(await SharedPreferences.getInstance());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(RedApi.channel, (call) async {
            calls.add(call);
            return call.method == 'profile'
                ? {...profile, 'fstatus': 'both'}
                : {};
          });
      await mount(tester, app);
      expect(find.byType(BackButton), findsOneWidget);
      expect(find.text('互相关注'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('profile-follow')))
            .onPressed,
        isNull,
      );
      expect(calls.where((call) => call.method == 'followUser'), isEmpty);
      expect(tester.takeException(), isNull);
    },
  );
}
