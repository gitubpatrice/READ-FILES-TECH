// Phase 2 seed (port plan, phase 2): fills a Flutter Read Files Tech 2.15.2 or 2.15.3 with real data,
// written by THAT version's own code, then records what that version reads back — the oracle the port
// is compared with (tools/phase2/compare.py).
//
// Copied into lib/ of a worktree at the tag, built as a debug APK:
//     flutter build apk --debug -t lib/rft_seed_main.dart
// The host (tools/phase2/phase2.py) writes files/rft_seed/scenario.json through run-as, starts the app,
// waits for "RFTSEED DONE", then pulls files/rft_seed/expected.json.
//
// What only a screen of the 2.15.x writes is reproduced from that screen's code, line for line, and
// cited: the editor's history (code_editor_screen.dart `_backup`), the "Extract all" folder name
// (zip_viewer_screen.dart `_extractAll`, which calls the real extractArchiveIsolate), the dismissed
// recents (rft_picker_screen.dart `_addDismissed`), the theme and the permission flag (main.dart).
// The cache residues are created as the plugins leave them after a kill: no plugin can run headless.
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:crypto/crypto.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'services/archive_extract_service.dart';
import 'services/output_storage_service.dart';
import 'services/trash_service.dart';
import 'services/vault/vault_blob.dart';
import 'services/vault/vault_kdf.dart';
import 'services/vault_service.dart';
import 'utils/file_caps.dart';
import 'utils/monotonic_clock.dart';

const vaultPassword = 'Coffre été 🔐 2026';
const backupPassword = 'Sauvegarde ✓ export';
const sharedRoot = '/storage/emulated/0';
const userDir = '$sharedRoot/RFT seed';

final lines = <String>[];

void log(String s) {
  lines.add(s);
  debugPrint('RFTSEED $s');
}

Uint8List pseudo(int len, int seed) {
  final out = Uint8List(len);
  var x = seed;
  for (var i = 0; i < len; i++) {
    x = (x * 1103515245 + 12345) & 0x7fffffff;
    out[i] = x >> 16;
  }
  return out;
}

String sha(List<int> bytes) => sha256.convert(bytes).toString();

Future<Map<String, Object?>> fileDigest(File f) async {
  final bytes = await f.readAsBytes();
  return {'size': bytes.length, 'sha256': sha(bytes)};
}

/// Digests.tree of the port (check/CheckData.kt), computed here the Dart way.
Future<Map<String, Object?>> tree(String path) async {
  final type = await FileSystemEntity.type(path, followLinks: false);
  switch (type) {
    case FileSystemEntityType.link:
      return {'type': 'link'};
    case FileSystemEntityType.file:
      final bytes = await File(path).readAsBytes();
      return {'type': 'file', 'size': bytes.length, 'sha256': sha(bytes)};
    case FileSystemEntityType.directory:
      final files = <String, String>{};
      await for (final e in Directory(path).list(recursive: true, followLinks: false)) {
        if (await FileSystemEntity.type(e.path, followLinks: false) == FileSystemEntityType.file) {
          files[e.path.substring(path.length + 1)] = sha(await File(e.path).readAsBytes());
        }
      }
      final sorted = files.keys.toList()..sort();
      return {'type': 'dir', 'files': {for (final k in sorted) k: files[k]}};
    default:
      return {'type': 'absent'};
  }
}

String typeOf(Object? v) => switch (v) {
  bool() => 'bool',
  int() => 'int',
  double() => 'double',
  String() => 'String',
  List() => 'List<String>',
  _ => 'other:${v.runtimeType}',
};

Future<String> bootId() async => (await File('/proc/sys/kernel/random/boot_id').readAsString()).trim();

// ───────────────────────────── what only screens write ─────────────────────────────

/// code_editor_screen.dart `_backup()` (2.15.2 = 2.15.3), with the file name and path as parameters.
Future<void> editorBackup(String resolvedPath, String name) async {
  final dir = await getApplicationDocumentsDirectory();
  final histDir = Directory('${dir.path}/history');
  if (!await histDir.exists()) await histDir.create(recursive: true);

  final base = name.replaceAll(RegExp(r'[^\w.]'), '_');
  final ts = DateTime.now().millisecondsSinceEpoch;
  await File(resolvedPath).copy('${histDir.path}/${base}_$ts.bak');

  final entries = await histDir.list().toList();
  final baks = entries.whereType<File>().where((f) => f.path.contains('${base}_')).toList()
    ..sort((a, b) => a.path.compareTo(b.path));
  if (baks.length > 10) {
    for (final old in baks.sublist(0, baks.length - 10)) {
      await old.delete();
    }
  }
}

/// rft_picker_screen.dart `_addDismissed`.
Future<void> addDismissed(String path) async {
  final prefs = await SharedPreferences.getInstance();
  final set = (prefs.getStringList('recents_dismissed_paths') ?? const <String>[]).toSet();
  set.add(path);
  await prefs.setStringList('recents_dismissed_paths', set.toList());
}

/// zip_viewer_screen.dart `_extractAll`: the folder name, then the real extraction.
Future<void> extractAll(String zipPath, String name) async {
  final dir = await getApplicationDocumentsDirectory();
  final base = name.replaceAll(RegExp(r'\.\w+$'), '');
  final outDirPath = '${dir.path}/${base}_extracted';
  final r = await extractArchiveIsolate(
    zipPath: zipPath,
    outDirPath: outDirPath,
    maxEntryBytes: FileCaps.zipEntryDecompressed,
    maxTotalBytes: FileCaps.zipExtractTotal,
  );
  log('extraction ${r.outDirPath}');
}

// ───────────────────────────── seeding ─────────────────────────────

/// The user's files, on shared storage: accented and emoji names on purpose (port plan, finding 4).
final userFiles = <String, List<int>>{
  'Relevé été ✓.txt': utf8.encode('Relevé de l’été 2026 — àéèùç ✓\n'),
  'notes.txt': utf8.encode('notes v1\n'),
  'mes_notes.txt': utf8.encode('mes notes v1\n'),
  'rapport 📊.md': utf8.encode('# Rapport 📊\n'),
  'photo.jpg': pseudo(48 * 1024, 11),
  'à jeter.pdf': pseudo(9 * 1024, 12),
};

/// The vault's files: the three encryption paths of the 2.15.x (< 1 MB in Dart, 1-5 MB in an isolate,
/// >= 5 MB through the native channel), an empty file and an accented name.
final vaultFiles = <String, List<int>>{
  'Relevé été 2026 ✓.txt': utf8.encode('Bonjour — relevé de l’été 2026, accents àéèùç ✓\n'),
  'vide.txt': <int>[],
  'photo-200k.jpg': pseudo(200 * 1024, 1),
  'scan-2M.pdf': pseudo(2 * 1024 * 1024 + 17, 2),
  'gros-6M.bin': pseudo(6 * 1024 * 1024 + 3, 3),
};

Future<void> seedUserData(SharedPreferences prefs) async {
  await Directory(userDir).create(recursive: true);
  for (final e in userFiles.entries) {
    await File('$userDir/${e.key}').writeAsBytes(e.value);
  }
  final trashDir = Directory('$userDir/dossier à jeter');
  await Directory('${trashDir.path}/sous dossier').create(recursive: true);
  await File('${trashDir.path}/un.txt').writeAsString('un\n');
  await File('${trashDir.path}/sous dossier/deux.bin').writeAsBytes(pseudo(3000, 13));

  // main.dart:45 and :310; the output settings through their service.
  await prefs.setBool('permissions_asked', true);
  await prefs.setString('theme_mode', 'dark');
  await OutputStorageService().setBasePath('$sharedRoot/Files Tech');
  await OutputStorageService().setAutoShare(true);
  // Written by files_tech_core's update check up to 2.15.2; an orphan after 2.15.3 (INVENTAIRE §9 bis).
  await prefs.setInt('update_last_check_ms_read-files-tech', DateTime.now().millisecondsSinceEpoch);

  // Recents through the core's service: lastOpened carries microseconds; one favourite.
  const recents = RecentFilesService();
  var current = await recents.load();
  for (final name in ['Relevé été ✓.txt', 'notes.txt', 'mes_notes.txt', 'rapport 📊.md', 'photo.jpg']) {
    current = await recents.addOrUpdate(current, '$userDir/$name');
    await Future<void>.delayed(const Duration(milliseconds: 3));
  }
  current = await recents.toggleFavorite(current, '$userDir/notes.txt');
  await addDismissed('$userDir/mes_notes.txt');
  await addDismissed('$sharedRoot/Download/ancien é.pdf');

  // The editor's history: notes.txt and mes_notes.txt collide under the 2.15.x filter (D6).
  for (final (name, saves) in [('notes.txt', 3), ('mes_notes.txt', 2), ('rapport 📊.md', 1)]) {
    for (var i = 1; i <= saves; i++) {
      await editorBackup('$userDir/$name', name);
      await File('$userDir/$name').writeAsString('$name v${i + 1}\n');
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
  }

  // "Extract all" of a real archive.
  final archive = Archive()
    ..addFile(ArchiveFile('a.txt', 6, utf8.encode('alpha\n')))
    ..addFile(ArchiveFile('sous dossier/b.bin', 4000, pseudo(4000, 14)));
  final zipPath = '$userDir/archive été.zip';
  await File(zipPath).writeAsBytes(ZipEncoder().encode(archive));
  await extractAll(zipPath, 'archive été.zip');

  // The trash: a file and a folder.
  await TrashService().moveToTrash(File('$userDir/à jeter.pdf'));
  await TrashService().moveToTrash(trashDir);
}

/// Imports [files] through importFileSafe, from the cache, as a picked file would be.
Future<void> importAll(VaultService vault, Map<String, List<int>> files) async {
  final tmp = await getTemporaryDirectory();
  for (final e in files.entries) {
    final f = File('${tmp.path}/${e.key}');
    await f.writeAsBytes(e.value);
    await vault.importFileSafe(f, overwrite: true);
    await f.delete();
    log('import ${e.key} ${e.value.length} ${sha(e.value)}');
  }
}

/// D1 of the 2.15.2: a lock during an import encrypts the file with a zeroed key. Checked, not assumed.
Future<String> zeroKeyFile(VaultService vault, String name, List<int> data) async {
  final tmp = await getTemporaryDirectory();
  final f = File('${tmp.path}/$name');
  await f.writeAsBytes(data);
  final pending = vault.importFileSafe(f, overwrite: true);
  vault.lock();
  final encPath = await pending;
  await f.delete();
  await vault.unlockWithPassword(vaultPassword);
  final plain = decryptAuto(
    blob: await File(encPath).readAsBytes(),
    key: Uint8List(32),
    filename: encPath.split('/').last,
    v2Only: true,
  );
  if (sha(plain) != sha(data)) throw StateError('D1 not reproduced for $name');
  log('zero-key file $name');
  return name;
}

/// The plain copies the 2.15.x leaves in its cache when the process dies before its own purge
/// (INVENTAIRE §5.2): a share from the vault, share_plus's staging, file_picker and image_picker
/// copies, the EXIF and signature temporaries, and a shared ZIP entry under its own name.
Future<void> cacheResidues() async {
  final tmp = (await getTemporaryDirectory()).path;
  final plain = utf8.encode('Bonjour — relevé de l’été 2026, accents àéèùç ✓\n');
  for (final path in [
    'vault_decrypt/9f8e7d6c5b4a/Relevé été 2026 ✓.txt',
    'share_plus/Relevé été 2026 ✓.txt',
    'file_picker/1791720000000/photo.jpg',
    'image_picker4242424242424242.jpg',
    'photo_no_exif.jpg',
    'contrat_signe.pdf',
    'Facture été.pdf',
  ]) {
    final f = File('$tmp/$path');
    await f.parent.create(recursive: true);
    await f.writeAsBytes(plain);
  }
}

/// A vault as a 2.5.x left it: PBKDF2 (no vault_kdf_version), no vault_v2_only, a v1 sentinel, a v1
/// file next to a v2 one (2.5.5 started v2). Written with the 2.15.x's own KDF and blob functions; the
/// oracle is the 2.15.x opening it (INVENTAIRE §5.3).
Future<Map<String, List<int>>> legacyVault(SharedPreferences prefs) async {
  final rnd = Random.secure();
  Uint8List random(int n) => Uint8List.fromList(List.generate(n, (_) => rnd.nextInt(256)));
  final salt = random(16);
  final key = derivePbkdf2(vaultPassword, salt);
  Uint8List v1(List<int> plain) {
    final nonce = random(12);
    final ct = gcmProcess(forEncryption: true, input: Uint8List.fromList(plain), key: key, nonce: nonce, aad: Uint8List(0));
    return (BytesBuilder()
          ..add(nonce)
          ..add(ct))
        .toBytes();
  }

  final dir = Directory('${(await getApplicationDocumentsDirectory()).path}/vault');
  await dir.create(recursive: true);
  final files = <String, List<int>>{
    'ancien v1 été.txt': utf8.encode('écrit par une 2.5.4 — v1, AAD vide\n'),
    'récent v2.bin': pseudo(300 * 1024, 31),
  };
  await File('${dir.path}/_check.enc').writeAsBytes(v1(utf8.encode('read_files_tech_vault_v1')));
  await File('${dir.path}/ancien v1 été.txt.enc').writeAsBytes(v1(files['ancien v1 été.txt']!));
  await File('${dir.path}/récent v2.bin.enc').writeAsBytes(
    encryptV2(plain: files['récent v2.bin']!, key: key, nonce: random(12), filename: 'récent v2.bin.enc'),
  );
  await prefs.setString('vault_salt_v1', base64Encode(salt));
  await prefs.setBool('vault_setup_v1', true);
  return files;
}

/// Fails until [counterKey] reaches [target]: a refused attempt (lockout running) waits and retries,
/// so the counter climbs the tiers as a user's would (1, 2, then 4 minutes at the seventh).
Future<void> failUntil(SharedPreferences prefs, String counterKey, int target, Future<Object?> Function(int) attempt) async {
  var i = 0;
  while ((prefs.getInt(counterKey) ?? 0) < target) {
    final before = prefs.getInt(counterKey) ?? 0;
    i++;
    try {
      log('attempt $i -> ${await attempt(i)}');
    } catch (e) {
      log('attempt $i: $e');
    }
    await prefs.reload();
    if ((prefs.getInt(counterKey) ?? 0) == before) await Future<void>.delayed(const Duration(seconds: 5));
  }
}

/// Four wrong passwords: below the first lockout (fifth failure), the counter at 4.
Future<void> wrongUnlocks(VaultService vault, int count) async {
  vault.lock();
  for (var i = 1; i <= count; i++) {
    log('wrong unlock $i -> ${await vault.unlockWithPassword('mauvais $i')}');
  }
}

// ───────────────────────────── the oracle ─────────────────────────────

Future<Map<String, Object?>> prefsAsDartReadsThem(SharedPreferences prefs) async {
  await prefs.reload();
  final keys = prefs.getKeys().toList()..sort();
  return {
    for (final k in keys) k: {'type': typeOf(prefs.get(k)), 'value': prefs.get(k)},
  };
}

Future<Map<String, Object?>> recentsAsDartReadsThem(SharedPreferences prefs) async {
  const service = RecentFilesService();
  final loaded = await service.load(); // first: it may rewrite the list
  final raw = prefs.getStringList('recent_files');
  final stored = raw?.map((s) {
    try {
      return RecentFile.fromJsonString(s).toJson();
    } catch (_) {
      return {'unreadable': s};
    }
  }).toList();
  final dismissed = (prefs.getStringList('recents_dismissed_paths') ?? const <String>[]).toList()..sort();
  return {'stored': stored, 'loaded': [for (final f in loaded) f.path], 'dismissed': dismissed};
}

Future<List<Object?>> trashAsDartReadsIt() async => [
  for (final e in await TrashService().list())
    {
      'id': e.id,
      'trashRoot': e.trashRoot,
      'originalPath': e.originalPath,
      'name': e.name,
      'isDir': e.isDir,
      'size': e.size,
      'deletedAt': e.deletedAt.toIso8601String(),
      'payload': await tree('${e.trashRoot}/items/${e.id}/${e.name}'),
    },
];

Future<Map<String, Object?>> history(List<String> names) async {
  final dir = Directory('${(await getApplicationDocumentsDirectory()).path}/history');
  final files = (await dir.exists()) ? (await dir.list().toList()).whereType<File>().toList() : <File>[];
  final legacyName = RegExp(r'^.*_(\d+)\.bak$');
  final legacy = <Map<String, Object?>>[];
  for (final f in files) {
    final name = f.path.split('/').last;
    final m = legacyName.firstMatch(name);
    if (m != null) legacy.add({'name': name, 'savedAtMs': int.parse(m.group(1)!), 'sha256': sha(await f.readAsBytes())});
  }
  legacy.sort((a, b) => (a['name'] as String).compareTo(b['name'] as String));
  // The EXACT name the 2.15.x derived (docs D-021), by Dart's own RegExp: the port must find these.
  final byName = <String, Object?>{};
  for (final n in names) {
    final base = n.replaceAll(RegExp(r'[^\w.]'), '_');
    final exact = RegExp('^${RegExp.escape(base)}_(\\d+)\\.bak\$');
    final mine = legacy.where((c) => exact.hasMatch(c['name'] as String)).toList()
      ..sort((a, b) => (b['savedAtMs'] as int).compareTo(a['savedAtMs'] as int));
    byName[n] = [for (final c in mine) c['name']];
  }
  return {'legacy': legacy, 'byName': byName};
}

Future<List<Object?>> extractions() async {
  final docs = await getApplicationDocumentsDirectory();
  const suffix = '_extracted';
  final out = <Map<String, Object?>>[];
  for (final e in await docs.list().toList()) {
    final name = e.path.split('/').last;
    final isDir = await FileSystemEntity.type(e.path, followLinks: false) == FileSystemEntityType.directory;
    if (!isDir || !name.endsWith(suffix) || name.length <= suffix.length) continue;
    final content = await tree(e.path);
    final files = (content['files'] as Map).values.length;
    var bytes = 0;
    await for (final f in Directory(e.path).list(recursive: true, followLinks: false)) {
      if (f is File) bytes += await f.length();
    }
    out.add({'dir': name, 'archiveName': name.substring(0, name.length - suffix.length), 'files': files, 'bytes': bytes, 'content': content});
  }
  out.sort((a, b) => (a['dir'] as String).compareTo(b['dir'] as String));
  return out;
}

/// The vault as the 2.15.x reads it — each file decrypted by its own path, or the error it gives.
Future<Map<String, Object?>> vaultReads(VaultService vault) async {
  final out = <String, Object?>{};
  for (final f in await vault.listFiles()) {
    final name = f.path.split('/').last.replaceAll(RegExp(r'\.enc$'), '');
    try {
      out[name] = await fileDigest(await vault.decryptToTemp(f));
    } catch (e) {
      out[name] = {'error': e.runtimeType.toString()};
    }
  }
  return out;
}

const lockoutKeys = {
  'unlock': ['vault_unlock_fails', 'vault_lockout_until_ms', 'vault_lockout_until_elapsed', 'vault_lockout_armed_elapsed'],
  'backup': ['vault_backup_fails', 'vault_backup_lockout_until_ms', 'vault_backup_lockout_until_elapsed', 'vault_backup_lockout_armed_elapsed'],
};

// ───────────────────────────── main ─────────────────────────────

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final support = await getApplicationSupportDirectory();
  final work = Directory('${support.path}/rft_seed');
  final expected = <String, Object?>{};
  try {
    final request = jsonDecode(await File('${work.path}/scenario.json').readAsString()) as Map<String, dynamic>;
    final scenario = request['scenario'] as String;
    final tag = request['tag'] as String;
    log('scenario $scenario on $tag');
    final prefs = await SharedPreferences.getInstance();
    final vault = VaultService.instance;
    final imported = <String, Object?>{};
    final backups = <Object?>[];
    final zeroKey = <String>[];

    if (scenario == 'full') {
      await seedUserData(prefs);
      await vault.setupWithPassword(vaultPassword);
      await importAll(vault, vaultFiles);
      vaultFiles.forEach((k, v) => imported[k] = {'size': v.length, 'sha256': sha(v)});
      final backup = await vault.exportToBackup(exportPassword: backupPassword);
      backups.add({'path': backup.path, 'entries': Map.of(imported)});
      log('backup ${backup.path}');
      if (tag == '2.15.2') {
        final data = pseudo(2 * 1024 * 1024, 21);
        zeroKey.add(await zeroKeyFile(vault, 'clé-nulle 2M.bin', data));
        imported['clé-nulle 2M.bin'] = {'size': data.length, 'sha256': sha(data)};
      }
      expected['vaultReads'] = await vaultReads(vault);
      await wrongUnlocks(vault, 4);
      await cacheResidues(); // after the lock, which purges the 2.15.x's own copies
    } else if (scenario == 'legacy') {
      final files = await legacyVault(prefs);
      files.forEach((k, v) => imported[k] = {'size': v.length, 'sha256': sha(v)});
      log('legacy unlock -> ${await vault.unlockWithPassword(vaultPassword)}');
      expected['vaultReads'] = await vaultReads(vault);
      expected['legacy'] = {'kdf': 'pbkdf2', 'sentinel': 'v1', 'v1Files': ['ancien v1 été.txt']};
      vault.lock();
    } else if (scenario == 'backup-lockout') {
      await vault.setupWithPassword(vaultPassword);
      final files = {'Relevé été 2026 ✓.txt': vaultFiles['Relevé été 2026 ✓.txt']!, 'scan-2M.pdf': vaultFiles['scan-2M.pdf']!};
      await importAll(vault, files);
      files.forEach((k, v) => imported[k] = {'size': v.length, 'sha256': sha(v)});
      final backup = await vault.exportToBackup(exportPassword: backupPassword);
      backups.add({'path': backup.path, 'entries': Map.of(imported)});
      expected['vaultReads'] = await vaultReads(vault);
      await failUntil(prefs, 'vault_backup_fails', 7, (i) => vault.restoreFromBackup(backupFile: backup, exportPassword: 'mauvaise $i'));
      vault.lock();
    } else if (scenario == 'unlock-lockout') {
      await vault.setupWithPassword(vaultPassword);
      final files = {'Relevé été 2026 ✓.txt': vaultFiles['Relevé été 2026 ✓.txt']!};
      await importAll(vault, files);
      files.forEach((k, v) => imported[k] = {'size': v.length, 'sha256': sha(v)});
      expected['vaultReads'] = await vaultReads(vault);
      vault.lock();
      await failUntil(prefs, 'vault_unlock_fails', 7, (i) => vault.unlockWithPassword('mauvais $i'));
    } else {
      throw StateError('unknown scenario $scenario');
    }

    expected['seed'] = {
      'tag': tag,
      'scenario': scenario,
      'wallMs': DateTime.now().millisecondsSinceEpoch,
      'monoMs': await MonotonicClock.elapsedRealtimeMs(),
      'bootId': await bootId(),
    };
    expected['prefs'] = await prefsAsDartReadsThem(prefs);
    expected['recents'] = await recentsAsDartReadsThem(prefs);
    expected['trash'] = await trashAsDartReadsIt();
    expected['history'] = await history(['notes.txt', 'mes_notes.txt', 'rapport 📊.md', 'Relevé été ✓.txt']);
    expected['extractions'] = await extractions();
    expected['vault'] = {
      'setUp': await vault.isSetup(),
      'imported': imported,
      'zeroKey': zeroKey,
      'backups': backups,
      'lockouts': {
        for (final e in lockoutKeys.entries) e.key: {for (final k in e.value) k: prefs.getInt(k)},
      },
    };
    await work.create(recursive: true);
    await File('${work.path}/expected.json').writeAsString(const JsonEncoder.withIndent(' ').convert(expected));
    log('DONE ok');
    await File('${work.path}/status.txt').writeAsString('ok');
  } catch (e, st) {
    log('DONE failed $e\n$st');
    await work.create(recursive: true);
    await File('${work.path}/status.txt').writeAsString('failed: $e\n$st');
  }
  runApp(MaterialApp(home: Scaffold(body: SafeArea(child: SelectableText(lines.join('\n'))))));
}
