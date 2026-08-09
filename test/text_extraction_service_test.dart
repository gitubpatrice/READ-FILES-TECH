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

  // A-P1b — l'écran `docx_viewer_screen.dart` portait sa propre extraction
  // OpenDocument, jamais testée, avec une liste d'entités codée en dur.
  // Elle vit maintenant ici. Ces tests portent sur ce que l'ancienne version
  // faisait FAUX : sans eux, la déduplication serait un déplacement de code
  // sans preuve que le comportement retenu est le bon.
  group('odtXmlToPlainText', () {
    test('extrait les paragraphes text:p', () {
      const xml =
          '<office:text>'
          '<text:p>Premier paragraphe</text:p>'
          '<text:p>Second paragraphe</text:p>'
          '</office:text>';
      final out = odtXmlToPlainText(xml);
      expect(out, 'Premier paragraphe\nSecond paragraphe');
    });

    test('retire les balises de mise en forme internes', () {
      const xml =
          '<text:p>Du <text:span text:style-name="T1">gras</text:span> ici</text:p>';
      expect(odtXmlToPlainText(xml), 'Du gras ici');
    });

    test(
      'décode les entités numériques — ce que l\'ancienne copie ne faisait pas',
      () {
        // Le décodage codé en dur de l'écran ne connaissait que les cinq
        // entités nommées : un `.odt` de LibreOffice affichait « &#233; » en
        // toutes lettres au lieu de « é ».
        const xml = '<text:p>caf&#233; cr&#xE8;me</text:p>';
        expect(odtXmlToPlainText(xml), 'café crème');
      },
    );

    test('ne décode pas deux fois `&amp;lt;`', () {
      // L'ancienne copie remplaçait `&amp;` EN PREMIER : `&amp;lt;` devenait
      // `&lt;` puis `<`. Le fichier disait « &lt; », l'écran affichait « < ».
      const xml = '<text:p>&amp;lt; reste du texte</text:p>';
      expect(odtXmlToPlainText(xml), '&lt; reste du texte');
    });

    test('&#xD; vaut un saut de ligne, pas un retour chariot brut', () {
      // ODF s'en sert comme saut de ligne. Passer par le décodage générique
      // rendrait un `\r`, invisible et mal rendu dans un widget Text.
      const xml = '<text:p>ligne un&#xD;ligne deux</text:p>';
      final out = odtXmlToPlainText(xml);
      expect(out, 'ligne un\nligne deux');
      expect(out, isNot(contains('\r')));
    });

    test('ignore les paragraphes vides', () {
      const xml = '<text:p>A</text:p><text:p>   </text:p><text:p>B</text:p>';
      expect(odtXmlToPlainText(xml), 'A\nB');
    });

    // Constats de la relecture GPT du 2026-08-09 sur la deduplication.
    test('extrait aussi les titres text:h, dans l\'ordre du document', () {
      // Un document structure en titres perdait TOUS ses titres, et un plan
      // qui n'en contient que ressortait vide — puis, depuis que le service
      // transforme le vide en erreur, affichait « Le document semble vide ».
      const xml =
          '<office:text>'
          '<text:h text:outline-level="1">Titre premier</text:h>'
          '<text:p>Un paragraphe.</text:p>'
          '<text:h text:outline-level="2">Sous-titre</text:h>'
          '</office:text>';
      expect(
        odtXmlToPlainText(xml),
        'Titre premier\nUn paragraphe.\nSous-titre',
      );
    });

    test('un document fait uniquement de titres n\'est pas vide', () {
      const xml = '<text:h>Plan</text:h><text:h>Chapitre 1</text:h>';
      expect(odtXmlToPlainText(xml), 'Plan\nChapitre 1');
    });

    test(
      'text:line-break devient un saut de ligne, text:tab une tabulation',
      () {
        // Ce sont des balises : le nettoyage generique les effacait sans rien
        // laisser, et deux lignes se retrouvaient collees.
        const xml =
            '<text:p>ligne un<text:line-break/>ligne deux</text:p>'
            '<text:p>col1<text:tab/>col2</text:p>';
        expect(odtXmlToPlainText(xml), 'ligne un\nligne deux\ncol1\tcol2');
      },
    );

    test('préserve accents et emoji', () {
      const xml = '<text:p>Été 🌍 你好</text:p>';
      final out = odtXmlToPlainText(xml);
      expect(out, contains('Été'));
      expect(out, contains('🌍'));
      expect(out, contains('你好'));
    });
  });

  // Ces tests portent sur le TEMPS, pas sur le resultat. C'est inhabituel, et
  // c'est le seul angle qui attrape le defaut : l'ancienne implementation
  // rendait exactement le meme texte (rien), simplement elle mettait vingt
  // secondes a le faire, sur le thread principal, pour un fichier de quelques
  // kilo-octets. Aucune assertion sur la sortie ne pouvait le voir.
  //
  // Le seuil est volontairement large. Le but n'est pas de mesurer une
  // performance mais de distinguer un algorithme lineaire d'un algorithme
  // quadratique : l'ancien mettait ~5 s sur l'entree ODT ci-dessous et
  // ~19 s sur l'entree DOCX, le nouveau quelques millisecondes. Un facteur
  // mille laisse toute la marge voulue a une machine lente.
  group('complexite sur entree hostile', () {
    const budget = Duration(seconds: 2);

    test('ODT : des milliers de balises jamais fermees', () {
      final piege = '<text:p>' * 20000; // 160 Ko
      final sw = Stopwatch()..start();
      final out = odtXmlToPlainText(piege);
      sw.stop();
      expect(out, isEmpty);
      expect(
        sw.elapsed,
        lessThan(budget),
        reason:
            'balayage quadratique : ${sw.elapsedMilliseconds} ms pour '
            '${piege.length} caracteres',
      );
    });

    test('DOCX : des milliers de balises jamais fermees', () {
      final piege = '<w:t>' * 20000; // 100 Ko
      final sw = Stopwatch()..start();
      final out = docxXmlToPlainText(piege);
      sw.stop();
      expect(out, isEmpty);
      expect(
        sw.elapsed,
        lessThan(budget),
        reason:
            'balayage quadratique : ${sw.elapsedMilliseconds} ms pour '
            '${piege.length} caracteres',
      );
    });

    test('une fermeture orpheline ne fait pas boucler', () {
      // L'autre bout du meme probleme : des fermetures sans ouverture.
      final piege = '</text:p>' * 20000;
      final sw = Stopwatch()..start();
      expect(odtXmlToPlainText(piege), isEmpty);
      sw.stop();
      expect(sw.elapsed, lessThan(budget));
    });

    test('un document legitime volumineux reste rapide', () {
      // Le controle qui donne son sens aux trois precedents : si le nouveau
      // balayage etait lent en general, ces tests passeraient au vert pour la
      // mauvaise raison.
      final vrai = '<text:p>une ligne de texte</text:p>' * 20000; // 660 Ko
      final sw = Stopwatch()..start();
      final out = odtXmlToPlainText(vrai);
      sw.stop();
      expect(out.split('\n').length, 20000);
      expect(sw.elapsed, lessThan(budget));
    });
  });
}
