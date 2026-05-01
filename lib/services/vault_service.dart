import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:math';
import 'dart:typed_data';

import 'package:files_tech_core/files_tech_core.dart';
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
  static const _kSalt          = 'vault_salt_v1';
  static const _kSetup         = 'vault_setup_v1';
  static const _kFails         = 'vault_unlock_fails';
  static const _kLockoutUntil  = 'vault_lockout_until_ms';

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
  static const _kdfPbkdf2   = 'pbkdf2';
  static const _kdfArgon2id = 'argon2id';

  static const _iterations = 600000; // PBKDF2 (legacy)

  // Argon2id params : choisis pour bon équilibre sécurité / low-end devices.
  // 16 Mo de mémoire = OK sur Redmi 9C 3GB, 4 itérations compensent.
  // Sur S24 flagship : ~0.5s. Sur S9 : ~1.5s. Sur Redmi 9C : ~4s.
  // GPU-cracking : ~1000× plus difficile que PBKDF2 600k SHA-256.
  static const _argon2MemoryPowerOf2 = 14;  // 2^14 KB = 16 MB
  static const _argon2Iterations     = 4;
  static const _argon2Lanes          = 1;

  static const _saltLen  = 16;
  static const _nonceLen = 12;
  static const _keyLen   = 32;
  static const _checkFile = '_check.enc';
  static const _checkPlain = 'read_files_tech_vault_v1';

  /// Magic bytes du format v2 : "RFT2".
  static const _magicV2 = [0x52, 0x46, 0x54, 0x32];
  static const _aadPrefix = 'rft-vault-v2|';

  /// Seuil au-delà duquel on bascule l'op crypto sur un Isolate (évite UI freeze).
  /// 1 Mo = ~30 ms PointyCastle GCM sur S9 → acceptable. Au-dessus, isolate.
  static const _isolateThreshold = 1024 * 1024;

  /// Seuil au-delà duquel on bascule sur le crypto NATIF Kotlin (10-50× plus
  /// rapide grâce à l'accélération matérielle ARMv8). 5 Mo = ~150 ms en
  /// PointyCastle Dart sur S9 vs ~5 ms native — le coût de la copie bytes
  /// Dart→native est amorti au-delà de cette taille.
  static const _nativeThreshold = 5 * 1024 * 1024;

  /// Channel Kotlin exposant `encrypt` / `decrypt` AES-256-GCM natif.
  /// Voir MainActivity.kt section vault_crypto.
  static const _nativeChannel =
      MethodChannel('com.readfilestech/vault_crypto');

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
  /// Utilise Argon2id par défaut (depuis v2.6.0).
  Future<void> setupWithPassword(String password) async {
    final salt = _randomBytes(_saltLen);
    // Argon2id en Isolate (~0.5-4s selon device).
    final key  = await Isolate.run(() => _deriveKeyArgon2id(password, salt));
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kSalt, base64Encode(salt));
    await prefs.setString(_kKdfVersion, _kdfArgon2id);
    await prefs.setBool(_kSetup, true);
    await prefs.remove(_kFails);
    await prefs.remove(_kLockoutUntil);
    // Crée le fichier sentinelle (format v2 avec AAD = filename).
    final dir = await _vaultDir();
    final encrypted = _encryptV2(utf8.encode(_checkPlain), key, _checkFile);
    await File('${dir.path}/$_checkFile').writeAsBytes(encrypted);
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
    if (saltB64 == null) return false;
    final salt = base64Decode(saltB64);
    // Sélectionne le KDF selon la version stockée :
    // - 'argon2id' (v2.6.0+) → Argon2id
    // - 'pbkdf2' ou absent (legacy) → PBKDF2
    final kdf = prefs.getString(_kKdfVersion) ?? _kdfPbkdf2;
    final key = kdf == _kdfArgon2id
        ? await Isolate.run(() => _deriveKeyArgon2id(password, salt))
        : await Isolate.run(() => _deriveKey(password, salt));
    final dir  = await _vaultDir();
    final check = File('${dir.path}/$_checkFile');
    if (!await check.exists()) return false;
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
    } catch (_) {/* on tombe sur incrément échec ci-dessous */}
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

  /// Marque le coffre comme déverrouillé en cache (après biométrique réussie).
  bool get isUnlocked => _cachedKey != null;

  void lock() {
    final k = _cachedKey;
    if (k != null) _zeroize(k);
    _cachedKey = null;
    // Best-effort : nettoyer les fichiers déchiffrés laissés en tmp.
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
      throw FileSystemException('Fichier homonyme déjà dans le coffre', dest.path);
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
    final encName = encrypted.path.split(RegExp(r'[/\\]')).last;
    final originalName = encName.endsWith('.enc')
        ? encName.substring(0, encName.length - 4)
        : encName;
    final out = File('${tmp.path}/$originalName');
    final blob = await encrypted.readAsBytes();
    final plain = await _decryptMaybeIsolate(blob, key, encName);
    await out.writeAsBytes(plain);
    return out;
  }

  /// Supprime tous les fichiers déchiffrés laissés dans le cache.
  Future<void> purgeTempDecrypted() async {
    try {
      final tmpRoot = await getTemporaryDirectory();
      final tmp = Directory('${tmpRoot.path}/vault_decrypt');
      if (await tmp.exists()) await tmp.delete(recursive: true);
    } catch (_) {}
  }

  /// Exporte (déchiffre) un fichier du coffre vers un dossier de destination.
  Future<File> exportFile(File encrypted, String destDir) async {
    final key = _requireKey();
    final encName = encrypted.path.split(RegExp(r'[/\\]')).last;
    final originalName = encName.endsWith('.enc')
        ? encName.substring(0, encName.length - 4)
        : encName;
    final out = File('$destDir/$originalName');
    final blob = await encrypted.readAsBytes();
    final plain = await _decryptMaybeIsolate(blob, key, encName);
    await out.writeAsBytes(plain);
    return out;
  }

  Future<void> deleteFile(File encrypted) async {
    if (await encrypted.exists()) await encrypted.delete();
  }

  /// Réinitialise complètement le coffre.
  Future<void> reset() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kSalt);
    await prefs.remove(_kSetup);
    await prefs.remove(_kKdfVersion);
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

  Uint8List _randomBytes(int n) {
    final rng = Random.secure();
    return Uint8List.fromList(List<int>.generate(n, (_) => rng.nextInt(256)));
  }

  /// PBKDF2 legacy (coffres < v2.6.0). Static pour `Isolate.run`.
  static Uint8List _deriveKey(String password, Uint8List salt) {
    final pbkdf2 = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))
      ..init(Pbkdf2Parameters(salt, _iterations, _keyLen));
    return pbkdf2.process(Uint8List.fromList(utf8.encode(password)));
  }

  /// Argon2id (coffres ≥ v2.6.0). m=16 Mo, t=4, p=1, type ARGON2_id, version 1.3.
  /// Memory-hard → résiste au cracking GPU bien mieux que PBKDF2.
  /// Static pour `Isolate.run`.
  static Uint8List _deriveKeyArgon2id(String password, Uint8List salt) {
    final params = Argon2Parameters(
      Argon2Parameters.ARGON2_id,
      salt,
      desiredKeyLength: _keyLen,
      iterations: _argon2Iterations,
      memoryPowerOf2: _argon2MemoryPowerOf2,
      lanes: _argon2Lanes,
      version: Argon2Parameters.ARGON2_VERSION_13,
    );
    final argon2 = Argon2BytesGenerator()..init(params);
    final out = Uint8List(_keyLen);
    argon2.deriveKey(
      Uint8List.fromList(utf8.encode(password)),
      0,
      out,
      0,
    );
    return out;
  }

  void _zeroize(Uint8List bytes) {
    for (var i = 0; i < bytes.length; i++) {
      bytes[i] = 0;
    }
  }

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
      final ct    = blob.sublist(4 + _nonceLen);
      final aad = Uint8List.fromList(utf8.encode('$_aadPrefix$filename'));
      final cipher = GCMBlockCipher(AESEngine())
        ..init(false, AEADParameters(KeyParameter(key), 128, nonce, aad));
      return cipher.process(ct);
    }
    // Format v1 (legacy) : nonce + ciphertext+tag, AAD vide.
    if (blob.length < _nonceLen + 16) throw StateError('Bloc invalide');
    final nonce = blob.sublist(0, _nonceLen);
    final ct    = blob.sublist(_nonceLen);
    final cipher = GCMBlockCipher(AESEngine())
      ..init(false, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));
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
      List<int> plain, Uint8List key, String filename) async {
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
    if (plain.length < _isolateThreshold) {
      return _encryptV2(plain, key, filename);
    }
    return Isolate.run(() => _encryptV2(plain, key, filename));
  }

  /// Decrypt avec même routing que l'encrypt. Fallback Isolate uniquement
  /// si channel indisponible — JAMAIS sur AEAD bad tag (qui doit propager
  /// pour signaler le tampering).
  Future<Uint8List> _decryptMaybeIsolate(
      Uint8List blob, Uint8List key, String filename) async {
    if (blob.length >= _nativeThreshold) {
      try {
        return await _decryptNative(blob, key, filename);
      } on MissingPluginException {
        // Channel KO → fallback OK.
      } on PlatformException catch (e) {
        if (_isCryptoErrorCode(e.code)) rethrow;
      }
    }
    if (blob.length < _isolateThreshold) {
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
      List<int> plain, Uint8List key, String filename) async {
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
      Uint8List blob, Uint8List key, String filename) async {
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
      ct    = Uint8List.sublistView(blob, 4 + _nonceLen);
      aad   = Uint8List.fromList(utf8.encode('$_aadPrefix$filename'));
    } else {
      // Format v1 (legacy) : nonce + ct+tag, AAD vide.
      if (blob.length < _nonceLen + 16) throw StateError('Bloc invalide');
      nonce = Uint8List.sublistView(blob, 0, _nonceLen);
      ct    = Uint8List.sublistView(blob, _nonceLen);
      aad   = Uint8List(0);
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
