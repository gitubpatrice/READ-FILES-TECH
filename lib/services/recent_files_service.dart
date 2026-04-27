import 'dart:io';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/recent_file.dart';

class RecentFilesService {
  static const _key = 'recent_files';
  static const _maxFiles = 20;

  Future<List<RecentFile>> load() async {
    final prefs = await SharedPreferences.getInstance();
    final list = prefs.getStringList(_key) ?? [];
    return list
        .map((s) => RecentFile.fromJsonString(s))
        .where((f) => File(f.path).existsSync())
        .toList()
      ..sort((a, b) => b.lastOpened.compareTo(a.lastOpened));
  }

  Future<List<RecentFile>> addOrUpdate(List<RecentFile> current, String path) async {
    final file = File(path);
    if (!file.existsSync()) return current;
    final name = path.split(RegExp(r'[/\\]')).last;
    final size = file.lengthSync();
    final existing = current.firstWhere((f) => f.path == path,
        orElse: () => RecentFile(path: path, name: name, lastOpened: DateTime.now(), sizeBytes: size));
    final updated = [
      RecentFile(path: path, name: name, lastOpened: DateTime.now(),
          sizeBytes: size, isFavorite: existing.isFavorite),
      ...current.where((f) => f.path != path),
    ];
    final trimmed = updated.take(_maxFiles).toList();
    await _save(trimmed);
    return trimmed;
  }

  Future<List<RecentFile>> remove(List<RecentFile> current, String path) async {
    final updated = current.where((f) => f.path != path).toList();
    await _save(updated);
    return updated;
  }

  Future<List<RecentFile>> toggleFavorite(List<RecentFile> current, String path) async {
    final updated = current
        .map((f) => f.path == path ? f.copyWith(isFavorite: !f.isFavorite) : f)
        .toList();
    await _save(updated);
    return updated;
  }

  Future<void> _save(List<RecentFile> files) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(_key, files.map((f) => f.toJsonString()).toList());
  }
}
