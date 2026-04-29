import 'dart:typed_data';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';

/// Capture une signature manuscrite. Retourne via Navigator.pop(Uint8List png)
/// un PNG **avec fond transparent** prêt à être posé sur un PDF.
class SignatureCaptureScreen extends StatefulWidget {
  const SignatureCaptureScreen({super.key});

  @override
  State<SignatureCaptureScreen> createState() => _SignatureCaptureScreenState();
}

class _SignatureCaptureScreenState extends State<SignatureCaptureScreen> {
  final List<List<Offset>> _strokes = [];
  Color _color = Colors.black;
  double _strokeWidth = 3;

  void _addPoint(Offset p) {
    if (_strokes.isEmpty) return;
    setState(() => _strokes.last.add(p));
  }

  void _startStroke(Offset p) => setState(() => _strokes.add([p]));

  void _undo() {
    if (_strokes.isEmpty) return;
    setState(() => _strokes.removeLast());
  }

  void _clear() => setState(_strokes.clear);

  /// Restitue les strokes dans une [ui.Image] de la taille du canvas, fond
  /// **transparent** (pas de drawRect / drawColor), et exporte en PNG.
  Future<Uint8List?> _exportPng(Size size) async {
    if (_strokes.isEmpty || _strokes.every((s) => s.length < 2)) return null;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Rect.fromLTWH(0, 0, size.width, size.height));
    // Pas de drawColor → fond transparent
    final paint = Paint()
      ..color = _color
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..isAntiAlias = true
      ..strokeWidth = _strokeWidth
      ..style = PaintingStyle.stroke;
    for (final stroke in _strokes) {
      if (stroke.length < 2) continue;
      final path = Path()..moveTo(stroke.first.dx, stroke.first.dy);
      for (var i = 1; i < stroke.length; i++) {
        path.lineTo(stroke[i].dx, stroke[i].dy);
      }
      canvas.drawPath(path, paint);
    }
    final picture = recorder.endRecording();
    final image = await picture.toImage(size.width.toInt(), size.height.toInt());
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    return bytes?.buffer.asUint8List();
  }

  Future<void> _validate() async {
    final size = MediaQuery.of(context).size;
    // Hauteur de la zone de dessin (Container ci-dessous)
    final canvasSize = Size(size.width, size.height * 0.65);
    final png = await _exportPng(canvasSize);
    if (!mounted) return;
    if (png == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Tracez votre signature avant de valider')),
      );
      return;
    }
    Navigator.pop(context, png);
  }

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    final canvasHeight = mq.size.height * 0.65;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Tracez votre signature'),
        actions: [
          IconButton(
            tooltip: 'Annuler le dernier trait',
            onPressed: _strokes.isEmpty ? null : _undo,
            icon: const Icon(Icons.undo),
          ),
          IconButton(
            tooltip: 'Tout effacer',
            onPressed: _strokes.isEmpty ? null : _clear,
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
      body: Column(
        children: [
          Container(
            width: double.infinity,
            height: canvasHeight,
            color: Colors.white,
            child: GestureDetector(
              onPanStart: (d) => _startStroke(d.localPosition),
              onPanUpdate: (d) => _addPoint(d.localPosition),
              child: CustomPaint(
                painter: _SignaturePainter(_strokes, _color, _strokeWidth),
                size: Size.infinite,
              ),
            ),
          ),
          const Divider(height: 1),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: Row(children: [
              const Icon(Icons.brush_outlined, size: 16),
              const SizedBox(width: 8),
              const Text('Épaisseur', style: TextStyle(fontSize: 12)),
              Expanded(
                child: Slider(
                  value: _strokeWidth, min: 1, max: 8, divisions: 14,
                  label: _strokeWidth.toStringAsFixed(1),
                  onChanged: (v) => setState(() => _strokeWidth = v),
                ),
              ),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(children: [
              const Icon(Icons.palette_outlined, size: 16),
              const SizedBox(width: 8),
              const Text('Couleur', style: TextStyle(fontSize: 12)),
              const SizedBox(width: 12),
              for (final c in const [Colors.black, Color(0xFF1A237E), Color(0xFF1B5E20), Color(0xFFB71C1C)])
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: GestureDetector(
                    onTap: () => setState(() => _color = c),
                    child: Container(
                      width: 28, height: 28,
                      decoration: BoxDecoration(
                        color: c,
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: c.toARGB32() == _color.toARGB32() ? Colors.amber : Colors.grey.shade400,
                          width: c.toARGB32() == _color.toARGB32() ? 3 : 1,
                        ),
                      ),
                    ),
                  ),
                ),
            ]),
          ),
          const Spacer(),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: FilledButton.icon(
                onPressed: _validate,
                icon: const Icon(Icons.check),
                label: const Text('Valider la signature'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SignaturePainter extends CustomPainter {
  final List<List<Offset>> strokes;
  final Color color;
  final double strokeWidth;
  _SignaturePainter(this.strokes, this.color, this.strokeWidth);

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..isAntiAlias = true
      ..strokeWidth = strokeWidth
      ..style = PaintingStyle.stroke;
    for (final stroke in strokes) {
      if (stroke.length < 2) continue;
      final path = Path()..moveTo(stroke.first.dx, stroke.first.dy);
      for (var i = 1; i < stroke.length; i++) {
        path.lineTo(stroke[i].dx, stroke[i].dy);
      }
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _SignaturePainter old) =>
      old.strokes != strokes || old.color != color || old.strokeWidth != strokeWidth;
}
