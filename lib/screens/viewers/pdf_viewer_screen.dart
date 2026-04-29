import 'dart:io';
import 'package:flutter/material.dart';
import 'package:syncfusion_flutter_pdfviewer/pdfviewer.dart';
import 'package:share_plus/share_plus.dart';

class PdfViewerScreen extends StatefulWidget {
  final String path;
  const PdfViewerScreen({super.key, required this.path});

  @override
  State<PdfViewerScreen> createState() => _PdfViewerScreenState();
}

class _PdfViewerScreenState extends State<PdfViewerScreen> {
  final PdfViewerController _ctrl = PdfViewerController();
  final GlobalKey<SfPdfViewerState> _viewerKey = GlobalKey();
  bool _showSearch = false;
  int _currentPage = 1;
  int _totalPages = 0;

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          if (_totalPages > 0)
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 4),
                child: Text('$_currentPage / $_totalPages',
                    style: const TextStyle(fontSize: 13)),
              ),
            ),
          IconButton(
            tooltip: 'Rechercher',
            icon: Icon(_showSearch ? Icons.search_off : Icons.search),
            onPressed: () {
              setState(() => _showSearch = !_showSearch);
              if (_showSearch) {
                _viewerKey.currentState?.openBookmarkView();
              }
            },
          ),
          IconButton(
            tooltip: 'Partager',
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
          PopupMenuButton<String>(
            onSelected: (v) {
              if (v == 'first')  _ctrl.firstPage();
              if (v == 'last')   _ctrl.lastPage();
              if (v == 'jump')   _showJumpDialog();
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'first', child: ListTile(
                  leading: Icon(Icons.first_page), title: Text('Première page'))),
              PopupMenuItem(value: 'last', child: ListTile(
                  leading: Icon(Icons.last_page), title: Text('Dernière page'))),
              PopupMenuItem(value: 'jump', child: ListTile(
                  leading: Icon(Icons.input), title: Text('Aller à la page…'))),
            ],
          ),
        ],
      ),
      body: SfPdfViewer.file(
        File(widget.path),
        key: _viewerKey,
        controller: _ctrl,
        onDocumentLoaded: (details) {
          setState(() => _totalPages = details.document.pages.count);
        },
        onPageChanged: (details) {
          setState(() => _currentPage = details.newPageNumber);
        },
      ),
      bottomNavigationBar: _totalPages > 1
          ? BottomAppBar(
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  IconButton(
                    icon: const Icon(Icons.chevron_left),
                    onPressed: _currentPage > 1 ? _ctrl.previousPage : null,
                  ),
                  Text('$_currentPage / $_totalPages',
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  IconButton(
                    icon: const Icon(Icons.chevron_right),
                    onPressed: _currentPage < _totalPages ? _ctrl.nextPage : null,
                  ),
                ],
              ),
            )
          : null,
    );
  }

  void _showJumpDialog() {
    final ctrl = TextEditingController();
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Aller à la page'),
        content: TextField(
          controller: ctrl,
          keyboardType: TextInputType.number,
          decoration: InputDecoration(
            hintText: '1 – $_totalPages',
            border: const OutlineInputBorder(),
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
          FilledButton(
            onPressed: () {
              final page = int.tryParse(ctrl.text);
              if (page != null && page >= 1 && page <= _totalPages) {
                _ctrl.jumpToPage(page);
              }
              Navigator.pop(context);
            },
            child: const Text('Aller'),
          ),
        ],
      ),
    );
  }
}
