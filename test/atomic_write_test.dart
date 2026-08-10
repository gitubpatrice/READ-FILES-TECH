import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/utils/atomic_write.dart';

/// L'écriture atomique est le point d'écriture du coffre, de la corbeille, des
/// conversions et des sauvegardes. Elle n'avait aucun test.
///
/// Le défaut trouvé par la relecture GPT du 2026-08-10 : le fichier temporaire
/// s'appelait `'$path.tmp'`, un nom **fixe** dérivé de la seule cible. Deux
/// écritures concurrentes vers le même chemin partageaient donc le même
/// temporaire, et le fichier final pouvait finir mélangé ou tronqué.
void main() {
  late Directory dir;

  setUp(() {
    dir = Directory.systemTemp.createTempSync('rft_atomic_');
  });

  tearDown(() {
    if (dir.existsSync()) dir.deleteSync(recursive: true);
  });

  String p(String name) => '${dir.path}/$name';

  test('le contenu ecrit est bien celui relu', () async {
    await atomicWriteString(p('a.txt'), 'bonjour');
    expect(File(p('a.txt')).readAsStringSync(), 'bonjour');
  });

  test('une reecriture remplace entierement l ancien contenu', () async {
    // Un `rename` remplace ; il ne fusionne pas. Si le fichier final gardait
    // la queue de l'ancien contenu, une ecriture plus courte laisserait des
    // octets du precedent — corruption silencieuse.
    await atomicWriteString(p('b.txt'), 'un contenu tres long a remplacer');
    await atomicWriteString(p('b.txt'), 'court');
    expect(File(p('b.txt')).readAsStringSync(), 'court');
  });

  test('aucun fichier .tmp ne subsiste apres une ecriture reussie', () async {
    await atomicWriteString(p('c.txt'), 'x');
    final restes = dir.listSync().where((e) => e.path.endsWith('.tmp'));
    expect(restes, isEmpty);
  });

  test('deux ecritures concurrentes ne se melangent pas', () async {
    // LE test de ce fichier. Avec le nom de temporaire fixe, les deux appels
    // ecrivaient dans le meme `.tmp` : le fichier final pouvait contenir un
    // melange des deux contenus, ou une version tronquee de l'un.
    //
    // Contenus de longueurs tres differentes : un melange se verrait, la ou
    // deux chaines de meme taille pourraient donner un resultat plausible.
    final a = 'A' * 200000;
    final b = 'B' * 50;
    await Future.wait([
      atomicWriteString(p('race.txt'), a),
      atomicWriteString(p('race.txt'), b),
    ]);

    final lu = File(p('race.txt')).readAsStringSync();
    expect(
      lu == a || lu == b,
      isTrue,
      reason:
          'le fichier fait ${lu.length} octets et ne vaut ni l un ni l autre : '
          'les deux ecritures se sont melangees.',
    );
  });

  test('dix ecritures concurrentes laissent un fichier coherent et zero '
      'residu', () async {
    final contenus = List.generate(10, (i) => '$i' * (1000 * (i + 1)));

    // Les echecs de `rename` sont TOLERES ici, et il faut dire pourquoi
    // plutot que de les masquer : sous Windows, renommer par-dessus un
    // fichier qu'un autre writer tient encore ouvert echoue avec
    // « Acces refuse ». Sous POSIX — donc sur Android, la seule cible reelle —
    // `rename` est atomique et remplace sans se plaindre.
    //
    // La suite s'execute sur la machine de developpement : ce test mesure donc
    // les semantiques de Windows, pas celles de la cible. Ce qu'il verifie
    // reste valable partout : qu'AUCUNE ecriture ne se melange a une autre, et
    // qu'aucun temporaire ne survit. C'est la propriete que le nom fixe
    // `'$path.tmp'` ne tenait pas.
    var echecs = 0;
    await Future.wait(
      contenus.map(
        (c) => atomicWriteString(p('m.txt'), c).catchError((_) => echecs++),
      ),
    );
    expect(
      echecs,
      lessThan(contenus.length),
      reason: 'les dix ecritures ont echoue : ce n est plus un test de course',
    );

    final lu = File(p('m.txt')).readAsStringSync();
    expect(
      contenus,
      contains(lu),
      reason:
          'le fichier fait ${lu.length} octets et ne correspond a aucune des '
          'dix ecritures : elles se sont melangees.',
    );

    final restes = dir.listSync().where((e) => e.path.endsWith('.tmp'));
    expect(restes, isEmpty, reason: 'des temporaires ont survecu');
  });

  test(
    'une ecriture vers un dossier inexistant leve et ne laisse rien',
    () async {
      final cible = '${dir.path}/absent/x.txt';
      await expectLater(atomicWriteString(cible, 'x'), throwsA(anything));
      final restes = dir.listSync().where((e) => e.path.endsWith('.tmp'));
      expect(restes, isEmpty);
    },
  );

  test('les octets binaires font un aller-retour exact', () async {
    final bytes = List<int>.generate(512, (i) => i & 0xFF);
    await atomicWriteBytes(p('d.bin'), bytes);
    expect(File(p('d.bin')).readAsBytesSync(), bytes);
  });
}
