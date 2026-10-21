import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rednote/features/media/image_gallery_page.dart';

void main() {
  const viewport = Rect.fromLTWH(0, 0, 1260, 2800);

  for (final imageSize in [const Size(4000, 3000), const Size(3000, 4000)]) {
    test('HDR zoom preserves aspect and focus for $imageSize', () {
      final fittedRect = Alignment.center.inscribe(
        applyBoxFit(BoxFit.contain, imageSize, viewport.size).destination,
        viewport,
      );
      for (final scale in [1.0, 2.5, 6.0]) {
        final target = Rect.fromLTWH(
          fittedRect.left - 130 * (scale - 1),
          fittedRect.top - 230 * (scale - 1),
          fittedRect.width * scale,
          fittedRect.height * scale,
        );
        final matrix = hdrImageViewTransform(viewport, target);
        final actual = MatrixUtils.transformRect(matrix, fittedRect);
        expect(matrix.entry(0, 0), closeTo(scale, 1e-8));
        expect(matrix.entry(1, 1), closeTo(scale, 1e-8));
        expect(actual.left, closeTo(target.left, 1e-8));
        expect(actual.top, closeTo(target.top, 1e-8));
        expect(actual.width, closeTo(target.width, 1e-8));
        expect(actual.height, closeTo(target.height, 1e-8));
      }
    });
  }

  test('HDR layer is untransformed before gesture geometry is available', () {
    expect(
      MatrixUtils.transformRect(
        hdrImageViewTransform(viewport, null),
        viewport,
      ),
      viewport,
    );
  });
}
