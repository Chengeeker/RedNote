import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../core/design_system/components/review_card.dart';
import '../core/utils/spring_page_route.dart';
import '../features/media/image_gallery_page.dart'
    show
        GalleryImage,
        directImageRectTween,
        ImageGalleryHeroTag,
        imageGalleryHeroFlightShuttleBuilder;
import 'common.dart';
import 'note_page.dart';
import 'note_title_hero.dart';

class FeedPage extends StatefulWidget {
  const FeedPage({
    super.key,
    required this.app,
    this.method = 'home',
    this.args = const {},
    this.source = 'pc_feed',
    this.header,
    this.showViewCount = false,
    this.viewCounts = const {},
  });
  final AppState app;
  final String method;
  final Json args;
  final String source;
  final Widget? header;
  final bool showViewCount;
  final Json viewCounts;
  @override
  State<FeedPage> createState() => FeedPageState();
}

class FeedPageState extends State<FeedPage> {
  late final ScrollController scroll;
  late final String _cacheKey =
      'homeSnapshot:${textOf(widget.app.user["user_id"])}';
  bool get _cacheable => widget.method == 'home';
  final notes = <Note>[];
  final seen = <String>{};
  bool busy = false, more = true;
  Object? failure;
  String cursor = '', sessionId = '';
  int page = 1, generation = 0;
  int? _lastEpoch;
  bool get _supportsGuest =>
      widget.method == 'home' || widget.method == 'search';

  @override
  void initState() {
    super.initState();
    _lastEpoch = widget.app.epoch;
    final restored = _restore();
    scroll = ScrollController(
      initialScrollOffset: restored
          ? (widget.app.preferences.getDouble('$_cacheKey:offset') ?? 0)
          : 0,
    );
    scroll.addListener(() {
      if (scroll.position.extentAfter < 600) load();
    });
    if (!restored && (widget.app.loggedIn || _supportsGuest)) load();
  }

  bool _restore() {
    if (!_cacheable) return false;
    try {
      final data = objectOf(
        jsonDecode(widget.app.preferences.getString(_cacheKey) ?? '{}'),
      );
      if (!mapEquals(objectOf(data['args']), widget.args)) return false;
      notes.addAll(
        objectsOf(data['items'])
            .map((item) => Note.fromItem(item, source: widget.source))
            .whereType<Note>(),
      );
      if (notes.isEmpty) return false;
      seen.addAll(notes.map((note) => note.reference.id));
      cursor = textOf(data['cursor']);
      sessionId = textOf(data['sessionId']);
      page = data['page'] as int;
      more = data['more'] as bool;
      return true;
    } catch (_) {
      notes.clear();
      seen.clear();
      cursor = sessionId = '';
      page = 1;
      more = true;
      return false;
    }
  }

  Future<void> _saveSnapshot() async {
    if (!_cacheable || notes.isEmpty) return;
    try {
      await widget.app.preferences.setString(
        _cacheKey,
        jsonEncode({
          'args': widget.args,
          'items': [
            for (final note in notes)
              {
                'id': note.reference.id,
                'xsec_token': note.reference.token,
                'xsec_source': note.reference.source,
                'note_card': note.raw,
              },
          ],
          'cursor': cursor,
          'sessionId': sessionId,
          'page': page,
          'more': more,
        }),
      );
    } catch (error) {
      debugPrint('Feed snapshot save failed: $error');
    }
  }

  void _saveOffset() {
    if (_cacheable && scroll.hasClients && notes.isNotEmpty) {
      widget.app.preferences.setDouble(
        '$_cacheKey:offset',
        scroll.offset.clamp(0, double.infinity),
      );
    }
  }

  @override
  void didUpdateWidget(FeedPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.app.epoch != _lastEpoch ||
        widget.method != oldWidget.method ||
        !mapEquals(widget.args, oldWidget.args)) {
      _lastEpoch = widget.app.epoch;
      refresh();
    }
  }

  @override
  void dispose() {
    generation++;
    _saveOffset();
    scroll.dispose();
    super.dispose();
  }

  void toTop() {
    if (!scroll.hasClients) return;
    if (MediaQuery.disableAnimationsOf(context)) {
      scroll.jumpTo(0);
    } else {
      scroll.animateTo(
        0,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
    }
  }

  Future<void> refresh() async {
    generation++;
    busy = false;
    cursor = '';
    sessionId = '';
    page = 1;
    more = true;
    failure = null;
    notes.clear();
    seen.clear();
    if (_cacheable) {
      await widget.app.preferences.setDouble('$_cacheKey:offset', 0);
    }
    await load();
  }

  Future<void> load() async {
    if (!mounted ||
        busy ||
        !more ||
        (!widget.app.loggedIn && !_supportsGuest) ||
        failure != null) {
      return;
    }
    final current = generation;
    setState(() {
      busy = true;
    });
    try {
      final data = await widget.app.api.call(widget.method, {
        ...widget.args,
        'cursor': cursor,
        'page': page,
        'sessionId': sessionId,
      });
      if (!mounted || current != generation) return;
      final items = objectsOf(data['items'] ?? data['notes']);
      final parsed = items
          .map((e) => Note.fromItem(e, source: widget.source))
          .whereType<Note>();
      final before = notes.length;
      for (final note in parsed) {
        if (seen.add(note.reference.id)) notes.add(note);
      }
      final next = textOf(data['cursor_score'] ?? data['cursor']);
      final isSearch = widget.method == 'search';
      final hasMore =
          data['has_more'] != false &&
          data['has_more'] != 0 &&
          items.isNotEmpty;
      more =
          hasMore &&
          notes.length > before &&
          (isSearch || (next.isNotEmpty && next != cursor));
      cursor = next;
      sessionId = textOf(data['session_id']);
      page++;
      await _saveSnapshot();
    } catch (e) {
      if (mounted && current == generation) failure = e;
    } finally {
      if (mounted && current == generation) {
        setState(() {
          busy = false;
        });
      }
    }
  }

  void retry() {
    setState(() {
      failure = null;
    });
    load();
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.app.loggedIn && !_supportsGuest) {
      return LoadMessage(
        title: '登录后，发现感兴趣的内容',
        onLogin: () => signIn(context, widget.app),
      );
    }
    if (notes.isEmpty) {
      final message = busy
          ? const Center(child: CircularProgressIndicator())
          : LoadMessage(
              title: failure == null ? '暂时没有内容' : '内容加载失败',
              error: failure,
              onRetry: failure == null ? refresh : retry,
              onLogin: () => signIn(context, widget.app),
              onVerification: (verificationUrl) => signIn(
                context,
                widget.app,
                manualVerification: true,
                verificationUrl: verificationUrl,
              ),
            );
      return CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          if (widget.header != null) SliverToBoxAdapter(child: widget.header),
          SliverFillRemaining(hasScrollBody: false, child: message),
        ],
      );
    }
    return NotificationListener<ScrollEndNotification>(
      onNotification: (_) {
        _saveOffset();
        return false;
      },
      child: RefreshIndicator(
        onRefresh: refresh,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final columns = constraints.maxWidth >= 840
                ? 4
                : constraints.maxWidth >= 600
                ? 3
                : 2;
            return CustomScrollView(
              controller: scroll,
              physics: const AlwaysScrollableScrollPhysics(),
              slivers: [
                if (widget.header != null)
                  SliverToBoxAdapter(child: widget.header),
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
                  sliver: SliverGrid(
                    gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: columns,
                      mainAxisSpacing: 8,
                      crossAxisSpacing: 8,
                      mainAxisExtent:
                          (constraints.maxWidth / columns) * 1.35 +
                          (MediaQuery.textScalerOf(context).scale(13) * 4),
                    ),
                    delegate: SliverChildBuilderDelegate(
                      (context, index) => NoteTile(
                        note: notes[index],
                        app: widget.app,
                        showViewCount: widget.showViewCount,
                        viewCount: widget.viewCounts[notes[index].reference.id]
                            ?.toString(),
                        clearFocusOnReturn: widget.method == 'search',
                      ),
                      childCount: notes.length,
                    ),
                  ),
                ),
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 100),
                    child: Center(
                      child: busy
                          ? const CircularProgressIndicator()
                          : failure != null
                          ? Column(
                              children: [
                                Text(
                                  errorText(failure!),
                                  textAlign: TextAlign.center,
                                ),
                                if (needsLogin(failure))
                                  TextButton(
                                    onPressed: () =>
                                        signIn(context, widget.app),
                                    child: const Text('网页登录'),
                                  )
                                else
                                  TextButton(
                                    onPressed: retry,
                                    child: const Text('重试加载'),
                                  ),
                              ],
                            )
                          : TextButton(
                              onPressed: more ? load : null,
                              child: Text(more ? '加载更多' : '已经到底了'),
                            ),
                    ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class NoteTile extends StatelessWidget {
  const NoteTile({
    super.key,
    required this.note,
    required this.app,
    this.showViewCount = false,
    this.viewCount,
    this.clearFocusOnReturn = false,
  });
  final Note note;
  final AppState app;
  final bool showViewCount;
  final String? viewCount;
  final bool clearFocusOnReturn;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) => _buildTile(context),
  );

  Future<void> _toggleLike(BuildContext context) async {
    if (app.noteInteractionBusy(note, 'likeNote')) return;
    if (!app.loggedIn) {
      await signIn(context, app);
      return;
    }
    try {
      await app.toggleNoteInteraction(note, 'likeNote');
    } catch (error) {
      if (context.mounted) showFailure(context, error);
    }
  }

  Widget _buildTile(BuildContext context) {
    final interaction = app.noteInteraction(note);
    final coverTag = ImageGalleryHeroTag.forPic(
      scope: note.reference.id,
      index: 0,
      pic: GalleryImage(note.coverUrl),
    );
    return ReviewCard(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      onTap: () async {
        if (clearFocusOnReturn) {
          FocusScope.of(context).unfocus(disposition: UnfocusDisposition.scope);
        }
        await Navigator.of(context).push(
          PhysicsSpringGalleryRoute<void>(
            child: NotePage(app: app, initial: note),
            opaque: true,
          ),
        );
        if (context.mounted && clearFocusOnReturn) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (context.mounted) {
              FocusScope.of(context)
                  .unfocus(disposition: UnfocusDisposition.scope);
            }
          });
        }
      },
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                Hero(
                  createRectTween: directImageRectTween,
                  tag: coverTag,
                  flightShuttleBuilder: imageGalleryHeroFlightShuttleBuilder,
                  child: LayoutBuilder(
                    builder: (context, box) => RemoteImage(
                      note.coverUrl,
                      api: app,
                      cacheLocally: true,
                      decodeWidth:
                          (box.maxWidth *
                                  MediaQuery.devicePixelRatioOf(context))
                              .round()
                              .clamp(1, 1200),
                    ),
                  ),
                ),
                if (note.isVideo)
                  const Positioned(
                    right: 8,
                    top: 8,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: Color(0x99000000),
                        shape: BoxShape.circle,
                      ),
                      child: Padding(
                        padding: EdgeInsets.all(5),
                        child: Icon(
                          Icons.play_arrow_rounded,
                          color: Colors.white,
                          size: 20,
                        ),
                      ),
                    ),
                  ),
                if (showViewCount && (viewCount ?? note.viewCount).isNotEmpty)
                  Positioned(
                    left: 8,
                    bottom: 8,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: const Color(0x99000000),
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 7,
                          vertical: 4,
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              Icons.visibility,
                              size: 14,
                              color: Colors.white,
                            ),
                            const SizedBox(width: 4),
                            Text(
                              viewCount ?? note.viewCount,
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(10, 9, 10, 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Hero(
                  createRectTween: directImageRectTween,
                  tag: 'note-title:${note.reference.id}',
                  flightShuttleBuilder: noteTitleFlightShuttle,
                  child: Material(
                    type: MaterialType.transparency,
                    child: XhsRichText(
                      note.title.isEmpty
                          ? (note.isVideo ? '视频笔记' : '图文笔记')
                          : note.title,
                      api: app,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurface,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    UserAvatar(note.avatar, size: 22, api: app),
                    const SizedBox(width: 5),
                    Expanded(
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              note.author,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ),
                          Tooltip(
                            message: interaction['liked'] == true
                                ? '取消点赞'
                                : '点赞笔记',
                            child: InkWell(
                              key: ValueKey('note-like:${note.reference.id}'),
                              borderRadius: BorderRadius.circular(16),
                              onTap: () => _toggleLike(context),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 4,
                                  vertical: 8,
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      interaction['liked'] == true
                                          ? Icons.favorite
                                          : Icons.favorite_border,
                                      size: 16,
                                      color: interaction['liked'] == true
                                          ? const Color(0xFFFF2442)
                                          : null,
                                    ),
                                    const SizedBox(width: 3),
                                    Text(
                                      textOf(interaction['liked_count'] ?? '0'),
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodySmall,
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
