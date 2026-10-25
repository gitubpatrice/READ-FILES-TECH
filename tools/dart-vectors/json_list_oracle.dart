import 'dart:convert';

// Oracle for the Kotlin encoder: what Dart's jsonEncode writes for a list of strings, the format the
// shared_preferences_android 2.4.27 plugin stores. Special characters are built with fromCharCode
// so no escape sequence is involved in writing this file.
String c(int code) => String.fromCharCode(code);

void main() {
  final bs = c(92);
  final quote = c(34);
  final cases = <List<String>>[
    [],
    ['a', 'b'],
    ['/storage/emulated/0/Files Tech/' + c(0xE9) + 't' + c(0xE9) + '.pdf'],
    ['quote ' + quote + ' backslash ' + bs + ' slash / tab ' + c(9) + ' nl ' + c(10) + ' cr ' + c(13)],
    ['ctrl ' + c(1) + ' ' + c(0x1F) + ' del ' + c(0x7F) + ' nbsp ' + c(0xA0) + ' ls ' + c(0x2028) + ' emoji ' + c(0x1F600)],
    ['{' + quote + 'path' + quote + ':' + quote + '/a/b.txt' + quote + '}'],
    ['bs ' + c(8) + ' ff ' + c(12) + ' vt ' + c(11) + ' nul ' + c(0), ''],
  ];
  for (final list in cases) {
    // Base64 of the UTF-8 bytes of the INPUT, then of the OUTPUT: both travel to Kotlin byte-exact.
    print('${base64Encode(utf8.encode(jsonEncode(list)))} ${base64Encode(utf8.encode(list.join(c(0))))}');
  }
}
