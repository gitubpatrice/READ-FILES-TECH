import 'package:flutter/material.dart';
import 'cloud_share_row.dart';
import 'file_viewer_router.dart';

/// Carte d'actions standard à afficher sous chaque fichier produit par un
/// outil (convert, scanner, ocr, signature, compress, exif…).
///
/// Trois lignes d'actions :
/// 1. **Ouvrir** : viewer interne Read Files Tech (txt/pdf/csv/xlsx/image…).
///    Bouton masqué si l'extension n'est pas supportée nativement.
/// 2. [CloudShareRow] : Ouvrir avec… (chooser système), kDrive, Proton, Partager.
///
/// L'objectif est de ne plus laisser l'utilisateur avec « Sauvegardé : path »
/// sans moyen de visualiser ce qu'il vient de produire.
class OutputActionsRow extends StatelessWidget {
  final String path;
  final String mime;
  const OutputActionsRow({
    super.key,
    required this.path,
    this.mime = 'application/octet-stream',
  });

  @override
  Widget build(BuildContext context) {
    final canView = FileViewerRouter.canViewInternally(path);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (canView)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: () => FileViewerRouter.open(context, path),
                icon: const Icon(Icons.visibility_outlined, size: 18),
                label: const Text('Ouvrir'),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 10),
                ),
              ),
            ),
          ),
        CloudShareRow(path: path, mime: mime),
      ],
    );
  }
}
