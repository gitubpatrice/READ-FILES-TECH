/// Dérivation de clé du coffre : Argon2id (courant) et PBKDF2 (legacy), plus
/// l'auto-calibrage des paramètres Argon2id sur l'appareil.
///
/// **Pourquoi ce fichier existe.** Tout ceci vivait dans `vault_service.dart`,
/// au milieu de la gestion de fichiers, du verrouillage et du format de
/// sauvegarde. Les fonctions étaient déjà `static` et pures — donc déjà
/// testables — mais privées, donc **non testées**. En particulier
/// [calibrateArgon2Params], qui n'est que de l'arithmétique et qui décide des
/// paramètres de sécurité du coffre **à vie** : ceux calculés au setup ne sont
/// jamais recalculés.
///
/// Un calibrage trop faible affaiblit le coffre en silence, sans qu'aucun
/// message ni aucun test ne le dise. C'est la raison d'être de ce fichier.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:pointycastle/export.dart';

/// Longueur de la clé dérivée, en octets (AES-256).
const kVaultKeyLen = 32;

/// Longueur du sel, en octets.
const kVaultSaltLen = 16;

/// Itérations PBKDF2 des coffres antérieurs à v2.6.0.
const kPbkdf2Iterations = 600000;

/// Paramètres du bench : volontairement minimums, pour mesurer vite.
const kArgon2BenchMemoryKB = 4096;
const kArgon2BenchIterations = 2;

/// Durée visée pour une dérivation sur l'appareil, en millisecondes.
const kArgon2TargetMs = 2500;

/// Bornes de l'auto-calibrage. Le plafond mémoire tient compte des appareils
/// d'entrée de gamme (Redmi 9C, 3 Go) : au-delà, la dérivation risque l'OOM.
const kArgon2MinMemoryKB = 8192;
const kArgon2MaxMemoryKB = 32768;
const kArgon2MinIterations = 2;
const kArgon2MaxIterations = 4;

/// Paramètres des coffres v2.6.0 → v2.7.0, avant l'auto-calibrage.
const kArgon2LegacyMemoryKB = 16384;
const kArgon2LegacyIterations = 4;

/// Paramètres des sauvegardes `.rftvault`, fixes et indépendants de l'appareil.
///
/// Une sauvegarde se restaure sur un **autre** téléphone que celui qui l'a
/// produite : des paramètres calibrés sur l'appareil source rendraient le
/// fichier illisible ailleurs, ou trivialement lent à attaquer.
const kArgon2ExportMemoryKB = 16384;
const kArgon2ExportIterations = 4;

/// Parallélisme Argon2id. Fixé à 1 : le gain d'un parallélisme supérieur est
/// nul sur mobile, et le faire varier changerait la clé dérivée.
const kArgon2Lanes = 1;

/// Repli utilisé quand la mesure est absurde ou que tous les benchs échouent.
///
/// Médian volontairement, pas minimum : en cas de doute on préfère un coffre
/// un peu lent à ouvrir qu'un coffre faible.
const kArgon2FallbackParams = (memoryKB: 12288, iterations: 2);

/// Dérive la clé par PBKDF2-HMAC-SHA256. Coffres antérieurs à v2.6.0.
///
/// Top-level et pure, donc utilisable depuis `Isolate.run`.
///
/// Le `String password` reste en mémoire — les chaînes Dart sont immuables et
/// on ne peut pas les effacer. Seule la copie UTF-8, qu'on contrôle, est
/// remise à zéro.
Uint8List derivePbkdf2(String password, Uint8List salt) {
  // `utf8.encode` rend DEJA un `Uint8List`, neuf et mutable a chaque appel
  // (verifie le 2026-08-10). L'envelopper dans `Uint8List.fromList` allouait
  // donc une SECONDE copie du mot de passe en clair, et seule celle-la etait
  // remise a zero : la premiere restait en memoire jusqu'au passage du
  // ramasse-miettes, a un moment imprevisible. Defaut preexistant, signale par
  // la relecture Gemini du 2026-08-10 et confirme a l'execution.
  final pwBytes = utf8.encode(password);
  try {
    final pbkdf2 = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))
      ..init(Pbkdf2Parameters(salt, kPbkdf2Iterations, kVaultKeyLen));
    return pbkdf2.process(pwBytes);
  } finally {
    for (var i = 0; i < pwBytes.length; i++) {
      pwBytes[i] = 0;
    }
  }
}

/// Dérive la clé par Argon2id. Coffres v2.6.0 et suivants.
///
/// Memory-hard : résiste bien mieux que PBKDF2 au cassage sur GPU.
///
/// Même limite que [derivePbkdf2] sur le mot de passe. `Isolate.run` en
/// duplique en outre une copie à l'envoi du message, hors de notre contrôle.
Uint8List deriveArgon2id(
  String password,
  Uint8List salt,
  int memoryKB,
  int iterations,
) {
  // `utf8.encode` rend DEJA un `Uint8List`, neuf et mutable a chaque appel
  // (verifie le 2026-08-10). L'envelopper dans `Uint8List.fromList` allouait
  // donc une SECONDE copie du mot de passe en clair, et seule celle-la etait
  // remise a zero : la premiere restait en memoire jusqu'au passage du
  // ramasse-miettes, a un moment imprevisible. Defaut preexistant, signale par
  // la relecture Gemini du 2026-08-10 et confirme a l'execution.
  final pwBytes = utf8.encode(password);
  try {
    final argon2 = Argon2BytesGenerator()
      ..init(
        Argon2Parameters(
          Argon2Parameters.ARGON2_id,
          salt,
          desiredKeyLength: kVaultKeyLen,
          iterations: iterations,
          memory: memoryKB,
          lanes: kArgon2Lanes,
          version: Argon2Parameters.ARGON2_VERSION_13,
        ),
      );
    final out = Uint8List(kVaultKeyLen);
    argon2.deriveKey(pwBytes, 0, out, 0);
    return out;
  } finally {
    for (var i = 0; i < pwBytes.length; i++) {
      pwBytes[i] = 0;
    }
  }
}

/// Mesure le temps d'une dérivation Argon2id aux paramètres de bench, en ms.
///
/// Top-level et pure, donc passable à `Isolate.run`.
int benchArgon2id() {
  final chrono = Stopwatch()..start();
  final argon2 = Argon2BytesGenerator()
    ..init(
      Argon2Parameters(
        Argon2Parameters.ARGON2_id,
        Uint8List(kVaultSaltLen),
        desiredKeyLength: kVaultKeyLen,
        iterations: kArgon2BenchIterations,
        memory: kArgon2BenchMemoryKB,
        lanes: kArgon2Lanes,
        version: Argon2Parameters.ARGON2_VERSION_13,
      ),
    );
  argon2.deriveKey(Uint8List(32), 0, Uint8List(kVaultKeyLen), 0);
  chrono.stop();
  return chrono.elapsedMilliseconds;
}

/// Déduit les paramètres Argon2id visant [kArgon2TargetMs] sur cet appareil, à
/// partir d'un temps [benchMs] mesuré aux paramètres de bench.
///
/// **Arithmétique pure, et décision de sécurité définitive.** Les paramètres
/// rendus ici sont écrits au setup du coffre et **ne sont jamais recalculés** :
/// une sous-évaluation affaiblit le coffre pour toute sa vie, en silence. D'où
/// des bornes strictes, un repli médian plutôt que minimal, et des tests.
({int memoryKB, int iterations}) calibrateArgon2Params(int benchMs) {
  // Mesure absurde (horloge nulle ou négative) : on ne déduit rien d'une
  // valeur en laquelle on n'a pas confiance.
  if (benchMs <= 0) return kArgon2FallbackParams;

  // L'appareil est déjà au-delà de la cible aux paramètres minimums : rien à
  // gagner à monter, on reste au plancher.
  if (benchMs >= kArgon2TargetMs) {
    return (memoryKB: kArgon2MinMemoryKB, iterations: kArgon2MinIterations);
  }

  // Argon2id coûte à peu près linéairement en (mémoire × itérations).
  const benchWork = kArgon2BenchMemoryKB * kArgon2BenchIterations;
  final targetWork = (benchWork * (kArgon2TargetMs / benchMs)).round();

  int mem;
  int iter;
  if (targetWork <= kArgon2MaxMemoryKB * kArgon2MinIterations) {
    // La cible tient en montant la mémoire seule : on garde le minimum
    // d'itérations, moins coûteux en temps perçu.
    mem = (targetWork / kArgon2MinIterations).round();
    iter = kArgon2MinIterations;
  } else {
    // Mémoire au plafond : le reste passe par les itérations.
    mem = kArgon2MaxMemoryKB;
    iter = (targetWork / kArgon2MaxMemoryKB).round();
  }

  // Arrondi au mégaoctet, pour la lisibilité de la valeur stockée.
  mem = ((mem / 1024).round() * 1024).clamp(
    kArgon2MinMemoryKB,
    kArgon2MaxMemoryKB,
  );
  iter = iter.clamp(kArgon2MinIterations, kArgon2MaxIterations);
  return (memoryKB: mem, iterations: iter);
}

/// Nombre de mesures successives avant de retenir la meilleure.
///
/// On garde le **minimum**, c'est-à-dire le cas le plus favorable, CPU non
/// bridé. Un calibrage fait téléphone chaud ou sous charge fixerait sinon des
/// paramètres trop faibles — et ils ne sont jamais recalculés.
const kArgon2BenchSamples = 3;
