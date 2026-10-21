import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import 'common.dart';

class UserProfileHeader extends StatelessWidget {
  const UserProfileHeader({
    super.key,
    required this.app,
    required this.user,
    required this.profile,
    this.actions = const [],
    this.topInset = false,
    this.busy = false,
    this.failure,
    this.onRetry,
  });
  final AppState app;
  final Json user;
  final Json profile;
  final List<Widget> actions;
  final bool topInset;
  final bool busy;
  final Object? failure;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final basic = {
      ...user,
      ...objectOf(profile['basic_info'] ?? profile['basicInfo']),
    };
    final background = profileBackgroundUrl(profile);
    final foreground = background.isEmpty
        ? theme.colorScheme.onSurface
        : Colors.white;
    final secondary = background.isEmpty
        ? theme.colorScheme.onSurfaceVariant
        : Colors.white.withValues(alpha: 0.82);
    final stats = objectsOf(profile['interactions']);
    final tags = objectsOf(profile['tags']);
    final redId = textOf(basic['red_id'] ?? basic['redId']);
    final name = textOf(basic['nickname'] ?? basic['nick_name']);
    final description = textOf(basic['desc'] ?? basic['description']);
    return Stack(
      children: [
        if (background.isNotEmpty) ...[
          Positioned.fill(child: RemoteImage(background, api: app)),
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.35),
                    Colors.black.withValues(alpha: 0.78),
                  ],
                ),
              ),
            ),
          ),
        ],
        SafeArea(
          top: topInset,
          bottom: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (actions.isNotEmpty)
                  IconTheme(
                    data: IconThemeData(color: foreground),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: actions,
                    ),
                  ),
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    UserAvatar(userImageUrl(basic), size: 84, api: app),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            name.isEmpty
                                ? (app.loggedIn ? '我的主页' : '欢迎使用 RedNote')
                                : name,
                            style: theme.textTheme.headlineSmall?.copyWith(
                              color: foreground,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          if (redId.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 6),
                              child: SelectableText(
                                '小红书号：$redId',
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: secondary,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
                if (stats.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 24),
                    child: Wrap(
                      spacing: 20,
                      runSpacing: 10,
                      children: [
                        for (final stat in stats)
                          if (textOf(stat['count'] ?? stat['i18n_count'])
                              .isNotEmpty)
                            Text.rich(
                              TextSpan(
                                children: [
                                  TextSpan(
                                    text: textOf(
                                      stat['i18n_count'] ?? stat['count'],
                                    ),
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                  TextSpan(
                                    text:
                                        ' ${textOf(stat['name']).isNotEmpty ? textOf(stat['name']) : const {'follows': '关注', 'fans': '粉丝', 'interaction': '获赞与收藏'}[textOf(stat['type'])] ?? ''}',
                                  ),
                                ],
                              ),
                              style: theme.textTheme.bodyLarge?.copyWith(
                                color: foreground,
                              ),
                            ),
                      ],
                    ),
                  ),
                if (description.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 18),
                    child: SelectableText(
                      description,
                      style: theme.textTheme.bodyLarge?.copyWith(
                        color: foreground,
                        height: 1.4,
                      ),
                    ),
                  ),
                if (tags.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 14),
                    child: Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        for (final tag in tags)
                          if (textOf(tag['name']).isNotEmpty)
                            DecoratedBox(
                              decoration: BoxDecoration(
                                color: background.isEmpty
                                    ? theme.colorScheme.surfaceContainerHigh
                                    : Colors.white.withValues(alpha: 0.16),
                                borderRadius: BorderRadius.circular(20),
                              ),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 6,
                                ),
                                child: Text(
                                  textOf(tag['name']),
                                  style: theme.textTheme.labelMedium?.copyWith(
                                    color: foreground,
                                  ),
                                ),
                              ),
                            ),
                      ],
                    ),
                  ),
                if (busy)
                  const Padding(
                    padding: EdgeInsets.only(top: 16),
                    child: LinearProgressIndicator(),
                  ),
                if (failure != null)
                  TextButton.icon(
                    onPressed: onRetry,
                    icon: const Icon(Icons.refresh),
                    label: Text('资料加载失败：${errorText(failure!)}'),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
