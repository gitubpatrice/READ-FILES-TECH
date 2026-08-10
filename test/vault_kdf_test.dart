import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/vault/vault_kdf.dart';

/// `calibrateArgon2Params` n'était couvert par aucun test avant le
/// 2026-08-10 : privé et noyé dans `vault_service.dart`, il était atteignable
/// seulement en montant un coffre complet.
///
/// C'est pourtant lui qui décide des paramètres de sécurité du coffre **à
/// vie** : ils sont écrits au setup et jamais recalculés. Une sous-évaluation
/// affaiblit le coffre en silence — aucun message, aucun symptôme, et un
/// attaquant qui en profite ne se manifeste pas.
void main() {
  group('calibrage Argon2id', () {
    test('une mesure absurde donne le repli médian, jamais le plancher', () {
      // Le repli est médian et non minimal, et c'est délibéré : face à une
      // mesure en laquelle on n'a pas confiance, on préfère un coffre un peu
      // lent à ouvrir qu'un coffre faible.
      for (final absurde in [0, -1, -10000]) {
        final p = calibrateArgon2Params(absurde);
        expect(p, kArgon2FallbackParams, reason: 'benchMs = $absurde');
        expect(
          p.memoryKB,
          greaterThan(kArgon2MinMemoryKB),
          reason: 'le repli doit être PLUS fort que le plancher',
        );
      }
    });

    test('un appareil déjà lent reste au plancher', () {
      for (final lent in [kArgon2TargetMs, kArgon2TargetMs + 1, 60000]) {
        final p = calibrateArgon2Params(lent);
        expect(p.memoryKB, kArgon2MinMemoryKB);
        expect(p.iterations, kArgon2MinIterations);
      }
    });

    test('un appareil rapide monte, sans jamais dépasser les bornes', () {
      for (final rapide in [1, 2, 5, 10, 50, 200, 1000, 2499]) {
        final p = calibrateArgon2Params(rapide);
        expect(
          p.memoryKB,
          inInclusiveRange(kArgon2MinMemoryKB, kArgon2MaxMemoryKB),
          reason: 'benchMs = $rapide',
        );
        expect(
          p.iterations,
          inInclusiveRange(kArgon2MinIterations, kArgon2MaxIterations),
          reason: 'benchMs = $rapide',
        );
      }
    });

    test('plus l\'appareil est rapide, plus les paramètres sont forts', () {
      // La monotonie est la propriété qui compte : si elle se cassait, un
      // téléphone puissant pourrait recevoir des paramètres plus faibles
      // qu'un téléphone lent, et rien ne le signalerait.
      var precedent = 0;
      for (final ms in [2400, 2000, 1500, 1000, 500, 200, 100, 50, 10, 1]) {
        final p = calibrateArgon2Params(ms);
        final travail = p.memoryKB * p.iterations;
        expect(
          travail,
          greaterThanOrEqualTo(precedent),
          reason: 'à $ms ms le travail retombe à $travail',
        );
        precedent = travail;
      }
    });

    test('la mémoire est toujours un multiple du mégaoctet', () {
      for (var ms = 1; ms <= 2500; ms += 7) {
        expect(
          calibrateArgon2Params(ms).memoryKB % 1024,
          0,
          reason: 'benchMs = $ms',
        );
      }
    });

    test('aucune entrée ne produit de paramètres sous le plancher', () {
      // Balayage large : c'est le seul moyen d'exclure un trou d'arrondi.
      for (var ms = -100; ms <= 5000; ms++) {
        final p = calibrateArgon2Params(ms);
        expect(p.memoryKB, greaterThanOrEqualTo(kArgon2MinMemoryKB));
        expect(p.iterations, greaterThanOrEqualTo(kArgon2MinIterations));
      }
    });
  });

  group('dérivation', () {
    test('Argon2id est déterministe et rend 32 octets', () {
      final salt = Uint8List.fromList(List.generate(kVaultSaltLen, (i) => i));
      final a = deriveArgon2id('motdepasse', salt, kArgon2MinMemoryKB, 2);
      final b = deriveArgon2id('motdepasse', salt, kArgon2MinMemoryKB, 2);
      expect(a, b);
      expect(a.length, kVaultKeyLen);
    });

    test('un sel différent donne une clé différente', () {
      final s1 = Uint8List(kVaultSaltLen);
      final s2 = Uint8List(kVaultSaltLen)..[0] = 1;
      expect(
        deriveArgon2id('x', s1, kArgon2MinMemoryKB, 2),
        isNot(deriveArgon2id('x', s2, kArgon2MinMemoryKB, 2)),
      );
    });

    test('des paramètres différents donnent une clé différente', () {
      // Ce n'est pas cosmétique : cela signifie qu'un coffre ne s'ouvre qu'avec
      // les paramètres exacts avec lesquels il a été créé, et donc qu'ils
      // doivent être stockés — pas redevinés.
      final salt = Uint8List(kVaultSaltLen);
      expect(
        deriveArgon2id('x', salt, kArgon2MinMemoryKB, 2),
        isNot(deriveArgon2id('x', salt, kArgon2MinMemoryKB, 3)),
      );
    });

    test('PBKDF2 legacy reste déterministe et rend 32 octets', () {
      final salt = Uint8List.fromList(List.generate(kVaultSaltLen, (i) => i));
      final a = derivePbkdf2('motdepasse', salt);
      expect(a, derivePbkdf2('motdepasse', salt));
      expect(a.length, kVaultKeyLen);
    });

    test('les paramètres de sauvegarde ne dépendent pas de l\'appareil', () {
      // Une sauvegarde se restaure sur un AUTRE téléphone. Si ces constantes
      // devenaient calibrées, le fichier deviendrait illisible ailleurs.
      expect(kArgon2ExportMemoryKB, 16384);
      expect(kArgon2ExportIterations, 4);
    });
  });
}
