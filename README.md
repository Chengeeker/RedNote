# RedNote

原生 Android Kotlin/Jetpack Compose 应用。项目根目录就是 Compose 工程入口，当前版本 `1.1.17+85`。Flutter 历史版本仍可从 Git 恢复。

## 功能

发现与搜索、笔记详情和评论、作者与个人主页、消息和私聊、图片/HDR 查看、视频播放与保存，以及主题、字体、悬浮导航、液态玻璃、缓存阈值和相册路径设置。

图片缓存阈值默认 200 MB，可选关闭、50/100/200/500 MB 或 1/2 GB。应用启动时异步检查，达到阈值后清理可重新生成的磁盘缓存；已保存到相册的数据、账号和设置不受影响。

登录仍通过应用内官方页面完成，只有原生 `user/me` 校验成功后才替换会话。离线测试与 APK 检查不能证明服务端、设备 HDR、GPU 帧率或全部视觉细节已实机验收；边界与历史问题见开发文档。

## 构建与检查

使用 JDK 21 和 Android SDK。将 `JAVA_HOME` 指向 JDK 21，再将项目根目录作为 Gradle 工程导入 Android Studio。应用最低支持 Android 24，正式包仅构建 `arm64-v8a`。

本地 SDK 路径由 `local.properties` 配置。构建签名包需要自己的 BKS 密钥和 `key.properties`，其中包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`。签名脚本使用本地 Gradle 缓存中的 Bouncy Castle。两份本地配置均由 Git 忽略。

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\tool\build_release.ps1
python .\tool\verify_release.py
```

构建脚本复用原 BKS 签名，运行 JVM/Robolectric 回归并构建正式 Release APK。最终 arm64 包名为 `RedNote-v1.1.17-arm64.apk`。
