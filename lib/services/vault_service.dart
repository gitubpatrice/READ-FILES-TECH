import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:pointycastle/export.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Coffre fort local : fichiers chiffrés AES-256-GCM.
///
/// **Format v2 (depuis v2.5.5)** :
///   `magic(4) | nonce(12) | ciphertext+tag` avec **AAD = "rft-vault-v2|" + filename**
///   `magic = 0x52465432 (ASCII "RFT2")`
///
/// **Format v1 (≤ v2.5.4)** : `nonce(12) | ciphertext+tag`, AAD = vide. Lu en
/// fallback (rétrocompatibilité), ré-écrit en v2 au prochain import.
///
/// L'AAD lié au filename empêche un attaquant ayant accès en écriture au dossier
/// vault de renommer un .enc pour le faire passer pour un autre fichier.
///
/// Master password : PBKDF2-HMAC-SHA256, 600 000 itérations, salt 16 o.
/// Vérification password : sentinelle `_check.enc`.
///
/// **Anti brute-force** : compteur d'échecs persistant + back-off exponentiel
/// au-delà de 5 essais (1, 2, 4, 8, 16 minutes).
class VaultService {
  /// Singleton : la clé maître est cachée en static `_cachedKey`. Toute
  /// instanciation multiple partageait déjà cet état — on rend la chose
  /// explicite pour éviter qu'un futur appelant n'oublie le pattern.
  VaultService._();
  static final VaultService instance = VaultService._();

  static const _kSalt = 'vault_salt_v1';
  static const _kSetup = 'vault_setup_v1';
  static const _kFails = 'vault_unlock_fails';
  static const _kLockoutUntil = 'vault_lockout_until_ms';

  /// KDF utilisé pour dériver la clé maître depuis le password.
  /// - Absent (null) ou `'pbkdf2'` : PBKDF2-HMAC-SHA256 600 000 itérations
  ///   (legacy, coffres créés avant v2.6.0)
  /// - `'argon2id'` : Argon2id m=16 Mo, t=4, p=1 (depuis v2.6.0,
  ///   GPU-résistant grâce à la memory-hardness)
  ///
  /// Les coffres existants restent en PBKDF2 (pas de migration automatique
  /// — ce serait nécessaire de re-chiffrer tous les fichiers). Les nouveaux
  /// coffres utilisent Argon2id par défaut.
  static const _kKdfVersion = 'vault_kdf_version';
  static const _kdfPbkdf2 = 'pbkdf2';
  static const _kdfArgon2id = 'argon2id';

  /// Params Argon2id auto-calibrés stockés au setup (depuis v2.7.1).
  /// Permet à chaque device d'utiliser son maximum CPU sans freezer.
  /// Coffres v2.6.0–v2.7.0 (sans ces clés) : fallback sur params legacy.
  static const _kArgon2MemoryKB = 'vault_argon2_mem_kb';
  static const _kArgon2Iterations = 'vault_argon2_iter';

  static const _iterations = 600000; // PBKDF2 (legacy)

  // ── Argon2id : auto-tuning ─────────────────────────────────────────────────
  // Cible : ~2.5s par dérivation, quel que soit le device.
  // Algo : 1 bench à params min, calcul du facteur d'échelle, choix de
  // params dans des bornes [4Mo..32Mo] × [2..4] itérations.

  static const _argon2BenchMemoryKB = 4096; // 4 MB pour le bench
  static const _argon2BenchIterations = 2;
  static const _argon2TargetMs = 2500;
  // Plancher absolu 8 Mo (pas 4) : protège contre un setup faussé par
  // thermal-throttle qui fixerait des params trop faibles à vie. 8 Mo
  // reste tenable même sur Redmi 9C 3GB.
  static const _argon2MinMemoryKB = 8192; // 8 MB
  static const _argon2MaxMemoryKB = 32768; // 32 MB (cap haut Redmi 9C 3GB)
  static const _argon2MinIterations = 2;
  static const _argon2MaxIterations = 4;
  // Nombre d'échantillons du bench. On garde le minimum (= meilleur cas
  // CPU non-throttled) pour éviter un calibrage trop bas si le device
  // est sous load au moment du setup.
  static const _argon2BenchSamples = 3;

  // Legacy params pour coffres v2.6.0–v2.7.0 (sans calibrage stocké) :
  // m=16 Mo, t=4. Lus en fallback à l'unlock pour rétro-compat.
  static const _argon2LegacyMemoryKB = 16384; // 16 MB
  static const _argon2LegacyIterations = 4;

  // Params utilisés pour l'export `.rftvault` (fixes, indépendants du
  // calibrage du coffre — le fichier peut être restauré sur n'importe
  // quel device, donc on cible la sécurité plutôt que la vitesse).
  static const _argon2ExportMemoryKB = 16384; // 16 MB
  static const _argon2ExportIterations = 4;

  static const _argon2Lanes = 1;

  static const _saltLen = 16;
  static const _nonceLen = 12;
  static const _keyLen = 32;
  static const _checkFile = '_check.enc';
  static const _checkPlain = 'read_files_tech_vault_v1';

  /// Magic bytes du format v2 : "RFT2".
  static const _magicV2 = [0x52, 0x46, 0x54, 0x32];
  static const _aadPrefix = 'rft-vault-v2|';

  // v2.11.1 — utilise PerfThresholds.isolateThreshold (files_tech_core).
  // 1 Mo = ~30 ms PointyCastle GCM sur S9 → acceptable. Au-dessus, isolate.

  /// Seuil au-delà duquel on bascule sur le crypto NATIF Kotlin (10-50× plus
  /// rapide grâce à l'accélération matérielle ARMv8). 5 Mo = ~150 ms en
  /// PointyCastle Dart sur S9 vs ~5 ms native — le coût de la copie bytes
  /// Dart→native est amorti au-delà de cette taille.
  static const _nativeThreshold = 5 * 1024 * 1024;

  /// Channel Kotlin exposant `encrypt` / `decrypt` AES-256-GCM natif.
  /// Voir MainActivity.kt section vault_crypto.
  static const _nativeChannel = MethodChannel('com.readfilestech/vault_crypto');

  static Uint8List? _cachedKey;

  Future<Directory> _vaultDir() async {
    final docs = await getApplicationDocumentsDirectory();
    final vault = Directory('${docs.path}/vault');
    if (!await vault.exists()) await vault.create(recursive: true);
    return vault;
  }

  Future<bool> isSetup() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_kSetup) ?? false;
  }

  /// Crée le coffre avec un master password (à appeler une seule fois).
  /// Utilise Argon2id avec params auto-calibrés pour ce device (depuis
  /// v2.7.1) ou legacy (m=16Mo t=4) sur les coffres pré-v2.7.1.
  ///
  /// Le calibrage tourne en Isolate (~1-3s) avant la dérivation effective
  /// (~2.5s ciblés). Cible UX globale ~3-5s à la création.
  Future<void> setupWithPassword(String password) async {
    final salt = _randomBytes(_saltLen);
    // Auto-tuning : mesure le device puis choisit des params Argon2id
    // optimaux dans les bornes [4-32 Mo] × [2-4 iter] pour cibler ~2.5s
    // par dérivation, quel que soit le hardware.
    final calibrated = await _calibrateArgon2();
    final memKB = calibrated.memoryKB;
    final iter = calibrated.iterations;
    final key = await Isolate.run(
      () => _deriveKeyArgon2id(password, salt, memKB, iter),
    );
    final prefs = await SharedPreferences.getInstance();

    // ── Ordre atomicité-friendly ───────────────────────────────────────────
    // Si l'app est killée à n'importe quelle étape, l'état reste cohérent :
    // - Crash avant étape 4 (sentinelle) → _kSetup pas écrit → coffre vu
    //   comme non-setup → retombe sur l'écran Setup
    // - Crash après sentinelle mais avant _kSetup → idem (Setup s'affiche),
    //   un futur setup écrasera la sentinelle
    // 1. Tous les params (salt, KDF version, mem, iter) ENSEMBLE
    await prefs.setString(_kSalt, base64Encode(salt));
    await prefs.setString(_kKdfVersion, _kdfArgon2id);
    await prefs.setInt(_kArgon2MemoryKB, memKB);
    await prefs.setInt(_kArgon2Iterations, iter);
    await prefs.remove(_kFails);
    await prefs.remove(_kLockoutUntil);
    // 2. Sentinelle disque (preuve crypto)
    final dir = await _vaultDir();
    final encrypted = _encryptV2(utf8.encode(_checkPlain), key, _checkFile);
    await File('${dir.path}/$_checkFile').writeAsBytes(encrypted);
    // 3. Flag final SEULEMENT si tout précède a réussi
    await prefs.setBool(_kSetup, true);
    _cachedKey = key;
  }

  /// Tente le déverrouillage. Retourne true si réussi.
  /// Lève [StateError] si le coffre est temporairement verrouillé après trop
  /// d'échecs ; le message contient le nombre de secondes restantes.
  Future<bool> unlockWithPassword(String password) async {
    final prefs = await SharedPreferences.getInstance();
    final lockoutUntil = prefs.getInt(_kLockoutUntil) ?? 0;
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now < lockoutUntil) {
      final remaining = ((lockoutUntil - now) / 1000).ceil();
      throw StateError('Trop d\'essais. Réessayez dans $remaining s.');
    }
    final saltB64 = prefs.getString(_kSalt);
    if (saltB64 == null) {
      // Salt absent alors que `_kSetup=true` (vérifié par l'appelant via
      // [isSetup]) → coffre incohérent. On lève plutôt que retourner false
      // (qui afficherait "mauvais mot de passe", trompeur).
      throw StateError(
        'Coffre corrompu : salt introuvable. '
        'Réinitialisez via Réglages.',
      );
    }
    final salt = base64Decode(saltB64);
    // Sélectionne le KDF selon la version stockée :
    // - 'argon2id' (v2.6.0+) → Argon2id avec params en prefs (v2.7.1+
    //   auto-calibrés) ou legacy m=16Mo t=4 (coffres v2.6.0–v2.7.0).
    // - 'pbkdf2' ou absent (legacy) → PBKDF2 600k.
    final kdf = prefs.getString(_kKdfVersion) ?? _kdfPbkdf2;
    final Uint8List key;
    if (kdf == _kdfArgon2id) {
      final memKB = prefs.getInt(_kArgon2MemoryKB) ?? _argon2LegacyMemoryKB;
      final iter = prefs.getInt(_kArgon2Iterations) ?? _argon2LegacyIterations;
      key = await Isolate.run(
        () => _deriveKeyArgon2id(password, salt, memKB, iter),
      );
    } else {
      key = await Isolate.run(() => _deriveKey(password, salt));
    }
    final dir = await _vaultDir();
    final check = File('${dir.path}/$_checkFile');
    if (!await check.exists()) {
      // Sentinelle absente alors que les params KDF sont présents → coffre
      // incohérent (suppression manuelle du fichier ?). Lever plutôt que
      // retourner false pour ne pas afficher "mauvais mot de passe".
      _zeroize(key);
      throw StateError(
        'Coffre corrompu : sentinelle introuvable. '
        'Réinitialisez via Réglages.',
      );
    }
    try {
      final blob = await check.readAsBytes();
      final plain = _decryptAuto(blob, key, _checkFile);
      if (utf8.decode(plain) == _checkPlain) {
        // Zeroize l'ancienne clé cache (cas double unlock) avant remplacement.
        final old = _cachedKey;
        if (old != null) _zeroize(old);
        _cachedKey = key;
        await prefs.remove(_kFails);
        await prefs.remove(_kLockoutUntil);
        return true;
      }
      // Sentinelle décodable mais contenu inattendu → coffre corrompu,
      // pas un mauvais password. On ne lockout pas l'utilisateur.
      _zeroize(key);
      throw StateError(
        'Sentinelle invalide — coffre corrompu, '
        'réinitialisez via Réglages.',
      );
    } on InvalidCipherTextException {
      // Bad tag GCM = vrai mauvais password → incrément compteur ci-dessous.
    } on PlatformException catch (e) {
      // DECRYPT_ERROR du native channel = bad tag équivalent.
      // Tout autre code = erreur système, on rethrow sans pénaliser.
      if (e.code != 'DECRYPT_ERROR') {
        _zeroize(key);
        rethrow;
      }
    } on StateError {
      // Propagé par le bloc plus haut (sentinelle invalide) ou par
      // _decryptAuto (bloc invalide / format inattendu) — pas un mauvais
      // password, pas de lockout.
      _zeroize(key);
      rethrow;
    } catch (e) {
      // I/O, OOM, ou autre erreur système — pas un mauvais password.
      _zeroize(key);
      rethrow;
    }
    // Échec : incrémente compteur + applique backoff exponentiel.
    final fails = (prefs.getInt(_kFails) ?? 0) + 1;
    await prefs.setInt(_kFails, fails);
    if (fails >= 5) {
      // Backoff : 1, 2, 4, 8, 16, 30 min (cappé). Sec : tentative GPU offline
      // reste possible mais ralentie.
      final minutes = [1, 2, 4, 8, 16, 30][((fails - 5).clamp(0, 5)).toInt()];
      await prefs.setInt(_kLockoutUntil, now + minutes * 60 * 1000);
    }
    // Zeroize la clé dérivée (mauvaise) avant retour.
    _zeroize(key);
    return false;
  }

  /// Indique si le coffre est actuellement déverrouillé (clé en cache).
  bool get isUnlocked => _cachedKey != null;

  void lock() {
    final k = _cachedKey;
    if (k != null) _zeroize(k);
    _cachedKey = null;
    // Best-effort : nettoyer les fichiers déchiffrés laissés en tmp
    // (vault_decrypt/ + cache/share/ utilisé par share_plus pour les
    // fichiers issus du coffre).
    purgeTempDecrypted();
  }

  /// Importe un fichier en clair → chiffre + stocke. Retourne le path chiffré.
  /// Si un fichier homonyme existe déjà, lance une `FileSystemException` —
  /// l'appelant peut alors confirmer l'écrasement et passer `overwrite: true`.
  Future<String> importFileSafe(File source, {bool overwrite = false}) async {
    final key = _requireKey();
    final dir = await _vaultDir();
    final name = PathSafe.basename(source.path);
    final destName = '$name.enc';
    final dest = File('${dir.path}/$destName');
    if (await dest.exists() && !overwrite) {
      throw FileSystemException(
        'Fichier homonyme déjà dans le coffre',
        dest.path,
      );
    }
    final plain = await source.readAsBytes();
    final ct = await _encryptMaybeIsolate(plain, key, destName);
    await dest.writeAsBytes(ct);
    return dest.path;
  }

  /// Liste les fichiers chiffrés dans le coffre (sans le sentinelle).
  Future<List<File>> listFiles() async {
    final dir = await _vaultDir();
    final entries = await dir.list().toList();
    return entries
        .whereType<File>()
        .where((f) => !f.path.endsWith(_checkFile))
        .toList();
  }

  /// Importe un fichier en clair → chiffre + stocke. Retourne le path chiffré.
  Future<String> importFile(File source) async {
    final key = _requireKey();
    final dir = await _vaultDir();
    final name = PathSafe.basename(source.path);
    final destName = '$name.enc';
    final dest = File('${dir.path}/$destName');
    final plain = await source.readAsBytes();
    final ct = await _encryptMaybeIsolate(plain, key, destName);
    await dest.writeAsBytes(ct);
    return dest.path;
  }

  /// Déchiffre un fichier du coffre vers un emplacement temporaire (pour viewer/share).
  Future<File> decryptToTemp(File encrypted) async {
    final key = _requireKey();
    final tmpRoot = await getTemporaryDirectory();
    final tmp = Directory('${tmpRoot.path}/vault_decrypt');
    if (!await tmp.exists()) await tmp.create(recursive: true);
    final encName = PathSafe.basename(encrypted.path);
    final originalName = _stripEnc(encName);
    final out = File('${tmp.path}/$originalName');
    final blob = await encrypted.readAsBytes();
    final plain = await _decryptMaybeIsolate(blob, key, encName);
    await out.writeAsBytes(plain);
    return out;
  }

  /// Supprime tous les fichiers déchiffrés laissés dans le cache :
  /// - `cache/vault_decrypt/` : staging déchiffrement viewer/share
  /// - `cache/share/` : staging utilisé par share_plus / FileProvider qui
  ///   peut conserver des copies en clair après un Share depuis le coffre.
  Future<void> purgeTempDecrypted() async {
    try {
      final tmpRoot = await getTemporaryDirectory();
      final tmp = Directory('${tmpRoot.path}/vault_decrypt');
      if (await tmp.exists()) await tmp.delete(recursive: true);
    } catch (e, st) {
      if (kDebugMode) debugPrint('purgeTempDecrypted vault_decrypt: $e\n$st');
    }
    // Purge defensive du dossier share/ (créé par share_plus). On ne supprime
    // que ce sous-dossier, pas tout cache/, pour ne pas casser d'autres
    // caches d'app (thumbnails, plugins).
    try {
      final tmpRoot = await getTemporaryDirectory();
      final share = Directory('${tmpRoot.path}/share');
      if (await share.exists()) await share.delete(recursive: true);
    } catch (e, st) {
      if (kDebugMode) debugPrint('purgeTempDecrypted share/: $e\n$st');
    }
  }

  /// Exporte (déchiffre) un fichier du coffre vers un dossier de destination.
  Future<File> exportFile(File encrypted, String destDir) async {
    final key = _requireKey();
    final encName = PathSafe.basename(encrypted.path);
    final originalName = _stripEnc(encName);
    final out = File('$destDir/$originalName');
    final blob = await encrypted.readAsBytes();
    final plain = await _decryptMaybeIsolate(blob, key, encName);
    await out.writeAsBytes(plain);
    return out;
  }

  Future<void> deleteFile(File encrypted) async {
    if (await encrypted.exists()) await encrypted.delete();
  }

  // ── Export / Restore (.rftvault backup) ────────────────────────────────────

  /// Magic header du format de sauvegarde `.rftvault`.
  static const _backupMagic = [0x52, 0x46, 0x54, 0x56, 0x41, 0x55, 0x4C, 0x54];
  // "RFTVAULT"
  /// Version courante. v1 (legacy) ne stockait pas les params Argon2 et n'AAD-bindait
  /// pas le header. v2 corrige ces deux points :
  /// - Params Argon2id (memKB u32 BE + iter u32 BE) après reserved
  /// - AAD = bytes complets du header (magic + version + reserved + params + salt + nonce)
  ///   → tampering du header détecté par le tag GCM
  static const _backupVersionV1 = 1;
  static const _backupVersionV2 = 2;
  static const _backupVersion = _backupVersionV2;

  /// AAD legacy v1 (constante, ne lie rien au header réel). Conservé en lecture.
  static const _backupAadV1 = 'rftvault-v1';

  /// Limite hard pour éviter OOM sur les low-end (Redmi 9C 3GB) lors de
  /// l'export ou restore qui matérialise tout le payload en RAM avant
  /// chiffrement enveloppe.
  static const _backupMaxBytes = 200 * 1024 * 1024; // 200 Mo

  /// Exporte tout le coffre dans un fichier `.rftvault` chiffré avec
  /// [exportPassword] (Argon2id + AES-GCM, distinct du master password).
  ///
  /// Le fichier produit peut être restauré sur un autre device via
  /// [restoreFromBackup]. Le master password actuel n'est pas requis pour
  /// le restore — résilience max en cas de perte du téléphone.
  ///
  /// Limité à 200 Mo total (sinon [StateError]). [onProgress] est rapporté
  /// dans `[0.0, 1.0]` pour brancher une progress bar.
  Future<File> exportToBackup({
    required String exportPassword,
    void Function(double)? onProgress,
  }) async {
    final masterKey = _requireKey();
    final files = await listFiles();

    // Garde-fou taille.
    int total = 0;
    for (final f in files) {
      total += await f.length();
    }
    if (total > _backupMaxBytes) {
      throw StateError(
        'Export limité à ${_backupMaxBytes ~/ (1024 * 1024)} Mo. '
        'Coffre actuel : ${total ~/ (1024 * 1024)} Mo.',
      );
    }

    onProgress?.call(0.0);

    // Liste des plaintext alloués pour zeroize garanti en finally.
    final plains = <Uint8List>[];
    Uint8List? payload;
    Uint8List? exportKey;
    try {
      // Construction du payload en clair (déchiffre chaque fichier avec master).
      final builder = BytesBuilder();
      builder.add(_int32be(files.length));
      for (var i = 0; i < files.length; i++) {
        final f = files[i];
        final encName = PathSafe.basename(f.path);
        final plainName = _stripEnc(encName);
        final blob = await f.readAsBytes();
        final plain = await _decryptMaybeIsolate(blob, masterKey, encName);
        plains.add(plain);
        final nameBytes = utf8.encode(plainName);
        if (nameBytes.length > 0xFFFF) {
          throw StateError('Nom de fichier trop long pour l\'export');
        }
        builder.add(_int16be(nameBytes.length));
        builder.add(nameBytes);
        builder.add(_int32be(plain.length));
        builder.add(plain);
        onProgress?.call((i + 1) / files.length * 0.6);
      }
      // Force ownership pour pouvoir zeroize (toBytes() peut renvoyer une vue).
      final payloadLocal = Uint8List.fromList(builder.toBytes());
      payload = payloadLocal;

      // Dérivation Argon2id depuis exportPassword (distinct du master).
      // Params FIXES pour l'export (m=16Mo, t=4) — le fichier .rftvault
      // peut être restauré sur n'importe quel device, on cible donc la
      // sécurité maximale plutôt que la vitesse. Cohérent avec les
      // anciens coffres v2.6.0–v2.7.0.
      final salt = _randomBytes(_saltLen);
      final exportKeyLocal = await Isolate.run(
        () => _deriveKeyArgon2id(
          exportPassword,
          salt,
          _argon2ExportMemoryKB,
          _argon2ExportIterations,
        ),
      );
      exportKey = exportKeyLocal;
      onProgress?.call(0.85);

      // Chiffrement enveloppe AES-GCM. v2 : AAD = bytes complets du header
      // (magic|version|reserved|argon2_mem|argon2_iter|salt|nonce). Tout
      // tampering du header (incl. params Argon2 ou sel) est détecté par
      // le tag GCM lors du decrypt.
      final nonce = _randomBytes(_nonceLen);
      final headerForAad =
          (BytesBuilder()
                ..add(_backupMagic)
                ..addByte(_backupVersion)
                ..add(const [0, 0, 0]) // reserved
                ..add(_int32be(_argon2ExportMemoryKB))
                ..add(_int32be(_argon2ExportIterations))
                ..add(salt)
                ..add(nonce))
              .toBytes();
      final aad = Uint8List.fromList(headerForAad);
      final ct = await _encryptRaw(payloadLocal, exportKeyLocal, nonce, aad);

      // Construction du fichier final = header (déjà construit pour AAD) + ct.
      final out = BytesBuilder()
        ..add(headerForAad)
        ..add(ct);

      final tmpDir = await getTemporaryDirectory();
      final ts = DateTime.now()
          .toIso8601String()
          .substring(0, 19)
          .replaceAll(':', '-');
      final outFile = File('${tmpDir.path}/coffre_$ts.rftvault');
      await outFile.writeAsBytes(out.toBytes());
      onProgress?.call(1.0);
      return outFile;
    } finally {
      // Zeroize tous les buffers en clair, même si exception.
      for (final p in plains) {
        _zeroize(p);
      }
      if (payload != null) _zeroize(payload);
      if (exportKey != null) _zeroize(exportKey);
    }
  }

  /// Restaure les fichiers d'un `.rftvault` dans le coffre actuel
  /// (qui doit être déverrouillé). Retourne le nombre de fichiers
  /// effectivement restaurés.
  ///
  /// [overwriteExisting] : si false (défaut), les fichiers déjà présents
  /// sont ignorés. Si true, ils sont écrasés.
  ///
  /// Lève [StateError] si le format est invalide, [PlatformException]
  /// (code DECRYPT_ERROR) si le password est mauvais ou le fichier altéré
  /// (vérification AEAD GCM).
  Future<RestoreResult> restoreFromBackup({
    required File backupFile,
    required String exportPassword,
    bool overwriteExisting = false,
    void Function(double)? onProgress,
  }) async {
    final masterKey = _requireKey();

    // Cap taille AVANT readAsBytes — un .rftvault malformé/forgé de plusieurs
    // Go ferait OOM crash sur low-end (Redmi 9C 3GB). 1.2× pour overhead
    // enveloppe (header + tag + padding interne).
    final fileSize = await backupFile.length();
    if (fileSize > _backupMaxBytes * 1.2) {
      throw StateError(
        'Fichier trop volumineux (max ${_backupMaxBytes ~/ (1024 * 1024)} Mo).',
      );
    }

    final raw = await backupFile.readAsBytes();
    if (raw.length < 8 + 1 + 16) {
      throw StateError('Fichier .rftvault invalide (taille).');
    }

    // Validation magic.
    for (var i = 0; i < _backupMagic.length; i++) {
      if (raw[i] != _backupMagic[i]) {
        throw StateError('Fichier .rftvault invalide (signature).');
      }
    }

    final version = raw[8];
    final int memKB;
    final int iter;
    final int headerLen;
    final int saltOffset;
    final Uint8List aad;

    if (version == _backupVersionV1) {
      // v1 (legacy) : pas de params en header → constantes export historiques.
      // AAD = constante "rftvault-v1" (ne lie rien au header — accepté pour
      // rétro-compat des sauvegardes existantes).
      memKB = _argon2ExportMemoryKB;
      iter = _argon2ExportIterations;
      headerLen = 8 + 1 + 3 + _saltLen + _nonceLen;
      saltOffset = 12;
      aad = Uint8List.fromList(utf8.encode(_backupAadV1));
    } else if (version == _backupVersionV2) {
      // v2 : params Argon2id dans le header + AAD = bytes complets du header.
      // Tampering du magic, version, params, sel ou nonce → tag GCM invalide.
      headerLen = 8 + 1 + 3 + 4 + 4 + _saltLen + _nonceLen;
      if (raw.length < headerLen + 16) {
        throw StateError('Fichier .rftvault invalide (taille).');
      }
      memKB = _readU32be(raw, 12);
      iter = _readU32be(raw, 16);
      // Garde-fous : refuse params absurdes (forgé) pour éviter OOM/CPU DoS
      // côté restore. Min 1 Mo / 1 iter, max 1 Go / 64 iter.
      if (memKB < 1024 || memKB > 1024 * 1024 || iter < 1 || iter > 64) {
        throw StateError('Fichier .rftvault invalide (params Argon2).');
      }
      saltOffset = 20;
      aad = Uint8List.fromList(raw.sublist(0, headerLen));
    } else {
      throw StateError('Version $version non supportée.');
    }

    final salt = Uint8List.sublistView(raw, saltOffset, saltOffset + _saltLen);
    final nonce = Uint8List.sublistView(
      raw,
      saltOffset + _saltLen,
      saltOffset + _saltLen + _nonceLen,
    );
    final ct = Uint8List.sublistView(raw, headerLen);

    onProgress?.call(0.05);

    // Dérivation Argon2id avec params lus dans header (v2) ou constantes (v1).
    final saltCopy = Uint8List.fromList(salt);
    final exportKey = await Isolate.run(
      () => _deriveKeyArgon2id(exportPassword, saltCopy, memKB, iter),
    );
    onProgress?.call(0.4);

    // Décryptage enveloppe — lève si bad password / tampering (GCM tag).
    Uint8List payload;
    try {
      payload = await _decryptRaw(
        Uint8List.fromList(ct),
        exportKey,
        Uint8List.fromList(nonce),
        aad,
      );
    } finally {
      _zeroize(exportKey);
    }
    onProgress?.call(0.55);

    // Parse + ré-importation dans le coffre actuel (re-chiffré sous master key).
    // Zeroize garanti du payload + des plaintexts individuels en finally.
    List<_BackupEntry>? entries;
    try {
      entries = _parseBackupPayload(payload);
      final dir = await _vaultDir();
      int restored = 0;
      int skipped = 0;
      int failed = 0;
      for (var i = 0; i < entries.length; i++) {
        final e = entries[i];
        final destName = '${e.name}.enc';
        final dest = File('${dir.path}/$destName');
        if (await dest.exists() && !overwriteExisting) {
          skipped++;
          continue;
        }
        // Try/catch par entrée : si une écriture échoue (FAT32 nom invalide,
        // disque plein, perm refusée), on continue avec les autres au lieu
        // de planter tout le restore au milieu.
        try {
          final encrypted = await _encryptMaybeIsolate(
            e.plain,
            masterKey,
            destName,
          );
          await dest.writeAsBytes(encrypted);
          restored++;
        } catch (_) {
          failed++;
        }
        onProgress?.call(0.55 + (i + 1) / entries.length * 0.45);
      }
      onProgress?.call(1.0);
      return RestoreResult(
        total: entries.length,
        restored: restored,
        skipped: skipped + failed,
      );
    } finally {
      // _parseBackupPayload alloue une COPIE pour chaque e.plain (via
      // Uint8List.fromList) — ce ne sont PAS des vues de payload. Donc
      // zeroizer les deux est nécessaire (pas redondant).
      if (entries != null) {
        for (final e in entries) {
          _zeroize(e.plain);
        }
      }
      _zeroize(payload);
    }
  }

  /// Parse défensif du payload après décryptage de l'enveloppe.
  /// Refuse les noms de fichiers invalides (path traversal protection
  /// via [PathSafe.basename]).
  List<_BackupEntry> _parseBackupPayload(Uint8List payload) {
    final out = <_BackupEntry>[];
    int p = 0;
    if (p + 4 > payload.length) {
      throw StateError('Payload tronqué (en-tête).');
    }
    final n = _readInt32be(payload, p);
    p += 4;
    if (n < 0 || n > 100000) {
      throw StateError('Nombre de fichiers invalide.');
    }
    for (var i = 0; i < n; i++) {
      if (p + 2 > payload.length) {
        throw StateError('Payload tronqué (nameLen #$i).');
      }
      final nameLen = _readInt16be(payload, p);
      p += 2;
      if (p + nameLen > payload.length) {
        throw StateError('Payload tronqué (name #$i).');
      }
      final name = utf8.decode(Uint8List.sublistView(payload, p, p + nameLen));
      p += nameLen;
      if (p + 4 > payload.length) {
        throw StateError('Payload tronqué (dataLen #$i).');
      }
      final dataLen = _readInt32be(payload, p);
      p += 4;
      if (dataLen < 0 || p + dataLen > payload.length) {
        throw StateError('Payload tronqué (data #$i).');
      }
      final plain = Uint8List.fromList(
        Uint8List.sublistView(payload, p, p + dataLen),
      );
      p += dataLen;
      // Anti path-traversal STRICT : on garde uniquement les noms qui
      // restent IDENTIQUES après basename(). Si PathSafe modifie ou rejette,
      // l'entrée est ignorée silencieusement (anti DoS via filename forgé).
      final String safeName;
      try {
        safeName = PathSafe.basename(name);
      } catch (e) {
        if (kDebugMode) {
          debugPrint('vault: parseBackupPayload basename failed: $e');
        }
        continue;
      }
      if (safeName != name) continue;
      // Caractères réservés Windows + NUL byte (defense in depth pour SD
      // FAT32 et compat cross-FS).
      if (RegExp(r'[\x00-\x1f<>:"|?*]').hasMatch(safeName)) continue;
      out.add(_BackupEntry(safeName, plain));
    }
    return out;
  }

  /// Encrypt AES-GCM "raw" (sans magic header) — utilisé pour l'enveloppe
  /// `.rftvault`. Routing identique à [_encryptMaybeIsolate] (native >5Mo).
  Future<Uint8List> _encryptRaw(
    Uint8List plain,
    Uint8List key,
    Uint8List nonce,
    Uint8List aad,
  ) async {
    if (plain.length >= _nativeThreshold) {
      try {
        final result = await _nativeChannel.invokeMethod<Uint8List>('encrypt', {
          'key': key,
          'nonce': nonce,
          'aad': aad,
          'plain': plain,
        });
        if (result != null) return result;
      } on MissingPluginException {
        /* fallback */
      } on PlatformException catch (e) {
        if (_isCryptoErrorCode(e.code)) rethrow;
      }
    }
    if (plain.length < PerfThresholds.isolateThreshold) {
      return _gcmRaw(true, plain, key, nonce, aad);
    }
    return Isolate.run(() => _gcmRaw(true, plain, key, nonce, aad));
  }

  /// Decrypt AES-GCM "raw" (sans magic header) — propage [PlatformException]
  /// de code DECRYPT_ERROR si tampering / bad key (signal d'intégrité).
  Future<Uint8List> _decryptRaw(
    Uint8List blob,
    Uint8List key,
    Uint8List nonce,
    Uint8List aad,
  ) async {
    if (blob.length >= _nativeThreshold) {
      try {
        final result = await _nativeChannel.invokeMethod<Uint8List>('decrypt', {
          'key': key,
          'nonce': nonce,
          'aad': aad,
          'blob': blob,
        });
        if (result != null) return result;
      } on MissingPluginException {
        /* fallback */
      } on PlatformException catch (e) {
        if (_isCryptoErrorCode(e.code)) rethrow;
      }
    }
    if (blob.length < PerfThresholds.isolateThreshold) {
      return _gcmRaw(false, blob, key, nonce, aad);
    }
    return Isolate.run(() => _gcmRaw(false, blob, key, nonce, aad));
  }

  /// PointyCastle GCM brut, statique pour `Isolate.run`.
  static Uint8List _gcmRaw(
    bool forEncryption,
    Uint8List input,
    Uint8List key,
    Uint8List nonce,
    Uint8List aad,
  ) {
    final cipher = GCMBlockCipher(AESEngine())
      ..init(forEncryption, AEADParameters(KeyParameter(key), 128, nonce, aad));
    return cipher.process(input);
  }

  // Helpers big-endian.
  static int _readU32be(Uint8List b, int off) {
    return (b[off] << 24) | (b[off + 1] << 16) | (b[off + 2] << 8) | b[off + 3];
  }

  static Uint8List _int32be(int v) {
    final out = Uint8List(4);
    out[0] = (v >> 24) & 0xff;
    out[1] = (v >> 16) & 0xff;
    out[2] = (v >> 8) & 0xff;
    out[3] = v & 0xff;
    return out;
  }

  static Uint8List _int16be(int v) {
    final out = Uint8List(2);
    out[0] = (v >> 8) & 0xff;
    out[1] = v & 0xff;
    return out;
  }

  static int _readInt32be(Uint8List b, int o) =>
      (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3];
  static int _readInt16be(Uint8List b, int o) => (b[o] << 8) | b[o + 1];

  /// Réinitialise complètement le coffre.
  Future<void> reset() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kSalt);
    await prefs.remove(_kSetup);
    await prefs.remove(_kKdfVersion);
    await prefs.remove(_kArgon2MemoryKB);
    await prefs.remove(_kArgon2Iterations);
    await prefs.remove(_kFails);
    await prefs.remove(_kLockoutUntil);
    final dir = await _vaultDir();
    if (await dir.exists()) {
      await dir.delete(recursive: true);
      await dir.create(recursive: true);
    }
    final k = _cachedKey;
    if (k != null) _zeroize(k);
    _cachedKey = null;
  }

  // ── Crypto helpers ──────────────────────────────────────────────────────────

  Uint8List _requireKey() {
    final k = _cachedKey;
    if (k == null) throw StateError('Coffre verrouillé');
    return k;
  }

  Uint8List _randomBytes(int n) => SecretBytes.randomBytes(n);

  /// PBKDF2 legacy (coffres < v2.6.0). Static pour `Isolate.run`.
  ///
  /// Note : le `String password` reste en RAM (limitation Dart, immuable).
  /// On ne zeroize que la copie bytes UTF-8 que l'on a contrôle.
  static Uint8List _deriveKey(String password, Uint8List salt) {
    final pwBytes = Uint8List.fromList(utf8.encode(password));
    try {
      final pbkdf2 = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))
        ..init(Pbkdf2Parameters(salt, _iterations, _keyLen));
      return pbkdf2.process(pwBytes);
    } finally {
      for (var i = 0; i < pwBytes.length; i++) {
        pwBytes[i] = 0;
      }
    }
  }

  /// Argon2id (coffres ≥ v2.6.0). Params [memoryKB] et [iterations] passés
  /// dynamiquement (auto-tuning depuis v2.7.1, ou legacy/export sinon).
  /// `p=1`, type ARGON2_id, version 1.3. Memory-hard → résiste au cracking
  /// GPU bien mieux que PBKDF2.
  /// Static pour `Isolate.run`.
  ///
  /// Note : le `String password` reste en RAM (limitation Dart, immuable).
  /// On ne zeroize que la copie bytes UTF-8 que l'on contrôle. L'isolate
  /// duplique aussi le password à l'envoi du message — ces bytes sont
  /// hors de notre contrôle (limitation Isolate.run).
  static Uint8List _deriveKeyArgon2id(
    String password,
    Uint8List salt,
    int memoryKB,
    int iterations,
  ) {
    final pwBytes = Uint8List.fromList(utf8.encode(password));
    try {
      final params = Argon2Parameters(
        Argon2Parameters.ARGON2_id,
        salt,
        desiredKeyLength: _keyLen,
        iterations: iterations,
        memory: memoryKB,
        lanes: _argon2Lanes,
        version: Argon2Parameters.ARGON2_VERSION_13,
      );
      final argon2 = Argon2BytesGenerator()..init(params);
      final out = Uint8List(_keyLen);
      argon2.deriveKey(pwBytes, 0, out, 0);
      return out;
    } finally {
      for (var i = 0; i < pwBytes.length; i++) {
        pwBytes[i] = 0;
      }
    }
  }

  // ── Auto-tuning Argon2id (par device) ──────────────────────────────────────

  /// Bench rapide à params minimums pour estimer la vitesse Argon2id du
  /// device. Static pour `Isolate.run`. Retourne le temps en ms.
  static int _benchArgon2id() {
    final salt = Uint8List(_saltLen);
    final pwBytes = Uint8List(32);
    final stopwatch = Stopwatch()..start();
    final params = Argon2Parameters(
      Argon2Parameters.ARGON2_id,
      salt,
      desiredKeyLength: _keyLen,
      iterations: _argon2BenchIterations,
      memory: _argon2BenchMemoryKB,
      lanes: _argon2Lanes,
      version: Argon2Parameters.ARGON2_VERSION_13,
    );
    final argon2 = Argon2BytesGenerator()..init(params);
    final out = Uint8List(_keyLen);
    argon2.deriveKey(pwBytes, 0, out, 0);
    stopwatch.stop();
    return stopwatch.elapsedMilliseconds;
  }

  /// Calcule des params Argon2id qui ciblent ~[_argon2TargetMs] sur ce
  /// device, à partir d'un temps mesuré [benchMs] aux params bench.
  /// Bornes [4Mo..32Mo] × [2..4] iter pour rester safe sur low-end (Redmi 9C 3GB).
  static ({int memoryKB, int iterations}) _calibrateFromBench(int benchMs) {
    if (benchMs <= 0) {
      // Fallback de sécurité (mesure absurde) : params médians.
      return (memoryKB: 12288, iterations: 2);
    }
    if (benchMs >= _argon2TargetMs) {
      // Device déjà lent au bench min → params minimums.
      return (memoryKB: _argon2MinMemoryKB, iterations: _argon2MinIterations);
    }
    // Argon2id est ~linéaire en (memory × iterations).
    const benchWork = _argon2BenchMemoryKB * _argon2BenchIterations; // 8192
    final scaleFactor = _argon2TargetMs / benchMs;
    final targetWork = (benchWork * scaleFactor).round();

    int mem;
    int iter;
    if (targetWork <= _argon2MaxMemoryKB * _argon2MinIterations) {
      // On peut tenir avec t=2, on bumpe juste la memory.
      mem = (targetWork / _argon2MinIterations).round();
      iter = _argon2MinIterations;
    } else {
      // Memory au max, on bumpe iter.
      mem = _argon2MaxMemoryKB;
      iter = (targetWork / _argon2MaxMemoryKB).round();
    }
    // Arrondi à des multiples de 1024 KB (cohérence + lecture humaine).
    mem = ((mem / 1024).round() * 1024).clamp(
      _argon2MinMemoryKB,
      _argon2MaxMemoryKB,
    );
    iter = iter.clamp(_argon2MinIterations, _argon2MaxIterations);
    return (memoryKB: mem, iterations: iter);
  }

  /// Mesure + calcule les params Argon2id optimaux pour le device courant.
  /// À appeler une fois au setup.
  ///
  /// Lance [_argon2BenchSamples] benchs successifs en Isolate et garde le
  /// minimum (= meilleur cas, CPU non-throttled). Évite qu'un setup fait
  /// téléphone chaud / sous load fixe des params trop faibles à vie.
  ///
  /// Si tous les benchs échouent (OOM, isolate crash), fallback safe :
  /// params médians (m=12 Mo, t=2) — sécurité priorisée sur vitesse.
  Future<({int memoryKB, int iterations})> _calibrateArgon2() async {
    final samples = <int>[];
    for (var i = 0; i < _argon2BenchSamples; i++) {
      try {
        samples.add(await Isolate.run(_benchArgon2id));
      } catch (e) {
        // OOM, isolate crash → on saute cet échantillon.
        if (kDebugMode) debugPrint('vault: calibrateArgon2 fail: $e');
      }
    }
    if (samples.isEmpty) {
      // Fallback safe : tous les benchs ont échoué.
      return (memoryKB: 12288, iterations: 2);
    }
    samples.sort();
    return _calibrateFromBench(samples.first);
  }

  static void _zeroize(Uint8List bytes) => SecretBytes.wipe(bytes);

  /// Retire le suffixe `.enc` d'un nom de fichier du coffre s'il est présent.
  static String _stripEnc(String name) =>
      name.endsWith('.enc') ? name.substring(0, name.length - 4) : name;

  /// Chiffre en v2 (magic "RFT2" + nonce + ciphertext+tag, AAD = prefix|filename).
  Uint8List _encryptV2(List<int> plain, Uint8List key, String filename) {
    final nonce = _randomBytes(_nonceLen);
    final aad = Uint8List.fromList(utf8.encode('$_aadPrefix$filename'));
    final cipher = GCMBlockCipher(AESEngine())
      ..init(true, AEADParameters(KeyParameter(key), 128, nonce, aad));
    final ct = cipher.process(Uint8List.fromList(plain));
    final out = BytesBuilder()
      ..add(_magicV2)
      ..add(nonce)
      ..add(ct);
    return out.toBytes();
  }

  /// Déchiffre auto-détectant le format (magic v2 sinon fallback v1 sans AAD).
  Uint8List _decryptAuto(Uint8List blob, Uint8List key, String filename) {
    if (blob.length >= 4 + _nonceLen + 16 &&
        blob[0] == _magicV2[0] &&
        blob[1] == _magicV2[1] &&
        blob[2] == _magicV2[2] &&
        blob[3] == _magicV2[3]) {
      // Format v2
      final nonce = blob.sublist(4, 4 + _nonceLen);
      final ct = blob.sublist(4 + _nonceLen);
      final aad = Uint8List.fromList(utf8.encode('$_aadPrefix$filename'));
      final cipher = GCMBlockCipher(AESEngine())
        ..init(false, AEADParameters(KeyParameter(key), 128, nonce, aad));
      return cipher.process(ct);
    }
    // Format v1 (legacy) : nonce + ciphertext+tag, AAD vide.
    if (blob.length < _nonceLen + 16) throw StateError('Bloc invalide');
    final nonce = blob.sublist(0, _nonceLen);
    final ct = blob.sublist(_nonceLen);
    final cipher = GCMBlockCipher(
      AESEngine(),
    )..init(false, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));
    return cipher.process(ct);
  }

  /// Encrypt avec routing 3 niveaux :
  /// - <1 Mo : main isolate (PointyCastle Dart pur, instantané)
  /// - 1-5 Mo : Dart Isolate (offload main thread)
  /// - >5 Mo : Kotlin native AES-GCM (accélération matérielle ARMv8,
  ///   ~10-50× plus rapide). Fallback Isolate UNIQUEMENT si channel
  ///   indisponible (MissingPluginException) — JAMAIS sur erreur crypto
  ///   réelle (BAD_KEY, BAD_NONCE, ENCRYPT_ERROR) qui doit être propagée.
  Future<Uint8List> _encryptMaybeIsolate(
    List<int> plain,
    Uint8List key,
    String filename,
  ) async {
    if (plain.length >= _nativeThreshold) {
      try {
        return await _encryptNative(plain, key, filename);
      } on MissingPluginException {
        // Channel non enregistré (hot-reload, dev) → fallback acceptable.
      } on PlatformException catch (e) {
        // Toute erreur crypto authentique doit remonter (signal d'intégrité).
        if (_isCryptoErrorCode(e.code)) rethrow;
        // Autres codes inconnus → fallback prudent + log debug.
      }
    }
    if (plain.length < PerfThresholds.isolateThreshold) {
      return _encryptV2(plain, key, filename);
    }
    return Isolate.run(() => _encryptV2(plain, key, filename));
  }

  /// Decrypt avec même routing que l'encrypt. Fallback Isolate uniquement
  /// si channel indisponible — JAMAIS sur AEAD bad tag (qui doit propager
  /// pour signaler le tampering).
  Future<Uint8List> _decryptMaybeIsolate(
    Uint8List blob,
    Uint8List key,
    String filename,
  ) async {
    if (blob.length >= _nativeThreshold) {
      try {
        return await _decryptNative(blob, key, filename);
      } on MissingPluginException {
        // Channel KO → fallback OK.
      } on PlatformException catch (e) {
        if (_isCryptoErrorCode(e.code)) rethrow;
      }
    }
    if (blob.length < PerfThresholds.isolateThreshold) {
      return _decryptAuto(blob, key, filename);
    }
    return Isolate.run(() => _decryptAuto(blob, key, filename));
  }

  /// True si le code d'erreur du channel crypto natif est une erreur réelle
  /// d'authentification / validation — qui doit être propagée et non masquée
  /// par un fallback Isolate.
  static bool _isCryptoErrorCode(String code) {
    return code == 'DECRYPT_ERROR' ||
        code == 'ENCRYPT_ERROR' ||
        code == 'BAD_KEY' ||
        code == 'BAD_NONCE' ||
        code == 'NO_ARGS';
  }

  /// Chiffre v2 via Kotlin native (Cipher AES/GCM/NoPadding hardware-accel).
  /// Format de sortie identique à [_encryptV2] : magic "RFT2" + nonce + ct+tag.
  Future<Uint8List> _encryptNative(
    List<int> plain,
    Uint8List key,
    String filename,
  ) async {
    final nonce = _randomBytes(_nonceLen);
    final aad = Uint8List.fromList(utf8.encode('$_aadPrefix$filename'));
    final result = await _nativeChannel.invokeMethod<Uint8List>('encrypt', {
      'key': key,
      'nonce': nonce,
      'aad': aad,
      'plain': Uint8List.fromList(plain),
    });
    if (result == null) throw StateError('Native encrypt returned null');
    final out = BytesBuilder()
      ..add(_magicV2)
      ..add(nonce)
      ..add(result);
    return out.toBytes();
  }

  /// Déchiffre via Kotlin native, auto-détection format v2 / v1.
  Future<Uint8List> _decryptNative(
    Uint8List blob,
    Uint8List key,
    String filename,
  ) async {
    Uint8List nonce;
    Uint8List ct;
    Uint8List aad;
    if (blob.length >= 4 + _nonceLen + 16 &&
        blob[0] == _magicV2[0] &&
        blob[1] == _magicV2[1] &&
        blob[2] == _magicV2[2] &&
        blob[3] == _magicV2[3]) {
      // Format v2
      nonce = Uint8List.sublistView(blob, 4, 4 + _nonceLen);
      ct = Uint8List.sublistView(blob, 4 + _nonceLen);
      aad = Uint8List.fromList(utf8.encode('$_aadPrefix$filename'));
    } else {
      // Format v1 (legacy) : nonce + ct+tag, AAD vide.
      if (blob.length < _nonceLen + 16) throw StateError('Bloc invalide');
      nonce = Uint8List.sublistView(blob, 0, _nonceLen);
      ct = Uint8List.sublistView(blob, _nonceLen);
      aad = Uint8List(0);
    }
    final result = await _nativeChannel.invokeMethod<Uint8List>('decrypt', {
      'key': key,
      'nonce': nonce,
      'aad': aad,
      'blob': ct,
    });
    if (result == null) throw StateError('Native decrypt returned null');
    return result;
  }
}

/// Résultat d'un restore depuis un fichier `.rftvault`.
class RestoreResult {
  /// Nombre total d'entrées dans le backup.
  final int total;

  /// Nombre de fichiers effectivement restaurés (écrits dans le coffre).
  final int restored;

  /// Nombre de fichiers ignorés car homonymes existent déjà
  /// (et `overwriteExisting=false`).
  final int skipped;

  const RestoreResult({
    required this.total,
    required this.restored,
    required this.skipped,
  });
}

/// Entrée parsée du payload `.rftvault` (interne au service).
class _BackupEntry {
  final String name;
  final Uint8List plain;
  const _BackupEntry(this.name, this.plain);
}
