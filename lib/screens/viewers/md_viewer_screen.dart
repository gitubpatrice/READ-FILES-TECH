import 'dart:io';
import 'package:flutter/material.dart';
import 'package:gpt_markdown/gpt_markdown.dart';
import 'package:flutter_highlight/flutter_highlight.dart';
import 'package:flutter_highlight/themes/github.dart';
import 'package:flutter_highlight/themes/atom-one-dark.dart';
import 'package:share_plus/share_plus.dart';

class MdViewerScreen extends StatefulWidget {
  final String path;
  const MdViewerScreen({super.key, required this.path});

  @override
  State<MdViewerScreen> createState() => _MdViewerScreenState();
}

class _MdViewerScreenState extends State<MdViewerScreen> {
  String _content = '';
  bool _isLoading = true;
  bool _showSource = false;
  double _fontSize = 14;

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;
  bool get _isDark => Theme.of(context).brightness == Brightness.dark;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final content = await File(widget.path).readAsString();
      setState(() {
        _content = content;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _content = 'Erreur : $e';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          IconButton(
            tooltip: _showSource ? 'Aperçu rendu' : 'Source Markdown',
            icon: Icon(_showSource ? Icons.preview : Icons.code),
            onPressed: () => setState(() => _showSource = !_showSource),
          ),
          IconButton(
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
          if (_showSource)
            PopupMenuButton<double>(
              icon: const Icon(Icons.text_fields),
              onSelected: (v) => setState(() => _fontSize = v),
              itemBuilder: (_) => [10, 12, 13, 14, 16, 18, 20]
                  .map(
                    (s) => PopupMenuItem(
                      value: s.toDouble(),
                      child: Text(
                        '$s pt',
                        style: TextStyle(
                          fontWeight: _fontSize == s ? FontWeight.bold : null,
                        ),
                      ),
                    ),
                  )
                  .toList(),
            ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _showSource
          ? SingleChildScrollView(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: HighlightView(
                  _content,
                  language: 'markdown',
                  theme: _isDark ? atomOneDarkTheme : githubTheme,
                  padding: const EdgeInsets.all(16),
                  textStyle: TextStyle(
                    fontSize: _fontSize,
                    fontFamily: 'monospace',
                  ),
                ),
              ),
            )
          : SingleChildScrollView(
              padding: const EdgeInsets.all(20),
              child: GptMarkdown(
                _content,
                style: Theme.of(
                  context,
                ).textTheme.bodyMedium?.copyWith(height: 1.7),
              ),
            ),
    );
  }
}
