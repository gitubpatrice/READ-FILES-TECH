import 'package:flutter/material.dart';

class PermissionBanner extends StatelessWidget {
  final VoidCallback onOpenSettings;

  /// Texte adapté à la version d'Android : annoncer « tous les fichiers » à
  /// quelqu'un qui n'a pas cette option — Android 10 et antérieurs — c'est
  /// l'envoyer chercher ce qui n'existe pas. Voir `StorageAccess`.
  final String message;

  const PermissionBanner({
    super.key,
    required this.onOpenSettings,
    this.message = 'Accès aux fichiers limité — autorisez le stockage.',
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.fromLTRB(12, 4, 12, 4),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: cs.error.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: cs.error.withValues(alpha: 0.3)),
      ),
      child: Row(
        children: [
          Icon(Icons.warning_amber_rounded, size: 18, color: cs.error),
          const SizedBox(width: 8),
          Expanded(child: Text(message, style: const TextStyle(fontSize: 12))),
          TextButton(
            onPressed: onOpenSettings,
            style: TextButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            child: const Text('Réglages'),
          ),
        ],
      ),
    );
  }
}
