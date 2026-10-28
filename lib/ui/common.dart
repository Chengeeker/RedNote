import 'package:flutter/material.dart';
import 'package:extended_image/extended_image.dart'
    show ExtendedNetworkImageProvider;
import 'package:flutter/services.dart';
import 'package:flutter/gestures.dart';

import '../app_state.dart';
import '../data/media_support.dart';
import '../data/red_api.dart';

class LoadMessage extends StatelessWidget {
  const LoadMessage({
    super.key,
    required this.title,
    this.error,
    this.onRetry,
    this.onLogin,
    this.onVerification,
  });
  final String title;
  final Object? error;
  final VoidCallback? onRetry;
  final Future<bool> Function()? onLogin;
  final Future<bool> Function(String?)? onVerification;
  @override
  Widget build(BuildContext context) {
    final verification = isVerificationError(error);
    final details = error is PlatformException
        ? objectOf((error as PlatformException).details)
        : <String, dynamic>{};
    final verificationUrl = textOf(details['verificationUrl']);
    final canOpenVerification =
        !details.containsKey('verificationUrl') || verificationUrl.isNotEmpty;
    final Future<bool> Function()? login = verification && !canOpenVerification
        ? null
        : verification && onVerification != null
        ? () => onVerification!(verificationUrl)
        : onLogin;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              error == null
                  ? Icons.auto_stories_outlined
                  : Icons.cloud_off_outlined,
              size: 40,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
            const SizedBox(height: 16),
            Text(
              title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (error != null) ...[
              const SizedBox(height: 8),
              Text(errorText(error!), textAlign: TextAlign.center),
            ],
            const SizedBox(height: 16),
            if (login != null && (error == null || needsLogin(error)))
              FilledButton.icon(
                onPressed: () async {
                  final completed = await login();
                  if (context.mounted && completed) onRetry?.call();
                },
                icon: Icon(
                  verification ? Icons.verified_user_outlined : Icons.login,
                ),
                label: Text(verification ? '前往官方安全验证' : '网页登录'),
              )
            else if (onRetry != null)
              OutlinedButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh),
                label: const Text('重试'),
              ),
            if (verification && login != null && onRetry != null)
              TextButton(onPressed: onRetry, child: const Text('重试此请求')),
          ],
        ),
      ),
    );
  }
}

class RemoteImage extends StatelessWidget {
  const RemoteImage(
    this.url, {
    super.key,
    this.fit = BoxFit.cover,
    this.label,
    this.decodeWidth,
    this.api,
    this.cacheLocally = false,
  });
  final String url;
  final BoxFit fit;
  final String? label;
  final int? decodeWidth;
  final AppState? api;
  final bool cacheLocally;
  @override
  Widget build(BuildContext context) {
    Widget fallback() => ColoredBox(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      child: Center(
        child: Icon(
          Icons.image_outlined,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    );
    if (url.isEmpty) return fallback();
    final headers = redImageHeaders(url, api: api);
    final ImageProvider<Object> provider = cacheLocally
        ? ExtendedNetworkImageProvider(
            url,
            cache: true,
            headers: headers,
            retries: 3,
            printError: false,
          )
        : NetworkImage(url, headers: headers);
    return Image(
      image: decodeWidth == null
          ? provider
          : ResizeImage(provider, width: decodeWidth),
      fit: fit,
      semanticLabel: label,
      excludeFromSemantics: label == null,
      frameBuilder: (context, child, frame, sync) =>
          sync || frame != null ? child : fallback(),
      errorBuilder: (context, error, stack) => fallback(),
    );
  }
}

Future<Map<String, String>>? _xhsEmojiUrls;

class XhsRichText extends StatefulWidget {
  const XhsRichText(
    this.text, {
    super.key,
    this.style,
    this.api,
    this.maxLines,
    this.overflow,
    this.textAlign,
    this.onTopicTap,
  });
  final String text;
  final TextStyle? style;
  final AppState? api;
  final int? maxLines;
  final TextOverflow? overflow;
  final TextAlign? textAlign;
  final ValueChanged<String>? onTopicTap;

  @override
  State<XhsRichText> createState() => _XhsRichTextState();
}

class _XhsRichTextState extends State<XhsRichText> {
  final _topicRecognizers = <TapGestureRecognizer>[];

  void _disposeTopicRecognizers() {
    for (final recognizer in _topicRecognizers) {
      recognizer.dispose();
    }
    _topicRecognizers.clear();
  }

  @override
  void didUpdateWidget(covariant XhsRichText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text ||
        oldWidget.onTopicTap != widget.onTopicTap) {
      _disposeTopicRecognizers();
    }
  }

  @override
  void dispose() {
    _disposeTopicRecognizers();
    super.dispose();
  }

  // shortcut: supports the 146 codes bundled with xhsTV v0.2.11; refresh if XHS adds codes.
  static Future<Map<String, String>> _loadEmojiUrls() async {
    try {
      return parseXhsEmojiMap(
        await rootBundle.loadString('assets/xhs_emoji.json'),
      );
    } catch (_) {
      return const {};
    }
  }

  @override
  Widget build(BuildContext context) {
    final future = _xhsEmojiUrls ??= _loadEmojiUrls();
    return FutureBuilder<Map<String, String>>(
      future: future,
      builder: (context, snapshot) {
        _disposeTopicRecognizers();
        final spans = <InlineSpan>[];
        final baseSize =
            widget.style?.fontSize ??
            Theme.of(context).textTheme.bodyMedium?.fontSize ??
            14;
        final emojiSize =
            MediaQuery.textScalerOf(context).scale(baseSize) * 1.15;
        for (final part in xhsTextParts(
          widget.text,
          snapshot.data ?? const {},
        )) {
          final url = part.emojiUrl;
          if (url == null) {
            if (part.topic != null && widget.onTopicTap != null) {
              final recognizer = TapGestureRecognizer()
                ..onTap = () => widget.onTopicTap!(part.topic!);
              _topicRecognizers.add(recognizer);
              spans.add(
                TextSpan(
                  text: part.text,
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  recognizer: recognizer,
                ),
              );
            } else {
              spans.add(TextSpan(text: part.text));
            }
          } else {
            spans.add(
              WidgetSpan(
                alignment: PlaceholderAlignment.middle,
                child: SizedBox.square(
                  dimension: emojiSize,
                  child: RemoteImage(
                    url,
                    api: widget.api,
                    label: part.text
                        .substring(1, part.text.length - 1)
                        .replaceFirst(RegExp(r'R$'), ''),
                  ),
                ),
              ),
            );
          }
        }
        return Text.rich(
          TextSpan(children: spans),
          style: widget.style,
          maxLines: widget.maxLines,
          overflow: widget.overflow,
          textAlign: widget.textAlign,
        );
      },
    );
  }
}

class UserAvatar extends StatelessWidget {
  const UserAvatar(this.url, {super.key, this.size = 32, this.api});
  final String url;
  final double size;
  final AppState? api;
  @override
  Widget build(BuildContext context) => ClipOval(
    child: SizedBox.square(
      dimension: size,
      child: RemoteImage(
        url,
        decodeWidth: (size * MediaQuery.devicePixelRatioOf(context)).round(),
        api: api,
      ),
    ),
  );
}

void showFailure(BuildContext context, Object error) =>
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(errorText(error))));

Future<bool> signIn(
  BuildContext context,
  AppState app, {
  bool manualVerification = false,
  String? verificationUrl,
}) async {
  await app.login(
    manualVerification: manualVerification,
    verificationUrl: verificationUrl,
  );
  if (context.mounted && app.error != null) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(app.error!)));
  }
  return app.loggedIn && app.error == null && app.session['blocked'] == null;
}
