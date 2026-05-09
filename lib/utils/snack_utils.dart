import 'package:flutter/material.dart';

/// Durées standardisées pour SnackBar (homogénéise les valeurs disparates).
const kSnackShort = Duration(seconds: 2);
const kSnackMedium = Duration(seconds: 4);
const kSnackLong = Duration(seconds: 6);

/// Affiche un SnackBar floating cohérent. Utilise `rootMessenger` pour
/// fonctionner même depuis un dialog/bottom sheet.
void showFloatingSnack(
  BuildContext context,
  String message, {
  Duration duration = kSnackShort,
  SnackBarAction? action,
}) {
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(message),
      behavior: SnackBarBehavior.floating,
      duration: duration,
      action: action,
    ),
  );
}

/// Variante erreur : couleur error + durée medium par défaut.
void showErrorSnack(
  BuildContext context,
  Object error, {
  Duration duration = kSnackMedium,
}) {
  if (!context.mounted) return;
  final cs = Theme.of(context).colorScheme;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(error.toString()),
      behavior: SnackBarBehavior.floating,
      backgroundColor: cs.errorContainer,
      duration: duration,
    ),
  );
}

/// Variante succès : neutral floating, courte. Sucre syntaxique.
void showSuccessSnack(BuildContext context, String message) =>
    showFloatingSnack(context, message);
