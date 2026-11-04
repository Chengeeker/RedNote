import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/core/theme/app_theme.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/data/media_support.dart';
import 'package:rednote/ui/common.dart';
import 'package:rednote/ui/notifications_page.dart';
import 'package:rednote/ui/search_users_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  setUp(() {
    calls.clear();
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'mentions' || 'likes' => {
              'message_list': [
                {
                  'id': 'notification',
                  'title': call.method == 'mentions' ? '评论了你的笔记' : '赞了你的评论',
                  'user_info': {'user_id': 'reader', 'nickname': '读者🦀'},
                  'comment_info': {'id': 'comment', 'content': '正文[微笑R][未收录R]'},
                  'item_info': {'note_id': 'note', 'xsec_token': 'token'},
                },
              ],
              'has_more': false,
            },
            'privateChats' => {
              'chats': [
                {
                  'chat_user_id': 'reader',
                  'info': {'nickname': '读者'},
                  'last_msg_content': '消息[微笑R]',
                },
              ],
            },
            'searchUsers' => {
              'users': [
                {'user_id': 'reader', 'nickname': '读者', 'desc': '简介[微笑R]'},
              ],
              'has_more': false,
            },
            _ => <String, dynamic>{},
          };
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, null);
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
  });

  Future<AppState> mount(
    WidgetTester tester,
    Widget Function(AppState) page, {
    double scale = 1,
    double width = 390,
  }) async {
    await tester.pumpWidget(const SizedBox.shrink());
    tester.view.physicalSize = Size(width, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.runAsync(() async {
      final urls = parseXhsEmojiMap(
        await rootBundle.loadString('assets/xhs_emoji.json'),
      );
      final recorder = ui.PictureRecorder();
      ui.Canvas(recorder)
          .drawColor(const ui.Color(0xFF00FF00), ui.BlendMode.src);
      final picture = recorder.endRecording();
      final image = await picture.toImage(2, 2);
      picture.dispose();
      PaintingBinding.instance.imageCache.putIfAbsent(
        NetworkImage(urls['[微笑R]']!, headers: redImageHeaders(urls['[微笑R]']!)),
        () => OneFrameImageStreamCompleter(
          Future.value(ImageInfo(image: image.clone())),
        ),
      );
      image.dispose();
    });
    final app = AppState(await SharedPreferences.getInstance())
      ..session = {
        'loggedIn': true,
        'user': {'user_id': 'viewer'},
      };
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.darkTheme(),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: Scaffold(body: page(app)),
      ),
    );
    await tester.pumpAndSettle();
    await tester.runAsync(() => Future<void>.delayed(Duration.zero));
    await tester.pumpAndSettle();
    return app;
  }

  void expectEmoji(WidgetTester tester) {
    expect(find.byType(RawImage), findsOneWidget);
    expect(
      tester.renderObject<RenderImage>(find.byType(RawImage)).image,
      isNotNull,
    );
    expect(find.textContaining('[微笑R]'), findsNothing);
    expect(tester.takeException(), isNull);
  }

  // The shared emoji Future belongs to one fake-async zone, as in one app run.
  testWidgets(
    'compact notification actions and emoji across message surfaces',
    (tester) async {
      for (final scale in [1.0, 2.0]) {
        await mount(
          tester,
          (app) => NotificationDetailPage(app: app, type: 'mentions'),
          scale: scale,
          width: scale == 1 ? 390 : 320,
        );
        expectEmoji(tester);
        expect(find.textContaining('[未收录R]'), findsOneWidget);
        final reply = find.widgetWithText(FilledButton, '回复');
        final like = find.widgetWithText(FilledButton, '赞');
        for (final button in [reply, like]) {
          final painted = find
              .descendant(of: button, matching: find.byType(Material))
              .first;
          expect(tester.getSize(painted).height, closeTo(32, 0.1));
          expect(tester.getSize(button).height, greaterThanOrEqualTo(48));
          expect(
            tester.getSize(painted).width,
            lessThan(scale == 1 ? 90 : 130),
          );
        }
        await tester.tap(like);
        await tester.pumpAndSettle();
        final request = calls.lastWhere((call) => call.method == 'likeComment');
        expect(request.arguments['commentId'], 'comment');
        expect(request.arguments['value'], true);
        await tester.tap(reply);
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsOneWidget);
        expect(find.text('回复 读者🦀'), findsOneWidget);
        expect(tester.takeException(), isNull);
      }
      await mount(
        tester,
        (app) => NotificationDetailPage(app: app, type: 'likes'),
      );
      expectEmoji(tester);
      expect(find.textContaining('[未收录R]'), findsOneWidget);
      await mount(tester, (app) => NotificationsPage(app: app));
      expectEmoji(tester);
      await mount(
        tester,
        (app) => SearchUsersPage(app: app, keyword: '读者', searchId: 'fixture'),
      );
      expectEmoji(tester);
      final bio = tester.element(
        find.byWidgetPredicate(
          (widget) => widget is XhsRichText && widget.text == '简介[微笑R]',
        ),
      );
      expect(
        tester.getSize(find.byType(RawImage)).height,
        closeTo(DefaultTextStyle.of(bio).style.fontSize! * 1.15, 0.1),
      );
    },
  );
}
