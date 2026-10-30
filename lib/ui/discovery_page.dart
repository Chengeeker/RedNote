import 'dart:convert';

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import 'feed_page.dart';

class DiscoveryPage extends StatefulWidget {
  const DiscoveryPage({
    super.key,
    required this.app,
    this.topPadding = 0,
    this.onChromeChanged,
  });
  final AppState app;
  final double topPadding;
  final VoidCallback? onChromeChanged;

  @override
  State<DiscoveryPage> createState() => DiscoveryPageState();
}

class DiscoveryPageState extends State<DiscoveryPage> {
  final feed = GlobalKey<FeedPageState>();
  List<Json> categories = [];
  String category = 'homefeed_recommend';
  bool busy = false;
  Object? failure;

  @override
  void initState() {
    super.initState();
    category =
        widget.app.preferences.getString(
          'discoveryCategory:${textOf(widget.app.user["user_id"])}',
        ) ??
        'homefeed_recommend';
    try {
      categories = objectsOf(
        jsonDecode(
          widget.app.preferences.getString('discoveryCategories') ?? '[]',
        ),
      );
    } on FormatException {
      categories = [];
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onChromeChanged?.call();
    });
    loadCategories();
  }

  Future<void> loadCategories() async {
    if (busy) return;
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      final data = await widget.app.api.call('homeCategories');
      final received = objectsOf(data['categories'])
          .where(
            (item) =>
                textOf(item['id']).isNotEmpty &&
                textOf(item['name']).isNotEmpty,
          )
          .toList();
      if (received.isEmpty) throw StateError('服务器未返回推荐分类');
      if (mounted) setState(() => categories = received);
      await widget.app.preferences.setString(
        'discoveryCategories',
        jsonEncode(received),
      );
    } catch (error) {
      if (mounted) setState(() => failure = error);
    } finally {
      if (mounted) {
        setState(() => busy = false);
        widget.onChromeChanged?.call();
      }
    }
  }

  void toTop() => feed.currentState?.toTop();
  Future<void> refresh() async {
    await feed.currentState?.refresh();
    if (failure != null) await loadCategories();
  }

  PreferredSizeWidget buildCategoryBar(BuildContext context) {
    final choices = <Json>[
      {'id': 'homefeed_recommend', 'name': '推荐'},
      ...categories.where(
        (choice) => textOf(choice['id']) != 'homefeed_recommend',
      ),
      if (categories.isEmpty)
        for (final name in ['穿搭', '美食', '彩妆', '影视', '职场']) {'name': name},
    ];
    return PreferredSize(
      preferredSize: const Size.fromHeight(40),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(
          children: [
            for (final choice in choices)
              TextButton(
                onPressed: textOf(choice['id']).isEmpty
                    ? null
                    : () {
                        setState(() => category = textOf(choice['id']));
                        widget.onChromeChanged?.call();
                        widget.app.preferences.setString(
                          'discoveryCategory:${textOf(widget.app.user["user_id"])}',
                          category,
                        );
                      },
                style: TextButton.styleFrom(
                  foregroundColor: category == textOf(choice['id'])
                      ? Theme.of(context).colorScheme.onSurface
                      : Theme.of(context).colorScheme.onSurfaceVariant,
                  minimumSize: const Size(48, 40),
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                child: Text(
                  textOf(choice['name']),
                  style: TextStyle(
                    fontWeight: category == textOf(choice['id'])
                        ? FontWeight.w700
                        : FontWeight.normal,
                  ),
                ),
              ),
            if (failure != null)
              TextButton.icon(
                onPressed: busy ? null : loadCategories,
                style: TextButton.styleFrom(
                  minimumSize: const Size(48, 40),
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('分类加载失败，重试'),
              ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final feedPage = FeedPage(
      key: feed,
      app: widget.app,
      args: {'category': category},
      topPadding: widget.onChromeChanged == null ? 0 : widget.topPadding,
    );
    return widget.onChromeChanged == null
        ? Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              buildCategoryBar(context),
              Expanded(child: feedPage),
            ],
          )
        : feedPage;
  }
}
