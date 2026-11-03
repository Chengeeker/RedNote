import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import 'feed_page.dart';
import 'profile_header.dart';
import 'common.dart';

class ProfilePage extends StatefulWidget {
  const ProfilePage({
    super.key,
    required this.app,
    required this.user,
    this.token = '',
    this.source = 'pc_note',
  });
  final AppState app;
  final Json user;
  final String token;
  final String source;
  @override
  State<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends State<ProfilePage> {
  Json profile = {};
  Object? failure;
  bool busy = true;
  bool followingBusy = false, followConfirmed = false, chatBusy = false;
  String get userId => textOf(widget.user['user_id'] ?? widget.user['userid']);
  String get relationship => textOf(
    profile['fstatus'] ??
        objectOf(profile['basic_info'] ?? profile['basicInfo'])['fstatus'] ??
        widget.user['fstatus'],
  );
  bool get isFollowing {
    if (followConfirmed) return true;
    final followed = profile['followed'];
    return followed is bool
        ? followed
        : const ['both', 'follows'].contains(relationship);
  }

  bool get isSelf =>
      userId == textOf(widget.app.user['user_id'] ?? widget.app.user['userid']);
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      final data = await widget.app.api.call('profile', {
        'id': userId,
        'token': widget.token,
        'source': widget.source,
      });
      if (mounted) setState(() => profile = data);
    } catch (e) {
      if (mounted) setState(() => failure = e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> follow() async {
    if (followingBusy || isFollowing || isSelf || userId.isEmpty) return;
    if (!widget.app.loggedIn) {
      await signIn(context, widget.app);
      return;
    }
    setState(() => followingBusy = true);
    try {
      await widget.app.api.call('followUser', {'id': userId});
      if (mounted) setState(() => followConfirmed = true);
    } catch (error) {
      if (mounted) showFailure(context, error);
    } finally {
      if (mounted) setState(() => followingBusy = false);
    }
  }

  Future<void> openChat() async {
    if (chatBusy || isSelf || userId.isEmpty) return;
    if (!widget.app.loggedIn) {
      await signIn(context, widget.app);
      return;
    }
    setState(() => chatBusy = true);
    try {
      final basic = objectOf(profile['basic_info'] ?? profile['basicInfo']);
      await widget.app.api.openPrivateChat(
        userId,
        textOf(
          basic['nickname'] ??
              widget.user['nickname'] ??
              widget.user['nick_name'],
        ),
      );
    } catch (error) {
      if (mounted) showFailure(context, error);
    } finally {
      if (mounted) setState(() => chatBusy = false);
    }
  }

  Widget header(BuildContext context) => UserProfileHeader(
    app: widget.app,
    user: widget.user,
    profile: profile,
    busy: busy,
    failure: failure,
    onRetry: load,
    topInset: true,
    showAccountDetails: true,
    leading: const BackButton(),
    actions: [
      PopupMenuButton<String>(
        tooltip: '更多',
        onSelected: (_) => load(),
        itemBuilder: (_) => [
          PopupMenuItem(
            value: 'refresh',
            enabled: !busy,
            child: const Text('刷新资料'),
          ),
        ],
      ),
    ],
    footer: userId.isEmpty || isSelf
        ? null
        : Row(
            children: [
              Expanded(
                child: FilledButton(
                  key: const ValueKey('profile-follow'),
                  onPressed: busy || followingBusy || isFollowing
                      ? null
                      : follow,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(0, 40),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: followingBusy
                      ? const SizedBox.square(
                          dimension: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(
                          isFollowing
                              ? (relationship == 'both' ? '互相关注' : '已关注')
                              : (relationship == 'fans' ? '回关' : '关注'),
                        ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.tonal(
                  key: const ValueKey('profile-message'),
                  onPressed: chatBusy ? null : openChat,
                  style: FilledButton.styleFrom(
                    minimumSize: const Size(0, 40),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    backgroundColor: Theme.of(context)
                        .colorScheme
                        .surfaceContainerHighest,
                    foregroundColor: Theme.of(context).colorScheme.onSurface,
                  ),
                  child: const Text('发私信'),
                ),
              ),
            ],
          ),
  );

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: Theme.of(context).colorScheme.surface,
    body: FeedPage(
      app: widget.app,
      method: 'userPosts',
      source: 'pc_user',
      profileLayout: true,
      header: ColoredBox(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        child: Column(
          children: [
            header(context),
            Material(
              color: Theme.of(context).colorScheme.surface,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(12),
              ),
              child: SizedBox(
                width: double.infinity,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 12,
                  ),
                  child: Text(
                    '笔记',
                    style: Theme.of(context).tabBarTheme.labelStyle,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
      args: {'id': userId, 'token': widget.token, 'source': widget.source},
    ),
  );
}
