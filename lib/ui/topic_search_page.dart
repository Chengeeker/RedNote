import 'package:flutter/material.dart';

import '../app_state.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import 'feed_page.dart';

Future<void> openTopicSearch(
  BuildContext context,
  AppState app,
  String topic,
) async {
  final value = topic.trim();
  if (value.isEmpty) return;
  await Navigator.of(context).push<void>(
    MaterialPageRoute<void>(
      builder: (_) => TopicSearchPage(app: app, topic: value),
    ),
  );
}

class TopicSearchPage extends StatelessWidget {
  TopicSearchPage({super.key, required this.app, required this.topic})
    : searchId = DateTime.now().microsecondsSinceEpoch.toRadixString(36);

  final AppState app;
  final String topic;
  final String searchId;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: ReviewFrostedAppBar(
      leading: const BackButton(),
      centerTitle: true,
      showBottomBorder: false,
      title: Text('#$topic#'),
    ),
    body: FeedPage(
      key: ValueKey(searchId),
      app: app,
      method: 'search',
      source: 'pc_search',
      args: {
        'keyword': topic,
        'searchId': searchId,
        'sort': 'general',
        'noteType': 0,
      },
    ),
  );
}
