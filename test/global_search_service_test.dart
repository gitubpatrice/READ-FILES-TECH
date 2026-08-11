import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/global_search_service.dart';

/// Premier test de la recherche globale. Le service n'en avait **aucun** avant
/// le 2026-08-11, alors qu'il pilote un isolate, un port d'annulation et un
/// parcours récursif du stockage — trois sources de pannes silencieuses.
///
/// La relecture externe du 2026-08-11 y a trouvé trois défauts, tous confirmés
/// dans le code avant correction :
///
///  1. un dossier illisible interrompait **toute** la recherche ;
///  2. le port d'annulation était câblé à l'envers, si bien que le drapeau
///     `cancelled` du worker ne pouvait jamais passer à `true` ;
///  3. ce même port n'était jamais fermé — une fuite par recherche.
void main() {
  late Directory tmp;
  late GlobalSearchService service;

  setUp(() {
    tmp = Directory.systemTemp.createTempSync('rft_search_test');
    service = GlobalSearchService();
  });

  tearDown(() {
    service.cancel();
    try {
      tmp.deleteSync(recursive: true);
    } catch (_) {}
  });

  File write(String relative, String content) {
    final f = File('${tmp.path}/$relative');
    f.parent.createSync(recursive: true);
    f.writeAsStringSync(content);
    return f;
  }

  Future<List<SearchHit>> collect(SearchQuery q) async {
    final hits = <SearchHit>[];
    await for (final e in service.search(q)) {
      if (e is SearchHit) hits.add(e);
    }
    return hits;
  }

  test('trouve par nom, sans descendre dans les fichiers cachés', () async {
    write('rapport_2026.txt', 'peu importe');
    write('autre.txt', 'peu importe');
    write('.cache/rapport_secret.txt', 'ignoré car caché');

    final hits = await collect(
      SearchQuery(rootPath: tmp.path, namePattern: 'rapport'),
    );

    expect(hits.map((h) => h.path.split(RegExp(r'[/\\]')).last), [
      'rapport_2026.txt',
    ]);
  });

  test('trouve par contenu et rend un extrait', () async {
    write('a.txt', 'rien ici');
    write('b.txt', 'ligne 1\nle mot de passe est ailleurs\nligne 3');

    final hits = await collect(
      SearchQuery(rootPath: tmp.path, contentPattern: 'mot de passe'),
    );

    expect(hits.length, 1);
    expect(hits.first.snippet, contains('mot de passe'));
  });

  test('descend dans les sous-dossiers', () async {
    write('n1/n2/n3/profond.txt', 'x');
    final hits = await collect(
      SearchQuery(rootPath: tmp.path, namePattern: 'profond'),
    );
    expect(hits.length, 1);
  });

  test('maxResults borne réellement le nombre de résultats', () async {
    for (var i = 0; i < 20; i++) {
      write('doc_$i.txt', 'x');
    }
    final hits = await collect(
      SearchQuery(rootPath: tmp.path, namePattern: 'doc_', maxResults: 5),
    );
    expect(hits.length, lessThanOrEqualTo(5));
  });

  test('un dossier source absent est signalé, pas silencieux', () async {
    Object? erreur;
    await service
        .search(SearchQuery(rootPath: '${tmp.path}/nexiste_pas'))
        .forEach((_) {})
        .catchError((e) => erreur = e);
    expect(erreur, isNotNull);
  });

  test('le flux se termine, il ne reste pas suspendu', () async {
    // Sans fermeture du `StreamController`, l'écran appelant resterait
    // indéfiniment en état « recherche en cours ». Le `timeout` transforme une
    // attente infinie en échec explicite plutôt qu'en test qui pend.
    write('x.txt', 'y');
    await collect(
      SearchQuery(rootPath: tmp.path, namePattern: 'x'),
    ).timeout(const Duration(seconds: 20));
  });

  test('deux recherches successives donnent le même résultat', () async {
    // La seconde reutilise le service : si un port, un isolate ou le jeton de
    // course restait accroche a la premiere, elle rendrait un resultat
    // different — ou ne se terminerait pas.
    write('cible.txt', 'z');
    final q = SearchQuery(rootPath: tmp.path, namePattern: 'cible');

    final premiere = await collect(q);
    final seconde = await collect(q);

    expect(premiere.length, 1);
    expect(seconde.length, premiere.length);
  });

  test('annuler pendant la recherche ferme le flux sans lever', () async {
    for (var i = 0; i < 200; i++) {
      write('lot/f_$i.txt', 'contenu $i');
    }
    final stream = service.search(
      SearchQuery(rootPath: tmp.path, namePattern: 'f_'),
    );
    final sub = stream.listen((_) {});
    await Future<void>.delayed(const Duration(milliseconds: 30));
    await sub.cancel();
    // Rien ne doit lever, et le service doit être réutilisable ensuite.
    final apres = await collect(
      SearchQuery(rootPath: tmp.path, namePattern: 'f_1'),
    ).timeout(const Duration(seconds: 20));
    expect(apres, isNotEmpty);
  });
}
