import 'package:flutter/services.dart';

/// Pose / retire `WindowManager.LayoutParams.FLAG_SECURE` sur la fenêtre
/// principale via un MethodChannel Kotlin.
///
/// Effets :
/// - Bloque captures d'écran et enregistrement écran
/// - Masque l'aperçu dans Recent Apps (vignette noire)
///
/// À appeler avec [enable] quand un contenu sensible est affiché (coffre
/// déverrouillé, signature PDF, contenu déchiffré) et [disable] au verrouillage
/// / fermeture de l'écran.
///
/// G4 v2.12.1 — Refcount au lieu d'un bool : si deux écrans sensibles se
/// chevauchent (ex. Vault → SignatureCapture), le `disable()` du second ne
/// doit PAS retirer le flag tant que le premier est encore vivant.
/// Pattern aligné Notes Tech v1.0.6 / PDF Tech v1.12.2.
class SecureWindow {
  static const _channel = MethodChannel('com.readfilestech/lifecycle');
  static int _refs = 0;

  static Future<void> enable() async {
    _refs++;
    if (_refs != 1) return; // déjà actif côté Kotlin
    try {
      await _channel.invokeMethod('setSecure', {'enabled': true});
    } catch (_) {
      /* silent — non bloquant */
    }
  }

  static Future<void> disable() async {
    if (_refs == 0) return; // déséquilibré : ne descend pas sous zéro
    _refs--;
    if (_refs != 0) return; // d'autres écrans demandent encore le flag
    try {
      await _channel.invokeMethod('setSecure', {'enabled': false});
    } catch (_) {
      /* silent */
    }
  }
}
