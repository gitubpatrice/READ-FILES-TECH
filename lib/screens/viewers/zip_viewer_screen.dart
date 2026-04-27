import 'dart:io';
import 'package:archive/archive.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

class ZipViewerScreen extends StatefulWidget {
  final String path;
  const ZipViewerScreen({super.key, required this.path});

  @override
  State<ZipViewerScreen> createState() => _ZipViewerScreenState();
}

class _ZipViewerScreenState extends State<ZipViewerScreen> {
  List<ArchiveFile> _files = [];
  bool _isLoading = true;
  String? _error;
  String _search = '';
  late Archive _archive;

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final bytes = await File(widget.path).readAsBytes();
      _archive = ZipDecoder().decodeBytes(bytes);
      final files = _archive.files.where((f) => !f.isFile || f.size > 0).toList();
      files.sort((a, b) => a.name.compareTo(b.name));
      setState(() { _files = files; _isLoading = false; });
    } catch (e) {
      setState(() { _error = e.toString(); _isLoading = false; });
    }
  }

  String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  IconData _iconForExt(String name) {
    final ext = name.split('.').last.toLowerCase();
    switch (ext) {
      case 'pdf':                        return Icons.picture_as_pdf_outlined;
      case 'jpg': case 'jpeg':
      case 'png': case 'gif': case 'webp': return Icons.image_outlined;
      case 'mp4': case 'avi': case 'mov':  return Icons.videocam_outlined;
      case 'mp3': case 'wav': case 'flac': return Icons.audiotrack_outlined;
      case 'zip': case 'rar': case '7z':   return Icons.folder_zip_outlined;
      case 'txt': case 'md':             return Icons.text_snippet_outlined;
      case 'js': case 'ts': case 'dart':
      case 'php': case 'py': case 'java': return Icons.code;
      case 'html': case 'css': case 'xml': return Icons.html_outlined;
      case 'docx': case 'doc': case 'odt': return Icons.article_outlined;
      case 'xlsx': case 'xls': case 'csv': return Icons.table_chart_outlined;
      default:                            return Icons.insert_drive_file_outlined;
    }
  }

  Color _colorForExt(String name) {
    final ext = name.split('.').last.toLowerCase();
    switch (ext) {
      case 'pdf':           return Colors.red;
      case 'jpg': case 'jpeg':
      case 'png': case 'gif': return Colors.purple;
      case 'js': case 'ts': return Colors.yellow.shade700;
      case 'html':          return Colors.orange;
      case 'css':           return Colors.blue;
      case 'php':           return Colors.indigo;
      case 'docx': case 'doc': return Colors.blue.shade700;
      case 'xlsx': case 'csv': return Colors.green;
      default:              return Colors.grey;
    }
  }

  Future<void> _extractAndShare(ArchiveFile file) async {
    try {
      final dir = await getTemporaryDirectory();
      final fileName = file.name.split('/').last;
      final outPath = '${dir.path}/$fileName';
      await File(outPath).writeAsBytes(file.content as List<int>);
      await Share.shareXFiles([XFile(outPath)]);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Erreur : $e')),
      );
    }
  }

  Future<void> _extractAll() async {
    setState(() => _isLoading = true);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final base = _name.replaceAll(RegExp(r'\.\w+$'), '');
      final outDir = Directory('${dir.path}/${base}_extracted');
      await outDir.create(recursive: true);
      for (final file in _archive.files) {
        if (!file.isFile) continue;
        final outFile = File('${outDir.path}/${file.name}');
        await outFile.parent.create(recursive: true);
        await outFile.writeAsBytes(file.content as List<int>);
      }
      if (!mounted) return;
      setState(() => _isLoading = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Extrait dans : ${outDir.path.split('/').last}')),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _search.isEmpty
        ? _files
        : _files.where((f) => f.name.toLowerCase().contains(_search.toLowerCase())).toList();

    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: 'Extraire tout',
            icon: const Icon(Icons.unarchive_outlined),
            onPressed: _isLoading ? null : _extractAll,
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Text('Erreur : $_error'))
              : Column(
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                      child: Row(children: [
                        Expanded(
                          child: TextField(
                            decoration: InputDecoration(
                              hintText: 'Rechercher dans l\'archive…',
                              prefixIcon: const Icon(Icons.search, size: 18),
                              isDense: true,
                              border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(8)),
                              contentPadding: const EdgeInsets.symmetric(vertical: 8),
                            ),
                            onChanged: (v) => setState(() => _search = v),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text('${filtered.length} fichier${filtered.length > 1 ? 's' : ''}',
                            style: const TextStyle(color: Colors.grey, fontSize: 12)),
                      ]),
                    ),
                    Expanded(
                      child: ListView.builder(
                        itemCount: filtered.length,
                        itemBuilder: (_, i) {
                          final file = filtered[i];
                          final isDir = !file.isFile;
                          final color = isDir ? Colors.amber : _colorForExt(file.name);
                          return ListTile(
                            dense: true,
                            leading: Icon(isDir ? Icons.folder_outlined : _iconForExt(file.name),
                                color: color, size: 22),
                            title: Text(file.name.split('/').last,
                                style: const TextStyle(fontSize: 13)),
                            subtitle: Text(
                              file.name.contains('/')
                                  ? file.name.substring(0, file.name.lastIndexOf('/'))
                                  : '',
                              style: const TextStyle(fontSize: 11),
                              overflow: TextOverflow.ellipsis,
                            ),
                            trailing: isDir
                                ? null
                                : Row(mainAxisSize: MainAxisSize.min, children: [
                                    Text(_formatSize(file.size),
                                        style: const TextStyle(fontSize: 11, color: Colors.grey)),
                                    const SizedBox(width: 4),
                                    IconButton(
                                      padding: EdgeInsets.zero,
                                      constraints: const BoxConstraints(),
                                      icon: const Icon(Icons.share, size: 18),
                                      onPressed: () => _extractAndShare(file),
                                    ),
                                  ]),
                          );
                        },
                      ),
                    ),
                  ],
                ),
    );
  }
}
