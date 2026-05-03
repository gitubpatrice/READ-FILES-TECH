import 'package:files_tech_core/files_tech_core.dart' hide CloudShareRow;
import 'package:flutter/material.dart';
import 'cloud_share_row.dart';
import 'file_viewer_router.dart';

/// Carte d'actions standard à afficher sous chaque fichier produit par un
/// outil (convert, scanner, ocr, signature, compress, exif…).
///
/// - **Ouvrir** : viewer interne Read Files Tech (txt/pdf/csv/xlsx/image…).
///   Bouton masqué si l'extension n'est pas supportée nativement.
/// - [CloudShareRow] : Ouvrir avec… (chooser système), kDrive, Proton, Partager.
class OutputActionsRow extends StatefulWidget {
  final String path;
  final String mime;
  const OutputActionsRow({
    super.key,
    required this.path,
    this.mime = 'application/octet-stream',
  });

  @override
  State<OutputActionsRow> createState() => _OutputActionsRowState();
}

class _OutputActionsRowState extends State<OutputActionsRow> {
  /// Garde anti-double-tap : tant qu'une navigation est en cours,
  /// les taps suivants sont ignorés (sinon → 2 viewers empilés).
  bool _opening = false;

  @override
  void initState() {
    super.initState();
    // Enregistre automatiquement le fichier produit dans les Récents.
    // Sans ça, l'utilisateur ne le retrouve qu'en navigant manuellement
    // dans Files Tech/<Catégorie>/, ce qui est frustrant.
    _registerAsRecent();
  }

  Future<void> _registerAsRecent() async {
    try {
      const service = RecentFilesService();
      final current = await service.load();
      await service.addOrUpdate(current, widget.path);
    } catch (_) {
      // Silencieux : si l'enregistrement échoue (path invalide, prefs
      // indispos), ce n'est pas bloquant pour l'utilisateur.
    }
  }

  Future<void> _open() async {
    if (_opening) return;
    setState(() => _opening = true);
    try {
      await FileViewerRouter.open(context, widget.path);
    } finally {
      if (mounted) setState(() => _opening = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final canView = FileViewerRouter.canViewInternally(widget.path);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (canView)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: _opening ? null : _open,
                icon: const Icon(Icons.visibility_outlined, size: 18),
                label: const Text('Ouvrir'),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                ),
              ),
            ),
          ),
        CloudShareRow(path: widget.path, mime: widget.mime),
      ],
    );
  }
}
