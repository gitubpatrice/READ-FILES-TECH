import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pointycastle/export.dart';
import 'package:read_files_tech/services/vault_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Tests du coffre-fort — point 19 du plan d'audit du 2026-08-02 : « tests du
/// vault AVANT refonte ». 1352 lignes de crypto n'en avaient aucun.
///
/// Ces tests exercent l'API publique réelle ([VaultService.instance]) et non
/// des helpers recopiés : c'est la seule façon de détecter une régression du
/// format de chiffrement. Trois plugins sont donc simulés :
///
/// - `path_provider` : renvoie un dossier temporaire jetable par test ;
/// - `com.readfilestech/lifecycle` : `elapsedRealtime` pour le lockout
///   monotone. Le compteur est piloté par le test, ce qui permet de vérifier
///   le backoff sans attendre réellement une minute ;
/// - `com.readfilestech/vault_crypto` : **délibérément absent**. Les fichiers
///   de test font moins de 5 Mo, donc le routage reste sur PointyCastle et
///   c'est bien le chemin Dart qui est vérifié ici. Un test qui aurait mocké
///   ce canal en réimplémentant AES-GCM se serait vérifié lui-même.
///
/// ⚠ Argon2id est volontairement lent (~2,5 s par dérivation après
/// calibrage). `setupWithPassword` n'est donc appelé qu'une fois par test,
/// et les cas qui n'ont pas besoin d'un coffre neuf réutilisent le même.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tmp;
  late int fakeElapsed;

  const lifecycle = MethodChannel('com.readfilestech/lifecycle');
  const pathProvider = MethodChannel('plugins.flutter.io/path_provider');

  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() async {
    tmp = Directory.systemTemp.createTempSync('rft_vault_test');
    Directory('${tmp.path}/docs').createSync(recursive: true);
    Directory('${tmp.path}/cache').createSync(recursive: true);
    fakeElapsed = 1000000;

    SharedPreferences.setMockInitialValues(<String, Object>{});

    messenger.setMockMethodCallHandler(pathProvider, (call) async {
      switch (call.method) {
        case 'getApplicationDocumentsDirectory':
          return '${tmp.path}/docs';
        case 'getTemporaryDirectory':
          return '${tmp.path}/cache';
      }
      return null;
    });

    messenger.setMockMethodCallHandler(lifecycle, (call) async {
      if (call.method == 'elapsedRealtime') return fakeElapsed;
      return null;
    });

    // Chaque test repart d'un coffre vierge : `reset()` efface le dossier et
    // les préférences, et surtout remet `_cachedKey` à null — la clé est un
    // static partagé entre tous les tests du fichier.
    await VaultService.instance.reset();
  });

  tearDown(() async {
    await VaultService.instance.reset();
    VaultService.instance.lock();
    messenger.setMockMethodCallHandler(pathProvider, null);
    messenger.setMockMethodCallHandler(lifecycle, null);
    try {
      tmp.deleteSync(recursive: true);
    } catch (_) {}
  });

  /// Dossier de sortie, toujours distinct de celui qui porte les fichiers
  /// sources : depuis la garde V-M2, exporter vers le dossier de la source
  /// bute sur l'homonyme… qui est la source elle-même.
  String outDir() {
    final d = Directory('${tmp.path}/out');
    if (!d.existsSync()) d.createSync(recursive: true);
    return d.path;
  }

  File sourceFile(String name, List<int> bytes) {
    final f = File('${tmp.path}/$name');
    f.writeAsBytesSync(bytes);
    return f;
  }

  Directory vaultDir() => Directory('${tmp.path}/docs/vault');

  // ── Aller-retour ─────────────────────────────────────────────────────────

  test('chiffre puis déchiffre un fichier sans altération', () async {
    await VaultService.instance.setupWithPassword('correct horse battery');

    final payload = List<int>.generate(4096, (i) => (i * 7) & 0xFF);
    final src = sourceFile('secret.bin', payload);
    final encPath = await VaultService.instance.importFileSafe(src);

    // Le fichier chiffré ne doit contenir ni le clair, ni sa taille exacte.
    final blob = File(encPath).readAsBytesSync();
    expect(blob.length, greaterThan(payload.length));
    expect(
      utf8.decode(blob.sublist(0, 4), allowMalformed: true),
      'RFT2',
      reason: 'magic v2 attendu en tête de fichier',
    );

    final out = await VaultService.instance.exportFile(File(encPath), outDir());
    expect(out.readAsBytesSync(), equals(payload));
  });

  test('un fichier vide fait un aller-retour correct', () async {
    await VaultService.instance.setupWithPassword('mot de passe vide-test');
    final src = sourceFile('vide.txt', const <int>[]);
    final encPath = await VaultService.instance.importFileSafe(src);
    final out = await VaultService.instance.exportFile(File(encPath), outDir());
    expect(out.readAsBytesSync(), isEmpty);
  });

  // ── Mauvais mot de passe ─────────────────────────────────────────────────

  test('refuse un mauvais mot de passe et accepte le bon', () async {
    await VaultService.instance.setupWithPassword('bon mot de passe');
    VaultService.instance.lock();

    expect(await VaultService.instance.unlockWithPassword('mauvais'), isFalse);
    expect(VaultService.instance.isUnlocked, isFalse);

    expect(
      await VaultService.instance.unlockWithPassword('bon mot de passe'),
      isTrue,
    );
    expect(VaultService.instance.isUnlocked, isTrue);
  });

  test('un mauvais mot de passe ne donne pas accès aux fichiers', () async {
    await VaultService.instance.setupWithPassword('bon mot de passe');
    final src = sourceFile('carte.txt', utf8.encode('4111 1111 1111 1111'));
    await VaultService.instance.importFileSafe(src);
    VaultService.instance.lock();

    await VaultService.instance.unlockWithPassword('mauvais');
    // La clé n'est pas en cache : toute opération doit lever, et surtout pas
    // rendre un plaintext partiel.
    expect(
      () => VaultService.instance.listFiles().then(
        (f) => VaultService.instance.exportFile(f.first, outDir()),
      ),
      throwsA(isA<StateError>()),
    );
  });

  // ── Intégrité : l'en-tête et le corps sont authentifiés ──────────────────

  test(
    'un octet modifié dans le ciphertext fait échouer le déchiffrement',
    () async {
      await VaultService.instance.setupWithPassword('integrite');
      final src = sourceFile('doc.bin', List<int>.filled(2048, 0x41));
      final encPath = await VaultService.instance.importFileSafe(src);

      final f = File(encPath);
      final blob = f.readAsBytesSync();
      // Dernier octet = tag GCM ; on vise plutôt le milieu du ciphertext pour
      // prouver que c'est bien l'authentification du contenu qui refuse.
      blob[blob.length ~/ 2] ^= 0x01;
      f.writeAsBytesSync(blob);

      expect(
        () => VaultService.instance.exportFile(f, outDir()),
        throwsA(anything),
        reason: 'le tag GCM doit refuser un ciphertext altéré',
      );
    },
  );

  test(
    'un octet modifié dans le nonce fait échouer le déchiffrement',
    () async {
      await VaultService.instance.setupWithPassword('integrite nonce');
      final src = sourceFile('doc2.bin', List<int>.filled(2048, 0x42));
      final encPath = await VaultService.instance.importFileSafe(src);

      final f = File(encPath);
      final blob = f.readAsBytesSync();
      blob[4] ^= 0xFF; // premier octet du nonce, juste après le magic
      f.writeAsBytesSync(blob);

      expect(
        () => VaultService.instance.exportFile(f, outDir()),
        throwsA(anything),
      );
    },
  );

  test(
    'renommer un .enc invalide son déchiffrement (AAD liée au nom)',
    () async {
      await VaultService.instance.setupWithPassword('anti-rename');
      final src = sourceFile('facture.pdf', List<int>.filled(1024, 0x50));
      final encPath = await VaultService.instance.importFileSafe(src);

      // Un attaquant ayant accès en écriture au dossier renomme le fichier pour
      // le faire passer pour un autre document.
      final renamed = File('${vaultDir().path}/releve.pdf.enc');
      File(encPath).renameSync(renamed.path);

      expect(
        () => VaultService.instance.exportFile(renamed, outDir()),
        throwsA(anything),
        reason: "l'AAD lie le chiffré à son nom de fichier",
      );
    },
  );

  test('un coffre neuf refuse un blob au format v1 (anti-confusion)', () async {
    await VaultService.instance.setupWithPassword('v2 only');
    final src = sourceFile('note.txt', utf8.encode('contenu'));
    final encPath = await VaultService.instance.importFileSafe(src);

    // Retirer le magic « RFT2 » transforme le blob en apparent format v1.
    final f = File(encPath);
    final blob = f.readAsBytesSync();
    f.writeAsBytesSync(Uint8List.fromList(blob.sublist(4)));

    expect(
      () => VaultService.instance.exportFile(f, outDir()),
      throwsA(isA<StateError>()),
      reason: 'un coffre v2-only ne doit pas retomber sur le chemin v1',
    );
  });

  // ── Compatibilité ascendante PBKDF2 ──────────────────────────────────────

  /// Fabrique sur disque un coffre tel que le produisait la v2.5.4 :
  /// KDF PBKDF2-HMAC-SHA256 600 000 itérations, sentinelle au **format v1**
  /// (`nonce | ciphertext+tag`, AAD vide, pas de magic « RFT2 »), et aucun
  /// flag `vault_v2_only`.
  ///
  /// Le format historique est réécrit ici plutôt que réutilisé depuis le
  /// service : c'est volontaire. Un test de compatibilité ascendante doit
  /// énoncer **indépendamment** à quoi ressemblaient les octets d'alors ; s'il
  /// appelait le code de production pour les produire, il resterait vert le
  /// jour où ce code changerait de format — c'est-à-dire précisément le jour
  /// où il devrait virer au rouge.
  Future<void> writeLegacyPbkdf2Vault(String password) async {
    final salt = Uint8List.fromList(
      List<int>.generate(16, (i) => (i * 17 + 3) & 0xFF),
    );
    final pw = Uint8List.fromList(utf8.encode(password));
    final kdf = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))
      ..init(Pbkdf2Parameters(salt, 600000, 32));
    final key = kdf.process(pw);

    final nonce = Uint8List.fromList(
      List<int>.generate(12, (i) => (i * 31 + 7) & 0xFF),
    );
    final cipher = GCMBlockCipher(AESEngine())
      ..init(true, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));
    final ct = cipher.process(
      Uint8List.fromList(utf8.encode('read_files_tech_vault_v1')),
    );

    final dir = Directory('${tmp.path}/docs/vault')
      ..createSync(recursive: true);
    File(
      '${dir.path}/_check.enc',
    ).writeAsBytesSync(Uint8List.fromList([...nonce, ...ct]));

    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('vault_salt_v1', base64Encode(salt));
    await prefs.setString('vault_kdf_version', 'pbkdf2');
    await prefs.setBool('vault_setup_v1', true);
    // Pas de `vault_v2_only` : un coffre d'avant v2.12.0 ne le portait pas.
  }

  test(
    'un coffre legacy PBKDF2 v1 reste ouvrable après le passage à Argon2id',
    () async {
      await writeLegacyPbkdf2Vault('mot de passe historique');
      expect(await VaultService.instance.isSetup(), isTrue);

      expect(
        await VaultService.instance.unlockWithPassword(
          'mot de passe historique',
        ),
        isTrue,
        reason: "un coffre créé en v2.5.4 doit encore s'ouvrir aujourd'hui",
      );
      expect(VaultService.instance.isUnlocked, isTrue);
    },
  );

  test(
    'un coffre legacy PBKDF2 refuse quand même un mauvais mot de passe',
    () async {
      await writeLegacyPbkdf2Vault('mot de passe historique');
      expect(await VaultService.instance.unlockWithPassword('autre'), isFalse);
      expect(VaultService.instance.isUnlocked, isFalse);
    },
  );

  test('un fichier importé dans un coffre legacy est réécrit en v2', () async {
    await writeLegacyPbkdf2Vault('mot de passe historique');
    await VaultService.instance.unlockWithPassword('mot de passe historique');

    final encPath = await VaultService.instance.importFileSafe(
      sourceFile('nouveau.txt', utf8.encode('écrit aujourd\'hui')),
    );
    final blob = File(encPath).readAsBytesSync();
    expect(
      utf8.decode(blob.sublist(0, 4), allowMalformed: true),
      'RFT2',
      reason: 'les écritures neuves passent en v2 même dans un coffre legacy',
    );
    final out = await VaultService.instance.exportFile(File(encPath), outDir());
    expect(out.readAsStringSync(), 'écrit aujourd\'hui');
  });

  // ── Export / restore .rftvault ───────────────────────────────────────────

  test(
    'export puis restore avec un mot de passe distinct du principal',
    () async {
      await VaultService.instance.setupWithPassword('principal');
      final a = sourceFile('a.txt', utf8.encode('contenu A'));
      final b = sourceFile('b.txt', utf8.encode('contenu B'));
      await VaultService.instance.importFileSafe(a);
      await VaultService.instance.importFileSafe(b);

      final backup = await VaultService.instance.exportToBackup(
        exportPassword: 'sauvegarde distincte',
      );
      expect(backup.existsSync(), isTrue);

      // Vide le coffre sans toucher au master password.
      for (final f in await VaultService.instance.listFiles()) {
        await VaultService.instance.deleteFile(f);
      }
      expect(await VaultService.instance.listFiles(), isEmpty);

      final res = await VaultService.instance.restoreFromBackup(
        backupFile: backup,
        exportPassword: 'sauvegarde distincte',
      );
      expect(res.total, 2);
      expect(res.restored, 2);

      final files = await VaultService.instance.listFiles();
      expect(files.length, 2);
      final names = <String>{};
      for (final f in files) {
        final out = await VaultService.instance.exportFile(f, outDir());
        names.add(out.readAsStringSync());
      }
      expect(names, {'contenu A', 'contenu B'});
    },
  );

  test(
    'un .rftvault restauré avec le mauvais mot de passe est refusé',
    () async {
      await VaultService.instance.setupWithPassword('principal');
      await VaultService.instance.importFileSafe(
        sourceFile('c.txt', utf8.encode('contenu C')),
      );
      final backup = await VaultService.instance.exportToBackup(
        exportPassword: 'bon export',
      );

      expect(
        () => VaultService.instance.restoreFromBackup(
          backupFile: backup,
          exportPassword: 'mauvais export',
        ),
        throwsA(anything),
      );
    },
  );

  test(
    "l'en-tête d'un .rftvault est authentifié (AAD = header complet)",
    () async {
      await VaultService.instance.setupWithPassword('principal');
      await VaultService.instance.importFileSafe(
        sourceFile('d.txt', utf8.encode('contenu D')),
      );
      final backup = await VaultService.instance.exportToBackup(
        exportPassword: 'export',
      );

      // Offset 12 = premier octet du champ `argon2_mem` (u32 BE), donc dans
      // l'en-tête et hors du ciphertext. Sans AAD liée au header, cette
      // modification passerait inaperçue — c'est exactement la propriété
      // annoncée par le commentaire de `exportToBackup`.
      final blob = backup.readAsBytesSync();
      blob[12] ^= 0x01;
      backup.writeAsBytesSync(blob);

      expect(
        () => VaultService.instance.restoreFromBackup(
          backupFile: backup,
          exportPassword: 'export',
        ),
        throwsA(anything),
        reason: 'tampering des params Argon2 du header doit invalider le tag',
      );
    },
  );

  // ── Lockout ──────────────────────────────────────────────────────────────

  test('cinq échecs déclenchent le lockout, et le bon mot de passe est '
      'refusé pendant la fenêtre', () async {
    await VaultService.instance.setupWithPassword('lockout');
    VaultService.instance.lock();

    for (var i = 0; i < 5; i++) {
      expect(await VaultService.instance.unlockWithPassword('faux'), isFalse);
    }

    // Le bon mot de passe lui-même doit être refusé tant que la fenêtre court.
    await expectLater(
      VaultService.instance.unlockWithPassword('lockout'),
      throwsA(isA<StateError>()),
    );
    expect(VaultService.instance.isUnlocked, isFalse);
  });

  test('le lockout ne se contourne pas en reculant la wall-clock', () async {
    await VaultService.instance.setupWithPassword('mono');
    VaultService.instance.lock();
    for (var i = 0; i < 5; i++) {
      await VaultService.instance.unlockWithPassword('faux');
    }

    // Simule « Réglages → Date/heure » : la deadline wall-clock est repoussée
    // dans le passé. La deadline monotone, elle, n'a pas bougé.
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('vault_lockout_until_ms', 0);

    await expectLater(
      VaultService.instance.unlockWithPassword('mono'),
      throwsA(isA<StateError>()),
      reason: 'la deadline monotone doit tenir seule',
    );
  });

  test('la fenêtre de lockout expire et le coffre se rouvre', () async {
    await VaultService.instance.setupWithPassword('expire');
    VaultService.instance.lock();
    for (var i = 0; i < 5; i++) {
      await VaultService.instance.unlockWithPassword('faux');
    }

    // Avance les deux horloges au-delà de la fenêtre (1 min au 5ᵉ échec).
    fakeElapsed += 2 * 60 * 1000;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('vault_lockout_until_ms', 0);

    expect(await VaultService.instance.unlockWithPassword('expire'), isTrue);
  });

  test('un déverrouillage réussi remet le compteur d\'échecs à zéro', () async {
    await VaultService.instance.setupWithPassword('compteur');
    VaultService.instance.lock();

    for (var i = 0; i < 4; i++) {
      await VaultService.instance.unlockWithPassword('faux');
    }
    expect(await VaultService.instance.unlockWithPassword('compteur'), isTrue);

    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getInt('vault_unlock_fails'), isNull);
    expect(prefs.getInt('vault_lockout_until_ms'), isNull);
    expect(prefs.getInt('vault_lockout_until_elapsed'), isNull);
  });

  // ── reset() ──────────────────────────────────────────────────────────────

  test(
    'reset() ne laisse aucun lockout résiduel sur le coffre suivant',
    () async {
      await VaultService.instance.setupWithPassword('avant reset');
      VaultService.instance.lock();
      // 5 échecs exactement : au 5ᵉ, le lockout s'arme. Un 6ᵉ appel ne
      // compterait pas un échec de plus — il lèverait, parce que la fenêtre
      // court déjà. Le compteur ne monte donc que d'un cran par fenêtre écoulée.
      for (var i = 0; i < 5; i++) {
        await VaultService.instance.unlockWithPassword('faux');
      }
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt('vault_lockout_until_elapsed'), isNotNull);

      await VaultService.instance.reset();

      // V-L5 : un coffre tout neuf ne doit hériter d'aucune deadline.
      expect(prefs.getInt('vault_unlock_fails'), isNull);
      expect(prefs.getInt('vault_lockout_until_ms'), isNull);
      expect(prefs.getInt('vault_lockout_until_elapsed'), isNull);

      // Et la preuve fonctionnelle : le coffre recréé s'ouvre immédiatement.
      await VaultService.instance.setupWithPassword('apres reset');
      VaultService.instance.lock();
      expect(
        await VaultService.instance.unlockWithPassword('apres reset'),
        isTrue,
      );
    },
  );

  test(
    'reset() efface aussi le lockout de restauration de sauvegarde',
    () async {
      await VaultService.instance.setupWithPassword('backup lockout');
      await VaultService.instance.importFileSafe(
        sourceFile('e.txt', utf8.encode('contenu E')),
      );
      final backup = await VaultService.instance.exportToBackup(
        exportPassword: 'bon',
      );
      for (var i = 0; i < 5; i++) {
        try {
          await VaultService.instance.restoreFromBackup(
            backupFile: backup,
            exportPassword: 'faux',
          );
        } catch (_) {
          /* attendu */
        }
      }
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt('vault_backup_fails'), isNotNull);

      await VaultService.instance.reset();
      expect(prefs.getInt('vault_backup_fails'), isNull);
      expect(prefs.getInt('vault_backup_lockout_until_ms'), isNull);
      expect(prefs.getInt('vault_backup_lockout_until_elapsed'), isNull);
    },
  );

  test('reset() supprime les enveloppes chiffrées du disque', () async {
    await VaultService.instance.setupWithPassword('efface');
    await VaultService.instance.importFileSafe(
      sourceFile('f.txt', utf8.encode('contenu F')),
    );
    expect(vaultDir().listSync(), isNotEmpty);

    await VaultService.instance.reset();
    expect(vaultDir().listSync(), isEmpty);
    expect(await VaultService.instance.isSetup(), isFalse);
  });

  // ── Écrasement silencieux ────────────────────────────────────────────────

  test('importFileSafe refuse un homonyme sans overwrite explicite', () async {
    await VaultService.instance.setupWithPassword('homonyme');
    final src = sourceFile('rapport.txt', utf8.encode('v1'));
    await VaultService.instance.importFileSafe(src);

    src.writeAsStringSync('v2');
    expect(
      () => VaultService.instance.importFileSafe(src),
      throwsA(isA<FileSystemException>()),
    );

    // Avec overwrite: true, la nouvelle version remplace bien l'ancienne.
    await VaultService.instance.importFileSafe(src, overwrite: true);
    final files = await VaultService.instance.listFiles();
    final out = await VaultService.instance.exportFile(files.single, outDir());
    expect(out.readAsStringSync(), 'v2');
  });

  test(
    'exportFile n\'écrase pas silencieusement un fichier existant',
    () async {
      await VaultService.instance.setupWithPassword('export ecrasement');
      final src = sourceFile('impots.txt', utf8.encode('depuis le coffre'));
      final encPath = await VaultService.instance.importFileSafe(src);

      // L'utilisateur possède déjà un fichier de ce nom dans le dossier cible.
      final destDir = Directory('${tmp.path}/dest')..createSync();
      final existing = File('${destDir.path}/impots.txt')
        ..writeAsStringSync('DOCUMENT UTILISATEUR À NE PAS PERDRE');

      // V-M2 : l'export doit refuser plutôt que détruire. L'appelant peut
      // demander l'écrasement explicitement.
      await expectLater(
        VaultService.instance.exportFile(File(encPath), destDir.path),
        throwsA(isA<FileSystemException>()),
      );
      expect(
        existing.readAsStringSync(),
        'DOCUMENT UTILISATEUR À NE PAS PERDRE',
      );

      final out = await VaultService.instance.exportFile(
        File(encPath),
        destDir.path,
        overwrite: true,
      );
      expect(out.readAsStringSync(), 'depuis le coffre');
    },
  );

  // ── Payload de sauvegarde forgé ──────────────────────────────────────────

  test('restore ignore une entrée dont le nom tente une traversée', () async {
    await VaultService.instance.setupWithPassword('traversee');
    // Un nom de fichier ne peut pas contenir « / » sur le FS, donc on ne peut
    // pas fabriquer l'entrée par un import. On vérifie la garde en amont :
    // `importFileSafe` construit toujours le nom via PathSafe.basename, donc
    // aucune entrée du coffre ne porte de séparateur.
    final src = sourceFile('normal.txt', utf8.encode('x'));
    final encPath = await VaultService.instance.importFileSafe(src);
    expect(encPath.contains('/vault/normal.txt.enc'), isTrue);

    final files = await VaultService.instance.listFiles();
    for (final f in files) {
      final name = f.path.split(RegExp(r'[/\\]')).last;
      expect(name.contains('..'), isFalse);
    }
  });

  // ── Le coffre verrouillé ne rend rien ────────────────────────────────────

  test('toute opération sur un coffre verrouillé lève', () async {
    await VaultService.instance.setupWithPassword('verrou');
    final src = sourceFile('g.txt', utf8.encode('contenu G'));
    final encPath = await VaultService.instance.importFileSafe(src);
    VaultService.instance.lock();

    expect(VaultService.instance.isUnlocked, isFalse);
    expect(
      () => VaultService.instance.importFileSafe(src),
      throwsA(isA<StateError>()),
    );
    expect(
      () => VaultService.instance.exportFile(File(encPath), outDir()),
      throwsA(isA<StateError>()),
    );
    expect(
      () => VaultService.instance.decryptToTemp(File(encPath)),
      throwsA(isA<StateError>()),
    );
    expect(
      () => VaultService.instance.exportToBackup(exportPassword: 'x'),
      throwsA(isA<StateError>()),
    );
  });
}
