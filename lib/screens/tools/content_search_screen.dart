import 'dart:io';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import '../viewers/txt_viewer_screen.dart';
import '../viewers/md_viewer_screen.dart';
import '../viewers/json_viewer_screen.dart';
import '../viewers/html_viewer_screen.dart';
import '../viewers/csv_viewer_screen.dart';

class ContentSearchScreen extends StatefulWidget {
  const ContentSearchScreen({super.key});

  @override
  State<ContentSearchScreen> createState() => _ContentSearchScreenState();
}

class _ContentSearchScreenState extends State<ContentSearchScreen> {
  final _queryCtrl = TextEditingController();
  String? _folderPath;
  List<_SearchResult> _results = [];
  bool _isSearching = false;
  bool _caseSensitive = false;
  bool _useRegex = false;
  int _totalFiles = 0;
  int _scannedFiles = 0;

  static const _textExts = {
    'txt',
    'md',
    'csv',
    'xml',
    'json',
    'html',
    'htm',
    'css',
    'js',
    'php',
    'dart',
    'ts',
    'yaml',
    'yml',
    'ini',
    'conf',
    'log',
    'sh',
    'bat',
  };

  @override
  void dispose() {
    _queryCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickFolder() async {
    final result = await FilePicker.getDirectoryPath();
    if (result != null) setState(() => _folderPath = result);
  }

  Future<void> _search() async {
    final query = _queryCtrl.text.trim();
    if (query.isEmpty || _folderPath == null) return;

    setState(() {
      _isSearching = true;
      _results = [];
      _scannedFiles = 0;
    });

    final dir = Directory(_folderPath!);
    final allFiles = <File>[];
    try {
      await for (final e in dir.list(recursive: true)) {
        if (e is File) {
          final ext = PathUtils.fileExt(e.path).toLowerCase();
          if (_textExts.contains(ext)) allFiles.add(e);
        }
      }
    } catch (_) {}

    setState(() => _totalFiles = allFiles.length);

    final results = <_SearchResult>[];
    for (final file in allFiles) {
      try {
        final lines = await file.readAsLines();
        final matches = <_LineMatch>[];
        for (int i = 0; i < lines.length; i++) {
          final line = lines[i];
          final haystack = _caseSensitive ? line : line.toLowerCase();
          final needle = _caseSensitive ? query : query.toLowerCase();
          bool hit;
          if (_useRegex) {
            try {
              hit = RegExp(query, caseSensitive: _caseSensitive).hasMatch(line);
            } catch (_) {
              hit = false;
            }
          } else {
            hit = haystack.contains(needle);
          }
          if (hit) matches.add(_LineMatch(i + 1, line.trim()));
        }
        if (matches.isNotEmpty) {
          results.add(_SearchResult(file.path, matches));
        }
      } catch (_) {}
      setState(() => _scannedFiles++);
    }

    setState(() {
      _results = results;
      _isSearching = false;
    });
  }

  Widget? _viewerFor(String path) {
    final ext = PathUtils.fileExt(path).toLowerCase();
    switch (ext) {
      case 'md':
        return MdViewerScreen(path: path);
      case 'json':
        return JsonViewerScreen(path: path);
      case 'html':
      case 'htm':
        return HtmlViewerScreen(path: path);
      case 'csv':
        return CsvViewerScreen(path: path);
      default:
        final hl = ['css', 'js', 'php', 'xml'].contains(ext) ? ext : null;
        return TxtViewerScreen(path: path, highlightLanguage: hl);
    }
  }

  String _relativePath(String path) {
    if (_folderPath == null) return PathUtils.fileName(path);
    return path.replaceFirst('$_folderPath/', '');
  }

  @override
  Widget build(BuildContext context) {
    final totalMatches = _results.fold(0, (s, r) => s + r.matches.length);

    return Scaffold(
      appBar: AppBar(title: const Text('Recherche dans les fichiers')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Dossier
                GestureDetector(
                  onTap: _pickFolder,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: Theme.of(
                        context,
                      ).colorScheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Theme.of(context).dividerColor),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          Icons.folder_outlined,
                          color: Theme.of(context).colorScheme.primary,
                          size: 20,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _folderPath != null
                                ? PathUtils.fileName(_folderPath!)
                                : 'Choisir un dossier…',
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: _folderPath == null ? Colors.grey : null,
                              fontSize: 13,
                            ),
                          ),
                        ),
                        const Icon(
                          Icons.chevron_right,
                          size: 18,
                          color: Colors.grey,
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 8),

                // Recherche
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _queryCtrl,
                        decoration: InputDecoration(
                          hintText: 'Mot, phrase ou regex…',
                          prefixIcon: const Icon(Icons.search),
                          border: const OutlineInputBorder(),
                          isDense: true,
                          suffixIcon: _queryCtrl.text.isNotEmpty
                              ? IconButton(
                                  icon: const Icon(Icons.clear, size: 18),
                                  onPressed: () {
                                    _queryCtrl.clear();
                                    setState(() => _results = []);
                                  },
                                )
                              : null,
                        ),
                        onSubmitted: (_) => _search(),
                        onChanged: (_) => setState(() {}),
                      ),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: _isSearching ? null : _search,
                      child: const Text('Chercher'),
                    ),
                  ],
                ),
                const SizedBox(height: 6),

                // Options
                Wrap(
                  spacing: 8,
                  children: [
                    FilterChip(
                      label: const Text('Aa', style: TextStyle(fontSize: 12)),
                      tooltip: 'Respecter la casse',
                      selected: _caseSensitive,
                      onSelected: (v) => setState(() => _caseSensitive = v),
                      visualDensity: VisualDensity.compact,
                    ),
                    FilterChip(
                      label: const Text(
                        '.*',
                        style: TextStyle(fontFamily: 'monospace', fontSize: 12),
                      ),
                      tooltip: 'Expression régulière',
                      selected: _useRegex,
                      onSelected: (v) => setState(() => _useRegex = v),
                      visualDensity: VisualDensity.compact,
                    ),
                  ],
                ),
              ],
            ),
          ),
          const Divider(height: 1),

          if (_isSearching)
            LinearProgressIndicator(
              value: _totalFiles > 0 ? _scannedFiles / _totalFiles : null,
            ),

          if (!_isSearching && _results.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Row(
                children: [
                  Text(
                    '$totalMatches résultat${totalMatches > 1 ? 's' : ''} '
                    'dans ${_results.length} fichier${_results.length > 1 ? 's' : ''}',
                    style: const TextStyle(fontSize: 12, color: Colors.grey),
                  ),
                ],
              ),
            ),

          Expanded(
            child: _results.isEmpty && !_isSearching
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.manage_search,
                          size: 72,
                          color: Theme.of(
                            context,
                          ).colorScheme.primary.withValues(alpha: 0.25),
                        ),
                        const SizedBox(height: 16),
                        Text(
                          _folderPath == null
                              ? 'Choisissez un dossier pour commencer'
                              : 'Entrez un terme et appuyez sur Chercher',
                          style: const TextStyle(color: Colors.grey),
                        ),
                      ],
                    ),
                  )
                : ListView.builder(
                    itemCount: _results.length,
                    itemBuilder: (_, i) {
                      final r = _results[i];
                      return Card(
                        margin: const EdgeInsets.fromLTRB(12, 4, 12, 4),
                        child: ExpansionTile(
                          leading: const Icon(
                            Icons.insert_drive_file_outlined,
                            size: 20,
                          ),
                          title: Text(
                            _relativePath(r.path),
                            style: const TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w600,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(
                            '${r.matches.length} occurrence${r.matches.length > 1 ? 's' : ''}',
                            style: const TextStyle(fontSize: 11),
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                icon: const Icon(Icons.open_in_new, size: 18),
                                tooltip: 'Ouvrir',
                                onPressed: () {
                                  final screen = _viewerFor(r.path);
                                  if (screen != null) {
                                    Navigator.push(
                                      context,
                                      MaterialPageRoute(builder: (_) => screen),
                                    );
                                  }
                                },
                              ),
                            ],
                          ),
                          children: r.matches
                              .map((m) => _matchTile(m, _queryCtrl.text))
                              .toList(),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  Widget _matchTile(_LineMatch m, String query) {
    final line = m.line;
    final lowerLine = _caseSensitive ? line : line.toLowerCase();
    final lowerQuery = _caseSensitive ? query : query.toLowerCase();

    // Highlight matches
    List<TextSpan> spans = [];
    if (!_useRegex && lowerLine.contains(lowerQuery)) {
      int pos = 0;
      while (pos < line.length) {
        final idx = lowerLine.indexOf(lowerQuery, pos);
        if (idx == -1) {
          spans.add(TextSpan(text: line.substring(pos)));
          break;
        }
        if (idx > pos) spans.add(TextSpan(text: line.substring(pos, idx)));
        spans.add(
          TextSpan(
            text: line.substring(idx, idx + query.length),
            style: TextStyle(
              backgroundColor: Colors.yellow.withValues(alpha: 0.5),
              color: Colors.black,
              fontWeight: FontWeight.bold,
            ),
          ),
        );
        pos = idx + query.length;
      }
    } else {
      spans = [TextSpan(text: line)];
    }

    return Container(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      decoration: BoxDecoration(
        border: Border(
          left: BorderSide(
            color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.3),
            width: 3,
          ),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 36,
            child: Text(
              '${m.line_}',
              style: const TextStyle(
                fontSize: 11,
                color: Colors.grey,
                fontFamily: 'monospace',
              ),
            ),
          ),
          Expanded(
            child: RichText(
              text: TextSpan(
                style: TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurface,
                ),
                children: spans,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

class _SearchResult {
  final String path;
  final List<_LineMatch> matches;
  _SearchResult(this.path, this.matches);
}

class _LineMatch {
  final int line_;
  final String line;
  _LineMatch(this.line_, this.line);
}
