import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';
import 'package:path_provider/path_provider.dart';
import '../../widgets/rft_picker_screen.dart';

class FormatScreen extends StatefulWidget {
  const FormatScreen({super.key});

  @override
  State<FormatScreen> createState() => _FormatScreenState();
}

class _FormatScreenState extends State<FormatScreen> {
  final _inputCtrl = TextEditingController();
  final _outputCtrl = TextEditingController();

  /// Catégorie du fichier : 'json', 'css' ou 'js'.
  String _mode = 'json';

  /// Action choisie pour la catégorie courante. Pour json :
  /// 'format' ou 'minify'. Pour css/js : 'minify' uniquement.
  String _action = 'format';
  bool _isProcessing = false;

  @override
  void dispose() {
    _inputCtrl.dispose();
    _outputCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickFile() async {
    final exts = _mode == 'json' ? const {'json'} : const {'css', 'js'};
    final path = await RftPickerScreen.pickOne(
      context,
      title: 'Choisir un fichier',
      extensions: exts,
    );
    if (path == null) return;
    final content = await File(path).readAsString();
    _inputCtrl.text = content;
    _process();
  }

  void _process() {
    final input = _inputCtrl.text.trim();
    if (input.isEmpty) {
      _outputCtrl.text = '';
      return;
    }
    setState(() => _isProcessing = true);
    try {
      String result;
      switch ('${_mode}_$_action') {
        case 'json_format':
          final decoded = json.decode(input);
          result = const JsonEncoder.withIndent('  ').convert(decoded);
        case 'json_minify':
          final decoded = json.decode(input);
          result = json.encode(decoded);
        case 'css_minify':
          result = _minifyCss(input);
        case 'js_minify':
          result = _minifyJs(input);
        default:
          result = input;
      }
      _outputCtrl.text = result;
    } catch (e) {
      _outputCtrl.text = 'Erreur : $e';
    }
    setState(() => _isProcessing = false);
  }

  String _minifyCss(String css) {
    return css
        .replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '')
        .replaceAll(RegExp(r'\s+'), ' ')
        .replaceAll(RegExp(r'\s*([{}:;,>+~])\s*'), r'$1')
        .replaceAll(RegExp(r';\}'), '}')
        .trim();
  }

  String _minifyJs(String js) {
    return js
        .replaceAll(RegExp(r'//[^\n]*'), '')
        .replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '')
        .replaceAll(RegExp(r'\n+'), ' ')
        .replaceAll(RegExp(r'\s{2,}'), ' ')
        .trim();
  }

  void _copy() {
    Clipboard.setData(ClipboardData(text: _outputCtrl.text));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Copié'), duration: Duration(seconds: 1)),
    );
  }

  Future<void> _saveAndShare() async {
    if (_outputCtrl.text.isEmpty) return;
    final dir = await getTemporaryDirectory();
    final ext = _mode;
    final ts = DateTime.now().millisecondsSinceEpoch;
    final path = '${dir.path}/result_$ts.$ext';
    await File(path).writeAsString(_outputCtrl.text);
    await Share.shareXFiles([XFile(path)]);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Formater / Minifier')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Mode
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(
                  value: 'json',
                  label: Text('JSON'),
                  icon: Icon(Icons.data_object, size: 16),
                ),
                ButtonSegment(
                  value: 'css',
                  label: Text('CSS'),
                  icon: Icon(Icons.css_outlined, size: 16),
                ),
                ButtonSegment(
                  value: 'js',
                  label: Text('JS'),
                  icon: Icon(Icons.javascript_outlined, size: 16),
                ),
              ],
              selected: {_mode},
              onSelectionChanged: (v) => setState(() {
                _mode = v.first;
                // CSS/JS n'ont qu'une action ; JSON garde format par défaut.
                _action = _mode == 'json' ? 'format' : 'minify';
                _outputCtrl.text = '';
              }),
            ),
            const SizedBox(height: 12),

            // Actions selon mode
            if (_mode == 'json')
              Row(
                children: [
                  ChoiceChip(
                    label: const Text('Formater'),
                    selected: _action == 'format',
                    onSelected: (_) => setState(() {
                      _action = 'format';
                      _process();
                    }),
                  ),
                  const SizedBox(width: 8),
                  ChoiceChip(
                    label: const Text('Minifier'),
                    selected: _action == 'minify',
                    onSelected: (_) => setState(() {
                      _action = 'minify';
                      _process();
                    }),
                  ),
                ],
              ),
            if (_mode == 'css')
              ChoiceChip(
                label: const Text('Minifier CSS'),
                selected: true,
                onSelected: (_) => _process(),
              ),
            if (_mode == 'js')
              ChoiceChip(
                label: const Text('Minifier JS'),
                selected: true,
                onSelected: (_) => _process(),
              ),
            const SizedBox(height: 12),

            // Input
            Row(
              children: [
                Text('Entrée', style: Theme.of(context).textTheme.titleSmall),
                const Spacer(),
                TextButton.icon(
                  onPressed: _pickFile,
                  icon: const Icon(Icons.folder_open, size: 16),
                  label: const Text('Ouvrir fichier'),
                ),
              ],
            ),
            const SizedBox(height: 6),
            TextField(
              controller: _inputCtrl,
              maxLines: 8,
              onChanged: (_) => _process(),
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                hintText: 'Collez ou ouvrez un fichier…',
              ),
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
            const SizedBox(height: 8),

            FilledButton.icon(
              onPressed: _isProcessing ? null : _process,
              icon: const Icon(Icons.auto_fix_high, size: 18),
              label: Text(_mode.contains('minify') ? 'Minifier' : 'Formater'),
            ),
            const SizedBox(height: 16),

            // Output
            Row(
              children: [
                Text('Résultat', style: Theme.of(context).textTheme.titleSmall),
                const Spacer(),
                if (_outputCtrl.text.isNotEmpty &&
                    !_outputCtrl.text.startsWith('Erreur')) ...[
                  IconButton(
                    onPressed: _copy,
                    icon: const Icon(Icons.copy, size: 18),
                    tooltip: 'Copier',
                  ),
                  IconButton(
                    onPressed: _saveAndShare,
                    icon: const Icon(Icons.share, size: 18),
                    tooltip: 'Partager',
                  ),
                ],
              ],
            ),
            const SizedBox(height: 6),
            Container(
              width: double.infinity,
              constraints: const BoxConstraints(minHeight: 80),
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
                  fontSize: 12,
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
