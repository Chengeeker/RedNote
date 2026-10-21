# GreenNote dependency source

Copied from the current `D:/App/Review/packages/liquid_glass_widgets` source on
2026-10-19 (Review commit `a3e69b475bcf1d7c6d4e9a6f9539d911c3e29003`).
Retains package version 1.13.0, license, third-party notices, shaders and iOS plugin.

Review's bottom-bar implementation keeps the native draggable indicator mounted.
Its thickness remains zero on ordinary taps when pinch strength is zero, and its
velocity transform applies no squash/stretch when maximum distortion is zero.
Long holds and drags still use the package's native lens, springs and icon masking.
Keep this source in the repository so these behaviors do not depend on a modified
global Pub cache. Navigation regression tests live in
`test/liquid_glass_navigation_test.dart` in the app.
