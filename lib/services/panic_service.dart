import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'trash_service.dart';
import 'vault_service.dart';

/// Mode panique : wipe complet en cas de menace immédiate (saisie device,
/// contrainte). Calque Notes Tech / Pass Tech.
///
/// Étapes ordonnées (best-effort, continue malgré exceptions) :
/// 1. Lock + zeroize de la clé maître en RAM
/// 2. Suppression du dossier `vault/` (toutes les enveloppes chiffrées)
/// 3. Reset des SharedPreferences vault (sel, params Argon2, sentinelle, flags)
/// 4. Purge des dossiers `cache/vault_decrypt/`, `cache/share/`, `cache/exports/`
/// 5. F6 v2.13.0 — Purge des sous-dossiers cache potentiellement plaintext
///    (history éditeur code, exports `.rftvault` orphelins, `_no_exif.jpg`,
///    `_signe.pdf` laissés à la racine de `cache/`).
/// 6. Purge de la liste des fichiers récents
///
/// La sauvegarde `.rftvault` éventuellement EXPORTÉE par l'utilisateur (et
/// déplacée hors du cache via la share-sheet) reste intacte — hors scope —
/// c'est volontaire pour permettre la restauration.
class PanicService {
  PanicService._();
  static final PanicService instance = PanicService._();

  /// Liste blanche des clés SharedPreferences à PRÉSERVER pendant le wipe.
  /// Tout le reste (vault_*, output_*, theme_mode, permissions_asked…) est
  /// effacé.
  static const _preservedPrefs = <String>{
    // (volontairement vide — tout est effacé. Si on doit conserver un
    // toggle plus tard, l'ajouter ici.)
  };

  /// Lance le wipe complet. Renvoie un rapport synthétique.
  Future<PanicReport> wipeAll() async {
    final report = PanicReport();
    // 1. Lock + zeroize clé.
    try {
      VaultService.instance.lock();
      report.locked = true;
    } catch (e) {
      if (kDebugMode) debugPrint('panic lock: $e');
    }
    // 2. Suppression vault/ entier.
    try {
      final docs = await getApplicationDocumentsDirectory();
      final vault = Directory('${docs.path}/vault');
      if (await vault.exists()) {
        await vault.delete(recursive: true);
        report.vaultDeleted = true;
      }
    } catch (e) {
      if (kDebugMode) debugPrint('panic vault dir: $e');
    }
    // 3. Reset SharedPreferences (whitelist).
    try {
      final prefs = await SharedPreferences.getInstance();
      final keys = prefs.getKeys().toList();
      // `remove()` rend un booleen et ne leve PAS quand il echoue. Sa valeur
      // etait jetee, puis le drapeau pose inconditionnellement : le rapport
      // annoncait « prefs effacees » alors que des cles pouvaient subsister.
      // Sur un effacement d'URGENCE, un rapport qui rassure a tort est le pire
      // resultat possible — l'utilisateur cesse de s'inquieter.
      var toutesRetirees = true;
      for (final k in keys) {
        if (_preservedPrefs.contains(k)) continue;
        if (!await prefs.remove(k)) toutesRetirees = false;
      }
      // Verification independante : ce qui compte n'est pas que chaque appel
      // ait rendu true, c'est qu'il ne reste rien.
      final restantes = prefs
          .getKeys()
          .where((k) => !_preservedPrefs.contains(k))
          .toList();
      if (restantes.isNotEmpty) {
        toutesRetirees = false;
        if (kDebugMode) {
          debugPrint('panic prefs: ${restantes.length} cle(s) subsistent');
        }
      }
      report.prefsCleared = toutesRetirees;
    } catch (e) {
      if (kDebugMode) debugPrint('panic prefs: $e');
    }
    // 4. Purge des dossiers cache portant du plaintext (cf
    // VaultService.purgeTempDecrypted), `share_plus/` compris.
    try {
      await VaultService.instance.purgeTempDecrypted();
      report.cachePurged = true;
    } catch (e) {
      if (kDebugMode) debugPrint('panic cache: $e');
    }
    // 5. F6 v2.13.0 — Purge des fichiers temporaires à la racine du cache
    // (artefacts EXIF/PDF signés/OCR laissés là par les outils en cas
    // d'annulation share). Ne supprime PAS les sous-dossiers d'autres
    // plugins (FontCache, etc.) — uniquement les fichiers à la racine et
    // les sous-dossiers connus.
    var tempIntact = true;
    try {
      final tmpRoot = await getTemporaryDirectory();
      if (await tmpRoot.exists()) {
        await for (final entry in tmpRoot.list(followLinks: false)) {
          if (entry is File) {
            final name = entry.path.split(RegExp(r'[/\\]')).last;
            // Patterns connus de l'app : _no_exif, _signe, ocr_, .rftvault,
            // texte_extrait_, _compresse, scan_.
            if (name.contains('_no_exif') ||
                name.contains('_signe') ||
                name.startsWith('ocr_') ||
                name.endsWith('.rftvault') ||
                name.startsWith('texte_extrait_') ||
                name.contains('_compresse') ||
                name.startsWith('scan_')) {
              try {
                await entry.delete();
              } catch (_) {
                // Un fichier qu'on n'a pas su supprimer est du plaintext qui
                // RESTE : il doit invalider le drapeau, pas etre avale.
                tempIntact = false;
              }
            }
          }
        }
      }
      // History éditeur de code (peut contenir des extraits sensibles
      // sauvegardés automatiquement).
      final docs = await getApplicationDocumentsDirectory();
      final history = Directory('${docs.path}/history');
      if (await history.exists()) {
        await history.delete(recursive: true);
      }
      report.tempPurged = tempIntact;
    } catch (e) {
      if (kDebugMode) debugPrint('panic temp: $e');
    }
    // 6. V-M3 v2.15 — Vidage de la corbeille.
    //
    // La corbeille est arrivée en v2.14.0 ; le panic wipe, lui, datait de
    // v2.12. Rien ne les a reliés. Or « supprimer » dans l'explorateur
    // **déplace** désormais le fichier dans `<volume>/.RFT_Corbeille`, en
    // clair, sur le stockage partagé — lisible par toute app ayant l'accès
    // stockage. Un utilisateur qui supprimait un document sensible puis
    // déclenchait le mode panique voyait `PanicReport.isComplete` au vert
    // alors que le document était toujours là, à un tap de la restauration.
    //
    // C'est le motif dominant de ce dépôt : une fonctionnalité neuve
    // réintroduit une surface qu'un mécanisme plus ancien croyait couvrir.
    try {
      final trash = TrashService();
      final entries = await trash.list();
      final res = await trash.emptyAll(entries);
      // Un seul échec suffit à invalider la promesse « wipe complet » : le
      // rapport doit dire la vérité, pas rassurer.
      report.trashEmptied = res.fail == 0;
    } catch (e) {
      if (kDebugMode) debugPrint('panic trash: $e');
    }
    // 7. Purge des récents.
    //
    // La clé `recent_files` n'est pas whitelistée : elle est donc effacée à
    // l'étape 3, et ce drapeau ne fait qu'en refléter la couverture. Il était
    // posé **inconditionnellement**, y compris quand l'étape 3 avait échoué —
    // le rapport affirmait alors que les récents étaient purgés alors que la
    // clé était toujours là. Un drapeau qui ne mesure rien vaut moins que pas
    // de drapeau du tout.
    report.recentsCleared = report.prefsCleared;
    return report;
  }
}

class PanicReport {
  bool locked = false;
  bool vaultDeleted = false;
  bool prefsCleared = false;
  bool cachePurged = false;
  bool tempPurged = false;

  /// v2.15 — corbeille vidée sans échec. Faux tant que l'étape n'a pas tourné.
  bool trashEmptied = false;
  bool recentsCleared = false;

  bool get isComplete =>
      locked &&
      vaultDeleted &&
      prefsCleared &&
      cachePurged &&
      tempPurged &&
      trashEmptied &&
      recentsCleared;

  @override
  String toString() =>
      'PanicReport(locked=$locked vault=$vaultDeleted '
      'prefs=$prefsCleared cache=$cachePurged temp=$tempPurged '
      'trash=$trashEmptied recents=$recentsCleared)';
}
