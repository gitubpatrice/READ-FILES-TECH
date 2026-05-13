/// Constantes globales partagées par plusieurs écrans/services.
///
/// G16 v2.12.1 — Centralise les valeurs précédemment dupliquées entre
/// `main.dart` et `vault_screen.dart` pour éviter qu'elles dérivent.
abstract final class AppConstants {
  AppConstants._();

  /// Délai de verrouillage automatique du coffre après mise en pause.
  /// Aligné sur Bitwarden / KeePassDX : 30 s. Lock immédiat sur `detached`.
  static const Duration autoLockDelay = Duration(seconds: 30);

  /// Durées SnackBar (alignées sur snack_utils mais accessibles aux services
  /// qui n'importent pas Material).
  static const Duration snackShort = Duration(seconds: 2);
  static const Duration snackMedium = Duration(seconds: 4);
}
