import 'dart:convert';

class RecentFile {
  final String path;
  final String name;
  final DateTime lastOpened;
  final int sizeBytes;
  final bool isFavorite;

  const RecentFile({
    required this.path,
    required this.name,
    required this.lastOpened,
    required this.sizeBytes,
    this.isFavorite = false,
  });

  RecentFile copyWith({bool? isFavorite}) => RecentFile(
        path: path, name: name, lastOpened: lastOpened,
        sizeBytes: sizeBytes, isFavorite: isFavorite ?? this.isFavorite,
      );

  String get extension => name.contains('.')
      ? name.split('.').last.toLowerCase()
      : '';

  Map<String, dynamic> toJson() => {
        'path': path, 'name': name,
        'lastOpened': lastOpened.toIso8601String(),
        'sizeBytes': sizeBytes, 'isFavorite': isFavorite,
      };

  factory RecentFile.fromJson(Map<String, dynamic> json) => RecentFile(
        path: json['path'] as String,
        name: json['name'] as String,
        lastOpened: DateTime.parse(json['lastOpened'] as String),
        sizeBytes: json['sizeBytes'] as int,
        isFavorite: json['isFavorite'] as bool? ?? false,
      );

  String toJsonString() => jsonEncode(toJson());
  factory RecentFile.fromJsonString(String s) =>
      RecentFile.fromJson(jsonDecode(s) as Map<String, dynamic>);

  String get formattedSize {
    if (sizeBytes < 1024) return '$sizeBytes B';
    if (sizeBytes < 1024 * 1024) return '${(sizeBytes / 1024).toStringAsFixed(1)} Ko';
    return '${(sizeBytes / (1024 * 1024)).toStringAsFixed(1)} Mo';
  }
}
