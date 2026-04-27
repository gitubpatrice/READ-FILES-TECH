import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

class JsonViewerScreen extends StatefulWidget {
  final String path;
  const JsonViewerScreen({super.key, required this.path});

  @override
  State<JsonViewerScreen> createState() => _JsonViewerScreenState();
}

class _JsonViewerScreenState extends State<JsonViewerScreen> {
  dynamic _data;
  bool _isLoading = true;
  String? _error;
  bool _treeMode = true;
  String _raw = '';

  String get _name => widget.path.split(RegExp(r'[/\\]')).last;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      _raw = await File(widget.path).readAsString();
      final data = json.decode(_raw);
      setState(() { _data = data; _isLoading = false; });
    } catch (e) {
      setState(() { _error = e.toString(); _isLoading = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_name, overflow: TextOverflow.ellipsis),
        actions: [
          if (_error == null)
            IconButton(
              tooltip: _treeMode ? 'Texte brut' : 'Arbre',
              icon: Icon(_treeMode ? Icons.data_object : Icons.account_tree_outlined),
              onPressed: () => setState(() => _treeMode = !_treeMode),
            ),
          IconButton(
            icon: const Icon(Icons.share),
            onPressed: () => Share.shareXFiles([XFile(widget.path)]),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.error_outline, size: 48, color: Colors.red),
                        const SizedBox(height: 12),
                        Text('JSON invalide',
                            style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 8),
                        Text(_error!,
                            style: const TextStyle(color: Colors.grey, fontSize: 12),
                            textAlign: TextAlign.center),
                      ],
                    ),
                  ),
                )
              : _treeMode
                  ? SingleChildScrollView(
                      padding: const EdgeInsets.all(12),
                      child: _JsonNode(data: _data, initiallyExpanded: true),
                    )
                  : SingleChildScrollView(
                      padding: const EdgeInsets.all(16),
                      child: SelectableText(
                        const JsonEncoder.withIndent('  ').convert(_data),
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                      ),
                    ),
    );
  }
}

class _JsonNode extends StatefulWidget {
  final dynamic data;
  final String? label;
  final bool initiallyExpanded;

  const _JsonNode({required this.data, this.label, this.initiallyExpanded = false});

  @override
  State<_JsonNode> createState() => _JsonNodeState();
}

class _JsonNodeState extends State<_JsonNode> {
  late bool _expanded;

  @override
  void initState() {
    super.initState();
    _expanded = widget.initiallyExpanded;
  }

  bool get _isCollapsible => widget.data is Map || widget.data is List;

  String get _preview {
    if (widget.data is Map) {
      final m = widget.data as Map;
      return '{${m.length} clé${m.length > 1 ? 's' : ''}}';
    }
    if (widget.data is List) {
      final l = widget.data as List;
      return '[${l.length} élément${l.length > 1 ? 's' : ''}]';
    }
    return '';
  }

  Color _valueColor(BuildContext context) {
    if (widget.data is String) return Colors.green;
    if (widget.data is num)    return Colors.orange;
    if (widget.data is bool)   return Colors.blue;
    if (widget.data == null)   return Colors.grey;
    return Theme.of(context).colorScheme.primary;
  }

  String _valueText() {
    if (widget.data is String) return '"${widget.data}"';
    if (widget.data == null)   return 'null';
    return widget.data.toString();
  }

  @override
  Widget build(BuildContext context) {
    if (!_isCollapsible) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: GestureDetector(
          onLongPress: () {
            Clipboard.setData(ClipboardData(text: _valueText()));
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Copié'), duration: Duration(seconds: 1)),
            );
          },
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(width: 16),
              if (widget.label != null)
                Text('"${widget.label}": ',
                    style: TextStyle(
                        color: Theme.of(context).colorScheme.primary,
                        fontFamily: 'monospace', fontSize: 13)),
              Flexible(
                child: Text(_valueText(),
                    style: TextStyle(
                        color: _valueColor(context),
                        fontFamily: 'monospace', fontSize: 13)),
              ),
            ],
          ),
        ),
      );
    }

    final children = <Widget>[];
    if (widget.data is Map) {
      for (final entry in (widget.data as Map).entries) {
        children.add(Padding(
          padding: const EdgeInsets.only(left: 16),
          child: _JsonNode(data: entry.value, label: entry.key.toString()),
        ));
      }
    } else {
      final l = widget.data as List;
      for (int i = 0; i < l.length; i++) {
        children.add(Padding(
          padding: const EdgeInsets.only(left: 16),
          child: _JsonNode(data: l[i], label: i.toString()),
        ));
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        GestureDetector(
          onTap: () => setState(() => _expanded = !_expanded),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 2),
            child: Row(
              children: [
                Icon(_expanded ? Icons.expand_more : Icons.chevron_right,
                    size: 16, color: Colors.grey),
                if (widget.label != null)
                  Text('"${widget.label}": ',
                      style: TextStyle(
                          color: Theme.of(context).colorScheme.primary,
                          fontFamily: 'monospace', fontSize: 13)),
                Text(_preview,
                    style: const TextStyle(
                        color: Colors.grey, fontFamily: 'monospace', fontSize: 13)),
              ],
            ),
          ),
        ),
        if (_expanded) ...children,
      ],
    );
  }
}
