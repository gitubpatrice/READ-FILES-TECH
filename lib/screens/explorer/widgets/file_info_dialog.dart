import 'dart:io';
import 'package:flutter/foundation.dart' show kDebugMode, debugPrint;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../file_type_helpers.dart';

Future<void> showFileInfoDialog(
  BuildContext context,
  FileSystemEntity e,
) async {
  final name = e.path.split('/').last;
  final isDir = e is Directory;
  int size = 0;
  int items = 0;
  DateTime? modified;
  DateTime? accessed;
  bool isSymlink = false;
  try {
    final stat = e.statSync();
    modified = stat.modified;
    accessed = stat.accessed;
    isSymlink = FileSystemEntity.isLinkSync(e.path);
    if (isDir) {
      items = e.listSync().length;
    } else {
      size = stat.size;
    }
  } catch (err) {
    if (kDebugMode) debugPrint('showFileInfo ${e.path}: $err');
  }

  final ext = isDir ? '—' : (fileExt(e.path).isEmpty ? '—' : fileExt(e.path));
  final mime = isDir ? '—' : (mimeOf(fileExt(e.path)) ?? 'inconnu');

  String fmt(DateTime? d) => d == null
      ? '—'
      : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year} '
            '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';

  if (!context.mounted) return;
  await showDialog(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Row(
        children: [
          Icon(iconFor(e), color: colorFor(e), size: 20),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              name,
              style: const TextStyle(fontSize: 15),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _row('Type', isDir ? 'Dossier' : 'Fichier'),
            if (!isDir) _row('Extension', ext),
            if (!isDir) _row('Type MIME', mime),
            if (!isDir) _row('Taille', '${formatSize(size)}  ($size octets)'),
            if (isDir) _row('Éléments', '$items'),
            _row('Modifié', fmt(modified)),
            _row('Consulté', fmt(accessed)),
            if (isSymlink) _row('Lien symbolique', 'oui'),
            const SizedBox(height: 8),
            const Text(
              'Chemin',
              style: TextStyle(fontWeight: FontWeight.w600, fontSize: 12),
            ),
            const SizedBox(height: 4),
            SelectableText(
              e.path,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () {
            Clipboard.setData(ClipboardData(text: e.path));
            Navigator.pop(ctx);
          },
          child: const Text('Copier le chemin'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('OK'),
        ),
      ],
    ),
  );
}

Widget _row(String label, String value) {
  return Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 110,
          child: Text(
            label,
            style: const TextStyle(fontSize: 12, color: Colors.grey),
          ),
        ),
        Expanded(child: Text(value, style: const TextStyle(fontSize: 12))),
      ],
    ),
  );
}
