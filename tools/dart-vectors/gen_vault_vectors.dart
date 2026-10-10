// Génère des vecteurs de test à partir du VRAI code Dart du coffre de Read Files Tech
// (lib/services/vault/vault_kdf.dart et vault_blob.dart, identiques à la v2.15.2).
//
// Rien n'est réimplémenté ici pour les dérivations et le format des blobs : on appelle
// les fonctions de l'application. Seule l'enveloppe .rftvault est reconstruite à la main
// (elle vit dans VaultService, qui dépend de Flutter) — elle est donc AUSSI produite sur
// appareil par l'application réelle, et c'est ce second jeu qui fait foi.
//
// Lancement :
//   dart --packages=J:/applications/read_files_tech/.dart_tool/package_config.json \
//        tools/dart-vectors/gen_vectors.dart > app/src/test/resources/vectors.json

import 'dart:convert';
import 'dart:typed_data';

import 'package:pointycastle/export.dart';
import 'package:read_files_tech/services/vault/vault_blob.dart';
import 'package:read_files_tech/services/vault/vault_bytes.dart';
import 'package:read_files_tech/services/vault/vault_kdf.dart';

String hex(List<int> b) =>
    b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();

Uint8List seq(int len, int seed) =>
    Uint8List.fromList(List.generate(len, (i) => (i * 31 + seed * 7 + 3) & 0xff));

void main() {
  final passwords = ['correct horse battery', 'Mot de passe é€ 🔑 ñ'];
  final salt = seq(kVaultSaltLen, 1);
  final out = <String, Object>{
    'source': 'read_files_tech lib/services/vault (v2.15.2)',
    'salt': hex(salt),
  };

  // ── KDF ────────────────────────────────────────────────────────────────
  final kdf = <Map<String, Object>>[];
  for (final pw in passwords) {
    kdf.add({
      'kdf': 'pbkdf2',
      'password': pw,
      'iterations': kPbkdf2Iterations,
      'key': hex(derivePbkdf2(pw, salt)),
    });
    for (final p in const [
      (kArgon2LegacyMemoryKB, kArgon2LegacyIterations),
      (kArgon2MinMemoryKB, kArgon2MinIterations),
      (kArgon2MaxMemoryKB, kArgon2MaxIterations),
      (12288, 2),
      (kArgon2BenchMemoryKB, kArgon2BenchIterations),
      (20480, 3),
    ]) {
      kdf.add({
        'kdf': 'argon2id',
        'password': pw,
        'memoryKB': p.$1,
        'iterations': p.$2,
        'key': hex(deriveArgon2id(pw, salt, p.$1, p.$2)),
      });
    }
  }
  out['kdf'] = kdf;

  // ── Blobs .enc (v2 et v1) ──────────────────────────────────────────────
  final key = deriveArgon2id(passwords[1], salt, 8192, 2);
  final blobs = <Map<String, Object>>[];
  final cases = <(String, List<int>)>[
    ('_check.enc', utf8.encode('read_files_tech_vault_v1')),
    ('Rapport été 2026.pdf.enc', seq(5000, 9)),
    ('vide.txt.enc', <int>[]),
  ];
  for (var i = 0; i < cases.length; i++) {
    final (name, plain) = cases[i];
    final nonce = seq(kVaultNonceLen, 100 + i);
    blobs.add({
      'format': 'v2',
      'filename': name,
      'plain': hex(plain),
      'blob': hex(encryptV2(plain: plain, key: key, nonce: nonce, filename: name)),
    });
    // v1 : nonce | ct+tag, AAD vide (≤ v2.5.4). Produit par gcmProcess de l'app.
    final ct = gcmProcess(
      forEncryption: true,
      input: Uint8List.fromList(plain),
      key: key,
      nonce: nonce,
      aad: Uint8List(0),
    );
    blobs.add({
      'format': 'v1',
      'filename': name,
      'plain': hex(plain),
      'blob': hex([...nonce, ...ct]),
    });
  }
  out['blobKey'] = hex(key);
  out['blobs'] = blobs;

  // ── Enveloppe .rftvault reconstruite (v2 puis v1) ──────────────────────
  final entries = <(String, List<int>)>[
    ('notes été.txt', utf8.encode('Bonjour — été 2026 ✓')),
    ('photo.jpg', seq(3000, 4)),
  ];
  final payload = BytesBuilder()..add(u32be(entries.length));
  for (final (n, d) in entries) {
    final nb = utf8.encode(n);
    payload
      ..add(u16be(nb.length))
      ..add(nb)
      ..add(u32be(d.length))
      ..add(d);
  }
  final payloadBytes = payload.toBytes();
  const magic = [0x52, 0x46, 0x54, 0x56, 0x41, 0x55, 0x4C, 0x54];
  final bSalt = seq(kVaultSaltLen, 50);
  final bNonce = seq(kVaultNonceLen, 60);
  const exportPw = 'export 🔒 sauvegarde';
  final exportKey =
      deriveArgon2id(exportPw, bSalt, kArgon2ExportMemoryKB, kArgon2ExportIterations);
  final headerV2 = (BytesBuilder()
        ..add(magic)
        ..addByte(2)
        ..add(const [0, 0, 0])
        ..add(u32be(kArgon2ExportMemoryKB))
        ..add(u32be(kArgon2ExportIterations))
        ..add(bSalt)
        ..add(bNonce))
      .toBytes();
  final ctV2 = gcmProcess(
    forEncryption: true,
    input: payloadBytes,
    key: exportKey,
    nonce: bNonce,
    aad: headerV2,
  );
  final headerV1 = (BytesBuilder()
        ..add(magic)
        ..addByte(1)
        ..add(const [0, 0, 0])
        ..add(bSalt)
        ..add(bNonce))
      .toBytes();
  final ctV1 = gcmProcess(
    forEncryption: true,
    input: payloadBytes,
    key: exportKey,
    nonce: bNonce,
    aad: Uint8List.fromList(utf8.encode('rftvault-v1')),
  );
  out['backup'] = {
    'exportPassword': exportPw,
    'entries': [
      for (final (n, d) in entries) {'name': n, 'data': hex(d)},
    ],
    'v2': hex([...headerV2, ...ctV2]),
    'v1': hex([...headerV1, ...ctV1]),
  };

  print(const JsonEncoder.withIndent('  ').convert(out));
}
