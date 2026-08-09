import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/screens/explorer/services/batch_ops_service.dart';

/// V-M1 (audit 2026-08-02) — copie et déplacement par lot écrasaient
/// silencieusement un homonyme ; en mode `move`, la source était ensuite
/// supprimée, donc **les deux versions** disparaissaient.
///
/// Ces tests falsifient sur un vrai système de fichiers : ils écrivent, ils
/// relisent, et ils vérifient le contenu — pas seulement l'absence
/// d'exception. Un test qui se contenterait de compter `ok`/`fail` resterait
/// vert alors que le fichier de l'utilisateur aurait été détruit.
void main() {
  late Directory tmp;
  late Directory src;
  late Directory dest;
  late BatchOpsService ops;

  setUp(() {
    tmp = Directory.systemTemp.createTempSync('rft_batch_test');
    src = Directory('${tmp.path}/src')..createSync(recursive: true);
    dest = Directory('${tmp.path}/dest')..createSync(recursive: true);
    ops = BatchOpsService();
  });

  tearDown(() {
    try {
      tmp.deleteSync(recursive: true);
    } catch (_) {}
  });

  File write(Directory dir, String name, String content) =>
      File('${dir.path}/$name')..writeAsStringSync(content);

  test(
    'copier vers un homonyme ne détruit pas le fichier de destination',
    () async {
      write(src, 'rapport.txt', 'SOURCE');
      final victim = write(dest, 'rapport.txt', 'DOCUMENT DE L\'UTILISATEUR');

      final res = await ops.copyAll(
        ['${src.path}/rapport.txt'],
        dest.path,
        move: false,
      );

      expect(res.ok, 1);
      expect(
        victim.readAsStringSync(),
        'DOCUMENT DE L\'UTILISATEUR',
        reason: "l'homonyme préexistant doit rester intact",
      );
      expect(File('${dest.path}/rapport (1).txt').readAsStringSync(), 'SOURCE');
    },
  );

  test(
    'déplacer vers un homonyme ne détruit NI la source NI la destination',
    () async {
      write(src, 'photo.jpg', 'NOUVELLE');
      final victim = write(dest, 'photo.jpg', 'ANCIENNE');

      final res = await ops.copyAll(
        ['${src.path}/photo.jpg'],
        dest.path,
        move: true,
      );

      expect(res.ok, 1);
      // Avant correction : `victim` valait « NOUVELLE » et la source était
      // supprimée — « ANCIENNE » n'existait plus nulle part.
      expect(victim.readAsStringSync(), 'ANCIENNE');
      expect(File('${dest.path}/photo (1).jpg').readAsStringSync(), 'NOUVELLE');
      expect(File('${src.path}/photo.jpg').existsSync(), isFalse);
    },
  );

  test('déplacer un fichier dans son propre dossier ne le vide pas', () async {
    final f = write(src, 'notes.md', 'CONTENU IMPORTANT');

    final res = await ops.copyAll(
      ['${src.path}/notes.md'],
      src.path,
      move: true,
    );

    // Cas limite : `copy()` vers le même chemin ouvre la destination en
    // écriture (donc tronque) avant de lire la source — même inode. Le
    // fichier était vidé, puis supprimé par le `move`.
    expect(res.ok, 1);
    final survivors = src
        .listSync()
        .whereType<File>()
        .map((e) => e.readAsStringSync())
        .toList();
    expect(
      survivors,
      contains('CONTENU IMPORTANT'),
      reason: 'le contenu doit exister quelque part après un déplacement',
    );
    expect(
      f.existsSync() ? f.readAsStringSync() : 'CONTENU IMPORTANT',
      'CONTENU IMPORTANT',
    );
  });

  test(
    'plusieurs fichiers de même nom se suffixent au lieu de se remplacer',
    () async {
      final a = Directory('${tmp.path}/a')..createSync();
      final b = Directory('${tmp.path}/b')..createSync();
      write(a, 'doc.txt', 'DE A');
      write(b, 'doc.txt', 'DE B');

      final res = await ops.copyAll(
        ['${a.path}/doc.txt', '${b.path}/doc.txt'],
        dest.path,
        move: false,
      );

      expect(res.ok, 2);
      expect(File('${dest.path}/doc.txt').readAsStringSync(), 'DE A');
      expect(File('${dest.path}/doc (1).txt').readAsStringSync(), 'DE B');
    },
  );

  test(
    'uniqueDestination préserve l\'extension et gère les fichiers cachés',
    () {
      write(dest, 'archive.tar.gz', 'x');
      expect(
        uniqueDestination(dest.path, 'archive.tar.gz'),
        '${dest.path}/archive.tar (1).gz',
      );

      // `.bashrc` n'a pas d'extension : le point initial fait partie du nom.
      write(dest, '.bashrc', 'x');
      expect(
        uniqueDestination(dest.path, '.bashrc'),
        '${dest.path}/.bashrc (1)',
      );

      // Nom libre → inchangé.
      expect(uniqueDestination(dest.path, 'neuf.txt'), '${dest.path}/neuf.txt');
    },
  );

  test('un dossier est refusé et compté en échec, sans rien écrire', () async {
    Directory('${src.path}/dossier').createSync();
    final res = await ops.copyAll(
      ['${src.path}/dossier'],
      dest.path,
      move: false,
    );
    expect(res.ok, 0);
    expect(res.fail, 1);
    expect(dest.listSync(), isEmpty);
  });

  test(
    'deleteAll supprime fichiers et dossiers, et compte les échecs',
    () async {
      write(src, 'a.txt', 'a');
      Directory('${src.path}/d').createSync();
      write(Directory('${src.path}/d'), 'b.txt', 'b');

      final res = await ops.deleteAll([
        '${src.path}/a.txt',
        '${src.path}/d',
        '${src.path}/inexistant.txt',
      ]);

      expect(res.ok, 2);
      expect(res.fail, 1);
      expect(src.listSync(), isEmpty);
    },
  );
}
