import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import 'common.dart';
import 'profile_page.dart';

class SearchUsersPage extends StatefulWidget {
  const SearchUsersPage({
    super.key,
    required this.app,
    required this.keyword,
    required this.searchId,
  });
  final AppState app;
  final String keyword;
  final String searchId;
  @override
  State<SearchUsersPage> createState() => _SearchUsersPageState();
}

class _SearchUsersPageState extends State<SearchUsersPage> {
  final users = <Json>[];
  final seen = <String>{};
  int page = 1;
  bool busy = false, more = true;
  Object? failure;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    if (busy || !more) return;
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      final data = await widget.app.api.call('searchUsers', {
        'keyword': widget.keyword,
        'searchId': widget.searchId,
        'page': page,
        'requestId': widget.searchId,
      });
      if (!mounted) return;
      final items = objectsOf(data['users']);
      final before = users.length;
      for (final item in items) {
        final u = objectOf(item['user_info']).isNotEmpty
            ? objectOf(item['user_info'])
            : item;
        final id = textOf(u['user_id'] ?? u['id']);
        if (id.isNotEmpty && seen.add(id)) users.add(u);
      }
      more =
          data['has_more'] != false &&
          items.isNotEmpty &&
          users.length > before;
      page++;
    } catch (e) {
      if (mounted) failure = e;
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.only(bottom: 110),
    children: [
      for (final user in users)
        ListTile(
          leading: UserAvatar(userImageUrl(user), size: 44, api: widget.app),
          title: Text(textOf(user['nickname'] ?? user['name'])),
          subtitle: Text(
            textOf(user['desc'] ?? user['description']),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          onTap: () => Navigator.push(
            context,
            MaterialPageRoute<void>(
              builder: (_) => ProfilePage(
                app: widget.app,
                user: {...user, 'user_id': user['user_id'] ?? user['id']},
                token: textOf(user['xsec_token']),
                source: 'pc_search',
              ),
            ),
          ),
        ),
      if (busy)
        const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: CircularProgressIndicator(),
          ),
        )
      else if (failure != null)
        LoadMessage(
          title: '用户搜索失败',
          error: failure,
          onRetry: load,
          onLogin: () => signIn(context, widget.app),
          onVerification: () =>
              signIn(context, widget.app, manualVerification: true),
        )
      else
        Center(
          child: TextButton(
            onPressed: more ? load : null,
            child: Text(
              more
                  ? '加载更多'
                  : users.isEmpty
                  ? '没有找到相关用户'
                  : '已经到底了',
            ),
          ),
        ),
    ],
  );
}
