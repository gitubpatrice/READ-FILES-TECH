/// Calcul du blocage anti force brute du coffre — commun au déverrouillage et à la restauration
/// d'une sauvegarde `.rftvault`.
///
/// **Pourquoi ce fichier existe.** Les deux verrous calculaient leur temps restant chacun de son
/// côté, avec la même faute (défaut D2, reproduit le 2026-10-10 sur émulateur) : l'échéance
/// « monotone » est un `elapsedRealtime`, qui **repart de zéro à chaque démarrage** du téléphone.
/// Une échéance posée avant un redémarrage était donc comparée à une horloge remise à zéro, et le
/// blocage durait de l'ordre de l'**ancien temps de fonctionnement** — des jours sur un téléphone
/// allumé depuis des jours, bon mot de passe compris. Une seule fonction, testée, pour les deux.
library;

/// Délai de blocage, en millisecondes, après [fails] échecs consécutifs : aucun avant le
/// cinquième, puis 1, 2, 4, 8, 16 et 30 minutes (plafond).
int lockoutDelayMs(int fails) {
  if (fails < 5) return 0;
  const minutes = [1, 2, 4, 8, 16, 30];
  return minutes[(fails - 5).clamp(0, minutes.length - 1)] * 60 * 1000;
}

/// Temps de blocage restant, en millisecondes (0 = libre).
///
/// Les deux échéances ([wallDeadline] sur l'horloge murale, [monoDeadline] sur
/// `elapsedRealtime`) ont été posées au plus [lockoutDelayMs] en avant de leur horloge. Pendant
/// un même démarrage, avec une horloge qui n'a pas reculé, leur temps restant ne peut donc
/// JAMAIS dépasser ce délai. Une échéance plus lointaine ne décrit pas ce démarrage :
///
/// - monotone : elle appartient à un démarrage précédent (l'horloge est repartie de zéro) ;
///   [monoArmedAt], l'`elapsedRealtime` de l'armement, le dit aussi quand l'horloge est
///   aujourd'hui en deçà. `null` pour une échéance posée par une version antérieure — c'est
///   alors l'invariant seul qui la reconnaît ;
/// - murale : l'horloge a été reculée après l'armement.
///
/// Une telle échéance est écartée ; la seconde horloge décide. Dans le démarrage en cours, la
/// monotone tient seule si l'on avance l'horloge murale (Réglages → Date et heure) — la
/// protection de F3 v2.13.0 est intacte. Ce qui n'est plus couvert : redémarrer ET avancer
/// l'horloge murale ; chaque essai coûte alors un redémarrage.
int lockoutRemainingMs({
  required int fails,
  required int wallNow,
  required int monoNow,
  required int wallDeadline,
  required int monoDeadline,
  int? monoArmedAt,
}) {
  final delay = lockoutDelayMs(fails);
  if (delay == 0) return 0;
  final monoRemaining = monoDeadline - monoNow;
  final monoBelongsToThisBoot =
      (monoArmedAt == null || monoNow >= monoArmedAt) && monoRemaining <= delay;
  final wallRemaining = wallDeadline - wallNow;
  final wallTrusted = wallRemaining <= delay;
  final mono = monoBelongsToThisBoot ? monoRemaining : 0;
  final wall = wallTrusted ? wallRemaining : 0;
  final remaining = mono > wall ? mono : wall;
  return remaining > 0 ? remaining : 0;
}
