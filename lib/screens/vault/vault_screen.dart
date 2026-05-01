import 'dart:async';
import 'dart:io';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:share_plus/share_plus.dart';
import '../../services/secure_window.dart';
import '../../services/vault_service.dart';
import '../../widgets/rft_picker_screen.dart';
import 'vault_import_folder_screen.dart';

class VaultScreen extends StatefulWidget {
  const VaultScreen({super.key});

  @override
  State<VaultScreen> createState() => _VaultScreenState();
}

class _VaultScreenState extends State<VaultScreen> with WidgetsBindingObserver {
  final _service = VaultService();
  bool _checking = true;
  bool _unlocked = false;
  bool _setup = false;

  /// Délai d'auto-lock après `paused`. Permet à un picker natif (SAF, share
  /// sheet, FilePicker) qui pause brièvement l'app de se terminer sans
  /// déclencher le lock. Pattern standard des password managers (Bitwarden,
  /// KeePassDX).
  static const _autoLockDelay = Duration(seconds: 30);

  /// Timer programmant le lock différé. Annulé sur `resumed`.
  Timer? _pendingLockTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Au boot du coffre, purger d'éventuels fichiers déchiffrés laissés.
    _service.purgeTempDecrypted();
    _bootstrap();
  }

  @override
  void dispose() {
    _pendingLockTimer?.cancel();
    _pendingLockTimer = null;
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
    SecureWindow.disable();
    if (mounted) setState(() => _unlocked = false);
  }

  Future<void> _bootstrap() async {
    final setup = await _service.isSetup();
    if (!mounted) return;
    setState(() { _setup = setup; _checking = false; _unlocked = _service.isUnlocked; });
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!_setup) {
      return _SetupScreen(onCreated: () async {
        SecureWindow.enable();
        setState(() { _setup = true; _unlocked = true; });
      });
    }
    if (!_unlocked) {
      return _UnlockScreen(
        onUnlocked: () {
          SecureWindow.enable();
          setState(() => _unlocked = true);
        },
        onReset: () async {
          final ok = await _confirmReset(context);
          if (ok) {
            await _service.reset();
            SecureWindow.disable();
            if (mounted) setState(() { _setup = false; _unlocked = false; });
          }
        },
      );
    }
    return _VaultContent(service: _service, onLock: () {
      _service.lock();
      SecureWindow.disable();
      setState(() => _unlocked = false);
    });
  }

  Future<bool> _confirmReset(BuildContext context) async {
    final res = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Réinitialiser le coffre ?'),
        content: const Text('Tous les fichiers chiffrés seront supprimés. '
            'Cette action est irréversible.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
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
    _scrubPwd(_pwd1);
    _scrubPwd(_pwd2);
    _pwd1.dispose();
    _pwd2.dispose();
    super.dispose();
  }

  static void _scrubPwd(TextEditingController c) {
    if (c.text.isNotEmpty) c.text = '\x00' * c.text.length;
    c.text = '';
  }

  Future<void> _create() async {
    setState(() { _error = null; });
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
    final messenger = ScaffoldMessenger.of(context);
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
              Text('Création du coffre…',
                  style: TextStyle(fontSize: 13)),
              SizedBox(height: 6),
              Text('Optimisation pour votre appareil',
                  style: TextStyle(fontSize: 11, color: Colors.grey)),
            ],
          ),
        ),
      ),
    );

    try {
      await VaultService().setupWithPassword(p1);
      if (!mounted) return;
      // Ferme l'overlay
      Navigator.of(context, rootNavigator: true).pop();
      // Snackbar de confirmation (auto-dismiss)
      messenger.showSnackBar(const SnackBar(
        content: Text('✓ Coffre fort créé'),
        duration: Duration(seconds: 2),
      ));
      widget.onCreated();
    } catch (e) {
      if (!mounted) return;
      Navigator.of(context, rootNavigator: true).pop();
      setState(() { _error = 'Erreur : $e'; _busy = false; });
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
            'Le mot de passe est dérivé localement (PBKDF2 600 000 itérations) '
            'et n\'est jamais stocké.',
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
            decoration: InputDecoration(
              labelText: 'Mot de passe',
              helperText: 'Minimum 8 caractères',
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                icon: Icon(_showPwd ? Icons.visibility_off : Icons.visibility,
                    size: 20),
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
            decoration: const InputDecoration(
              labelText: 'Confirmer',
              border: OutlineInputBorder(),
              prefixIcon: Icon(Icons.lock_outline),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(_error!, style: const TextStyle(color: Colors.red, fontSize: 12)),
          ],
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: _busy ? null : _create,
            icon: _busy
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
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
            child: const Row(children: [
              Icon(Icons.warning_amber, color: Colors.amber, size: 18),
              SizedBox(width: 8),
              Expanded(child: Text(
                'Aucune récupération possible : si vous oubliez le mot de passe, '
                'les fichiers chiffrés seront irrécupérables.',
                style: TextStyle(fontSize: 11),
              )),
            ]),
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
    if (_pwd.text.isNotEmpty) _pwd.text = '\x00' * _pwd.text.length;
    _pwd.text = '';
    _pwd.dispose();
    super.dispose();
  }

  Future<void> _unlock() async {
    setState(() { _busy = true; _error = null; });
    try {
      final ok = await VaultService().unlockWithPassword(_pwd.text);
      if (!mounted) return;
      if (ok) {
        widget.onUnlocked();
      } else {
        setState(() { _error = 'Mot de passe incorrect'; _busy = false; });
      }
    } on StateError catch (e) {
      // Verrouillage temporaire après trop d'échecs.
      if (!mounted) return;
      setState(() { _error = e.message; _busy = false; });
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
          const Text('Coffre verrouillé',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
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
                icon: Icon(_showPwd ? Icons.visibility_off : Icons.visibility,
                    size: 20),
                tooltip: _showPwd ? 'Masquer' : 'Afficher',
                onPressed: () => setState(() => _showPwd = !_showPwd),
              ),
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: Colors.red, fontSize: 12)),
          ],
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _busy ? null : _unlock,
            icon: _busy
                ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.lock_open),
            label: const Text('Déverrouiller'),
          ),
          const SizedBox(height: 32),
          TextButton.icon(
            icon: const Icon(Icons.delete_forever_outlined, color: Colors.red),
            label: const Text('Réinitialiser le coffre',
                style: TextStyle(color: Colors.red)),
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
  void initState() { super.initState(); _refresh(); }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final files = await widget.service.listFiles();
    if (!mounted) return;
    setState(() { _files = files; _loading = false; });
  }

  Future<void> _import() async {
    final messenger = ScaffoldMessenger.of(context);
    final paths = await RftPickerScreen.pickMany(context,
        title: 'Importer dans le coffre');
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
            content: Text('"${p.split(RegExp(r'[/\\\\]')).last}" existe déjà dans le coffre. Écraser ?'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Garder')),
              FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Écraser')),
            ],
          ),
        );
        if (overwrite == true) {
          try {
            await widget.service.importFileSafe(File(p), overwrite: true);
            ok++;
          } catch (_) { fail++; }
        } else { skip++; }
      } catch (_) { fail++; }
    }
    await _refresh();
    if (!mounted) return;
    final parts = <String>[
      if (ok > 0)   '$ok chiffré${ok > 1 ? 's' : ''}',
      if (skip > 0) '$skip ignoré${skip > 1 ? 's' : ''}',
      if (fail > 0) '$fail erreur${fail > 1 ? 's' : ''}',
    ];
    messenger.showSnackBar(SnackBar(content: Text(parts.join(' · '))));
  }

  Future<void> _share(File enc) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final tmp = await widget.service.decryptToTemp(enc);
      await Share.shareXFiles([XFile(tmp.path)]);
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _export(File enc) async {
    final messenger = ScaffoldMessenger.of(context);
    final destDir = await FilePicker.platform.getDirectoryPath();
    if (destDir == null) return;
    try {
      final out = await widget.service.exportFile(enc, destDir);
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(content: Text('Exporté : ${out.path.split('/').last}')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  Future<void> _delete(File enc) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Supprimer du coffre'),
        content: Text('Supprimer "${_displayName(enc)}" ? Action irréversible.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Supprimer')),
        ],
      ),
    );
    if (confirm != true) return;
    await widget.service.deleteFile(enc);
    _refresh();
  }

  // ── Importer dossier ──────────────────────────────────────────────────────

  Future<void> _importFolder() async {
    final messenger = ScaffoldMessenger.of(context);
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
      messenger.hideCurrentSnackBar();
    }
  }

  // ── Exporter le coffre (.rftvault) ────────────────────────────────────────

  Future<void> _exportBackup() async {
    if (_files.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Coffre vide — rien à exporter.'),
      ));
      return;
    }
    final pwd = await _askPassword(
      title: 'Exporter le coffre',
      info: 'Choisissez un mot de passe pour la sauvegarde. '
          'Il sera nécessaire pour la restaurer.\n\n'
          '⚠ Distinct du mot de passe principal — choisissez-le bien : '
          'sans lui, la sauvegarde est irrécupérable.',
      confirm: true,
      submitLabel: 'Exporter',
    );
    if (pwd == null || !mounted) return;

    final messenger = ScaffoldMessenger.of(context);
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
      await Share.shareXFiles(
        [XFile(out.path, mimeType: 'application/octet-stream')],
        subject: 'Sauvegarde Read Files Tech',
      );
    } catch (e) {
      if (!mounted) return;
      progressDialog.close();
      messenger.showSnackBar(SnackBar(content: Text('Erreur : $e')));
    }
  }

  // ── Restaurer un coffre depuis un .rftvault ───────────────────────────────

  Future<void> _restoreBackup() async {
    final messenger = ScaffoldMessenger.of(context);
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
              'Le fichier ne se termine pas par .rftvault. Continuer quand même ?'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Annuler')),
            FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Continuer')),
          ],
        ),
      );
      if (cont != true || !mounted) return;
    }

    final pwd = await _askPassword(
      title: 'Restaurer un coffre',
      info: 'Entrez le mot de passe utilisé lors de l\'export.\n'
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
      if (!mounted) return;
      progressDialog.close();
      await _refresh();
      if (!mounted) return;
      final parts = <String>[
        '${result.restored} restauré${result.restored > 1 ? "s" : ""}',
        if (result.skipped > 0)
          '${result.skipped} ignoré${result.skipped > 1 ? "s" : ""} (homonyme)',
      ];
      messenger.showSnackBar(SnackBar(content: Text(parts.join(' · '))));
    } catch (e) {
      if (!mounted) return;
      progressDialog.close();
      // Tampering ou mauvais password → message neutre (pas d'oracle).
      messenger.showSnackBar(SnackBar(
        content: Text(e is StateError
            ? e.message
            : 'Mot de passe incorrect ou fichier invalide'),
      ));
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
        return StatefulBuilder(builder: (_, setSt) {
          controller._setSt = setSt;
          return AlertDialog(
            content: SizedBox(
              height: 110,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(title, style: const TextStyle(fontSize: 13)),
                  const SizedBox(height: 14),
                  LinearProgressIndicator(value: progressOf()),
                  const SizedBox(height: 8),
                  Text('${(progressOf() * 100).round()}%',
                      style: const TextStyle(fontSize: 11)),
                ],
              ),
            ),
          );
        });
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
    final n = f.path.split(RegExp(r'[/\\]')).last;
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
                case 'folder': _importFolder(); break;
                case 'export': _exportBackup(); break;
                case 'restore': _restoreBackup(); break;
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(
                value: 'folder',
                child: ListTile(
                  leading: Icon(Icons.folder_copy_outlined),
                  title: Text('Importer un dossier'),
                  subtitle: Text('Chiffrement batch',
                      style: TextStyle(fontSize: 11)),
                ),
              ),
              PopupMenuDivider(),
              PopupMenuItem(
                value: 'export',
                child: ListTile(
                  leading: Icon(Icons.archive_outlined),
                  title: Text('Exporter le coffre'),
                  subtitle: Text('Sauvegarde .rftvault',
                      style: TextStyle(fontSize: 11)),
                ),
              ),
              PopupMenuItem(
                value: 'restore',
                child: ListTile(
                  leading: Icon(Icons.unarchive_outlined),
                  title: Text('Restaurer un coffre'),
                  subtitle: Text('Depuis .rftvault',
                      style: TextStyle(fontSize: 11)),
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
                      Icon(Icons.shield_outlined, size: 64, color: Colors.grey.shade400),
                      const SizedBox(height: 12),
                      const Text('Coffre vide'),
                      const SizedBox(height: 4),
                      Text('Importez un fichier pour le chiffrer',
                          style: TextStyle(color: Colors.grey.shade600, fontSize: 12)),
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
                          if (v == 'share')  _share(f);
                          if (v == 'export') _export(f);
                          if (v == 'delete') _delete(f);
                        },
                        itemBuilder: (_) => const [
                          PopupMenuItem(value: 'share', child: ListTile(
                              leading: Icon(Icons.share), title: Text('Partager (déchiffré)'))),
                          PopupMenuItem(value: 'export', child: ListTile(
                              leading: Icon(Icons.download_outlined), title: Text('Exporter…'))),
                          PopupMenuItem(value: 'delete', child: ListTile(
                              leading: Icon(Icons.delete_outline, color: Colors.red),
                              title: Text('Supprimer'))),
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

  String _fmt(int b) {
    if (b < 1024) return '$b B';
    if (b < 1024 * 1024) return '${(b / 1024).toStringAsFixed(0)} KB';
    return '${(b / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

/// Controller de progress dialog : permet à l'appelant d'appeler `refresh()`
/// pour rebuild la barre, et `close()` pour fermer le dialog modal.
class _ProgressDialog {
  BuildContext? _ctx;
  StateSetter? _setSt;

  void refresh() => _setSt?.call(() {});

  void close() {
    final ctx = _ctx;
    if (ctx != null && Navigator.of(ctx).canPop()) {
      Navigator.of(ctx).pop();
    }
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
    // Scrub best-effort : remplace le contenu par des null-bytes puis vide.
    // Ne garantit PAS l'effacement RAM (les String Dart sont immuables et
    // non-zeroizables — limitation langage). Mais retire la référence
    // forte du controller, accélérant le GC potentiel.
    _scrub(_pwd1);
    _scrub(_pwd2);
    _pwd1.dispose();
    _pwd2.dispose();
    super.dispose();
  }

  static void _scrub(TextEditingController c) {
    if (c.text.isNotEmpty) {
      c.text = '\x00' * c.text.length;
    }
    c.text = '';
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
          Text(widget.info,
              style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
          const SizedBox(height: 16),
          TextField(
            controller: _pwd1,
            obscureText: !_show,
            autofocus: true,
            enableSuggestions: false,
            autocorrect: false,
            autofillHints: const <String>[], // disable Android Autofill
            keyboardType: TextInputType.visiblePassword,
            onSubmitted: widget.confirm ? null : (_) => _submit(),
            decoration: InputDecoration(
              labelText: 'Mot de passe',
              helperText: widget.confirm ? 'Minimum 8 caractères' : null,
              border: const OutlineInputBorder(),
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                icon: Icon(_show ? Icons.visibility_off : Icons.visibility,
                    size: 20),
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
            Text(_error!,
                style: const TextStyle(color: Colors.red, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Annuler'),
        ),
        FilledButton(
          onPressed: _submit,
          child: Text(widget.submitLabel),
        ),
      ],
    );
  }
}
