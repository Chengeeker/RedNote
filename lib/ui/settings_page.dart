import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import '../core/design_system/components/review_page_scaffold.dart';
import '../core/design_system/components/review_preference_tile.dart';
import '../core/design_system/components/review_switch_tile.dart';
import '../core/design_system/components/review_choice_tile.dart';
import '../core/utils/haptic_feedback_util.dart';
import '../core/storage/storage_path_resolver.dart';
import '../core/widgets/app_section_card.dart';
import '../core/theme/app_theme.dart';
import '../core/utils/app_dialog.dart';
import 'common.dart';
import 'cache_page.dart';

Widget _sectionTitle(BuildContext context, String title) => Text(
  title,
  style: Theme.of(context).textTheme.titleSmall
      ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
);

Widget _cardTitle(BuildContext context, String title) => Padding(
  padding: const EdgeInsets.fromLTRB(16, 14, 16, 4),
  child: Text(
    title,
    style: TextStyle(
      fontSize: 15,
      fontWeight: context.adjustWeight(FontWeight.bold),
      color: Theme.of(context).colorScheme.onSurfaceVariant,
      letterSpacing: 0,
    ),
  ),
);

class SettingsHubPage extends StatelessWidget {
  const SettingsHubPage({super.key, required this.app});
  final AppState app;
  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    Widget link(String title, IconData icon, Widget page, {String? subtitle}) =>
        ReviewPreferenceTile(
          leading: Icon(icon, color: scheme.onSurfaceVariant),
          title: title,
          subtitle: subtitle,
          onTap: () {
            HapticFeedbackUtil.light();
            Navigator.push(
              context,
              MaterialPageRoute<void>(builder: (_) => page),
            );
          },
        );
    return ReviewPageScaffold(
      title: '设置',
      bodyBuilder: (context, insets) => ListView(
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 24).add(insets),
        children: [
          _sectionTitle(context, '偏好与功能'),
          const SizedBox(height: 12),
          AppSectionCard(
            child: link('个性化', Icons.palette_outlined, SettingsPage(app: app)),
          ),
          const SizedBox(height: 32),
          _sectionTitle(context, '存储与备份'),
          const SizedBox(height: 12),
          AppSectionCard(
            child: Column(
              children: [
                link(
                  '存储设置',
                  Icons.folder_open_outlined,
                  StorageSettingsPage(app: app),
                ),
                const Divider(height: 1, indent: 56),
                link(
                  '缓存管理',
                  Icons.cleaning_services_outlined,
                  const CachePage(),
                  subtitle: '查看与清理媒体缓存',
                ),
              ],
            ),
          ),
          const SizedBox(height: 32),
          _sectionTitle(context, '账号设置'),
          const SizedBox(height: 12),
          AppSectionCard(
            child: link(
              '账号与连接',
              Icons.manage_accounts_outlined,
              ConnectionPage(app: app),
            ),
          ),
          const SizedBox(height: 32),
          _sectionTitle(context, '关于与支持'),
          const SizedBox(height: 12),
          AppSectionCard(
            child: ReviewPreferenceTile(
              title: '关于 RedNote',
              subtitle: '版本 0.3.21',
              leading: Icon(
                Icons.info_outline_rounded,
                color: scheme.onSurfaceVariant,
              ),
              onTap: () => showAppDialog<void>(
                context: context,
                builder: (ctx) => AlertDialog(
                  icon: const Icon(Icons.info_outline_rounded),
                  title: const Text('RedNote 0.3.21'),
                  content: const SingleChildScrollView(
                    child: Text(
                      '个人使用的小红书第三方客户端，非官方应用。\n\n网页登录由官方页面处理，应用不接收密码。登录过期或需要验证码时，请在官方网页内手动完成。\n\n网页与接口变化可能影响功能。',
                    ),
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(ctx),
                      child: const Text('关闭'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.app});
  final AppState app;
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  double? previewScale;
  Future<bool> save(String key, Object value) async {
    try {
      await widget.app.setSetting(key, value);
      return true;
    } catch (e) {
      if (mounted) showFailure(context, e);
      return false;
    }
  }

  String weightLabel() => !widget.app.customWeight
      ? '跟随系统'
      : const {
              -300: '极细',
              -200: '细',
              -100: '偏细',
              0: '默认',
              100: '中等',
              200: '偏粗',
              300: '加粗',
            }[widget.app.weightDelta] ??
            '${widget.app.weightDelta}';

  Future<void> chooseWeight() async {
    HapticFeedbackUtil.light();
    final value = await showAppDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('选择字体粗细'),
        contentPadding: const EdgeInsets.symmetric(vertical: 12),
        content: SingleChildScrollView(
          child: RadioGroup<int>(
            groupValue: widget.app.customWeight
                ? widget.app.weightDelta
                : -1000,
            onChanged: (value) {
              if (value != null) Navigator.pop(ctx, value);
            },
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final entry in const {
                  -1000: '跟随系统',
                  -300: '极细',
                  -200: '细',
                  -100: '偏细',
                  0: '默认',
                  100: '中等',
                  200: '偏粗',
                  300: '加粗',
                }.entries)
                  RadioListTile<int>(
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 2,
                    ),
                    title: Text(
                      entry.value,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    value: entry.key,
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
        ],
      ),
    );
    if (value == null || !mounted) return;
    if (value != -1000 && !await save('weightDelta', value)) return;
    if (mounted) await save('customWeight', value != -1000);
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.app,
    builder: (context, _) {
      final app = widget.app;
      final scheme = Theme.of(context).colorScheme;
      Widget toggle(
        String title,
        String key,
        bool value,
        IconData icon, {
        String? summary,
      }) => ReviewSwitchTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 3),
        leading: Icon(icon, color: scheme.primary),
        title: title,
        summary: summary,
        value: value,
        onChanged: (v) {
          HapticFeedbackUtil.light();
          save(key, v);
        },
      );
      return ReviewPageScaffold(
        title: '个性化',
        bodyBuilder: (context, insets) => ListView(
          padding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 12,
          ).add(insets),
          children: [
            AppSectionCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _cardTitle(context, '明暗模式'),
                  for (final mode in ThemeMode.values) ...[
                    ReviewChoiceTile<ThemeMode>(
                      title: const ['跟随系统', '浅色模式', '深色模式'][mode.index],
                      summary: mode == ThemeMode.system
                          ? '自动匹配系统深色/浅色设置'
                          : null,
                      value: mode,
                      groupValue: app.themeMode,
                      onSelected: () => save('theme', mode.index),
                    ),
                    if (mode != ThemeMode.dark)
                      const Divider(height: 1, indent: 72),
                  ],
                  const Divider(height: 1, indent: 16, endIndent: 16),
                  toggle(
                    '纯黑深色模式',
                    'pureBlack',
                    app.pureBlack,
                    Icons.contrast_rounded,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            AppSectionCard(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 6, 16, 4),
                      child: Text(
                        '色彩方案',
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: context.adjustWeight(FontWeight.bold),
                          color: scheme.onSurfaceVariant,
                          letterSpacing: 0,
                        ),
                      ),
                    ),
                    toggle(
                      'Material You (Monet) 动态取色',
                      'dynamicColor',
                      app.dynamicColor,
                      Icons.auto_awesome_outlined,
                      summary: '从 Android 12+ 壁纸自动提取主色调',
                    ),
                    if (!app.dynamicColor) ...[
                      const Divider(height: 1, indent: 16, endIndent: 16),
                      Padding(
                        padding: const EdgeInsets.only(
                          left: 16,
                          top: 14,
                          bottom: 10,
                        ),
                        child: Text(
                          'Material 3 预置主题色',
                          style: TextStyle(
                            fontSize: 14,
                            color: scheme.onSurfaceVariant,
                            height: 1.35,
                            letterSpacing: 0,
                          ),
                        ),
                      ),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        child: Wrap(
                          spacing: 12,
                          runSpacing: 12,
                          children: [
                            for (
                              var i = 0;
                              i < AppTheme.themeColors.length;
                              i++
                            )
                              ChoiceChip(
                                avatar: CircleAvatar(
                                  backgroundColor: AppTheme.getSeedColor(i),
                                  radius: 10,
                                ),
                                label: Text(
                                  AppTheme.themeColors[i]['name'] as String,
                                ),
                                selected: app.colorIndex == i,
                                onSelected: (_) {
                                  HapticFeedbackUtil.light();
                                  save('color', i);
                                },
                              ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 8),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            AppSectionCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _cardTitle(context, '字体粗细'),
                  ListTile(
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 3,
                    ),
                    leading: Icon(
                      Icons.format_bold_rounded,
                      color: scheme.primary,
                    ),
                    title: Text(
                      '自定义应用字体粗细',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: context.adjustWeight(FontWeight.w600),
                        height: 1.35,
                        letterSpacing: 0,
                      ),
                    ),
                    subtitle: Text(
                      '当前：${weightLabel()}',
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.4,
                        letterSpacing: 0,
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: chooseWeight,
                  ),
                  const Divider(height: 1, indent: 16, endIndent: 16),
                  Container(
                    margin: const EdgeInsets.fromLTRB(16, 12, 16, 14),
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: scheme.surfaceContainerHighest.withValues(
                        alpha: 0.3,
                      ),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '效果预览 (${weightLabel()})',
                          style: TextStyle(
                            fontSize: 11.5,
                            fontWeight: context.adjustWeight(FontWeight.w600),
                            color: scheme.primary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'RedNote 极简现代体验：这是一段用于预览字体粗细的示例文本。@开发者 #话题#',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: context.adjustWeight(FontWeight.normal),
                            letterSpacing: 0,
                            height: 1.4,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.fromLTRB(4, 0, 4, 12),
              child: Text(
                '字体大小',
                style: Theme.of(context).textTheme.titleSmall
                    ?.copyWith(color: scheme.primary),
              ),
            ),
            AppSectionCard(
              child: ListTile(
                leading: Icon(Icons.format_size_rounded, color: scheme.primary),
                title: const Text('应用字体大小'),
                subtitle: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${((previewScale ?? app.fontScale) * 100).round()}%'),
                    Slider(
                      key: const ValueKey('app-text-scale-slider'),
                      value: previewScale ?? app.fontScale,
                      min: 0.85,
                      max: 1.5,
                      divisions: 13,
                      label:
                          '${((previewScale ?? app.fontScale) * 100).round()}%',
                      onChanged: (v) => setState(() => previewScale = v),
                      onChangeEnd: (v) async {
                        await save('fontScale', v);
                        if (mounted) setState(() => previewScale = null);
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            AppSectionCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _cardTitle(context, '底部导航'),
                  toggle(
                    '使用悬浮底栏',
                    'floating',
                    app.floating,
                    Icons.dock_rounded,
                    summary: '居中显示悬浮式导航栏',
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            AppSectionCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _cardTitle(context, '显示效果'),
                  toggle(
                    '使用液态玻璃效果',
                    'liquidGlass',
                    app.liquidGlass,
                    Icons.blur_on_rounded,
                    summary: '控制液态玻璃控件；用于悬浮底栏和图库操作按钮',
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            AppSectionCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _cardTitle(context, '触感与震动'),
                  toggle(
                    '触感与震动反馈',
                    'haptics',
                    app.haptics,
                    Icons.vibration_rounded,
                    summary: '按键、卡片点击、手势滑动与底栏切换的触感反馈',
                  ),
                ],
              ),
            ),
          ],
        ),
      );
    },
  );
}

class StorageSettingsPage extends StatelessWidget {
  const StorageSettingsPage({super.key, required this.app});
  final AppState app;
  String path(int type, bool video) {
    if (type == 2) return '${video ? 'Movies' : 'Pictures'}/RedNote/笔记作者昵称/';
    return '${video ? 'Movies' : 'Pictures'}/${StoragePathResolver.resolveSubDir(pathType: type, myNickname: textOf(app.user['nickname']), isVideo: video)}/';
  }

  Future<void> choose(BuildContext context, bool video) async {
    HapticFeedbackUtil.light();
    final selected = await showModalBottomSheet<int>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        video ? '视频存储路径' : '图片存储路径',
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: '关闭',
                      onPressed: () => Navigator.pop(ctx),
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  '保存${video ? '视频' : '图片'}时，文件将自动归类并存储至相册对应文件夹下。',
                  style: TextStyle(
                    fontSize: 14,
                    color: Theme.of(ctx).colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 12),
                RadioGroup<int>(
                  groupValue: app.savePath(video),
                  onChanged: (v) {
                    if (v != null) Navigator.pop(ctx, v);
                  },
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (var i = 0; i < 3; i++)
                        RadioListTile<int>(
                          value: i,
                          title: Text(
                            const ['默认路径', '按本人昵称前缀归类', '按博主昵称前缀归类'][i],
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 16,
                            ),
                          ),
                          subtitle: Text(
                            path(i, video),
                            style: const TextStyle(fontSize: 14),
                          ),
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: 8),
              ],
            ),
          ),
        ),
      ),
    );
    if (selected == null || !context.mounted) return;
    try {
      await app.setSetting(video ? 'videoPath' : 'imagePath', selected);
    } catch (e) {
      if (context.mounted) showFailure(context, e);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: app,
    builder: (context, _) => ReviewPageScaffold(
      title: '存储设置',
      bodyBuilder: (context, insets) => ListView(
        padding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 12,
        ).add(insets),
        children: [
          AppSectionCard(
            child: Column(
              children: [
                for (final video in [false, true]) ...[
                  if (video) const Divider(height: 1, indent: 56),
                  ListTile(
                    leading: Icon(
                      video ? Icons.videocam_outlined : Icons.image_outlined,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                    title: Text(
                      video ? '视频存储路径' : '图片存储路径',
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 16,
                      ),
                    ),
                    subtitle: Text(
                      path(app.savePath(video), video),
                      style: TextStyle(
                        fontSize: 14,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                    trailing: Icon(
                      Icons.chevron_right_rounded,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                    onTap: () => choose(context, video),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class ConnectionPage extends StatefulWidget {
  const ConnectionPage({super.key, required this.app});
  final AppState app;
  @override
  State<ConnectionPage> createState() => _ConnectionPageState();
}

class _ConnectionPageState extends State<ConnectionPage> {
  @override
  void initState() {
    super.initState();
    refresh();
  }

  Future<void> refresh() async {
    try {
      await widget.app.refreshSession();
    } catch (e) {
      if (mounted) showFailure(context, e);
    }
  }

  Future<void> logout() async {
    final yes = await showAppDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('退出账号？'),
        content: const Text('将清除本应用保存的登录状态，外观设置保留。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('退出'),
          ),
        ],
      ),
    );
    if (yes != true) return;
    try {
      await widget.app.logout();
      if (mounted) Navigator.popUntil(context, (route) => route.isFirst);
    } catch (e) {
      if (mounted) showFailure(context, e);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.app,
    builder: (context, _) {
      final app = widget.app;
      final last = objectOf(app.session['lastRequest']);
      return Scaffold(
        extendBodyBehindAppBar: true,
        appBar: ReviewFrostedAppBar(
          title: const Text('账号与连接'),
          actions: [
            IconButton(
              tooltip: '刷新状态',
              onPressed: refresh,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        body: ListView(
          padding: EdgeInsets.fromLTRB(
            16,
            MediaQuery.paddingOf(context).top + kToolbarHeight + 12,
            16,
            32,
          ),
          children: [
            _sectionTitle(context, '登录与会话'),
            const SizedBox(height: 12),
            AppSectionCard(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      app.loggedIn ? '已保存登录状态' : '尚未登录',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 12),
                    const Text('请在官方网页完成扫码或验证码。登录校验通过后才保存新会话。'),
                    const SizedBox(height: 16),
                    FilledButton.icon(
                      onPressed: app.loginBusy
                          ? null
                          : () => signIn(context, app),
                      icon: const Icon(Icons.login),
                      label: Text(app.loggedIn ? '重新网页登录' : '网页登录'),
                    ),
                  ],
                ),
              ),
            ),
            if (app.loggedIn) ...[
              const SizedBox(height: 16),
              AppSectionCard(
                child: Column(
                  children: [
                    ListTile(
                      title: const Text('账号 ID'),
                      subtitle: SelectableText(
                        textOf(app.user['user_id'] ?? app.user['userid']),
                      ),
                    ),
                    ListTile(
                      title: const Text('站点'),
                      subtitle: Text(
                        app.session['region'] == 'rednote' ? 'Rednote' : '小红书',
                      ),
                    ),
                  ],
                ),
              ),
            ],
            if (last.isNotEmpty) ...[
              const SizedBox(height: 16),
              AppSectionCard(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '最近一次请求',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 8),
                      Text(textOf(last['path'])),
                      Text('HTTP ${last['http']} · ${last['elapsedMs']} ms'),
                      if (app.session['blocked'] != null)
                        const Text('请求已暂停，请重新网页登录并完成验证。'),
                    ],
                  ),
                ),
              ),
            ],
            if (app.error != null)
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(app.error!),
              ),
            if (app.loggedIn) ...[
              const SizedBox(height: 24),
              AppSectionCard(
                child: ListTile(
                  leading: const Icon(
                    Icons.logout_rounded,
                    color: Colors.redAccent,
                  ),
                  title: const Text(
                    '退出登录',
                    style: TextStyle(
                      color: Colors.redAccent,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  onTap: logout,
                ),
              ),
            ],
          ],
        ),
      );
    },
  );
}
