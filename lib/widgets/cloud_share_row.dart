import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

/// Rangée de boutons d'envoi cloud direct + partage générique.
/// Réutilisée dans Scanner, Convert, Compress, EXIF, Signature, OCR.
///
/// Sécurité : utilise le MethodChannel `com.readfilestech/open_file` côté
/// Kotlin (méthode `sendToPackage`) qui passe par FileProvider + setPackage.
/// Pas d'URI directe, pas d'exfiltration possible vers une app non listée
/// dans le manifest `queries`.
class CloudShareRow extends StatelessWidget {
  final String path;
  final String mime;
  const CloudShareRow({
    super.key,
    required this.path,
    this.mime = 'application/octet-stream',
  });

  static const _channel = MethodChannel('com.readfilestech/open_file');

  static const _kDrive = 'com.infomaniak.drive';
  static const _googleDrive = 'com.google.android.apps.docs';
  static const _protonDrive = 'me.proton.android.drive';

  Future<void> _send(BuildContext context, String pkg, String label) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await _channel.invokeMethod('sendToPackage', {
        'path': path,
        'mime': mime,
        'package': pkg,
      });
    } on PlatformException catch (e) {
      messenger.showSnackBar(SnackBar(
        content: Text(e.code == 'NOT_INSTALLED'
            ? '$label n\'est pas installé sur cet appareil.'
            : 'Erreur d\'envoi vers $label.'),
      ));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 6,
      children: [
        OutlinedButton.icon(
          onPressed: () => Share.shareXFiles([XFile(path, mimeType: mime)]),
          icon: const Icon(Icons.share, size: 14),
          label: const Text('Partager', style: TextStyle(fontSize: 12)),
        ),
        OutlinedButton.icon(
          onPressed: () => _send(context, _kDrive, 'kDrive'),
          icon: const Icon(Icons.cloud_upload_outlined, size: 14, color: Color(0xFF0098FF)),
          label: const Text('kDrive', style: TextStyle(fontSize: 12)),
        ),
        OutlinedButton.icon(
          onPressed: () => _send(context, _googleDrive, 'Google Drive'),
          icon: const Icon(Icons.cloud_upload_outlined, size: 14, color: Color(0xFFEA4335)),
          label: const Text('Google Drive', style: TextStyle(fontSize: 12)),
        ),
        OutlinedButton.icon(
          onPressed: () => _send(context, _protonDrive, 'Proton Drive'),
          icon: const Icon(Icons.cloud_upload_outlined, size: 14, color: Color(0xFF6D4AFF)),
          label: const Text('Proton Drive', style: TextStyle(fontSize: 12)),
        ),
      ],
    );
  }
}
