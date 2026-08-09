import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:csv/csv.dart';
import 'package:flutter/material.dart';
import '../file_type_helpers.dart';

/// Lit au plus [max] lignes en flux, puis referme la source.
///
/// `openRead()` ne lit que les blocs consommés : dès que [max] lignes sont
/// obtenues on rompt la boucle, et le reste du fichier n'est jamais touché.
/// Un plafond d'octets double la garde, pour le cas d'un fichier binaire sans
/// aucun saut de ligne — où « 40 lignes » vaudrait « tout le fichier ».
Future<List<String>> _firstLines(File f, int max) async {
  const maxBytes = 512 * 1024;
  final out = <String>[];
  var read = 0;
  final stream = f
      .openRead()
      .transform(
        StreamTransformer<List<int>, List<int>>.fromHandlers(
          handleData: (data, sink) {
            read += data.length;
            sink.add(data);
          },
        ),
      )
      .transform(const Utf8Decoder(allowMalformed: true))
      .transform(const LineSplitter());
  await for (final line in stream) {
    out.add(line);
    if (out.length >= max || read >= maxBytes) break;
  }
  return out;
}

Future<void> showFilePreviewSheet(
  BuildContext context,
  String path, {
  required VoidCallback onOpen,
}) async {
  final ext = fileExt(path);
  final name = path.basename;
  String preview = '';
  String type = 'text';
  try {
    if (previewExts.contains(ext)) {
      // V-H3 (audit 2026-08-02) — `readAsLines()` chargeait le fichier ENTIER
      // en mémoire pour n'en afficher que 40 lignes. Un appui long → Aperçu
      // sur un journal de 2 Go tuait l'app par OOM. On lit maintenant en flux
      // et on s'arrête dès la 40ᵉ ligne : le coût ne dépend plus de la taille
      // du fichier mais de ce qu'on affiche.
      final lines = await _firstLines(File(path), 40);
      preview = lines.join('\n');
      if (ext == 'json') {
        type = 'json';
      } else if (ext == 'csv') {
        type = 'csv';
      }
    } else {
      preview = 'Aperçu non disponible pour ce format.';
      type = 'none';
    }
  } catch (_) {
    preview = 'Impossible de lire le fichier.';
    type = 'none';
  }

  if (!context.mounted) return;
  await showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (_) => DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.55,
      minChildSize: 0.3,
      maxChildSize: 0.92,
      builder: (ctx, scrollCtrl) => Column(
        children: [
          Container(
            margin: const EdgeInsets.symmetric(vertical: 8),
            width: 40,
            height: 4,
            decoration: BoxDecoration(
              color: Colors.grey.withValues(alpha: 0.4),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 8, 8),
            child: Row(
              children: [
                Icon(
                  iconFor(File(path)),
                  color: colorFor(File(path)),
                  size: 20,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    name,
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 14,
                    ),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                TextButton(
                  onPressed: () {
                    Navigator.pop(ctx);
                    onOpen();
                  },
                  child: const Text('Ouvrir'),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: SingleChildScrollView(
              controller: scrollCtrl,
              padding: const EdgeInsets.all(14),
              child: type == 'csv'
                  ? _CsvPreview(raw: preview)
                  : SelectableText(
                      preview,
                      style: TextStyle(
                        fontFamily: type == 'none' ? null : 'monospace',
                        fontSize: 12,
                        color: type == 'none' ? Colors.grey : null,
                        height: 1.5,
                      ),
                    ),
            ),
          ),
        ],
      ),
    ),
  );
}

class _CsvPreview extends StatelessWidget {
  final String raw;
  const _CsvPreview({required this.raw});

  @override
  Widget build(BuildContext context) {
    final rows = (() {
      final head = raw.split('\n').take(11).join('\n');
      try {
        return Csv()
            .decode(head)
            .take(10)
            .map((r) => r.map((c) => c?.toString() ?? '').toList())
            .toList();
      } catch (_) {
        return raw.split('\n').take(10).map((l) => l.split(',')).toList();
      }
    })();
    if (rows.isEmpty) return const Text('Fichier vide');
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Table(
        border: TableBorder.all(color: Colors.grey.withValues(alpha: 0.3)),
        defaultColumnWidth: const IntrinsicColumnWidth(),
        children: rows.asMap().entries.map((entry) {
          final isHeader = entry.key == 0;
          return TableRow(
            decoration: isHeader
                ? BoxDecoration(
                    color: Theme.of(
                      context,
                    ).colorScheme.surfaceContainerHighest,
                  )
                : null,
            children: entry.value
                .map(
                  (cell) => Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 4,
                    ),
                    child: Text(
                      cell.trim(),
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: isHeader
                            ? FontWeight.w700
                            : FontWeight.normal,
                      ),
                    ),
                  ),
                )
                .toList(),
          );
        }).toList(),
      ),
    );
  }
}
