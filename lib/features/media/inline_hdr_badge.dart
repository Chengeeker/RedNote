import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/media_support.dart';

/// The note preview stays SDR; only a confirmed original receives this badge.
class InlineHdrBadge extends StatefulWidget {
  const InlineHdrBadge({super.key, required this.url, required this.headers});
  final String url;
  final Map<String, String> headers;

  @override
  State<InlineHdrBadge> createState() => _InlineHdrBadgeState();
}

class _InlineHdrBadgeState extends State<InlineHdrBadge> {
  Timer? _warmup;

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  @override
  void didUpdateWidget(InlineHdrBadge oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) _schedule();
  }

  void _schedule() {
    _warmup?.cancel();
    final url = widget.url;
    _warmup = Timer(const Duration(milliseconds: 400), () {
      if (!mounted) return;
      unawaited(
        HdrImageDetector.prepareInline(
          url,
          widget.headers,
          isActive: () =>
              mounted &&
              widget.url == url &&
              (ModalRoute.of(context)?.isCurrent ?? true),
        ),
      );
    });
  }

  @override
  void dispose() {
    _warmup?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<int>(
    valueListenable: HdrImageDetector.notifier,
    builder: (_, _, _) => !HdrImageDetector.isKnownHdr(widget.url)
        ? const SizedBox.shrink()
        : Semantics(
            label: 'HDR 图片',
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.72),
                borderRadius: BorderRadius.circular(4),
                border: Border.all(
                  color: Colors.white.withValues(alpha: 0.45),
                  width: 0.8,
                ),
              ),
              child: const Text(
                'HDR',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 10,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 0.4,
                ),
              ),
            ),
          ),
  );
}
