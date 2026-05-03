import 'dart:io';
import 'package:flutter/material.dart';
import 'package:google_mlkit_document_scanner/google_mlkit_document_scanner.dart';
import 'package:share_plus/share_plus.dart';
import '../../services/output_storage_service.dart';
import '../../widgets/output_actions_row.dart';
import '../explorer/file_explorer_screen.dart';

/// Scanner de document via ML Kit (Google) — on-device, gratuit.
/// Détection automatique des bords, redressement perspective, sortie PDF.
class ScannerScreen extends StatefulWidget {
  const ScannerScreen({super.key});

  @override
  State<ScannerScreen> createState() => _ScannerScreenState();
}

class _ScannerScreenState extends State<ScannerScreen> {
  final _storage = OutputStorageService();
  bool _busy = false;
  String? _lastPdfPath;
  String? _error;
  int _pageLimit = 10;

  Future<void> _scan() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    DocumentScanner? scanner;
    try {
      scanner = DocumentScanner(
        options: DocumentScannerOptions(
          documentFormats: {DocumentFormat.pdf},
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
        setState(() {
          _busy = false;
          _error = 'Aucun document scanné';
        });
        return;
      }
      final src = File(pdfRes.uri.replaceFirst('file://', ''));
      if (!await src.exists()) {
        throw FileSystemException('Fichier scan introuvable', src.path);
      }
      // Sauvegarde persistante dans <Files Tech>/Scans/scan_yyyyMMdd_HHmmss.pdf
      final dest = await _storage.reserveFile(
        category: OutputCategory.scans,
        suggestedName: 'scan',
        extension: 'pdf',
      );
      await src.copy(dest.path);
      // src appartient à ML Kit — on ne le supprime pas, c'est leur cache.
      final autoShare = await _storage.getAutoShare();
      if (!mounted) return;
      setState(() {
        _busy = false;
        _lastPdfPath = dest.path;
      });
      if (autoShare) {
        await Share.shareXFiles([XFile(dest.path)]);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = 'Erreur scanner : $e';
      });
    } finally {
      // Libère les ressources natives du scanner.
      try {
        await scanner?.close();
      } catch (_) {}
    }
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
              onPressed: () => Share.shareXFiles([XFile(_lastPdfPath!)]),
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
                  Row(
                    children: [
                      Icon(Icons.document_scanner, size: 32, color: cs.primary),
                      const SizedBox(width: 12),
                      const Expanded(
                        child: Text(
                          'Scanner un document',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
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
          Text(
            'Pages maximum : $_pageLimit',
            style: const TextStyle(fontSize: 12, color: Colors.grey),
          ),
          Slider(
            value: _pageLimit.toDouble(),
            min: 1,
            max: 25,
            divisions: 24,
            label: '$_pageLimit',
            onChanged: _busy
                ? null
                : (v) => setState(() => _pageLimit = v.toInt()),
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
              child: Text(
                _error!,
                style: const TextStyle(fontSize: 12, color: Colors.red),
              ),
            ),
          ],
          if (_lastPdfPath != null) ...[
            const SizedBox(height: 24),
            Card(
              color: Colors.lightBlue.shade50.withValues(alpha: 0.85),
              shape: RoundedRectangleBorder(
                side: BorderSide(color: Colors.lightBlue.shade300, width: 1.5),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          Icons.check_circle,
                          color: Colors.lightBlue.shade700,
                          size: 24,
                        ),
                        const SizedBox(width: 8),
                        Text(
                          'Scan sauvegardé sur votre téléphone',
                          style: TextStyle(
                            fontWeight: FontWeight.w700,
                            fontSize: 15,
                            color: Colors.lightBlue.shade900,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      'Le PDF est enregistré ici, vous pourrez le retrouver à tout moment :',
                      style: TextStyle(fontSize: 12),
                    ),
                    const SizedBox(height: 6),
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.05),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        _lastPdfPath!,
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: () {
                        final dir = _lastPdfPath!.substring(
                          0,
                          _lastPdfPath!.lastIndexOf(RegExp(r'[/\\]')),
                        );
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) =>
                                FileExplorerScreen(initialPath: dir),
                          ),
                        );
                      },
                      icon: const Icon(Icons.folder_open),
                      label: const Text('Voir le fichier dans l\'explorateur'),
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      'Ou envoyer vers :',
                      style: TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                    const SizedBox(height: 6),
                    OutputActionsRow(
                      path: _lastPdfPath!,
                      mime: 'application/pdf',
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
