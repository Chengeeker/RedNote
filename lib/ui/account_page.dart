import 'dart:convert';

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import 'common.dart';
import 'feed_page.dart';
import 'profile_header.dart';
import 'settings_page.dart';

class AccountPage extends StatefulWidget {
  const AccountPage({super.key, required this.app});
  final AppState app;
  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  Json profile = {};
  bool busy = false;
  Object? failure;
  int selected = 0;
  bool syncing = false;
  final feed = GlobalKey<FeedPageState>();
  Json viewCounts = {};
  String get userId =>
      textOf(widget.app.user['user_id'] ?? widget.app.user['userid']);

  @override
  void initState() {
    super.initState();
    if (widget.app.loggedIn) {
      try {
        viewCounts = objectOf(
          jsonDecode(
            widget.app.preferences.getString('viewCounts:$userId') ?? '{}',
          ),
        );
      } on FormatException {
        viewCounts = {};
      }
      load();
    }
  }

  Future<void> syncViews() async {
    if (syncing) return;
    setState(() => syncing = true);
    try {
      final ids =
          feed.currentState?.notes.map((n) => n.reference.id).toSet() ??
          <String>{};
      if (selected != 0 || ids.isEmpty) {
        final basic = objectOf(profile['basic_info'] ?? profile['basicInfo']);
        final id = textOf(
          basic['user_id'] ??
              basic['userId'] ??
              profile['user_id'] ??
              profile['userId'] ??
              widget.app.user['user_id'] ??
              widget.app.user['userid'],
        );
        if (id.isNotEmpty) {
          // shortcut: other tabs fetch one page; add cursor paging to sync every post.
          final posts = await widget.app.api.call('userPosts', {
            'id': id,
            'cursor': '',
            'page': 1,
            'sessionId': '',
          });
          ids.addAll(
            objectsOf(posts['items'] ?? posts['notes'])
                .map((item) => Note.fromItem(item, source: 'pc_user'))
                .whereType<Note>()
                .map((note) => note.reference.id),
          );
        }
      }
      if (ids.isEmpty) {
        if (mounted) {
          ScaffoldMessenger.of(context)
              .showSnackBar(const SnackBar(content: Text('没有可同步浏览量的笔记')));
        }
        return;
      }
      final data = await widget.app.api.call('syncCreatorStats', {
        'ids': ids.toList(),
      });
      if (!mounted) return;
      final received = validatedViewCounts(data, ids);
      if (received.isEmpty) return;
      final next = {...viewCounts, ...received};
      if (!await widget.app.preferences.setString(
        'viewCounts:$userId',
        jsonEncode(next),
      )) {
        throw StateError('浏览量未保存');
      }
      if (mounted) {
        setState(() => viewCounts = next);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已同步 ${received.length} 条笔记的浏览量')),
        );
      }
    } catch (e) {
      if (mounted) showFailure(context, e);
    } finally {
      if (mounted) setState(() => syncing = false);
    }
  }

  Future<void> load() async {
    if (busy) return;
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      final self = await widget.app.api.call('selfInfo');
      final uid = textOf(
        self['user_id'] ??
            self['userId'] ??
            widget.app.user['user_id'] ??
            widget.app.user['userid'],
      );
      if (mounted) setState(() => profile = self);
      if (uid.isEmpty) {
        throw StateError('服务器未返回用户 ID');
      }
      final data = await widget.app.api.call('profile', {'id': uid});
      if (data.isEmpty) {
        throw StateError('服务器未返回个人资料');
      }
      if (mounted) {
        setState(
          () => profile = {
            ...self,
            ...data,
            if (profileBackgroundUrl(data).isEmpty &&
                profileBackgroundUrl(self).isNotEmpty)
              'background_image': profileBackgroundUrl(self),
          },
        );
      }
    } catch (error) {
      if (mounted) setState(() => failure = error);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> refreshProfile() async {
    if (busy || syncing) return;
    await load();
    if (!mounted || failure != null) return;
    await syncViews();
  }

  @override
  Widget build(BuildContext context) {
    final user = widget.app.user;
    final basic = objectOf(profile['basic_info'] ?? profile['basicInfo']);
    final id = textOf(
      basic['user_id'] ?? basic['userId'] ?? user['user_id'] ?? user['userid'],
    );
    final header = Column(
      children: [
        UserProfileHeader(
          app: widget.app,
          user: {...user, ...profile},
          profile: profile,
          topInset: true,
          busy: busy,
          failure: failure,
          onRetry: refreshProfile,
          actions: [
            if (widget.app.loggedIn)
              IconButton(
                tooltip: '刷新个人资料',
                onPressed: busy || syncing ? null : refreshProfile,
                icon: syncing
                    ? const SizedBox.square(
                        dimension: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.refresh),
              ),
            IconButton(
              tooltip: '设置',
              onPressed: () => Navigator.push(
                context,
                MaterialPageRoute<void>(
                  builder: (_) => SettingsHubPage(app: widget.app),
                ),
              ),
              icon: const Icon(Icons.settings_outlined),
            ),
          ],
        ),
        if (widget.app.loggedIn)
          Row(
            children: [
              for (var index = 0; index < 3; index++)
                Expanded(
                  child: InkWell(
                    onTap: () => setState(() => selected = index),
                    child: Container(
                      alignment: Alignment.center,
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      decoration: BoxDecoration(
                        border: Border(
                          bottom: BorderSide(
                            width: 2,
                            color: selected == index
                                ? Theme.of(context).colorScheme.primary
                                : Colors.transparent,
                          ),
                        ),
                      ),
                      child: Text(
                        const ['笔记', '收藏', '赞过'][index],
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(
                              fontWeight: selected == index
                                  ? FontWeight.w700
                                  : FontWeight.normal,
                              color: selected == index
                                  ? Theme.of(context).colorScheme.onSurface
                                  : Theme.of(context)
                                        .colorScheme
                                        .onSurfaceVariant,
                            ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
      ],
    );
    if (!widget.app.loggedIn || id.isEmpty) {
      return ListView(
        padding: const EdgeInsets.only(bottom: 120),
        children: [
          header,
          if (!widget.app.loggedIn)
            LoadMessage(
              title: '登录后查看个人笔记',
              onLogin: () => signIn(context, widget.app),
            ),
          if (widget.app.loggedIn && id.isEmpty && !busy)
            const LoadMessage(title: '账号未返回用户 ID，请重新登录'),
        ],
      );
    }
    return FeedPage(
      key: feed,
      app: widget.app,
      method: const ['userPosts', 'collections', 'likedNotes'][selected],
      source: 'pc_user',
      args: {'id': id},
      viewCounts: selected == 0 ? viewCounts : const {},
      header: header,
      showViewCount: selected == 0,
    );
  }
}
