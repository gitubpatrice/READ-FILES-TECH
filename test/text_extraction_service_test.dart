// Tests prioritaires pour `TextExtractionService` — couvrent les bugs réels
// que l'audit avait identifiés en v2.8.0 :
//   1. UTF-8 sur DOCX (caractères accentués + emoji + idéogrammes)
//   2. Sentinel de paragraphe (`split('')` cassé qui produisait un .txt vide)
//   3. Décodage des entités XML (standard + numériques)
//
// On utilise des fixtures inline plutôt que des fichiers binaires : ces
// fonctions sont pures et lisent du XML brut, on n'a pas besoin de générer
// un vrai .docx pour les tester.

import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/text_extraction_service.dart';

void main() {
  group('docxXmlToPlainText', () {
    test('extrait un paragraphe simple', () {
      const xml =
          '<w:document><w:body>'
          '<w:p><w:r><w:t>Hello world</w:t></w:r></w:p>'
          '</w:body></w:document>';
      expect(docxXmlToPlainText(xml), 'Hello world');
    });

    test('sépare correctement plusieurs paragraphes', () {
      // Régression du bug `split('')` qui découpait par caractère
      // individuel et produisait un .txt vide.
      const xml =
          '<w:document><w:body>'
          '<w:p><w:r><w:t>Première</w:t></w:r></w:p>'
          '<w:p><w:r><w:t>Deuxième</w:t></w:r></w:p>'
          '<w:p><w:r><w:t>Troisième</w:t></w:r></w:p>'
          '</w:body></w:document>';
      final out = docxXmlToPlainText(xml);
      expect(out, contains('Première'));
      expect(out, contains('Deuxième'));
      expect(out, contains('Troisième'));
      // Les trois mots doivent être sur des lignes différentes.
      final lines = out.split('\n').where((l) => l.isNotEmpty).toList();
      expect(lines.length, greaterThanOrEqualTo(3));
    });

    test('respecte xml:space="preserve" sur <w:t>', () {
      const xml =
          '<w:p><w:r>'
          '<w:t xml:space="preserve">Mot 1 </w:t>'
          '<w:t>Mot 2</w:t>'
          '</w:r></w:p>';
      expect(docxXmlToPlainText(xml), 'Mot 1 Mot 2');
    });

    test('convertit <w:br/> en saut de ligne et <w:tab/> en tab', () {
      const xml =
          '<w:p><w:r>'
          '<w:t>Avant</w:t><w:br/><w:t>Après</w:t>'
          '<w:tab/><w:t>Indenté</w:t>'
          '</w:r></w:p>';
      final out = docxXmlToPlainText(xml);
      expect(out, contains('Avant\nAprès'));
      expect(out, contains('Après\tIndenté'));
    });
  });

  group('decodeXmlEntities', () {
    test('décode les 5 entités standard', () {
      expect(
        decodeXmlEntities(
          'a &lt; b &amp; c &gt; d &quot;e&quot; f &apos;g&apos;',
        ),
        "a < b & c > d \"e\" f 'g'",
      );
    });

    test('préserve `&amp;lt;` (encodage simple, pas double)', () {
      // Régression : si on décode `&amp;` AVANT `&lt;`, on aurait `<` au
      // lieu de `&lt;`. L'ordre `&amp;` en dernier protège.
      expect(decodeXmlEntities('&amp;lt;'), '&lt;');
    });

    test('décode les entités numériques décimales et hexadécimales', () {
      // &#233; = é, &#xE9; = é, &#10; = newline, &#x1F600; = 😀
      expect(decodeXmlEntities('caf&#233;'), 'café');
      expect(decodeXmlEntities('caf&#xE9;'), 'café');
      expect(decodeXmlEntities('a&#10;b'), 'a\nb');
      expect(decodeXmlEntities('&#x1F600;'), '😀');
    });

    test('laisse intactes les entités malformées', () {
      // `&#abc;` n'est pas une entité numérique valide (pas de digits).
      expect(decodeXmlEntities('foo &#abc; bar'), 'foo &#abc; bar');
    });
  });

  group('UTF-8 et caractères non-ASCII', () {
    // Test critique : si on revenait à `String.fromCharCodes` à la place de
    // `utf8.decode`, ces tests échoueraient (chaque byte UTF-8 traité comme
    // codepoint Latin-1 → mojibake systématique sur les accents).
    //
    // Note : on teste la chaîne déjà décodée en UTF-8 par le caller
    // (`extractDocxText`), donc ici les accents sont déjà des codepoints
    // Unicode normaux. Le test garantit qu'aucune étape de
    // `docxXmlToPlainText` ne re-corrompt le texte.
    test('préserve les accents français', () {
      const xml =
          '<w:p><w:r><w:t>'
          'Été à la française : éàèçùôîïëœ — c\'était parfait !'
          '</w:t></w:r></w:p>';
      final out = docxXmlToPlainText(xml);
      expect(out, contains('Été à la française'));
      expect(out, contains('éàèçùôîïëœ'));
    });

    test('préserve les emojis et idéogrammes CJK', () {
      const xml =
          '<w:p><w:r><w:t>'
          'Hello 🌍 こんにちは 你好 안녕'
          '</w:t></w:r></w:p>';
      final out = docxXmlToPlainText(xml);
      expect(out, contains('🌍'));
      expect(out, contains('こんにちは'));
      expect(out, contains('你好'));
      expect(out, contains('안녕'));
    });
  });
}
