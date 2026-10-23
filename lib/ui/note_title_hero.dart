import 'package:flutter/material.dart';

Widget noteTitleFlightShuttle(
  BuildContext context,
  Animation<double> animation,
  HeroFlightDirection direction,
  BuildContext fromContext,
  BuildContext toContext,
) {
  Widget endpoint(BuildContext context) {
    final hero = context.widget as Hero;
    // Keep each endpoint's line breaks while the flight rectangle changes size.
    return FittedBox(
      alignment: Alignment.topLeft,
      fit: BoxFit.contain,
      child: SizedBox.fromSize(
        size: (context.findRenderObject()! as RenderBox).size,
        child: InheritedTheme.captureAll(context, hero.child),
      ),
    );
  }

  final source = endpoint(fromContext);
  final target = endpoint(toContext);
  return AnimatedBuilder(
    animation: animation,
    builder: (context, _) {
      final progress = direction == HeroFlightDirection.push
          ? animation.value
          : 1 - animation.value;
      return Stack(
        fit: StackFit.expand,
        children: [
          Opacity(opacity: 1 - progress, child: source),
          Opacity(opacity: progress, child: target),
        ],
      );
    },
  );
}
