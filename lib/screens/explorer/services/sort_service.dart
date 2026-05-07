import 'dart:io';
import 'package:files_tech_core/files_tech_core.dart';

enum SortMode { name, date, size }

/// Tri pur sur les FileSystemEntity. Les dossiers passent toujours en premier.
/// Les métadonnées (size, mtime) sont fournies via callbacks pour permettre
/// l'utilisation d'un cache externe (cf. _statCache dans l'écran).
class SortService {
  SortMode mode;

  SortService({this.mode = SortMode.name});

  static SortMode fromString(String s) {
    switch (s) {
      case 'size':
        return SortMode.size;
      case 'date':
        return SortMode.date;
      default:
        return SortMode.name;
    }
  }

  static String toKey(SortMode m) {
    switch (m) {
      case SortMode.size:
        return 'size';
      case SortMode.date:
        return 'date';
      case SortMode.name:
        return 'name';
    }
  }

  void sort(
    List<FileSystemEntity> entries, {
    required int Function(FileSystemEntity) sizeOf,
    required int Function(FileSystemEntity) modifiedOf,
  }) {
    // Schwartzian transform : pré-calcule basename.toLowerCase() une seule
    // fois par path (évite O(n log n) splits + .toLowerCase pendant le tri).
    final Map<String, String> lowerNameCache = {};
    if (mode == SortMode.name) {
      for (final e in entries) {
        try {
          lowerNameCache[e.path] = PathSafe.basename(e.path).toLowerCase();
        } catch (_) {
          lowerNameCache[e.path] = e.path.toLowerCase();
        }
      }
    }
    entries.sort((a, b) {
      final aDir = a is Directory;
      final bDir = b is Directory;
      if (aDir != bDir) return aDir ? -1 : 1;
      switch (mode) {
        case SortMode.size:
          return sizeOf(b).compareTo(sizeOf(a));
        case SortMode.date:
          return modifiedOf(b).compareTo(modifiedOf(a));
        case SortMode.name:
          return lowerNameCache[a.path]!.compareTo(lowerNameCache[b.path]!);
      }
    });
  }
}
