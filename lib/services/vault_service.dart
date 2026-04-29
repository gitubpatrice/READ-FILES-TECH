import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:path_provider/path_provider.dart';
import 'package:pointycastle/export.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Coffre fort local : fichiers chiffrés AES-256-GCM.
/// - Master password → PBKDF2-HMAC-SHA256 (600 000 itérations, salt 16 o)
/// - Chaque fichier : nonce 12 o + ciphertext + tag 16 o, le tout dans un seul .enc
/// - Le nom de fichier d'origine est préservé en clair (basename) — le contenu seul est chiffré.
/// - Vérification du master password : un fichier sentinelle "_check.enc" qui contient
///   un texte connu chiffré ; si le déchiffrement réussit, le password est correct.
class VaultService {
  static const _kSalt   = 'vault_salt_v1';
  static const _kSetup  = 'vault_setup_v1';
  static const _iterations = 600000;
  static const _saltLen  = 16;
  static const _nonceLen = 12;
  static const _keyLen   = 32;
  static const _checkFile = '_check.enc';
  static const _checkPlain = 'read_files_tech_vault_v1';

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
    final key  = _deriveKey(password, salt);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kSalt, base64Encode(salt));
    await prefs.setBool(_kSetup, true);
    // Crée le fichier sentinelle.
    final dir = await _vaultDir();
    final encrypted = _encrypt(utf8.encode(_checkPlain), key);
    await File('${dir.path}/$_checkFile').writeAsBytes(encrypted);
    _cachedKey = key;
  }

  /// Tente le déverrouillage. Retourne true si réussi.
  Future<bool> unlockWithPassword(String password) async {
    final prefs = await SharedPreferences.getInstance();
    final saltB64 = prefs.getString(_kSalt);
    if (saltB64 == null) return false;
    final salt = base64Decode(saltB64);
    final key  = _deriveKey(password, salt);
    final dir  = await _vaultDir();
    final check = File('${dir.path}/$_checkFile');
    if (!await check.exists()) return false;
    try {
      final plain = _decrypt(await check.readAsBytes(), key);
      if (utf8.decode(plain) == _checkPlain) {
        _cachedKey = key;
        return true;
      }
    } catch (_) {}
    return false;
  }

  /// Marque le coffre comme déverrouillé en cache (après biométrique réussie).
  /// Note : nécessite que le password ait été passé une fois ; ici on ne stocke
  /// jamais le password. La biométrique permet juste de réutiliser la clé en
  /// mémoire pour la session courante. Si l'app est tuée, il faut ressaisir.
  bool get isUnlocked => _cachedKey != null;

  void lock() {
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
    final dest = File('${dir.path}/$name.enc');
    if (await dest.exists() && !overwrite) {
      throw FileSystemException('Fichier homonyme déjà dans le coffre', dest.path);
    }
    final plain = await source.readAsBytes();
    await dest.writeAsBytes(_encrypt(plain, key));
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
    final dest = File('${dir.path}/$name.enc');
    final plain = await source.readAsBytes();
    await dest.writeAsBytes(_encrypt(plain, key));
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
  /// Le fichier en clair est créé dans `tmp/vault_decrypt/` — nettoyé à `lock()`
  /// et au démarrage via [purgeTempDecrypted].
  Future<File> decryptToTemp(File encrypted) async {
    final key = _requireKey();
    final tmpRoot = await getTemporaryDirectory();
    final tmp = Directory('${tmpRoot.path}/vault_decrypt');
    if (!await tmp.exists()) await tmp.create(recursive: true);
    final base = encrypted.path.split(RegExp(r'[/\\]')).last;
    final originalName = base.endsWith('.enc')
        ? base.substring(0, base.length - 4)
        : base;
    final out = File('${tmp.path}/$originalName');
    final plain = _decrypt(await encrypted.readAsBytes(), key);
    await out.writeAsBytes(plain);
    return out;
  }

  /// Supprime tous les fichiers déchiffrés laissés dans le cache.
  /// À appeler au boot de l'app et à chaque verrouillage du coffre.
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
    final base = encrypted.path.split(RegExp(r'[/\\]')).last;
    final originalName = base.endsWith('.enc')
        ? base.substring(0, base.length - 4)
        : base;
    final out = File('$destDir/$originalName');
    final plain = _decrypt(await encrypted.readAsBytes(), key);
    await out.writeAsBytes(plain);
    return out;
  }

  Future<void> deleteFile(File encrypted) async {
    if (await encrypted.exists()) await encrypted.delete();
  }

  /// Réinitialise complètement le coffre (DESTRUCTIF — supprime tous les fichiers chiffrés).
  Future<void> reset() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kSalt);
    await prefs.remove(_kSetup);
    final dir = await _vaultDir();
    if (await dir.exists()) {
      await dir.delete(recursive: true);
      await dir.create(recursive: true);
    }
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

  Uint8List _deriveKey(String password, Uint8List salt) {
    final pbkdf2 = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))
      ..init(Pbkdf2Parameters(salt, _iterations, _keyLen));
    return pbkdf2.process(Uint8List.fromList(utf8.encode(password)));
  }

  Uint8List _encrypt(List<int> plain, Uint8List key) {
    final nonce = _randomBytes(_nonceLen);
    final cipher = GCMBlockCipher(AESEngine())
      ..init(true, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));
    final ct = cipher.process(Uint8List.fromList(plain));
    final out = BytesBuilder()..add(nonce)..add(ct);
    return out.toBytes();
  }

  Uint8List _decrypt(Uint8List blob, Uint8List key) {
    if (blob.length < _nonceLen + 16) throw StateError('Bloc invalide');
    final nonce = blob.sublist(0, _nonceLen);
    final ct    = blob.sublist(_nonceLen);
    final cipher = GCMBlockCipher(AESEngine())
      ..init(false, AEADParameters(KeyParameter(key), 128, nonce, Uint8List(0)));
    return cipher.process(ct);
  }
}
