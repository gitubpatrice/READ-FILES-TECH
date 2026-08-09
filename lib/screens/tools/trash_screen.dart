import 'package:flutter/material.dart';

import '../../services/trash_service.dart';
import '../../utils/snack_utils.dart';
import '../../widgets/danger_style.dart';
import '../explorer/file_type_helpers.dart';
import '../explorer/widgets/explorer_dialogs.dart';

/// Corbeille : éléments mis de côté par l'explorateur, restaurables tant que
/// l'utilisateur ne les supprime pas définitivement. Aucune purge automatique.
class TrashScreen extends StatefulWidget {
  const TrashScreen({super.key});

  @override
  State<TrashScreen> createState() => _TrashScreenState();
}

class _TrashScreenState extends State<TrashScreen> {
  final TrashService _trash = TrashService();
  List<TrashEntry> _entries = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    List<TrashEntry> entries;
    try {
      entries = await _trash.list();
    } catch (_) {
      entries = const [];
    }
    if (!mounted) return;
    setState(() {
      _entries = entries;
      _loading = false;
    });
  }

  Future<void> _restore(TrashEntry e) async {
    final snack = SnackTarget.of(context);
    try {
      final dest = await _trash.restore(e);
      await _load();
      snack.info('Restauré : ${dest.basename}');
    } catch (ex) {
      // Un échec de restauration est une erreur, et se présentait comme un
      // bandeau neutre — indiscernable du succès juste au-dessus.
      snack.error('Restauration impossible : $ex');
    }
  }

  Future<void> _deleteForever(TrashEntry e) async {
    final ok = await confirmDelete(
      context,
      title: 'Supprimer définitivement',
      message:
          'Supprimer définitivement "${e.name}" ?\nCette action est irréversible.',
    );
    if (!ok || !mounted) return;
    final snack = SnackTarget.of(context);
    try {
      await _trash.deleteForever(e);
      await _load();
      snack.info('Supprimé définitivement');
    } catch (ex) {
      snack.error('Erreur : $ex');
    }
  }

  Future<void> _emptyAll() async {
    final count = _entries.length;
    if (count == 0) return;
    final ok = await confirmDelete(
      context,
      title: 'Vider la corbeille',
      message:
          'Supprimer définitivement $count élément${count > 1 ? 's' : ''} ?\n'
          'Cette action est irréversible.',
    );
    if (!ok || !mounted) return;
    final snack = SnackTarget.of(context);
    final r = await _trash.emptyAll(_entries);
    await _load();
    final message =
        '${r.ok} supprimé${r.ok > 1 ? 's' : ''}'
        '${r.fail > 0 ? ' · ${r.fail} erreur(s)' : ''}';
    // Un vidage partiel n'est pas un succès : des éléments sont restés, et le
    // bandeau neutre le disait du même ton que « tout est parti ».
    if (r.fail > 0) {
      snack.error(message);
    } else {
      snack.info(message);
    }
  }

  int get _totalSize =>
      _entries.fold(0, (sum, e) => sum + (e.size > 0 ? e.size : 0));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Corbeille', style: TextStyle(fontSize: 16)),
            Text(
              _loading
                  ? '…'
                  : '${_entries.length} élément${_entries.length > 1 ? 's' : ''}'
                        ' · ${formatSize(_totalSize)}',
              style: const TextStyle(fontSize: 11, color: Colors.grey),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Actualiser',
            onPressed: _load,
          ),
          IconButton(
            icon: const Icon(Icons.delete_forever, color: kDangerRed),
            tooltip: 'Vider la corbeille',
            onPressed: _entries.isEmpty ? null : _emptyAll,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _entries.isEmpty
          ? const _EmptyTrash()
          : RefreshIndicator(
              onRefresh: _load,
              child: ListView.builder(
                physics: const AlwaysScrollableScrollPhysics(),
                itemCount: _entries.length,
                itemBuilder: (_, i) => _row(_entries[i]),
              ),
            ),
    );
  }

  Widget _row(TrashEntry e) {
    final d = e.deletedAt;
    final date =
        '${d.day.toString().padLeft(2, '0')}/'
        '${d.month.toString().padLeft(2, '0')}/${d.year} '
        '${d.hour.toString().padLeft(2, '0')}:'
        '${d.minute.toString().padLeft(2, '0')}';
    return ListTile(
      dense: true,
      leading: Icon(
        e.isDir ? Icons.folder_outlined : Icons.insert_drive_file_outlined,
        color: Colors.grey,
      ),
      title: Text(
        e.name,
        style: const TextStyle(fontSize: 13),
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        '${e.size >= 0 ? '${formatSize(e.size)} · ' : ''}$date\n${e.originalPath}',
        style: const TextStyle(fontSize: 11, color: Colors.grey),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      isThreeLine: true,
      trailing: PopupMenuButton<String>(
        onSelected: (v) {
          if (v == 'restore') _restore(e);
          if (v == 'delete') _deleteForever(e);
        },
        itemBuilder: (_) => const [
          PopupMenuItem(
            value: 'restore',
            child: ListTile(
              leading: Icon(Icons.restore_from_trash_outlined),
              title: Text('Restaurer'),
            ),
          ),
          PopupMenuItem(
            value: 'delete',
            child: ListTile(
              leading: Icon(Icons.delete_forever, color: kDangerRed),
              title: Text('Supprimer définitivement'),
            ),
          ),
        ],
      ),
    );
  }
}

class _EmptyTrash extends StatelessWidget {
  const _EmptyTrash();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.delete_outline, size: 48, color: Colors.grey),
          SizedBox(height: 12),
          Text('La corbeille est vide', style: TextStyle(color: Colors.grey)),
          SizedBox(height: 4),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 32),
            child: Text(
              'Les éléments mis à la corbeille depuis l\'explorateur '
              'restent ici jusqu\'à ce que vous les supprimiez.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 12, color: Colors.grey),
            ),
          ),
        ],
      ),
    );
  }
}
