import 'package:flutter/material.dart';

import '../app_state.dart';
import '../data/red_api.dart';
import '../core/design_system/components/review_frosted_app_bar.dart';
import 'common.dart';

Future<bool> editComment(
  BuildContext context,
  AppState app,
  NoteRef note, {
  String targetId = '',
  String? author,
}) async =>
    await Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) => _CommentEditor(
          app: app,
          note: note,
          targetId: targetId,
          author: author,
        ),
      ),
    ) ??
    false;

class _CommentEditor extends StatefulWidget {
  const _CommentEditor({
    required this.app,
    required this.note,
    required this.targetId,
    this.author,
  });
  final AppState app;
  final NoteRef note;
  final String targetId;
  final String? author;
  @override
  State<_CommentEditor> createState() => _CommentEditorState();
}

class _CommentEditorState extends State<_CommentEditor> {
  final input = TextEditingController();
  bool busy = false;
  Object? failure;
  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  Future<void> send() async {
    if (busy || input.text.trim().isEmpty) return;
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      await widget.app.api.call('postComment', {
        ...widget.note.args,
        'targetId': widget.targetId,
        'content': input.text.trim(),
      });
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => failure = e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !busy,
    child: Scaffold(
      appBar: ReviewFrostedAppBar(
        title: Text(widget.author == null ? '发表评论' : '回复 ${widget.author}'),
        actions: [
          TextButton(
            onPressed: busy ? null : send,
            child: Text(busy ? '发送中…' : '发送'),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            TextField(
              controller: input,
              autofocus: true,
              maxLength: 2000,
              maxLines: 8,
              minLines: 4,
              enabled: !busy,
              decoration: const InputDecoration(
                hintText: '说说你的想法…',
                border: OutlineInputBorder(),
              ),
            ),
            if (failure != null) ...[
              const SizedBox(height: 12),
              Text(errorText(failure!)),
              if (needsLogin(failure))
                TextButton(
                  onPressed: () => signIn(context, widget.app),
                  child: const Text('网页登录后重试'),
                ),
            ],
          ],
        ),
      ),
    ),
  );
}
