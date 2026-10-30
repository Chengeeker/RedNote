import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import '../core/utils/app_dialog.dart';
import 'common.dart';
import 'feed_page.dart';

class AlbumsPage extends StatefulWidget {
  const AlbumsPage({super.key, required this.app});
  final AppState app;
  @override
  State<AlbumsPage> createState() => _AlbumsPageState();
}

class _AlbumsPageState extends State<AlbumsPage> {
  final albums = <Json>[];
  late final int epoch = widget.app.epoch;
  int page = 1;
  bool busy = false, more = true;
  Object? failure;
  bool get current => mounted && epoch == widget.app.epoch;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load({bool refresh = false}) async {
    if (busy || !current || (!more && !refresh)) return;
    setState(() {
      busy = true;
      failure = null;
    });
    final next = refresh ? 1 : page;
    try {
      final data = await widget.app.api.call('boards', {'page': next});
      if (!current) return;
      if (data['boards'] is! List) throw StateError('服务器未返回专辑列表');
      final values = objectsOf(data['boards']);
      setState(() {
        if (refresh) albums.clear();
        final before = albums.length;
        final seen = albums.map((x) => textOf(x['id'])).toSet();
        for (final value in values) {
          if (textOf(value['id']).isNotEmpty && seen.add(textOf(value['id']))) {
            albums.add(value);
          }
        }
        page = next + 1;
        more = values.length >= 15 && albums.length > before;
      });
    } catch (error) {
      if (current) setState(() => failure = error);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> open(Json album) async {
    final changed = await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => AlbumPage(app: widget.app, album: album),
      ),
    );
    if (changed == true && current) await load(refresh: true);
  }

  Future<void> create() async {
    final saved = await showAppDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _AlbumEditor(app: widget.app),
    );
    if (saved == true && current) await load(refresh: true);
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.app,
    builder: (context, _) => Scaffold(
      appBar: ReviewFrostedAppBar(
        title: const Text('收藏专辑'),
        actions: [
          IconButton(
            tooltip: '新建专辑',
            onPressed: current && !busy ? create : null,
            icon: const Icon(Icons.create_new_folder_outlined),
          ),
        ],
      ),
      body: !current
          ? const LoadMessage(title: '账号已变化，请返回重新打开')
          : RefreshIndicator(
              onRefresh: () => load(refresh: true),
              child: ListView(
                padding: const EdgeInsets.all(16),
                physics: const AlwaysScrollableScrollPhysics(),
                children: [
                  for (final album in albums)
                    ListTile(
                      leading: const Icon(Icons.folder_outlined),
                      title: Text(textOf(album['name'])),
                      subtitle: Text(
                        '${album['total'] ?? 0} 篇笔记 · ${album['privacy'] == 0 ? '公开' : '私密'}',
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => open(album),
                    ),
                  if (failure != null)
                    LoadMessage(
                      title: '专辑加载失败',
                      error: failure,
                      onRetry: () => load(),
                    ),
                  if (busy) const Center(child: CircularProgressIndicator()),
                  if (!busy && failure == null && albums.isEmpty)
                    const LoadMessage(title: '还没有收藏专辑'),
                  if (!busy && failure == null && more)
                    TextButton(onPressed: load, child: const Text('加载更多')),
                ],
              ),
            ),
    ),
  );
}

class AlbumPage extends StatefulWidget {
  const AlbumPage({super.key, required this.app, required this.album});
  final AppState app;
  final Json album;
  @override
  State<AlbumPage> createState() => _AlbumPageState();
}

class _AlbumPageState extends State<AlbumPage> {
  late final int epoch = widget.app.epoch;
  bool busy = false;
  Future<void> edit() async {
    if (busy) return;
    setState(() => busy = true);
    try {
      final data = await widget.app.api.call('boardInfo', {
        'id': widget.album['id'],
      });
      if (!mounted || epoch != widget.app.epoch) return;
      if (textOf(data['id']) != textOf(widget.album['id'])) {
        throw StateError('专辑信息不匹配');
      }
      final changed = await showAppDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (_) => _AlbumEditor(app: widget.app, album: data),
      );
      if (changed == true && mounted && epoch == widget.app.epoch) {
        Navigator.pop(context, true);
      }
    } catch (error) {
      if (mounted && epoch == widget.app.epoch) showFailure(context, error);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.app,
    builder: (context, _) => Scaffold(
      appBar: ReviewFrostedAppBar(
        title: Text(textOf(widget.album['name'])),
        actions: [
          IconButton(
            tooltip: '编辑专辑',
            onPressed: busy || epoch != widget.app.epoch ? null : edit,
            icon: const Icon(Icons.edit_outlined),
          ),
        ],
      ),
      body: epoch != widget.app.epoch
          ? const LoadMessage(title: '账号已变化，请返回重新打开')
          : FeedPage(
              app: widget.app,
              method: 'boardNotes',
              args: {'id': widget.album['id']},
              source: 'pc_board',
            ),
    ),
  );
}

class _AlbumEditor extends StatefulWidget {
  const _AlbumEditor({required this.app, this.album});
  final AppState app;
  final Json? album;
  @override
  State<_AlbumEditor> createState() => _AlbumEditorState();
}

class _AlbumEditorState extends State<_AlbumEditor> {
  late final name = TextEditingController(text: textOf(widget.album?['name']));
  late final desc = TextEditingController(text: textOf(widget.album?['desc']));
  late bool private = widget.album != null && widget.album!['privacy'] != 0;
  late final int epoch = widget.app.epoch;
  bool busy = false;
  Object? failure;

  @override
  void dispose() {
    name.dispose();
    desc.dispose();
    super.dispose();
  }

  Future<void> save({bool delete = false}) async {
    if (busy) return;
    if (epoch != widget.app.epoch) {
      setState(() => failure = StateError('账号已变化，请重新打开'));
      return;
    }
    if (!delete &&
        (name.text.trim().isEmpty ||
            name.text.trim().runes.length > 12 ||
            desc.text.runes.length > 1000)) {
      setState(() => failure = StateError('名称需为1至12字，简介最多1000字'));
      return;
    }
    if (delete) {
      final yes = await showAppDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('删除专辑？'),
          content: const Text('专辑删除后无法恢复，里面的笔记仍会保留在全部收藏中。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('删除'),
            ),
          ],
        ),
      );
      if (yes != true || !mounted || epoch != widget.app.epoch) return;
    }
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      await widget.app.api.call(delete ? 'deleteBoard' : 'saveBoard', {
        if (widget.album != null) 'id': widget.album!['id'],
        if (!delete) ...{
          'name': name.text.trim(),
          'desc': desc.text,
          'private': private,
        },
      });
      if (mounted && epoch == widget.app.epoch) Navigator.pop(context, true);
    } catch (error) {
      if (mounted) setState(() => failure = error);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !busy,
    child: AlertDialog(
      title: Text(widget.album == null ? '新建专辑' : '编辑专辑'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: name,
              enabled: !busy,
              maxLength: 12,
              decoration: const InputDecoration(labelText: '名称'),
            ),
            TextField(
              controller: desc,
              enabled: !busy,
              maxLength: 1000,
              maxLines: 3,
              decoration: const InputDecoration(labelText: '简介'),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('私密专辑'),
              value: private,
              onChanged: busy
                  ? null
                  : (value) => setState(() => private = value),
            ),
            if (failure != null)
              Text(
                failure.toString(),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            if (busy) const CircularProgressIndicator(),
          ],
        ),
      ),
      actions: [
        if (widget.album != null)
          TextButton(
            onPressed: busy ? null : () => save(delete: true),
            child: const Text('删除专辑'),
          ),
        TextButton(
          onPressed: busy ? null : () => Navigator.pop(context, false),
          child: const Text('取消'),
        ),
        FilledButton(onPressed: busy ? null : save, child: const Text('保存')),
      ],
    ),
  );
}
