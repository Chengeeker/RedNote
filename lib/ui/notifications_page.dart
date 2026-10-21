import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import 'common.dart';
import 'note_page.dart';
import 'comment_editor.dart';

class NotificationsPage extends StatefulWidget {
  const NotificationsPage({super.key, required this.app});
  final AppState app;
  @override
  State<NotificationsPage> createState() => _NotificationsPageState();
}

class _NotificationsPageState extends State<NotificationsPage> {
  Json counts = {};
  @override
  void initState() {
    super.initState();
    if (widget.app.loggedIn) refreshCounts();
  }

  Future<void> refreshCounts() async {
    try {
      final data = await widget.app.api.call('unread');
      if (mounted) setState(() => counts = data);
    } catch (_) {
      // Counts are optional; detail pages show their own request errors.
    }
  }

  Future<void> open(String type) async {
    await Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => NotificationDetailPage(app: widget.app, type: type),
      ),
    );
    if (mounted) refreshCounts();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: const ReviewFrostedAppBar(
      title: Text('消息'),
      centerTitle: true,
      showBottomBorder: false,
    ),
    body: !widget.app.loggedIn
        ? LoadMessage(
            title: '登录后查看消息',
            onLogin: () => signIn(context, widget.app),
          )
        : Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 18, 16, 16),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    shortcut(
                      'likes',
                      '赞和收藏',
                      Icons.favorite,
                      const Color(0xFFFF6672),
                    ),
                    shortcut(
                      'connections',
                      '新增关注',
                      Icons.person,
                      const Color(0xFF5595FF),
                    ),
                    shortcut(
                      'mentions',
                      '评论和@',
                      Icons.chat_bubble,
                      const Color(0xFF42CE9F),
                    ),
                  ],
                ),
              ),
              Expanded(child: _PrivateChatsSection(app: widget.app)),
            ],
          ),
  );
  Widget shortcut(String type, String label, IconData icon, Color color) {
    final count = num.tryParse(textOf(counts[type])) ?? 0;
    return Expanded(
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => open(type),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            children: [
              Badge(
                isLabelVisible: count > 0,
                label: Text('$count'),
                child: Container(
                  width: 52,
                  height: 52,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(17),
                  ),
                  child: Icon(icon, color: color, size: 28),
                ),
              ),
              const SizedBox(height: 14),
              Text(
                label,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PrivateChatsSection extends StatefulWidget {
  const _PrivateChatsSection({required this.app});
  final AppState app;

  @override
  State<_PrivateChatsSection> createState() => _PrivateChatsSectionState();
}

class _PrivateChatsSectionState extends State<_PrivateChatsSection> {
  final chats = <PrivateChatPreview>[];
  final seen = <String>{};
  int page = 0;
  bool busy = false, more = true;
  Object? failure;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load({bool next = false}) async {
    if (busy || (next && !more)) return;
    final requestedPage = next ? page + 1 : 0;
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      final data = await widget.app.api.call('privateChats', {
        'page': requestedPage,
      });
      if (!mounted) return;
      final items = objectsOf(data['chats'])
          .map(PrivateChatPreview.fromJson)
          .toList();
      var added = 0;
      for (final chat in items) {
        if (chat.id.isEmpty || seen.add(chat.id)) {
          chats.add(chat);
          added++;
        }
      }
      page = requestedPage;
      more = items.length >= 100 && added > 0;
    } catch (e) {
      if (mounted) failure = e;
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> refresh() async {
    if (busy) return;
    chats.clear();
    seen.clear();
    page = 0;
    more = true;
    await load();
  }

  @override
  Widget build(BuildContext context) {
    if (chats.isEmpty && busy) {
      return const Center(child: CircularProgressIndicator());
    }
    if (chats.isEmpty && failure != null) {
      return LoadMessage(
        title: '私信加载失败',
        error: failure,
        onRetry: load,
        onLogin: () => signIn(context, widget.app),
        onVerification: () =>
            signIn(context, widget.app, manualVerification: true),
      );
    }
    if (chats.isEmpty) {
      return const Center(child: Text('暂无私信'));
    }
    return RefreshIndicator(
      onRefresh: refresh,
      child: ListView.builder(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        itemCount: chats.length + 1,
        itemBuilder: (context, index) {
          if (index < chats.length) {
            final chat = chats[index];
            return ListTile(
              onTap: () async {
                try {
                  await widget.app.api.openPrivateChat(chat.id, chat.name);
                } catch (error) {
                  if (context.mounted) showFailure(context, error);
                }
              },
              contentPadding: const EdgeInsets.symmetric(vertical: 7),
              leading: UserAvatar(chat.avatar, size: 52, api: widget.app),
              title: Text(
                chat.name.isEmpty ? '小红书用户' : chat.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text(
                chat.preview.isEmpty ? '暂无新消息' : chat.preview,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              trailing: chat.time.isEmpty
                  ? null
                  : Text(
                      chat.time,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
            );
          }
          if (busy) {
            return const Padding(
              padding: EdgeInsets.all(20),
              child: Center(child: CircularProgressIndicator()),
            );
          }
          if (failure != null) {
            return SizedBox(
              height: 220,
              child: LoadMessage(
                title: '后续私信加载失败',
                error: failure,
                onRetry: () => load(next: true),
                onLogin: () => signIn(context, widget.app),
                onVerification: () =>
                    signIn(context, widget.app, manualVerification: true),
              ),
            );
          }
          return Center(
            child: TextButton(
              onPressed: more ? () => load(next: true) : null,
              child: Text(more ? '加载更多' : '已经到底了'),
            ),
          );
        },
      ),
    );
  }
}

class NotificationDetailPage extends StatefulWidget {
  const NotificationDetailPage({
    super.key,
    required this.app,
    required this.type,
  });
  final AppState app;
  final String type;
  @override
  State<NotificationDetailPage> createState() => _NotificationDetailPageState();
}

class _NotificationDetailPageState extends State<NotificationDetailPage> {
  final list = GlobalKey<_NotificationListState>();
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: ReviewFrostedAppBar(
      leading: const BackButton(),
      showBottomBorder: false,
      centerTitle: true,
      title: Text(
        const {
          'likes': '收到的赞和收藏',
          'mentions': '收到的评论和@',
          'connections': '新增关注',
        }[widget.type]!,
      ),
      actions: [
        if (widget.type != 'connections')
          IconButton(
            tooltip: '标为已读',
            onPressed: () => list.currentState?.read(),
            icon: const Icon(Icons.done_all, size: 20),
          ),
      ],
    ),
    body: _NotificationList(key: list, app: widget.app, type: widget.type),
  );
}

class _NotificationList extends StatefulWidget {
  const _NotificationList({super.key, required this.app, required this.type});
  final AppState app;
  final String type;
  @override
  State<_NotificationList> createState() => _NotificationListState();
}

class _NotificationListState extends State<_NotificationList>
    with AutomaticKeepAliveClientMixin {
  final messages = <Json>[];
  final seen = <String>{};
  String cursor = '';
  bool busy = false, more = true;
  Object? failure;
  @override
  bool get wantKeepAlive => true;
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
      final data = await widget.app.api.call(widget.type, {'cursor': cursor});
      if (!mounted) return;
      final items = objectsOf(data['message_list']);
      final before = messages.length;
      for (final m in items) {
        final id = textOf(m['id']);
        if (id.isEmpty || seen.add(id)) messages.add(m);
      }
      final next = textOf(data['strCursor'] ?? data['cursor']);
      more =
          items.isNotEmpty &&
          messages.length > before &&
          next.isNotEmpty &&
          next != cursor &&
          data['has_more'] != false;
      cursor = next;
    } catch (e) {
      if (mounted) failure = e;
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> read() async {
    try {
      await widget.app.api.call('readMessages', {'type': widget.type});
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('已标记为已读')));
      }
    } catch (e) {
      if (mounted) showFailure(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
      children: [
        for (final message in messages) _message(message),
        if (busy)
          const Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: CircularProgressIndicator(),
            ),
          )
        else if (failure != null)
          LoadMessage(
            title: '消息加载失败',
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
                    : messages.isEmpty
                    ? '暂无消息'
                    : '已经到底了',
              ),
            ),
          ),
      ],
    );
  }

  Widget _message(Json message) {
    final user = objectOf(message['user_info']);
    var item = objectOf(message['item_info']);
    if (item['type'] == 'board_info') item = objectOf(item['attach_item_info']);
    final comment = objectOf(message['comment_info']);
    final link = Uri.tryParse(textOf(item['link']));
    final token = textOf(
      item['xsec_token'] ?? link?.queryParameters['xsec_token'],
    );
    final id = textOf(
      item['note_id'] ??
          item['id'] ??
          (link?.pathSegments.isNotEmpty == true
              ? link!.pathSegments.last
              : null),
    );
    final note = Note(
      {...item, 'user': user},
      NoteRef(
        id,
        token,
        textOf(link?.queryParameters['xsec_source']).isEmpty
            ? 'pc_notice'
            : textOf(link?.queryParameters['xsec_source']),
      ),
    );
    final actor = user.isNotEmpty ? user : objectOf(message['user']);
    final actorId = textOf(actor['user_id'] ?? actor['userid']);
    final commentId = textOf(comment['id'] ?? comment['comment_id']);
    final validNote = id.isNotEmpty && token.isNotEmpty;
    final isConnection = widget.type == 'connections';
    final title = textOf(message['title']);
    final content = textOf(comment['content']);
    final date = notificationTime(message['time'] ?? message['create_time']);
    final thumb = userImageUrl({
      'images':
          item['image'] ??
          item['cover'] ??
          item['image_info'] ??
          item['image_url'],
    });
    final following =
        followed.contains(actorId) ||
        const ['both', 'follows'].contains(actor['fstatus']);
    final secondary = Theme.of(context).colorScheme.onSurfaceVariant;
    void openNote() => Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => NotePage(app: widget.app, initial: note),
      ),
    );
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 22),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          UserAvatar(userImageUrl(actor), size: 44, api: widget.app),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  textOf(actor['nickname']),
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  [
                    title.isNotEmpty
                        ? title
                        : isConnection
                        ? '开始关注你了'
                        : '',
                    date,
                  ].where((s) => s.isNotEmpty).join('  '),
                  style: TextStyle(fontSize: 13, color: secondary),
                ),
                if (content.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      content,
                      style: const TextStyle(fontSize: 15, height: 1.5),
                    ),
                  ),
                if (widget.type == 'mentions' &&
                    validNote &&
                    commentId.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        FilledButton.tonalIcon(
                          onPressed: () => editComment(
                            context,
                            widget.app,
                            note.reference,
                            targetId: commentId,
                            author: textOf(actor['nickname']),
                          ),
                          icon: const Icon(Icons.chat_bubble_outline, size: 16),
                          label: const Text('回复'),
                        ),
                        FilledButton.tonalIcon(
                          onPressed: pending.contains(commentId)
                              ? null
                              : () => like(commentId, note.reference, comment),
                          icon: Icon(
                            (likeValues[commentId] ?? comment['liked'] == true)
                                ? Icons.favorite
                                : Icons.favorite_border,
                            size: 16,
                          ),
                          label: const Text('赞'),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
          if (isConnection && actorId.isNotEmpty) ...[
            const SizedBox(width: 8),
            SizedBox(
              width: 76,
              height: 44,
              child: Center(
                child: following
                    ? Text(
                        '已关注',
                        style: TextStyle(
                          color: secondary,
                          fontSize: 14,
                          fontWeight: FontWeight.w500,
                        ),
                      )
                    : OutlinedButton(
                        onPressed: pending.contains(actorId)
                            ? null
                            : () => follow(actorId),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: const Color(0xFFFF2442),
                          side: const BorderSide(color: Color(0xFFFF2442)),
                          fixedSize: const Size(76, 32),
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          textStyle: const TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        child: Text(actor['fstatus'] == 'fans' ? '回关' : '关注'),
                      ),
              ),
            ),
          ] else if (thumb.isNotEmpty) ...[
            const SizedBox(width: 12),
            GestureDetector(
              onTap: validNote ? openNote : null,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(4),
                child: SizedBox.square(
                  dimension: 44,
                  child: RemoteImage(thumb, api: widget.app, decodeWidth: 160),
                ),
              ),
            ),
          ] else if (validNote)
            IconButton(
              tooltip: '查看笔记',
              onPressed: openNote,
              icon: const Icon(Icons.chevron_right),
            ),
        ],
      ),
    );
  }

  final pending = <String>{}, followed = <String>{};
  final likeValues = <String, bool>{};
  Future<void> follow(String id) async {
    if (!pending.add(id)) return;
    setState(() {});
    try {
      await widget.app.api.call('followUser', {'id': id});
      if (mounted) followed.add(id);
    } catch (e) {
      if (mounted) showFailure(context, e);
    } finally {
      if (mounted) setState(() => pending.remove(id));
    }
  }

  Future<void> like(String id, NoteRef note, Json comment) async {
    if (!pending.add(id)) return;
    final value = !(likeValues[id] ?? comment['liked'] == true);
    setState(() {});
    try {
      await widget.app.api.call('likeComment', {
        ...note.args,
        'commentId': id,
        'value': value,
      });
      if (mounted) likeValues[id] = value;
    } catch (e) {
      if (mounted) showFailure(context, e);
    } finally {
      if (mounted) setState(() => pending.remove(id));
    }
  }
}
