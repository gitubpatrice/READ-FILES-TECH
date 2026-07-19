import 'package:flutter/material.dart';

import '../../../widgets/danger_style.dart';

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

/// Confirmation rouge d'une suppression DÉFINITIVE. Renvoie true si confirmé.
///
/// v2.13.2 (S3) — pattern destructif Files Tech : `autofocus: true` sur
/// Cancel (anti-clic réflexe).
/// v2.14.0 — fond rouge plein + texte blanc ([dangerFilledButtonStyle]) :
/// `cs.errorContainer` rendait le bouton pâle, donc peu distinguable du
/// bouton d'annulation sur une action irréversible.
Future<bool> confirmDelete(
  BuildContext context, {
  required String title,
  required String message,
  String confirmLabel = 'Supprimer définitivement',
}) async {
  final res = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Text(message),
      actions: [
        TextButton(
          autofocus: true,
          onPressed: () => Navigator.pop(ctx, false),
          child: const Text('Annuler'),
        ),
        FilledButton(
          style: dangerFilledButtonStyle(),
          onPressed: () => Navigator.pop(ctx, true),
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
  return res == true;
}
