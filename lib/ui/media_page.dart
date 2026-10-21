import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../features/media/image_gallery_page.dart';
import '../features/media/video_player_page.dart';

class GalleryPage extends StatelessWidget {
  const GalleryPage({
    super.key,
    required this.app,
    required this.pics,
    required this.initial,
    required this.scope,
    this.author,
  });
  final AppState app;
  final List<GalleryImage> pics;
  final String? author;
  final int initial;
  final Object scope;
  @override
  Widget build(BuildContext context) => ImageGalleryPage(
    app: app,
    pics: pics,
    authorName: author,
    initialIndex: initial,
    statusId: '',
    heroScope: scope,
    useLiquidGlass: app.liquidGlass,
  );
}

class PlayerPage extends StatelessWidget {
  const PlayerPage({
    super.key,
    required this.app,
    required this.sources,
    this.title,
    this.author,
  });
  final AppState app;
  final List<VideoSource> sources;
  final String? title;
  final String? author;
  @override
  Widget build(BuildContext context) => RedVideoPlayerPage(
    app: app,
    sources: sources,
    videoUrl: sources.first.url,
    title: title,
    authorName: author,
  );
}
