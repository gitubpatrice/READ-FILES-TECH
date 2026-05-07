import 'package:flutter/material.dart';

class ExplorerEmptyState extends StatelessWidget {
  final bool permissionDenied;
  final bool hasExtensionFilter;
  final Set<String>? extensionFilter;
  final int totalEntries;
  final VoidCallback onRequestAllFiles;

  const ExplorerEmptyState({
    super.key,
    required this.permissionDenied,
    required this.hasExtensionFilter,
    required this.extensionFilter,
    required this.totalEntries,
    required this.onRequestAllFiles,
  });

  @override
  Widget build(BuildContext context) {
    if (permissionDenied) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            children: [
              Icon(
                Icons.folder_off_outlined,
                size: 64,
                color: Theme.of(context).colorScheme.error,
              ),
              const SizedBox(height: 16),
              const Text(
                'Accès aux fichiers refusé',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              const Text(
                'Pour afficher les fichiers de ce dossier, '
                'autorisez l\'accès à tous les fichiers '
                'dans les Réglages.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.grey, fontSize: 13),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: onRequestAllFiles,
                icon: const Icon(Icons.settings),
                label: const Text('Ouvrir les Réglages'),
              ),
            ],
          ),
        ),
      );
    }
    final filtered = hasExtensionFilter && totalEntries > 0;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          children: [
            Icon(
              filtered ? Icons.filter_alt_outlined : Icons.folder_open_outlined,
              size: 56,
              color: Colors.grey.shade400,
            ),
            const SizedBox(height: 12),
            Text(
              filtered ? 'Aucun fichier compatible' : 'Dossier vide',
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
            ),
            if (filtered &&
                extensionFilter != null &&
                extensionFilter!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                'Ce dossier contient $totalEntries '
                'élément${totalEntries > 1 ? 's' : ''} '
                'mais aucun ne correspond au filtre '
                '(${extensionFilter!.map((e) => '.$e').join(', ')}).',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
