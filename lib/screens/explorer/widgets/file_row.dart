import 'dart:io';
import 'package:flutter/material.dart';
import '../file_type_helpers.dart';

/// Actions appelées depuis le menu kebab de la tuile fichier.
class FileRowActions {
  final void Function(String path) onOpen;
  final void Function(String path, String ext) onOpenSystem;
  final void Function(String path, String ext) onOpenChooser;
  final void Function(String path) onPreview;
  final void Function(String path) onEdit;
  final void Function(String path) onEditPdfTech;
  final void Function(String path) onStripExif;
  final void Function(String path, String ext) onShare;
  final void Function(String path) onSendKDrive;
  final void Function(String path) onSendProton;
  final void Function(FileSystemEntity e) onRename;
  final void Function(String path) onCopy;
  final void Function(String path) onMove;
  final void Function(FileSystemEntity e) onInfo;
  final void Function(FileSystemEntity e) onDelete;

  const FileRowActions({
    required this.onOpen,
    required this.onOpenSystem,
    required this.onOpenChooser,
    required this.onPreview,
    required this.onEdit,
    required this.onEditPdfTech,
    required this.onStripExif,
    required this.onShare,
    required this.onSendKDrive,
    required this.onSendProton,
    required this.onRename,
    required this.onCopy,
    required this.onMove,
    required this.onInfo,
    required this.onDelete,
  });
}

class FileRow extends StatelessWidget {
  final FileSystemEntity entity;
  final bool isSelected;
  final bool selectionMode;
  final int? size;
  final DateTime? modified;
  final VoidCallback onTap;
  final VoidCallback onLongPress;
  final FileRowActions actions;

  const FileRow({
    super.key,
    required this.entity,
    required this.isSelected,
    required this.selectionMode,
    required this.size,
    required this.modified,
    required this.onTap,
    required this.onLongPress,
    required this.actions,
  });

  @override
  Widget build(BuildContext context) {
    final e = entity;
    final isDir = e is Directory;
    final ext = isDir ? '' : fileExt(e.path);
    final canEdit = editableExts.contains(ext);
    final canView =
        canEdit || viewableExts.contains(ext) || imageExts.contains(ext);
    final name = e.path.split('/').last;
    final cs = Theme.of(context).colorScheme;

    return ListTile(
      dense: true,
      selected: isSelected,
      selectedTileColor: cs.primary.withValues(alpha: 0.12),
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: isSelected
            ? Container(
                width: 36,
                height: 36,
                color: cs.primary,
                child: const Icon(Icons.check, color: Colors.white, size: 20),
              )
            : (imageExts.contains(ext)
                  ? Image.file(
                      File(e.path),
                      width: 36,
                      height: 36,
                      fit: BoxFit.cover,
                      cacheWidth: 72,
                      cacheHeight: 72,
                      gaplessPlayback: true,
                      filterQuality: FilterQuality.low,
                      errorBuilder: (_, _, _) => _iconBox(e),
                    )
                  : _iconBox(e)),
      ),
      title: Text(
        name,
        style: const TextStyle(fontSize: 13),
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Row(
        children: [
          if (size != null)
            Text(formatSize(size!), style: const TextStyle(fontSize: 11)),
          if (size != null && modified != null)
            const Text(
              ' · ',
              style: TextStyle(fontSize: 11, color: Colors.grey),
            ),
          if (modified != null)
            Text(
              '${modified!.day.toString().padLeft(2, '0')}/'
              '${modified!.month.toString().padLeft(2, '0')}/'
              '${modified!.year}',
              style: const TextStyle(fontSize: 11, color: Colors.grey),
            ),
        ],
      ),
      trailing: selectionMode
          ? null
          : isDir
          ? _dirMenu(e)
          : _fileMenu(e, ext, canEdit: canEdit, canView: canView),
      onTap: onTap,
      onLongPress: onLongPress,
    );
  }

  Widget _iconBox(FileSystemEntity e) {
    return Container(
      width: 36,
      height: 36,
      color: colorFor(e).withValues(alpha: 0.12),
      child: Icon(iconFor(e), color: colorFor(e), size: 20),
    );
  }

  Widget _dirMenu(FileSystemEntity e) {
    return PopupMenuButton<String>(
      onSelected: (v) {
        if (v == 'rename') actions.onRename(e);
        if (v == 'info') actions.onInfo(e);
        if (v == 'delete') actions.onDelete(e);
      },
      itemBuilder: (_) => const [
        PopupMenuItem(
          value: 'rename',
          child: ListTile(
            leading: Icon(Icons.drive_file_rename_outline),
            title: Text('Renommer'),
          ),
        ),
        PopupMenuItem(
          value: 'info',
          child: ListTile(
            leading: Icon(Icons.info_outline),
            title: Text('Informations'),
          ),
        ),
        PopupMenuItem(
          value: 'delete',
          child: ListTile(
            leading: Icon(Icons.delete_outline, color: Colors.red),
            title: Text('Supprimer'),
          ),
        ),
      ],
    );
  }

  Widget _fileMenu(
    FileSystemEntity e,
    String ext, {
    required bool canEdit,
    required bool canView,
  }) {
    return PopupMenuButton<String>(
      onSelected: (v) {
        switch (v) {
          case 'open':
            actions.onOpen(e.path);
          case 'open_system':
            actions.onOpenSystem(e.path, ext);
          case 'open_chooser':
            actions.onOpenChooser(e.path, ext);
          case 'preview':
            actions.onPreview(e.path);
          case 'edit':
            actions.onEdit(e.path);
          case 'edit_pdftech':
            actions.onEditPdfTech(e.path);
          case 'strip_exif':
            actions.onStripExif(e.path);
          case 'kdrive':
            actions.onSendKDrive(e.path);
          case 'proton':
            actions.onSendProton(e.path);
          case 'share':
            actions.onShare(e.path, ext);
          case 'rename':
            actions.onRename(e);
          case 'copy':
            actions.onCopy(e.path);
          case 'move':
            actions.onMove(e.path);
          case 'info':
            actions.onInfo(e);
          case 'delete':
            actions.onDelete(e);
        }
      },
      itemBuilder: (_) => [
        if (canView)
          const PopupMenuItem(
            value: 'open',
            child: ListTile(
              leading: Icon(Icons.open_in_new),
              title: Text('Ouvrir'),
            ),
          ),
        if (!canView)
          const PopupMenuItem(
            value: 'open_system',
            child: ListTile(
              leading: Icon(Icons.open_in_new),
              title: Text('Ouvrir'),
            ),
          ),
        const PopupMenuItem(
          value: 'open_chooser',
          child: ListTile(
            leading: Icon(Icons.apps_outlined),
            title: Text('Ouvrir avec…'),
          ),
        ),
        const PopupMenuItem(
          value: 'preview',
          child: ListTile(
            leading: Icon(Icons.visibility_outlined),
            title: Text('Aperçu'),
          ),
        ),
        if (canEdit)
          const PopupMenuItem(
            value: 'edit',
            child: ListTile(
              leading: Icon(Icons.edit_outlined),
              title: Text('Éditer'),
            ),
          ),
        if (ext == 'pdf')
          const PopupMenuItem(
            value: 'edit_pdftech',
            child: ListTile(
              leading: Icon(Icons.picture_as_pdf_outlined, color: Colors.red),
              title: Text('Éditer dans PDF Tech'),
            ),
          ),
        if (imageExts.contains(ext))
          const PopupMenuItem(
            value: 'strip_exif',
            child: ListTile(
              leading: Icon(Icons.cleaning_services_outlined),
              title: Text('Effacer les métadonnées'),
            ),
          ),
        const PopupMenuItem(
          value: 'share',
          child: ListTile(leading: Icon(Icons.share), title: Text('Partager')),
        ),
        const PopupMenuItem(
          value: 'kdrive',
          child: ListTile(
            leading: Icon(
              Icons.cloud_upload_outlined,
              color: Color(0xFF0098FF),
            ),
            title: Text('Envoyer vers kDrive'),
          ),
        ),
        const PopupMenuItem(
          value: 'proton',
          child: ListTile(
            leading: Icon(
              Icons.cloud_upload_outlined,
              color: Color(0xFF6D4AFF),
            ),
            title: Text('Envoyer vers Proton Drive'),
          ),
        ),
        const PopupMenuDivider(),
        const PopupMenuItem(
          value: 'rename',
          child: ListTile(
            leading: Icon(Icons.drive_file_rename_outline),
            title: Text('Renommer'),
          ),
        ),
        const PopupMenuItem(
          value: 'copy',
          child: ListTile(
            leading: Icon(Icons.copy_outlined),
            title: Text('Copier vers…'),
          ),
        ),
        const PopupMenuItem(
          value: 'move',
          child: ListTile(
            leading: Icon(Icons.drive_file_move_outlined),
            title: Text('Déplacer vers…'),
          ),
        ),
        const PopupMenuItem(
          value: 'info',
          child: ListTile(
            leading: Icon(Icons.info_outline),
            title: Text('Informations'),
          ),
        ),
        const PopupMenuItem(
          value: 'delete',
          child: ListTile(
            leading: Icon(Icons.delete_outline, color: Colors.red),
            title: Text('Supprimer'),
          ),
        ),
      ],
    );
  }
}
