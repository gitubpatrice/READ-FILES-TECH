import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

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
      for (final k in keys) {
        if (_preservedPrefs.contains(k)) continue;
        await prefs.remove(k);
      }
      report.prefsCleared = true;
    } catch (e) {
      if (kDebugMode) debugPrint('panic prefs: $e');
    }
    // 4. Purge cache/vault_decrypt + cache/share + cache/exports (étendu
    // dans v2.13.0 — cf VaultService.purgeTempDecrypted).
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
                /* ignore */
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
      report.tempPurged = true;
    } catch (e) {
      if (kDebugMode) debugPrint('panic temp: $e');
    }
    // 6. Purge récents (la clé prefs `recent_files` est déjà effacée à l'étape
    // 3 puisque non whitelistée — ce flag confirme la couverture du périmètre).
    report.recentsCleared = true;
    return report;
  }
}

class PanicReport {
  bool locked = false;
  bool vaultDeleted = false;
  bool prefsCleared = false;
  bool cachePurged = false;
  bool tempPurged = false;
  bool recentsCleared = false;

  bool get isComplete =>
      locked &&
      vaultDeleted &&
      prefsCleared &&
      cachePurged &&
      tempPurged &&
      recentsCleared;

  @override
  String toString() =>
      'PanicReport(locked=$locked vault=$vaultDeleted '
      'prefs=$prefsCleared cache=$cachePurged temp=$tempPurged '
      'recents=$recentsCleared)';
}
