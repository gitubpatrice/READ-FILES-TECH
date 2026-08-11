import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/duplicate_finder_service.dart';

/// Premier test du détecteur de doublons. Le service n'en avait **aucun** avant
/// le 2026-08-11, alors qu'il pilote un isolate qui parcourt le stockage et
/// calcule des empreintes.
///
/// Il portait le même défaut de course que son service jumeau, corrigé là-bas
/// le 2026-08-02 et jamais reporté ici : `_iso = await Isolate.spawn(...)`
/// pouvait affecter un isolate **déjà orphelin**, que plus personne ne tuerait.
void main() {
  late Directory tmp;
  late DuplicateFinderService service;

  setUp(() {
    tmp = Directory.systemTemp.createTempSync('rft_dup_test');
    service = DuplicateFinderService();
  });

  tearDown(() {
    service.cancel();
    try {
      tmp.deleteSync(recursive: true);
    } catch (_) {}
  });

  void write(String name, String content) {
    final f = File('${tmp.path}/$name');
    f.parent.createSync(recursive: true);
    f.writeAsStringSync(content);
  }

  /// Le seuil par défaut est de 4 Ko : en dessous, rien n'est comparé.
  String gros(String graine) {
    final b = StringBuffer();
    while (b.length < 8192) {
      b.write(graine);
    }
    return b.toString();
  }

  test('repère deux fichiers identiques', () async {
    write('a.txt', gros('meme contenu '));
    write('copie/b.txt', gros('meme contenu '));
    write('autre.txt', gros('different '));

    final r = await service.find(root: tmp.path);

    expect(r.duplicates, isNotEmpty);
    final noms = r.duplicates.first.files
        .map((f) => f.path.split(RegExp(r'[/\\]')).last)
        .toSet();
    expect(noms, {'a.txt', 'b.txt'});
  });

  test('ne groupe pas des fichiers différents', () async {
    write('x.txt', gros('aaa '));
    write('y.txt', gros('bbb '));
    final r = await service.find(root: tmp.path);
    expect(r.duplicates, isEmpty);
  });

  test('un dossier vide se termine, il ne pend pas', () async {
    // C'est le cas qui déclenchait la course : l'isolate répond avant même que
    // `Isolate.spawn` ne se résolve. Le `timeout` transforme une attente
    // infinie en échec explicite plutôt qu'en test suspendu.
    final r = await service
        .find(root: tmp.path)
        .timeout(const Duration(seconds: 20));
    expect(r.duplicates, isEmpty);
  });

  test('un dossier introuvable rend une erreur, pas un silence', () async {
    await expectLater(
      service.find(root: '${tmp.path}/nexiste_pas'),
      throwsA(anything),
    );
  });

  test('deux recherches successives donnent le même résultat', () async {
    write('a.txt', gros('z '));
    write('b.txt', gros('z '));

    final premiere = await service.find(root: tmp.path);
    final seconde = await service
        .find(root: tmp.path)
        .timeout(const Duration(seconds: 20));

    expect(premiere.duplicates.length, 1);
    expect(seconde.duplicates.length, premiere.duplicates.length);
  });

  test('un appel concurrent ne laisse pas le premier suspendu', () async {
    // Avant le 2026-08-11, le second appel écrasait `_iso` et `_recv` : le
    // premier isolate perdait sa référence et continuait de hacher, son port
    // restait ouvert, et son `Completer` ne se complétait JAMAIS — l'appelant
    // attendait indéfiniment.
    //
    // Le premier appel doit désormais se conclure d'une façon ou d'une autre —
    // résultat ou erreur — mais jamais rester en suspens.
    for (var i = 0; i < 40; i++) {
      write('lot/f_$i.txt', gros('contenu $i '));
      write('lot/g_$i.txt', gros('contenu $i '));
    }

    final premiere = service.find(root: tmp.path);
    final seconde = service.find(root: tmp.path);

    await expectLater(
      Future.wait([
        premiere.then<void>((_) {}, onError: (_) {}),
        seconde.then<void>((_) {}, onError: (_) {}),
      ]).timeout(const Duration(seconds: 25)),
      completes,
    );
  });
}
