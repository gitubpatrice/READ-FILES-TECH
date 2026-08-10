import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/services/ods_service.dart';

/// Un `.ods` n'a jamais été lisible par l'application, alors qu'elle lui
/// routait l'extension depuis toujours. Ces tests couvrent le parcours du
/// `content.xml` ODF, et surtout les deux pièges du format :
///
///  - les cellules vides sont **auto-fermantes** (`<table:table-cell/>`), et
///    les ignorer décale toutes les colonnes suivantes ;
///  - les répétitions (`table:number-columns-repeated`) sont un vecteur de
///    bombe : un fichier minuscule peut en déclarer un milliard.
String contentXml(String body) =>
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<office:document-content '
    'xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
    'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
    'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">'
    '<office:body><office:spreadsheet>$body'
    '</office:spreadsheet></office:body></office:document-content>';

String cell(String v) =>
    '<table:table-cell office:value-type="string"><text:p>$v</text:p>'
    '</table:table-cell>';

String row(String cells) => '<table:table-row>$cells</table:table-row>';

String table(String name, String rows) =>
    '<table:table table:name="$name">$rows</table:table>';

void main() {
  group('parcours du content.xml', () {
    test('lit une feuille, ses lignes et ses colonnes', () {
      final sheets = odsXmlToSheets(
        contentXml(table('Feuille1', row(cell('Bonjour') + cell('Monde')))),
      );
      expect(sheets.keys, ['Feuille1']);
      expect(sheets['Feuille1'], [
        ['Bonjour', 'Monde'],
      ]);
    });

    test('plusieurs feuilles gardent l\'ordre du document', () {
      final sheets = odsXmlToSheets(
        contentXml(
          table('Ventes', row(cell('a'))) + table('Stock', row(cell('b'))),
        ),
      );
      expect(sheets.keys.toList(), ['Ventes', 'Stock']);
    });

    test('deux feuilles homonymes ne s\'ecrasent pas', () {
      // Un `Map` ecraserait silencieusement la premiere : la seconde feuille
      // disparaitrait sans le moindre signe.
      final sheets = odsXmlToSheets(
        contentXml(
          table('Feuille', row(cell('premiere'))) +
              table('Feuille', row(cell('seconde'))),
        ),
      );
      expect(sheets.length, 2);
      expect(sheets['Feuille'], [
        ['premiere'],
      ]);
      expect(sheets['Feuille (2)'], [
        ['seconde'],
      ]);
    });

    test('les entites XML sont decodees, dans le contenu et dans le nom', () {
      final sheets = odsXmlToSheets(
        contentXml(
          '<table:table table:name="Caf&#233;">'
          '${row(cell('accents : &#233;&#224;&#252;'))}'
          '</table:table>',
        ),
      );
      expect(sheets.keys, ['Café']);
      expect(sheets['Café']!.first.first, 'accents : éàü');
    });
  });

  group('cellules vides auto-fermantes', () {
    test('une cellule vide au milieu ne decale pas les colonnes', () {
      // LE test de ce fichier. `tagContents` ne voyait pas les balises
      // auto-fermantes : « B » aurait glisse en colonne 1 et le tableau entier
      // se serait affiche faux, sans erreur ni message.
      final sheets = odsXmlToSheets(
        contentXml(
          table('F', row('${cell('A')}<table:table-cell/>${cell('C')}')),
        ),
      );
      expect(sheets['F'], [
        ['A', '', 'C'],
      ]);
    });

    test('une cellule vide en fin de ligne est elaguee', () {
      final sheets = odsXmlToSheets(
        contentXml(table('F', row('${cell('A')}<table:table-cell/>'))),
      );
      expect(sheets['F'], [
        ['A'],
      ]);
    });

    test('une ligne entierement vide en queue est elaguee', () {
      final sheets = odsXmlToSheets(
        contentXml(table('F', row(cell('A')) + row('<table:table-cell/>'))),
      );
      expect(sheets['F'], [
        ['A'],
      ]);
    });
  });

  group('repetitions — vecteur de bombe', () {
    test('une repetition legitime est depliee', () {
      final sheets = odsXmlToSheets(
        contentXml(
          table(
            'F',
            row(
              '<table:table-cell table:number-columns-repeated="3" '
              'office:value-type="string"><text:p>x</text:p>'
              '</table:table-cell>',
            ),
          ),
        ),
      );
      expect(sheets['F'], [
        ['x', 'x', 'x'],
      ]);
    });

    test(
      'un milliard de colonnes repetees ne fait pas exploser la memoire',
      () {
        // Un `.ods` de quelques centaines d'octets peut declarer ceci. Deplier
        // naivement allouerait un milliard de chaines.
        final chrono = Stopwatch()..start();
        final sheets = odsXmlToSheets(
          contentXml(
            table(
              'F',
              row(
                '<table:table-cell table:number-columns-repeated="1000000000" '
                'office:value-type="string"><text:p>x</text:p>'
                '</table:table-cell>',
              ),
            ),
          ),
        );
        chrono.stop();
        expect(sheets['F']!.first.length, kOdsMaxCols);
        expect(
          chrono.elapsedMilliseconds,
          lessThan(1000),
          reason:
              'Le depliage a pris ${chrono.elapsedMilliseconds} ms : la borne '
              'ne s exprime pas et le fichier dicte l allocation.',
        );
      },
    );

    test('un milliard de lignes repetees ne fait pas exploser la memoire', () {
      final chrono = Stopwatch()..start();
      final sheets = odsXmlToSheets(
        contentXml(
          table(
            'F',
            '<table:table-row table:number-rows-repeated="1000000000">'
                '${cell('x')}</table:table-row>',
          ),
        ),
      );
      chrono.stop();
      expect(sheets['F']!.length, kOdsMaxRows);
      expect(chrono.elapsedMilliseconds, lessThan(2000));
    });

    test('une repetition de lignes VIDES ne produit pas de lignes', () {
      // Cas le plus courant en pratique : LibreOffice termine ses feuilles par
      // une ligne vide repetee ~1 048 576 fois pour materialiser la grille. La
      // deplier remplirait le tableau de vide jusqu au plafond.
      final sheets = odsXmlToSheets(
        contentXml(
          table(
            'F',
            '${row(cell('utile'))}'
                '<table:table-row table:number-rows-repeated="1048576">'
                '<table:table-cell/></table:table-row>',
          ),
        ),
      );
      expect(sheets['F'], [
        ['utile'],
      ]);
    });

    test('une repetition absurde ou negative vaut 1', () {
      final sheets = odsXmlToSheets(
        contentXml(
          table(
            'F',
            row(
              '<table:table-cell table:number-columns-repeated="-5" '
              'office:value-type="string"><text:p>a</text:p>'
              '</table:table-cell>'
              '<table:table-cell table:number-columns-repeated="nawak" '
              'office:value-type="string"><text:p>b</text:p>'
              '</table:table-cell>',
            ),
          ),
        ),
      );
      expect(sheets['F'], [
        ['a', 'b'],
      ]);
    });
  });

  group('lecture du ZIP', () {
    Uint8List odsZip(String xml, {String entryName = 'content.xml'}) {
      final archive = Archive()
        ..addFile(
          ArchiveFile.string(
            'mimetype',
            'application/vnd.oasis.'
                'opendocument.spreadsheet',
          ),
        )
        ..addFile(ArchiveFile.string(entryName, xml));
      return Uint8List.fromList(ZipEncoder().encode(archive));
    }

    test('un .ods complet est lu de bout en bout', () {
      final bytes = odsZip(
        contentXml(table('Feuille1', row(cell('Bonjour') + cell('Monde')))),
      );
      final r = readOdsBytes(bytes);
      expect(r.error, isNull);
      expect(r.sheets!['Feuille1'], [
        ['Bonjour', 'Monde'],
      ]);
    });

    test('un fichier qui n\'est pas un ZIP est refuse proprement', () {
      final r = readOdsBytes(utf8.encode('ceci n est pas une archive'));
      expect(r.sheets, isNull);
      expect(r.error, contains('ZIP illisible'));
    });

    test('un ZIP sans content.xml est refuse proprement', () {
      final r = readOdsBytes(odsZip('<x/>', entryName: 'autre.xml'));
      expect(r.sheets, isNull);
      expect(r.error, contains('content.xml'));
    });

    test('une archive corrompue est distinguee d un contenu manquant', () {
      // `PK` + octets aleatoires : signature valide, zero entree
      // decodee. Sans le croisement avec `zipDeclaresEntries`, le message
      // aurait ete « content.xml introuvable » — qui accuse le contenu du
      // fichier au lieu de dire qu il est illisible.
      final corrompu = Uint8List.fromList([
        0x50,
        0x4B,
        0x03,
        0x04,
        ...List<int>.generate(2048, (i) => (i * 53 + 7) & 0xFF),
      ]);
      final r = readOdsBytes(corrompu);
      expect(r.sheets, isNull);
      expect(r.error, contains('corrompue'));
      expect(r.error, isNot(contains('content.xml')));
    });

    test('un classeur sans feuille est signale, pas rendu vide', () {
      final r = readOdsBytes(odsZip(contentXml('')));
      expect(r.sheets, isNull);
      expect(r.error, contains('Aucune feuille'));
    });
  });
}
