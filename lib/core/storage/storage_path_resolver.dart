/// 存储路径计算与文件名过滤工具
class StoragePathResolver {
  const StoragePathResolver._();

  /// 过滤非法文件与目录名字符
  static String sanitizeDirectoryName(
    String? name, {
    String fallback = 'User',
  }) {
    final clean = (name ?? '')
        .trim()
        .replaceAll(RegExp(r'[\\/:*?"<>|\r\n]'), '_')
        .replaceAll(RegExp(r'_+'), '_')
        .trim();
    if (clean.isEmpty || clean == '.' || clean == '..') {
      return fallback;
    }
    // Limit subfolder length to 50 characters to prevent path overflow
    return clean.length > 50 ? clean.substring(0, 50).trim() : clean;
  }

  /// 获取设置界面展示的路径摘要
  static String getPathSummary(
    int type,
    String myNickname, {
    required bool isVideo,
  }) {
    final baseDir = isVideo ? 'Movies' : 'Pictures';
    final nick = myNickname.trim().isEmpty ? '我的昵称' : myNickname.trim();
    switch (type) {
      case 1:
        return '$baseDir/RedNote/$nick/';
      case 2:
        return '$baseDir/RedNote/笔记作者/';
      default:
        return '$baseDir/RedNote/';
    }
  }

  /// 解析保存到 MediaStore 时的 relativeSubDir
  static String resolveSubDir({
    required int pathType,
    String? myNickname,
    String? authorName,
    required bool isVideo,
  }) {
    switch (pathType) {
      case 1:
        final safeNick = sanitizeDirectoryName(
          myNickname,
          fallback: 'MyProfile',
        );
        return 'RedNote/$safeNick';
      case 2:
        final safeAuthor = sanitizeDirectoryName(
          authorName,
          fallback: 'Unknown',
        );
        return 'RedNote/$safeAuthor';
      default:
        return 'RedNote';
    }
  }
}
