import 'dart:io';
import 'package:flutter/material.dart';
import 'package:google_mlkit_document_scanner/google_mlkit_document_scanner.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

/// Scanner de document via ML Kit (Google) — on-device, gratuit.
/// Détection automatique des bords, redressement perspective, sortie PDF.
class ScannerScreen extends StatefulWidget {
  const ScannerScreen({super.key});

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  bool _busy = false;
  String? _lastPdfPath;
  String? _error;
  int _pageLimit = 10;

  Future<void> _scan() async {
    setState(() { _busy = true; _error = null; });
    DocumentScanner? scanner;
    try {
      scanner = DocumentScanner(
        options: DocumentScannerOptions(
          documentFormat: DocumentFormat.pdf,
          mode: ScannerMode.full,
          pageLimit: _pageLimit,
          isGalleryImport: true,
        ),
      );
      final result = await scanner.scanDocument();
      // result.pdf?.uri pointe vers un fichier ML Kit dans le cache externe.
      // On le copie dans notre tmp pour gérer le cycle de vie nous-mêmes.
      final pdfRes = result.pdf;
      if (pdfRes == null) {
        if (!mounted) return;
        setState(() { _busy = false; _error = 'Aucun document scanné'; });
        return;
      }
      final src = File(pdfRes.uri.replaceFirst('file://', ''));
      if (!await src.exists()) {
        throw FileSystemException('Fichier scan introuvable', src.path);
      }
      final tmp = await getTemporaryDirectory();
      final dest = File('${tmp.path}/scan_${DateTime.now().millisecondsSinceEpoch}.pdf');
      await src.copy(dest.path);
      // src appartient à ML Kit — on ne le supprime pas, c'est leur cache.
      if (!mounted) return;
      setState(() { _busy = false; _lastPdfPath = dest.path; });
    } catch (e) {
      if (!mounted) return;
      setState(() { _busy = false; _error = 'Erreur scanner : $e'; });
    } finally {
      // Libère les ressources natives du scanner.
      try { await scanner?.close(); } catch (_) {}
    }
  }

  Future<void> _share() async {
    if (_lastPdfPath == null) return;
    await Share.shareXFiles([XFile(_lastPdfPath!)]);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Scanner un document'),
        actions: [
          if (_lastPdfPath != null)
            IconButton(
              icon: const Icon(Icons.share),
              tooltip: 'Partager le PDF',
              onPressed: _share,
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_busy) const LinearProgressIndicator(),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(children: [
                    Icon(Icons.document_scanner, size: 32, color: cs.primary),
                    const SizedBox(width: 12),
                    const Expanded(
                      child: Text('Scanner un document',
                          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                    ),
                  ]),
                  const SizedBox(height: 12),
                  const Text(
                    'Détection des bords automatique, redressement perspective '
                    'et export PDF. 100 % local — vos documents ne quittent '
                    'jamais l\'appareil.',
                    style: TextStyle(fontSize: 13),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Text('Pages maximum : $_pageLimit',
              style: const TextStyle(fontSize: 12, color: Colors.grey)),
          Slider(
            value: _pageLimit.toDouble(),
            min: 1, max: 25, divisions: 24,
            label: '$_pageLimit',
            onChanged: _busy ? null : (v) => setState(() => _pageLimit = v.toInt()),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : _scan,
            icon: const Icon(Icons.photo_camera_outlined),
            label: Text(_busy ? 'Scan en cours…' : 'Lancer le scan'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.red.withValues(alpha: 0.10),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.red.withValues(alpha: 0.3)),
              ),
              child: Text(_error!,
                  style: const TextStyle(fontSize: 12, color: Colors.red)),
            ),
          ],
          if (_lastPdfPath != null) ...[
            const SizedBox(height: 24),
            Card(
              color: Colors.green.withValues(alpha: 0.10),
              child: ListTile(
                leading: const Icon(Icons.picture_as_pdf, color: Colors.green),
                title: Text(_lastPdfPath!.split(RegExp(r'[/\\]')).last,
                    overflow: TextOverflow.ellipsis),
                subtitle: const Text('PDF prêt — toucher pour partager'),
                trailing: const Icon(Icons.share),
                onTap: _share,
              ),
            ),
          ],
        ],
      ),
    );
  }
}
