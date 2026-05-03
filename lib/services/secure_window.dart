import 'package:flutter/services.dart';

/// Pose / retire `WindowManager.LayoutParams.FLAG_SECURE` sur la fenêtre
/// principale via un MethodChannel Kotlin.
///
/// Effets :
/// - Bloque captures d'écran et enregistrement écran
/// - Masque l'aperçu dans Recent Apps (vignette noire)
///
/// À appeler avec `true` quand un contenu sensible est affiché (coffre déverrouillé,
/// signature PDF, contenu déchiffré) et `false` au verrouillage / fermeture.
class SecureWindow {
  static const _channel = MethodChannel('com.readfilestech/lifecycle');
  static bool _enabled = false;

  static Future<void> enable() async {
    if (_enabled) return;
    try {
      await _channel.invokeMethod('setSecure', {'enabled': true});
      _enabled = true;
    } catch (_) {
      /* silent — non bloquant */
    }
  }

  static Future<void> disable() async {
    if (!_enabled) return;
    try {
      await _channel.invokeMethod('setSecure', {'enabled': false});
      _enabled = false;
    } catch (_) {
      /* silent */
    }
  }
}
