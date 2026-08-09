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
  final bool Function()? _stillWanted;

  const SnackTarget._(this._messenger, this._colors, this._stillWanted);

  /// À appeler **avant** le premier `await`, tant que [context] est valide.
  ///
  /// [stillWanted] permet à un écran de **renoncer** à son bandeau s'il a
  /// disparu entre-temps. C'est l'exception, pas la règle : par défaut le
  /// message s'affiche quoi qu'il arrive, puisque c'est tout l'intérêt de
  /// cette classe.
  ///
  /// L'exception existe pour le coffre. `VaultScreen.dispose()` **verrouille**
  /// (V-H1) : quitter l'écran, c'est en avoir fini. Un bandeau annonçant
  /// « Exporté : releve_bancaire.pdf » qui arrive après coup afficherait donc
  /// un nom de fichier du coffre sur un écran quelconque, alors que le coffre
  /// est déjà refermé — exactement ce que le verrouillage automatique sert à
  /// empêcher. Là, se taire est le bon comportement.
  ///
  /// ```dart
  /// final snack = SnackTarget.of(context, stillWanted: () => mounted);
  /// ```
  factory SnackTarget.of(
    BuildContext context, {
    bool Function()? stillWanted,
  }) => SnackTarget._(
    ScaffoldMessenger.of(context),
    Theme.of(context).colorScheme,
    stillWanted,
  );

  /// Bandeau neutre.
  void info(
    String message, {
    Duration duration = kSnackShort,
    SnackBarAction? action,
  }) {
    _show(_build(content: Text(message), duration: duration, action: action));
  }

  /// Bandeau d'erreur : fond `errorContainer`, texte `onErrorContainer`.
  void error(
    Object error, {
    Duration duration = kSnackMedium,
    SnackBarAction? action,
  }) {
    _show(
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
  void clear() {
    if (!_messenger.mounted) return;
    _messenger.hideCurrentSnackBar();
  }

  /// Le messager appartient normalement au `MaterialApp` racine et survit à
  /// tout ce que l'application pousse par-dessus. « Normalement » : rien
  /// n'empêche un `ScaffoldMessenger` imbriqué dans un sous-arbre qui, lui,
  /// peut disparaître. Appeler `showSnackBar` sur un `State` déjà libéré
  /// déclencherait un `setState` après `dispose` — en release, un
  /// déréférencement nul plutôt qu'une assertion lisible.
  ///
  /// Aucun `ScaffoldMessenger` imbriqué n'existe dans l'application à ce jour
  /// (vérifié le 2026-08-09) ; ce garde existe pour que le jour où l'un
  /// apparaîtra ne se solde pas par un plantage à distance de sa cause.
  void _show(SnackBar bar) {
    if (_stillWanted != null && !_stillWanted()) return;
    if (!_messenger.mounted) return;
    _messenger.showSnackBar(bar);
  }

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
