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

/// De quoi afficher un bandeau **après** un `await`.
///
/// Le problème que cette classe résout est banal et se retrouvait partout :
/// une opération longue échoue, on veut le dire, mais `context` a pu devenir
/// invalide entre-temps. Deux réponses cohabitaient dans ce dépôt :
///
/// - `showErrorSnack(context, e)`, qui teste `context.mounted` et **renonce**
///   si l'écran est parti — silence complet, y compris sur l'échec ;
/// - `final messenger = ScaffoldMessenger.of(context);` capturé *avant* le
///   `await`, puis `messenger.showSnackBar(SnackBar(...))` écrit à la main —
///   qui affiche bien le message, mais reconstruisait le `SnackBar` sur place,
///   sans la couleur d'erreur, sans `persist: false`, et sans `behavior`.
///
/// Le second comportement est le bon : le messager appartient au `Scaffold`
/// englobant, qui survit à l'écran qu'on vient de quitter. C'était la mise en
/// forme qui manquait. [SnackTarget] capture le messager **et** le
/// `ColorScheme` en une ligne, et rend les mêmes bandeaux que les fonctions
/// ci-dessous.
///
/// ```dart
/// final snack = SnackTarget.of(context);
/// try {
///   await longueOperation();
/// } catch (e) {
///   snack.error(e);   // s'affiche même si l'écran a été quitté
/// }
/// ```
@immutable
final class SnackTarget {
  final ScaffoldMessengerState _messenger;
  final ColorScheme _colors;

  const SnackTarget._(this._messenger, this._colors);

  /// À appeler **avant** le premier `await`, tant que [context] est valide.
  factory SnackTarget.of(BuildContext context) => SnackTarget._(
    ScaffoldMessenger.of(context),
    Theme.of(context).colorScheme,
  );

  /// Bandeau neutre.
  void info(
    String message, {
    Duration duration = kSnackShort,
    SnackBarAction? action,
  }) {
    _messenger.showSnackBar(
      _build(content: Text(message), duration: duration, action: action),
    );
  }

  /// Bandeau d'erreur : fond `errorContainer`, texte `onErrorContainer`.
  void error(
    Object error, {
    Duration duration = kSnackMedium,
    SnackBarAction? action,
  }) {
    _messenger.showSnackBar(
      _build(
        content: Text(
          error.toString(),
          style: TextStyle(color: _colors.onErrorContainer),
        ),
        background: _colors.errorContainer,
        duration: duration,
        action: action,
      ),
    );
  }

  /// Retire le bandeau courant sans attendre sa durée.
  void clear() => _messenger.hideCurrentSnackBar();

  SnackBar _build({
    required Widget content,
    required Duration duration,
    Color? background,
    SnackBarAction? action,
  }) => SnackBar(
    content: content,
    behavior: SnackBarBehavior.floating,
    backgroundColor: background,
    duration: duration,
    // `SnackBar.persist` vaut par défaut `action != null` : sans ce flag,
    // passer une [action] rendrait le bandeau permanent et [duration]
    // inopérante (cf. snack_bar.dart / scaffold.dart).
    persist: false,
    action: action,
  );
}

/// Affiche un SnackBar floating cohérent.
///
/// Ne fait rien si [context] n'est plus monté. Quand le message doit survivre
/// à la disparition de l'écran — typiquement le résultat d'une opération
/// longue — utiliser [SnackTarget] capturé avant l'`await`.
void showFloatingSnack(
  BuildContext context,
  String message, {
  Duration duration = kSnackShort,
  SnackBarAction? action,
}) {
  if (!context.mounted) return;
  SnackTarget.of(context).info(message, duration: duration, action: action);
}

/// Variante erreur : couleur error + durée medium par défaut.
///
/// U4 v2.13.0 — Accepte une [action] optionnelle (typiquement "Réessayer" /
/// "Annuler") afin de standardiser le pattern d'erreur récupérable. La
/// couleur du texte est explicitement `onErrorContainer` pour garantir le
/// contraste WCAG AA même en thème clair.
///
/// Ne fait rien si [context] n'est plus monté — voir [SnackTarget].
void showErrorSnack(
  BuildContext context,
  Object error, {
  Duration duration = kSnackMedium,
  SnackBarAction? action,
}) {
  if (!context.mounted) return;
  SnackTarget.of(context).error(error, duration: duration, action: action);
}

/// Variante succès : neutral floating, courte. Sucre syntaxique.
void showSuccessSnack(BuildContext context, String message) =>
    showFloatingSnack(context, message);
