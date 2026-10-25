import 'dart:convert';

// Oracle for the Kotlin readers of the JSON the 2.15.x stores outside the vault: a recent file
// (`RecentFile.fromJson`, files_tech_core) and a trash entry (`TrashEntry.fromJson`). Two questions:
// 1. what does `jsonDecode` make of an object — which values are an int, a double, a bool, a String;
// 2. what do `DateTime.parse` (recents) and `DateTime.tryParse` (trash) make of a date text.
//
// Letters stand for characters that need escaping in a source file: Q double quote, B backslash.
String c(int code) => String.fromCharCode(code);

String t(String template) => template.replaceAll('Q', c(34)).replaceAll('B', c(92));

String describe(Object? v) {
  if (v == null) return 'null';
  if (v is bool) return 'bool:$v';
  if (v is int) return 'int:$v';
  if (v is double) return 'double:${v.toString()}';
  if (v is String) return 'string:' + v.codeUnits.join(',');
  if (v is List) return 'list:${v.length}';
  if (v is Map) return 'map:${v.length}';
  return 'other';
}

void main() {
  final objects = <String>[
    t('{QaQ:1}'),
    t('{QaQ:-0}'),
    t('{QaQ:1.0}'),
    t('{QaQ:1e3}'),
    t('{QaQ:1E+3}'),
    t('{QaQ:-1.5e-3}'),
    t('{QaQ:9223372036854775807}'),
    t('{QaQ:9223372036854775808}'),
    t('{QaQ:-9223372036854775808}'),
    t('{QaQ:01}'),
    t('{QaQ:1.}'),
    t('{QaQ:.5}'),
    t('{QaQ:+1}'),
    t('{QaQ:true,QbQ:false,QcQ:null}'),
    t('{QaQ:QxQ,QaQ:QyQ}'),
    t('{QaQ:[1,QxQ],QbQ:{QcQ:{}}}'),
    t('{}'),
    t('{ QaQ : 1 , QbQ : 2 }'),
    t('{QaQ:1,}'),
    t('{a:1}'),
    t('{QaQ 1}'),
    t('{QsizeQ:5,QnameQ:QxBu00e9Q}'),
    t('[1]'),
    t('{QaQ:NaN}'),
    t('{QaQ:Infinity}'),
    t('{QaQ:1}x'),
  ];
  print('OBJECTS');
  for (final text in objects) {
    String verdict;
    try {
      final v = jsonDecode(text);
      if (v is Map) {
        final keys = v.keys.toList();
        verdict = 'ACCEPT ' + keys.map((k) => '${(k as String).codeUnits.join(',')}=${describe(v[k])}').join(';');
      } else {
        verdict = 'NOTMAP ${describe(v)}';
      }
    } catch (_) {
      verdict = 'REFUSE';
    }
    print('${base64Encode(utf8.encode(text))} $verdict');
  }

  final dates = <String>[
    '2026-10-11T12:34:56.789012',
    '2026-10-11T12:34:56.789',
    '2026-10-11T12:34:56',
    '2026-10-11T12:34',
    '2026-10-11T12',
    '2026-10-11',
    '20261011',
    '20261011T123456',
    '2026-10-11 12:34:56',
    '2026-10-11T12:34:56Z',
    '2026-10-11T12:34:56.789z',
    '2026-10-11T12:34:56+02:00',
    '2026-10-11T12:34:56-0230',
    '2026-10-11T12:34:56 +02',
    '2026-10-11T12:34:56,5',
    '2026-10-11T12:34:56.1234567',
    '2026-10-11T12:34:56.123456789',
    '+002026-10-11T00:00:00',
    '-0001-01-01T00:00:00',
    '2026-13-01T00:00:00',
    '2026-02-30T00:00:00',
    '2026-10-11T24:00:00',
    '2026-10-11T23:59:60',
    '2026-10-11T12:34:56.',
    '2026-1-11T00:00:00',
    ' 2026-10-11T12:34:56',
    '2026-10-11t12:34:56',
    '2026-10-11T12:34:56+2',
    '',
    'hier',
  ];
  print('DATES');
  for (final text in dates) {
    final parsed = DateTime.tryParse(text);
    String verdict;
    if (parsed == null) {
      verdict = 'REFUSE';
    } else {
      // Fields, not an epoch: a local time depends on the zone of the machine that runs this.
      verdict = 'ACCEPT ${parsed.isUtc ? 'utc' : 'local'} ${parsed.year} ${parsed.month} ${parsed.day} '
          '${parsed.hour} ${parsed.minute} ${parsed.second} ${parsed.millisecond} ${parsed.microsecond} '
          '${parsed.toIso8601String()}';
    }
    print('${base64Encode(utf8.encode(text))} $verdict');
  }
}
