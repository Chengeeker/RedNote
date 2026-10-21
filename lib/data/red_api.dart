import 'dart:convert';

import 'package:flutter/services.dart';

typedef Json = Map<String, dynamic>;

Json objectOf(dynamic value) => value is Map
    ? value.map((key, value) => MapEntry(key.toString(), value))
    : <String, dynamic>{};
List<Json> objectsOf(dynamic value) =>
    value is List ? value.whereType<Map>().map(objectOf).toList() : <Json>[];
String textOf(dynamic value) => value == null ? '' : value.toString();

String normalizeXhsTopicMarkers(String text) => text.replaceAllMapped(
  RegExp(r'#([^#\r\n]+?)\[(?:话题|标签)\]#'),
  (match) => '#${match[1]}#',
);

Map<String, String> parseXhsEmojiMap(String source) {
  dynamic decoded;
  try {
    decoded = jsonDecode(source);
  } on FormatException {
    return const {};
  }
  if (decoded is! Map) return const {};
  final tokenPattern = RegExp(r'^\[[^\[\]\r\n]{1,32}\]$');
  final result = <String, String>{};
  for (final entry in decoded.entries) {
    final token = entry.key.toString();
    final url = mediaUrl(entry.value);
    final host = Uri.tryParse(url)?.host ?? '';
    if (tokenPattern.hasMatch(token) &&
        url.isNotEmpty &&
        isOfficialRedMediaHost(host)) {
      result[token] = url;
    }
  }
  return result;
}

class XhsTextPart {
  const XhsTextPart(this.text, {this.emojiUrl, this.topic});
  final String text;
  final String? emojiUrl;
  final String? topic;
}

List<XhsTextPart> xhsTextParts(String value, Map<String, String> emojiUrls) {
  final text = normalizeXhsTopicMarkers(value);
  if (text.isEmpty) return const [];
  final parts = <XhsTextPart>[];
  var plainStart = 0;
  var index = 0;
  while (index < text.length) {
    if (text.codeUnitAt(index) == 0x23) {
      final end = text.indexOf('#', index + 1);
      if (end > index + 1 &&
          !text.substring(index, end).contains(RegExp(r'[\r\n]'))) {
        if (plainStart < index) {
          parts.add(XhsTextPart(text.substring(plainStart, index)));
        }
        parts.add(
          XhsTextPart(
            text.substring(index, end + 1),
            topic: text.substring(index + 1, end),
          ),
        );
        index = end + 1;
        plainStart = index;
        continue;
      }
    }
    if (text.codeUnitAt(index) == 0x5B) {
      final end = text.indexOf(']', index + 1);
      if (end > index && end - index <= 33) {
        final token = text.substring(index, end + 1);
        final url = emojiUrls[token];
        if (url != null) {
          if (plainStart < index) {
            parts.add(XhsTextPart(text.substring(plainStart, index)));
          }
          parts.add(XhsTextPart(token, emojiUrl: url));
          index = end + 1;
          plainStart = index;
          continue;
        }
      }
    }
    index++;
  }
  if (plainStart < text.length) {
    parts.add(XhsTextPart(text.substring(plainStart)));
  }
  if (parts.isEmpty) parts.add(XhsTextPart(text));
  return parts;
}

class RedApi {
  const RedApi();
  static const channel = MethodChannel('com.red.note/api');

  Future<Json> call(String method, [Json args = const {}]) async =>
      objectOf(await channel.invokeMethod<dynamic>(method, args));

  Future<void> openPrivateChat(String id, String name) =>
      channel.invokeMethod<void>('openPrivateChat', {'id': id, 'name': name});
}

String errorText(Object error) =>
    error is PlatformException ? error.message ?? '请求失败，请重试' : '内容暂时无法加载，请重试';

bool needsLogin(Object? error) =>
    error is PlatformException &&
    const {'login', 'signature', 'verification', 'client'}.contains(error.code);

bool isVerificationError(Object? error) =>
    error is PlatformException && error.code == 'verification';

bool isOfficialRedMediaHost(String host) {
  final normalized = host.toLowerCase();
  return const [
    'xhscdn.com',
    'xiaohongshu.com',
    'rednote.com',
  ].any((domain) => normalized == domain || normalized.endsWith('.$domain'));
}

String mediaUrl(dynamic value) {
  final text = textOf(value);
  final normalized = text.startsWith('//') ? 'https:$text' : text;
  var uri = Uri.tryParse(normalized);
  if (uri == null || uri.host.isEmpty || uri.userInfo.isNotEmpty) return '';
  if (uri.scheme == 'http' &&
      uri.port == 80 &&
      isOfficialRedMediaHost(uri.host)) {
    uri = uri.replace(scheme: 'https');
  }
  return uri.scheme == 'https' ? uri.toString() : '';
}

class NoteRef {
  const NoteRef(this.id, this.token, this.source);
  final String id;
  final String token;
  final String source;
  Json get args => {'id': id, 'token': token, 'source': source};
}

class Note {
  Note(this.raw, this.reference);
  final Json raw;
  final NoteRef reference;
  Json get user => objectOf(raw['user']);
  String get userId => textOf(user['user_id'] ?? user['userid']);
  String get author => textOf(user['nickname'] ?? user['nick_name']);
  String get avatar => userImageUrl(user);
  String get title => textOf(raw['display_title'] ?? raw['title']);
  String get description => textOf(raw['desc']);
  String get likes => textOf(objectOf(raw['interact_info'])['liked_count']);
  String get viewCount =>
      textOf(raw['view_count'] ?? objectOf(raw['interact_info'])['view_count']);
  bool get isVideo => raw['type'] == 'video' || raw['video'] is Map;
  Json get cover => objectOf(raw['cover']);
  String get coverUrl {
    for (final value in [raw['cover'], raw['cover_url']]) {
      final direct = mediaUrl(value);
      if (direct.isNotEmpty) return direct;
      final image = imageUrl(objectOf(value));
      if (image.isNotEmpty) return image;
    }
    final imageList = images;
    return imageList.isNotEmpty ? imageUrl(imageList.first) : '';
  }

  double get coverRatio {
    final width = double.tryParse(textOf(cover['width'])) ?? 0;
    final height = double.tryParse(textOf(cover['height'])) ?? 0;
    return width > 0 && height > 0 ? (width / height).clamp(0.65, 1.5) : 0.75;
  }

  List<Json> get images {
    final values = raw['image_list'];
    if (values is! List) return const [];
    return [
      for (final value in values)
        if (value is Map)
          objectOf(value)
        else if (value is String)
          {'url': value},
    ].where((image) => imageUrl(image).isNotEmpty).toList();
  }

  static String imageUrl(Json image) {
    final infoList = objectsOf(image['info_list']);
    for (final scene in ['WB_DFT', 'FD_WM_WEBP', 'FD_PRV_WEBP']) {
      for (final info in infoList) {
        if (textOf(info['image_scene']) != scene) continue;
        final url = mediaUrl(info['url']);
        if (url.isNotEmpty) return url;
      }
    }
    for (final key in [
      'url_default',
      'url_pre',
      'url',
      'url_size_large',
      'url_size_original',
      'origin_url',
    ]) {
      final url = mediaUrl(image[key]);
      if (url.isNotEmpty) return url;
    }
    for (final info in infoList) {
      final url = mediaUrl(info['url']);
      if (url.isNotEmpty) return url;
    }
    return '';
  }

  static Note? fromItem(Json item, {required String source}) {
    final card = objectOf(item['note_card']);
    final raw = card.isNotEmpty
        ? {
            ...card,
            if (!card.containsKey('view_count') &&
                item.containsKey('view_count'))
              'view_count': item['view_count'],
          }
        : item;
    final id = textOf(
      item['id'] ?? item['note_id'] ?? raw['note_id'] ?? raw['id'],
    );
    if (id.isEmpty ||
        (card.isEmpty &&
            !raw.containsKey('title') &&
            !raw.containsKey('display_title') &&
            !raw.containsKey('cover'))) {
      return null;
    }
    return Note(
      raw,
      NoteRef(
        id,
        textOf(item['xsec_token'] ?? raw['xsec_token']),
        textOf(item['xsec_source']).isNotEmpty
            ? textOf(item['xsec_source'])
            : source,
      ),
    );
  }
}

String userImageUrl(Json user) {
  String find(dynamic value) {
    final direct = mediaUrl(value);
    if (direct.isNotEmpty) return direct;
    if (value is Map) return Note.imageUrl(objectOf(value));
    if (value is List) {
      for (final item in value) {
        final url = find(item);
        if (url.isNotEmpty) return url;
      }
    }
    return '';
  }

  for (final key in ['imageb', 'images', 'avatar', 'image']) {
    final url = find(user[key]);
    if (url.isNotEmpty) return url;
  }
  return '';
}

String profileBackgroundUrl(Json profile) {
  final basic = objectOf(profile['basic_info'] ?? profile['basicInfo']);
  for (final value in [
    basic['background_image'],
    objectOf(profile['user'])['background_image'],
    objectOf(profile['user'])['backgroundImage'],
    basic['backgroundImage'],
    profile['background_image'],
    profile['backgroundImage'],
  ]) {
    final url = userImageUrl({'images': value});
    if (url.isNotEmpty) return url;
  }
  return '';
}

class VideoSource {
  const VideoSource(this.url, this.codec, {this.audioUrl = ''});
  final String url;
  final String codec;
  final String audioUrl;
}

List<VideoSource> videoSources(Json raw) {
  final video = objectOf(raw['video']);
  final media = objectOf(video['media']);
  final streams = objectOf(media['stream']);
  dynamic v2 = video['media_v2'];
  if (v2 is String && v2.length < 1024 * 1024) {
    try {
      v2 = jsonDecode(v2);
    } catch (_) {
      v2 = null;
    }
  }
  final audioStreams = objectOf(objectOf(v2)['audio_stream']);
  final audioEntries = [
    for (final entries in audioStreams.values) ...objectsOf(entries),
  ];
  audioEntries.sort(
    (a, b) => (b['default_stream'] == 1 ? 1 : 0).compareTo(
      a['default_stream'] == 1 ? 1 : 0,
    ),
  );
  final audioUrls = <String>{};
  for (final entry in audioEntries) {
    final candidates = [
      entry['master_url'],
      if (entry['backup_urls'] is List) ...entry['backup_urls'] as List,
    ];
    for (final candidate in candidates) {
      final url = mediaUrl(candidate);
      if (url.isNotEmpty) audioUrls.add(url);
    }
  }
  final result = <VideoSource>[];
  final seen = <String>{};
  void add(dynamic value, String codec, {String audio = ''}) {
    final url = mediaUrl(value);
    if (url.isNotEmpty && seen.add('$url|$audio')) {
      result.add(VideoSource(url, codec, audioUrl: audio));
    }
  }

  // Prefer widely supported streams; retain the actual returned alternatives.
  for (final codec in ['h264', 'h265', 'av1', ...streams.keys]) {
    for (final entry in objectsOf(streams[codec])) {
      final hasAudio =
          textOf(entry['audio_codec']).isNotEmpty ||
          (int.tryParse(textOf(entry['audio_bitrate'])) ?? 0) > 0;
      final audios = hasAudio || audioUrls.isEmpty ? [''] : audioUrls.take(2);
      for (final audio in audios) {
        add(entry['master_url'], codec, audio: audio);
        if (entry['backup_urls'] is List) {
          for (final url in entry['backup_urls'] as List) {
            add(url, codec, audio: audio);
          }
        }
      }
    }
  }
  add(video['url'], 'video');
  return result;
}

String notificationTime(dynamic value) {
  final timestamp = num.tryParse(textOf(value));
  if (timestamp == null || timestamp <= 0 || !timestamp.isFinite) return '';
  final millis = (timestamp < 100000000000 ? timestamp * 1000 : timestamp)
      .toInt();
  if (millis > 8640000000000000) return '';
  final date = DateTime.fromMillisecondsSinceEpoch(millis);
  final now = DateTime.now();
  final age = now.difference(date);
  if (age.isNegative || age < const Duration(minutes: 1)) return '刚刚';
  if (age < const Duration(hours: 1)) return '${age.inMinutes}分钟前';
  if (age < const Duration(days: 1)) return '${age.inHours}小时前';
  if (age < const Duration(days: 7)) return '${age.inDays}天前';
  final md =
      '${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
  return date.year == now.year ? md : '${date.year}-$md';
}

class PrivateChatPreview {
  PrivateChatPreview.fromJson(Json json)
    : id = textOf(json['chat_user_id'] ?? json['user_id']),
      name = _firstText([
        objectOf(json['info'])['nickname'],
        objectOf(json['info'])['nick_name'],
        objectOf(json['info'])['user_name'],
        json['user_name'],
      ]),
      avatar = userImageUrl(objectOf(json['info'])),
      preview = textOf(json['last_msg_content']),
      time = notificationTime(json['last_msg_time']);

  final String id;
  final String name;
  final String avatar;
  final String preview;
  final String time;
}

String _firstText(Iterable<dynamic> values) => values
    .map(textOf)
    .firstWhere((value) => value.isNotEmpty, orElse: () => '');

String notePublicationMeta(Json raw) {
  final timestamp = num.tryParse(textOf(raw['time']));
  var dateText = '';
  if (timestamp != null &&
      timestamp.isFinite &&
      timestamp > 0 &&
      timestamp < 8640000000000000) {
    final millis = (timestamp < 100000000000 ? timestamp * 1000 : timestamp)
        .toInt();
    final date = DateTime.fromMillisecondsSinceEpoch(millis);
    dateText =
        '${date.year == DateTime.now().year ? '' : '${date.year}年'}${date.month}月${date.day}日';
  }
  final location = textOf(raw['ip_location'])
      .replaceFirst(RegExp(r'^IP属地[:：]\s*'), '')
      .trim();
  return [dateText, location].where((x) => x.isNotEmpty).join('  ');
}

Json validatedViewCounts(Json values, Set<String> noteIds) {
  return {
    for (final entry in values.entries)
      if (noteIds.contains(entry.key) &&
          RegExp(r'^\d{1,18}$').hasMatch(textOf(entry.value)))
        entry.key: textOf(entry.value),
  };
}
