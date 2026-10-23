import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:extended_image/extended_image.dart'
    show ExtendedNetworkImageProvider;
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../core/design_system/components/review_navigation_bar.dart';

import 'package:flutter/services.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../data/media_support.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import 'common.dart';
import 'media_page.dart';
import 'profile_page.dart';
import 'comment_editor.dart';
import 'topic_search_page.dart';
import 'note_title_hero.dart';
import '../core/utils/app_dialog.dart';
import '../core/theme/app_theme.dart';
import '../features/media/image_gallery_page.dart';
import '../features/media/inline_hdr_badge.dart';
import '../core/utils/spring_page_route.dart';

class NotePage extends StatefulWidget {
  const NotePage({super.key, required this.app, required this.initial});
  final AppState app;
  final Note initial;
  @override
  State<NotePage> createState() => _NotePageState();
}

class _NotePageState extends State<NotePage> {
  late Note note;
  final Object galleryScope = Object();
  final GlobalKey commentsHeaderKey = GlobalKey();
  bool loading = true, commentBusy = false, more = true;
  Object? failure, commentFailure;
  final comments = <Json>[];
  final commentIds = <String>{};
  bool mutating = false, followingBusy = false, followed = false;
  int imageIndex = 0;
  final imagePages = PageController();
  ui.Image? _openingCoverPreview;
  int _previewIndex = 0;
  String cursor = '';
  @override
  void initState() {
    super.initState();
    note = widget.initial;
    load();
  }

  @override
  void dispose() {
    _openingCoverPreview?.dispose();
    imagePages.dispose();
    super.dispose();
  }

  void _retainOpeningPreview(ui.Image image, {int index = 0}) {
    if (!mounted) return;
    final previous = _openingCoverPreview;
    _previewIndex = index;
    _openingCoverPreview = image.clone();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      previous?.dispose();
      if (mounted) setState(() {});
    });
  }

  void _syncGalleryImage(int index) {
    if (!mounted || index == imageIndex) return;
    setState(() => imageIndex = index);
    if (imagePages.hasClients) imagePages.jumpToPage(index);
  }

  void _releaseOpeningPreview() {
    final preview = _openingCoverPreview;
    if (preview == null) return;
    _openingCoverPreview = null;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) setState(() {});
      preview.dispose();
    });
  }

  void openAuthor() {
    if (note.userId.isEmpty) return;
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => ProfilePage(
          app: widget.app,
          user: note.user,
          token: textOf(note.user['xsec_token']),
          source: 'pc_note',
        ),
      ),
    );
  }

  Future<void> followAuthor() async {
    if (followingBusy || note.userId.isEmpty) return;
    if (!widget.app.loggedIn) {
      await signIn(context, widget.app);
      return;
    }
    setState(() => followingBusy = true);
    try {
      await widget.app.api.call('followUser', {'id': note.userId});
      if (mounted) setState(() => followed = true);
    } catch (e) {
      if (mounted) showFailure(context, e);
    } finally {
      if (mounted) setState(() => followingBusy = false);
    }
  }

  Future<void> share() async {
    try {
      await widget.app.api.call('shareNote', note.reference.args);
    } catch (e) {
      if (mounted) showFailure(context, e);
    }
  }

  Future<void> load() async {
    setState(() {
      loading = true;
      failure = null;
    });
    try {
      if (note.reference.token.isEmpty) {
        throw PlatformException(
          code: 'reference',
          message: '列表未返回笔记访问凭据，请返回刷新列表后重试',
        );
      }
      final data = await widget.app.api.call('detail', note.reference.args);
      if (!mounted) return;
      final items = objectsOf(data['items']);
      final card = items.isEmpty
          ? <String, dynamic>{}
          : objectOf(items.first['note_card']);
      if (card.isEmpty) {
        throw PlatformException(code: 'response', message: '服务器未返回笔记内容');
      }
      note = Note({
        ...note.raw,
        ...card,
        'user': {...note.user, ...objectOf(card['user'])},
      }, note.reference);
      imageIndex = 0;
      if (imagePages.hasClients) imagePages.jumpToPage(0);
      comments.clear();
      commentIds.clear();
      cursor = '';
      more = true;
      commentFailure = null;
    } catch (e) {
      if (mounted) failure = e;
    }
    if (mounted) {
      setState(() {
        loading = false;
      });
      if (failure == null) loadComments();
    }
  }

  Future<void> loadComments() async {
    if (commentBusy || !more) return;
    setState(() {
      commentBusy = true;
      commentFailure = null;
    });
    try {
      final data = await widget.app.api.call('comments', {
        ...note.reference.args,
        'cursor': cursor,
      });
      if (!mounted) return;
      final received = objectsOf(data['comments']);
      for (final comment in received) {
        final id = textOf(comment['id']);
        if (id.isNotEmpty && commentIds.add(id)) comments.add(comment);
      }
      final next = textOf(data['cursor']);
      more =
          data['has_more'] != false &&
          data['has_more'] != 0 &&
          received.isNotEmpty &&
          next.isNotEmpty &&
          next != cursor;
      cursor = next;
    } catch (e) {
      if (mounted) commentFailure = e;
    }
    if (mounted) {
      setState(() {
        commentBusy = false;
      });
    }
  }

  Future<void> toggleNote(String method) async {
    if (mutating || loading) return;
    setState(() => mutating = true);
    try {
      await widget.app.toggleNoteInteraction(note, method);
    } catch (e) {
      if (mounted) showFailure(context, e);
    } finally {
      if (mounted) setState(() => mutating = false);
    }
  }

  Future<void> compose() async {
    if (await editComment(context, widget.app, note.reference) && mounted) {
      _adjustCommentCount(1);
      comments.clear();
      commentIds.clear();
      cursor = '';
      more = true;
      await loadComments();
    }
  }

  void _adjustCommentCount(int delta) {
    final interaction = objectOf(note.raw['interact_info']);
    final count = int.tryParse(textOf(interaction['comment_count']));
    if (count != null) {
      interaction['comment_count'] = (count + delta).clamp(0, 0x7fffffff);
      note.raw['interact_info'] = interaction;
    }
  }

  void _commentDeleted(String id) {
    _adjustCommentCount(-1);
    comments.removeWhere((comment) => textOf(comment['id']) == id);
    commentIds.remove(id);
    if (mounted) setState(() {});
  }

  Future<void> scrollToComments() async {
    final target = commentsHeaderKey.currentContext;
    if (target != null) {
      await Scrollable.ensureVisible(
        target,
        duration: const Duration(milliseconds: 320),
        curve: Curves.easeOutCubic,
      );
    }
  }

  @override
  Widget build(BuildContext context) =>
      ListenableBuilder(listenable: widget.app, builder: _buildPage);

  Widget _buildPage(BuildContext context, Widget? child) {
    final pics = [
      for (final image in note.images)
        if (Note.imageUrl(image).isNotEmpty)
          GalleryImage(
            Note.originalImageUrl(image),
            width: int.tryParse(textOf(image['width'])) ?? 0,
            height: int.tryParse(textOf(image['height'])) ?? 0,
            raw: image,
            thumbnailUrl: Note.imageUrl(image),
          ),
    ];
    final coverPreviewUrl = widget.initial.coverUrl.isNotEmpty
        ? widget.initial.coverUrl
        : note.coverUrl;
    if (pics.isNotEmpty && coverPreviewUrl.isNotEmpty) {
      final first = pics.first;
      pics[0] = GalleryImage(
        first.url,
        width: first.width,
        height: first.height,
        isHdr: first.overrideIsHdr,
        raw: first.raw,
        thumbnailUrl: coverPreviewUrl,
      );
    }
    if (pics.isEmpty && note.coverUrl.isNotEmpty) {
      pics.add(GalleryImage(note.coverUrl));
    }
    final images = pics.map((pic) {
      final preview = Note.imageUrl(pic.raw ?? {});
      return preview.isEmpty ? pic.url : preview;
    }).toList();
    final sources = videoSources(note.raw);
    final interaction = widget.app.noteInteraction(note);
    final reportedCommentCount =
        int.tryParse(textOf(interaction['comment_count'])) ?? 0;
    final commentCount = reportedCommentCount > comments.length
        ? reportedCommentCount
        : comments.length;
    final useGlass = widget.app.liquidGlass;
    final scheme = Theme.of(context).colorScheme;
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final capsuleHeight = math.max(
      44.0,
      29 + MediaQuery.textScalerOf(context).scale(9),
    );
    Widget capsule(String key, Widget child) => Material(
      key: ValueKey(key),
      color: useGlass
          ? Colors.transparent
          : isDark
          ? const Color(0xFF262626)
          : scheme.surfaceContainerHighest.withValues(alpha: 0.92),
      elevation: useGlass ? 0 : 2,
      shadowColor: Colors.black.withValues(alpha: 0.1),
      borderRadius: BorderRadius.circular(24),
      clipBehavior: Clip.antiAlias,
      child: Container(
        height: capsuleHeight,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(24),
          border: useGlass
              ? null
              : Border.all(
                  color: scheme.outlineVariant.withValues(
                    alpha: isDark ? 0.2 : 0.35,
                  ),
                  width: 0.6,
                ),
        ),
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (useGlass)
              AdaptiveGlass(
                key: ValueKey('$key-glass'),
                shape: const LiquidRoundedSuperellipse(borderRadius: 9999),
                settings: ReviewNavigationBar.liquidGlassSettings,
                quality: ReviewNavigationBar.liquidGlassQuality,
                child: const SizedBox.expand(),
              ),
            child,
          ],
        ),
      ),
    );
    final scaffold = Scaffold(
      extendBody: true,
      bottomNavigationBar: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 4, 14, 16),
          child: Row(
            children: [
              Expanded(
                flex: 9,
                child: capsule(
                  'note-comment-capsule',
                  InkWell(
                    onTap: loading || failure != null ? null : compose,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 14),
                      child: Row(
                        children: [
                          Icon(
                            Icons.edit_outlined,
                            size: 16,
                            color: scheme.onSurfaceVariant,
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              '说点什么…',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 13.5,
                                color: scheme.onSurfaceVariant,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 12,
                child: capsule(
                  'note-actions-capsule',
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Row(
                      children: [
                        Expanded(
                          child: _CommentFooterAction(
                            tooltip: '点赞',
                            icon: Icons.favorite_border_rounded,
                            selectedIcon: Icons.favorite_rounded,
                            count: textOf(interaction['liked_count'] ?? '0'),
                            selected: interaction['liked'] == true,
                            onPressed: mutating || loading
                                ? null
                                : () => toggleNote('likeNote'),
                          ),
                        ),
                        Expanded(
                          child: _CommentFooterAction(
                            tooltip: '评论',
                            icon: Icons.chat_bubble_outline_rounded,
                            selectedIcon: Icons.chat_bubble_rounded,
                            count: '$commentCount',
                            onPressed: loading || failure != null
                                ? null
                                : scrollToComments,
                          ),
                        ),
                        Expanded(
                          child: _CommentFooterAction(
                            tooltip: '收藏',
                            icon: Icons.star_border_rounded,
                            selectedIcon: Icons.star_rounded,
                            count: textOf(
                              interaction['collected_count'] ??
                                  interaction['collect_count'] ??
                                  '0',
                            ),
                            selected: interaction['collected'] == true,
                            onPressed: mutating || loading
                                ? null
                                : () => toggleNote('collectNote'),
                          ),
                        ),
                        Expanded(
                          child: _CommentFooterAction(
                            tooltip: '分享',
                            icon: Icons.ios_share_rounded,
                            selectedIcon: Icons.ios_share_rounded,
                            count: '分享',
                            onPressed: share,
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
      ),
      appBar: ReviewFrostedAppBar(
        titleSpacing: 0,
        title: InkWell(
          onTap: note.userId.isEmpty ? null : openAuthor,
          child: Row(
            children: [
              UserAvatar(note.avatar, size: 36, api: widget.app),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  note.author.isEmpty ? '作者' : note.author,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 16),
                ),
              ),
            ],
          ),
        ),
        actions: [
          if (note.userId.isNotEmpty &&
              note.userId !=
                  textOf(
                    widget.app.user['user_id'] ?? widget.app.user['userid'],
                  ))
            Padding(
              padding: const EdgeInsets.only(left: 8),
              child: OutlinedButton(
                onPressed:
                    followingBusy ||
                        followed ||
                        const ['both', 'follows'].contains(note.user['fstatus'])
                    ? null
                    : followAuthor,
                style: OutlinedButton.styleFrom(
                  foregroundColor: const Color(0xFFFF2442),
                  side: const BorderSide(color: Color(0xFFFF2442)),
                  minimumSize: const Size(68, 30),
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                ),
                child: Text(
                  followed ||
                          const [
                            'both',
                            'follows',
                          ].contains(note.user['fstatus'])
                      ? '已关注'
                      : '关注',
                ),
              ),
            ),
          IconButton(
            tooltip: '分享笔记',
            onPressed: share,
            icon: const Icon(Icons.ios_share_rounded),
          ),
          PopupMenuButton<String>(
            tooltip: '更多',
            onSelected: (_) => load(),
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'refresh',
                enabled: !loading,
                child: const Text('刷新笔记'),
              ),
            ],
          ),
        ],
      ),
      body: GlassBackgroundSource(
        enabled: useGlass,
        child: SelectionArea(
          child: ListView(
            padding: EdgeInsets.only(
              bottom:
                  capsuleHeight + 48 + MediaQuery.viewPaddingOf(context).bottom,
            ),
            children: [
              if (loading) const LinearProgressIndicator(),
              if (images.isNotEmpty)
                AspectRatio(
                  aspectRatio: pics.first.width > 0 && pics.first.height > 0
                      ? (pics.first.width / pics.first.height).clamp(0.6, 1.8)
                      : note.coverRatio.clamp(0.6, 1.8),
                  child: Stack(
                    children: [
                      Positioned.fill(
                        child: PageView.builder(
                          controller: imagePages,
                          itemCount: pics.length,
                          onPageChanged: (index) =>
                              setState(() => imageIndex = index),
                          itemBuilder: (context, index) {
                            final openingPreview = index == _previewIndex
                                ? _openingCoverPreview
                                : null;
                            final image = Stack(
                              fit: StackFit.expand,
                              children: [
                                if (openingPreview != null)
                                  RawImage(
                                    key: const ValueKey('note-opening-preview'),
                                    image: openingPreview,
                                    fit: BoxFit.contain,
                                  )
                                else if (index == 0 &&
                                    widget.initial.coverUrl.isNotEmpty &&
                                    widget.initial.coverUrl != images[index])
                                  RemoteImage(
                                    widget.initial.coverUrl,
                                    fit: BoxFit.contain,
                                    decodeWidth: 600,
                                    api: widget.app,
                                  ),
                                Image(
                                  key: ValueKey('note-image-$index'),
                                  image: ExtendedNetworkImageProvider(
                                    images[index],
                                    cache: true,
                                    headers: redImageHeaders(
                                      images[index],
                                      api: widget.app,
                                    ),
                                    printError: false,
                                  ),
                                  gaplessPlayback: true,
                                  fit: BoxFit.contain,
                                  frameBuilder:
                                      (context, child, frame, synchronousCall) {
                                        final ready =
                                            synchronousCall || frame != null;
                                        if (ready && index == _previewIndex) {
                                          _releaseOpeningPreview();
                                        }
                                        return ready
                                            ? child
                                            : const SizedBox.shrink();
                                      },
                                  errorBuilder: (_, _, _) =>
                                      const SizedBox.shrink(),
                                ),
                              ],
                            );
                            final thumbnail = ImageGalleryHeroThumbnail(
                              scope: note.reference.id,
                              index: index,
                              pic: pics[index],
                              child: Material(
                                color: Colors.transparent,
                                child: InkWell(
                                  onTap: () => Navigator.push(
                                    context,
                                    PhysicsSpringGalleryRoute<void>(
                                      child: GalleryPage(
                                        app: widget.app,
                                        pics: pics,
                                        author: note.author,
                                        initial: index,
                                        scope: note.reference.id,
                                        onPageChanged: _syncGalleryImage,
                                      ),
                                    ),
                                  ),
                                  child: image,
                                ),
                              ),
                            );
                            return HeroMode(
                              enabled: index == imageIndex,
                              child: ImageGalleryHeroPreviewHost(
                                onPreviewCaptured: (image) =>
                                    _retainOpeningPreview(image, index: index),
                                child: thumbnail,
                              ),
                            );
                          },
                        ),
                      ),
                      if (pics.length > 1)
                        Positioned(
                          left: 12,
                          bottom: 12,
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: Colors.black.withValues(alpha: 0.5),
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 9,
                                vertical: 4,
                              ),
                              child: Text(
                                '${imageIndex + 1}/${pics.length}',
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ),
                        ),
                      Positioned(
                        right: 12,
                        bottom: 12,
                        child: IgnorePointer(
                          child: InlineHdrBadge(
                            url: pics[imageIndex.clamp(0, pics.length - 1)]
                                .originalUrl,
                            headers: redImageHeaders(
                              pics[imageIndex.clamp(0, pics.length - 1)]
                                  .originalUrl,
                              api: widget.app,
                              preserveHdr: true,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              if (note.isVideo && !loading)
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: sources.isEmpty
                      ? const Text('此笔记暂未返回可直接播放的视频流')
                      : FilledButton.icon(
                          onPressed: () => Navigator.push(
                            context,
                            MaterialPageRoute<void>(
                              builder: (_) => PlayerPage(
                                app: widget.app,
                                sources: sources,
                                title: note.title,
                                author: note.author,
                              ),
                            ),
                          ),
                          icon: const Icon(Icons.play_arrow),
                          label: const Text('播放视频'),
                        ),
                ),
              if (failure != null)
                LoadMessage(
                  title: '笔记加载失败',
                  error: failure,
                  onRetry: load,
                  onLogin: () => signIn(context, widget.app),
                  onVerification: () =>
                      signIn(context, widget.app, manualVerification: true),
                ),
              Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (note.title.isNotEmpty)
                      Hero(
                        createRectTween: directImageRectTween,
                        tag: 'note-title:${widget.initial.reference.id}',
                        flightShuttleBuilder: noteTitleFlightShuttle,
                        child: Material(
                          type: MaterialType.transparency,
                          child: XhsRichText(
                            note.title,
                            api: widget.app,
                            onTopicTap: (topic) =>
                                openTopicSearch(context, widget.app, topic),
                            style: TextStyle(
                              fontSize: 18,
                              fontWeight: context.adjustWeight(FontWeight.w600),
                              height: 1.5,
                            ),
                          ),
                        ),
                      ),
                    if (note.description.isNotEmpty) ...[
                      const SizedBox(height: 12),
                      XhsRichText(
                        note.description,
                        api: widget.app,
                        onTopicTap: (topic) =>
                            openTopicSearch(context, widget.app, topic),
                        style: const TextStyle(fontSize: 16, height: 1.6),
                      ),
                    ],
                    if (notePublicationMeta(note.raw).isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 24, bottom: 4),
                        child: Text(
                          notePublicationMeta(note.raw),
                          style: TextStyle(
                            fontSize: 12,
                            color: Theme.of(context)
                                .colorScheme
                                .onSurfaceVariant,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              const Divider(height: 1, indent: 16, endIndent: 16),
              Padding(
                key: commentsHeaderKey,
                padding: const EdgeInsets.fromLTRB(16, 16, 12, 8),
                child: Row(
                  children: [
                    Text(
                      '共 $commentCount 条评论',
                      style: Theme.of(context).textTheme.titleMedium
                          ?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const Spacer(),
                    Tooltip(
                      message: '当前为默认排序',
                      child: Semantics(
                        label: '评论默认排序',
                        child: Icon(
                          Icons.sort_rounded,
                          size: 22,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 6, 16, 12),
                child: Row(
                  children: [
                    UserAvatar(
                      userImageUrl(widget.app.user),
                      size: 40,
                      api: widget.app,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Material(
                        color: Theme.of(context)
                            .colorScheme
                            .surfaceContainerHigh,
                        borderRadius: BorderRadius.circular(28),
                        child: InkWell(
                          onTap: loading || failure != null ? null : compose,
                          borderRadius: BorderRadius.circular(28),
                          child: SizedBox(
                            height: 48,
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 16,
                              ),
                              child: Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      '留下你的想法吧',
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      style: Theme.of(context)
                                          .textTheme
                                          .bodyLarge
                                          ?.copyWith(
                                            color: Theme.of(context)
                                                .colorScheme
                                                .onSurfaceVariant,
                                          ),
                                    ),
                                  ),
                                  Icon(
                                    Icons.edit_outlined,
                                    size: 22,
                                    color: Theme.of(context)
                                        .colorScheme
                                        .onSurfaceVariant,
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              for (final comment in comments)
                CommentTile(
                  key: ValueKey(textOf(comment['id'])),
                  comment: comment,
                  app: widget.app,
                  reference: note.reference,
                  onDeleted: _commentDeleted,
                ),
              if (commentBusy)
                const Center(
                  child: Padding(
                    padding: EdgeInsets.all(20),
                    child: CircularProgressIndicator(),
                  ),
                ),
              if (commentFailure != null)
                LoadMessage(
                  title: '评论加载失败',
                  error: commentFailure,
                  onRetry: loadComments,
                  onLogin: () => signIn(context, widget.app),
                  onVerification: () =>
                      signIn(context, widget.app, manualVerification: true),
                )
              else if (!loading && failure == null && !commentBusy)
                Center(
                  child: TextButton(
                    onPressed: more ? loadComments : null,
                    child: Text(
                      more
                          ? '加载更多评论'
                          : comments.isEmpty
                          ? '暂无评论'
                          : '评论已全部加载',
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
    return useGlass ? GlassPage(child: scaffold) : scaffold;
  }
}

class CommentTile extends StatefulWidget {
  const CommentTile({
    super.key,
    required this.comment,
    required this.app,
    required this.reference,
    this.onDeleted,
  });
  final Json comment;
  final AppState app;
  final NoteRef reference;
  final ValueChanged<String>? onDeleted;
  @override
  State<CommentTile> createState() => _CommentTileState();
}

class _CommentTileState extends State<CommentTile> {
  final Object galleryScope = Object();
  bool busy = false, deleted = false;
  Json get comment => widget.comment;
  AppState get app => widget.app;
  NoteRef get reference => widget.reference;
  Future<void> like([Json? target]) async {
    if (busy) return;
    final targetComment = target ?? comment;
    setState(() => busy = true);
    final value = targetComment['liked'] != true;
    final count = int.tryParse(textOf(targetComment['like_count']));
    try {
      await app.api.call('likeComment', {
        ...reference.args,
        'commentId': textOf(targetComment['id']),
        'value': value,
      });
      if (mounted) {
        setState(() {
          targetComment['liked'] = value;
          if (count != null) {
            targetComment['like_count'] = (count + (value ? 1 : -1))
                .clamp(0, 0x7fffffff)
                .toString();
          }
          if (target != null && comment['sub_comments'] is List) {
            for (final item in comment['sub_comments'] as List) {
              if (item is! Map ||
                  textOf(item['id']) != textOf(targetComment['id'])) {
                continue;
              }
              item['liked'] = value;
              if (count != null) {
                item['like_count'] = targetComment['like_count'];
              }
              break;
            }
          }
        });
      }
    } catch (e) {
      if (mounted) showFailure(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> remove() async {
    if (busy) return;
    final yes = await showAppDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除这条评论？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (yes != true || !mounted) return;
    setState(() => busy = true);
    try {
      await app.api.call('deleteComment', {
        ...reference.args,
        'commentId': textOf(comment['id']),
      });
      if (mounted) {
        setState(() => deleted = true);
        widget.onDeleted?.call(textOf(comment['id']));
      }
    } catch (e) {
      if (mounted) showFailure(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (deleted) return const SizedBox.shrink();
    final user = objectOf(comment['user_info']);
    final children = objectsOf(comment['sub_comments']);
    final replies = children.take(3).toList();
    final replyCount =
        int.tryParse(textOf(comment['sub_comment_count'])) ?? children.length;
    final likeCount = textOf(comment['like_count']);
    final meta = _commentMeta(comment);
    final pictures = objectsOf(comment['pictures'])
        .where((image) => Note.imageUrl(image).isNotEmpty)
        .toList();
    final commentPics = [
      for (final pic in pictures)
        GalleryImage(
          Note.originalImageUrl(pic),
          width: int.tryParse(textOf(pic['width'])) ?? 0,
          height: int.tryParse(textOf(pic['height'])) ?? 0,
          raw: pic,
          thumbnailUrl: Note.imageUrl(pic),
        ),
    ];
    final theme = Theme.of(context);
    Widget replyPreview(Json reply) {
      final replyUser = objectOf(reply['user_info']);
      final name = textOf(replyUser['nickname']);
      final replyLikeCount = textOf(reply['like_count']);
      return Padding(
        padding: const EdgeInsets.only(top: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            UserAvatar(userImageUrl(replyUser), size: 30, api: app),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name.isEmpty ? '用户' : name,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 3),
                  XhsRichText(
                    textOf(reply['content']),
                    api: app,
                    onTopicTap: (topic) => openTopicSearch(context, app, topic),
                    style: theme.textTheme.bodyLarge?.copyWith(height: 1.4),
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          _commentMeta(reply),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                      TextButton(
                        onPressed: () => editComment(
                          context,
                          app,
                          reference,
                          targetId: textOf(reply['id']),
                          author: name,
                        ),
                        style: TextButton.styleFrom(
                          minimumSize: const Size(48, 48),
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          foregroundColor: theme.colorScheme.onSurfaceVariant,
                        ),
                        child: const Text('回复'),
                      ),
                      Tooltip(
                        message: '点赞回复',
                        child: TextButton.icon(
                          onPressed: busy ? null : () => like(reply),
                          style: TextButton.styleFrom(
                            minimumSize: const Size(48, 48),
                            padding: const EdgeInsets.symmetric(horizontal: 6),
                            foregroundColor: reply['liked'] == true
                                ? theme.colorScheme.primary
                                : theme.colorScheme.onSurfaceVariant,
                          ),
                          icon: Icon(
                            reply['liked'] == true
                                ? Icons.favorite_rounded
                                : Icons.favorite_border_rounded,
                            size: 19,
                          ),
                          label: Text(replyLikeCount),
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

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          UserAvatar(userImageUrl(user), size: 40, api: app),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  textOf(user['nickname']).isEmpty
                      ? '用户'
                      : textOf(user['nickname']),
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 6),
                XhsRichText(
                  textOf(comment['content']),
                  api: app,
                  onTopicTap: (topic) => openTopicSearch(context, app, topic),
                  style: theme.textTheme.bodyLarge?.copyWith(height: 1.45),
                ),
                for (var index = 0; index < commentPics.length; index++)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: InkWell(
                      onTap: () => Navigator.push(
                        context,
                        PhysicsSpringGalleryRoute<void>(
                          child: GalleryPage(
                            app: app,
                            pics: commentPics,
                            initial: index,
                            scope: galleryScope,
                            author: textOf(user['nickname']),
                          ),
                        ),
                      ),
                      child: ImageGalleryHeroThumbnail(
                        key: ValueKey(
                          'comment-image:${textOf(comment['id'])}:$index',
                        ),
                        scope: galleryScope,
                        index: index,
                        pic: commentPics[index],
                        child: SizedBox(
                          width: 120,
                          height: 100,
                          child: RemoteImage(
                            commentPics[index].url,
                            decodeWidth: 400,
                            api: app,
                          ),
                        ),
                      ),
                    ),
                  ),
                const SizedBox(height: 4),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        meta,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                    TextButton(
                      onPressed: () => editComment(
                        context,
                        app,
                        reference,
                        targetId: textOf(comment['id']),
                        author: textOf(user['nickname']),
                      ),
                      style: TextButton.styleFrom(
                        minimumSize: const Size(48, 48),
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                        foregroundColor: theme.colorScheme.onSurfaceVariant,
                      ),
                      child: const Text('回复'),
                    ),
                    if (textOf(user['user_id']).isNotEmpty &&
                        textOf(user['user_id']) ==
                            textOf(app.user['user_id'] ?? app.user['userid']))
                      TextButton(
                        onPressed: busy ? null : remove,
                        style: TextButton.styleFrom(
                          minimumSize: const Size(48, 48),
                          padding: const EdgeInsets.symmetric(horizontal: 6),
                          foregroundColor: theme.colorScheme.onSurfaceVariant,
                        ),
                        child: const Text('删除'),
                      ),
                    Tooltip(
                      message: '点赞评论',
                      child: TextButton.icon(
                        onPressed: busy ? null : like,
                        style: TextButton.styleFrom(
                          minimumSize: const Size(48, 48),
                          padding: const EdgeInsets.symmetric(horizontal: 6),
                          foregroundColor: comment['liked'] == true
                              ? theme.colorScheme.primary
                              : theme.colorScheme.onSurfaceVariant,
                        ),
                        icon: Icon(
                          comment['liked'] == true
                              ? Icons.favorite_rounded
                              : Icons.favorite_border_rounded,
                          size: 19,
                        ),
                        label: Text(likeCount),
                      ),
                    ),
                  ],
                ),
                for (final reply in replies) replyPreview(reply),
                if (replyCount > replies.length)
                  Padding(
                    padding: const EdgeInsets.only(top: 8, left: 2),
                    child: InkWell(
                      onTap: () => Navigator.push(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) => RepliesPage(
                            app: app,
                            reference: reference,
                            rootId: textOf(comment['id']),
                          ),
                        ),
                      ),
                      borderRadius: BorderRadius.circular(8),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          vertical: 8,
                          horizontal: 4,
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              width: 24,
                              height: 1,
                              color: theme.colorScheme.outlineVariant,
                            ),
                            const SizedBox(width: 8),
                            Text(
                              '展开$replyCount条回复',
                              style: theme.textTheme.bodyMedium?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                                fontWeight: FontWeight.w600,
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
        ],
      ),
    );
  }
}

String _commentMeta(Json comment) {
  final timestamp = int.tryParse(textOf(comment['create_time'])) ?? 0;
  var time = '';
  if (timestamp > 0) {
    final millis = timestamp < 100000000000 ? timestamp * 1000 : timestamp;
    final date = DateTime.fromMillisecondsSinceEpoch(millis);
    final now = DateTime.now();
    final elapsed = now.difference(date);
    final age = elapsed.isNegative ? Duration.zero : elapsed;
    if (age < const Duration(minutes: 1)) {
      time = '刚刚';
    } else if (age < const Duration(hours: 1)) {
      time = '${age.inMinutes}分钟前';
    } else if (age < const Duration(days: 1)) {
      time = '${age.inHours}小时前';
    } else if (age < const Duration(days: 30)) {
      time = '${age.inDays}天前';
    } else {
      final month = date.month.toString().padLeft(2, '0');
      final day = date.day.toString().padLeft(2, '0');
      time = date.year == now.year ? '$month-$day' : '${date.year}-$month-$day';
    }
  }
  final location = textOf(comment['ip_location'])
      .replaceFirst(RegExp(r'^IP属地[:：]\s*'), '')
      .trim();
  return [time, location].where((value) => value.isNotEmpty).join('  ');
}

class _CommentFooterAction extends StatelessWidget {
  const _CommentFooterAction({
    required this.tooltip,
    required this.icon,
    required this.selectedIcon,
    required this.count,
    required this.onPressed,
    this.selected = false,
  });
  final String tooltip;
  final IconData icon;
  final IconData selectedIcon;
  final String count;
  final VoidCallback? onPressed;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final color = selected ? colors.primary : colors.onSurfaceVariant;
    return Tooltip(
      message: tooltip,
      child: Semantics(
        button: true,
        selected: selected,
        label: '$tooltip $count',
        child: InkWell(
          onTap: onPressed,
          borderRadius: BorderRadius.circular(16),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 3, vertical: 2),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(selected ? selectedIcon : icon, size: 24, color: color),
                const SizedBox(height: 1),
                Flexible(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      count,
                      maxLines: 1,
                      style: TextStyle(
                        fontSize: 9,
                        fontWeight: FontWeight.w600,
                        color: color,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class RepliesPage extends StatefulWidget {
  const RepliesPage({
    super.key,
    required this.app,
    required this.reference,
    required this.rootId,
  });
  final AppState app;
  final NoteRef reference;
  final String rootId;
  @override
  State<RepliesPage> createState() => _RepliesPageState();
}

class _RepliesPageState extends State<RepliesPage> {
  final replies = <Json>[];
  final seen = <String>{};
  String cursor = '';
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
      final data = await widget.app.api.call('subComments', {
        ...widget.reference.args,
        'rootId': widget.rootId,
        'cursor': cursor,
      });
      if (!mounted) return;
      final items = objectsOf(data['comments']);
      for (final item in items) {
        if (seen.add(textOf(item['id']))) replies.add(item);
      }
      final next = textOf(data['cursor']);
      more =
          data['has_more'] != false &&
          items.isNotEmpty &&
          next.isNotEmpty &&
          next != cursor;
      cursor = next;
    } catch (e) {
      if (mounted) failure = e;
    }
    if (mounted) {
      setState(() {
        busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: ReviewFrostedAppBar(title: const Text('回复')),
    body: ListView(
      children: [
        for (final reply in replies)
          CommentTile(
            key: ValueKey(textOf(reply['id'])),
            comment: reply,
            app: widget.app,
            reference: widget.reference,
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
            title: '回复加载失败',
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
              child: Text(more ? '加载更多' : '回复已全部加载'),
            ),
          ),
      ],
    ),
  );
}
