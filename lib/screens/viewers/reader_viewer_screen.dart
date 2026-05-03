import 'dart:io';
import 'package:flutter/material.dart';
import '../../services/reader_service.dart';

/// Affiche du HTML brut OU un EPUB en mode lecture désencombré.
/// - HTML : un seul "chapitre" avec tous les blocs.
/// - EPUB : chapitres séparés, navigation précédent/suivant.
class ReaderViewerScreen extends StatefulWidget {
  final String path;
  final bool isEpub;
  const ReaderViewerScreen({
    super.key,
    required this.path,
    required this.isEpub,
  });

  @override
  State<ReaderViewerScreen> createState() => _ReaderViewerScreenState();
}

class _ReaderViewerScreenState extends State<ReaderViewerScreen> {
  final _service = ReaderService();
  List<EpubChapter> _chapters = [];
  int _index = 0;
  double _fontSize = 16;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      if (widget.isEpub) {
        final chapters = await _service.readEpub(File(widget.path));
        if (!mounted) return;
        setState(() {
          _chapters = chapters.isEmpty
              ? [EpubChapter(title: 'Vide', blocks: [])]
              : chapters;
          _loading = false;
        });
      } else {
        final raw = await File(widget.path).readAsString();
        final blocks = _service.htmlToBlocks(raw);
        if (!mounted) return;
        setState(() {
          _chapters = [
            EpubChapter(
              title: widget.path.split(RegExp(r'[/\\]')).last,
              blocks: blocks,
            ),
          ];
          _loading = false;
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e';
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_error != null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Mode lecture')),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(
              'Erreur : $_error',
              style: const TextStyle(color: Colors.red),
            ),
          ),
        ),
      );
    }
    final ch = _chapters[_index];
    return Scaffold(
      appBar: AppBar(
        title: Text(ch.title, overflow: TextOverflow.ellipsis),
        actions: [
          PopupMenuButton<double>(
            icon: const Icon(Icons.text_fields),
            tooltip: 'Taille du texte',
            onSelected: (v) => setState(() => _fontSize = v),
            itemBuilder: (_) => [12, 14, 16, 18, 20, 24]
                .map(
                  (s) =>
                      PopupMenuItem(value: s.toDouble(), child: Text('$s pt')),
                )
                .toList(),
          ),
          if (_chapters.length > 1)
            PopupMenuButton<int>(
              icon: const Icon(Icons.menu_book_outlined),
              tooltip: 'Chapitres',
              onSelected: (v) => setState(() => _index = v),
              itemBuilder: (_) => [
                for (var i = 0; i < _chapters.length; i++)
                  PopupMenuItem(
                    value: i,
                    child: Text(
                      '${i + 1}. ${_chapters[i].title}',
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
              ],
            ),
        ],
      ),
      body: ch.blocks.isEmpty
          ? const Center(child: Text('Contenu vide'))
          : ListView.builder(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 80),
              itemCount: ch.blocks.length,
              itemBuilder: (_, i) => _renderBlock(ch.blocks[i]),
            ),
      bottomNavigationBar: (widget.isEpub && _chapters.length > 1)
          ? BottomAppBar(
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  IconButton(
                    icon: const Icon(Icons.chevron_left),
                    onPressed: _index > 0
                        ? () => setState(() => _index--)
                        : null,
                  ),
                  Text(
                    'Chapitre ${_index + 1} / ${_chapters.length}',
                    style: const TextStyle(fontSize: 12),
                  ),
                  IconButton(
                    icon: const Icon(Icons.chevron_right),
                    onPressed: _index < _chapters.length - 1
                        ? () => setState(() => _index++)
                        : null,
                  ),
                ],
              ),
            )
          : null,
    );
  }

  Widget _renderBlock(ReaderBlock b) {
    final base = TextStyle(fontSize: _fontSize, height: 1.6);
    switch (b.type) {
      case 'h1':
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: SelectableText(
            b.text,
            style: base.copyWith(
              fontSize: _fontSize * 1.6,
              fontWeight: FontWeight.w700,
            ),
          ),
        );
      case 'h2':
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: SelectableText(
            b.text,
            style: base.copyWith(
              fontSize: _fontSize * 1.35,
              fontWeight: FontWeight.w700,
            ),
          ),
        );
      case 'h3':
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: SelectableText(
            b.text,
            style: base.copyWith(
              fontSize: _fontSize * 1.15,
              fontWeight: FontWeight.w600,
            ),
          ),
        );
      case 'quote':
        return Container(
          margin: const EdgeInsets.symmetric(vertical: 8),
          padding: const EdgeInsets.fromLTRB(12, 6, 12, 6),
          decoration: BoxDecoration(
            border: Border(
              left: BorderSide(
                color: Theme.of(
                  context,
                ).colorScheme.primary.withValues(alpha: 0.5),
                width: 3,
              ),
            ),
          ),
          child: SelectableText(
            b.text,
            style: base.copyWith(fontStyle: FontStyle.italic),
          ),
        );
      case 'li':
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('• '),
              Expanded(child: SelectableText(b.text, style: base)),
            ],
          ),
        );
      case 'p':
      default:
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: SelectableText(b.text, style: base),
        );
    }
  }
}
