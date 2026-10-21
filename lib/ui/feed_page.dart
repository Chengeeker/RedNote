import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../core/design_system/components/review_card.dart';
import '../core/utils/spring_page_route.dart';
import '../features/media/image_gallery_page.dart'
    show
        GalleryImage,
        ImageGalleryHeroTag,
        imageGalleryHeroFlightShuttleBuilder;
import 'common.dart';
import 'note_page.dart';

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
  final scroll = ScrollController();
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
    scroll.addListener(() {
      if (scroll.position.extentAfter < 600) load();
    });
    if (widget.app.loggedIn || _supportsGuest) load();
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
              onVerification: () =>
                  signIn(context, widget.app, manualVerification: true),
            );
      return CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          if (widget.header != null) SliverToBoxAdapter(child: widget.header),
          SliverFillRemaining(hasScrollBody: false, child: message),
        ],
      );
    }
    return RefreshIndicator(
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
                                  onPressed: () => signIn(context, widget.app),
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
  });
  final Note note;
  final AppState app;
  final bool showViewCount;
  final String? viewCount;
  @override
  Widget build(BuildContext context) {
    final coverTag = ImageGalleryHeroTag.forPic(
      scope: note.reference.id,
      index: 0,
      pic: GalleryImage(note.coverUrl),
    );
    return ReviewCard(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      onTap: () => Navigator.of(context).push(
        PhysicsSpringGalleryRoute<void>(
          child: NotePage(app: app, initial: note),
          opaque: true,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                Hero(
                  tag: coverTag,
                  flightShuttleBuilder: imageGalleryHeroFlightShuttleBuilder,
                  child: LayoutBuilder(
                    builder: (context, box) => RemoteImage(
                      note.coverUrl,
                      api: app,
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
                  tag: 'note-title:${note.reference.id}',
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
                          if (note.likes.isNotEmpty) ...[
                            const Icon(Icons.favorite_border, size: 14),
                            const SizedBox(width: 3),
                            Text(
                              note.likes,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ],
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
