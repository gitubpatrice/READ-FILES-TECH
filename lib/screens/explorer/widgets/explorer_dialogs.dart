import 'package:flutter/material.dart';

/// Demande à l'utilisateur un nom (création / renommage). Renvoie le texte
/// trimé ou `null` si annulé / vide.
Future<String?> promptName(
  BuildContext context, {
  required String title,
  required String confirmLabel,
  String initial = '',
  String? hint,
}) async {
  final ctrl = TextEditingController(text: initial);
  final res = await showDialog<String>(
    context: context,
    builder: (_) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: ctrl,
        autofocus: true,
        decoration: InputDecoration(
          hintText: hint,
          border: const OutlineInputBorder(),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Annuler'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, ctrl.text.trim()),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  if (res == null || res.isEmpty) return null;
  return res;
}

/// Confirmation rouge typée "Supprimer". Renvoie true si confirmé.
///
/// v2.13.2 (S3) — pattern destructif Files Tech : `autofocus: true` sur
/// Cancel (anti-clic réflexe) + `cs.errorContainer` au lieu de
/// `Colors.red` hardcodé (non adaptive dark mode, ratio WCAG variable).
/// Aligné Pass Tech v2.4.4 U2, PDF Tech v1.12.5 U3.
Future<bool> confirmDelete(
  BuildContext context, {
  required String title,
  required String message,
}) async {
  final cs = Theme.of(context).colorScheme;
  final res = await showDialog<bool>(
    context: context,
    builder: (_) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(
          autofocus: true,
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Annuler'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(
            backgroundColor: cs.errorContainer,
            foregroundColor: cs.onErrorContainer,
          ),
          onPressed: () => Navigator.pop(context, true),
          child: const Text('Supprimer'),
        ),
      ],
    ),
  );
  return res == true;
}
