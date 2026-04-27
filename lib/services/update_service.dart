import 'package:http/http.dart' as http;
import 'dart:convert';

class UpdateService {
  static const _owner   = 'gitubpatrice';
  static const _repo    = 'read-files-tech';
  static const _current = '1.7.1';

  Future<UpdateInfo?> checkForUpdate() async {
    try {
      final uri = Uri.parse(
          'https://api.github.com/repos/$_owner/$_repo/releases/latest');
      final response = await http.get(uri,
          headers: {'Accept': 'application/vnd.github+json'})
          .timeout(const Duration(seconds: 8));
      if (response.statusCode != 200) return null;
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final tag = (data['tag_name'] as String).replaceFirst('v', '');
      if (!_isNewer(tag, _current)) return null;
      return UpdateInfo(
        version: tag,
        body: data['body'] as String? ?? '',
      );
    } catch (_) {
      return null;
    }
  }

  bool _isNewer(String remote, String local) {
    final r = remote.split('.').map(int.tryParse).toList();
    final l = local.split('.').map(int.tryParse).toList();
    for (int i = 0; i < 3; i++) {
      final rv = i < r.length ? (r[i] ?? 0) : 0;
      final lv = i < l.length ? (l[i] ?? 0) : 0;
      if (rv > lv) return true;
      if (rv < lv) return false;
    }
    return false;
  }
}

class UpdateInfo {
  final String version;
  final String body;
  const UpdateInfo({required this.version, required this.body});
}
