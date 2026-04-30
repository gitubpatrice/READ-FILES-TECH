import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:math';
import 'dart:typed_data';

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
  static const _iterations = 600000;
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
  Future<void> setupWithPassword(String password) async {
    final salt = _randomBytes(_saltLen);
    // Dérivation PBKDF2 600k itérations en Isolate (1-3s sur S9 — bloquerait
    // l'UI sinon).
    final key  = await Isolate.run(() => _deriveKey(password, salt));
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kSalt, base64Encode(salt));
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
    // Dérivation PBKDF2 600k itérations en Isolate (1-3s sur S9).
    final key  = await Isolate.run(() => _deriveKey(password, salt));
    final dir  = await _vaultDir();
    final check = File('${dir.path}/$_checkFile');
    if (!await check.exists()) return false;
    try {
      final blob = await check.readAsBytes();
      final plain = _decryptAuto(blob, key, _checkFile);
      if (utf8.decode(plain) == _checkPlain) {
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
    final name = _safeBasename(source.path);
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
    final name = _safeBasename(source.path);
    final destName = '$name.enc';
    final dest = File('${dir.path}/$destName');
    final plain = await source.readAsBytes();
    final ct = await _encryptMaybeIsolate(plain, key, destName);
    await dest.writeAsBytes(ct);
    return dest.path;
  }

  /// Extrait un basename sûr d'un path source. Refuse `..`, vide, ou chemins
  /// comportant des séparateurs internes — empêche un path forgé de sortir
  /// du dossier vault via concaténation.
  String _safeBasename(String path) {
    final raw = path.split(RegExp(r'[/\\]')).last;
    if (raw.isEmpty || raw == '.' || raw == '..') {
      throw ArgumentError('Nom de fichier invalide');
    }
    if (raw.contains('/') || raw.contains('\\') || raw.contains('\x00')) {
      throw ArgumentError('Nom de fichier invalide');
    }
    return raw;
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

  /// Static : nécessaire pour `Isolate.run` (pas de capture de `this`).
  static Uint8List _deriveKey(String password, Uint8List salt) {
    final pbkdf2 = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))
      ..init(Pbkdf2Parameters(salt, _iterations, _keyLen));
    return pbkdf2.process(Uint8List.fromList(utf8.encode(password)));
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

  /// Encrypt avec offload Isolate au-delà du seuil.
  Future<Uint8List> _encryptMaybeIsolate(
      List<int> plain, Uint8List key, String filename) async {
    if (plain.length < _isolateThreshold) {
      return _encryptV2(plain, key, filename);
    }
    return Isolate.run(() => _encryptV2(plain, key, filename));
  }

  /// Decrypt avec offload Isolate au-delà du seuil.
  Future<Uint8List> _decryptMaybeIsolate(
      Uint8List blob, Uint8List key, String filename) async {
    if (blob.length < _isolateThreshold) {
      return _decryptAuto(blob, key, filename);
    }
    return Isolate.run(() => _decryptAuto(blob, key, filename));
  }
}
