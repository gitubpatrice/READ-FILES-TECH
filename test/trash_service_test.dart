import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/trash_service.dart';

/// Tests de la corbeille. `fallbackBase` pointe sur un dossier temporaire :
/// aucun plugin `path_provider` n'est sollicité, et les chemins testés ne
/// ressemblent pas à un volume Android (donc repli sur cette base).
void main() {
  late Directory tmp;
  late Directory work;
  late TrashService trash;

  setUp(() {
    tmp = Directory.systemTemp.createTempSync('rft_trash_test');
    work = Directory('${tmp.path}/work')..createSync(recursive: true);
    trash = TrashService(fallbackBase: tmp.path);
  });

  tearDown(() {
    try {
      tmp.deleteSync(recursive: true);
    } catch (_) {}
  });

  File makeFile(String name, String content) {
    final f = File('${work.path}/$name')..writeAsStringSync(content);
    return f;
  }

  test(
    'met un fichier à la corbeille et le retire de son emplacement',
    () async {
      final f = makeFile('note.txt', 'hello');
      final entry = await trash.moveToTrash(f);

      expect(f.existsSync(), isFalse);
      expect(File(entry.payloadPath).existsSync(), isTrue);
      expect(File(entry.payloadPath).readAsStringSync(), 'hello');
      expect(File(entry.metaPath).existsSync(), isTrue);
      expect(entry.originalPath, f.path);
      expect(entry.size, 5);
      expect(entry.isDir, isFalse);
    },
  );

  test('list() relit les métadonnées écrites', () async {
    await trash.moveToTrash(makeFile('a.txt', 'a'));
    await trash.moveToTrash(makeFile('b.txt', 'bb'));

    final entries = await trash.list();
    expect(entries.length, 2);
    expect(entries.map((e) => e.name).toSet(), {'a.txt', 'b.txt'});
  });

  test('restore() remet le fichier à son emplacement d\'origine', () async {
    final f = makeFile('doc.txt', 'contenu');
    final entry = await trash.moveToTrash(f);

    final dest = await trash.restore(entry);
    expect(dest, f.path);
    expect(File(dest).readAsStringSync(), 'contenu');
    expect(File(entry.payloadPath).existsSync(), isFalse);
    expect(File(entry.metaPath).existsSync(), isFalse);
    expect(await trash.list(), isEmpty);
  });

  test('restore() ne remplace jamais un fichier existant', () async {
    final f = makeFile('doc.txt', 'ancien');
    final entry = await trash.moveToTrash(f);
    makeFile('doc.txt', 'nouveau');

    final dest = await trash.restore(entry);
    expect(dest, '${work.path}/doc (1).txt');
    expect(File(dest).readAsStringSync(), 'ancien');
    expect(File(f.path).readAsStringSync(), 'nouveau');
  });

  test('restore() recrée le dossier parent disparu', () async {
    final sub = Directory('${work.path}/sub')..createSync();
    final f = File('${sub.path}/x.txt')..writeAsStringSync('x');
    final entry = await trash.moveToTrash(f);
    sub.deleteSync(recursive: true);

    final dest = await trash.restore(entry);
    expect(File(dest).readAsStringSync(), 'x');
  });

  test('un dossier part à la corbeille avec tout son contenu', () async {
    final dir = Directory('${work.path}/photos')..createSync();
    File('${dir.path}/1.txt').writeAsStringSync('un');
    Directory('${dir.path}/nested').createSync();
    File('${dir.path}/nested/2.txt').writeAsStringSync('deux');

    final entry = await trash.moveToTrash(dir);
    expect(dir.existsSync(), isFalse);
    expect(entry.isDir, isTrue);
    expect(
      File('${entry.payloadPath}/nested/2.txt').readAsStringSync(),
      'deux',
    );

    await trash.restore(entry);
    expect(File('${dir.path}/nested/2.txt').readAsStringSync(), 'deux');
  });

  test('deleteForever() efface charge utile et métadonnées', () async {
    final entry = await trash.moveToTrash(makeFile('z.txt', 'z'));
    await trash.deleteForever(entry);

    expect(File(entry.payloadPath).existsSync(), isFalse);
    expect(File(entry.metaPath).existsSync(), isFalse);
    expect(Directory(entry.itemDirPath).existsSync(), isFalse);
    expect(await trash.list(), isEmpty);
  });

  test('emptyAll() vide toutes les entrées', () async {
    await trash.moveToTrash(makeFile('a.txt', 'a'));
    await trash.moveToTrash(makeFile('b.txt', 'b'));

    final r = await trash.emptyAll(await trash.list());
    expect(r.ok, 2);
    expect(r.fail, 0);
    expect(await trash.list(), isEmpty);
  });

  test('list() nettoie une méta orpheline (charge utile disparue)', () async {
    final entry = await trash.moveToTrash(makeFile('orph.txt', 'o'));
    File(entry.payloadPath).deleteSync();

    expect(await trash.list(), isEmpty);
    expect(File(entry.metaPath).existsSync(), isFalse);
  });

  test('la corbeille elle-même ne peut pas être mise à la corbeille', () async {
    final self = Directory('${tmp.path}/${TrashService.trashDirName}')
      ..createSync(recursive: true);
    expect(() => trash.moveToTrash(self), throwsA(isA<FileSystemException>()));
  });

  test('une méta falsifiée avec un id de traversée est ignorée', () async {
    // Les métadonnées vivent en clair sur le stockage partagé : on simule une
    // réécriture par un tiers pour vérifier qu'aucune entrée forgée ne remonte.
    final victim = makeFile('victime.txt', 'ne pas toucher');
    final entry = await trash.moveToTrash(makeFile('vrai.txt', 'v'));
    File(entry.metaPath).writeAsStringSync(
      jsonEncode({
        'id': '../..',
        'originalPath': '${work.path}/ailleurs.txt',
        'name': 'victime.txt',
        'isDir': false,
        'size': 1,
        'deletedAt': DateTime.now().toIso8601String(),
      }),
    );

    expect(await trash.list(), isEmpty);
    expect(victim.readAsStringSync(), 'ne pas toucher');
  });

  test(
    'une méta dont le nom de fichier ne correspond pas à l\'id est ignorée',
    () async {
      final entry = await trash.moveToTrash(makeFile('x.txt', 'x'));
      final content = File(entry.metaPath).readAsStringSync();
      File(
        '${entry.trashRoot}/${TrashService.metaDir}/intrus.json',
      ).writeAsStringSync(content);

      final entries = await trash.list();
      expect(entries.length, 1, reason: 'seule la méta légitime doit remonter');
    },
  );

  test('restore() refuse un chemin d\'origine relatif ou avec ..', () async {
    final entry = await trash.moveToTrash(makeFile('r.txt', 'r'));
    final forged = TrashEntry(
      id: entry.id,
      trashRoot: entry.trashRoot,
      originalPath: '${work.path}/../evade.txt',
      name: entry.name,
      isDir: false,
      size: 1,
      deletedAt: entry.deletedAt,
    );
    expect(() => trash.restore(forged), throwsA(isA<FileSystemException>()));
  });

  test('un fichier inexistant lève au lieu de créer une entrée vide', () async {
    expect(
      () => trash.moveToTrash(File('${work.path}/fantome.txt')),
      throwsA(isA<FileSystemException>()),
    );
  });
}
