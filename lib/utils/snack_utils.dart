import 'package:flutter/material.dart';

/// Bleu du logo Read Files Tech — même valeur que le `seedColor` du
/// `ColorScheme` (`main.dart`). Utilisé pour les SnackBar d'information à
/// action, afin de rester dans la palette de l'app plutôt que le gris-noir
/// par défaut de Material.
const kBrandBlue = Color(0xFF1565C0);

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
      // `SnackBar.persist` vaut par défaut `action != null` : sans ce flag,
      // passer une [action] rendrait le bandeau permanent et [duration]
      // inopérante (cf. snack_bar.dart / scaffold.dart).
      persist: false,
      action: action,
    ),
  );
}

/// Variante erreur : couleur error + durée medium par défaut.
///
/// U4 v2.13.0 — Accepte une [action] optionnelle (typiquement "Réessayer" /
/// "Annuler") afin de standardiser le pattern d'erreur récupérable. La
/// couleur du texte est explicitement `onErrorContainer` pour garantir le
/// contraste WCAG AA même en thème clair.
void showErrorSnack(
  BuildContext context,
  Object error, {
  Duration duration = kSnackMedium,
  SnackBarAction? action,
}) {
  if (!context.mounted) return;
  final cs = Theme.of(context).colorScheme;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(
        error.toString(),
        style: TextStyle(color: cs.onErrorContainer),
      ),
      behavior: SnackBarBehavior.floating,
      backgroundColor: cs.errorContainer,
      duration: duration,
      // `SnackBar.persist` vaut par défaut `action != null` : sans ce flag,
      // passer une [action] rendrait le bandeau permanent et [duration]
      // inopérante (cf. snack_bar.dart / scaffold.dart).
      persist: false,
      action: action,
    ),
  );
}

/// Variante succès : neutral floating, courte. Sucre syntaxique.
void showSuccessSnack(BuildContext context, String message) =>
    showFloatingSnack(context, message);
