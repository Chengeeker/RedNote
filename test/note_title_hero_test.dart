import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rednote/ui/note_title_hero.dart';

void main() {
  testWidgets('long title keeps endpoint line widths throughout push and pop', (
    tester,
  ) async {
    const title = '这是一段需要换行的长标题，用来验证打开和关闭帖子时文字不会反复跳行闪烁';
    final navigator = GlobalKey<NavigatorState>();
    Widget page(bool detail) => Scaffold(
      body: Align(
        alignment: detail ? Alignment.topLeft : Alignment.bottomRight,
        child: SizedBox(
          width: detail ? 368 : 160,
          child: Hero(
            tag: 'title',
            createRectTween: (begin, end) => RectTween(begin: begin, end: end),
            flightShuttleBuilder: noteTitleFlightShuttle,
            child: Material(
              type: MaterialType.transparency,
              child: Text(
                title,
                maxLines: detail ? null : 2,
                overflow: detail ? TextOverflow.clip : TextOverflow.ellipsis,
                style: TextStyle(fontSize: detail ? 18 : 14, height: 1.5),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpWidget(
      MaterialApp(navigatorKey: navigator, home: page(false)),
    );
    navigator.currentState!.push(
      PageRouteBuilder<void>(
        transitionDuration: const Duration(milliseconds: 400),
        reverseTransitionDuration: const Duration(milliseconds: 400),
        pageBuilder: (_, _, _) => page(true),
      ),
    );
    await tester.pump();
    Future<void> checkFlight() async {
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 50));
        final texts = find.text(title);
        expect(texts, findsNWidgets(2));
        for (final element in texts.evaluate()) {
          final paragraph = element.renderObject! as RenderParagraph;
          expect(paragraph.size.width, anyOf(160.0, 368.0));
        }
        expect(tester.takeException(), isNull);
      }
    }

    await checkFlight();
    await tester.pumpAndSettle();
    navigator.currentState!.pop();
    await tester.pump();
    await checkFlight();
    await tester.pumpAndSettle();
    expect(find.text(title), findsOneWidget);
  });
}
