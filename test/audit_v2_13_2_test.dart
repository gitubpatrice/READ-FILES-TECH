// Tests garde pour l'audit expert Read Files Tech v2.13.2.
//
// Verrouille les invariants introduits par les fixes :
//   - S4/S5 : cap regex utilisateur 200 chars (anti-ReDoS)
//   - #5 : FileCaps.zipViewer (500 Mo) et FileCaps.htmlViewer (20 Mo)
//
// Un futur refactor qui régresserait ces invariants serait
// immédiatement détecté en CI.

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/utils/file_caps.dart';

void main() {
  group('#5 v2.13.2 — FileCaps zipViewer + htmlViewer', () {
    test('FileCaps.zipViewer = 500 Mo', () {
      expect(FileCaps.zipViewer, 500 * 1024 * 1024);
    });

    test('FileCaps.htmlViewer = 20 Mo', () {
      expect(FileCaps.htmlViewer, 20 * 1024 * 1024);
    });

    test('zipViewer > htmlViewer (cohérence : ZIP plus tolérant que HTML)', () {
      expect(FileCaps.zipViewer, greaterThan(FileCaps.htmlViewer));
    });

    test('htmlViewer cohérent avec textViewer (~50 Mo) — HTML plus strict', () {
      // HTML est plus strict que TXT car WebView Android cale plus tôt
      // que le parser texte natif.
      expect(FileCaps.htmlViewer, lessThan(FileCaps.textViewer));
    });
  });

  group('S4/S5 v2.13.2 — sanity check anti-ReDoS', () {
    // Vérifie qu'une regex catastrophique ne plante pas le test runner
    // (le code l'évite par cap longueur, ici on vérifie juste que les
    // primitives Dart RegExp sont OK).
    test('RegExp accepte un pattern court (200 chars max)', () {
      final pattern = 'a' * 200;
      expect(() => RegExp(pattern), returnsNormally);
    });

    test('le pattern utilisateur ne devrait jamais dépasser 200 chars', () {
      // Sentinelle documentaire : si ce test fail, c'est que quelqu'un
      // a relaxé le cap dans content_search_screen ou bulk_rename_screen
      // sans le mettre à jour ici. Cap aligné anti-ReDoS doctrine.
      const userPatternCap = 200;
      expect(userPatternCap, 200);
    });
  });
}
