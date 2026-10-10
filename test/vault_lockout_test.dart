import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/vault/vault_lockout.dart';

/// D2 — reproduit le 2026-10-10 sur émulateur avec la 2.15.2 : après un redémarrage, le BON mot
/// de passe restait refusé de l'ordre de l'ancien temps de fonctionnement (« Réessayez dans
/// 88 s », 141 s après l'échéance d'un blocage d'une minute ; ~13 jours sur un S9 allumé depuis
/// 13 jours).
void main() {
  const minute = 60 * 1000;
  const day = 24 * 60 * minute;

  test('le délai suit le nombre d\'échecs et plafonne à 30 min', () {
    expect([0, 1, 4].map(lockoutDelayMs), everyElement(0));
    expect(lockoutDelayMs(5), 1 * minute);
    expect(lockoutDelayMs(6), 2 * minute);
    expect(lockoutDelayMs(9), 16 * minute);
    expect(lockoutDelayMs(10), 30 * minute);
    expect(lockoutDelayMs(500), 30 * minute);
  });

  test('dans le même démarrage, le blocage court puis expire', () {
    int at(int t) => lockoutRemainingMs(
      fails: 5,
      wallNow: 1000000 + t,
      monoNow: 5 * day + t,
      wallDeadline: 1000000 + minute,
      monoDeadline: 5 * day + minute,
      monoArmedAt: 5 * day,
    );
    expect(at(0), minute);
    expect(at(30000), 30000);
    expect(at(minute), 0);
    expect(at(2 * minute), 0);
  });

  test('avancer l\'horloge murale ne lève pas le blocage (F3 v2.13.0)', () {
    expect(
      lockoutRemainingMs(
        fails: 5,
        wallNow: 1000000 + 10 * day, // date avancée de dix jours
        monoNow: 5 * day + 1000,
        wallDeadline: 1000000 + minute,
        monoDeadline: 5 * day + minute,
        monoArmedAt: 5 * day,
      ),
      minute - 1000,
    );
  });

  test(
    'après un redémarrage, l\'échéance monotone de l\'ancien démarrage est ignorée',
    () {
      // Armé à 5 jours d'uptime ; redémarré, uptime 30 s ; l'échéance murale est passée.
      expect(
        lockoutRemainingMs(
          fails: 5,
          wallNow: 1000000 + 3 * minute,
          monoNow: 30000,
          wallDeadline: 1000000 + minute,
          monoDeadline: 5 * day + minute,
          monoArmedAt: 5 * day,
        ),
        0,
      );
      // Échéance murale pas encore passée : c'est elle qui décide, pas l'uptime d'avant.
      expect(
        lockoutRemainingMs(
          fails: 5,
          wallNow: 1000000 + 20000,
          monoNow: 30000,
          wallDeadline: 1000000 + minute,
          monoDeadline: 5 * day + minute,
          monoArmedAt: 5 * day,
        ),
        40000,
      );
    },
  );

  test(
    'une échéance héritée de la 2.15.2, sans instant d\'armement, ne bloque pas des jours',
    () {
      int at(int wallNow) => lockoutRemainingMs(
        fails: 5,
        wallNow: wallNow,
        monoNow: 30000,
        wallDeadline: 1000000 + minute,
        monoDeadline:
            13 * day +
            minute, // posée à 13 jours d'uptime, avant le redémarrage
      );
      expect(at(1000000 + 20000), 40000);
      expect(
        at(1000000 + minute),
        0,
        reason: 'libre dès l\'échéance murale, pas dans 13 jours',
      );
    },
  );

  test('une horloge murale reculée ne rallonge pas le blocage', () {
    expect(
      lockoutRemainingMs(
        fails: 5,
        wallNow:
            1000000 - 2 * day, // date reculée de deux jours après l'armement
        monoNow: 5 * day + minute + 1,
        wallDeadline: 1000000 + minute,
        monoDeadline: 5 * day + minute,
        monoArmedAt: 5 * day,
      ),
      0,
      reason:
          'la monotone, valide dans ce démarrage, dit que le délai est écoulé',
    );
  });

  test(
    'redémarrer PUIS reculer l\'heure ne libère pas le coffre (échec fermé)',
    () {
      // Relecture Claude du 2026-10-10 : la monotone d'un autre démarrage écartée, l'horloge
      // murale reculée était écartée à son tour, et le blocage tombait à zéro.
      expect(
        lockoutRemainingMs(
          fails: 5,
          wallNow: 1000000 - 2 * day,
          monoNow: 30000,
          wallDeadline: 1000000 + minute,
          monoDeadline: 5 * day + minute,
          monoArmedAt: 5 * day,
        ),
        2 * day + minute,
      );
    },
  );

  test('aucun échec, aucun blocage, même avec des échéances résiduelles', () {
    expect(
      lockoutRemainingMs(
        fails: 0,
        wallNow: 0,
        monoNow: 0,
        wallDeadline: day,
        monoDeadline: day,
      ),
      0,
    );
  });
}
