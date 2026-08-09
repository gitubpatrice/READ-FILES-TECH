import 'dart:async';
import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../../services/secure_window.dart';
import '../../services/vault_service.dart';
import '../../utils/app_constants.dart';
import '../../utils/snack_utils.dart';
import '../../widgets/danger_style.dart';
import '../../widgets/rft_picker_screen.dart';
import 'vault_import_folder_screen.dart';

/// Scrub best-effort d'un controller de mot de passe : remplace le contenu
/// par des null-bytes puis vide. Ne garantit PAS l'effacement RAM (les
/// String Dart sont immuables et non-zeroizables — limitation langage),
/// mais retire la référence forte du controller, accélérant le GC potentiel.
void _scrubPasswordController(TextEditingController c) {
  if (c.text.isNotEmpty) c.text = '\x00' * c.text.length;
  c.text = '';
}

class VaultScreen extends StatefulWidget {
  const VaultScreen({super.key});

  @override
  State<VaultScreen> createState() => _VaultScreenState();
}

class _VaultScreenState extends State<VaultScreen> with WidgetsBindingObserver {
  final _service = VaultService.instance;
  bool _checking = true;
  bool _unlocked = false;
  bool _setup = false;

  /// Délai d'auto-lock après `paused`. Permet à un picker natif (SAF, share
  /// sheet, FilePicker) qui pause brièvement l'app de se terminer sans
  /// déclencher le lock. Pattern standard des password managers (Bitwarden,
  /// KeePassDX).
  static const _autoLockDelay = AppConstants.autoLockDelay;

  /// Timer programmant le lock différé. Annulé sur `resumed`.
  Timer? _pendingLockTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // FLAG_SECURE actif dès l'entrée dans VaultScreen, AVANT toute saisie de
    // mot de passe (Setup ou Unlock). Empêche la capture de l'écran de saisie
    // ainsi que sa présence dans l'aperçu Recents si l'utilisateur swipe en
    // plein typing. Désactivé seulement quand on quitte VaultScreen ou qu'on
    // lock explicitement.
    SecureWindow.enable();
    // Le verrouillage peut désormais venir d'ailleurs que de cet écran :
    // minuteur d'inactivité au premier plan (`main.dart`), panic wipe. Sans
    // cette écoute, `_unlocked` restait à `true` et l'écran continuait
    // d'afficher la LISTE DES NOMS de fichiers du coffre alors que la clé
    // était déjà zéroïsée — le contenu restait protégé, mais les
    // métadonnées, non.
    VaultService.unlockedNotifier.addListener(_onLockStateChanged);
    // Au boot du coffre, purger d'éventuels fichiers déchiffrés laissés.
    _service.purgeTempDecrypted();
    _bootstrap();
  }

  void _onLockStateChanged() {
    if (!mounted) return;
    final unlocked = _service.isUnlocked;
    if (unlocked != _unlocked) setState(() => _unlocked = unlocked);
  }

  @override
  void dispose() {
    _pendingLockTimer?.cancel();
    _pendingLockTimer = null;
    // V-H1 (audit 2026-08-02) — verrouiller en quittant l'écran.
    //
    // Le commentaire précédent affirmait que « le lock se fera via _lockNow /
    // lifecycle ». C'était faux dans le seul cas qui compte : quitter le
    // coffre par le bouton retour ne passe ni par `_lockNow` (déclenché
    // uniquement en arrière-plan) ni par `onLock` (le bouton explicite). Le
    // coffre restait donc déverrouillé **indéfiniment au premier plan** :
    // déverrouiller, revenir à l'accueil, poser le téléphone — quiconque le
    // reprenait rouvrait « Coffre » sans mot de passe.
    //
    // Le lock d'inactivité au premier plan (`main.dart`) couvre l'oubli ;
    // celui-ci couvre l'intention : quitter l'écran, c'est en avoir fini.
    VaultService.unlockedNotifier.removeListener(_onLockStateChanged);
    if (_service.isUnlocked) _service.lock();
    // Un seul `enable()` (initState) ⇄ un seul `disable()` (ici). Les
    // `enable()` supplémentaires posés à la création et au déverrouillage
    // laissaient le compteur de `SecureWindow` à 1 en quittant l'écran :
    // FLAG_SECURE restait posé sur **toute l'app** jusqu'à la mort du
    // process, et l'utilisateur ne pouvait plus faire la moindre capture
    // d'écran ailleurs. Le refcount n'était pas en cause — son appariement
    // l'était.
    SecureWindow.disable();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// Sécurité : verrouille le coffre quand l'app passe en arrière-plan,
  /// avec un délai de [_autoLockDelay] (30 s) — pattern Bitwarden/KeePassDX.
  /// Si l'utilisateur revient dans les 30 s (ex. retour d'un picker SAF), le
  /// timer est annulé et le coffre reste déverrouillé.
  /// `detached` (process killed) → lock immédiat (mais la clé est de toute
  /// façon perdue avec le process).
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Guard : si l'observer est déclenché après dispose (race rare),
    // on ne crée pas de Timer orphelin retenant une ref vers ce State.
    if (!mounted) return;
    if (state == AppLifecycleState.paused) {
      if (_service.isUnlocked) {
        _pendingLockTimer?.cancel();
        _pendingLockTimer = Timer(_autoLockDelay, _lockNow);
      }
    } else if (state == AppLifecycleState.detached) {
      if (_service.isUnlocked) _lockNow();
    } else if (state == AppLifecycleState.resumed) {
      // Annule un lock en attente si l'utilisateur revient dans les 30s.
      _pendingLockTimer?.cancel();
      _pendingLockTimer = null;
    }
  }

  void _lockNow() {
    if (!_service.isUnlocked) return;
    _service.lock();
    // Pas de `SecureWindow.disable()` ici : on reste sur VaultScreen, donc
    // sur l'écran de saisie du mot de passe, qui est précisément l'écran à
    // masquer. Le ref posé en initState est rendu au dispose, une fois.
    if (mounted) setState(() => _unlocked = false);
  }

  Future<void> _bootstrap() async {
    final setup = await _service.isSetup();
    if (!mounted) return;
    setState(() {
      _setup = setup;
      _checking = false;
      _unlocked = _service.isUnlocked;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    // Aucun appel à SecureWindow ci-dessous : le flag appartient à l'écran
    // (initState → dispose), pas à l'état de déverrouillage. Les `enable()` /
    // `disable()` qui étaient posés ici déséquilibraient le compteur selon le
    // chemin emprunté par l'utilisateur.
    if (!_setup) {
      return _SetupScreen(
        onCreated: () async {
          setState(() {
            _setup = true;
            _unlocked = true;
          });
        },
      );
    }
    if (!_unlocked) {
      return _UnlockScreen(
        onUnlocked: () {
          setState(() => _unlocked = true);
        },
        onReset: () async {
          final ok = await _confirmReset(context);
          if (ok) {
            await _service.reset();
            if (mounted) {
              setState(() {
                _setup = false;
                _unlocked = false;
              });
            }
          }
        },
      );
    }
    return _VaultContent(
      service: _service,
      onLock: () {
        _service.lock();
        setState(() => _unlocked = false);
      },
    );
  }

  Future<bool> _confirmReset(BuildContext context) async {
    // v2.13.2 (#2) — pattern destructif M3 : autofocus Annuler + rouge plein.
    final res = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Réinitialiser le coffre ?'),
        content: const Text(
          'Tous les fichiers chiffrés seront supprimés. '
          'Cette action est irréversible.',
        ),
        actions: [
          TextButton(
            autofocus: true,
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            // C-C8 — style destructif canonique (kDangerRed). `cs.errorContainer`
            // rendait le bouton pâle : le changelog d'explorer_dialogs.dart
            // documente l'abandon de ce pattern, mais l'écran le plus
            // destructif de l'app l'avait conservé.
            style: dangerFilledButtonStyle(),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Réinitialiser'),
          ),
        ],
      ),
    );
    return res ?? false;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Setup
// ─────────────────────────────────────────────────────────────────────────────

class _SetupScreen extends StatefulWidget {
  final VoidCallback onCreated;
  const _SetupScreen({required this.onCreated});

  @override
  State<_SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<_SetupScreen> {
  final _pwd1 = TextEditingController();
  final _pwd2 = TextEditingController();
  bool _busy = false;
  bool _showPwd = false;
  String? _error;

  @override
  void dispose() {
    _scrubPasswordController(_pwd1);
    _scrubPasswordController(_pwd2);
    _pwd1.dispose();
    _pwd2.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    // F8 : garde re-entrante (tap+Enter parallèle = 2 dérivations Argon2
    // simultanées = OOM Redmi 9C 3GB).
    if (_busy) return;
    setState(() {
      _error = null;
    });
    final p1 = _pwd1.text;
    final p2 = _pwd2.text;
    if (p1.length < 8) {
      setState(() => _error = 'Mot de passe : 8 caractères minimum');
      return;
    }
    if (p1 != p2) {
      setState(() => _error = 'Les mots de passe ne correspondent pas');
      return;
    }
    setState(() => _busy = true);

    // Overlay modal pendant la dérivation PBKDF2 + setup (1-3s sur S9).
    // Même si la dérivation est désormais en Isolate, on affiche un retour
    // visuel explicite — plus intuitif que juste un spinner sur le bouton.
    final snack = SnackTarget.of(context, stillWanted: () => mounted);
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const AlertDialog(
        content: SizedBox(
          height: 110,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text('Création du coffre…', style: TextStyle(fontSize: 13)),
              SizedBox(height: 6),
              Text(
                'Optimisation pour votre appareil',
                style: TextStyle(fontSize: 11, color: Colors.grey),
              ),
            ],
          ),
        ),
      ),
    );

    try {
      await VaultService.instance.setupWithPassword(p1);
      if (!mounted) return;
      // Ferme l'overlay
      Navigator.of(context, rootNavigator: true).pop();
      // Snackbar de confirmation (auto-dismiss)
      snack.info('✓ Coffre fort créé', duration: const Duration(seconds: 2));
      widget.onCreated();
    } catch (e) {
      if (!mounted) return;
      Navigator.of(context, rootNavigator: true).pop();
      setState(() {
        _error = 'Erreur : $e';
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Créer un coffre fort')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Icon(Icons.shield_outlined, size: 56),
          const SizedBox(height: 12),
          const Text(
            'Le coffre chiffre vos fichiers avec AES-256-GCM. '
            'Le mot de passe est dérivé localement (Argon2id, paramètres '
            'auto-calibrés pour cet appareil — 1 à 3 secondes) et n\'est '
            'jamais stocké.',
            style: TextStyle(fontSize: 13),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 20),
          TextField(
            controller: _pwd1,
            obscureText: !_showPwd,
            enableSuggestions: false,
            autocorrect: false,
            autofillHints: const <String>[],
            keyboardType: TextInputType.visiblePassword,
            // F15 v2.13.0 — block selection/copy quand masqué.
            enableInteractiveSelection: _showPwd,
            decoration: InputDecoration(
              labelText: 'Mot de passe',
              helperText: 'Minimum 8 caractères',
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                icon: Icon(
                  _showPwd ? Icons.visibility_off : Icons.visibility,
                  size: 20,
                ),
                tooltip: _showPwd ? 'Masquer' : 'Afficher',
                onPressed: () => setState(() => _showPwd = !_showPwd),
              ),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _pwd2,
            obscureText: !_showPwd,
            enableSuggestions: false,
            autocorrect: false,
            autofillHints: const <String>[],
            keyboardType: TextInputType.visiblePassword,
            enableInteractiveSelection: _showPwd,
            decoration: const InputDecoration(
              labelText: 'Confirmer',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.lock_outline),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(
              _error!,
              // v2.13.2 (#2) — cs.error au lieu de Colors.red hardcodé.
              style: TextStyle(
                color: Theme.of(context).colorScheme.error,
                fontSize: 12,
              ),
            ),
          ],
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: _busy ? null : _create,
            icon: _busy
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.check),
            label: Text(_busy ? 'Création…' : 'Créer le coffre'),
          ),
          const SizedBox(height: 24),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.amber.withValues(alpha: 0.10),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Colors.amber.withValues(alpha: 0.4)),
            ),
            child: const Row(
              children: [
                Icon(Icons.warning_amber, color: Colors.amber, size: 18),
                SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Aucune récupération possible : si vous oubliez le mot de passe, '
                    'les fichiers chiffrés seront irrécupérables.',
                    style: TextStyle(fontSize: 11),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unlock
// ─────────────────────────────────────────────────────────────────────────────

class _UnlockScreen extends StatefulWidget {
  final VoidCallback onUnlocked;
  final VoidCallback onReset;
  const _UnlockScreen({required this.onUnlocked, required this.onReset});

  @override
  State<_UnlockScreen> createState() => _UnlockScreenState();
}

class _UnlockScreenState extends State<_UnlockScreen> {
  final _pwd = TextEditingController();
  bool _busy = false;
  bool _showPwd = false;
  String? _error;

  @override
  void dispose() {
    _scrubPasswordController(_pwd);
    _pwd.dispose();
    super.dispose();
  }

  Future<void> _unlock() async {
    // F8 : garde re-entrante (onSubmitted + onPressed = 2 Argon2id //).
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final ok = await VaultService.instance.unlockWithPassword(_pwd.text);
      if (!mounted) return;
      if (ok) {
        widget.onUnlocked();
      } else {
        setState(() {
          _error = 'Mot de passe incorrect';
          _busy = false;
        });
      }
    } on StateError catch (e) {
      // Verrouillage temporaire après trop d'échecs.
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Coffre fort')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const SizedBox(height: 20),
          const Icon(Icons.lock_outline, size: 56),
          const SizedBox(height: 16),
          const Text(
            'Coffre verrouillé',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 24),
          TextField(
            controller: _pwd,
            obscureText: !_showPwd,
            autofocus: true,
            enableSuggestions: false,
            autocorrect: false,
            keyboardType: TextInputType.visiblePassword,
            onSubmitted: (_) => _unlock(),
            decoration: InputDecoration(
              labelText: 'Mot de passe',
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                icon: Icon(
                  _showPwd ? Icons.visibility_off : Icons.visibility,
                  size: 20,
                ),
                tooltip: _showPwd ? 'Masquer' : 'Afficher',
                onPressed: () => setState(() => _showPwd = !_showPwd),
              ),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(
              _error!,
              // v2.13.2 (#2) — cs.error au lieu de Colors.red hardcodé.
              style: TextStyle(
                color: Theme.of(context).colorScheme.error,
                fontSize: 12,
              ),
            ),
          ],
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : _unlock,
            icon: _busy
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.lock_open),
            label: const Text('Déverrouiller'),
          ),
          const SizedBox(height: 32),
          TextButton.icon(
            icon: const Icon(Icons.delete_forever_outlined, color: Colors.red),
            label: const Text(
              'Réinitialiser le coffre',
              style: TextStyle(color: Colors.red),
            ),
            onPressed: widget.onReset,
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content (déverrouillé)
// ─────────────────────────────────────────────────────────────────────────────

class _VaultContent extends StatefulWidget {
  final VaultService service;
  final VoidCallback onLock;
  const _VaultContent({required this.service, required this.onLock});

  @override
  State<_VaultContent> createState() => _VaultContentState();
}

class _VaultContentState extends State<_VaultContent> {
  List<File> _files = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    // `_refresh` est appelé après des opérations longues (import de plusieurs
    // fichiers, suppression, restauration) que l'utilisateur peut avoir quittées
    // entre-temps. Le `mounted` du milieu ne protégeait que le SECOND
    // `setState` ; le premier partait avant tout `await`, donc sur un State
    // potentiellement démonté depuis longtemps.
    if (!mounted) return;
    setState(() => _loading = true);
    final files = await widget.service.listFiles();
    if (!mounted) return;
    setState(() {
      _files = files;
      _loading = false;
    });
  }

  Future<void> _import() async {
    final snack = SnackTarget.of(context, stillWanted: () => mounted);
    final paths = await RftPickerScreen.pickMany(
      context,
      title: 'Importer dans le coffre',
    );
    if (paths == null || paths.isEmpty) return;
    int ok = 0, skip = 0, fail = 0;
    for (final p in paths) {
      try {
        await widget.service.importFileSafe(File(p));
        ok++;
      } on FileSystemException {
        if (!mounted) return;
        final overwrite = await showDialog<bool>(
          context: context,
          builder: (_) => AlertDialog(
            title: const Text('Fichier déjà présent'),
            content: Text(
              '"${PathSafe.basename(p)}" existe déjà dans le coffre. Écraser ?',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Garder'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Écraser'),
              ),
            ],
          ),
        );
        if (overwrite == true) {
          try {
            await widget.service.importFileSafe(File(p), overwrite: true);
            ok++;
          } catch (_) {
            fail++;
          }
        } else {
          skip++;
        }
      } catch (_) {
        fail++;
      }
    }
    await _refresh();
    final parts = <String>[
      if (ok > 0) '$ok chiffré${ok > 1 ? 's' : ''}',
      if (skip > 0) '$skip ignoré${skip > 1 ? 's' : ''}',
      if (fail > 0) '$fail erreur${fail > 1 ? 's' : ''}',
    ];
    // Un import dont une partie a échoué n'était signalé que par un fragment
    // « n erreur(s) » noyé dans un bandeau neutre.
    if (fail > 0) {
      snack.error(parts.join(' · '));
    } else {
      snack.info(parts.join(' · '));
    }
  }

  Future<void> _share(File enc) async {
    final snack = SnackTarget.of(context, stillWanted: () => mounted);
    try {
      final tmp = await widget.service.decryptToTemp(enc);
      // Le déchiffrement peut durer sur un gros fichier. Si l'utilisateur a
      // quitté le coffre entre-temps, `dispose()` l'a VERROUILLÉ (V-H1) : faire
      // surgir la feuille de partage système par-dessus l'écran où il se
      // trouve, avec un fichier du coffre en clair dedans, va exactement à
      // l'encontre de ce verrouillage. Le clair reste dans `cache/vault_decrypt`
      // et sera effacé par `purgeTempDecrypted()` au prochain boot ou passage
      // en arrière-plan. Jumeau du même défaut corrigé dans `ocr_screen` et
      // `zip_viewer_screen` ; celui-ci avait été manqué, et c'était le pire
      // des trois.
      if (!mounted) return;
      await Share.shareXFiles([XFile(tmp.path)]);
    } catch (e) {
      snack.error('Erreur : $e');
    }
  }

  Future<void> _export(File enc) async {
    final snack = SnackTarget.of(context, stillWanted: () => mounted);
    final destDir = await FilePicker.getDirectoryPath();
    if (destDir == null) return;
    try {
      await widget.service.exportFile(enc, destDir);
    } on FileSystemException {
      // V-M2 — un fichier de l'utilisateur porte déjà ce nom dans le dossier
      // choisi. Avant, il était remplacé sans un mot, et le SnackBar de
      // succès annonçait le nom du document qu'on venait de détruire.
      if (!mounted) return;
      final overwrite = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('Fichier déjà présent'),
          content: Text(
            '"${_displayName(enc)}" existe déjà dans le dossier de '
            'destination. L\'écraser ?',
          ),
          actions: [
            TextButton(
              autofocus: true,
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Garder'),
            ),
            FilledButton(
              style: dangerFilledButtonStyle(),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Écraser'),
            ),
          ],
        ),
      );
      if (overwrite != true) {
        snack.info('Export annulé — fichier conservé.');
        return;
      }
      try {
        final out = await widget.service.exportFile(
          enc,
          destDir,
          overwrite: true,
        );
        snack.info('Exporté : ${PathSafe.basename(out.path)}');
      } catch (e) {
        snack.error('Erreur : $e');
      }
      return;
    } catch (e) {
      snack.error('Erreur : $e');
      return;
    }
    snack.info('Exporté : ${_displayName(enc)}');
  }

  Future<void> _delete(File enc) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) {
        // v2.13.2 (#2/S3) — pattern destructif M3.
        return AlertDialog(
          title: const Text('Supprimer du coffre'),
          content: Text(
            'Supprimer "${_displayName(enc)}" ? Action irréversible.',
          ),
          actions: [
            TextButton(
              autofocus: true,
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Annuler'),
            ),
            FilledButton(
              style: dangerFilledButtonStyle(),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Supprimer'),
            ),
          ],
        );
      },
    );
    if (confirm != true) return;
    await widget.service.deleteFile(enc);
    _refresh();
  }

  // ── Importer dossier ──────────────────────────────────────────────────────

  Future<void> _importFolder() async {
    final snack = SnackTarget.of(context, stillWanted: () => mounted);
    // Picker custom RFT — UX cohérente avec le reste de l'app (raccourcis
    // colorés Téléchargements/Photos/Vidéos/Documents/WhatsApp + tous les
    // dossiers du stockage + bouton "Parcourir un autre dossier" SAF).
    final folderPath = await RftPickerScreen.pickFolder(
      context,
      title: 'Choisir un dossier à chiffrer',
    );
    if (folderPath == null || !mounted) return;

    final imported = await Navigator.push<int>(
      context,
      MaterialPageRoute(
        builder: (_) => VaultImportFolderScreen(
          folderPath: folderPath,
          service: widget.service,
        ),
      ),
    );
    if (!mounted) return;
    if (imported != null && imported > 0) {
      await _refresh();
      // Snackbar déjà affiché par l'écran enfant — pas de double notification.
    } else {
      snack.clear();
    }
  }

  // ── Exporter le coffre (.rftvault) ────────────────────────────────────────

  Future<void> _exportBackup() async {
    if (_files.isEmpty) {
      showFloatingSnack(context, 'Coffre vide — rien à exporter.');
      return;
    }
    final pwd = await _askPassword(
      title: 'Exporter le coffre',
      info:
          'Choisissez un mot de passe pour la sauvegarde. '
          'Il sera nécessaire pour la restaurer.\n\n'
          '⚠ Distinct du mot de passe principal — choisissez-le bien : '
          'sans lui, la sauvegarde est irrécupérable.',
      confirm: true,
      submitLabel: 'Exporter',
    );
    if (pwd == null || !mounted) return;

    final snack = SnackTarget.of(context, stillWanted: () => mounted);
    double progress = 0;
    final progressDialog = _showProgressDialog(
      title: 'Export en cours…',
      progressOf: () => progress,
    );

    try {
      final out = await widget.service.exportToBackup(
        exportPassword: pwd,
        onProgress: (p) {
          progress = p;
          progressDialog.refresh();
        },
      );
      if (!mounted) return;
      progressDialog.close();
      // Partage du fichier produit (l'utilisateur choisit où le sauver).
      await Share.shareXFiles([
        XFile(out.path, mimeType: 'application/octet-stream'),
      ], subject: 'Sauvegarde Read Files Tech');
    } catch (e) {
      if (mounted) progressDialog.close();
      snack.error('Erreur : $e');
    }
  }

  // ── Restaurer un coffre depuis un .rftvault ───────────────────────────────

  Future<void> _restoreBackup() async {
    final snack = SnackTarget.of(context, stillWanted: () => mounted);
    // Picker custom RFT cohérent avec le reste de l'app — raccourcis colorés
    // (Téléchargements, Documents, Files Tech) où l'utilisateur stocke
    // typiquement ses sauvegardes .rftvault.
    final path = await RftPickerScreen.pickOne(
      context,
      title: 'Choisir une sauvegarde .rftvault',
    );
    if (path == null || !mounted) return;
    if (!path.toLowerCase().endsWith('.rftvault')) {
      final cont = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('Extension inattendue'),
          content: const Text(
            'Le fichier ne se termine pas par .rftvault. Continuer quand même ?',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Annuler'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Continuer'),
            ),
          ],
        ),
      );
      if (cont != true || !mounted) return;
    }

    final pwd = await _askPassword(
      title: 'Restaurer un coffre',
      info:
          'Entrez le mot de passe utilisé lors de l\'export.\n'
          'Les fichiers déjà présents dans le coffre actuel '
          'seront ignorés (pas écrasés).',
      confirm: false,
      submitLabel: 'Restaurer',
    );
    if (pwd == null || !mounted) return;

    double progress = 0;
    final progressDialog = _showProgressDialog(
      title: 'Restauration en cours…',
      progressOf: () => progress,
    );

    try {
      final result = await widget.service.restoreFromBackup(
        backupFile: File(path),
        exportPassword: pwd,
        onProgress: (p) {
          progress = p;
          progressDialog.refresh();
        },
      );
      if (mounted) progressDialog.close();
      await _refresh();
      final parts = <String>[
        '${result.restored} restauré${result.restored > 1 ? "s" : ""}',
        if (result.skipped > 0)
          '${result.skipped} ignoré${result.skipped > 1 ? "s" : ""} (homonyme)',
      ];
      snack.info(parts.join(' · '));
    } catch (e) {
      if (mounted) progressDialog.close();
      // Tampering ou mauvais password → message neutre (pas d'oracle).
      snack.error(
        e is StateError
            ? e.message
            : 'Mot de passe incorrect ou fichier invalide',
      );
    }
  }

  /// Affiche un dialog modal avec progress bar dont la valeur est lue à la
  /// demande via [progressOf]. Retourne un controller permettant `refresh()`
  /// (re-build) et `close()`.
  _ProgressDialog _showProgressDialog({
    required String title,
    required double Function() progressOf,
  }) {
    final controller = _ProgressDialog();
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) {
        controller._ctx = ctx;
        return StatefulBuilder(
          builder: (_, setSt) {
            controller._setSt = setSt;
            return AlertDialog(
              content: SizedBox(
                height: 110,
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(title, style: const TextStyle(fontSize: 13)),
                    const SizedBox(height: 14),
                    // U7 v2.13.0 — Semantics.value parle TalkBack en %
                    // (sinon "en cours" générique, l'utilisateur aveugle
                    // ne sait pas si ça avance).
                    Semantics(
                      value: '${(progressOf() * 100).round()}%',
                      child: LinearProgressIndicator(value: progressOf()),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      '${(progressOf() * 100).round()}%',
                      style: const TextStyle(fontSize: 11),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
    return controller;
  }

  /// Demande un mot de passe à l'utilisateur (avec confirmation optionnelle).
  /// Retourne null si annulé.
  Future<String?> _askPassword({
    required String title,
    required String info,
    required bool confirm,
    required String submitLabel,
  }) async {
    return showDialog<String>(
      context: context,
      builder: (_) => _PasswordDialog(
        title: title,
        info: info,
        confirm: confirm,
        submitLabel: submitLabel,
      ),
    );
  }

  String _displayName(File f) {
    final n = PathSafe.basename(f.path);
    return n.endsWith('.enc') ? n.substring(0, n.length - 4) : n;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Coffre fort'),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            tooltip: 'Plus d\'actions',
            onSelected: (v) {
              switch (v) {
                case 'folder':
                  _importFolder();
                  break;
                case 'export':
                  _exportBackup();
                  break;
                case 'restore':
                  _restoreBackup();
                  break;
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(
                value: 'folder',
                child: ListTile(
                  leading: Icon(Icons.folder_copy_outlined),
                  title: Text('Importer un dossier'),
                  subtitle: Text(
                    'Chiffrement batch',
                    style: TextStyle(fontSize: 11),
                  ),
                ),
              ),
              PopupMenuDivider(),
              PopupMenuItem(
                value: 'export',
                child: ListTile(
                  leading: Icon(Icons.archive_outlined),
                  title: Text('Exporter le coffre'),
                  subtitle: Text(
                    'Sauvegarde .rftvault',
                    style: TextStyle(fontSize: 11),
                  ),
                ),
              ),
              PopupMenuItem(
                value: 'restore',
                child: ListTile(
                  leading: Icon(Icons.unarchive_outlined),
                  title: Text('Restaurer un coffre'),
                  subtitle: Text(
                    'Depuis .rftvault',
                    style: TextStyle(fontSize: 11),
                  ),
                ),
              ),
            ],
          ),
          IconButton(
            icon: const Icon(Icons.lock_outline),
            tooltip: 'Verrouiller',
            onPressed: widget.onLock,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _files.isEmpty
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.shield_outlined,
                    size: 64,
                    color: Colors.grey.shade400,
                  ),
                  const SizedBox(height: 12),
                  const Text('Coffre vide'),
                  const SizedBox(height: 4),
                  Text(
                    'Importez un fichier pour le chiffrer',
                    style: TextStyle(color: Colors.grey.shade600, fontSize: 12),
                  ),
                ],
              ),
            )
          : ListView.builder(
              itemCount: _files.length,
              itemBuilder: (_, i) {
                final f = _files[i];
                final size = f.lengthSync();
                return ListTile(
                  leading: const Icon(Icons.lock, color: Colors.green),
                  title: Text(_displayName(f), overflow: TextOverflow.ellipsis),
                  subtitle: Text(_fmt(size)),
                  trailing: PopupMenuButton<String>(
                    onSelected: (v) {
                      if (v == 'share') _share(f);
                      if (v == 'export') _export(f);
                      if (v == 'delete') _delete(f);
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(
                        value: 'share',
                        child: ListTile(
                          leading: Icon(Icons.share),
                          title: Text('Partager (déchiffré)'),
                        ),
                      ),
                      PopupMenuItem(
                        value: 'export',
                        child: ListTile(
                          leading: Icon(Icons.download_outlined),
                          title: Text('Exporter…'),
                        ),
                      ),
                      PopupMenuItem(
                        value: 'delete',
                        child: ListTile(
                          leading: Icon(
                            Icons.delete_outline,
                            color: Colors.red,
                          ),
                          title: Text('Supprimer'),
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _import,
        icon: const Icon(Icons.add),
        label: const Text('Importer'),
      ),
    );
  }

  /// C-C9 (audit 2026-08-02) — trois implémentations concurrentes du
  /// formatage de tailles cohabitaient, dont deux copies inline
  /// octet-pour-octet identiques de celle-ci. Résultat visible : dans la même
  /// fonctionnalité « coffre », un écran affichait « 1.5 Ko » (FormatUtils,
  /// unités FR) et l'autre « 1.5 MB ». Une seule source désormais.
  String _fmt(int b) => FormatUtils.bytesStorage(b);
}

/// Controller de progress dialog : permet à l'appelant d'appeler `refresh()`
/// pour rebuild la barre, et `close()` pour fermer le dialog modal.
class _ProgressDialog {
  BuildContext? _ctx;
  StateSetter? _setSt;

  /// Un dialogue ne se ferme qu'une fois. `_exportBackup` appelait `close()`
  /// en cas de succès (:825) PUIS de nouveau dans le `catch` (:832) si le
  /// partage qui suit échouait — le dialogue n'étant plus là, le second
  /// `close()` dépilait la route suivante, c'est-à-dire **l'écran du coffre
  /// lui-même**. Une erreur de partage éjectait donc l'utilisateur du coffre.
  bool _closed = false;

  void refresh() {
    // Le job de fond continue de rapporter sa progression après la fermeture
    // du dialogue (retour arrière Android, qui ferme même un dialogue
    // `barrierDismissible: false`) : sans cette garde, `setState` était appelé
    // sur le `StatefulBuilder` démonté.
    if (_closed) return;
    _setSt?.call(() {});
  }

  void close() {
    if (_closed) return;
    _closed = true;
    final ctx = _ctx;
    _ctx = null;
    _setSt = null;
    // `canPop()` répondait « oui » dès que le Navigator avait quoi que ce soit
    // à dépiler — y compris quand notre dialogue était déjà parti. Le bon test
    // est de savoir si CE contexte est encore monté.
    if (ctx == null || !ctx.mounted) return;
    Navigator.of(ctx).pop();
  }
}

/// Dialog modal de saisie de mot de passe.
/// Avec [confirm] true : 2e champ "Confirmer" + validation matching.
class _PasswordDialog extends StatefulWidget {
  final String title;
  final String info;
  final bool confirm;
  final String submitLabel;

  const _PasswordDialog({
    required this.title,
    required this.info,
    required this.confirm,
    required this.submitLabel,
  });

  @override
  State<_PasswordDialog> createState() => _PasswordDialogState();
}

class _PasswordDialogState extends State<_PasswordDialog> {
  final _pwd1 = TextEditingController();
  final _pwd2 = TextEditingController();
  bool _show = false;
  String? _error;

  @override
  void dispose() {
    _scrubPasswordController(_pwd1);
    _scrubPasswordController(_pwd2);
    _pwd1.dispose();
    _pwd2.dispose();
    super.dispose();
  }

  void _submit() {
    final p1 = _pwd1.text;
    if (p1.length < 8) {
      setState(() => _error = 'Minimum 8 caractères');
      return;
    }
    if (widget.confirm && p1 != _pwd2.text) {
      setState(() => _error = 'Les mots de passe ne correspondent pas');
      return;
    }
    Navigator.of(context).pop(p1);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            widget.info,
            style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _pwd1,
            obscureText: !_show,
            autofocus: true,
            enableSuggestions: false,
            autocorrect: false,
            autofillHints: const <String>[], // disable Android Autofill
            keyboardType: TextInputType.visiblePassword,
            // F15 v2.13.0 — Désactive la sélection / copie quand le password
            // est masqué : empêche un long-press → "Tout sélectionner" →
            // copier vers le presse-papier (qui sur Android 13- peut être
            // capté par un clipboard manager tiers).
            enableInteractiveSelection: _show,
            onSubmitted: widget.confirm ? null : (_) => _submit(),
            decoration: InputDecoration(
              labelText: 'Mot de passe',
              helperText: widget.confirm ? 'Minimum 8 caractères' : null,
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                icon: Icon(
                  _show ? Icons.visibility_off : Icons.visibility,
                  size: 20,
                ),
                tooltip: _show ? 'Masquer' : 'Afficher',
                onPressed: () => setState(() => _show = !_show),
              ),
            ),
          ),
          if (widget.confirm) ...[
            const SizedBox(height: 12),
            TextField(
              controller: _pwd2,
              obscureText: !_show,
              enableSuggestions: false,
              autocorrect: false,
              autofillHints: const <String>[], // disable Android Autofill
              keyboardType: TextInputType.visiblePassword,
              // F15 v2.13.0 — cf TextField précédent.
              enableInteractiveSelection: _show,
              onSubmitted: (_) => _submit(),
              decoration: const InputDecoration(
                labelText: 'Confirmer',
                border: OutlineInputBorder(),
                prefixIcon: Icon(Icons.lock_outline),
              ),
            ),
          ],
          if (_error != null) ...[
            const SizedBox(height: 10),
            Text(
              _error!,
              // v2.13.2 (#2) — cs.error au lieu de Colors.red hardcodé.
              style: TextStyle(
                color: Theme.of(context).colorScheme.error,
                fontSize: 12,
              ),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Annuler'),
        ),
        FilledButton(onPressed: _submit, child: Text(widget.submitLabel)),
      ],
    );
  }
}
