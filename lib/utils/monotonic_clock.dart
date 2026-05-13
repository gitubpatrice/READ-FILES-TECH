import 'package:flutter/services.dart';

/// Source de temps monotone exposée par Android (`SystemClock.elapsedRealtime`).
///
/// F3 v2.13.0 — `DateTime.now().millisecondsSinceEpoch` est manipulable par
/// l'utilisateur via Réglages → Date/heure (ou `adb shell date -s`). Le
/// lockout brute-force du coffre s'appuyait dessus → un attaquant ayant
/// l'appareil déverrouillé pouvait remettre l'horloge en arrière et
/// contourner instantanément le backoff exponentiel.
///
/// `elapsedRealtime()` repart de zéro à chaque boot et n'est PAS modifiable
/// par l'utilisateur. Une combinaison `max(wall, monotonic)` côté lockout
/// rend la fenêtre incassable sans reboot complet.
///
/// Fallback : si le channel échoue (debug, hot-reload), on retombe sur
/// `DateTime.now()` — la dégradation revient au comportement v2.12.x.
abstract final class MonotonicClock {
  MonotonicClock._();

  static const _channel = MethodChannel('com.readfilestech/lifecycle');

  /// Millisecondes depuis le boot. Fallback wall-clock si channel KO.
  static Future<int> elapsedRealtimeMs() async {
    try {
      final r = await _channel.invokeMethod<int>('elapsedRealtime');
      if (r != null) return r;
    } catch (_) {
      /* fallback */
    }
    return DateTime.now().millisecondsSinceEpoch;
  }
}
