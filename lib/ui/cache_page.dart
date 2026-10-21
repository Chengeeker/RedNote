import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/design_system/components/review_page_scaffold.dart';
import '../core/widgets/app_section_card.dart';
import '../core/utils/app_dialog.dart';
import 'common.dart';
import '../data/red_api.dart';

class CachePage extends StatefulWidget {
  const CachePage({super.key});
  @override
  State<CachePage> createState() => _CachePageState();
}

class _CachePageState extends State<CachePage> {
  static const channel = MethodChannel('com.red.note/media');
  int bytes = 0;
  bool busy = true;
  Object? failure;
  @override
  void initState() {
    super.initState();
    refresh();
  }

  Future<void> refresh() async {
    setState(() => busy = true);
    try {
      final stats = await channel.invokeMapMethod<String, dynamic>(
        'cacheStats',
      );
      if (mounted) {
        setState(() {
          bytes = (stats?['bytes'] as num?)?.toInt() ?? 0;
          failure = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => failure = e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> clear() async {
    final yes = await showAppDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清理媒体缓存？'),
        content: const Text('只清理可重新下载的缓存。已保存到相册的媒体、账号与设置不受影响。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('清理'),
          ),
        ],
      ),
    );
    if (yes != true || !mounted) return;
    setState(() => busy = true);
    try {
      await channel.invokeMethod<void>('clearCache');
      PaintingBinding.instance.imageCache.clear();
      PaintingBinding.instance.imageCache.clearLiveImages();
      await refresh();
    } catch (e) {
      if (mounted) {
        setState(() => busy = false);
        showFailure(context, e);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ReviewPageScaffold(
      title: '缓存管理',
      bodyBuilder: (context, insets) => ListView(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 32).add(insets),
        children: [
          AppSectionCard(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Center(
                child: Column(
                  children: [
                    Container(
                      width: 56,
                      height: 56,
                      decoration: BoxDecoration(
                        color: scheme.primaryContainer,
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        Icons.cleaning_services_rounded,
                        color: scheme.onPrimaryContainer,
                        size: 30,
                      ),
                    ),
                    const SizedBox(height: 16),
                    if (busy)
                      const SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2.5),
                      )
                    else
                      Text(
                        failure == null
                            ? '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB'
                            : errorText(failure!),
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.headlineMedium
                            ?.copyWith(
                              fontWeight: FontWeight.bold,
                              color: failure == null
                                  ? scheme.onSurface
                                  : scheme.error,
                            ),
                      ),
                    const SizedBox(height: 4),
                    Text(
                      '可管理的媒体磁盘缓存',
                      style: Theme.of(context).textTheme.bodyMedium
                          ?.copyWith(color: scheme.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          AppSectionCard(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: FilledButton.icon(
                onPressed: busy || failure != null ? null : clear,
                icon: const Icon(Icons.delete_sweep_rounded),
                label: Text(busy ? '正在读取...' : '清理媒体缓存'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 24),
          AppSectionCard(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '仅清理应用可重新下载的图片和视频缓存。已保存到相册的媒体、账号资料及外观设置不会被删除。',
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: scheme.onSurfaceVariant, height: 1.45),
              ),
            ),
          ),
          const SizedBox(height: 16),
          Align(
            alignment: Alignment.center,
            child: TextButton.icon(
              onPressed: busy ? null : refresh,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('刷新缓存占用'),
            ),
          ),
        ],
      ),
    );
  }
}
