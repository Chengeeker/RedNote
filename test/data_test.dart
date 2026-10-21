import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rednote/app_state.dart';
import 'package:rednote/data/media_support.dart';
import 'package:rednote/data/red_api.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('separate audio metadata is paired, but an existing muxed audio track is retained', () {
    final video = {
      'media_v2': '{"audio_stream":{"aac":[{"master_url":"https://cdn.test/audio","default_stream":1}]}}',
      'media': {
        'stream': {
          'h264': [
            {
              'master_url': 'https://cdn.test/silent',
              'audio_codec': '',
              'audio_bitrate': 0,
            },
            {
              'master_url': 'https://cdn.test/muxed',
              'audio_codec': 'aac',
              'audio_bitrate': 128000,
            },
          ],
        },
      },
    };
    final sources = videoSources({'video': video});
    expect(sources[0].audioUrl, 'https://cdn.test/audio');
    expect(sources[1].audioUrl, isEmpty);
    video['media_v2'] = 'not json';
    expect(videoSources({'video': video}).first.audioUrl, isEmpty);
  });
  test(
    'note references survive card mapping; advertising records are ignored',
    () {
      final note = Note.fromItem({
        'id': '42',
        'xsec_token': 'signed',
        'xsec_source': 'pc_search',
        'note_card': {'title': '标题'},
      }, source: 'pc_feed')!;
      expect(note.reference.args, {
        'id': '42',
        'token': 'signed',
        'source': 'pc_search',
      });
      expect(
        Note.fromItem({
          'id': 'ad',
          'model_type': 'advertisement',
        }, source: 'pc_feed'),
        isNull,
      );
    },
  );
  test('note images and user avatars accept URL and nested API variants', () {
    final note = Note({
      'cover': '//cdn.test/cover',
      'image_list': ['//cdn.test/photo'],
    }, const NoteRef('1', '', 'pc_feed'));
    expect(note.coverUrl, 'https://cdn.test/cover');
    expect(Note(note.raw, note.reference).images, [
      {'url': '//cdn.test/photo'},
    ]);
    expect(
      userImageUrl({
        'imageb': {'url_default': '//cdn.test/avatar'},
      }),
      'https://cdn.test/avatar',
    );
    expect(
      userImageUrl({
        'avatar': ['https://cdn.test/avatar-2'],
      }),
      'https://cdn.test/avatar-2',
    );
    expect(
      Note.imageUrl({
        'info_list': [
          {'image_scene': 'FD_PRV_WEBP', 'url': 'https://cdn.test/preview'},
          {'image_scene': 'WB_DFT', 'url': 'https://cdn.test/default'},
        ],
      }),
      'https://cdn.test/default',
    );
    expect(
      Note({
        'cover_url': 'http://sns-webpic-qc.xhscdn.com/cover',
      }, const NoteRef('2', '', 'pc_feed')).coverUrl,
      'https://sns-webpic-qc.xhscdn.com/cover',
    );
  });
  test('XHS topics and custom emoji codes render from safe official URLs', () {
    const emojiUrl = 'https://picasso-static.xiaohongshu.com/emoji.png';
    final emojiUrls = parseXhsEmojiMap('''
      {
        "[微笑R]": "$emojiUrl",
        "[外链]": "https://images.example/emoji.png",
        "[不安全]": "https://images.example/unsafe.png"
      }
    ''');
    expect(emojiUrls, {'[微笑R]': emojiUrl});
    expect(
      normalizeXhsTopicMarkers('#教育[话题]# #数码[标签]# #普通话题#'),
      '#教育# #数码# #普通话题#',
    );
    final parts = xhsTextParts('#教育[话题]# [微笑R] [未知R]', emojiUrls);
    expect(parts.map((part) => part.text).toList(), [
      '#教育#',
      ' ',
      '[微笑R]',
      ' [未知R]',
    ]);
    expect(parts.first.topic, '教育');
    expect(parts[2].emojiUrl, emojiUrl);
    expect(parseXhsEmojiMap('not json'), isEmpty);
  });
  test(
    'media headers reuse the session UA only for official image hosts',
    () async {
      SharedPreferences.setMockInitialValues({});
      final app = AppState(await SharedPreferences.getInstance())
        ..session = {'mediaUserAgent': 'test-session-ua'};
      final cdnHeaders = redImageHeaders(
        'https://sns-webpic-qc.xhscdn.com/image',
        api: app,
      );
      expect(cdnHeaders['User-Agent'], 'test-session-ua');
      expect(cdnHeaders.containsKey('Cookie'), isFalse);
      expect(
        redImageHeaders(
          'https://images.example/image',
          api: app,
        ).containsKey('User-Agent'),
        isFalse,
      );
    },
  );
  test('media parser rejects local and credential URLs and keeps unique fallback streams', () {
    for (final url in [
      'http://example.com/a',
      'file:///tmp/a',
      'https://user:pass@example.com/a',
      'javascript:alert(1)',
    ]) {
      expect(mediaUrl(url), isEmpty);
    }
    expect(mediaUrl('//example.com/a'), 'https://example.com/a');
    expect(
      mediaUrl('http://sns-webpic-qc.xhscdn.com/a'),
      'https://sns-webpic-qc.xhscdn.com/a',
    );
    final sources = videoSources({
      'video': {
        'media': {
          'stream': {
            'h265': [
              {'master_url': 'https://cdn.test/hevc'},
            ],
            'h264': [
              {
                'master_url': 'https://cdn.test/avc',
                'backup_urls': [
                  'https://cdn.test/avc',
                  'https://cdn.test/backup',
                ],
              },
            ],
          },
        },
      },
    });
    expect(sources.map((s) => s.url), [
      'https://cdn.test/avc',
      'https://cdn.test/backup',
      'https://cdn.test/hevc',
    ]);
  });
  test('profile backgrounds and note views require explicit server data', () {
    expect(profileBackgroundUrl({}), '');
    expect(
      profileBackgroundUrl({
        'basic_info': {
          'background_image': 'http://sns-webpic-qc.xhscdn.com/bg',
        },
      }),
      'https://sns-webpic-qc.xhscdn.com/bg',
    );
    expect(
      profileBackgroundUrl({
        'backgroundImage': {'url': 'https://cdn.test/bg'},
      }),
      'https://cdn.test/bg',
    );
    expect(
      profileBackgroundUrl({'background_image': 'file:///private/bg'}),
      '',
    );
    expect(Note({}, const NoteRef('n', 't', 'pc_user')).viewCount, '');
    expect(
      Note({'view_count': 78}, const NoteRef('n', 't', 'pc_user')).viewCount,
      '78',
    );
  });
  test('notification dates and publication metadata use real fields only', () {
    expect(notificationTime(null), '');
    expect(notificationTime('NaN'), '');
    expect(
      notificationTime(
        DateTime.now().subtract(const Duration(days: 2)).millisecondsSinceEpoch,
      ),
      '2天前',
    );
    expect(notePublicationMeta({}), '');
    expect(
      notePublicationMeta({
        'time': DateTime(DateTime.now().year, 10, 7).millisecondsSinceEpoch,
        'ip_location': 'IP属地：广东',
      }),
      '10月7日  广东',
    );
  });
  test('private chat previews read the verified list fields safely', () {
    final chat = PrivateChatPreview.fromJson({
      'chat_user_id': 'private-user',
      'last_msg_content': '预览内容',
      'last_msg_time': 0,
      'info': {
        'nickname': '会话用户',
        'avatar': 'https://picasso-static.xiaohongshu.com/avatar.png',
      },
    });
    expect(chat.id, 'private-user');
    expect(chat.name, '会话用户');
    expect(chat.avatar, 'https://picasso-static.xiaohongshu.com/avatar.png');
    expect(chat.preview, '预览内容');
    expect(chat.time, isEmpty);
    expect(
      PrivateChatPreview.fromJson({
        'info': {'nickname': '外链', 'avatar': 'http://attacker.test/avatar'},
      }).avatar,
      isEmpty,
    );
  });
  test('verification API failures are distinguishable from login failures', () {
    expect(
      isVerificationError(PlatformException(code: 'verification')),
      isTrue,
    );
    expect(isVerificationError(PlatformException(code: 'login')), isFalse);
  });
  test('views keep outer card counts and creator sync accepts only own integer metrics', () {
    final note = Note.fromItem({
      'id': 'n',
      'view_count': 78,
      'note_card': {'title': 'test'},
    }, source: 'pc_user');
    expect(note!.viewCount, '78');
    expect(
      validatedViewCounts(
        {'n': 0, 'other': 30, 'negative': -1, 'decimal': 2.5},
        {'n', 'negative', 'decimal'},
      ),
      {'n': '0'},
    );
    expect(
      profileBackgroundUrl({
        'user': {'background_image': 'https://cdn.test/bg'},
      }),
      'https://cdn.test/bg',
    );
  });
}
