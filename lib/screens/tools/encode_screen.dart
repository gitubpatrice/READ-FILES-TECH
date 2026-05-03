import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class EncodeScreen extends StatefulWidget {
  const EncodeScreen({super.key});

  @override
  State<EncodeScreen> createState() => _EncodeScreenState();
}

class _EncodeScreenState extends State<EncodeScreen> {
  final _inputCtrl = TextEditingController();
  final _outputCtrl = TextEditingController();
  String _mode = 'base64';
  bool _encode = true;

  @override
  void dispose() {
    _inputCtrl.dispose();
    _outputCtrl.dispose();
    super.dispose();
  }

  void _process() {
    final input = _inputCtrl.text;
    if (input.isEmpty) {
      _outputCtrl.text = '';
      return;
    }
    try {
      String result;
      switch (_mode) {
        case 'base64':
          result = _encode
              ? base64Encode(utf8.encode(input))
              : utf8.decode(base64Decode(input));
        case 'url':
          result = _encode ? Uri.encodeFull(input) : Uri.decodeFull(input);
        case 'html':
          result = _encode ? _htmlEncode(input) : _htmlDecode(input);
        default:
          result = input;
      }
      _outputCtrl.text = result;
    } catch (e) {
      _outputCtrl.text = 'Erreur : $e';
    }
  }

  String _htmlEncode(String s) => s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;');

  String _htmlDecode(String s) => s
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll('&#39;', "'")
      .replaceAll('&apos;', "'");

  void _swap() {
    final tmp = _inputCtrl.text;
    _inputCtrl.text = _outputCtrl.text;
    _outputCtrl.text = tmp;
    setState(() => _encode = !_encode);
  }

  void _copy() {
    Clipboard.setData(ClipboardData(text: _outputCtrl.text));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Résultat copié'),
        duration: Duration(seconds: 1),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Encodage / Décodage')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Mode selector
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(
                  value: 'base64',
                  label: Text('Base64'),
                  icon: Icon(Icons.numbers, size: 16),
                ),
                ButtonSegment(
                  value: 'url',
                  label: Text('URL'),
                  icon: Icon(Icons.link, size: 16),
                ),
                ButtonSegment(
                  value: 'html',
                  label: Text('HTML'),
                  icon: Icon(Icons.html_outlined, size: 16),
                ),
              ],
              selected: {_mode},
              onSelectionChanged: (v) => setState(() {
                _mode = v.first;
                _process();
              }),
            ),
            const SizedBox(height: 16),

            // Encode / Decode toggle
            Row(
              children: [
                ChoiceChip(
                  label: const Text('Encoder'),
                  selected: _encode,
                  onSelected: (_) => setState(() {
                    _encode = true;
                    _process();
                  }),
                ),
                const SizedBox(width: 8),
                ChoiceChip(
                  label: const Text('Décoder'),
                  selected: !_encode,
                  onSelected: (_) => setState(() {
                    _encode = false;
                    _process();
                  }),
                ),
              ],
            ),
            const SizedBox(height: 16),

            // Input
            Text(
              _encode ? 'Texte source' : 'Texte encodé',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 6),
            TextField(
              controller: _inputCtrl,
              maxLines: 6,
              onChanged: (_) => _process(),
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                hintText: 'Entrez le texte ici…',
              ),
              style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
            ),
            const SizedBox(height: 8),

            // Action buttons
            Row(
              children: [
                FilledButton.icon(
                  onPressed: _process,
                  icon: const Icon(Icons.play_arrow, size: 18),
                  label: Text(_encode ? 'Encoder' : 'Décoder'),
                ),
                const SizedBox(width: 8),
                OutlinedButton.icon(
                  onPressed: _swap,
                  icon: const Icon(Icons.swap_vert, size: 18),
                  label: const Text('Inverser'),
                ),
                const SizedBox(width: 8),
                IconButton(
                  onPressed: () {
                    _inputCtrl.clear();
                    _outputCtrl.clear();
                  },
                  icon: const Icon(Icons.clear),
                  tooltip: 'Effacer',
                ),
              ],
            ),
            const SizedBox(height: 16),

            // Output
            Row(
              children: [
                Text(
                  _encode ? 'Résultat encodé' : 'Résultat décodé',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const Spacer(),
                if (_outputCtrl.text.isNotEmpty)
                  TextButton.icon(
                    onPressed: _copy,
                    icon: const Icon(Icons.copy, size: 16),
                    label: const Text('Copier'),
                  ),
              ],
            ),
            const SizedBox(height: 6),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Theme.of(context).dividerColor),
              ),
              child: SelectableText(
                _outputCtrl.text.isEmpty ? '—' : _outputCtrl.text,
                style: TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 13,
                  color: _outputCtrl.text.startsWith('Erreur')
                      ? Colors.red
                      : null,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
