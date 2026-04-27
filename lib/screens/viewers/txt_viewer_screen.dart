import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_highlight/flutter_highlight.dart';
import 'package:flutter_highlight/themes/github.dart';
import 'package:flutter_highlight/themes/atom-one-dark.dart';
import 'package:share_plus/share_plus.dart';

class TxtViewerScreen extends StatefulWidget {
  final String path;
  final String? highlightLanguage; // css, js, php, xml, json, etc.

  const TxtViewerScreen({super.key, required this.path, this.highlightLanguage});

  @override
  State<TxtViewerScreen> createState() => _TxtViewerScreenState();
}

class _TxtViewerScreenState extends State<TxtViewerScreen> {
  String _content = '';
  bool _isLoading = true;
  double _fontSize = 13;
  bool _showColors = false;
  List<_ColorMatch> _colorMatches = [];

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;
  bool get _hasHighlight => widget.highlightLanguage != null;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final content = await File(widget.path).readAsString();
      final colors = _extractColors(content);
      setState(() {
        _content = content;
        _colorMatches = colors;
        _isLoading = false;
        _showColors = colors.isNotEmpty;
      });
    } catch (e) {
      setState(() { _content = 'Erreur de lecture : $e'; _isLoading = false; });
    }
  }

  List<_ColorMatch> _extractColors(String text) {
    final results = <_ColorMatch>[];
    final seen = <String>{};

    // Hex colors
    final hexReg = RegExp(r'#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})\b');
    for (final m in hexReg.allMatches(text)) {
      final code = m.group(0)!;
      if (seen.contains(code)) continue;
      seen.add(code);
      final c = _parseHex(code);
      if (c != null) results.add(_ColorMatch(code, c));
    }

    // rgb() / rgba()
    final rgbReg = RegExp(r'rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)');
    for (final m in rgbReg.allMatches(text)) {
      final code = m.group(0)! + ')';
      if (seen.contains(code)) continue;
      seen.add(code);
      final r = int.tryParse(m.group(1)!) ?? 0;
      final g = int.tryParse(m.group(2)!) ?? 0;
      final b = int.tryParse(m.group(3)!) ?? 0;
      results.add(_ColorMatch(code, Color.fromRGBO(r, g, b, 1)));
    }

    return results;
  }

  Color? _parseHex(String hex) {
    var h = hex.replaceFirst('#', '');
    if (h.length == 3) h = h.split('').map((c) => c + c).join();
    final v = int.tryParse('FF$h', radix: 16);
    return v != null ? Color(v) : null;
  }

  bool get _isDark =>
      Theme.of(context).brightness == Brightness.dark;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          if (_colorMatches.isNotEmpty)
            IconButton(
              tooltip: 'Couleurs',
              icon: Icon(Icons.palette_outlined,
                  color: _showColors ? Theme.of(context).colorScheme.primary : null),
              onPressed: () => setState(() => _showColors = !_showColors),
            ),
          IconButton(
            tooltip: 'Partager',
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
          PopupMenuButton<double>(
            icon: const Icon(Icons.text_fields),
            onSelected: (v) => setState(() => _fontSize = v),
            itemBuilder: (_) => [10, 12, 13, 14, 16, 18, 20]
                .map((s) => PopupMenuItem(
                      value: s.toDouble(),
                      child: Text('$s pt',
                          style: TextStyle(fontWeight: _fontSize == s ? FontWeight.bold : FontWeight.normal)),
                    ))
                .toList(),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                if (_showColors && _colorMatches.isNotEmpty)
                  _buildColorBar(),
                Expanded(child: _buildContent()),
              ],
            ),
    );
  }

  Widget _buildColorBar() {
    return Container(
      height: 48,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(
            color: Theme.of(context).dividerColor)),
      ),
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: _colorMatches.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (_, i) {
          final m = _colorMatches[i];
          return GestureDetector(
            onTap: () {
              Clipboard.setData(ClipboardData(text: m.code));
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text('Copié : ${m.code}'),
                    duration: const Duration(seconds: 1)),
              );
            },
            child: Tooltip(
              message: m.code,
              child: Row(children: [
                Container(
                  width: 22, height: 22,
                  decoration: BoxDecoration(
                    color: m.color,
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: Colors.grey.withValues(alpha: 0.4)),
                  ),
                ),
                const SizedBox(width: 4),
                Text(m.code, style: const TextStyle(fontSize: 11)),
              ]),
            ),
          );
        },
      ),
    );
  }

  Widget _buildContent() {
    if (_hasHighlight) {
      return SingleChildScrollView(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: HighlightView(
            _content,
            language: widget.highlightLanguage!,
            theme: _isDark ? atomOneDarkTheme : githubTheme,
            padding: const EdgeInsets.all(16),
            textStyle: TextStyle(fontSize: _fontSize, fontFamily: 'monospace'),
          ),
        ),
      );
    }
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: SelectableText(
        _content,
        style: TextStyle(fontSize: _fontSize, height: 1.6),
      ),
    );
  }
}

class _ColorMatch {
  final String code;
  final Color color;
  _ColorMatch(this.code, this.color);
}
