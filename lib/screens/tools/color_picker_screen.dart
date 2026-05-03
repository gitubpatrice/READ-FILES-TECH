import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';

class ColorPickerScreen extends StatefulWidget {
  const ColorPickerScreen({super.key});

  @override
  State<ColorPickerScreen> createState() => _ColorPickerScreenState();
}

class _ColorPickerScreenState extends State<ColorPickerScreen> {
  Color _color = const Color(0xFF1565C0);

  String get _hex =>
      '#${_color.toARGB32().toRadixString(16).substring(2).toUpperCase()}';
  String get _rgb =>
      'rgb(${(_color.r * 255).round()}, ${(_color.g * 255).round()}, ${(_color.b * 255).round()})';
  String get _hsl {
    final h = HSLColor.fromColor(_color);
    return 'hsl(${h.hue.toStringAsFixed(0)}, ${(h.saturation * 100).toStringAsFixed(0)}%, ${(h.lightness * 100).toStringAsFixed(0)}%)';
  }

  void _copy(String value) {
    Clipboard.setData(ClipboardData(text: value));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Copié : $value'),
        duration: const Duration(seconds: 1),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Color Picker')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            // Aperçu
            Container(
              width: double.infinity,
              height: 80,
              decoration: BoxDecoration(
                color: _color,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Theme.of(context).dividerColor),
              ),
            ),
            const SizedBox(height: 20),

            // Picker
            ColorPicker(
              pickerColor: _color,
              onColorChanged: (c) => setState(() => _color = c),
              enableAlpha: false,
              labelTypes: const [],
              pickerAreaHeightPercent: 0.6,
            ),
            const SizedBox(height: 16),

            // Codes couleurs
            _codeRow('HEX', _hex),
            const SizedBox(height: 8),
            _codeRow('RGB', _rgb),
            const SizedBox(height: 8),
            _codeRow('HSL', _hsl),

            const SizedBox(height: 24),

            // Nuancier rapide
            Text(
              'Couleurs récentes',
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children:
                  [
                    '#FF0000',
                    '#FF6600',
                    '#FFCC00',
                    '#33CC33',
                    '#0066FF',
                    '#9900CC',
                    '#FF0099',
                    '#000000',
                    '#FFFFFF',
                    '#666666',
                    '#1565C0',
                    '#C62828',
                  ].map((hex) {
                    var h = hex.replaceFirst('#', '');
                    final v = int.tryParse('FF$h', radix: 16);
                    final c = v != null ? Color(v) : Colors.grey;
                    return GestureDetector(
                      onTap: () => setState(() => _color = c),
                      child: Container(
                        width: 32,
                        height: 32,
                        decoration: BoxDecoration(
                          color: c,
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: _color == c
                                ? Theme.of(context).colorScheme.primary
                                : Colors.grey.withValues(alpha: 0.3),
                            width: _color == c ? 2.5 : 1,
                          ),
                        ),
                      ),
                    );
                  }).toList(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _codeRow(String label, String value) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Theme.of(context).dividerColor),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.primaryContainer,
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w700,
                color: Theme.of(context).colorScheme.primary,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              value,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
            ),
          ),
          IconButton(
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
            icon: const Icon(Icons.copy, size: 18),
            onPressed: () => _copy(value),
          ),
        ],
      ),
    );
  }
}
