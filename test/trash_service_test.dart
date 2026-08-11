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
  // ──────────────────────────────────────────────────────────────────────────
  // Deux pertes de donnees signalees par la relecture GPT du 2026-08-10.
  // Aucune n'etait couverte : les tests existants exercaient la corbeille en
  // sequentiel, alors que les deux defauts n'apparaissent qu'en concurrence.

  group('identifiants — concurrence', () {
    test('dix mises a la corbeille simultanees ont dix identifiants '
        'DISTINCTS', () async {
      // `_newId` testait l'existence du dossier puis rendait l'identifiant ;
      // c'est l'appelant qui le creait, plus tard. Deux suppressions dans la
      // meme milliseconde obtenaient donc le MEME identifiant, et partageaient
      // `items/<id>/`.
      //
      // La consequence n'est pas cosmetique : `deleteForever` finit par un
      // `delete(recursive: true)` sur ce dossier. Supprimer definitivement UN
      // element effacait les DEUX.
      final fichiers = List.generate(
        10,
        (i) => makeFile('doc$i.txt', 'contenu $i'),
      );
      final entrees = await Future.wait(fichiers.map(trash.moveToTrash));

      final ids = entrees.map((e) => e.id).toSet();
      expect(
        ids.length,
        10,
        reason: 'identifiants en collision : ${entrees.map((e) => e.id)}',
      );

      // Et le contenu de chacun doit avoir survecu, distinctement.
      final listees = await trash.list();
      expect(listees.length, 10);
    });

    test(
      'supprimer definitivement une entree n en emporte pas une autre',
      () async {
        final a = makeFile('a.txt', 'AAA');
        final b = makeFile('b.txt', 'BBB');
        final entrees = await Future.wait([
          trash.moveToTrash(a),
          trash.moveToTrash(b),
        ]);

        await trash.deleteForever(entrees.first);

        final restantes = await trash.list();
        expect(
          restantes.length,
          1,
          reason: 'la seconde entree a disparu aussi',
        );
        expect(
          File(restantes.single.payloadPath).readAsStringSync(),
          anyOf('AAA', 'BBB'),
        );
      },
    );
  });

  group('restauration — le chemin de destination est reserve', () {
    test('restaurer n ecrase pas un fichier apparu entre-temps', () async {
      final f = makeFile('note.txt', 'ORIGINAL');
      final entree = await trash.moveToTrash(f);

      // Quelqu'un recree un fichier au meme endroit pendant que l'element est
      // a la corbeille : une autre application, une synchronisation. Ce
      // fichier-la n'a jamais ete supprime — le perdre serait entierement de
      // notre fait.
      File('${work.path}/note.txt').writeAsStringSync('NOUVEAU');

      final dest = await trash.restore(entree);

      expect(
        File('${work.path}/note.txt').readAsStringSync(),
        'NOUVEAU',
        reason: 'le fichier present a ete ecrase par la restauration',
      );
      expect(dest, isNot('${work.path}/note.txt'));
      expect(File(dest).readAsStringSync(), 'ORIGINAL');
    });

    test('deux restaurations simultanees ne se recouvrent pas', () async {
      final a = makeFile('meme.txt', 'PREMIER');
      final e1 = await trash.moveToTrash(a);
      final b = makeFile('meme.txt', 'SECOND');
      final e2 = await trash.moveToTrash(b);

      final dests = await Future.wait([trash.restore(e1), trash.restore(e2)]);

      expect(dests.toSet().length, 2, reason: 'meme destination pour les deux');
      final contenus = dests.map((d) => File(d).readAsStringSync()).toSet();
      expect(contenus, {'PREMIER', 'SECOND'});
    });
  });
  group('metadonnee hostile — plafond de lecture', () {
    // `.RFT_Corbeille/meta/` vit sur le stockage PARTAGE : toute application
    // disposant de la permission peut y deposer un fichier. Avant le
    // 2026-08-11, `list()` faisait `readAsString()` sans borne — un JSON de
    // plusieurs gigaoctets figeait l ouverture de la corbeille, et le mode
    // panique avec elle, puisqu il appelle `list()` avant d effacer.
    //
    // ATTENTION AU PIEGE. Une premiere version de ce test deposait un JSON
    // enorme mais SANS FORME (`{"x":"AAAA…"}`). Il passait — et il passait
    // aussi apres avoir retire la borne, parce que l entree etait ecartee par
    // la validation de schema, pas par le plafond. Il ne prouvait rien.
    //
    // Le fichier ci-dessous est donc une metadonnee PARFAITEMENT VALIDE : meme
    // identifiant que son nom de fichier, charge utile presente sur le disque,
    // tous les champs au bon type. Seule la borne peut la refuser.

    /// Depose une metadonnee valide de [padding] octets, avec sa charge utile.
    Future<String> deposeMetaValide(int padding) async {
      final modele = await trash.moveToTrash(makeFile('gros.txt', 'x'));
      final meta = File(modele.metaPath);
      final json =
          jsonDecode(await meta.readAsString()) as Map<String, dynamic>;
      // Champ inconnu, ignore par `fromJson`, qui ne sert qu a gonfler le
      // fichier sans le rendre invalide.
      json['bourrage'] = 'A' * padding;
      await meta.writeAsString(jsonEncode(json));
      return modele.name;
    }

    test('une metadonnee VALIDE mais enorme est ignoree', () async {
      await trash.moveToTrash(makeFile('vrai.txt', 'ok'));
      await deposeMetaValide(2 * 1024 * 1024);

      final noms = (await trash.list()).map((e) => e.name).toList();

      expect(
        noms,
        ['vrai.txt'],
        reason:
            'la metadonnee de 2 Mo doit etre ecartee par le plafond, et '
            'l entree legitime doit survivre a son voisinage',
      );
    });

    test('la meme metadonnee, sous le plafond, est bien lue', () async {
      // Le controle qui empeche le test precedent de passer pour la mauvaise
      // raison : avec un bourrage modeste, l entree DOIT apparaitre. Sans lui,
      // une borne absurdement basse — ou une validation qui rejette tout —
      // donnerait le meme vert.
      final nom = await deposeMetaValide(1024);
      final noms = (await trash.list()).map((e) => e.name).toList();
      expect(noms, contains(nom));
    });

    test('une metadonnee normale reste lue', () async {
      await trash.moveToTrash(makeFile('petit.txt', 'x'));
      final entries = await trash.list();
      expect(entries.map((e) => e.name), ['petit.txt']);
    });
  });
}
