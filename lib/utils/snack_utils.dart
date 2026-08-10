import 'package:flutter/material.dart';

/// Bleu du logo Read Files Tech — même valeur que le `seedColor` du
/// `ColorScheme` (`main.dart`). Utilisé pour les SnackBar d'information à
/// action, afin de rester dans la palette de l'app plutôt que le gris-noir
/// par défaut de Material.
const kBrandBlue = Color(0xFF1565C0);

/// Rouge des bandeaux d'erreur.
///
/// Les bandeaux utilisaient `colorScheme.errorContainer`. En Material 3, cette
/// teinte est dérivée du `seedColor` — ici un bleu — et l'harmonisation la
/// désature jusqu'à un rose pâle : à l'écran, un refus de sécurité passait pour
/// du saumon décoratif. Une alerte doit se lire comme une alerte.
///
/// Valeur fixe et non dérivée du thème, parce qu'un SnackBar porte son propre
/// fond : la même couleur convient en clair comme en sombre. Le texte est
/// blanc, contraste 4,98:1 — au-dessus du seuil AA de 4,5:1, mais de peu :
/// un rouge sensiblement plus clair passerait sous le seuil, ce que le test
/// « l'erreur porte un rouge franc » verrouille.
///
/// `ErrorPanel` s'en sert pour sa **bordure**, jamais pour son texte : un
/// filet coloré tient sur n'importe quel fond, alors qu'un rouge fixe posé en
/// texte sur le fond sombre de l'app tomberait sous le seuil de lisibilité.
/// Le texte y reste donc `colorScheme.error`, qui suit le thème.
const kErrorRed = Color(0xFFD32F2F);

/// Durées standardisées pour SnackBar (homogénéise les valeurs disparates).
///
/// [kSnackShort] est passée de 2 à 3 secondes le 2026-08-10 : deux secondes ne
/// suffisaient pas à lire un message un peu long avant qu'il ne parte.
const kSnackShort = Duration(seconds: 3);
const kSnackMedium = Duration(seconds: 4);
const kSnackLong = Duration(seconds: 6);

/// Durée d'un bandeau qui demande une **décision**, pas seulement une lecture.
///
/// Les durées ci-dessus sont calibrées pour lire et laisser partir. Dès qu'une
/// action est proposée, il faut lire, comprendre l'enjeu, puis viser un
/// bouton. Plusieurs bandeaux à action n'indiquaient aucune durée et tombaient
/// donc sur le défaut « info » : l'action disparaissait avant d'avoir pu être
/// lue — constaté le 2026-08-10 sur « Rien restauré … Remplacer ».
///
/// Sept secondes, valeur choisie par Patrice à l'usage sur appareil. C'est
/// court pour lire *et* décider ; si une action se révèle manquée en pratique,
/// c'est ici qu'il faut l'allonger, pas au site d'appel.
const kSnackAction = Duration(seconds: 7);

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
/// forme qui manquait. [SnackTarget] capture le messager en une ligne, et rend
/// les mêmes bandeaux que les fonctions ci-dessous.
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
  // Le `ColorScheme` n'est plus retenu : depuis que les bandeaux d'erreur
  // utilisent kErrorRed, aucune couleur ne se dérive plus du thème ici.
  final bool Function()? _stillWanted;

  const SnackTarget._(this._messenger, this._stillWanted);

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
  }) => SnackTarget._(ScaffoldMessenger.of(context), stillWanted);

  /// Bandeau neutre.
  ///
  /// [duration] laissee nulle, la duree se deduit de la presence d'une action —
  /// voir [_resolve].
  void info(String message, {Duration? duration, SnackBarAction? action}) {
    _show(
      _build(
        content: Text(message),
        duration: _resolve(duration, action, kSnackShort),
        action: action,
      ),
    );
  }

  /// Duree effective d'un bandeau.
  ///
  /// **Pourquoi ce n'est pas un simple defaut de parametre.** Un bandeau qui
  /// PROPOSE quelque chose demande de lire, de comprendre l'enjeu, puis de
  /// viser un bouton. Les durees de lecture n'y suffisent pas : plusieurs
  /// bandeaux a action n'indiquaient aucune duree et tombaient donc sur les
  /// deux secondes du defaut « info » — l'action disparaissait avant d'avoir
  /// pu etre lue. Constate le 2026-08-10 sur « Rien restaure … Remplacer ».
  ///
  /// Le resoudre ICI plutot qu'a chaque appel ferme le mode de panne pour de
  /// bon : un futur bandeau a action heritera de la bonne duree sans que
  /// personne ait a y penser.
  static Duration _resolve(
    Duration? explicite,
    SnackBarAction? action,
    Duration defautSansAction,
  ) {
    if (explicite != null) return explicite;
    return action != null ? kSnackAction : defautSansAction;
  }

  /// Bandeau d'erreur : fond [kErrorRed], texte blanc.
  void error(Object error, {Duration? duration, SnackBarAction? action}) {
    _show(
      _build(
        content: Text(
          error.toString(),
          style: const TextStyle(color: Colors.white),
        ),
        background: kErrorRed,
        duration: _resolve(duration, action, kSnackMedium),
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
  Duration? duration,
  SnackBarAction? action,
}) {
  if (!context.mounted) return;
  SnackTarget.of(context).info(message, duration: duration, action: action);
}

/// Variante erreur : couleur error + durée medium par défaut.
///
/// U4 v2.13.0 — Accepte une [action] optionnelle (typiquement "Réessayer" /
/// "Annuler") afin de standardiser le pattern d'erreur récupérable. Le fond
/// est [kErrorRed] et le texte blanc, ce qui garantit le contraste WCAG AA
/// dans les deux thèmes.
///
/// Ne fait rien si [context] n'est plus monté — voir [SnackTarget].
void showErrorSnack(
  BuildContext context,
  Object error, {
  Duration? duration,
  SnackBarAction? action,
}) {
  if (!context.mounted) return;
  SnackTarget.of(context).error(error, duration: duration, action: action);
}

/// Variante succès : neutral floating, courte. Sucre syntaxique.
void showSuccessSnack(BuildContext context, String message) =>
    showFloatingSnack(context, message);
