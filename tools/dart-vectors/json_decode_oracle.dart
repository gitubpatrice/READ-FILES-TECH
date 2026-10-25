import 'dart:convert';

// Oracle for the Kotlin DECODER of the plugin's JSON lists: does the 2.15.x accept this stored text as
// a list of strings, and which strings? The plugin hands the text to `jsonDecode`, and
// `SharedPreferences.getStringList` then does `(value as List).cast<String>().toList()`: anything
// refused here made the Flutter app throw.
//
// Templates use letters for the characters that need escaping in a source file: Q is a double quote,
// B a backslash, A an apostrophe. Every other special character is built from its code.
String c(int code) => String.fromCharCode(code);

String t(String template) =>
    template.replaceAll('Q', c(34)).replaceAll('B', c(92)).replaceAll('A', c(39));

void main() {
  final cases = <String>[
    t('[QaQ]'),
    t('[]'),
    t('[ ]'),
    t('[QQ]'),
    t('[QQ,QQ]'),
    c(32) + c(10) + c(9) + c(13) + t('[ QaQ , QbQ ]') + c(13) + c(10) + c(32),
    t('[AaA]'),
    t('[QaQ,]'),
    t('[,]'),
    t('[QaQ QbQ]'),
    t('[QaQ]x'),
    t('[QaQ][]'),
    t('[1]'),
    t('[null]'),
    t('{}'),
    t('[[QaQ]]'),
    t('QaQ'),
    '',
    t('[QBu00e9Q]'),
    t('[QBu00E9Q]'),
    t('[QB/Q]'),
    t('[QBbBfBnBrBtBQBBQ]'),
    t('[QBxQ]'),
    t('[QBu12Q]'),
    // Hexadecimal digits outside ASCII: Arabic-Indic nine, full-width A.
    t('[QBu00e') + c(0x0669) + t('Q]'),
    t('[QBu00') + c(0xFF21) + t('1Q]'),
    t('[QBud800Q]'),
    t('[QBud83dBude00Q]'),
    t('[Qa') + c(9) + t('bQ]'),
    t('[Qa') + c(0x1F) + t('bQ]'),
    t('[Qa') + c(0x7F) + t('bQ]'),
    t('[Qa') + c(0x2028) + c(0xE9) + c(0x1F600) + t('Q]'),
    c(0xFEFF) + t('[QaQ]'),
    c(0xA0) + t('[QaQ]'),
    t('[QaQ]') + c(0),
    t('[QaQ') + c(12) + t(',QbQ]'),
  ];
  for (final text in cases) {
    String verdict;
    try {
      final value = jsonDecode(text);
      if (value is! List) throw const FormatException('not a list');
      final list = value.cast<String>().toList();
      // Code units, not UTF-8: a lone surrogate must reach Kotlin unchanged.
      // The count first: without it `[]` and `[""]` would print the same.
      verdict = 'ACCEPT ${list.length} ' + list.map((s) => s.codeUnits.join(',')).join('|');
    } catch (_) {
      verdict = 'REFUSE';
    }
    print('${base64Encode(utf8.encode(text))} $verdict');
  }
}
