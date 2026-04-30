import 'dart:io';
import 'package:flutter/material.dart';
import '../../widgets/rft_picker_screen.dart';

class DiffScreen extends StatefulWidget {
  const DiffScreen({super.key});

  @override
  State<DiffScreen> createState() => _DiffScreenState();
}

class _DiffScreenState extends State<DiffScreen> {
  String? _pathA, _pathB, _nameA, _nameB;
  List<_DiffLine> _diff = [];
  bool _isComputing = false;
  int _added = 0, _removed = 0, _same = 0;

  Future<void> _pickFile(bool isA) async {
    final path = await RftPickerScreen.pickOne(context,
        title: isA ? 'Fichier A' : 'Fichier B',
        extensions: const {'txt','md','csv','xml','json','html','css','js','php','dart'});
    if (path == null) return;
    final name = path.split(RegExp(r'[/\\]')).last;
    setState(() {
      if (isA) {
        _pathA = path;
        _nameA = name;
      } else {
        _pathB = path;
        _nameB = name;
      }
    });
    if (_pathA != null && _pathB != null) await _compute();
  }

  Future<void> _compute() async {
    setState(() => _isComputing = true);
    final linesA = (await File(_pathA!).readAsString()).split('\n');
    final linesB = (await File(_pathB!).readAsString()).split('\n');
    final diff = _computeDiff(linesA, linesB);
    setState(() {
      _diff = diff;
      _added   = diff.where((d) => d.type == _DiffType.added).length;
      _removed = diff.where((d) => d.type == _DiffType.removed).length;
      _same    = diff.where((d) => d.type == _DiffType.same).length;
      _isComputing = false;
    });
  }

  List<_DiffLine> _computeDiff(List<String> a, List<String> b) {
    final lcs = _lcs(a, b);
    final result = <_DiffLine>[];
    int ai = 0, bi = 0, li = 0;
    while (li < lcs.length || ai < a.length || bi < b.length) {
      if (li < lcs.length &&
          ai < a.length && a[ai] == lcs[li] &&
          bi < b.length && b[bi] == lcs[li]) {
        result.add(_DiffLine(_DiffType.same, a[ai]));
        ai++; bi++; li++;
      } else {
        bool moved = false;
        while (ai < a.length && (li >= lcs.length || a[ai] != lcs[li])) {
          result.add(_DiffLine(_DiffType.removed, a[ai]));
          ai++; moved = true;
        }
        while (bi < b.length && (li >= lcs.length || b[bi] != lcs[li])) {
          result.add(_DiffLine(_DiffType.added, b[bi]));
          bi++; moved = true;
        }
        if (!moved) break;
      }
    }
    return result;
  }

  List<String> _lcs(List<String> a, List<String> b) {
    final maxA = a.length.clamp(0, 500);
    final maxB = b.length.clamp(0, 500);
    final dp = List.generate(maxA + 1, (_) => List.filled(maxB + 1, 0));
    for (int i = 1; i <= maxA; i++) {
      for (int j = 1; j <= maxB; j++) {
        dp[i][j] = a[i - 1] == b[j - 1]
            ? dp[i - 1][j - 1] + 1
            : (dp[i - 1][j] > dp[i][j - 1] ? dp[i - 1][j] : dp[i][j - 1]);
      }
    }
    final result = <String>[];
    int i = maxA, j = maxB;
    while (i > 0 && j > 0) {
      if (a[i - 1] == b[j - 1]) { result.add(a[i - 1]); i--; j--; }
      else if (dp[i - 1][j] > dp[i][j - 1]) { i--; } else { j--; }
    }
    return result.reversed.toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Comparer des fichiers')),
      body: (_pathA == null || _pathB == null) ? _buildPicker() : _buildDiff(),
    );
  }

  Widget _buildPicker() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.difference_outlined, size: 88,
                color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.35)),
            const SizedBox(height: 24),
            Text('Comparer deux fichiers', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('Sélectionnez deux fichiers texte pour voir leurs différences',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Colors.grey),
                textAlign: TextAlign.center),
            const SizedBox(height: 32),
            _pickBtn(_nameA ?? 'Choisir le fichier A', Colors.red.shade700, () => _pickFile(true)),
            const SizedBox(height: 12),
            _pickBtn(_nameB ?? 'Choisir le fichier B', Colors.green.shade700, () => _pickFile(false)),
          ],
        ),
      ),
    );
  }

  Widget _pickBtn(String label, Color color, VoidCallback onTap) {
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton.icon(
        onPressed: onTap,
        icon: Icon(Icons.folder_open, color: color),
        label: Text(label, overflow: TextOverflow.ellipsis),
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(0, 48),
          side: BorderSide(color: color.withValues(alpha: 0.5)),
        ),
      ),
    );
  }

  Widget _buildDiff() {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Row(children: [
            Expanded(child: _fileChip(_nameA!, Colors.red.shade700, () => _pickFile(true))),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 8),
              child: Icon(Icons.compare_arrows, color: Colors.grey),
            ),
            Expanded(child: _fileChip(_nameB!, Colors.green.shade700, () => _pickFile(false))),
          ]),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          child: Row(children: [
            _badge('+$_added', Colors.green),
            const SizedBox(width: 8),
            _badge('-$_removed', Colors.red),
            const SizedBox(width: 8),
            _badge('$_same identiques', Colors.grey),
          ]),
        ),
        const Divider(height: 1),
        if (_isComputing)
          const Expanded(child: Center(child: CircularProgressIndicator()))
        else
          Expanded(child: _buildList()),
      ],
    );
  }

  Widget _fileChip(String name, Color color, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: color.withValues(alpha: 0.4)),
        ),
        child: Row(children: [
          Icon(Icons.insert_drive_file_outlined, size: 14, color: color),
          const SizedBox(width: 4),
          Expanded(child: Text(name, overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: color, fontWeight: FontWeight.w600))),
        ]),
      ),
    );
  }

  Widget _badge(String label, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Text(label,
          style: TextStyle(fontSize: 11, color: color, fontWeight: FontWeight.w600)),
    );
  }

  Widget _buildList() {
    if (_diff.isEmpty) {
      return const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.check_circle_outline, size: 48, color: Colors.green),
            SizedBox(height: 12),
            Text('Les fichiers sont identiques'),
          ],
        ),
      );
    }
    return ListView.builder(
      padding: EdgeInsets.zero,
      itemCount: _diff.length,
      itemBuilder: (_, i) {
        final line = _diff[i];
        final Color? bg;
        final Color textColor;
        final String prefix;
        switch (line.type) {
          case _DiffType.added:
            bg = Colors.green.withValues(alpha: 0.12);
            textColor = Colors.green;
            prefix = '+ ';
          case _DiffType.removed:
            bg = Colors.red.withValues(alpha: 0.12);
            textColor = Colors.red;
            prefix = '- ';
          case _DiffType.same:
            bg = null;
            textColor = Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.5);
            prefix = '  ';
        }
        return Container(
          color: bg,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 1),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: 18,
                child: Text(prefix,
                    style: TextStyle(
                        color: textColor, fontFamily: 'monospace',
                        fontSize: 12, fontWeight: FontWeight.w700)),
              ),
              Expanded(
                child: Text(line.content,
                    style: TextStyle(
                        color: textColor, fontFamily: 'monospace', fontSize: 12),
                    overflow: TextOverflow.ellipsis),
              ),
            ],
          ),
        );
      },
    );
  }
}

enum _DiffType { same, added, removed }

class _DiffLine {
  final _DiffType type;
  final String content;
  _DiffLine(this.type, this.content);
}
