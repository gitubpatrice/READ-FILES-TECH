import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/screens/viewers/html_viewer_screen.dart';

/// La CSP injectée dans le viewer HTML est la seule chose qui empêche un
/// document piégé d'émettre une requête sortante : le `NavigationDelegate` ne
/// voit pas les sous-ressources, et l'APK release porte bien la permission
/// `INTERNET`. Si l'insertion rate, il n'y a plus aucun garde-fou.
///
/// La première implémentation cherchait `<head` dans le texte brut. Une
/// relecture externe (GPT-5.2, 2026-08-09) a montré qu'un document commençant
/// par `<!-- <head> -->` faisait insérer la balise **dans le commentaire**,
/// donc inerte. Ces tests épinglent le cas, pour qu'une future « simplification »
/// qui reviendrait à chercher un tag rende immédiatement le rouge.
void main() {
  /// Position de la balise CSP dans le document, ou -1.
  int cspAt(String doc) => doc.indexOf('http-equiv="Content-Security-Policy"');

  /// Vrai si la CSP se retrouve à l'intérieur d'un commentaire HTML.
  bool cspInsideComment(String doc) {
    final at = cspAt(doc);
    if (at < 0) return true;
    final openBefore = doc.lastIndexOf('<!--', at);
    if (openBefore < 0) return false;
    final closeBetween = doc.indexOf('-->', openBefore);
    return closeBetween < 0 || closeBetween > at;
  }

  test('la CSP est insérée et hors de tout commentaire — document simple', () {
    final doc = injectCsp('<html><head><title>x</title></head></html>');
    expect(cspAt(doc), greaterThanOrEqualTo(0));
    expect(cspInsideComment(doc), isFalse);
  });

  test('un faux <head> en commentaire ne détourne pas l\'insertion', () {
    const piege =
        '<!-- <head> -->\n'
        '<head>\n'
        '  <img src="https://attaquant.example/pixel?open=1">\n'
        '</head>';
    final doc = injectCsp(piege);

    expect(cspAt(doc), greaterThanOrEqualTo(0));
    expect(
      cspInsideComment(doc),
      isFalse,
      reason: 'une CSP dans un commentaire n\'est pas appliquée',
    );
    // Et surtout : elle doit précéder l'image distante, sinon elle arrive trop
    // tard pour la bloquer.
    expect(cspAt(doc), lessThan(doc.indexOf('attaquant.example')));
  });

  test('la CSP précède toujours la première ressource distante', () {
    for (final piege in <String>[
      '<img src="https://a.example/p.png">',
      '<!-- <head> --><img src="https://a.example/p.png">',
      '<HEAD><img src="https://a.example/p.png"></HEAD>',
      '<html><body><img src="https://a.example/p.png"></body></html>',
      '<!doctype html><html><head><img src="https://a.example/p.png">',
      "<script>fetch('https://a.example/x')</script>",
    ]) {
      final doc = injectCsp(piege);
      expect(cspAt(doc), greaterThanOrEqualTo(0), reason: piege);
      expect(cspInsideComment(doc), isFalse, reason: piege);
      expect(
        cspAt(doc),
        lessThan(doc.indexOf('a.example')),
        reason: 'CSP après la ressource, donc inutile : $piege',
      );
    }
  });

  test('un DOCTYPE reste le tout premier nœud du document', () {
    for (final doc in <String>[
      injectCsp('<!doctype html><html><head></head></html>'),
      injectCsp('<!DOCTYPE HTML><html><head></head></html>'),
      injectCsp('\n  <!doctype html>\n<html></html>'),
    ]) {
      final dt = doc.toLowerCase().indexOf('<!doctype');
      expect(dt, greaterThanOrEqualTo(0));
      expect(
        dt,
        lessThan(cspAt(doc)),
        reason:
            'insérer avant le DOCTYPE bascule la page en quirks mode et '
            'change son rendu',
      );
      // Rien d'autre qu'un blanc avant le DOCTYPE.
      expect(doc.substring(0, dt).trim(), isEmpty);
    }
  });

  test('la politique coupe bien le réseau et garde le local', () {
    final doc = injectCsp('<html></html>');
    expect(doc, contains("default-src 'none'"));
    expect(doc, contains("connect-src 'none'"));
    // Les ressources voisines du document doivent rester affichables.
    expect(doc, contains('img-src file:'));
    expect(doc, contains('style-src file:'));
    // Aucune autorisation de schéma distant, sous aucune directive.
    expect(doc.contains('https:'), isFalse);
    expect(doc.contains('http:'), isFalse);
  });

  test('un > dans un identifiant PUBLIC du DOCTYPE ne piège pas la CSP', () {
    // Le tokenizer HTML ne termine PAS le doctype sur un `>` situé dans un
    // identifiant PUBLIC/SYSTEM entre guillemets. Un `indexOf('>')` naïf
    // insérait la balise CSP à l'intérieur de l'identifiant, où le doctype
    // l'avalait : politique inexistante, image distante chargée.
    const doctype = '<!DOCTYPE html PUBLIC "a>b" "c>d">';
    const piege =
        '$doctype<html><head><img src="https://a.example/p.png"></head></html>';
    final doc = injectCsp(piege);

    // La CSP commence exactement là où le doctype se termine : ni dedans, ni
    // après le <html>.
    expect(doc.startsWith(doctype), isTrue, reason: 'DOCTYPE intact');
    expect(cspAt(doc), greaterThan(doctype.length - 1));
    expect(doc.indexOf('<html'), greaterThan(cspAt(doc)));
    expect(cspAt(doc), lessThan(doc.indexOf('a.example')));
  });

  test('les iframes sont refusées, pas seulement restreintes', () {
    // `frame-src file:` autorisait n'importe quel fichier local, et le
    // NavigationDelegate ne filtre pas les sous-frames.
    expect(injectCsp('<html></html>'), contains("frame-src 'none'"));
  });

  test('un document vide reçoit quand même la CSP', () {
    expect(cspAt(injectCsp('')), greaterThanOrEqualTo(0));
  });
}
