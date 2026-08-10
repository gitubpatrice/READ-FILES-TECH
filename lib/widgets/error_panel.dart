import 'package:flutter/material.dart';

import '../utils/snack_utils.dart';

/// Panneau d'erreur affiché au centre d'une visionneuse qui n'a rien à montrer.
///
/// **Pourquoi ce composant existe.** Les visionneuses affichaient leur erreur
/// par un `Center(child: Text('Erreur : $_error'))` — un texte nu, en couleur
/// de corps de texte, courant sur toute la largeur de l'écran. Sur un message
/// un peu long, comme le refus du `.ods`, la ligne traversait la dalle de bord
/// à bord sans que rien ne la distingue d'un contenu normal.
///
/// Deux écrans le recopiaient à l'identique ; un troisième aurait recopié la
/// même chose. D'où un composant unique plutôt qu'un style posé deux fois.
class ErrorPanel extends StatelessWidget {
  /// Message à afficher, déjà rédigé pour un humain. Le préfixe « Erreur : »
  /// n'est pas ajouté ici : certains messages sont des refus explicites et
  /// pédagogiques, pas des pannes.
  final String message;

  const ErrorPanel({super.key, required this.message});

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          // Le message ne prend jamais toute la largeur : au-delà d'environ
          // 60 caractères par ligne la lecture décroche, et un bloc borné se
          // distingue immédiatement d'un contenu de document.
          constraints: const BoxConstraints(maxWidth: 420),
          child: DecoratedBox(
            decoration: BoxDecoration(
              // Pas de fond teinté : `errorContainer` est dérivé du seed bleu
              // de l'app et l'harmonisation Material 3 le désature en rose
              // pâle — du saumon, pas une alerte. C'est la bordure qui porte
              // le signal, en rouge franc.
              border: Border.all(color: kErrorRed, width: 2),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.error_outline, color: colors.error, size: 22),
                  const SizedBox(width: 12),
                  Flexible(
                    child: Text(
                      message,
                      style: TextStyle(
                        color: colors.error,
                        fontSize: 15,
                        height: 1.35,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
