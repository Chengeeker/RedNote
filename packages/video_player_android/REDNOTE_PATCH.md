# Local extension (upstream 2.12.2)

HttpVideoAsset consumes and removes the local `RedNote-Audio-Url` option from the HTTP header map. The URL must be HTTPS without credentials. Its MediaSource.Factory merges video and external audio using the same Media3 player/clock; pause, seek, rate and disposal remain upstream behavior. The option is never sent as an HTTP header.

Added VideoAssetTest cases cover merging, header stripping, input preservation and unsafe URI rejection. Run the plugin's `testDebugUnitTest` task; this is a host JVM test and produces no separate installable app. Actual A/V synchronization and codec compatibility still require a device.

PlatformViewPlayer uses hybrid composition (`initExpensiveAndroidView`) to retain the native SurfaceView's HDR output. RedNote requests this mode for Android video; do not change it back to texture composition when upgrading. The device decoder and the actual returned bitstream still determine HDR playback support.
