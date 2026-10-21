import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import 'feed_page.dart';
import 'profile_header.dart';

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
        'id': textOf(widget.user['user_id']),
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

  Widget header(BuildContext context) => UserProfileHeader(
    app: widget.app,
    user: widget.user,
    profile: profile,
    busy: busy,
    failure: failure,
    onRetry: load,
  );

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: ReviewFrostedAppBar(
      title: Text(
        textOf(widget.user['nickname']).isEmpty
            ? '作者笔记'
            : textOf(widget.user['nickname']),
      ),
    ),
    body: FeedPage(
      app: widget.app,
      method: 'userPosts',
      source: 'pc_user',
      header: header(context),
      args: {
        'id': textOf(widget.user['user_id']),
        'token': widget.token,
        'source': widget.source,
      },
    ),
  );
}
