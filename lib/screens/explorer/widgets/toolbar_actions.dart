import 'package:flutter/material.dart';

import '../../../widgets/danger_style.dart';

/// Actions de l'AppBar en mode sélection multiple.
class SelectionToolbarActions extends StatelessWidget {
  final VoidCallback onSelectAll;
  final VoidCallback onShare;
  final VoidCallback onCopy;
  final VoidCallback onMove;
  final VoidCallback onBulkRename;

  /// Suppression douce (corbeille) — réversible.
  final VoidCallback onTrash;

  /// Suppression définitive — confirmation rouge côté écran.
  final VoidCallback onDelete;

  const SelectionToolbarActions({
    super.key,
    required this.onSelectAll,
    required this.onShare,
    required this.onCopy,
    required this.onMove,
    required this.onBulkRename,
    required this.onTrash,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          icon: const Icon(Icons.select_all),
          tooltip: 'Tout sélectionner',
          onPressed: onSelectAll,
        ),
        IconButton(
          icon: const Icon(Icons.share),
          tooltip: 'Partager',
          onPressed: onShare,
        ),
        IconButton(
          icon: const Icon(Icons.copy_outlined),
          tooltip: 'Copier vers…',
          onPressed: onCopy,
        ),
        IconButton(
          icon: const Icon(Icons.drive_file_move_outlined),
          tooltip: 'Déplacer vers…',
          onPressed: onMove,
        ),
        IconButton(
          icon: const Icon(Icons.drive_file_rename_outline),
          tooltip: 'Renommer en masse',
          onPressed: onBulkRename,
        ),
        // Menu plutôt qu'un bouton direct : la suppression définitive ne doit
        // pas être atteignable en un seul appui depuis la barre d'outils.
        PopupMenuButton<String>(
          icon: const Icon(Icons.delete_outline, color: kDangerRed),
          tooltip: 'Supprimer',
          onSelected: (v) {
            if (v == 'trash') onTrash();
            if (v == 'delete') onDelete();
          },
          itemBuilder: (_) => const [
            PopupMenuItem(
              value: 'trash',
              child: ListTile(
                leading: Icon(Icons.delete_outline),
                title: Text('Mettre à la corbeille'),
              ),
            ),
            PopupMenuItem(
              value: 'delete',
              child: ListTile(
                leading: Icon(Icons.delete_forever, color: kDangerRed),
                title: Text(
                  'Supprimer définitivement',
                  style: TextStyle(color: kDangerRed),
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

/// Actions de l'AppBar en mode normal (refresh, hidden, sort).
class BrowseToolbarActions extends StatelessWidget {
  final bool showHidden;
  final String sortKey;
  final VoidCallback onRefresh;
  final VoidCallback onToggleHidden;
  final VoidCallback onOpenTrash;
  final ValueChanged<String> onSortSelected;

  const BrowseToolbarActions({
    super.key,
    required this.showHidden,
    required this.sortKey,
    required this.onRefresh,
    required this.onToggleHidden,
    required this.onOpenTrash,
    required this.onSortSelected,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          icon: const Icon(Icons.refresh),
          tooltip: 'Actualiser',
          onPressed: onRefresh,
        ),
        IconButton(
          icon: Icon(showHidden ? Icons.visibility_off : Icons.visibility),
          tooltip: showHidden
              ? 'Masquer fichiers cachés'
              : 'Afficher fichiers cachés',
          onPressed: onToggleHidden,
        ),
        IconButton(
          icon: const Icon(Icons.delete_outline),
          tooltip: 'Corbeille',
          onPressed: onOpenTrash,
        ),
        PopupMenuButton<String>(
          icon: const Icon(Icons.sort),
          onSelected: onSortSelected,
          itemBuilder: (_) => const [
            PopupMenuItem(
              value: 'name',
              child: ListTile(
                leading: Icon(Icons.sort_by_alpha),
                title: Text('Nom'),
              ),
            ),
            PopupMenuItem(
              value: 'date',
              child: ListTile(
                leading: Icon(Icons.access_time),
                title: Text('Date'),
              ),
            ),
            PopupMenuItem(
              value: 'size',
              child: ListTile(
                leading: Icon(Icons.data_usage),
                title: Text('Taille'),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
