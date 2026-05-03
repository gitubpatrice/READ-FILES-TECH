import 'dart:async';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../../services/global_search_service.dart';
import '../explorer/file_explorer_screen.dart';

/// Recherche globale sur l'ensemble du stockage (ou un dossier choisi),
/// par nom et/ou contenu. Stream les résultats au fur et à mesure pour rester
/// fluide même sur 50k fichiers.
class GlobalSearchScreen extends StatefulWidget {
  const GlobalSearchScreen({super.key});

  @override
  State<GlobalSearchScreen> createState() => _GlobalSearchScreenState();
}

class _GlobalSearchScreenState extends State<GlobalSearchScreen> {
  final _service = GlobalSearchService();
  final _nameCtrl = TextEditingController();
  final _contentCtrl = TextEditingController();
  String _root = '/storage/emulated/0';
  bool _searching = false;
  int _scanned = 0;
  final List<SearchHit> _results = [];
  StreamSubscription? _sub;

  @override
  void dispose() {
    _service.cancel();
    _sub?.cancel();
    _nameCtrl.dispose();
    _contentCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickRoot() async {
    final dir = await FilePicker.platform.getDirectoryPath();
    if (dir != null) setState(() => _root = dir);
  }

  void _start() {
    final name = _nameCtrl.text.trim();
    final content = _contentCtrl.text.trim();
    if (name.isEmpty && content.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Saisissez un nom OU un contenu à rechercher'),
        ),
      );
      return;
    }
    _sub?.cancel();
    setState(() {
      _results.clear();
      _scanned = 0;
      _searching = true;
    });
    final stream = _service.search(
      SearchQuery(
        rootPath: _root,
        namePattern: name.isEmpty ? null : name,
        contentPattern: content.isEmpty ? null : content,
      ),
    );
    _sub = stream.listen(
      (event) {
        if (event is SearchHit) {
          setState(() => _results.add(event));
        } else if (event is int) {
          setState(() => _scanned = event);
        }
      },
      onError: (e) {
        if (!mounted) return;
        setState(() => _searching = false);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Erreur : $e')));
      },
      onDone: () {
        if (!mounted) return;
        setState(() => _searching = false);
      },
    );
  }

  void _stop() {
    _service.cancel();
    if (mounted) setState(() => _searching = false);
  }

  void _open(SearchHit hit) {
    // Ouvre le dossier parent dans l'explorateur, plus utile que d'ouvrir
    // le fichier directement (cohérent avec la philosophie "explorateur").
    final parent = hit.path.substring(
      0,
      hit.path.lastIndexOf(RegExp(r'[/\\]')),
    );
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => FileExplorerScreen(initialPath: parent),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Recherche globale'),
        actions: [
          if (_results.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Center(
                child: Text(
                  '${_results.length} résultat${_results.length > 1 ? 's' : ''}',
                  style: const TextStyle(fontSize: 12),
                ),
              ),
            ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
            child: Column(
              children: [
                TextField(
                  controller: _nameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Nom du fichier contient',
                    hintText: 'ex : facture',
                    prefixIcon: Icon(Icons.title),
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                  onSubmitted: (_) => _start(),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _contentCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Contenu contient (texte uniquement)',
                    hintText: 'ex : SIRET',
                    prefixIcon: Icon(Icons.find_in_page_outlined),
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                  onSubmitted: (_) => _start(),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    IconButton(
                      tooltip: 'Choisir le dossier de recherche',
                      icon: const Icon(Icons.folder_open),
                      onPressed: _searching ? null : _pickRoot,
                    ),
                    Expanded(
                      child: Text(
                        _root,
                        style: const TextStyle(
                          fontSize: 11,
                          color: Colors.grey,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    if (!_searching)
                      FilledButton.icon(
                        onPressed: _start,
                        icon: const Icon(Icons.search),
                        label: const Text('Lancer'),
                      )
                    else
                      FilledButton.icon(
                        style: FilledButton.styleFrom(
                          backgroundColor: Colors.red,
                        ),
                        onPressed: _stop,
                        icon: const Icon(Icons.stop),
                        label: const Text('Arrêter'),
                      ),
                  ],
                ),
              ],
            ),
          ),
          if (_searching)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(
                children: [
                  const SizedBox(
                    width: 12,
                    height: 12,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    'Scan : $_scanned fichiers — ${_results.length} trouvés',
                    style: const TextStyle(fontSize: 11, color: Colors.grey),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 4),
          Expanded(
            child: _results.isEmpty
                ? Center(
                    child: Text(
                      _searching
                          ? 'Recherche…'
                          : 'Aucun résultat — saisissez vos critères',
                      style: const TextStyle(color: Colors.grey),
                    ),
                  )
                : ListView.builder(
                    itemCount: _results.length,
                    itemBuilder: (_, i) {
                      final hit = _results[i];
                      final name = hit.path.split(RegExp(r'[/\\]')).last;
                      final parent = hit.path.substring(
                        0,
                        hit.path.lastIndexOf(RegExp(r'[/\\]')),
                      );
                      return ListTile(
                        dense: true,
                        leading: const Icon(
                          Icons.insert_drive_file_outlined,
                          size: 20,
                        ),
                        title: Text(
                          name,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 13),
                        ),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              parent,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 10,
                                color: Colors.grey,
                              ),
                            ),
                            if (hit.snippet != null)
                              Padding(
                                padding: const EdgeInsets.only(top: 2),
                                child: Text(
                                  hit.snippet!,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontFamily: 'monospace',
                                    color: Theme.of(
                                      context,
                                    ).colorScheme.primary,
                                  ),
                                ),
                              ),
                          ],
                        ),
                        trailing: Text(
                          _fmt(hit.size),
                          style: const TextStyle(
                            fontSize: 10,
                            color: Colors.grey,
                          ),
                        ),
                        onTap: () => _open(hit),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  String _fmt(int b) {
    if (b < 1024) return '$b B';
    if (b < 1024 * 1024) return '${(b / 1024).toStringAsFixed(0)} KB';
    return '${(b / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
