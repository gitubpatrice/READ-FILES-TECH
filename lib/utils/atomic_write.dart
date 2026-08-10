/// Écriture atomique : `tmp + flush + rename`.
///
/// Garantit qu'un kill OS / OOM en milieu d'écriture ne laisse pas un fichier
/// final tronqué. Le `rename()` POSIX est atomique sur un même système de
/// fichiers — d'où le temporaire placé **à côté** de la cible, et non dans le
/// dossier temporaire du système, qui peut être sur un autre volume.
///
/// En cas d'erreur, le `.tmp` résiduel est supprimé best-effort.
library;

import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

/// Compteur de processus, pour que deux appels rapprochés ne puissent pas
/// tomber sur le même nom même si l'aléa se répétait.
int _seq = 0;

final _rand = Random.secure();

/// Nom du fichier temporaire, **unique à chaque appel**.
///
/// **Pourquoi ce n'est pas `'$path.tmp'`.** Ce nom fixe, dérivé de la seule
/// cible, était partagé par tous les appels visant le même fichier. Deux
/// écritures concurrentes écrivaient donc dans le MÊME temporaire, puis le
/// renommaient chacune à leur tour : le fichier final pouvait contenir un
/// mélange des deux, ou une version tronquée. Signalé par la relecture GPT du
/// 2026-08-10.
///
/// Ce n'est pas théorique ici : `atomicWriteBytes` est le point d'écriture du
/// coffre, de la corbeille, des conversions et des sauvegardes. Deux
/// opérations de corbeille qui se chevauchent suffisent.
///
/// L'unicité ferme au passage un second risque : un `.tmp` pré-existant posé
/// en lien symbolique par un tiers faisait écrire hors de la cible. Un nom
/// imprévisible ne peut pas être devancé.
String _tmpPathFor(String path) {
  final n = _seq = (_seq + 1) & 0xFFFF;
  final r = _rand.nextInt(1 << 32).toRadixString(16);
  return '$path.$n-$r.tmp';
}

/// Écrit [path] de façon atomique, via [write] qui reçoit le fichier
/// temporaire. Corps commun aux variantes ci-dessous, qui n'en différaient
/// que par une ligne.
Future<void> _atomically(
  String path,
  Future<void> Function(File tmp) write,
) async {
  final tmp = File(_tmpPathFor(path));
  try {
    await write(tmp);
    await tmp.rename(path);
  } catch (_) {
    try {
      if (await tmp.exists()) await tmp.delete();
    } catch (_) {
      // Le nettoyage est best-effort : masquer l'erreur d'origine par une
      // erreur de nettoyage rendrait la panne réelle indéchiffrable.
    }
    rethrow;
  }
}

/// Écrit [bytes] dans [path], atomiquement.
Future<void> atomicWriteBytes(String path, List<int> bytes) =>
    _atomically(path, (tmp) => tmp.writeAsBytes(bytes, flush: true));

/// Variante string. UTF-8 par défaut.
Future<void> atomicWriteString(String path, String content) =>
    _atomically(path, (tmp) => tmp.writeAsString(content, flush: true));

/// Variante prenant un `Uint8List` typé (évite cast inutile).
Future<void> atomicWriteUint8(String path, Uint8List bytes) =>
    atomicWriteBytes(path, bytes);
