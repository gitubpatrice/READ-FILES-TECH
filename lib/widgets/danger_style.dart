import 'package:flutter/material.dart';

/// Rouge « action destructive » Files Tech.
///
/// Valeur fixe (et non dérivée du `ColorScheme`) afin que le bouton de
/// suppression définitive se lise à l'identique en thème clair ET sombre :
/// `cs.error` / `cs.errorContainer` deviennent pâles en mode sombre, ce qui
/// affaiblit l'avertissement visuel.
///
/// #C62828 sur blanc = ratio de contraste 5.6:1 → WCAG 2.1 AA (texte normal).
const Color kDangerRed = Color(0xFFC62828);

/// Style du bouton de confirmation destructive : fond rouge plein, texte blanc.
ButtonStyle dangerFilledButtonStyle() => FilledButton.styleFrom(
  backgroundColor: kDangerRed,
  foregroundColor: Colors.white,
);
