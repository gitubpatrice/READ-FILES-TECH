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
/// 4. Purge des dossiers `cache/vault_decrypt/` et `cache/share/`
/// 5. Purge de la liste des fichiers récents
///
/// La sauvegarde `.rftvault` éventuellement exportée par l'utilisateur reste
/// intacte (hors scope — c'est volontaire pour permettre la restauration).
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
    // 4. Purge cache/vault_decrypt + cache/share (déjà fait par lock(), mais
    // on relance pour s'assurer même si lock a échoué).
    try {
      await VaultService.instance.purgeTempDecrypted();
      report.cachePurged = true;
    } catch (e) {
      if (kDebugMode) debugPrint('panic cache: $e');
    }
    // 5. Purge récents (la clé prefs `recent_files` est déjà effacée à l'étape
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
  bool recentsCleared = false;

  bool get isComplete =>
      locked && vaultDeleted && prefsCleared && cachePurged && recentsCleared;

  @override
  String toString() =>
      'PanicReport(locked=$locked vault=$vaultDeleted '
      'prefs=$prefsCleared cache=$cachePurged recents=$recentsCleared)';
}
