import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/main.dart';
import 'package:rednote/core/settings/app_settings.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/features/media/image_gallery_page.dart'
    show
        GalleryImage,
        ImageGalleryHeroPreviewHost,
        ImageGalleryHeroTag,
        ImageGalleryPage;
import 'package:rednote/ui/comment_editor.dart';
import 'package:rednote/ui/cache_page.dart';
import 'package:rednote/ui/note_page.dart';
import 'package:rednote/ui/notifications_page.dart';
import 'package:rednote/ui/common.dart';
import 'package:rednote/ui/account_page.dart';
import 'package:rednote/ui/media_page.dart';
import 'package:rednote/ui/settings_page.dart';
import 'package:rednote/ui/topic_search_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  dynamic Function(MethodCall)? respond;
  setUp(() {
    calls.clear();
    respond = null;
    SharedPreferences.setMockInitialValues({});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(RedApi.channel, (call) async {
          calls.add(call);
          if (respond != null) return await respond!(call);
          return switch (call.method) {
            'session' => {
              'loggedIn': true,
              'user': {'user_id': 'u', 'nickname': '测试用户'},
            },
            'homeCategories' => {
              'categories': [
                {'id': 'homefeed.food_v3', 'name': '美食'},
              ],
            },
            'selfInfo' => {'user_id': 'u'},
            'profile' => {
              'basic_info': {
                'nickname': '完整资料',
                'red_id': '12345',
                'desc': '真实签名',
              },
              'interactions': [
                {'type': 'follows', 'count': '12'},
                {'type': 'fans', 'count': '9'},
                {'type': 'interaction', 'count': '321'},
              ],
              'tags': [
                {'name': '广东佛山'},
              ],
            },
            'userPosts' ||
            'collections' ||
            'likedNotes' => {'notes': [], 'has_more': false},
            'mentions' || 'likes' => {'message_list': [], 'has_more': false},
            'privateChats' => {
              'page': 0,
              'total': 1,
              'next_ts': 0,
              'chats': [
                {
                  'chat_user_id': 'private-user',
                  'last_msg_content': '消息预览 fixture',
                  'last_msg_time': 0,
                  'info': {'nickname': '私聊用户'},
                },
              ],
            },
            'openPrivateChat' => null,
            'home' => {
              'items': [
                {
                  'id': 'n',
                  'xsec_token': 'secret-reference',
                  'note_card': {
                    'title': '测试笔记',
                    'user': {'nickname': '作者', 'user_id': 'author-id'},
                  },
                },
              ],
              'has_more': false,
            },
            'search' => {
              'items': [
                {
                  'id': 'search-note',
                  'note_card': {
                    'title': '测试笔记',
                    'user': {'nickname': '作者', 'user_id': 'author-id'},
                  },
                },
              ],
              'has_more': false,
            },
            'detail' => {
              'items': [
                {
                  'note_card': {
                    'title': '详情内容',
                    'desc': '真实详情 fixture',
                    'time': DateTime(
                      DateTime.now().year,
                      10,
                      7,
                    ).millisecondsSinceEpoch,
                    'ip_location': '广东',
                    'interact_info': {
                      'liked': false,
                      'liked_count': '1248',
                      'collected': false,
                      'collected_count': '12',
                      'comment_count': '197',
                    },
                  },
                },
              ],
            },
            'comments' => {
              'comments': [
                {
                  'id': 'c',
                  'content': '评论内容',
                  'create_time': DateTime.now()
                      .subtract(const Duration(hours: 2))
                      .millisecondsSinceEpoch,
                  'ip_location': 'IP属地：江苏',
                  'like_count': '83',
                  'sub_comment_count': '30',
                  'sub_comments': [
                    {
                      'id': 'r',
                      'content': '楼中楼内容',
                      'create_time': DateTime.now()
                          .subtract(const Duration(minutes: 50))
                          .millisecondsSinceEpoch,
                      'ip_location': '广东',
                      'like_count': '456',
                      'user_info': {'nickname': '回复者'},
                    },
                  ],
                  'user_info': {'nickname': '读者'},
                },
              ],
              'has_more': false,
            },
            _ => <String, dynamic>{},
          };
        });
  });
  testWidgets('note Hero preview host retains the decoded source image', (
    tester,
  ) async {
    final recorder = ui.PictureRecorder();
    ui.Canvas(recorder).drawColor(const ui.Color(0xFFFF0000), ui.BlendMode.src);
    final image = await recorder.endRecording().toImage(1, 1);
    ui.Image? retained;
    await tester.pumpWidget(
      ImageGalleryHeroPreviewHost(
        onPreviewCaptured: (value) => retained = value,
        child: Builder(
          builder: (context) {
            ImageGalleryHeroPreviewHost.retainPreview(context, image);
            return const SizedBox.shrink();
          },
        ),
      ),
    );
    expect(identical(retained, image), isTrue);
    image.dispose();
  });
  testWidgets('note cover Hero uses the card preview without nesting Heroes', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({'theme': 2});
    final state = AppState(await SharedPreferences.getInstance());
    await state.initialize();
    const cover = 'https://cdn.test/cover-preview.jpg';
    const full = 'https://cdn.test/cover-full.jpg';
    final note = Note({
      'cover': cover,
      'image_list': [
        {'url': full, 'width': 400, 'height': 500},
      ],
      'user': {'user_id': 'author', 'nickname': '作者'},
    }, const NoteRef('n', 'token', 'pc_feed'));
    await tester.pumpWidget(
      MaterialApp(
        home: NotePage(app: state, initial: note),
      ),
    );
    await tester.pumpAndSettle();

    final hero = tester.widgetList<Hero>(find.byType(Hero)).single;
    expect(
      hero.tag,
      ImageGalleryHeroTag.forPic(
        scope: 'n',
        index: 0,
        pic: const GalleryImage(cover),
      ),
    );
    expect(find.byType(ImageGalleryHeroPreviewHost), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  Future<AppState> app() async {
    final state = AppState(await SharedPreferences.getInstance());
    await state.initialize();
    return state;
  }

  Future<void> mount(WidgetTester tester, AppState state) => tester.pumpWidget(
    ProviderScope(
      overrides: [appStateProvider.overrideWith((ref) => state)],
      child: RedApp(app: state),
    ),
  );
  testWidgets('XHS topic spans open matching search results', (tester) async {
    final state = await app();
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: XhsRichText(
              '#教育[话题]#',
              onTopicTap: (topic) => openTopicSearch(context, state, topic),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('#教育#'));
    await tester.pumpAndSettle();
    final search = calls.singleWhere((call) => call.method == 'search');
    expect(search.arguments['keyword'], '教育');
    expect(search.arguments['searchId'], isNotEmpty);
    expect(search.arguments['sort'], 'general');
    expect(find.text('#教育#'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'feed opens detail with its access reference and loads comments',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      SharedPreferences.setMockInitialValues({'theme': 2, 'fontScale': 1.25});
      final state = await app();
      await mount(tester, state);
      await tester.pumpAndSettle();
      final sourceHeroes = tester.widgetList<Hero>(
        find.byType(Hero, skipOffstage: false),
      );
      final sourceCover = sourceHeroes.firstWhere(
        (hero) => hero.tag is ImageGalleryHeroTag,
      );
      final coverTag = sourceCover.tag;
      expect(sourceCover.flightShuttleBuilder, isNotNull);
      const titleTag = 'note-title:n';
      await tester.tap(find.text('测试笔记'));
      await tester.pumpAndSettle();
      final route = ModalRoute.of(tester.element(find.byType(NotePage)))!;
      expect(route.transitionDuration, const Duration(milliseconds: 220));
      expect(
        route.reverseTransitionDuration,
        const Duration(milliseconds: 200),
      );
      expect(route.opaque, isTrue);
      final routeHeroes = tester.widgetList<Hero>(
        find.byType(Hero, skipOffstage: false),
      );
      expect(routeHeroes.any((hero) => hero.tag == coverTag), isTrue);
      expect(routeHeroes.any((hero) => hero.tag == titleTag), isTrue);
      expect(find.text('详情内容'), findsOneWidget);
      expect(find.text('作者'), findsOneWidget);
      expect(find.text('关注'), findsOneWidget);
      expect(find.text('10月7日  广东'), findsOneWidget);
      expect(find.text('评论内容'), findsOneWidget);
      expect(find.text('共 197 条评论'), findsOneWidget);
      expect(find.text('留下你的想法吧'), findsOneWidget);
      expect(find.text('楼中楼内容'), findsOneWidget);
      expect(find.text('展开30条回复'), findsOneWidget);
      expect(find.text('2小时前  江苏'), findsOneWidget);
      expect(find.text('50分钟前  广东'), findsOneWidget);
      expect(find.text('1248'), findsOneWidget);
      expect(find.text('12'), findsOneWidget);
      expect(find.text('456'), findsOneWidget);
      await tester.tap(find.byTooltip('点赞评论'));
      await tester.pumpAndSettle();
      expect(find.text('84'), findsOneWidget);
      final replyLike = find.byTooltip('点赞回复');
      await tester.ensureVisible(replyLike);
      await tester.pumpAndSettle();
      await tester.tap(replyLike);
      await tester.pumpAndSettle();
      expect(
        calls.where(
          (call) =>
              call.method == 'likeComment' &&
              call.arguments['commentId'] == 'r',
        ),
        hasLength(1),
      );
      expect(find.text('457'), findsOneWidget);
      await tester.tap(find.byTooltip('评论'));
      await tester.pumpAndSettle();
      expect(find.text('共 197 条评论').hitTestable(), findsOneWidget);
      expect(calls.firstWhere((c) => c.method == 'detail').arguments, {
        'id': 'n',
        'token': 'secret-reference',
        'source': 'pc_feed',
      });
      await tester.tap(find.byTooltip('点赞'));
      await tester.pumpAndSettle();
      expect(calls.where((c) => c.method == 'likeNote'), hasLength(1));
      expect(
        calls.lastWhere((c) => c.method == 'likeNote').arguments['value'],
        true,
      );
      final inputCapsule = find.byKey(const ValueKey('note-comment-capsule'));
      final actionCapsule = find.byKey(const ValueKey('note-actions-capsule'));
      expect(inputCapsule, findsOneWidget);
      expect(actionCapsule, findsOneWidget);
      expect(
        tester.getRect(actionCapsule).left - tester.getRect(inputCapsule).right,
        closeTo(10, 0.1),
      );
      expect(tester.getSize(inputCapsule).height, greaterThanOrEqualTo(44));
      await state.setSetting('liquidGlass', true);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(
        find.byKey(const ValueKey('note-comment-capsule-glass')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('note-actions-capsule-glass')),
        findsOneWidget,
      );
      await tester.tap(find.byTooltip('分享'));
      await tester.pump();
      expect(calls.where((c) => c.method == 'shareNote'), hasLength(1));
      expect(tester.takeException(), isNull);
      await tester.tap(find.byType(BackButton).first);
      await tester.pumpAndSettle();
      expect(find.text('测试笔记').hitTestable(), findsOneWidget);
    },
  );
  testWidgets('search page keeps history and working note and user tabs', (
    tester,
  ) async {
    final state = await app();
    await mount(tester, state);
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('搜索笔记'));
    await tester.pumpAndSettle();
    expect(find.byTooltip('返回发现'), findsOneWidget);
    expect(find.text('搜索'), findsOneWidget);
    expect(tester.testTextInput.isVisible, isFalse);
    await tester.tap(find.byType(TextField));
    await tester.pump();
    expect(tester.testTextInput.isVisible, isTrue);
    await tester.enterText(find.byType(TextField), '博领家具');
    await tester.tap(find.text('搜索'));
    await tester.pumpAndSettle();
    expect(find.text('全部'), findsOneWidget);
    expect(find.text('用户'), findsOneWidget);
    expect(
      calls.singleWhere((call) => call.method == 'search').arguments['keyword'],
      '博领家具',
    );
    expect(tester.testTextInput.isVisible, isFalse);
    await tester.tap(find.text('测试笔记').first);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(BackButton).first);
    await tester.pumpAndSettle();
    expect(find.text('全部'), findsOneWidget);
    expect(tester.testTextInput.isVisible, isFalse);
    for (final entry in {
      '最多评论': 'comment_descending',
      '最多收藏': 'collect_descending',
    }.entries) {
      await tester.tap(find.byTooltip('排序与类型筛选'));
      await tester.pumpAndSettle();
      await tester.tap(find.text(entry.key));
      await tester.pumpAndSettle();
      final searches = calls.where((call) => call.method == 'search');
      expect(searches.last.arguments['sort'], entry.value);
      expect(searches.last.arguments['page'], 1);
      expect(tester.testTextInput.isVisible, isFalse);
    }
    await tester.tap(find.text('用户'));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'searchUsers'), hasLength(1));
    expect(find.text('没有找到相关用户'), findsOneWidget);
    await tester.tap(find.byTooltip('清除搜索词'));
    await tester.pumpAndSettle();
    expect(find.text('历史记录'), findsOneWidget);
    expect(find.text('博领家具'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('navigation keeps search input and double tap refreshes home', (
    tester,
  ) async {
    final state = await app();
    await mount(tester, state);
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('搜索笔记'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '保留搜索草稿');
    await tester.tap(find.byTooltip('返回发现'));
    await tester.pumpAndSettle();
    final count = calls.where((c) => c.method == 'home').length;
    await tester.tap(find.text('发现').last);
    await tester.pump(const Duration(milliseconds: 50));
    expect(calls.where((c) => c.method == 'home'), hasLength(count));
    await tester.tap(find.text('发现').last);
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'home'), hasLength(count + 1));
    await tester.tap(find.byTooltip('搜索笔记'));
    await tester.pumpAndSettle();
    expect(find.text('保留搜索草稿'), findsOneWidget);
  });
  testWidgets('server categories and supported account and message routes', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final state = await app();
    await state.setSetting('fontScale', 1.25);
    await mount(tester, state);
    await tester.pumpAndSettle();
    expect(
      calls.where((c) => c.method == 'selfInfo' || c.method == 'mentions'),
      isEmpty,
    );
    expect(find.text('关注'), findsNothing);
    expect(find.text('推荐'), findsOneWidget);
    await tester.tap(find.text('美食'));
    await tester.pumpAndSettle();
    expect(
      calls.lastWhere((c) => c.method == 'home').arguments['category'],
      'homefeed.food_v3',
    );
    await tester.tap(find.text('推荐'));
    await tester.pumpAndSettle();
    expect(
      calls.lastWhere((c) => c.method == 'home').arguments['category'],
      'homefeed_recommend',
    );
    await tester.tap(find.text('消息'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(find.text('评论和@'), findsOneWidget);
    expect(find.text('赞和收藏'), findsOneWidget);
    expect(calls.where((c) => c.method == 'privateChats'), hasLength(1));
    expect(find.text('私聊用户'), findsOneWidget);
    expect(find.text('消息预览 fixture'), findsOneWidget);
    expect(calls.where((c) => c.method == 'mentions'), isEmpty);
    await tester.tap(find.text('私聊用户'));
    await tester.pump();
    final chat = calls.singleWhere((call) => call.method == 'openPrivateChat');
    expect(chat.arguments, {'id': 'private-user', 'name': '私聊用户'});
    await tester.tap(find.text('赞和收藏'));
    await tester.pumpAndSettle();
    expect(find.text('暂无消息'), findsOneWidget);
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    await tester.tap(find.text('我的'));
    await tester.pumpAndSettle();
    expect(find.text('完整资料'), findsOneWidget);
    expect(find.text('小红书号：12345'), findsOneWidget);
    expect(find.text('真实签名'), findsOneWidget);
    expect(find.text('广东佛山'), findsOneWidget);
    await tester.tap(find.text('收藏'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'collections'), hasLength(1));
    await tester.tap(find.text('赞过'));
    await tester.pumpAndSettle();
    expect(calls.where((c) => c.method == 'likedNotes'), hasLength(1));
    await tester.tap(find.byTooltip('设置'));
    await tester.pumpAndSettle();
    expect(find.text('个性化'), findsOneWidget);
    expect(find.text('账号与连接'), findsOneWidget);
    expect(find.text('缓存管理'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('manual verification resumes the failed read after return', (
    tester,
  ) async {
    var verified = false;
    var retries = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: LoadMessage(
            title: '私信加载失败',
            error: PlatformException(code: 'verification', message: '安全验证'),
            onVerification: (url) async {
              verified = true;
              return true;
            },
            onRetry: () {
              expect(verified, isTrue);
              retries++;
            },
          ),
        ),
      ),
    );
    await tester.tap(find.text('前往官方安全验证'));
    await tester.pumpAndSettle();
    expect(retries, 1);
  });
  test('manual verification forwards its challenge and keeps the current page epoch', () async {
    final state = await app();
    final before = state.epoch;
    const url = 'https://www.xiaohongshu.com/web-login/captcha?fixture=current';
    respond = (call) => call.method == 'login'
        ? {
            'loggedIn': true,
            'user': {'user_id': 'u'},
          }
        : <String, dynamic>{};
    await state.login(manualVerification: true, verificationUrl: url);
    expect(state.epoch, before);
    expect(
      calls
          .singleWhere((call) => call.method == 'login')
          .arguments['verificationUrl'],
      url,
    );
  });

  testWidgets(
    'missing challenge hides the verification link and permits explicit retry',
    (tester) async {
      var retries = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: LoadMessage(
              title: '评论加载失败',
              error: PlatformException(
                code: 'verification',
                message: '未提供验证入口',
                details: {'verificationUrl': ''},
              ),
              onVerification: (_) async =>
                  throw StateError('Must not open a generic page'),
              onRetry: () => retries++,
            ),
          ),
        ),
      );
      expect(find.text('前往官方安全验证'), findsNothing);
      expect(retries, 0);
      await tester.tap(find.text('重试'));
      await tester.pump();
      expect(retries, 1);
    },
  );

  testWidgets('verification error stays visible without automatic retries', (
    tester,
  ) async {
    final state = await app();
    respond = (call) {
      if (call.method == 'home') {
        throw PlatformException(code: 'verification', message: '需要官方网页验证');
      }
      return {};
    };
    await mount(tester, state);
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('需要官方网页验证'), findsOneWidget);
    await tester.pump(const Duration(seconds: 10));
    expect(calls.where((c) => c.method == 'home'), hasLength(1));
    expect(find.text('暂时没有内容'), findsNothing);
  });
  testWidgets('failed comment preserves draft and sends only once while busy', (
    tester,
  ) async {
    final state = await app();
    final response = Completer<Json>();
    respond = (_) => response.future;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => editComment(
                context,
                state,
                const NoteRef('n', 't', 'pc_feed'),
              ),
              child: const Text('撰写'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('撰写'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '保留这份草稿');
    await tester.tap(find.text('发送'));
    await tester.pump();
    expect(find.text('发送中…'), findsOneWidget);
    response.completeError(PlatformException(code: 'network', message: '网络断开'));
    await tester.pumpAndSettle();
    expect(find.text('保留这份草稿'), findsOneWidget);
    expect(find.text('网络断开'), findsOneWidget);
    expect(calls.where((c) => c.method == 'postComment'), hasLength(1));
  });
  testWidgets(
    'personalization is scrollable at large font and persists preferences',
    (tester) async {
      final state = await app();
      await state.setSetting('fontScale', 1.5);
      await tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: const MediaQueryData(
              size: Size(800, 600),
              textScaler: TextScaler.linear(2),
            ),
            child: SettingsPage(app: state),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('深色模式'));
      await tester.pumpAndSettle();
      expect(state.themeMode, ThemeMode.dark);
      await tester.scrollUntilVisible(find.text('触感与震动反馈').hitTestable(), 200);
      await tester.tap(find.text('触感与震动反馈'));
      await tester.pumpAndSettle();
      expect(state.haptics, false);
      expect(AppState(await SharedPreferences.getInstance()).haptics, false);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'Review settings choices preserve saved values and storage routes',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      SharedPreferences.setMockInitialValues({
        'customWeight': true,
        'weightDelta': -200,
        'fontScale': 1.5,
        'videoPath': 2,
      });
      final state = await app();
      await tester.pumpWidget(MaterialApp(home: SettingsHubPage(app: state)));
      await tester.pumpAndSettle();
      expect(find.text('偏好与功能'), findsOneWidget);
      expect(find.text('存储与备份'), findsOneWidget);
      await tester.tap(find.text('个性化'));
      await tester.pumpAndSettle();
      expect(find.text('明暗模式'), findsOneWidget);
      expect(find.byType(RadioListTile<ThemeMode>), findsNWidgets(3));
      expect(find.byType(ChoiceChip), findsNWidgets(8));
      await tester.tap(find.text('Material You (Monet) 动态取色'));
      await tester.pumpAndSettle();
      expect(state.dynamicColor, true);
      expect(find.byType(ChoiceChip), findsNothing);
      await tester.scrollUntilVisible(find.text('自定义应用字体粗细'), 200);
      await tester.tap(find.text('自定义应用字体粗细'));
      await tester.pumpAndSettle();
      expect(find.text('选择字体粗细'), findsOneWidget);
      expect(state.weightDelta, -200);
      await tester.tap(find.text('偏粗'));
      await tester.pumpAndSettle();
      expect(state.weightDelta, 200);
      expect(state.customWeight, true);
      expect(state.fontScale, 1.5);
      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.tap(find.text('存储设置'));
      await tester.pumpAndSettle();
      expect(find.text('Movies/RedNote/笔记作者昵称/'), findsOneWidget);
      await tester.tap(find.text('图片存储路径'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('按本人昵称前缀归类'));
      await tester.pumpAndSettle();
      expect(state.savePath(false), 1);
      expect(state.savePath(true), 2);
      expect(find.text('Pictures/RedNote/测试用户/'), findsOneWidget);
      expect(
        AppState(await SharedPreferences.getInstance()).savePath(false),
        1,
      );
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('cache errors stay visible and retry restores real usage', (
    tester,
  ) async {
    const media = MethodChannel('com.red.note/media');
    var fail = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(media, (call) async {
          expect(call.method, 'cacheStats');
          if (fail) throw PlatformException(code: 'io', message: '读取缓存失败');
          return {'bytes': 2097152};
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(media, null),
    );
    await tester.pumpWidget(const MaterialApp(home: CachePage()));
    await tester.pumpAndSettle();
    expect(find.text('读取缓存失败'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    fail = false;
    await tester.tap(find.text('刷新缓存占用'));
    await tester.pumpAndSettle();
    expect(find.text('2.0 MB'), findsOneWidget);
    await tester.tap(find.text('清理媒体缓存'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(find.text('2.0 MB'), findsOneWidget);
  });
  testWidgets(
    'note images swipe inline and open the selected full screen image',
    (tester) async {
      final state = await app();
      respond = (call) => call.method == 'detail'
          ? {
              'items': [
                {
                  'note_card': {
                    'title': '多图笔记',
                    'user': {'user_id': 'author', 'nickname': '图集作者'},
                    'image_list': [
                      for (var image = 1; image <= 5; image++)
                        {
                          'url': 'https://cdn.test/$image.jpg',
                          'width': 400,
                          'height': 300,
                        },
                    ],
                  },
                },
              ],
            }
          : {};
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('com.red.note/media'),
            (call) async =>
                call.method == 'isHdrDisplaySupported' ? false : null,
          );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
              const MethodChannel('com.red.note/media'),
              null,
            ),
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appStateProvider.overrideWith((ref) => state)],
          child: MaterialApp(
            home: NotePage(
              app: state,
              initial: Note({}, const NoteRef('n', 't', 'pc_feed')),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('1/5'), findsOneWidget);
      await tester.drag(find.byType(PageView), const Offset(-650, 0));
      await tester.pumpAndSettle();
      expect(find.text('2/5'), findsOneWidget);
      final inlineController = tester
          .widget<PageView>(find.byType(PageView))
          .controller!;
      await tester.tap(find.byType(PageView));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(tester.widget<GalleryPage>(find.byType(GalleryPage)).initial, 1);
      await tester.tap(find.byKey(const ValueKey('gallery-thumbnail-4')));
      await tester.pump(const Duration(milliseconds: 400));
      expect(inlineController.page, 4);
      expect(find.text('5/5', skipOffstage: false), findsOneWidget);
      final inlineHeroes = tester
          .widgetList<Hero>(
            find.descendant(
              of: find.byType(NotePage, skipOffstage: false),
              matching: find.byType(Hero, skipOffstage: false),
            ),
          )
          .where((hero) => hero.tag is ImageGalleryHeroTag);
      expect(
        inlineHeroes.any(
          (hero) => (hero.tag as ImageGalleryHeroTag).index == 4,
        ),
        isTrue,
      );
      Navigator.of(tester.element(find.byType(ImageGalleryPage))).pop();
      await tester.pumpAndSettle();
      expect(find.text('5/5'), findsOneWidget);
      expect(calls.where((c) => c.method == 'detail'), hasLength(1));
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets(
    'new follower page follows once and comment likes toggle on server success',
    (tester) async {
      final state = await app();
      respond = (call) => switch (call.method) {
        'connections' => {
          'message_list': [
            {
              'id': 'f',
              'title': '开始关注你了',
              'user': {
                'userid': 'follower',
                'nickname': '新粉丝',
                'fstatus': 'fans',
              },
            },
          ],
          'has_more': false,
        },
        'mentions' => {
          'message_list': [
            {
              'id': 'm',
              'title': '评论了你的笔记',
              'user_info': {'nickname': '读者'},
              'comment_info': {'id': 'comment', 'content': '真实通知内容'},
              'item_info': {'note_id': 'n', 'xsec_token': 't'},
            },
          ],
          'has_more': false,
        },
        _ => {},
      };
      await tester.pumpWidget(
        MaterialApp(
          home: NotificationDetailPage(app: state, type: 'connections'),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('新粉丝'), findsOneWidget);
      final followCenter = tester.getCenter(find.text('回关'));
      await tester.tap(find.text('回关'));
      await tester.pumpAndSettle();
      expect(find.text('已关注'), findsOneWidget);
      expect(
        tester.getCenter(find.text('已关注')).dx,
        closeTo(followCenter.dx, 0.1),
      );
      expect(
        tester.getCenter(find.text('已关注')).dy,
        closeTo(followCenter.dy, 0.1),
      );
      expect(calls.where((c) => c.method == 'followUser'), hasLength(1));
      await tester.pumpWidget(
        MaterialApp(
          home: NotificationDetailPage(
            key: UniqueKey(),
            app: state,
            type: 'mentions',
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('真实通知内容'), findsOneWidget);
      await tester.tap(find.text('赞'));
      await tester.pumpAndSettle();
      expect(calls.last.arguments['value'], true);
      await tester.tap(find.text('赞'));
      await tester.pumpAndSettle();
      expect(calls.last.arguments['value'], false);
    },
  );
  testWidgets(
    'creator sync overlays only loaded own notes and persists metrics',
    (tester) async {
      final state = await app();
      respond = (call) => switch (call.method) {
        'selfInfo' => {'user_id': 'u'},
        'profile' => {
          'basic_info': {'nickname': '测试用户'},
        },
        'userPosts' => {
          'notes': [
            {'id': 'n', 'title': '自己的笔记', 'xsec_token': 't'},
          ],
          'has_more': false,
        },
        'syncCreatorStats' => {'n': 78, 'other': 999},
        _ => {},
      };
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: AccountPage(app: state)),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byTooltip('同步浏览量'), findsNothing);
      await tester.tap(find.byTooltip('刷新个人资料'));
      await tester.pumpAndSettle();
      expect(
        calls.lastWhere((c) => c.method == 'syncCreatorStats').arguments['ids'],
        ['n'],
      );
      expect(state.preferences.getString('viewCounts:u'), '{"n":"78"}');
      await tester.scrollUntilVisible(
        find.text('78'),
        180,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('78'), findsOneWidget);
      expect(find.text('999'), findsNothing);
    },
  );
  testWidgets('profile refresh syncs own views from other profile tabs', (
    tester,
  ) async {
    final state = await app();
    respond = (call) => switch (call.method) {
      'selfInfo' => {'user_id': 'u'},
      'profile' => {
        'basic_info': {'nickname': '测试用户', 'user_id': 'u'},
      },
      'userPosts' => {
        'notes': [
          {'id': 'own-note', 'title': '自己的笔记', 'xsec_token': 't'},
        ],
        'has_more': false,
      },
      'collections' => {'notes': []},
      'syncCreatorStats' => {'own-note': 42},
      _ => {},
    };
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AccountPage(app: state)),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('收藏'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('刷新个人资料'));
    await tester.pumpAndSettle();
    final ownPostsCall = calls.lastWhere((c) => c.method == 'userPosts');
    expect(ownPostsCall.arguments['id'], 'u');
    expect(
      calls.lastWhere((c) => c.method == 'syncCreatorStats').arguments['ids'],
      ['own-note'],
    );
    expect(state.preferences.getString('viewCounts:u'), '{"own-note":"42"}');
  });
  testWidgets(
    'comment pictures match gallery heroes when opening and closing',
    (tester) async {
      final state = await app();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('com.red.note/media'),
            (call) async =>
                call.method == 'isHdrDisplaySupported' ? false : null,
          );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
              const MethodChannel('com.red.note/media'),
              null,
            ),
      );
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appStateProvider.overrideWith((ref) => state)],
          child: MaterialApp(
            home: Scaffold(
              body: CommentTile(
                app: state,
                reference: const NoteRef('n', 't', 'pc_feed'),
                comment: {
                  'id': 'photo-comment',
                  'content': '带图评论',
                  'pictures': [
                    {
                      'url': 'https://cdn.test/comment.jpg',
                      'width': 400,
                      'height': 300,
                    },
                  ],
                },
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final source = find.byKey(
        const ValueKey('comment-image:photo-comment:0'),
      );
      final sourceHero = tester.widget<Hero>(
        find.descendant(of: source, matching: find.byType(Hero)),
      );
      await tester.tap(source);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(
        find.descendant(
          of: find.byType(GalleryPage),
          matching: find.byWidgetPredicate(
            (w) => w is Hero && w.tag == sourceHero.tag,
          ),
        ),
        findsOneWidget,
      );
      final route = ModalRoute.of(tester.element(find.byType(GalleryPage)))!;
      expect(route.transitionDuration, const Duration(milliseconds: 330));
      expect(
        route.reverseTransitionDuration,
        const Duration(milliseconds: 200),
      );
      tester.state<NavigatorState>(find.byType(Navigator)).pop();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(
        find.byKey(const ValueKey('comment-image:photo-comment:0')),
        findsOneWidget,
      );
      expect(find.byType(GalleryPage), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );
  test('logout clears user state but preserves personalization', () async {
    final state = await app();
    await state.setSetting('pureBlack', true);
    await state.logout();
    expect(state.loggedIn, false);
    expect(state.user, isEmpty);
    expect(state.pureBlack, true);
  });
  testWidgets(
    'portrait search remains usable with large fonts and the keyboard open',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final state = await app();
      await state.setSetting('fontScale', 1.5);
      await mount(tester, state);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('搜索笔记'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(TextField));
      tester.view.viewInsets = const FakeViewPadding(bottom: 320);
      addTearDown(tester.view.resetViewInsets);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );
}
