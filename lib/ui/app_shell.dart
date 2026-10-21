import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import '../app_state.dart';
import '../core/design_system/components/review_navigation_bar.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import 'common.dart';
import 'feed_page.dart';
import 'account_page.dart';
import 'discovery_page.dart';
import 'notifications_page.dart';
import 'search_users_page.dart';
import '../data/red_api.dart';
import '../core/utils/app_dialog.dart';

class AppShell extends StatefulWidget {
  const AppShell({super.key, required this.app});
  final AppState app;
  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell>
    with SingleTickerProviderStateMixin {
  int selected = 0, previous = 0;
  DateTime? lastTap;
  late final AnimationController animation;
  final feed = GlobalKey<DiscoveryPageState>();
  final search = GlobalKey<SearchPageState>();
  final visited = <int>{0};
  @override
  void initState() {
    super.initState();
    animation = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 260),
      value: 1,
    );
  }

  @override
  void dispose() {
    animation.dispose();
    super.dispose();
  }

  void select(int next) {
    if (next == selected) {
      final now = DateTime.now();
      final twice =
          lastTap != null &&
          now.difference(lastTap!) < const Duration(milliseconds: 300);
      lastTap = now;
      if (next == 0) {
        feed.currentState?.toTop();
        if (twice) feed.currentState?.refresh();
      }
      if (next == 3) search.currentState?.toTop(refresh: twice);
      return;
    }
    lastTap = null;
    setState(() {
      previous = selected;
      selected = next;
      visited.add(next);
    });
    if (MediaQuery.disableAnimationsOf(context)) {
      animation.value = 1;
    } else {
      animation.forward(from: 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      DiscoveryPage(key: feed, app: widget.app),
      NotificationsPage(
        key: ValueKey('messages:${widget.app.epoch}'),
        app: widget.app,
      ),
      AccountPage(
        key: ValueKey('account:${widget.app.epoch}'),
        app: widget.app,
      ),
      SearchPage(key: search, app: widget.app, onBack: () => select(0)),
    ];
    final glass = widget.app.liquidGlass && widget.app.floating;
    final scaffold = Scaffold(
      extendBody: widget.app.floating,
      appBar: selected == 1 || selected == 2 || selected == 3
          ? null
          : ReviewFrostedAppBar(
              title: Text(selected == 0 ? '发现' : '搜索'),
              centerTitle: selected == 0,
              leading: selected == 3
                  ? IconButton(
                      tooltip: '返回发现',
                      onPressed: () => select(0),
                      icon: const Icon(Icons.arrow_back),
                    )
                  : null,
              actions: [
                if (selected == 0)
                  IconButton(
                    tooltip: '搜索笔记',
                    onPressed: () => select(3),
                    icon: const Icon(Icons.search),
                  ),
                if (!widget.app.loggedIn)
                  TextButton(
                    onPressed: widget.app.loginBusy
                        ? null
                        : () => signIn(context, widget.app),
                    child: const Text('登录'),
                  ),
              ],
            ),
      body: GlassBackgroundSource(
        enabled: glass,
        child: AnimatedBuilder(
          animation: animation,
          builder: (context, _) {
            final t = Curves.easeOutCubic.transform(animation.value);
            final direction =
                (selected > previous ? 1.0 : -1.0) *
                (Directionality.of(context) == TextDirection.ltr ? 1 : -1);
            return ClipRect(
              child: Stack(
                children: [
                  for (var i = 0; i < pages.length; i++)
                    Offstage(
                      offstage:
                          i != selected &&
                          (animation.isCompleted || i != previous),
                      child: TickerMode(
                        enabled: i == selected,
                        child: IgnorePointer(
                          ignoring: i != selected,
                          child: FractionalTranslation(
                            translation: Offset(
                              animation.isCompleted
                                  ? 0
                                  : i == selected
                                  ? (1 - t) * direction
                                  : -t * direction,
                              0,
                            ),
                            child: RepaintBoundary(
                              child: visited.contains(i)
                                  ? pages[i]
                                  : const SizedBox.shrink(),
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            );
          },
        ),
      ),
      bottomNavigationBar: selected == 3
          ? null
          : ReviewNavigationBar(
              selectedIndex: selected,
              onSelected: select,
              hasBottomInset: MediaQuery.viewPaddingOf(context).bottom > 0,
            ),
    );
    return PopScope(
      canPop: selected != 3,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && selected == 3) select(0);
      },
      child: glass ? GlassPage(child: scaffold) : scaffold,
    );
  }
}

class SearchPage extends StatefulWidget {
  const SearchPage({super.key, required this.app, required this.onBack});
  final AppState app;
  final VoidCallback onBack;
  @override
  State<SearchPage> createState() => SearchPageState();
}

class SearchPageState extends State<SearchPage> {
  final input = TextEditingController();
  final feed = GlobalKey<FeedPageState>();
  final history = <String>[];
  List<String> suggestionValues = [];
  Timer? suggestionTimer;
  String keyword = '', searchId = '', sort = 'general';
  int noteType = 0;
  bool users = false, busySuggestions = false;

  @override
  void initState() {
    super.initState();
    history.addAll(widget.app.preferences.getStringList('searchHistory') ?? []);
    if (widget.app.loggedIn) unawaited(loadHistory());
  }

  @override
  void dispose() {
    suggestionTimer?.cancel();
    input.dispose();
    super.dispose();
  }

  void toTop({required bool refresh}) {
    feed.currentState?.toTop();
    if (refresh) feed.currentState?.refresh();
  }

  Future<void> submit() async {
    final value = input.text.trim();
    if (value.isEmpty) return;
    suggestionTimer?.cancel();
    FocusScope.of(context).unfocus();
    setState(() {
      keyword = value;
      users = false;
      suggestionValues = [];
      history.remove(value);
      history.insert(0, value);
      if (history.length > 20) history.removeRange(20, history.length);
      searchId =
          '${DateTime.now().millisecondsSinceEpoch.toRadixString(36)}${Random.secure().nextInt(1 << 30).toRadixString(36)}';
    });
    await widget.app.preferences.setStringList('searchHistory', history);
  }

  void scheduleSuggestions(String value) {
    suggestionTimer?.cancel();
    if (keyword.isNotEmpty || value.trim().isEmpty) {
      if (suggestionValues.isNotEmpty) {
        setState(() => suggestionValues = []);
      }
      return;
    }
    suggestionTimer = Timer(const Duration(milliseconds: 350), () {
      unawaited(loadSuggestions(value.trim()));
    });
  }

  Future<void> loadSuggestions(String value) async {
    if (busySuggestions || value.isEmpty) return;
    setState(() => busySuggestions = true);
    try {
      final data = await widget.app.api.call('suggestions', {'keyword': value});
      final items = objectsOf(data['sug_items']);
      final values = items
          .map(
            (x) => textOf(x['query'] ?? x['keyword'] ?? x['text'] ?? x['name']),
          )
          .where((x) => x.isNotEmpty)
          .toSet();
      if (mounted && input.text.trim() == value && keyword.isEmpty) {
        setState(() => suggestionValues = values.take(12).toList());
      }
    } catch (_) {
      if (mounted && input.text.trim() == value) {
        setState(() => suggestionValues = []);
      }
    } finally {
      if (mounted) {
        setState(() => busySuggestions = false);
        final current = input.text.trim();
        if (keyword.isEmpty && current.isNotEmpty && current != value) {
          scheduleSuggestions(current);
        }
      }
    }
  }

  Future<void> clearHistory() async {
    setState(history.clear);
    await widget.app.preferences.remove('searchHistory');
  }

  Future<void> loadHistory() async {
    try {
      final data = await widget.app.api.call('searchHistory');
      final remote = objectsOf(data['records'])
          .map((x) => textOf(x['query'] ?? x['keyword'] ?? x['text']))
          .where((x) => x.isNotEmpty);
      final merged = <String>{...history, ...remote}.take(20).toList();
      if (!mounted) return;
      setState(() {
        history
          ..clear()
          ..addAll(merged);
      });
      await widget.app.preferences.setStringList('searchHistory', history);
    } catch (_) {
      // Local search history remains available when the account request fails.
    }
  }

  void chooseQuery(String value) {
    input.value = TextEditingValue(
      text: value,
      selection: TextSelection.collapsed(offset: value.length),
    );
    unawaited(submit());
  }

  Future<void> filter() async {
    if (keyword.isEmpty) return;
    try {
      await widget.app.api.call('searchFilters', {
        'keyword': keyword,
        'searchId': searchId,
      });
    } catch (e) {
      if (mounted) showFailure(context, e);
      return;
    }
    if (!mounted) return;
    final choice = await showAppDialog<String>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('排序与类型'),
        children: [
          for (final entry in {
            'general': '综合',
            'time_descending': '最新',
            'popularity_descending': '最热',
            'type:0': '全部类型',
            'type:1': '视频笔记',
            'type:2': '图文笔记',
          }.entries)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(ctx, entry.key),
              child: Text(entry.value),
            ),
        ],
      ),
    );
    if (choice != null && mounted) {
      if (choice.startsWith('type:')) {
        noteType = int.parse(choice.substring(5));
      } else {
        sort = choice;
      }
      await submit();
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 8, 16, 8),
            child: Row(
              children: [
                IconButton(
                  tooltip: '返回发现',
                  onPressed: () {
                    FocusScope.of(context).unfocus();
                    widget.onBack();
                  },
                  icon: const Icon(Icons.arrow_back_ios_new),
                ),
                Expanded(
                  child: Container(
                    constraints: const BoxConstraints(minHeight: 54),
                    decoration: BoxDecoration(
                      color: colors.surfaceContainerHigh,
                      borderRadius: BorderRadius.circular(32),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: input,
                            autofocus: true,
                            textInputAction: TextInputAction.search,
                            onSubmitted: (_) => unawaited(submit()),
                            onChanged: (value) {
                              setState(() {});
                              scheduleSuggestions(value);
                            },
                            decoration: const InputDecoration(
                              hintText: '搜索感兴趣的笔记或用户',
                              border: InputBorder.none,
                              contentPadding: EdgeInsets.symmetric(
                                horizontal: 18,
                              ),
                            ),
                          ),
                        ),
                        if (input.text.isNotEmpty)
                          IconButton(
                            tooltip: '清除搜索词',
                            onPressed: () {
                              input.clear();
                              setState(() {
                                keyword = '';
                                searchId = '';
                                users = false;
                                suggestionValues = [];
                              });
                            },
                            icon: const Icon(Icons.cancel, size: 20),
                          ),
                        Container(
                          width: 1,
                          height: 24,
                          color: colors.outlineVariant.withValues(alpha: .5),
                        ),
                        TextButton(
                          onPressed: () => unawaited(submit()),
                          style: TextButton.styleFrom(
                            foregroundColor: colors.onSurface,
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                          ),
                          child: const Text('搜索'),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: keyword.isEmpty
                ? _buildLanding(context)
                : _buildResults(context),
          ),
        ],
      ),
    );
  }

  Widget _buildLanding(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (history.isNotEmpty) ...[
            Row(
              children: [
                Text('历史记录', style: Theme.of(context).textTheme.titleMedium),
                const Spacer(),
                IconButton(
                  tooltip: '清空本机搜索记录',
                  onPressed: () => unawaited(clearHistory()),
                  icon: const Icon(Icons.delete_outline),
                ),
              ],
            ),
            Wrap(
              spacing: 10,
              runSpacing: 8,
              children: [
                for (final value in history)
                  ActionChip(
                    label: Text(
                      value,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    side: BorderSide(
                      color: colors.outlineVariant.withValues(alpha: .45),
                    ),
                    backgroundColor: Colors.transparent,
                    onPressed: () => chooseQuery(value),
                  ),
              ],
            ),
            const SizedBox(height: 24),
          ],
          if (suggestionValues.isNotEmpty || busySuggestions) ...[
            Text('猜你想搜', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (busySuggestions)
              const LinearProgressIndicator(minHeight: 2)
            else
              LayoutBuilder(
                builder: (context, constraints) {
                  final width = (constraints.maxWidth - 20) / 2;
                  return Wrap(
                    spacing: 20,
                    runSpacing: 4,
                    children: [
                      for (final value in suggestionValues)
                        SizedBox(
                          width: width,
                          child: TextButton(
                            onPressed: () => chooseQuery(value),
                            style: TextButton.styleFrom(
                              alignment: Alignment.centerLeft,
                              foregroundColor: colors.onSurface,
                              padding: const EdgeInsets.symmetric(vertical: 10),
                            ),
                            child: Text(
                              value,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ),
                    ],
                  );
                },
              ),
          ] else if (history.isEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 32),
              child: Center(
                child: Text(
                  '搜索后，记录会显示在这里',
                  style: TextStyle(color: colors.onSurfaceVariant),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildResults(BuildContext context) {
    return Column(
      children: [
        SizedBox(
          height: 52,
          child: Row(
            children: [
              _searchTab(
                context,
                '全部',
                !users,
                () => setState(() => users = false),
              ),
              _searchTab(
                context,
                '用户',
                users,
                () => setState(() => users = true),
              ),
              if (!users)
                IconButton(
                  tooltip: '排序与类型筛选',
                  onPressed: filter,
                  icon: Icon(
                    Icons.tune,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
            ],
          ),
        ),
        Expanded(
          child: KeyedSubtree(
            key: ValueKey('$searchId:$users'),
            child: users
                ? SearchUsersPage(
                    app: widget.app,
                    keyword: keyword,
                    searchId: searchId,
                  )
                : FeedPage(
                    key: feed,
                    app: widget.app,
                    method: 'search',
                    source: 'pc_search',
                    args: {
                      'keyword': keyword,
                      'searchId': searchId,
                      'sort': sort,
                      'noteType': noteType,
                    },
                  ),
          ),
        ),
      ],
    );
  }

  Widget _searchTab(
    BuildContext context,
    String label,
    bool selected,
    VoidCallback onTap,
  ) => InkWell(
    onTap: onTap,
    child: SizedBox(
      height: 52,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 18),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              label,
              style: TextStyle(
                color: selected
                    ? Theme.of(context).colorScheme.onSurface
                    : Theme.of(context).colorScheme.onSurfaceVariant,
                fontWeight: selected ? FontWeight.w700 : FontWeight.normal,
              ),
            ),
            const SizedBox(height: 5),
            Container(
              width: 38,
              height: 3,
              decoration: BoxDecoration(
                color: selected
                    ? Theme.of(context).colorScheme.primary
                    : Colors.transparent,
                borderRadius: BorderRadius.circular(4),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
