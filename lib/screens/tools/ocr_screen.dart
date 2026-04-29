import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

class OcrScreen extends StatefulWidget {
  const OcrScreen({super.key});

  @override
  State<OcrScreen> createState() => _OcrScreenState();
}

class _OcrScreenState extends State<OcrScreen> {
  final _recognizer = TextRecognizer(script: TextRecognitionScript.latin);
  final _picker = ImagePicker();
  String _text = '';
  String? _imagePath;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _recognizer.close();
    super.dispose();
  }

  Future<void> _process(String imagePath) async {
    setState(() { _busy = true; _error = null; _imagePath = imagePath; _text = ''; });
    try {
      final input = InputImage.fromFilePath(imagePath);
      final result = await _recognizer.processImage(input);
      setState(() { _text = result.text; _busy = false; });
    } catch (e) {
      setState(() { _busy = false; _error = 'Erreur OCR : $e'; });
    }
  }

  Future<void> _pickImage() async {
    final res = await FilePicker.platform.pickFiles(type: FileType.image);
    if (res == null || res.files.single.path == null) return;
    await _process(res.files.single.path!);
  }

  Future<void> _capture() async {
    try {
      final shot = await _picker.pickImage(source: ImageSource.camera);
      if (shot == null) return;
      await _process(shot.path);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = 'Caméra indisponible : $e');
    }
  }

  Future<void> _saveAsTxt() async {
    if (_text.isEmpty) return;
    final tmp = await getTemporaryDirectory();
    final out = File('${tmp.path}/ocr_${DateTime.now().millisecondsSinceEpoch}.txt');
    await out.writeAsString(_text);
    if (!mounted) return;
    await Share.shareXFiles([XFile(out.path)]);
  }

  void _copy() {
    Clipboard.setData(ClipboardData(text: _text));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Texte copié')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('OCR — Image vers texte'),
        actions: [
          if (_text.isNotEmpty) IconButton(
            icon: const Icon(Icons.copy),
            tooltip: 'Copier',
            onPressed: _copy,
          ),
          if (_text.isNotEmpty) IconButton(
            icon: const Icon(Icons.save_alt),
            tooltip: 'Enregistrer en .txt',
            onPressed: _saveAsTxt,
          ),
        ],
      ),
      body: Column(
        children: [
          if (_busy) const LinearProgressIndicator(),
          if (_imagePath != null)
            Container(
              height: 140,
              padding: const EdgeInsets.all(8),
              child: Image.file(File(_imagePath!), fit: BoxFit.contain),
            ),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.all(12),
              child: Text(_error!, style: const TextStyle(color: Colors.red, fontSize: 12)),
            ),
          Expanded(
            child: _text.isEmpty && !_busy
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.text_snippet_outlined, size: 64, color: Colors.grey.shade400),
                          const SizedBox(height: 12),
                          const Text('Choisissez une image pour extraire le texte',
                              textAlign: TextAlign.center,
                              style: TextStyle(color: Colors.grey)),
                          const SizedBox(height: 4),
                          const Text('Reconnaissance 100 % locale (latin)',
                              style: TextStyle(fontSize: 11, color: Colors.grey)),
                        ],
                      ),
                    ),
                  )
                : SingleChildScrollView(
                    padding: const EdgeInsets.all(16),
                    child: SelectableText(_text,
                        style: const TextStyle(fontSize: 14, height: 1.5)),
                  ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: _busy ? null : _capture,
                      icon: const Icon(Icons.photo_camera_outlined),
                      label: const Text('Photo'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: _busy ? null : _pickImage,
                      icon: const Icon(Icons.image_outlined),
                      label: const Text('Galerie'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
