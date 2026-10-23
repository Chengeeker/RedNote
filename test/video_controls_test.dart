import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/data/red_api.dart';
import 'package:rednote/features/media/video_player_page.dart';

class _Video extends VideoPlayerPlatform {
  final disposed = Completer<void>();
  int pauses = 0;
  Duration position = Duration.zero;
  double speed = 1;
  double volume = 0.7;
  @override
  Future<void> init() async {}
  @override
  Future<int?> createWithOptions(VideoCreationOptions options) async => 1;
  @override
  Stream<VideoEvent> videoEventsFor(int playerId) => Stream.value(
    VideoEvent(
      eventType: VideoEventType.initialized,
      duration: const Duration(minutes: 1),
      size: const Size(1920, 1080),
    ),
  );
  @override
  Widget buildViewWithOptions(VideoViewOptions options) =>
      const IgnorePointer(child: SizedBox.expand());
  @override
  Future<void> dispose(int playerId) async {
    disposed.complete();
  }

  @override
  Future<void> play(int playerId) async {}
  @override
  Future<void> pause(int playerId) async {
    pauses++;
  }

  @override
  Future<void> setLooping(int playerId, bool looping) async {}
  @override
  Future<void> setVolume(int playerId, double value) async {
    volume = value;
  }

  @override
  Future<void> setPlaybackSpeed(int playerId, double value) async {
    speed = value;
  }

  @override
  Future<void> seekTo(int playerId, Duration value) async {
    position = value;
  }

  @override
  Future<Duration> getPosition(int playerId) async => position;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'hidden native video controls wake on tap and all gestures remain usable',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final previous = VideoPlayerPlatform.instance;
      final platform = _Video();
      VideoPlayerPlatform.instance = platform;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('com.red.note/media'),
            (_) async => false,
          );
      final app = AppState(await SharedPreferences.getInstance());
      await tester.pumpWidget(
        MaterialApp(
          home: RedVideoPlayerPage(
            app: app,
            videoUrl: 'https://cdn.test/video',
            sources: const [VideoSource('https://cdn.test/video', 'h264')],
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      final overlay = find.byType(AnimatedOpacity);
      expect(tester.widget<AnimatedOpacity>(overlay).opacity, 1);
      await tester.pump(const Duration(seconds: 4));
      expect(tester.widget<AnimatedOpacity>(overlay).opacity, 0);
      // Black letterbox and IgnorePointer platform views must both receive input.
      await tester.tapAt(const Offset(100, 100));
      await tester.pump(const Duration(milliseconds: 350));
      expect(tester.widget<AnimatedOpacity>(overlay).opacity, 1);
      await tester.tap(find.byIcon(Icons.pause_rounded));
      await tester.pump();
      expect(platform.pauses, greaterThan(0));
      await tester.tap(find.byIcon(Icons.play_arrow_rounded));
      await tester.pump();
      await tester.tapAt(const Offset(700, 250));
      await tester.pump(const Duration(milliseconds: 60));
      await tester.tapAt(const Offset(700, 250));
      await tester.pump(const Duration(milliseconds: 350));
      expect(platform.position, const Duration(seconds: 10));
      final hold = await tester.startGesture(const Offset(400, 250));
      await tester.pump(const Duration(milliseconds: 600));
      expect(platform.speed, 2);
      await hold.up();
      await tester.pump();
      expect(platform.speed, 1);
      await tester.dragFrom(const Offset(700, 330), const Offset(0, -80));
      await tester.pump();
      expect(platform.volume, greaterThan(0.7));
      await tester.pump(const Duration(seconds: 4));
      expect(tester.widget<AnimatedOpacity>(overlay).opacity, 0);
      await tester.binding.handlePopRoute();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.byType(RedVideoPlayerPage), findsOneWidget);
      expect(tester.widget<AnimatedOpacity>(overlay).opacity, 1);
      await tester.pumpWidget(const SizedBox());
      await tester.pumpAndSettle();
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pump();
      expect(platform.disposed.isCompleted, isTrue);
      VideoPlayerPlatform.instance = previous;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('com.red.note/media'),
            null,
          );
      expect(tester.takeException(), isNull);
    },
  );
}
